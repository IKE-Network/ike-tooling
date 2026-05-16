package network.ike.plugin;

import network.ike.plugin.support.AbstractGoalMojo;
import network.ike.plugin.support.GoalReportBuilder;
import network.ike.plugin.support.GoalReportSpec;
import network.ike.workspace.cascade.CascadeAssembler;
import network.ike.workspace.cascade.CascadeEdge;
import network.ike.workspace.cascade.CascadeRepo;
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
 * every repo that has unreleased changes — in order, so each repo's
 * Nexus deploy completes before the next (which {@code ike:release-publish}
 * aligns to its upstreams, #419-B) begins.
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
@Mojo(name = "release-cascade", projectRequired = false, aggregator = true)
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
            Outcome outcome = walkOne(dir, repo.repo());
            outcomes.add(outcome);
            if (outcome.kind() == Kind.FAILED) {
                reportSummary(outcomes);
                throw new MojoException("Release cascade failed at "
                        + repo.repo() + " — " + outcome.detail()
                        + ". Fix it, then re-run ike:release-cascade to"
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
                        + " in " + gitRoot + " — run ike:release-cascade"
                        + " from a foundation cascade repo."));

        File rootPom = new File(gitRoot, "pom.xml");
        CascadeEdge start = new CascadeEdge(
                ReleaseSupport.readPomGroupId(rootPom),
                ReleaseSupport.readPomArtifactId(rootPom),
                gitRoot.getName(), null, null);

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
     * Processes one cascade member: detect git state, release it when
     * it has unreleased changes.
     *
     * @param dir  the member's checkout directory
     * @param name the member's repo name
     * @return the outcome
     */
    private Outcome walkOne(File dir, String name) {
        getLog().info("─── " + name + " ───");
        if (!dir.isDirectory() || !new File(dir, ".git").exists()
                || !new File(dir, "pom.xml").isFile()) {
            getLog().error("  Not a usable checkout at " + dir);
            return new Outcome(name, Kind.FAILED,
                    "no checkout at " + dir);
        }

        String tag = latestReleaseTag(dir);
        if (tag == null) {
            getLog().info("  Never released — releasing for the"
                    + " first time.");
        } else {
            int meaningful = meaningfulCommitsSinceTag(dir, tag);
            if (meaningful == 0) {
                getLog().info("  At " + tag + "; no meaningful commits"
                        + " since — skipping (already released).");
                getLog().info("");
                return new Outcome(name, Kind.UP_TO_DATE, tag);
            }
            getLog().info("  At " + tag + "; " + meaningful
                    + " meaningful commit(s) since.");
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

        getLog().info("  Running mvn ike:release-publish...");
        try {
            ReleaseSupport.exec(dir, getLog(),
                    mvn, "ike:release-publish",
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

    /** The newest {@code v*} release tag in a repo, or null. */
    private static String latestReleaseTag(File dir) {
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
