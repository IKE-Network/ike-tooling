package network.ike.workspace.cascade;

import java.util.List;

/**
 * Renders an assembled {@link ReleaseCascade} into machine-readable
 * formats for CI consumption (IKE-Network/ike-issues#403, #420).
 *
 * <p>A CI meta-runner that generates build-chain edges from the
 * cascade topology consumes one of these outputs, so the CI build
 * graph derives from the assembled cascade rather than being
 * hand-wired and drifting from it.
 *
 * <ul>
 *   <li>{@link #toJson} — the full graph, for a meta-runner building
 *       the CI project model programmatically.</li>
 *   <li>{@link #toProperties} — a flattened key/value view, for a
 *       meta-runner or shell script that only needs order + edges.</li>
 * </ul>
 */
public final class CascadeExporter {

    private CascadeExporter() {}

    /**
     * Renders the cascade as a JSON document: a {@code cascade} array
     * of {@code {groupId, artifactId, repo, url, consumes[], terminal}}
     * objects in topological order.
     *
     * @param cascade the assembled cascade
     * @return a JSON document string (newline-terminated)
     */
    public static String toJson(ReleaseCascade cascade) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"cascade\": [\n");
        List<CascadeRepo> repos = cascade.repos();
        for (int i = 0; i < repos.size(); i++) {
            CascadeRepo r = repos.get(i);
            sb.append("    {\"groupId\": ").append(jsonString(r.groupId()))
                    .append(", \"artifactId\": ")
                    .append(jsonString(r.artifactId()))
                    .append(", \"repo\": ").append(jsonString(r.repo()))
                    .append(", \"url\": ").append(jsonString(r.url()))
                    .append(", \"consumes\": ").append(jsonArray(r.consumes()))
                    .append(", \"terminal\": ").append(r.terminal())
                    .append("}");
            if (i < repos.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Renders the cascade as a {@code .properties} document: the
     * release order, then per-repo coordinates and {@code consumes}
     * edges keyed by repo name.
     *
     * @param cascade the assembled cascade
     * @return a properties document string (newline-terminated)
     */
    public static String toProperties(ReleaseCascade cascade) {
        StringBuilder sb = new StringBuilder();
        sb.append("# release cascade exported as properties"
                + " (IKE-Network/ike-issues#403)\n");
        sb.append("cascade.repos=")
                .append(String.join(",", cascade.repos().stream()
                        .map(CascadeRepo::repo).toList()))
                .append("\n");
        for (CascadeRepo r : cascade.repos()) {
            String prefix = "cascade." + r.repo() + ".";
            sb.append(prefix).append("groupId=")
                    .append(r.groupId()).append("\n");
            sb.append(prefix).append("artifactId=")
                    .append(r.artifactId()).append("\n");
            if (r.url() != null) {
                sb.append(prefix).append("url=")
                        .append(r.url()).append("\n");
            }
            sb.append(prefix).append("consumes=")
                    .append(String.join(",", r.consumes())).append("\n");
            sb.append(prefix).append("terminal=")
                    .append(r.terminal()).append("\n");
        }
        return sb.toString();
    }

    private static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\"";
    }

    private static String jsonArray(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            sb.append(jsonString(items.get(i)));
            if (i < items.size() - 1) {
                sb.append(", ");
            }
        }
        return sb.append("]").toString();
    }
}
