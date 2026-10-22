package dev.exposed;

import com.mojang.blaze3d.platform.InputConstants;

import dev.exposed.network.SnapshotPayload;
import dev.exposed.network.SubscribePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class ExposedClient implements ClientModInitializer {
	private static KeyMapping toggleKey;

	@Override
	public void onInitializeClient() {
		ExposedConfig.load();
		ExposedImGui.init();

		KeyMapping.Category category = KeyMapping.Category.register(
				Identifier.fromNamespaceAndPath(ExposedCommon.MOD_ID, "main")
		);
		toggleKey = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key." + ExposedCommon.MOD_ID + ".toggle", InputConstants.Type.KEYBOARD, InputConstants.KEY_I, category)
		);

		ClientPlayNetworking.registerGlobalReceiver(SnapshotPayload.TYPE, (payload, context) ->
				context.client().execute(() -> RemoteFeed.accept(payload))
		);

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			RemoteFeed.clear();
			syncSubscription(client);
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> RemoteFeed.clear());

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (toggleKey.consumeClick()) {
				Profiler.toggle();
				RemoteFeed.clear();
				syncSubscription(client);

				if (Profiler.isEnabled() && !client.hasSingleplayerServer() && !supportsRemote(client) && client.player != null) {
					client.player.sendSystemMessage(Component.literal(
							"[Exposed] This server does not have Exposed installed."
					));
				}
			}
		});

		LevelRenderEvents.BEFORE_GIZMOS.register(ProfilerOverlay::render);

		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (!(screen instanceof PauseScreen)) {
				return;
			}

			Button button = Button.builder(Component.literal("Exposed Info"), pressed ->
					client.setScreenAndShow(new ExposedInfoScreen(screen))
			).bounds(6, scaledHeight - 26, 96, 20).build();

			Screens.getWidgets(screen).add(button);
		});
	}

	private static void syncSubscription(Minecraft client) {
		if (client.getConnection() == null || client.player == null || !supportsRemote(client)) {
			return;
		}

		ClientPlayNetworking.send(new SubscribePayload(Profiler.isEnabled()));
	}

	private static boolean supportsRemote(Minecraft client) {
		return client.getConnection() != null && ClientPlayNetworking.canSend(SubscribePayload.TYPE);
	}
}
