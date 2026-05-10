package network.ike.workspace;

/**
 * The workspace root's published Maven coordinates.
 *
 * <p>Persisted under the {@code workspace-root:} block in
 * {@code workspace.yaml} (schema 1.1+), populated by
 * {@code ws:create -Dgroup=...} and {@code ws:adopt-root} (ike-issues#183,
 * #184). Provides a single source of truth for the aggregator artifact's
 * GAV so downstream goals — {@code ws:release-publish} (#185),
 * {@code ws:align-publish}, site deploy (#186) — can reference the
 * workspace root by real
 * coordinates rather than the legacy {@code local.aggregate:<name>:1.0.0-SNAPSHOT}
 * placeholder.
 *
 * <p>The {@code version} field is a single-segment monotonic counter, not
 * semver — it represents \"the Nth release of this workspace manifest,\"
 * nothing more (per {@code feedback_no_semver_assumption}).
 *
 * @param groupId    the Maven groupId for the workspace root POM
 * @param artifactId the Maven artifactId (typically matches the
 *                   workspace's directory name)
 * @param version    the current version, typically ending in
 *                   {@code -SNAPSHOT} between releases
 */
public record WorkspaceRoot(
        String groupId,
        String artifactId,
        String version
) {}
