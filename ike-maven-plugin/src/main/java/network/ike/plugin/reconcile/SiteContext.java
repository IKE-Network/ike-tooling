package network.ike.plugin.reconcile;

import org.apache.maven.api.plugin.Log;

import java.io.File;

/**
 * Context handed to each {@link SiteReconciler} for {@code detect} and
 * {@code apply}. Bundles the per-repo identity (git root, artifact id,
 * project version), org-site coordinates, user-supplied flags, and a
 * logger.
 *
 * <p>Parallels {@code network.ike.plugin.ws.reconcile.WorkspaceContext}
 * but at the per-repo scope rather than the workspace-wide scope. The
 * two are intentionally kept separate — see {@link SiteReconciler} for
 * the rationale.
 *
 * @param gitRoot           the git root of the project whose site is being reconciled
 * @param projectId         the project's Maven artifact id
 * @param projectVersion    the current POM version (SNAPSHOT stripped where relevant)
 * @param projectName       the human-readable project name (POM {@code <name>}) or null
 * @param projectDescription the project description (POM {@code <description>}) or null
 * @param projectSiteUrl    the canonical site URL (e.g. https://ike.network/ike-tooling/)
 * @param githubUrl         the project's GitHub repository URL
 * @param srcRepoUrl        git URL of the org-site SOURCE repo
 * @param srcBranch         branch in the org-site source repo
 * @param pubRepoUrl        git URL of the org-site PUBLISH repo
 * @param pubBranch         branch in the org-site publish repo
 * @param options           user-supplied flag values
 * @param log               Maven logger for reconciler output
 */
public record SiteContext(
        File gitRoot,
        String projectId,
        String projectVersion,
        String projectName,
        String projectDescription,
        String projectSiteUrl,
        String githubUrl,
        String srcRepoUrl,
        String srcBranch,
        String pubRepoUrl,
        String pubBranch,
        SiteReconcilerOptions options,
        Log log) {
}
