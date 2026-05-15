package network.ike.plugin;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

/**
 * Displays available IKE build tool goals.
 *
 * @see <a href="https://github.com/IKE-Network/ike-pipeline">IKE Pipeline</a>
 */
@Mojo(name = "help", projectRequired = false)
public class IkeHelpMojo implements org.apache.maven.api.plugin.Mojo {

    @org.apache.maven.api.di.Inject
    private org.apache.maven.api.plugin.Log log;
    /**
     * Access the Maven logger.
     *
     * @return the logger
     */
    protected org.apache.maven.api.plugin.Log getLog() { return log; }

    /** Creates this goal instance. */
    public IkeHelpMojo() {}

    @Override
    public void execute() throws MojoException {
        getLog().info("");
        getLog().info("IKE Build Tools — Available Goals");
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("");
        getLog().info("  ── Workspace Goals ──────────────────────────────────────");
        getLog().info("  ike:dashboard                                   Composite overview (verify+status+cascade)");
        getLog().info("  ike:status                                      Git status across all repos");
        getLog().info("  ike:verify                                      Check manifest + VCS bridge state");
        getLog().info("  ike:cascade                                     Show downstream impact of a change");
        getLog().info("  ike:graph                                       Print dependency graph");
        getLog().info("  ike:init                                        Clone/initialize repos from manifest");
        getLog().info("  ike:pull                                        Git pull --rebase across repos");
        getLog().info("  ike:stignore                                    Generate .stignore for Syncthing");
        getLog().info("  ike:check-branch                                Warn on direct branching (git hook)");
        getLog().info("  ike:ws-sync                                     Sync workspace.yaml ↔ actual branches");
        getLog().info("");
        getLog().info("  ── Editor Goals ─────────────────────────────────────────");
        getLog().info("  ike:adocstudio                                  Generate Adoc Studio sidecar projects (macOS)");
        getLog().info("");
        getLog().info("  ── VCS Bridge Goals ─────────────────────────────────────");
        getLog().info("  ike:setup                                       Install VCS bridge hooks to ~/.git-hooks/");
        getLog().info("  ike:sync                                        Reconcile git state after machine switch");
        getLog().info("  ike:commit                                      Catch-up + commit");
        getLog().info("  ike:push                                        Catch-up + push");
        getLog().info("");
        getLog().info("  ── Gitflow Goals ────────────────────────────────────────");
        getLog().info("  ike:feature-start                               Create feature branch across repos");
        getLog().info("  ike:feature-start-draft                       Preview feature branch creation (interactive)");
        getLog().info("  ike:feature-finish-squash                       Squash-merge (default, deletes branch)");
        getLog().info("  ike:feature-finish-merge                        No-ff merge, keeps branch alive");
        getLog().info("  ike:feature-finish-rebase                       Rebase onto target, linear history");
        getLog().info("  ike:ws-checkpoint                               Record multi-repo checkpoint (SHAs+versions)");
        getLog().info("  ike:ws-checkpoint-draft                       Preview checkpoint without writing files or tags");
        getLog().info("");
        getLog().info("  ── Release Goals ────────────────────────────────────────");
        getLog().info("  ike:help                                        This help message");
        getLog().info("  ike:release-draft                               Preview release + bump to next SNAPSHOT");
        getLog().info("  ike:generate-bom                                Auto-generate BOM from ike-parent");
        getLog().info("  ike:site-draft                                  Report deployed-site drift (version, registration)");
        getLog().info("  ike:site-publish                                Deploy + register; -Dsite=removed to uninstall");
        getLog().info("");
        getLog().info("Options for ike:adocstudio:");
        getLog().info("  -Dadocstudio.sourceDir=<path>   Assembly root (default: current dir)");
        getLog().info("  -Dadocstudio.outputDir=<path>   Sidecar dir (default: ~/Documents/ike-adoc-studio)");
        getLog().info("");
        getLog().info("Options for workspace goals:");
        getLog().info("  -Dworkspace.manifest=<path>  Path to workspace.yaml (auto-detected)");
        getLog().info("  -Dgroup=<name>               Restrict to group (status, init, pull)");
        getLog().info("  -Dcomponent=<name>           Component for ike:cascade (required)");
        getLog().info("  -Dformat=dot                 Graphviz DOT output for ike:graph");
        getLog().info("");
        getLog().info("Options for VCS bridge goals:");
        getLog().info("  -Dmessage=<msg>        Commit message (ike:commit)");
        getLog().info("  -DaddAll=true          Stage all changes before commit");
        getLog().info("  -Dpush=true            Push after commit");
        getLog().info("  -Dremote=<name>        Remote name (default: origin)");
        getLog().info("  -Dforce=true           Overwrite existing hooks (ike:setup)");
        getLog().info("");
        getLog().info("Options for gitflow goals:");
        getLog().info("  -Dfeature=<name>       Feature name (branch: feature/<name>)");
        getLog().info("  -Dgroup=<name>         Restrict to group");
        getLog().info("  -DskipVersion=true     Skip POM version qualification (feature-start)");
        getLog().info("  -DtargetBranch=<name>  Merge target (default: main)");
        getLog().info("  -DkeepBranch=true      Keep branch after merge (feature-finish)");
        getLog().info("  -Dmessage=<msg>        Squash commit message (feature-finish-squash)");
        getLog().info("  -Dpublish=true          Execute (default is preview)");
        getLog().info("");
        getLog().info("Options for ike:ws-sync:");
        getLog().info("  -Dfrom=repos           Update workspace.yaml from repos (default)");
        getLog().info("  -Dfrom=manifest        Switch repos to match workspace.yaml");
        getLog().info("  -Dpublish=true          Execute (default is preview)");
        getLog().info("");
        getLog().info("Options for ike:ws-checkpoint / ike:ws-checkpoint-draft:");
        getLog().info("  -Dname=<name>          Checkpoint name (required)");
        getLog().info("  -DdeploySite=true      Deploy site for each component");
        getLog().info("  -DskipVerify=true      Skip tests during build");
        getLog().info("  -Dpublish=true          Execute without writing files or tags");
        getLog().info("");
        getLog().info("Options for ike:release:");
        getLog().info("  -DreleaseVersion=<v>   Version to release (auto-derived from POM)");
        getLog().info("  -DnextVersion=<v>      Next SNAPSHOT (auto-derived)");
        getLog().info("  -Dpublish=true          Execute (default is preview)");
        getLog().info("  -DskipVerify=true      Skip 'mvnw clean verify'");
        getLog().info("  -DallowBranch=<name>   Allow release from non-main branch");
        getLog().info("  -DdeploySite=false     Skip site deployment");
        getLog().info("");
        getLog().info("Options for ike:site-{draft,publish} (#398):");
        getLog().info("  -DupdateSite=false           Skip the gh-pages site deploy");
        getLog().info("  -DupdateRegistration=false   Skip the landing-page registration");
        getLog().info("  -Dsite=removed               Uninstall: deregister + remove site");
        getLog().info("  -DreleaseVersion=<v>         Override version (default: POM, -SNAPSHOT stripped)");
        getLog().info("");
    }
}
