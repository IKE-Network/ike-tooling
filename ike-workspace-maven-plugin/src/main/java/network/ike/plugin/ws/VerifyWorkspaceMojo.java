package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;
import network.ike.workspace.BomAnalysis;
import network.ike.workspace.Component;
import network.ike.workspace.DependencyConvergenceAnalysis;
import network.ike.workspace.DependencyConvergenceAnalysis.Divergence;
import network.ike.workspace.DependencyTreeParser;
import network.ike.workspace.DependencyTreeParser.ResolvedDependency;
import network.ike.workspace.PublishedArtifactSet;
import network.ike.workspace.WorkspaceGraph;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.plugin.ws.vcs.VcsState;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * Verify workspace manifest consistency and subproject git state.
 *
 * <p>Checks that all dependency references resolve, no cycles exist,
 * all group members are valid, and all component types are defined.
 * Also reports subproject git state, Syncthing health, and environment
 * presence.
 *
 * <pre>{@code mvn ike:verify}</pre>
 */
@Mojo(name = "verify", requiresProject = false, threadSafe = true)
public class VerifyWorkspaceMojo extends AbstractWorkspaceMojo {

    /**
     * Run transitive dependency convergence analysis across all
     * workspace components. Slow — requires {@code mvn dependency:tree}
     * per component.
     */
    @Parameter(property = "checkConvergence", defaultValue = "false")
    boolean checkConvergence;

    /**
     * Output file for the convergence markdown report. Defaults to
     * {@code target/convergence-report.md} in the workspace root.
     */
    @Parameter(property = "convergenceReport")
    String convergenceReport;

    /** Creates this goal instance. */
    public VerifyWorkspaceMojo() {}

    @Override
    public void execute() throws MojoExecutionException {
        getLog().info("");
        getLog().info(header("Verification"));
        getLog().info("══════════════════════════════════════════════════════════════");

        if (isWorkspaceMode()) {
            verifyWorkspaceManifest();
            verifyBomCascade();
            if (checkConvergence) {
                verifyDependencyConvergence();
            }
            verifyWorkspaceVcs();
        } else {
            verifyBareVcs();
        }

        verifyEnvironment();
        getLog().info("");
    }

    // ── Workspace manifest verification (existing logic) ──────────

    private void verifyWorkspaceManifest() throws MojoExecutionException {
        WorkspaceGraph graph = loadGraph();

        List<String> errors = graph.verify();

        int componentCount = graph.manifest().components().size();
        int typeCount = graph.manifest().componentTypes().size();
        int groupCount = graph.manifest().groups().size();

        getLog().info("  Components:      " + componentCount);
        getLog().info("  Component types: " + typeCount);
        getLog().info("  Groups:          " + groupCount);
        getLog().info("");

        if (errors.isEmpty()) {
            getLog().info("  Manifest:    consistent  ✓");
        } else {
            getLog().error("  Manifest:    " + errors.size() + " error(s)");
            for (String error : errors) {
                getLog().error("    ✗ " + error);
            }
        }
    }

    // ── BOM cascade verification ──────────────────────────────────

    private void verifyBomCascade() throws MojoExecutionException {
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();

        // Build published artifact sets for all components
        Map<String, Set<PublishedArtifactSet.Artifact>> workspaceArtifacts =
                new LinkedHashMap<>();
        for (String name : graph.manifest().components().keySet()) {
            java.nio.file.Path compDir = root.toPath().resolve(name);
            if (java.nio.file.Files.exists(compDir.resolve("pom.xml"))) {
                try {
                    workspaceArtifacts.put(name,
                            PublishedArtifactSet.scan(compDir));
                } catch (java.io.IOException e) {
                    getLog().debug("Could not scan " + name + ": " + e.getMessage());
                }
            }
        }

        try {
            var issues = BomAnalysis.analyzeCascadeIssues(
                    root.toPath(), graph.manifest(), workspaceArtifacts);

            if (issues.isEmpty()) {
                getLog().info("");
                getLog().info("  BOM cascade: all dependency edges can cascade  ✓");
            } else {
                getLog().info("");
                getLog().warn("  BOM cascade: " + issues.size() + " gap(s) detected");
                for (var issue : issues) {
                    getLog().warn("    " + issue.componentName() + " → "
                            + issue.dependsOn()
                            + ": no version-property or workspace BOM import");
                    if (!issue.externalBomPins().isEmpty()) {
                        for (var bom : issue.externalBomPins()) {
                            getLog().warn("      external BOM: "
                                    + bom.groupId() + ":" + bom.artifactId()
                                    + ":" + bom.version()
                                    + " (may pin workspace artifact versions)");
                        }
                    }
                }
            }
        } catch (java.io.IOException e) {
            getLog().warn("  BOM cascade check failed: " + e.getMessage());
        }
    }

    // ── Dependency convergence check ───────────────────────────────

    private void verifyDependencyConvergence() throws MojoExecutionException {
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();

        getLog().info("");
        getLog().info("  Dependency convergence (this may take a while)...");
        getLog().info("");

        // Resolve Maven wrapper or mvn for running dependency:tree
        File mvnExecutable = resolveMvn(root);

        // Collect dependency trees per component in topological order
        List<String> order = graph.topologicalSort();
        Map<String, List<ResolvedDependency>> componentTrees =
                new LinkedHashMap<>();

        for (String name : order) {
            File compDir = new File(root, name);
            File pomFile = new File(compDir, "pom.xml");
            if (!pomFile.exists()) {
                getLog().debug("Skipping " + name + " (not cloned)");
                continue;
            }

            getLog().info("    Resolving " + name + "...");
            try {
                String treeOutput = ReleaseSupport.execCapture(compDir,
                        mvnExecutable.getAbsolutePath(),
                        "dependency:tree", "-DoutputType=text",
                        "-B", "-q");
                List<ResolvedDependency> deps =
                        DependencyTreeParser.parse(treeOutput);
                if (!deps.isEmpty()) {
                    componentTrees.put(name, deps);
                }
            } catch (MojoExecutionException e) {
                getLog().warn("    ⚠ " + name + ": dependency:tree failed — "
                        + e.getMessage());
            }
        }

        if (componentTrees.size() < 2) {
            getLog().info("    Fewer than 2 components resolved — skipping analysis");
            return;
        }

        // Analyze
        List<Divergence> divergences =
                DependencyConvergenceAnalysis.analyze(componentTrees);

        // Terminal output
        if (divergences.isEmpty()) {
            getLog().info("");
            getLog().info("  Convergence: all shared dependencies converge across "
                    + componentTrees.size() + " components  ✓");
        } else {
            getLog().info("");
            getLog().warn("  Convergence: " + divergences.size()
                    + " artifact(s) diverge across "
                    + componentTrees.size() + " components");
            getLog().warn("");

            for (Divergence d : divergences) {
                getLog().warn("    " + d.coordinate());
                for (var vEntry : d.versionToComponents().entrySet()) {
                    getLog().warn("      " + vEntry.getKey() + " ← "
                            + String.join(", ", vEntry.getValue()));
                }
            }
        }

        // Markdown report (always write if convergence was checked)
        String wsName = workspaceName();
        String markdown = divergences.isEmpty()
                ? "# Dependency Convergence — " + wsName + "\n\n"
                + "All shared dependencies converge across "
                + componentTrees.size() + " components. ✓\n"
                : DependencyConvergenceAnalysis.formatMarkdownReport(
                divergences, wsName);

        Path reportPath = resolveReportPath(root);
        try {
            Files.createDirectories(reportPath.getParent());
            Files.writeString(reportPath, markdown, StandardCharsets.UTF_8);
            getLog().info("");
            getLog().info("  Report: " + reportPath);
        } catch (IOException e) {
            getLog().warn("  Could not write convergence report: "
                    + e.getMessage());
        }
    }

    private File resolveMvn(File root) throws MojoExecutionException {
        // Prefer mvnw in workspace root
        File mvnw = new File(root, "mvnw");
        if (mvnw.exists() && mvnw.canExecute()) {
            return mvnw;
        }

        // Fall back to mvn on PATH
        try {
            ReleaseSupport.execCapture(root, "mvn", "--version");
            return new File("mvn");
        } catch (MojoExecutionException e) {
            throw new MojoExecutionException(
                    "Cannot find mvnw or mvn. Place mvnw in the workspace "
                            + "root or ensure mvn is on PATH.");
        }
    }

    private Path resolveReportPath(File root) {
        if (convergenceReport != null && !convergenceReport.isBlank()) {
            return Path.of(convergenceReport);
        }
        return root.toPath().resolve("target").resolve("convergence-report.md");
    }

    // ── Subproject git state (workspace mode) ─────────────────────

    private void verifyWorkspaceVcs() throws MojoExecutionException {
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();

        getLog().info("");

        // Workspace repo itself
        if (VcsState.isIkeManaged(root.toPath())) {
            getLog().info("  Workspace");
            reportVcsState(root, "    ");
        }

        // Each component
        for (var entry : graph.manifest().components().entrySet()) {
            String name = entry.getKey();
            File dir = new File(root, name);

            if (!new File(dir, ".git").exists()) {
                continue;
            }

            getLog().info("  " + name);

            if (!VcsState.isIkeManaged(dir.toPath())) {
                getLog().info("    Git state: freshly added (no workspace operations yet)");
                continue;
            }

            reportVcsState(dir, "    ");
        }
    }

    // ── Subproject git state (bare mode) ──────────────────────────

    private void verifyBareVcs() throws MojoExecutionException {
        File dir = new File(System.getProperty("user.dir"));
        String dirName = dir.getName();

        getLog().info("  Machine:     " + hostname());

        if (!VcsState.isIkeManaged(dir.toPath())) {
            getLog().info("  Git state:   freshly added (no workspace operations yet)");
            return;
        }

        getLog().info("");
        getLog().info("  " + dirName);
        reportVcsState(dir, "    ");
    }

    // ── Shared VCS state reporting ───────────────────────────────

    private void reportVcsState(File dir, String indent)
            throws MojoExecutionException {
        String localBranch = gitBranch(dir);
        String localSha = gitShortSha(dir);

        getLog().info(indent + "Branch:        " + localBranch);
        getLog().info(indent + "Local HEAD:    " + localSha);

        Optional<VcsState> stateOpt = VcsState.readFrom(dir.toPath());

        if (stateOpt.isEmpty()) {
            getLog().info(indent + "State file:    absent (first commit, or Syncthing not delivered)");
            getLog().info(indent + "Status:        no state file  ─");
            return;
        }

        VcsState state = stateOpt.get();
        getLog().info(indent + "State file:    " + state.action()
                + " by " + state.machine() + " at " + state.timestamp());
        getLog().info(indent + "State SHA:     " + state.sha());
        getLog().info(indent + "State branch:  " + state.branch());

        // In sync?
        boolean shaMatch = state.sha().equals(localSha);
        boolean branchMatch = state.branch().equals(localBranch);

        if (shaMatch && branchMatch) {
            getLog().info(indent + "Status:        in sync  ✓");
            return;
        }

        // Not in sync — diagnose based on action
        if (!branchMatch) {
            diagnoseBranchMismatch(dir, indent, state, localBranch);
        } else {
            diagnoseShaMismatch(dir, indent, state, localSha);
        }
    }

    private void diagnoseBranchMismatch(File dir, String indent,
                                         VcsState state, String localBranch) {
        switch (state.action()) {
            case VcsState.ACTION_FEATURE_START:
                getLog().warn(indent + "Status:        feature branch '"
                        + state.branch() + "' started on " + state.machine()
                        + " at " + state.timestamp());
                getLog().warn(indent + "               You are on '"
                        + localBranch + "'.");
                getLog().warn(indent + "Action:        run 'mvnw ike:sync' to switch to the feature branch");
                break;
            case VcsState.ACTION_FEATURE_FINISH:
                getLog().warn(indent + "Status:        feature finished on "
                        + state.machine() + " at " + state.timestamp()
                        + ", merged to '" + state.branch() + "'");
                getLog().warn(indent + "               You are on '"
                        + localBranch + "'.");
                getLog().warn(indent + "Action:        run 'mvnw ike:sync' to return to '"
                        + state.branch() + "'");
                break;
            default:
                getLog().warn(indent + "Status:        branch mismatch — local '"
                        + localBranch + "', state file '" + state.branch() + "'");
                getLog().warn(indent + "Action:        run 'mvnw ike:sync' to reconcile");
                break;
        }
    }

    private void diagnoseShaMismatch(File dir, String indent,
                                      VcsState state, String localSha) {
        // Check if the state SHA exists on the remote
        Optional<String> remoteSha;
        try {
            remoteSha = VcsOperations.remoteSha(dir, "origin", state.branch());
        } catch (MojoExecutionException e) {
            remoteSha = Optional.empty();
        }

        boolean shaOnRemote = remoteSha.isPresent();

        switch (state.action()) {
            case VcsState.ACTION_COMMIT:
                if (shaOnRemote) {
                    getLog().warn(indent + "Status:        commit on "
                            + state.machine() + " at " + state.timestamp());
                    getLog().warn(indent + "Action:        run 'mvnw ike:sync'");
                } else {
                    getLog().warn(indent + "Status:        commit on "
                            + state.machine() + " at " + state.timestamp()
                            + ", but push did not complete");
                    getLog().warn(indent + "Action:        push from "
                            + state.machine() + " first, then 'mvnw ike:sync' here");
                    getLog().warn(indent + "               Or: IKE_VCS_OVERRIDE=1 to proceed independently");
                }
                break;
            case VcsState.ACTION_PUSH:
                getLog().warn(indent + "Status:        push from "
                        + state.machine() + " at " + state.timestamp());
                getLog().warn(indent + "               Local HEAD behind remote.");
                getLog().warn(indent + "Action:        run 'mvnw ike:sync'");
                break;
            case VcsState.ACTION_RELEASE:
                getLog().warn(indent + "Status:        release performed on "
                        + state.machine() + " at " + state.timestamp());
                getLog().warn(indent + "Action:        run 'mvnw ike:sync'");
                break;
            case VcsState.ACTION_CHECKPOINT:
                getLog().warn(indent + "Status:        checkpoint created on "
                        + state.machine() + " at " + state.timestamp());
                getLog().warn(indent + "Action:        run 'mvnw ike:sync'");
                break;
            default:
                getLog().warn(indent + "Status:        behind ("
                        + state.action() + " on " + state.machine() + ")");
                getLog().warn(indent + "Action:        run 'mvnw ike:sync'");
                break;
        }
    }

    // ── Environment checks ──────────────────────────────────────

    private void verifyEnvironment() {
        File dir = new File(System.getProperty("user.dir"));

        getLog().info("");

        // Standards
        File standards = new File(dir, ".claude/standards");
        if (standards.isDirectory()) {
            getLog().info("  Standards:   .claude/standards/ present  ✓");
        } else {
            getLog().info("  Standards:   .claude/standards/ absent");
        }

        // CLAUDE.md
        File claudeMd = new File(dir, "CLAUDE.md");
        if (claudeMd.exists()) {
            getLog().info("  CLAUDE.md:   present  ✓");
        } else {
            getLog().info("  CLAUDE.md:   absent");
        }

        // Syncthing
        checkSyncthingHealth();
    }

    private void checkSyncthingHealth() {
        int port = 8384;

        // Check for custom port in .ike/config
        File dir = new File(System.getProperty("user.dir"));
        Path config = dir.toPath().resolve(".ike/config");
        if (Files.exists(config)) {
            try {
                Properties props = new Properties();
                props.load(new java.io.StringReader(
                        Files.readString(config, StandardCharsets.UTF_8)));
                String portStr = props.getProperty("syncthing.port");
                if (portStr != null) {
                    port = Integer.parseInt(portStr.trim());
                }
            } catch (Exception e) {
                getLog().debug("Could not read .ike/config: " + e.getMessage());
            }
        }

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/rest/noauth/health"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                getLog().info("  Syncthing:   connected (port " + port + ")  ✓");
            } else {
                getLog().info("  Syncthing:   responded with status "
                        + response.statusCode());
            }
        } catch (Exception e) {
            getLog().info("  Syncthing:   not running (port " + port + ")");
        }
    }

    private String hostname() {
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isEmpty()) {
            try {
                host = java.net.InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                host = "unknown";
            }
        }
        int dot = host.indexOf('.');
        return dot > 0 ? host.substring(0, dot) : host;
    }
}
