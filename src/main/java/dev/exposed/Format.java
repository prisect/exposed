package dev.exposed;

public final class Format {
	private Format() {
	}

	public static String durationTick(double millisPerTick) {
		return duration(millisPerTick) + "/t";
	}

	/**
	 * Same scaling as {@link #durationTick(double)} but without the per-tick
	 * suffix, for totals accumulated since capture started.
	 */
	public static String duration(double millis) {
		double picos = Math.max(0.0, millis) * 1_000_000_000.0;

		if (picos >= 999_500_000_000.0) {
			return decimal2(picos / 1_000_000_000_000.0) + " s";
		}

		if (picos >= 999_500_000.0) {
			return decimal2(picos / 1_000_000_000.0) + " ms";
		}

		if (picos >= 999_500.0) {
			return decimal2(picos / 1_000_000.0) + " \u00B5s";
		}

		if (picos >= 999.5) {
			return decimal1(picos / 1_000.0) + " ns";
		}

		return Long.toString(Math.round(picos)) + " ps";
	}

	public static String percent(double millisPerTick, double tickMillis) {
		if (tickMillis <= 0.0001) {
			return "0.0%";
		}

		return decimal1(Math.max(0.0, millisPerTick) / tickMillis * 100.0) + "%";
	}

	public static String count(long value) {
		if (value >= 1_000_000L) {
			return decimal1(value / 1_000_000.0) + "M";
		}

		if (value >= 1_000L) {
			return decimal1(value / 1_000.0) + "k";
		}

		return Long.toString(value);
	}

	/**
	 * One decimal place, always shown. Avoids {@code String.format}, which is far
	 * too expensive for text that is rebuilt every frame.
	 */
	public static String decimal1(double value) {
		long rounded = Math.round(Math.max(0.0, value) * 10.0);
		long whole = rounded / 10L;
		long fraction = rounded % 10L;
		StringBuilder out = new StringBuilder(10);
		out.append(whole).append('.').append(fraction);
		return out.toString();
	}

	/**
	 * Two decimal places with trailing zeros trimmed ({@code 1.20 -> 1.2},
	 * {@code 1.00 -> 1}).
	 */
	public static String decimal2(double value) {
		long rounded = Math.round(Math.max(0.0, value) * 100.0);
		long whole = rounded / 100L;
		long fraction = rounded % 100L;

		if (fraction == 0L) {
			return Long.toString(whole);
		}

		StringBuilder out = new StringBuilder(10);
		out.append(whole).append('.');

		if (fraction < 10L) {
			out.append('0').append(fraction);
		} else if (fraction % 10L == 0L) {
			out.append(fraction / 10L);
		} else {
			out.append(fraction);
		}

		return out.toString();
	}
}
