package network.ike.plugin.support;

import org.apache.maven.api.plugin.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Per-goal report writer for IKE plugins.
 *
 * <p>Each goal writes its own file directly in the Maven project root
 * the goal was executed from (alongside the invoking {@code pom.xml}).
 * Files are <b>overwritten</b> on each run (not appended), so the
 * content always reflects the latest execution.
 *
 * <p>Filenames use {@code ꞉} (U+A789 MODIFIER LETTER COLON) to cluster
 * visually as {@code <prefix>꞉goal-name.md} in IDE file browsers. For
 * draft/publish goals, the filename includes the variant:
 * {@code ike꞉release-draft.md}, {@code ike꞉release-publish.md},
 * {@code idoc꞉asciidoc.md}.
 *
 * <p><strong>Self-healing gitignore:</strong> before writing, this
 * class ensures {@code <prefix>꞉*.md} is listed in the
 * {@code .gitignore} of the nearest {@code .git} ancestor. If the
 * pattern is missing, it is appended. This keeps reports out of git
 * without any manual setup — a fresh clone of a consumer repo becomes
 * report-ready the first time an IKE goal runs. One entry is added
 * per plugin prefix seen.
 *
 * <p>Parallels {@code network.ike.plugin.ws.WorkspaceReport} in the
 * ws plugin; both writers target their respective roots (the workspace
 * root for ws, the project root for ike/idoc) and both self-heal the
 * nearest {@code .gitignore}.
 *
 * <p>Replaces the previous {@code network.ike.plugin.IkeReport} as part
 * of the plugin split (ike-issues #215).
 */
public final class GoalReport {

    /** U+A789 MODIFIER LETTER COLON — filesystem-safe visual colon. */
    private static final char COLON = '\uA789';

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private GoalReport() {}

    /**
     * Write a goal's report to its per-goal file at the project root,
     * overwriting any previous content. Self-heals the nearest
     * {@code .gitignore} if needed so the report does not land in git.
     *
     * @param projectRoot the Maven project root the goal executed from
     * @param goal        the goal whose output is being reported
     * @param content     markdown content to write
     * @param log         Maven logger (null-safe)
     */
    public static void write(Path projectRoot, GoalRef goal,
                              String content, Log log) {
        String filename = goal.pluginPrefix() + COLON + goal.goalName() + ".md";
        Path reportFile = projectRoot.resolve(filename);

        try {
            ensureGitignored(projectRoot, gitignorePattern(goal), log);

            String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
            String fullContent = "# " + goal.qualified() + "\n"
                    + "_" + timestamp + "_\n\n"
                    + content.stripTrailing() + "\n";

            Files.writeString(reportFile, fullContent, StandardCharsets.UTF_8);
        } catch (IOException e) {
            if (log != null) {
                log.debug("Could not write report " + filename + ": "
                        + e.getMessage());
            }
        }
    }

    /**
     * Resolve the report file path for a specific goal.
     *
     * @param projectRoot the Maven project root
     * @param goal        the goal whose report is being located
     * @return path to the report file (may not exist yet)
     */
    public static Path reportPath(Path projectRoot, GoalRef goal) {
        String filename = goal.pluginPrefix() + COLON + goal.goalName() + ".md";
        return projectRoot.resolve(filename);
    }

    /**
     * The {@code .gitignore} glob used to cover every report file
     * emitted by a given goal's plugin prefix, e.g. {@code ike꞉*.md},
     * {@code idoc꞉*.md}.
     *
     * @param goal the goal whose plugin prefix seeds the glob
     * @return the gitignore glob string
     */
    static String gitignorePattern(GoalRef goal) {
        return goal.pluginPrefix() + COLON + "*.md";
    }

    /**
     * Walk up from {@code projectRoot} looking for a {@code .git}
     * directory; ensure its sibling {@code .gitignore} lists
     * {@code pattern}. If the file is missing, create it. If the
     * pattern is missing, append it. No-op when no {@code .git}
     * ancestor is found (e.g. the module is not yet in a git repo).
     *
     * @param projectRoot the Maven project root to search from
     * @param pattern     the gitignore glob to ensure is present
     * @param log         Maven logger (null-safe)
     * @throws IOException if the gitignore file cannot be read or written
     */
    static void ensureGitignored(Path projectRoot, String pattern, Log log)
            throws IOException {
        Path gitRoot = findGitRoot(projectRoot);
        if (gitRoot == null) return;

        Path gitignore = gitRoot.resolve(".gitignore");
        if (Files.exists(gitignore)) {
            List<String> lines = Files.readAllLines(gitignore,
                    StandardCharsets.UTF_8);
            for (String line : lines) {
                if (matchesPattern(line.trim(), pattern)) {
                    return;
                }
            }
            String existing = Files.readString(gitignore,
                    StandardCharsets.UTF_8);
            String appended = existing.endsWith("\n") ? existing
                    : existing + "\n";
            Files.writeString(gitignore,
                    appended + "\n# " + prefixHeader(pattern)
                            + " goal reports\n" + pattern + "\n",
                    StandardCharsets.UTF_8);
        } else {
            Files.writeString(gitignore,
                    "# " + prefixHeader(pattern) + " goal reports\n"
                            + pattern + "\n",
                    StandardCharsets.UTF_8);
        }
        if (log != null) {
            log.info("Added " + pattern
                    + " to " + gitRoot.relativize(gitignore));
        }
    }

    private static String prefixHeader(String pattern) {
        int colonIx = pattern.indexOf(COLON);
        return (colonIx > 0 ? pattern.substring(0, colonIx) : "ike") + ":*";
    }

    /**
     * Walk up from {@code start} looking for a directory that contains
     * a {@code .git} entry (directory or file — the latter for
     * worktrees and submodules).
     *
     * @param start the starting directory for the search
     * @return the git root directory, or {@code null} if none is found
     */
    private static Path findGitRoot(Path start) {
        Path current = start.toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve(".git"))) return current;
            current = current.getParent();
        }
        return null;
    }

    /**
     * Test whether a {@code .gitignore} line (with comments and leading
     * slash handling) matches the given pattern. Treats {@code session},
     * {@code session/}, and {@code /session/} as equivalent forms.
     *
     * @param line    the {@code .gitignore} line, already trimmed
     * @param pattern the normalized pattern to match against
     * @return {@code true} if the line covers the pattern
     */
    private static boolean matchesPattern(String line, String pattern) {
        if (line.isEmpty() || line.startsWith("#")) return false;
        String normalized = line.startsWith("/") ? line.substring(1) : line;
        String stripped = pattern.endsWith("/")
                ? pattern.substring(0, pattern.length() - 1)
                : pattern;
        String lineStripped = normalized.endsWith("/")
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
        return lineStripped.equals(stripped);
    }
}
