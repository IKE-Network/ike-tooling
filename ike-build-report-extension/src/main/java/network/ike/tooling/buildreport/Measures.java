package network.ike.tooling.buildreport;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What one session was like, in numbers (ike-issues#1207): warnings by
 * kind, tests, coverage, time, sizes, and whatever measurements its
 * tests left behind.
 *
 * <p>Values are keyed by {@link MeasureKey#key()} for the measures the
 * extension derives itself, and by the names the ledger or a test
 * chose for the rest. A quantity the session could not measure is
 * absent, never zero: a build that ran no tests has no
 * {@code tests.run}, so a bound on it is reported as not measured
 * rather than tripped.</p>
 *
 * @param values  the session's measures, in collection order
 * @param modules per-module measures, keyed by module artifact id and
 *                then by measure key
 * @param slowest the slowest test classes, slowest first
 * @param notes   what could not be measured and why, one line each
 */
public record Measures(
        Map<String, Double> values,
        Map<String, Map<String, Double>> modules,
        List<SlowSuite> slowest,
        List<String> notes) {

    /**
     * One test class and the time it took.
     *
     * @param module  the module that ran it
     * @param suite   the test class name as the report named it
     * @param seconds its elapsed time in seconds
     */
    public record SlowSuite(String module, String suite, double seconds) {
    }

    /**
     * Defensively copies every part, preserving order.
     *
     * @param values  the session's measures
     * @param modules per-module measures
     * @param slowest the slowest test classes
     * @param notes   what could not be measured
     */
    public Measures {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNullElse(values, Map.of())));
        Map<String, Map<String, Double>> perModule = new LinkedHashMap<>();
        Objects.requireNonNullElse(modules, Map.<String, Map<String, Double>>of()).forEach(
                (module, map) -> perModule.put(module, Collections.unmodifiableMap(new LinkedHashMap<>(map))));
        modules = Collections.unmodifiableMap(perModule);
        slowest = List.copyOf(Objects.requireNonNullElse(slowest, List.of()));
        notes = List.copyOf(Objects.requireNonNullElse(notes, List.of()));
    }

    /**
     * Returns the measures of a session nothing was collected for.
     *
     * @return empty measures
     */
    public static Measures empty() {
        return new Measures(Map.of(), Map.of(), List.of(), List.of());
    }

    /**
     * Reports whether the session measured anything at all.
     *
     * @return {@code true} when there are no values
     */
    public boolean isEmpty() {
        return values.isEmpty();
    }

    /**
     * Looks up one session-level measure.
     *
     * @param key the measure key text
     * @return the value, or {@code null} when it was not measured
     */
    public Double value(String key) {
        return values.get(key);
    }

    /**
     * Looks up one enumerated session-level measure.
     *
     * @param key the measure
     * @return the value, or {@code null} when it was not measured
     */
    public Double value(MeasureKey key) {
        return values.get(key.key());
    }

    /**
     * Reports whether an enumerated measure was taken.
     *
     * @param key the measure
     * @return {@code true} when a value is present
     */
    public boolean has(MeasureKey key) {
        return values.containsKey(key.key());
    }
}
