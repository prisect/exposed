package dev.exposed.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import dev.exposed.Profiler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;

@Mixin(Level.class)
public abstract class LevelBlockEntityMixin {
	@Redirect(
			method = "tickBlockEntities",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/TickingBlockEntity;tick()V")
	)
	private void exposed$tickBlockEntity(TickingBlockEntity ticker) {
		if (!Profiler.isRecording() || !((Object) this instanceof ServerLevel serverLevel)) {
			ticker.tick();
			return;
		}

		long start = System.nanoTime();

		try {
			ticker.tick();
		} finally {
			Profiler.recordBlockEntity(serverLevel, ticker.getPos(), ticker.getType(), System.nanoTime() - start);
		}
	}
}
