package dev.exposed.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import dev.exposed.Profiler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

@Mixin(ServerLevel.class)
public abstract class ServerLevelEntityMixin {
	@Redirect(
			method = "tickNonPassenger",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;tick()V")
	)
	private void exposed$tickEntity(Entity entity) {
		if (!Profiler.isRecording()) {
			entity.tick();
			return;
		}

		long start = System.nanoTime();

		try {
			entity.tick();
		} finally {
			Profiler.recordEntity((ServerLevel) (Object) this, entity, System.nanoTime() - start);
		}
	}

	@Redirect(
			method = "tickPassenger",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;rideTick()V")
	)
	private void exposed$rideTickEntity(Entity passenger) {
		if (!Profiler.isRecording()) {
			passenger.rideTick();
			return;
		}

		long start = System.nanoTime();

		try {
			passenger.rideTick();
		} finally {
			Profiler.recordEntity((ServerLevel) (Object) this, passenger, System.nanoTime() - start);
		}
	}
}
