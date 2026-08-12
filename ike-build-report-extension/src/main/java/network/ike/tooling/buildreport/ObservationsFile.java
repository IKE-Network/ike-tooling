package network.ike.tooling.buildreport;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The machine-readable observations sidecar
 * ({@code target/build-report-observations.yaml}): per-key observed
 * counts from the latest session, written beside the human receipt so
 * downstream tooling (the ratchet goal) derives from data instead of
 * scraping rendered Markdown.
 */
public final class ObservationsFile {

    private ObservationsFile() {
    }

    /**
     * Writes the sidecar for a finalized session.
     *
     * @param file       the sidecar path; parent directories are created
     * @param evaluation the session's ledger evaluation
     * @param findings   the session's findings (for per-key counts)
     * @throws IOException on write failure
     */
    public static void write(Path file, LedgerEvaluation evaluation, List<Finding> findings)
            throws IOException {
        Objects.requireNonNull(file, "file");
        Map<String, Long> observed = new LinkedHashMap<>();
        for (Finding finding : findings) {
            if (finding.severity() == Severity.WARNING) {
                observed.merge(finding.key(), 1L, Long::sum);
            }
        }
        StringBuilder out = new StringBuilder(512);
        out.append("# Machine-readable build-report observations — written at session\n");
        out.append("# end by ike-build-report-extension, consumed by\n");
        out.append("# ike:build-report-ratchet-draft/-publish. Not for hand edits;\n");
        out.append("# regenerated every session. See ike-issues#989.\n");
        out.append("failures: ").append(evaluation.failures().size()).append('\n');
        out.append("observed:\n");
        for (Map.Entry<String, Long> entry : observed.entrySet()) {
            out.append("  \"").append(entry.getKey()).append("\": ")
                    .append(entry.getValue()).append('\n');
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Reads a sidecar's observed counts.
     *
     * @param file the sidecar path
     * @return per-key observed counts in file order; empty when the file
     *         does not exist
     * @throws IOException              when the file exists but cannot be read
     * @throws IllegalArgumentException when the YAML shape is not a sidecar
     */
    public static Map<String, Long> readObserved(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        if (!Files.exists(file)) {
            return Map.of();
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Object root = yaml.load(reader);
            if (root == null) {
                return Map.of();
            }
            if (!(root instanceof Map) || !(((Map<?, ?>) root).get("observed") instanceof Map)) {
                throw new IllegalArgumentException(
                        "not an observations sidecar: " + file);
            }
            Map<?, ?> raw = (Map<?, ?>) ((Map<?, ?>) root).get("observed");
            Map<String, Long> observed = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                observed.put(String.valueOf(entry.getKey()),
                        ((Number) entry.getValue()).longValue());
            }
            return observed;
        }
    }
}
