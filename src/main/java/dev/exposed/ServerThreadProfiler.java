package dev.exposed;

import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

public final class ServerThreadProfiler {
	private static final long SAMPLE_INTERVAL_MILLIS = 25L;
	private static final Map<Frame, Long> COUNTS = new ConcurrentHashMap<>();
	private static final Map<String, Source> SOURCES = new ConcurrentHashMap<>();
	private static final Map<String, String> MOD_NAMES = new HashMap<>();

	private static volatile boolean running;
	private static volatile long totalSamples;
	private static Thread sampler;
	private static Thread serverThread;

	public static void start() {
		if (running) {
			return;
		}

		loadModNames();
		running = true;
		sampler = new Thread(ServerThreadProfiler::loop, "Exposed-Thread-Sampler");
		sampler.setDaemon(true);
		sampler.setPriority(Thread.MIN_PRIORITY);
		sampler.start();
	}

	public static void stop() {
		running = false;
		sampler = null;
	}

	public static void reset() {
		COUNTS.clear();
		totalSamples = 0L;
	}

	public static List<Entry> top(int limit) {
		long total = totalSamples;

		if (total == 0L || COUNTS.isEmpty()) {
			return List.of();
		}

		List<Entry> result = new ArrayList<>(COUNTS.size());

		for (Map.Entry<Frame, Long> entry : COUNTS.entrySet()) {
			Frame frame = entry.getKey();
			long count = entry.getValue();
			Source source = source(frame.className());
			result.add(new Entry(
					source.owner(),
					source.jar(),
					frame.className(),
					frame.methodName(),
					count * 100.0 / total,
					count
			));
		}

		result.sort(Comparator.comparingDouble(Entry::percent).reversed());

		if (result.size() > limit) {
			return new ArrayList<>(result.subList(0, limit));
		}

		return result;
	}

	private static void loop() {
		Thread self = Thread.currentThread();

		while (running && sampler == self) {
			try {
				if (Profiler.insideTick()) {
					Thread thread = serverThread();

					if (thread != null) {
						Frame frame = pick(thread.getStackTrace());

						if (frame != null) {
							COUNTS.merge(frame, 1L, Long::sum);
							totalSamples++;
						}
					}
				}

				Thread.sleep(SAMPLE_INTERVAL_MILLIS);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				return;
			} catch (Throwable ignored) {
			}
		}
	}

	private static Thread serverThread() {
		Thread current = serverThread;

		if (current != null && current.isAlive()) {
			return current;
		}

		for (Thread thread : Thread.getAllStackTraces().keySet()) {
			if (thread.isAlive() && "Server thread".equals(thread.getName())) {
				serverThread = thread;
				return thread;
			}
		}

		return null;
	}

	private static Frame pick(StackTraceElement[] stack) {
		Frame fallback = null;

		for (StackTraceElement element : stack) {
			String className = element.getClassName();

			if (isJdk(className) || className.startsWith("net.fabricmc.") || className.startsWith("org.spongepowered.")
					|| className.startsWith("com.mojang.")) {
				continue;
			}

			Frame frame = new Frame(className, element.getMethodName());

			if (!isNoise(className) && !className.startsWith("net.minecraft.")) {
				return frame;
			}

			if (fallback == null) {
				fallback = frame;
			}
		}

		return fallback;
	}

	private static boolean isJdk(String className) {
		return className.startsWith("java.") || className.startsWith("javax.") || className.startsWith("jdk.")
				|| className.startsWith("sun.") || className.startsWith("com.sun.");
	}

	private static boolean isNoise(String className) {
		return className.startsWith("dev.exposed.ServerThreadProfiler")
				|| className.startsWith("dev.exposed.Profiler")
				|| className.startsWith("dev.exposed.mixin.");
	}

	private static Source source(String className) {
		return SOURCES.computeIfAbsent(className, ServerThreadProfiler::resolveSource);
	}

	private static Source resolveSource(String className) {
		String loadableName = className;
		int lambda = loadableName.indexOf("$$Lambda");

		if (lambda >= 0) {
			loadableName = loadableName.substring(0, lambda);
		}

		try {
			Class<?> type = Class.forName(loadableName, false, ServerThreadProfiler.class.getClassLoader());
			CodeSource codeSource = type.getProtectionDomain().getCodeSource();

			if (codeSource != null && codeSource.getLocation() != null) {
				Path path = Path.of(codeSource.getLocation().toURI());
				String jar = path.getFileName() == null ? className : path.getFileName().toString();
				String owner = MOD_NAMES.getOrDefault(jar, owner(className));

				return new Source(owner, jar);
			}
		} catch (Throwable ignored) {
		}

		return new Source(owner(className), "unknown");
	}

	private static String owner(String className) {
		if (className.startsWith("net.minecraft.")) {
			return "Minecraft";
		}

		int dot = className.indexOf('.');
		return dot > 0 ? className.substring(0, dot) : className;
	}

	private static void loadModNames() {
		if (!MOD_NAMES.isEmpty()) {
			return;
		}

		try {
			for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
				for (Path path : mod.getOrigin().getPaths()) {
					Path fileName = path.getFileName();

					if (fileName != null) {
						MOD_NAMES.put(fileName.toString(), mod.getMetadata().getName());
					}
				}
			}
		} catch (Throwable ignored) {
		}
	}

	private record Frame(String className, String methodName) {
	}

	public record Source(String owner, String jar) {
	}

	public record Entry(String owner, String jar, String className, String methodName, double percent, long samples) {
	}
}
