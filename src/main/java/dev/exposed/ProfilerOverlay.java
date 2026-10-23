package dev.exposed;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector4f;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class ProfilerOverlay {
	private static final long SAMPLE_REFRESH_MILLIS = 100L;
	private static final Matrix4f VIEW_PROJECTION = new Matrix4f();
	private static final Vector4f PROJECTED = new Vector4f();

	private static List<Entity> cachedEntities = List.of();
	private static List<BlockPos> cachedBlockEntities = List.of();
	private static Map<Integer, Profiler.Sample> cachedEntitySamples = Map.of();
	private static Map<Long, Profiler.Sample> cachedBlockSamples = Map.of();
	private static long cachedAt;
	private static ResourceKey<Level> cachedDimension;

	private ProfilerOverlay() {
	}

	public static void render(LevelRenderContext context) {
		WorldLabels.clear();

		ExposedConfig config = ExposedConfig.get();

		if (!Profiler.isEnabled()) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;

		if (level == null || minecraft.player == null) {
			return;
		}

		// Keep the world overlay visible while chat is open, but avoid covering other menus.
		if (minecraft.gui.screen() != null && !(minecraft.gui.screen() instanceof ChatScreen)) {
			return;
		}

		Camera camera = minecraft.gameRenderer.mainCamera();

		if (camera == null) {
			return;
		}

		Vec3 eye = camera.position();
		Frustum frustum = camera.getCullFrustum();
		double maxDistance = config.distanceBlocks();
		double maxDistanceSq = maxDistance * maxDistance;
		Projector projector = null;

		if (config.showLabels) {
			// Camera-space projection of world points. View rotation + projection is
			// cheaper and more reliable than rebuilding the render matrices by hand.
			camera.getViewRotationProjectionMatrix(VIEW_PROJECTION);
			projector = new Projector(eye, minecraft.getWindow().getGuiScaledWidth(),
					minecraft.getWindow().getGuiScaledHeight());
		}

		refreshTargets(level, eye, config.distanceBlocks());

		for (BlockPos pos : cachedBlockEntities) {
			double dx = pos.getX() + 0.5 - eye.x;
			double dy = pos.getY() + 0.5 - eye.y;
			double dz = pos.getZ() + 0.5 - eye.z;
			if (dx * dx + dy * dy + dz * dz > maxDistanceSq) {
				continue;
			}

			Profiler.Sample sample = cachedBlockSamples.get(pos.asLong());
			double millis = sample == null ? 0.0 : sample.averageMillis();
			int rgb = sample == null || millis <= 0.0 ? 0xA0A0A0 : Heat.rgb(millis);
			String text = sample == null || millis <= 0.0 ? Format.durationTick(0.0) : Format.durationTick(millis);
			AABB box = new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1.0, pos.getY() + 1.0,
					pos.getZ() + 1.0);
			if (config.onlyVisible && frustum != null && !frustum.isVisible(box)) {
				continue;
			}
			if (config.showBoxes) {
				drawBox(box, rgb);
			}
			if (projector != null) {
				addLabel(projector, pos.getX() + 0.5, pos.getY() + 1.12, pos.getZ() + 0.5, rgb, text);
			}
		}

		for (Entity entity : cachedEntities) {
			if (entity == minecraft.player || !entity.isAlive() || entity.distanceToSqr(eye) > maxDistanceSq) {
				continue;
			}

			AABB box = entity.getBoundingBox();
			if (config.onlyVisible && frustum != null && !frustum.isVisible(box)) {
				continue;
			}

			Profiler.Sample sample = cachedEntitySamples.get(entity.getId());
			double millis = sample == null ? 0.0 : sample.averageMillis();
			int rgb = sample == null || millis <= 0.0 ? 0xA0A0A0 : Heat.rgb(millis);
			String text = sample == null || millis <= 0.0 ? Format.durationTick(0.0) : Format.durationTick(millis);
			Vec3 center = box.getCenter();
			double lineTop = box.maxY + 0.25;
			drawCenterLine(center.x, box.minY, center.z, lineTop, rgb);
			if (projector != null) {
				addLabelRight(projector, center.x, lineTop, center.z, rgb, text);
			}
		}
	}

	private static void drawBox(AABB box, int rgb) {
		Gizmos.cuboid(box, GizmoStyle.fill(Heat.argbRgb(rgb, 0x38)), true).setAlwaysOnTop();
	}

	private static void drawCenterLine(double x, double bottom, double z, double top, int rgb) {
		Gizmos.line(new Vec3(x, bottom, z), new Vec3(x, top, z), Heat.argbRgb(rgb, 0xFF), 2.0F)
				.setAlwaysOnTop();
	}

	private static void addLabel(Projector projector, double x, double y, double z, int rgb, String text) {
		if (!projector.project(x, y, z)) {
			return;
		}

		WorldLabels.add(projector.screenX, projector.screenY - 2.0F, Heat.imguiRgb(rgb, 0xFF), text);
	}

	private static void addLabelRight(Projector projector, double x, double y, double z, int rgb, String text) {
		if (!projector.project(x, y, z)) {
			return;
		}

		WorldLabels.addLeftAligned(projector.screenX + 3.0F, projector.screenY - 2.0F,
				Heat.imguiRgb(rgb, 0xFF), text);
	}

	private static void refreshTargets(ClientLevel level, Vec3 eye, double distance) {
		ResourceKey<Level> dimension = level.dimension();
		long now = System.currentTimeMillis();

		if (!dimension.equals(cachedDimension)) {
			cachedDimension = dimension;
			cachedAt = 0L;
		}

		if (now - cachedAt < SAMPLE_REFRESH_MILLIS) {
			return;
		}

		cachedAt = now;
		List<Profiler.Sample> samples;

		if (Minecraft.getInstance().hasSingleplayerServer()) {
			samples = Profiler.all(dimension);
		} else {
			RemoteFeed.Snapshot snapshot = RemoteFeed.current(dimension);
			samples = snapshot == null ? List.of() : snapshot.samples();
		}

		Map<Integer, Profiler.Sample> entitySamples = new HashMap<>();
		Map<Long, Profiler.Sample> blockSamples = new HashMap<>();
		for (Profiler.Sample sample : samples) {
			if (sample.block) {
				blockSamples.put(sample.pos, sample);
			} else {
				entitySamples.put(sample.entityId, sample);
			}
		}
		cachedEntitySamples = entitySamples;
		cachedBlockSamples = blockSamples;

		List<Entity> entities = new ArrayList<>();
		double distanceSq = distance * distance;
		for (Entity entity : level.entitiesForRendering()) {
			if (entity.distanceToSqr(eye) <= distanceSq) {
				entities.add(entity);
			}
		}
		cachedEntities = entities;

		List<BlockPos> blockEntities = new ArrayList<>();
		BlockPos cameraBlock = BlockPos.containing(eye);
		int centerChunkX = cameraBlock.getX() >> 4;
		int centerChunkZ = cameraBlock.getZ() >> 4;
		int chunkRadius = (int) Math.ceil(distance / 16.0);
		for (int chunkX = centerChunkX - chunkRadius; chunkX <= centerChunkX + chunkRadius; chunkX++) {
			for (int chunkZ = centerChunkZ - chunkRadius; chunkZ <= centerChunkZ + chunkRadius; chunkZ++) {
				LevelChunk chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
				if (chunk == null) {
					continue;
				}
				for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
					blockEntities.add(blockEntity.getBlockPos().immutable());
				}
			}
		}
		cachedBlockEntities = blockEntities;
	}

	private static final class Projector {
		private final double eyeX;
		private final double eyeY;
		private final double eyeZ;
		private final float width;
		private final float height;
		private float screenX;
		private float screenY;

		private Projector(Vec3 eye, float width, float height) {
			this.eyeX = eye.x;
			this.eyeY = eye.y;
			this.eyeZ = eye.z;
			this.width = width;
			this.height = height;
		}

		private boolean project(double x, double y, double z) {
			PROJECTED.set((float) (x - this.eyeX), (float) (y - this.eyeY), (float) (z - this.eyeZ), 1.0F);
			VIEW_PROJECTION.transform(PROJECTED);

			float w = PROJECTED.w;

			if (w <= 0.0001F) {
				return false;
			}

			float ndcX = PROJECTED.x / w;
			float ndcY = PROJECTED.y / w;

			if (ndcX < -1.05F || ndcX > 1.05F || ndcY < -1.05F || ndcY > 1.05F) {
				return false;
			}

			this.screenX = (ndcX * 0.5F + 0.5F) * this.width;
			this.screenY = (0.5F - ndcY * 0.5F) * this.height;
			return true;
		}
	}
}
