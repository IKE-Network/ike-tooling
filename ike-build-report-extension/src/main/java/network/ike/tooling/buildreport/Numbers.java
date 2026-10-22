package network.ike.tooling.buildreport;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Renders measure values the way they are read: a count as an integer,
 * anything else with as few decimals as say it exactly, six at most.
 */
final class Numbers {

    private static final double INTEGRAL_LIMIT = 1e15;

    private Numbers() {
    }

    /**
     * Formats a value plainly: {@code 415}, {@code 38.5}, {@code 0.125}.
     *
     * @param value the value
     * @return the shortest exact rendering, never in scientific notation
     */
    static String plain(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return String.valueOf(value);
        }
        if (value == Math.rint(value) && Math.abs(value) < INTEGRAL_LIMIT) {
            return Long.toString((long) value);
        }
        return new BigDecimal(value).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /**
     * Formats a value with a fixed number of decimals, for aligned columns.
     *
     * @param value    the value
     * @param decimals how many decimals to show
     * @return the rendering, in the root locale
     */
    static String fixed(double value, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }

    /**
     * Formats a byte count the way a reader says it: {@code 512 B},
     * {@code 1.2 MiB}, {@code 3.4 GiB}.
     *
     * @param bytes the size in bytes
     * @return the rendering
     */
    static String bytes(double bytes) {
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return unit == 0
                ? Long.toString((long) value) + " B"
                : String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }
}
