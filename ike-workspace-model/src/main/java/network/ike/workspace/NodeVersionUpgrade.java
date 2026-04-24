package network.ike.workspace;

import java.util.List;

/**
 * Proposed upgrades for a single node (a module in module scope, or
 * a workspace subproject in workspace scope).
 *
 * <p>The three subsections are applied in order during
 * {@code versions-upgrade-publish}: parent first (so inherited
 * plugin management is current), then property updates, then
 * literal updates. Each subsection may be empty.
 *
 * @param nodeName    the node's name; for workspace plans this is
 *                    the subproject key from {@code workspace.yaml};
 *                    for module plans this is the module's
 *                    {@code artifactId}
 * @param parent      proposed parent update, or null if the node's
 *                    parent POM is not being upgraded
 * @param properties  proposed property updates, in declaration order
 * @param literals    proposed literal-version updates, in POM order
 */
public record NodeVersionUpgrade(
        String nodeName,
        ParentVersionUpgrade parent,
        List<PropertyVersionUpgrade> properties,
        List<LiteralVersionUpgrade> literals
) {
    /**
     * Canonical constructor that defensively copies the lists to
     * preserve record immutability.
     *
     * @param nodeName   see {@link #nodeName()}
     * @param parent     see {@link #parent()}
     * @param properties see {@link #properties()}; null is normalized
     *                   to an empty list
     * @param literals   see {@link #literals()}; null is normalized
     *                   to an empty list
     */
    public NodeVersionUpgrade {
        properties = properties == null ? List.of() : List.copyOf(properties);
        literals = literals == null ? List.of() : List.copyOf(literals);
    }
}
