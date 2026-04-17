package network.ike.workspace;

import java.util.List;

/**
 * A workspace component — one git repository in the workspace manifest.
 *
 * @param name         the component identifier (directory name and YAML key)
 * @param type         the subproject's type (enum, from the {@code type:}
 *                     field in workspace.yaml)
 * @param description  human-readable purpose
 * @param repo         git clone URL
 * @param branch       the branch to track
 * @param version      Maven version string, or null if not versioned
 * @param groupId      Maven groupId
 * @param dependsOn    inter-repository dependencies
 * @param notes        free-text migration or status notes
 * @param mavenVersion Maven version for the wrapper (e.g., "4.0.0-rc-5"),
 *                     overrides {@link Defaults#mavenVersion()}. Null to inherit.
 * @param parent       component name of the Maven parent POM, or null if the
 *                     parent is not a workspace component. Used by ws:verify
 *                     and ws:align to enforce parent version alignment.
 * @param sha          git commit SHA to check out. When present, {@code ws:init}
 *                     checks out this exact commit instead of branch HEAD.
 *                     Written by {@code ws:checkpoint-publish}. Null means use
 *                     branch HEAD.
 */
public record Component(
        String name,
        SubprojectType type,
        String description,
        String repo,
        String branch,
        String version,
        String groupId,
        List<Dependency> dependsOn,
        String notes,
        String mavenVersion,
        String parent,
        String sha
) {}
