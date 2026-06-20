package network.ike.plugin;

import network.ike.plugin.support.AbstractGoalMojo;
import network.ike.plugin.support.GoalReportBuilder;
import network.ike.plugin.support.GoalReportSpec;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Renders a "What's changed" changelog from the commits in a release
 * range, for a CI release notification (the Zulip step) to consume
 * (IKE-Network/ike-issues#699, #706).
 *
 * <p>The composition is {@link ReleaseNotesSupport#formatChangelog}:
 * release-machinery commits are filtered out, and each surviving entry
 * is annotated with the full {@code owner/repo#N} issue references
 * parsed from its trailers — repo-agnostic, so a downstream linkifier
 * resolves each reference in whatever tracker it lives, with no tracker
 * hardcoded. This replaces the hand-rolled {@code gh compare | jq}
 * pipeline previously inlined in the TeamCity notify step, putting the
 * logic in one tested place.
 *
 * <p>The range is {@code <from>..<to>}. {@code from} defaults to the
 * previous release tag (auto-derived); {@code to} defaults to
 * {@code HEAD}. When no previous tag is reachable (a repo's first
 * release) the changelog is empty and the caller omits the block.
 *
 * <p>Usage:
 * <pre>
 *   mvn ike:release-changelog                              # prev-tag..HEAD to the log
 *   mvn ike:release-changelog -DoutputFile=target/changelog.md
 *   mvn ike:release-changelog -Dfrom=v222 -Dto=v223
 * </pre>
 *
 * <p>For clean capture into a shell variable, prefer
 * {@code -DoutputFile} and read the file — the build log carries
 * Maven's own decoration.
 */
@Mojo(name = IkeGoal.NAME_RELEASE_CHANGELOG, projectRequired = false, aggregator = true)
public class IkeReleaseChangelogMojo extends AbstractGoalMojo {

    /** The exclusive lower bound; defaults to the previous release tag. */
    @Parameter(property = "from")
    String from;

    /** The inclusive upper bound; defaults to {@code HEAD}. */
    @Parameter(property = "to", defaultValue = "HEAD")
    String to;

    /** File to write the changelog to. When unset, it is logged to stdout. */
    @Parameter(property = "outputFile")
    String outputFile;

    /** Override working directory for tests. If null, uses current directory. */
    File baseDir;

    /** Creates this goal instance. */
    public IkeReleaseChangelogMojo() {}

    @Override
    protected GoalReportSpec runGoal() throws MojoException {
        File startDir = baseDir != null ? baseDir : new File(".");
        File gitRoot = ReleaseSupport.gitRoot(startDir);

        String fromRef = (from != null && !from.isBlank())
                ? from
                : ReleaseNotesSupport.resolvePreviousTag(gitRoot, to);

        String changelog;
        if (fromRef == null || fromRef.isBlank()) {
            // No previous tag (first release) — nothing to compare.
            getLog().info("No previous release tag reachable from " + to
                    + "; changelog is empty.");
            changelog = "";
        } else {
            List<String> commits = ReleaseNotesSupport.commitMessagesBetween(
                    gitRoot, fromRef, to);
            changelog = ReleaseNotesSupport.formatChangelog(commits);
        }

        String location;
        if (outputFile != null && !outputFile.isBlank()) {
            Path out = Path.of(outputFile);
            try {
                if (out.getParent() != null) {
                    Files.createDirectories(out.getParent());
                }
                Files.writeString(out, changelog, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new MojoException(
                        "Could not write " + out + ": " + e.getMessage(), e);
            }
            getLog().info("Release changelog written to " + out);
            location = "written to `" + out + "`";
        } else if (changelog.isEmpty()) {
            getLog().info("Release changelog: (empty)");
            location = "empty";
        } else {
            getLog().info("Release changelog:");
            changelog.lines().forEach(getLog()::info);
            location = "printed to the build log";
        }

        String report = new GoalReportBuilder()
                .section("Release changelog")
                .paragraph("Rendered the changelog for `"
                        + (fromRef == null ? "(first release)" : fromRef)
                        + ".." + to + "`, " + location + ".")
                .codeBlock("markdown",
                        changelog.isEmpty() ? "(empty)" : changelog)
                .build();
        return new GoalReportSpec(IkeGoal.RELEASE_CHANGELOG,
                startDir.toPath(), report);
    }
}
