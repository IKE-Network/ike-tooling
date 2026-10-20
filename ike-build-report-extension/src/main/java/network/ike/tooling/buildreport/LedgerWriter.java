package network.ike.tooling.buildreport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * Canonical serializer for the acceptance ledger.
 *
 * <p>Once the ratchet goal manages a ledger, the file is tool-written:
 * this writer emits the canonical header and entry shape, and
 * {@link Ledger#load(Path)} of the output reproduces the ledger
 * exactly. Reason and since are data and survive rewrites; ad-hoc
 * comments outside the canonical header do not — prose belongs in the
 * {@code reason} field.</p>
 */
public final class LedgerWriter {

    private LedgerWriter() {
    }

    /**
     * Renders the canonical ledger text.
     *
     * @param ledger the ledger to render
     * @return YAML text that {@link Ledger#load(Path)} parses back to an
     *         equal ledger
     */
    public static String render(Ledger ledger) {
        Objects.requireNonNull(ledger, "ledger");
        StringBuilder out = new StringBuilder(1024);
        out.append("# Build-report acceptance ledger (ike-issues#978, gates ike-issues#989).\n");
        out.append("# Keys are stable finding identities; counts are expected observations\n");
        out.append("# per session. Managed by ike:build-report-ratchet-publish — counts only\n");
        out.append("# ratchet DOWNWARD mechanically; accepting new or grown findings is a\n");
        out.append("# deliberate human edit. Prose belongs in each entry's reason field.\n");
        out.append("mode: ").append(ledger.mode().name().toLowerCase(Locale.ROOT)).append('\n');
        out.append("accepted:\n");
        for (AcceptedEntry entry : ledger.entries()) {
            out.append("  - key: ").append(entry.key()).append('\n');
            out.append("    count: ").append(entry.count()).append('\n');
            if (!entry.reason().isBlank()) {
                out.append("    reason: >\n");
                for (String line : foldReason(entry.reason())) {
                    out.append("      ").append(line).append('\n');
                }
            }
            if (!entry.since().isBlank()) {
                out.append("    since: ").append(entry.since()).append('\n');
            }
            if (entry.entryMode() != null) {
                out.append("    mode: ")
                        .append(entry.entryMode().name().toLowerCase(Locale.ROOT)).append('\n');
            }
        }
        if (!ledger.measures().isEmpty()) {
            out.append("# Bounds on the session's measures (ike-issues#1207): at-most only\n");
            out.append("# ratchets down and at-least only up; slack is the room a tightening\n");
            out.append("# leaves. A regressed measure is an attention finding.\n");
            out.append("measures:\n");
            for (MeasureEntry measure : ledger.measures()) {
                out.append("  - key: ").append(measure.key()).append('\n');
                out.append("    ").append(measure.bound().yaml()).append(": ")
                        .append(Numbers.plain(measure.limit())).append('\n');
                if (measure.slack() != 0.0) {
                    out.append("    slack: ").append(Numbers.plain(measure.slack())).append('\n');
                }
                if (!measure.reason().isBlank()) {
                    out.append("    reason: >\n");
                    for (String line : foldReason(measure.reason())) {
                        out.append("      ").append(line).append('\n');
                    }
                }
                if (!measure.since().isBlank()) {
                    out.append("    since: ").append(measure.since()).append('\n');
                }
                if (measure.entryMode() != null) {
                    out.append("    mode: ")
                            .append(measure.entryMode().name().toLowerCase(Locale.ROOT)).append('\n');
                }
            }
        }
        if (!ledger.sizes().isEmpty()) {
            out.append("# Files or trees the session sizes, by glob under the execution root.\n");
            out.append("sizes:\n");
            for (SizeEntry size : ledger.sizes()) {
                out.append("  - key: ").append(size.key()).append('\n');
                out.append("    path: ").append(quote(size.path())).append('\n');
            }
        }
        if (!ledger.consoleIgnores().isEmpty()) {
            out.append("# Console warnings matching these texts are shown but not counted.\n");
            out.append("console:\n  ignore:\n");
            for (ConsoleIgnore rule : ledger.consoleIgnores()) {
                out.append("    - match: ").append(quote(rule.match())).append('\n');
                if (!rule.reason().isBlank()) {
                    out.append("      reason: >\n");
                    for (String line : foldReason(rule.reason())) {
                        out.append("        ").append(line).append('\n');
                    }
                }
            }
        }
        return out.toString();
    }

    /** Double-quotes match text, which is console prose and may hold any YAML indicator. */
    private static String quote(String text) {
        return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /**
     * Writes the canonical ledger text to a file.
     *
     * @param file   the ledger path, typically {@code .mvn/build-report.yaml}
     * @param ledger the ledger to write
     * @throws IOException on write failure
     */
    public static void write(Path file, Ledger ledger) throws IOException {
        Files.writeString(file, render(ledger), StandardCharsets.UTF_8);
    }

    /**
     * Folds a reason paragraph to lines of at most 68 characters at
     * word boundaries, matching the hand-authored folded-scalar style.
     */
    private static java.util.List<String> foldReason(String reason) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : reason.split("\\s+")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > 68) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }
}
