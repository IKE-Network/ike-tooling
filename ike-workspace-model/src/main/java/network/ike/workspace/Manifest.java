package network.ike.workspace;

import java.util.Map;

/**
 * The top-level workspace manifest, deserialized from {@code workspace.yaml}.
 *
 * <p>This is the typed, immutable representation of the entire manifest.
 * Use {@link ManifestReader#read} to parse YAML into this record.
 *
 * @param schemaVersion   manifest format version (current "1.1"; legacy
 *                        "1.0" still parses under default values)
 * @param generated       date string when the manifest was last updated
 * @param defaults        default values for subproject fields
 * @param workspaceRoot   the workspace root POM's published GAV
 *                        (schema 1.1+, ike-issues#183); {@code null} only
 *                        on legacy manifests pre-dating that schema bump
 * @param subprojects     named subproject definitions (insertion-ordered)
 * @param ide             optional IntelliJ project settings shared across
 *                        collaborators; {@link IdeSettings#EMPTY} when absent
 */
public record Manifest(
        String schemaVersion,
        String generated,
        Defaults defaults,
        WorkspaceRoot workspaceRoot,
        Map<String, Subproject> subprojects,
        IdeSettings ide
) {}
