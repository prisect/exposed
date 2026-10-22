package dev.exposed;

import java.util.Locale;

public final class Format {
	public static String durationTick(double millisPerTick) {
		double picos = Math.max(0.0, millisPerTick) * 1_000_000_000.0;

		if (picos >= 999_500_000_000.0) {
			return trim(String.format(Locale.ROOT, "%.2f", picos / 1_000_000_000_000.0)) + " s/t";
		}

		if (picos >= 999_500_000.0) {
			return trim(String.format(Locale.ROOT, "%.2f", picos / 1_000_000_000.0)) + " ms/t";
		}

		if (picos >= 999_500.0) {
			return trim(String.format(Locale.ROOT, "%.2f", picos / 1_000_000.0)) + " \u00B5s/t";
		}

		if (picos >= 999.5) {
			return trim(String.format(Locale.ROOT, "%.1f", picos / 1_000.0)) + " ns/t";
		}

		return Long.toString(Math.round(picos)) + " ps/t";
	}

	public static String percent(double millisPerTick, double tickMillis) {
		if (tickMillis <= 0.0001) {
			return "0.0%";
		}

		return String.format(Locale.ROOT, "%.1f%%", Math.max(0.0, millisPerTick) / tickMillis * 100.0);
	}

	public static String count(long value) {
		if (value >= 1_000_000L) {
			return String.format(Locale.ROOT, "%.1fM", value / 1_000_000.0);
		}

		if (value >= 1_000L) {
			return String.format(Locale.ROOT, "%.1fk", value / 1_000.0);
		}

		return Long.toString(value);
	}

	private static String trim(String value) {
		while (value.contains(".") && (value.endsWith("0") || value.endsWith("."))) {
			value = value.substring(0, value.length() - 1);
		}

		return value;
	}
}
