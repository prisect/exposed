package dev.exposed;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class ProfilerOverlay {
	private ProfilerOverlay() {
	}

	public static void render(LevelRenderContext context) {
		ExposedConfig config = ExposedConfig.get();

		if (!Profiler.isEnabled()) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;

		if (level == null || minecraft.player == null) {
			return;
		}

		List<Profiler.Sample> samples = samples(level);
		Map<Long, Profiler.Sample> blocks = new HashMap<>();
		Map<Integer, Profiler.Sample> entities = new HashMap<>();

		for (Profiler.Sample sample : samples) {
			if (sample.block) {
				blocks.put(sample.pos, sample);
			} else {
				entities.put(sample.entityId, sample);
			}
		}

		Camera camera = minecraft.gameRenderer.mainCamera();
		Vec3 eye = camera.position();
		Frustum frustum = camera.getCullFrustum();
		double maxDistance = config.distanceBlocks();
		double maxDistanceSq = maxDistance * maxDistance;

		renderBlockEntities(level, eye, frustum, blocks, maxDistance, maxDistanceSq);
		renderEntities(level, minecraft, eye, frustum, entities, maxDistanceSq);
	}

	private static void renderBlockEntities(ClientLevel level, Vec3 eye, Frustum frustum,
			Map<Long, Profiler.Sample> blocks, double maxDistance, double maxDistanceSq) {
		ExposedConfig config = ExposedConfig.get();
		int centerX = Math.floorDiv((int) eye.x, 16);
		int centerZ = Math.floorDiv((int) eye.z, 16);
		int radius = (int) Math.ceil(maxDistance / 16.0) + 1;

		for (int chunkX = centerX - radius; chunkX <= centerX + radius; chunkX++) {
			for (int chunkZ = centerZ - radius; chunkZ <= centerZ + radius; chunkZ++) {
				ChunkAccess chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);

				if (!(chunk instanceof LevelChunk levelChunk)) {
					continue;
				}

				for (BlockEntity blockEntity : levelChunk.getBlockEntities().values()) {
					BlockPos pos = blockEntity.getBlockPos();
					double dx = pos.getX() + 0.5 - eye.x;
					double dy = pos.getY() + 0.5 - eye.y;
					double dz = pos.getZ() + 0.5 - eye.z;

					if (dx * dx + dy * dy + dz * dz > maxDistanceSq) {
						continue;
					}

					AABB box = new AABB(pos.getX(), pos.getY(), pos.getZ(),
							pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0);

					if (config.onlyVisible && frustum != null && !frustum.isVisible(box)) {
						continue;
					}

					Profiler.Sample sample = blocks.get(pos.asLong());
					render(sample, box, Vec3.atCenterOf(pos), sample == null ? 0.0 : sample.averageMillis(), config);
				}
			}
		}
	}

	private static void renderEntities(ClientLevel level, Minecraft minecraft, Vec3 eye, Frustum frustum,
			Map<Integer, Profiler.Sample> entities, double maxDistanceSq) {
		ExposedConfig config = ExposedConfig.get();

		for (Entity entity : level.entitiesForRendering()) {
			if (entity == minecraft.player || entity instanceof Player || !entity.isAlive()) {
				continue;
			}

			if (entity.distanceToSqr(eye) > maxDistanceSq) {
				continue;
			}

			AABB box = entity.getBoundingBox();

			if (config.onlyVisible && frustum != null && !frustum.isVisible(box)) {
				continue;
			}

			Profiler.Sample sample = entities.get(entity.getId());
			double millis = sample == null ? 0.0 : sample.averageMillis();
			render(sample, box.inflate(0.03), box.getCenter(), millis, config);
		}
	}

	private static void render(Profiler.Sample sample, AABB box, Vec3 center, double millis, ExposedConfig config) {
		int fill = sample == null ? 0x16808080 : heat(millis, 0x78);
		int labelColor = sample == null ? 0xFF9A9A9A : heat(millis, 0xFF);

		if (config.showBoxes) {
			Gizmos.cuboid(box, GizmoStyle.fill(fill), true).setAlwaysOnTop();
		}

		if (config.showLabels) {
			Gizmos.billboardText(
					Format.durationTick(millis),
					center,
					TextGizmo.Style.forColorAndCentered(labelColor).withScale(0.52F)
			).setAlwaysOnTop();
		}
	}

	private static List<Profiler.Sample> samples(ClientLevel level) {
		if (Minecraft.getInstance().hasSingleplayerServer()) {
			return Profiler.top(level.dimension(), 512);
		}

		RemoteFeed.Snapshot snapshot = RemoteFeed.current(level.dimension());
		return snapshot == null ? List.of() : snapshot.samples();
	}

	private static int heat(double millis, int alpha) {
		double t = Math.max(0.0, Math.min(1.0, millis / ExposedConfig.get().redMillis()));
		int r;
		int g;
		if (t <= 0.5) {
			double u = t * 2.0;
			r = (int) Math.round(0x3C + (0xE6 - 0x3C) * u);
			g = 0xC8;
		} else {
			double u = (t - 0.5) * 2.0;
			r = 0xE6;
			g = (int) Math.round(0xC8 + (0x28 - 0xC8) * u);
		}
		return (alpha & 0xFF) << 24 | r << 16 | g << 8 | 0x38;
	}
}
