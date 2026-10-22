package dev.exposed.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.exposed.ExposedImGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;

@Mixin(GameRenderer.class)
public final class GameRendererMixin {
	@Inject(method = "render", at = @At("RETURN"))
	private void exposed$renderImGui(CallbackInfo callback) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gameRenderer != null) {
			ExposedImGui.render(mc, mc.gameRenderer.mainRenderTarget());
		}
	}
}
