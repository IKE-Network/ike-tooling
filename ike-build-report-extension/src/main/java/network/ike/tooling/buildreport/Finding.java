package network.ike.tooling.buildreport;

import java.util.Objects;

/**
 * One observation derived from a structured build event.
 *
 * <p>The {@code key} is the finding's stable identity — composed from
 * structured event fields (category, event type, coordinates such as a
 * repository id or a plugin goal), never from message prose. Ledger
 * entries accept findings by this key with an expected count.</p>
 *
 * @param category the structured event family the finding came from
 * @param severity whether the finding is build-breaking or ledger-evaluated
 * @param key      stable identity, e.g.
 *                 {@code repository/metadata-resolve-failed/private-assets}
 * @param detail   one human-readable line of context for the receipt
 */
public record Finding(FindingCategory category, Severity severity, String key, String detail) {

    /**
     * Validates that identity fields are present.
     *
     * @param category the structured event family the finding came from
     * @param severity whether the finding is build-breaking or ledger-evaluated
     * @param key      stable identity for ledger matching
     * @param detail   one human-readable line of context for the receipt
     */
    public Finding {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(key, "key");
        detail = detail == null ? "" : detail;
    }
}
