package network.ike.plugin;

import network.ike.plugin.support.AbstractGoalMojo;
import network.ike.plugin.support.GoalReportBuilder;
import network.ike.plugin.support.GoalReportSpec;
import network.ike.workspace.cascade.CascadeAssembler;
import network.ike.workspace.cascade.CascadeEdge;
import network.ike.workspace.cascade.CascadeRepo;
import network.ike.workspace.cascade.EdgeKind;
import network.ike.workspace.cascade.ProjectCascade;
import network.ike.workspace.cascade.ProjectCascadeIo;
import network.ike.workspace.cascade.ReleaseCascade;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Releases the whole IKE foundation cascade in topological order
 * (IKE-Network/ike-issues#419).
 *
 * <p>The cascade is decentralized (#420): each foundation repo
 * version-controls its own {@code src/main/cascade/release-cascade.yaml}
 * declaring only its own edges. This goal reads the local repo's
 * manifest, walks the edges into the sibling checkouts to assemble
 * the full ordered graph, and runs {@code ike:release-publish} on
 * every release-pending member in topological order. Each member's
 * Nexus deploy completes before the next (which
 * {@code ike:release-publish} aligns to its upstreams via
 * {@code alignUpstreamProperties}, #419-B) begins.
 *
 * <p>A member is release-pending when either:
 * <ul>
 *   <li>it has at least one non-release-cadence commit since its
 *       latest {@code v*} tag (substantive change), OR</li>
 *   <li>at least one of its upstream's {@code ${X.version}} property
 *       pin in the local POM is older than that upstream's latest
 *       released tag (stale upstream pin, #468).</li>
 * </ul>
 *
 * <p>The walk is a single full-graph topological sweep, not a
 * single-source downstream walk: from any starting node the assembler
 * reaches every connected member, and every release-pending node
 * releases exactly once even when the graph has multiple heads that
 * converge on a shared terminal (#468). The walker is idempotent —
 * re-running with no release-pending nodes is a no-op — and
 * crash-safe — re-running after a partial cascade re-evaluates the
 * release-pending set and picks up from the first unfinished member.
 *
 * <p>This is the {@code ike:}-tier cascade executor. The foundation
 * repos cannot form a workspace — {@code ike-workspace-maven-plugin}
 * lives <em>in</em> ike-platform — so the foundation must release with
 * {@code ike:}-tier tooling only.
 *
 * <p>Repo resolution is local: every cascade member is expected to be
 * checked out as a sibling directory alongside the repo this goal runs
 * in (override the containing directory with
 * {@code -Dike.release.cascade.basedir}). A member with no checkout is
 * a hard error — the cascade cannot release what it cannot see.
 *
 * <p>Usage:
 * <pre>
 *   mvn ike:release-cascade                       # release the cascade
 *   mvn ike:release-cascade -DpushRelease=false   # local-only dry of each
 *   mvn ike:release-cascade -Dike.release.cascade.basedir=/path/to/checkouts
 * </pre>
 */
@Mojo(name = IkeGoal.NAME_RELEASE_CASCADE, projectRequired = false, aggregator = true)
public class IkeReleaseCascadeMojo extends AbstractGoalMojo {

    /**
     * Release-cadence commit subjects — commits that are bookkeeping
     * from a prior release, not a reason to release again. Matches the
     * patterns the release flow itself produces so a re-run of a
     * partially-completed cascade does not re-release an up-to-date
     * repo.
     */
    private static final Pattern RELEASE_CADENCE = Pattern.compile(
            "^(release: .+"
                    + "|merge: release .+"
                    + "|post-release: .+"
                    + "|site: publish .+)$");

    /**
     * Directory containing every cascade member as a sibling checkout.
     * Defaults to the parent of the repo this goal runs in.
     */
    @Parameter(property = "ike.release.cascade.basedir")
    String cascadeBaseDir;

    /**
     * Forwarded to {@code ike:release-publish} on each repo. When
     * {@code false}, each release stays local (no tag/main push, no
     * Nexus deploy from a pushed tag).
     */
    @Parameter(property = "pushRelease", defaultValue = "true")
    boolean pushRelease;

    /**
     * Skip the per-repo {@code mvn install -DskipTests} that seeds
     * {@code ~/.m2} with the current SNAPSHOT before its release. The
     * seed exists for the self-hosting reactor bootstrap (#379); skip
     * it when {@code ~/.m2} is already warm.
     */
    @Parameter(property = "skipPreInstall", defaultValue = "false")
    boolean skipPreInstall;

    /** Override working directory for tests. If null, uses current directory. */
    File baseDir;

    /** Creates this goal instance. */
    public IkeReleaseCascadeMojo() {}

    /** What happened to one cascade member. */
    private enum Kind { RELEASED, UP_TO_DATE, SKIPPED, FAILED }

    /** The outcome of processing one cascade member. */
    private record Outcome(String name, Kind kind, String detail) {}

    @Override
    protected GoalReportSpec runGoal() throws MojoException {
        File startDir = baseDir != null ? baseDir : new File(".");
        File gitRoot = ReleaseSupport.gitRoot(startDir);

        ReleaseCascade cascade = assembleCascade(gitRoot);
        File siblings = cascadeBaseDir != null && !cascadeBaseDir.isBlank()
                ? new File(cascadeBaseDir)
                : gitRoot.getParentFile();

        getLog().info("Foundation release cascade — "
                + cascade.repos().size() + " repo(s) in order: "
                + String.join(" → ", cascade.repos().stream()
                        .map(CascadeRepo::repo).toList()));
        getLog().info("");

        List<Outcome> outcomes = new ArrayList<>();
        for (CascadeRepo repo : cascade.repos()) {
            File dir = new File(siblings, repo.repo());
            Outcome outcome = walkOne(dir, repo, siblings);
            outcomes.add(outcome);
            if (outcome.kind() == Kind.FAILED) {
                reportSummary(outcomes);
                throw new MojoException("Release cascade failed at "
                        + repo.repo() + " — " + outcome.detail()
                        + ". Fix it, then re-run "
                        + IkeGoal.RELEASE_CASCADE.qualified()
                        + " to"
                        + " continue with the remaining repos.");
            }
        }

        reportSummary(outcomes);
        return new GoalReportSpec(IkeGoal.RELEASE_CASCADE,
                startDir.toPath(), buildReport(outcomes));
    }

    /**
     * Reads the local repo's {@code release-cascade.yaml} and assembles
     * the full cascade graph by walking its edges into the siblings.
     */
    private ReleaseCascade assembleCascade(File gitRoot) {
        Path localManifest = gitRoot.toPath().resolve(
                ProjectCascadeIo.MANIFEST_RELATIVE_PATH);
        ProjectCascade local = ProjectCascadeIo.load(localManifest)
                .orElseThrow(() -> new MojoException(
                        "No " + ProjectCascadeIo.MANIFEST_RELATIVE_PATH
                        + " in " + gitRoot + " — run "
                        + IkeGoal.RELEASE_CASCADE.qualified()
                        + " from a foundation cascade repo."));

        File rootPom = new File(gitRoot, "pom.xml");
        CascadeEdge start = new CascadeEdge(
                ReleaseSupport.readPomGroupId(rootPom),
                ReleaseSupport.readPomArtifactId(rootPom),
                gitRoot.getName(), null);

        File siblings = cascadeBaseDir != null && !cascadeBaseDir.isBlank()
                ? new File(cascadeBaseDir)
                : gitRoot.getParentFile();
        try {
            return CascadeAssembler.assemble(start, local, edge -> {
                Path p = siblings.toPath().resolve(edge.repo())
                        .resolve(ProjectCascadeIo.MANIFEST_RELATIVE_PATH);
                return ProjectCascadeIo.read(p);
            });
        } catch (RuntimeException e) {
            throw new MojoException(
                    "Cannot assemble the release cascade: "
                    + e.getMessage() + " — every cascade member must be"
                    + " checked out as a sibling directory under "
                    + siblings, e);
        }
    }

    /**
     * Processes one cascade member: detect release-pending state,
     * release the member when its substantive commits or stale
     * upstream pins make it release-pending.
     *
     * <p>The release-pending decision combines two independent signals
     * — substantive commits since the last {@code v*} tag, and stale
     * upstream version-property pins relative to each upstream's
     * latest tag. Either signal is sufficient. Both are reported in
     * the per-member log so the operator can see WHY each member
     * released (or didn't).
     *
     * @param dir      the member's checkout directory
     * @param repo     the member's assembled cascade node (carries
     *                 its upstream edges)
     * @param siblings the parent directory containing every
     *                 cascade-member checkout
     * @return the outcome
     */
    private Outcome walkOne(File dir, CascadeRepo repo, File siblings) {
        String name = repo.repo();
        getLog().info("─── " + name + " ───");
        if (!dir.isDirectory() || !new File(dir, ".git").exists()
                || !new File(dir, "pom.xml").isFile()) {
            getLog().error("  Not a usable checkout at " + dir);
            return new Outcome(name, Kind.FAILED,
                    "no checkout at " + dir);
        }

        String tag = latestReleaseTag(dir);
        int meaningful = tag == null
                ? 0  // first release reported via tag==null branch below
                : meaningfulCommitsSinceTag(dir, tag);
        List<String> stalePins = stalePinsFor(repo, dir, siblings);
        boolean firstRelease = tag == null;

        if (!firstRelease && meaningful == 0 && stalePins.isEmpty()) {
            getLog().info("  At " + tag + "; no meaningful commits"
                    + " since and no stale upstream pins —"
                    + " skipping (already released).");
            getLog().info("");
            return new Outcome(name, Kind.UP_TO_DATE, tag);
        }

        if (firstRelease) {
            getLog().info("  Never released — releasing for the"
                    + " first time.");
        } else if (meaningful > 0) {
            getLog().info("  At " + tag + "; " + meaningful
                    + " meaningful commit(s) since.");
        } else {
            getLog().info("  At " + tag
                    + "; no meaningful commits since.");
        }
        if (!stalePins.isEmpty()) {
            getLog().info("  Stale upstream pin(s) (release-publish"
                    + " will align before tagging):");
            for (String pin : stalePins) {
                getLog().info("    • " + pin);
            }
        }

        File mvnw = ReleaseSupport.resolveMavenWrapper(dir, getLog());
        String mvn = mvnw.getAbsolutePath();

        if (!skipPreInstall) {
            getLog().info("  Seeding ~/.m2 with the current SNAPSHOT...");
            try {
                ReleaseSupport.exec(dir, getLog(),
                        mvn, "install", "-DskipTests", "-T", "4", "-B");
            } catch (RuntimeException e) {
                getLog().warn("  Pre-install failed (continuing —"
                        + " release-publish will surface a real"
                        + " error): " + e.getMessage());
            }
        }

        getLog().info("  Running mvn "
                + IkeGoal.RELEASE_PUBLISH.qualified() + "...");
        try {
            ReleaseSupport.exec(dir, getLog(),
                    mvn, IkeGoal.RELEASE_PUBLISH.qualified(),
                    "-DpushRelease=" + pushRelease, "-B");
            getLog().info("  ✓ Released " + name);
            getLog().info("");
            return new Outcome(name, Kind.RELEASED, null);
        } catch (RuntimeException e) {
            getLog().error("  ✗ Failed to release " + name + ": "
                    + e.getMessage());
            getLog().info("");
            return new Outcome(name, Kind.FAILED, e.getMessage());
        }
    }

    /**
     * Returns descriptions of stale upstream version-property pins
     * for {@code node}. A pin is stale when the local POM's
     * {@code <${X.version}>} value is older than the upstream's
     * latest {@code v*} tag (the upstream's last known release).
     *
     * <p>Format is {@code "ike-tooling.version  (191 → 192)"} so the
     * cascade log can show the operator exactly which property
     * release-publish's {@code alignUpstreamProperties} will bump.
     *
     * <p>Empty list when every upstream is at its latest tag, when
     * {@code node} has no upstream edges (cascade head), or when an
     * upstream's checkout is missing (the walker reports that
     * separately when it tries to walk the missing member).
     *
     * <p>Visible for testing.
     *
     * @param node     the cascade node being inspected
     * @param nodeDir  the node's checkout directory (its POM lives at
     *                 {@code nodeDir/pom.xml})
     * @param siblings the directory containing every cascade-member
     *                 checkout
     * @return per-pin stale descriptions, never null
     */
    static List<String> stalePinsFor(CascadeRepo node, File nodeDir,
                                     File siblings) {
        List<String> stale = new ArrayList<>();
        File pom = new File(nodeDir, "pom.xml");
        if (!pom.isFile()) {
            return stale;
        }
        String pomContent = null;
        for (CascadeEdge up : node.upstream()) {
            File upstreamDir = new File(siblings, up.repo());
            if (!upstreamDir.isDirectory()) {
                continue;
            }
            String upstreamTag = latestReleaseTag(upstreamDir);
            if (upstreamTag == null) {
                continue;
            }
            String upstreamLatest = upstreamTag.startsWith("v")
                    ? upstreamTag.substring(1)
                    : upstreamTag;
            // PARENT-kind edges pin via the <parent><version> block;
            // every other kind pins via the ${G·A} property
            // (IKE-Network/ike-issues#496 part E).
            boolean parentEdge = up.kind() == EdgeKind.PARENT;
            String pinned;
            String displaySite;
            if (parentEdge) {
                if (pomContent == null) {
                    pomContent = readPomContent(pom);
                    if (pomContent == null) {
                        return stale;
                    }
                }
                pinned = PomRewriter.readParentVersion(pomContent,
                        up.groupId(), up.artifactId()).orElse(null);
                displaySite = "<parent>" + up.ga() + "</parent>";
            } else {
                // Try typed-marker form first (post-#525), then fall
                // back to legacy ·-form so stale-pin reporting works
                // on POMs from both sides of the convention boundary.
                String property = up.versionProperty();
                pinned = (property == null || property.isBlank())
                        ? null
                        : ReleaseSupport.readPomProperty(pom, property);
                if (pinned == null) {
                    property = up.versionPropertyLegacy();
                    pinned = ReleaseSupport.readPomProperty(pom, property);
                }
                if (property == null || property.isBlank()) {
                    continue;
                }
                displaySite = property;
            }
            if (pinned == null || pinned.isBlank()
                    || pinned.contains("${")) {
                continue;
            }
            if (!pinned.equals(upstreamLatest)) {
                stale.add(displaySite + "  (" + pinned + " → "
                        + upstreamLatest + ")");
            }
        }
        return stale;
    }

    /**
     * Reads {@code pom.xml} content into a string, returning
     * {@code null} on I/O failure. Used by parent-edge inspection
     * in {@link #stalePinsFor}, which needs the raw text for
     * {@link PomRewriter#readParentVersion}.
     */
    private static String readPomContent(File pom) {
        try {
            return java.nio.file.Files.readString(
                    pom.toPath(),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** The newest {@code v*} release tag in a repo, or null. */
    static String latestReleaseTag(File dir) {
        try {
            String tags = ReleaseSupport.execCapture(dir, "git", "tag",
                    "-l", "v*", "--sort=-version:refname");
            return tags == null || tags.isBlank() ? null
                    : tags.lines().findFirst().orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Counts the commits since {@code tag} that are not release-cadence
     * bookkeeping — the commits that actually warrant a new release.
     */
    private static int meaningfulCommitsSinceTag(File dir, String tag) {
        try {
            String log = ReleaseSupport.execCapture(dir, "git", "log",
                    tag + "..HEAD", "--pretty=format:%s", "--no-merges");
            if (log == null || log.isBlank()) {
                return 0;
            }
            int count = 0;
            for (String line : log.strip().split("\n")) {
                if (!RELEASE_CADENCE.matcher(line.strip()).matches()) {
                    count++;
                }
            }
            return count;
        } catch (RuntimeException e) {
            // Cannot tell — assume there is work, so the cascade does
            // not silently skip a repo.
            return 1;
        }
    }

    /** Logs the per-repo cascade summary table. */
    private void reportSummary(List<Outcome> outcomes) {
        getLog().info("");
        getLog().info("Cascade summary:");
        for (Outcome o : outcomes) {
            getLog().info("  " + marker(o.kind()) + "  " + o.name()
                    + (o.detail() != null ? "  (" + o.detail() + ")"
                            : ""));
        }
    }

    private String buildReport(List<Outcome> outcomes) {
        GoalReportBuilder report = new GoalReportBuilder()
                .section("Release cascade");
        for (Outcome o : outcomes) {
            report.bullet(marker(o.kind()) + " **" + o.name() + "**"
                    + (o.detail() != null ? " — " + o.detail() : ""));
        }
        return report.build();
    }

    private static String marker(Kind kind) {
        return switch (kind) {
            case RELEASED -> "✓ released";
            case UP_TO_DATE -> "— up to date";
            case SKIPPED -> "— skipped";
            case FAILED -> "✗ FAILED";
        };
    }
}
