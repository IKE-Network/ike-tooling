package network.ike.tooling.buildreport;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The committed acceptance ledger, normally read from
 * {@code .mvn/build-report.yaml} at the execution root.
 *
 * <p>Expected shape:</p>
 *
 * <pre>{@code
 * mode: report          # report | gate
 * accepted:
 *   - key: model-problem/bom-import-from-reactor
 *     count: 2
 *     reason: accepted by design — see IKE-WORKSPACE.md
 *     since: 2026-08-10
 * }</pre>
 *
 * <p>A missing file yields {@link #empty()}: report mode, no accepted
 * entries — every warning finding surfaces under ATTENTION.</p>
 */
public final class Ledger {

    private final LedgerMode mode;
    private final List<AcceptedEntry> entries;

    private Ledger(LedgerMode mode, List<AcceptedEntry> entries) {
        this.mode = mode;
        this.entries = List.copyOf(entries);
    }

    /**
     * Creates the ledger used when no ledger file exists.
     *
     * @return an empty ledger in {@link LedgerMode#REPORT} mode
     */
    public static Ledger empty() {
        return new Ledger(LedgerMode.REPORT, List.of());
    }

    /**
     * Creates a ledger from parts — the construction path for tooling
     * that rewrites ledgers (the ratchet goal).
     *
     * @param mode    the enforcement posture
     * @param entries the accepted entries in declaration order
     * @return the ledger
     */
    public static Ledger of(LedgerMode mode, List<AcceptedEntry> entries) {
        Objects.requireNonNull(mode, "mode");
        return new Ledger(mode, entries);
    }

    /**
     * Loads a ledger from a YAML file.
     *
     * @param file the ledger file, typically {@code .mvn/build-report.yaml}
     * @return the parsed ledger, or {@link #empty()} when the file does not exist
     * @throws IOException              when the file exists but cannot be read
     * @throws IllegalArgumentException when the YAML shape is not a ledger
     */
    public static Ledger load(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        if (!Files.exists(file)) {
            return empty();
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Object root = yaml.load(reader);
            if (root == null) {
                return empty();
            }
            if (!(root instanceof Map)) {
                throw new IllegalArgumentException(
                        "ledger root must be a mapping, was: " + root.getClass().getSimpleName());
            }
            Map<?, ?> map = (Map<?, ?>) root;
            LedgerMode mode = parseMode(map.get("mode"));
            List<AcceptedEntry> entries = parseEntries(map.get("accepted"));
            return new Ledger(mode, entries);
        }
    }

    private static LedgerMode parseMode(Object value) {
        if (value == null) {
            return LedgerMode.REPORT;
        }
        String text = String.valueOf(value).strip().toUpperCase(Locale.ROOT);
        try {
            return LedgerMode.valueOf(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("mode must be report or gate, was: " + value);
        }
    }

    private static List<AcceptedEntry> parseEntries(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List)) {
            throw new IllegalArgumentException(
                    "accepted must be a list, was: " + value.getClass().getSimpleName());
        }
        List<AcceptedEntry> entries = new ArrayList<>();
        for (Object item : (List<?>) value) {
            if (!(item instanceof Map)) {
                throw new IllegalArgumentException("accepted entries must be mappings, found: " + item);
            }
            Map<?, ?> entry = (Map<?, ?>) item;
            Object key = entry.get("key");
            if (key == null) {
                throw new IllegalArgumentException("accepted entry is missing its key: " + entry);
            }
            Object count = entry.get("count");
            if (!(count instanceof Integer)) {
                throw new IllegalArgumentException(
                        "accepted entry '" + key + "' needs an integer count, was: " + count);
            }
            entries.add(new AcceptedEntry(
                    String.valueOf(key),
                    (Integer) count,
                    entry.get("reason") == null ? "" : String.valueOf(entry.get("reason")),
                    stringifySince(entry.get("since")),
                    entry.get("mode") == null ? null : parseMode(entry.get("mode"))));
        }
        return entries;
    }

    /**
     * Canonicalizes the {@code since} value to an ISO date string.
     *
     * <p>YAML resolves a bare {@code 2026-08-10} scalar as a timestamp
     * ({@link java.util.Date} at midnight UTC), so the raw value must be
     * mapped back to the date the author wrote.</p>
     */
    private static String stringifySince(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof java.util.Date date) {
            return java.time.Instant.ofEpochMilli(date.getTime())
                    .atZone(java.time.ZoneOffset.UTC)
                    .toLocalDate()
                    .toString();
        }
        return String.valueOf(value);
    }

    /**
     * Returns the enforcement posture declared by the ledger.
     *
     * @return the ledger's mode; {@link LedgerMode#REPORT} when undeclared
     */
    public LedgerMode mode() {
        return mode;
    }

    /**
     * Returns the accepted entries in declaration order.
     *
     * @return an immutable list of accepted entries
     */
    public List<AcceptedEntry> entries() {
        return entries;
    }

    /**
     * Compares a session's findings against this ledger.
     *
     * <p>{@link Severity#ERROR} findings go to failures unconditionally.
     * Warning findings are grouped by key: unaccepted keys and counts
     * above expectation go to attention; counts at expectation go to
     * accepted; counts below expectation (including zero) go to both
     * accepted bookkeeping and the ratchet list.</p>
     *
     * @param findings the session's findings in observation order
     * @return the evaluation backing the receipt's sections
     */
    public LedgerEvaluation evaluate(List<Finding> findings) {
        Objects.requireNonNull(findings, "findings");
        List<Finding> failures = new ArrayList<>();
        Map<String, List<Finding>> warningsByKey = new LinkedHashMap<>();
        for (Finding finding : findings) {
            if (finding.severity() == Severity.ERROR) {
                failures.add(finding);
            } else {
                warningsByKey.computeIfAbsent(finding.key(), k -> new ArrayList<>()).add(finding);
            }
        }

        Map<String, AcceptedEntry> entriesByKey = new LinkedHashMap<>();
        for (AcceptedEntry entry : entries) {
            entriesByKey.put(entry.key(), entry);
        }

        List<LedgerEvaluation.AttentionItem> attention = new ArrayList<>();
        List<LedgerEvaluation.AcceptedStatus> accepted = new ArrayList<>();
        List<LedgerEvaluation.AcceptedStatus> ratchet = new ArrayList<>();

        for (Map.Entry<String, List<Finding>> observed : warningsByKey.entrySet()) {
            AcceptedEntry entry = entriesByKey.remove(observed.getKey());
            long count = observed.getValue().size();
            String sample = observed.getValue().get(0).detail();
            if (entry == null) {
                attention.add(new LedgerEvaluation.AttentionItem(observed.getKey(), count, null, sample));
            } else if (count > entry.count()) {
                attention.add(new LedgerEvaluation.AttentionItem(observed.getKey(), count, entry.count(), sample));
            } else if (count == entry.count()) {
                accepted.add(new LedgerEvaluation.AcceptedStatus(entry, count));
            } else {
                accepted.add(new LedgerEvaluation.AcceptedStatus(entry, count));
                ratchet.add(new LedgerEvaluation.AcceptedStatus(entry, count));
            }
        }

        for (AcceptedEntry unobserved : entriesByKey.values()) {
            ratchet.add(new LedgerEvaluation.AcceptedStatus(unobserved, 0L));
        }

        return new LedgerEvaluation(failures, attention, accepted, ratchet);
    }

    /**
     * Filters an evaluation's attention items down to those that gate.
     *
     * <p>An attention item gates unless its key has a ledger entry whose
     * per-entry mode is {@link LedgerMode#REPORT} — the onboarding
     * override. Unaccepted keys have no entry and therefore always
     * gate.</p>
     *
     * @param evaluation the evaluation to filter
     * @return the gating attention items, in evaluation order
     */
    public List<LedgerEvaluation.AttentionItem> gatingAttention(LedgerEvaluation evaluation) {
        Objects.requireNonNull(evaluation, "evaluation");
        Map<String, AcceptedEntry> entriesByKey = new LinkedHashMap<>();
        for (AcceptedEntry entry : entries) {
            entriesByKey.put(entry.key(), entry);
        }
        List<LedgerEvaluation.AttentionItem> gating = new ArrayList<>();
        for (LedgerEvaluation.AttentionItem item : evaluation.attention()) {
            AcceptedEntry entry = entriesByKey.get(item.key());
            if (entry == null || entry.entryMode() != LedgerMode.REPORT) {
                gating.add(item);
            }
        }
        return gating;
    }
}
