package network.ike.plugin;

import network.ike.workspace.cascade.CascadeExporter;
import network.ike.workspace.cascade.ReleaseCascade;

/**
 * Output format for {@code ike:cascade-export}
 * (IKE-Network/ike-issues#403). A typed enum rather than a bare
 * string so the format choice is compiler-visible.
 */
public enum CascadeExportFormat {

    /** Full JSON graph — for programmatic CI project-model generation. */
    JSON {
        @Override
        public String render(ReleaseCascade cascade) {
            return CascadeExporter.toJson(cascade);
        }
    },

    /** Flattened {@code .properties} — order + per-repo coordinates and edges. */
    PROPERTIES {
        @Override
        public String render(ReleaseCascade cascade) {
            return CascadeExporter.toProperties(cascade);
        }
    };

    /**
     * Renders the cascade in this format.
     *
     * @param cascade the parsed cascade
     * @return the rendered document
     */
    public abstract String render(ReleaseCascade cascade);

    /**
     * Parses a format name case-insensitively.
     *
     * @param name the format name ({@code json} or {@code properties})
     * @return the matching format
     * @throws IllegalArgumentException if {@code name} is not a known
     *                                  format
     */
    public static CascadeExportFormat fromString(String name) {
        for (CascadeExportFormat format : values()) {
            if (format.name().equalsIgnoreCase(name)) {
                return format;
            }
        }
        throw new IllegalArgumentException(
                "Unknown cascade-export format: '" + name
                        + "' (expected 'json' or 'properties')");
    }
}
