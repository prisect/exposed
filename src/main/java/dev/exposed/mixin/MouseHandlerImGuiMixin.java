package dev.exposed.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import dev.exposed.ExposedImGui;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerImGuiMixin {
	@Redirect(method = "onButton", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z"))
	private boolean exposed$mouseClicked(Screen screen, MouseButtonEvent event, boolean doubled) {
		if (screen instanceof ChatScreen
				&& ExposedImGui.overlayMouseButton(event.button(), true, event.x(), event.y())) {
			return true;
		}
		return screen.mouseClicked(event, doubled);
	}

	@Redirect(method = "onButton", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;mouseReleased(Lnet/minecraft/client/input/MouseButtonEvent;)Z"))
	private boolean exposed$mouseReleased(Screen screen, MouseButtonEvent event) {
		if (screen instanceof ChatScreen
				&& ExposedImGui.overlayMouseButton(event.button(), false, event.x(), event.y())) {
			return true;
		}
		return screen.mouseReleased(event);
	}

	@Redirect(method = "onScroll", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;mouseScrolled(DDDD)Z"))
	private boolean exposed$mouseScrolled(Screen screen, double mouseX, double mouseY, double horizontal, double vertical) {
		if (screen instanceof ChatScreen
				&& ExposedImGui.overlayMouseScrolled(mouseX, mouseY, horizontal, vertical)) {
			return true;
		}
		return screen.mouseScrolled(mouseX, mouseY, horizontal, vertical);
	}

	@Redirect(method = "handleAccumulatedMovement", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;mouseMoved(DD)V"))
	private void exposed$mouseMoved(Screen screen, double mouseX, double mouseY) {
		if (screen instanceof ChatScreen) {
			ExposedImGui.overlayMouseMove(mouseX, mouseY);
		}
		screen.mouseMoved(mouseX, mouseY);
	}

	@Redirect(method = "handleAccumulatedMovement", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;mouseDragged(Lnet/minecraft/client/input/MouseButtonEvent;DD)Z"))
	private boolean exposed$mouseDragged(Screen screen, MouseButtonEvent event, double dragX, double dragY) {
		if (screen instanceof ChatScreen && ExposedImGui.overlayMouseDragged(event.x(), event.y())) {
			return true;
		}
		return screen.mouseDragged(event, dragX, dragY);
	}
}
