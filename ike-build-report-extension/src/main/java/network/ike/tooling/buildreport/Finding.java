package network.ike.tooling.buildreport;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One observation derived from a structured build event.
 *
 * <p>The {@code key} is the finding's stable identity — composed from
 * structured event fields (category, event type, coordinates such as a
 * repository id or a plugin goal), never from message prose. Ledger
 * entries accept findings by this key with an expected count.</p>
 *
 * <p>{@code detail} is the one line a reader sees; {@code context} is
 * the structured evidence behind it — the repository URL, the POM that
 * declared it, the resolver's verbatim message. Context exists because
 * a key alone cannot answer "what is this and is it mine?", which is
 * the question a gated build actually raises (ike-issues#989).</p>
 *
 * @param category the structured event family the finding came from
 * @param severity whether the finding is build-breaking or ledger-evaluated
 * @param key      stable identity, e.g.
 *                 {@code repository/metadata-resolve-failed/private-assets}
 * @param detail   one human-readable line of context for the receipt
 * @param context  structured evidence in insertion order; never null
 */
public record Finding(
        FindingCategory category,
        Severity severity,
        String key,
        String detail,
        Map<String, String> context) {

    /** Context key: the repository id as the resolver reported it. */
    public static final String CONTEXT_REPOSITORY_ID = "repository.id";

    /** Context key: the repository URL. */
    public static final String CONTEXT_REPOSITORY_URL = "repository.url";

    /** Context key: what was being fetched (metadata or artifact coordinates). */
    public static final String CONTEXT_SUBJECT = "subject";

    /** Context key: the resolver's verbatim failure message. */
    public static final String CONTEXT_ERROR = "error";

    /**
     * Context key: where the repository was declared — a POM's
     * coordinates, plus whether that POM is inside this workspace.
     */
    public static final String CONTEXT_DECLARED_BY = "declared-by";

    /**
     * Validates that identity fields are present.
     *
     * @param category the structured event family the finding came from
     * @param severity whether the finding is build-breaking or ledger-evaluated
     * @param key      stable identity for ledger matching
     * @param detail   one human-readable line of context for the receipt
     * @param context  structured evidence; defensively copied, null becomes empty
     */
    public Finding {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(key, "key");
        detail = detail == null ? "" : detail;
        // LinkedHashMap, not Map.copyOf — the receipt renders context in
        // the order the observer recorded it, most identifying first.
        context = context == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(context));
    }

    /**
     * Creates a finding with no structured context.
     *
     * @param category the structured event family the finding came from
     * @param severity whether the finding is build-breaking or ledger-evaluated
     * @param key      stable identity for ledger matching
     * @param detail   one human-readable line of context for the receipt
     */
    public Finding(FindingCategory category, Severity severity, String key, String detail) {
        this(category, severity, key, detail, Map.of());
    }

    /**
     * Returns a copy of this finding with one more context entry.
     *
     * <p>Used at session end, when evidence that was not available at
     * observation time — repository provenance, for instance — becomes
     * derivable.</p>
     *
     * @param name  the context key
     * @param value the context value; a null or blank value is ignored
     * @return this finding when the value is absent, otherwise a copy
     */
    public Finding withContext(String name, String value) {
        if (value == null || value.isBlank()) {
            return this;
        }
        Map<String, String> merged = new LinkedHashMap<>(context);
        merged.put(name, value);
        return new Finding(category, severity, key, detail, merged);
    }
}
