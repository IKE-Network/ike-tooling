package network.ike.plugin;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.Log;
import org.yaml.snakeyaml.Yaml;

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
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates release notes from a GitHub milestone's closed issues.
 *
 * <p>Queries the GitHub REST API, categorizes issues by label into
 * Fixes, Enhancements, and Internal sections, and produces markdown.
 * JSON responses are parsed via SnakeYAML (JSON is valid YAML).
 *
 * <p>Used by both {@code ws:release-notes} (standalone) and
 * {@code ike:release} (integrated into the release workflow).
 */
public final class ReleaseNotesSupport {

    private ReleaseNotesSupport() {}

    private static final String API_BASE = "https://api.github.com";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /**
     * A closed issue from a GitHub milestone.
     *
     * @param number issue number
     * @param title  issue title
     * @param labels label names
     */
    public record Issue(int number, String title, List<String> labels) {}

    /**
     * Generate release notes markdown for a named milestone.
     *
     * @param repo      GitHub repository in owner/repo format
     * @param milestone milestone title (e.g., "ike-tooling v57")
     * @param log       Maven logger (may be null for non-Maven callers)
     * @return formatted markdown, or null if the milestone is not found
     * @throws MojoException if the GitHub API call fails
     */
    public static String generate(String repo, String milestone, Log log)
            throws MojoException {
        return generate(repo, milestone, List.of(), log);
    }

    /**
     * Generate release notes markdown for a named milestone, including a
     * "Foundation upgrades" section for any cascade version bumps the
     * release applied (IKE-Network/ike-issues#706).
     *
     * <p>Returns {@code null} only when there is genuinely nothing to
     * report — no milestone <em>and</em> no foundation upgrades — so the
     * caller can fall back to GitHub's auto-generated notes. A
     * cascade-only rebuild (no milestone, but real upstream bumps) yields
     * a non-null body announcing what it was rebuilt against, rather than
     * a silent "no changes."
     *
     * @param repo      GitHub repository in owner/repo format
     * @param milestone milestone title (e.g., "ike-docs v76")
     * @param upgrades  the upstream-version bumps the release applied (may be empty)
     * @param log       Maven logger (may be null for non-Maven callers)
     * @return formatted markdown, or null if there is nothing to report
     * @throws MojoException if the GitHub API call fails
     */
    public static String generate(String repo, String milestone,
            List<CascadeBump> upgrades, Log log) throws MojoException {
        List<CascadeBump> ups = upgrades == null ? List.of() : upgrades;
        try {
            int milestoneNumber = findMilestone(repo, milestone);
            List<Issue> issues = milestoneNumber < 0
                    ? List.of()
                    : fetchClosedIssues(repo, milestoneNumber);
            // Nothing to report at all — let the caller fall back to
            // GitHub's auto-generated commit notes.
            if (milestoneNumber < 0 && ups.isEmpty()) {
                return null;
            }
            return formatNotes(milestone, issues, ups);
        } catch (IOException | InterruptedException e) {
            if (log != null) {
                log.warn("Release notes generation failed: " + e.getMessage());
            }
            // Even if the GitHub API failed, the foundation upgrades are
            // known locally — surface them rather than a silent fallback.
            if (!ups.isEmpty()) {
                return formatNotes(milestone, List.of(), ups);
            }
            return null;
        }
    }

    /**
     * Try to generate release notes, writing to a temp file suitable
     * for {@code gh release create --notes-file}. Returns the path,
     * or null if notes could not be generated.
     *
     * @param repo      GitHub repository in owner/repo format
     * @param milestone milestone title
     * @param log       Maven logger (may be null)
     * @return path to the temp file, or null on failure
     * @throws MojoException if the GitHub API call fails
     */
    public static Path generateToFile(String repo, String milestone, Log log)
            throws MojoException {
        return generateToFile(repo, milestone, List.of(), log);
    }

    /**
     * Try to generate release notes (with a "Foundation upgrades"
     * section sourced from {@code upgrades}, #706), writing to a temp
     * file suitable for {@code gh release create --notes-file}.
     *
     * @param repo      GitHub repository in owner/repo format
     * @param milestone milestone title
     * @param upgrades  the upstream-version bumps the release applied (may be empty)
     * @param log       Maven logger (may be null)
     * @return path to the temp file, or null if there was nothing to report
     * @throws MojoException if the GitHub API call fails
     */
    public static Path generateToFile(String repo, String milestone,
            List<CascadeBump> upgrades, Log log) throws MojoException {
        String notes = generate(repo, milestone, upgrades, log);
        if (notes == null) return null;

        try {
            Path tempFile = Files.createTempFile("release-notes-", ".md");
            Files.writeString(tempFile, notes, StandardCharsets.UTF_8);
            return tempFile;
        } catch (IOException e) {
            if (log != null) {
                log.warn("Could not write release notes to temp file: "
                        + e.getMessage());
            }
            return null;
        }
    }

    // ── GitHub API ──────────────────────────────────────────────────

    /**
     * Close a GitHub milestone by title. Warns if the milestone has
     * open issues remaining.
     *
     * @param repo      GitHub repository in owner/repo format
     * @param milestone milestone title
     * @param log       Maven logger
     * @return true if closed, false if not found
     * @throws MojoException if the GitHub API call fails
     */
    public static boolean closeMilestone(String repo, String milestone, Log log)
            throws MojoException {
        try {
            int number = findMilestone(repo, milestone);
            if (number < 0) return false;

            // Check for remaining open issues
            List<Issue> open = fetchOpenIssues(repo, number);
            if (!open.isEmpty() && log != null) {
                log.warn("Milestone \"" + milestone + "\" has "
                        + open.size() + " open issue(s) remaining:");
                for (Issue issue : open) {
                    log.warn("  #" + issue.number() + " " + issue.title());
                }
            }

            closeMilestoneViaGh(repo, number);

            if (log != null) {
                log.info("Closed milestone: " + milestone);
            }
            return true;
        } catch (IOException | InterruptedException e) {
            if (log != null) {
                log.warn("Could not close milestone: " + e.getMessage());
            }
            return false;
        }
    }

    /**
     * Fetch all issues (open and closed) for a milestone, returning
     * them categorized for a checkpoint testing context snapshot.
     *
     * @param repo      GitHub repository in owner/repo format
     * @param milestone milestone title
     * @param log       Maven logger
     * @return snapshot with closed (ready to test) and open (in progress) issues,
     *         or null if milestone not found
     * @throws MojoException if the GitHub API call fails
     */
    public static TestingContext snapshotMilestone(String repo, String milestone,
                                                   Log log)
            throws MojoException {
        try {
            int number = findMilestone(repo, milestone);
            if (number < 0) return null;

            List<Issue> closed = fetchClosedIssues(repo, number);
            List<Issue> open = fetchOpenIssues(repo, number);

            return new TestingContext(milestone, closed, open);
        } catch (IOException | InterruptedException e) {
            if (log != null) {
                log.warn("Could not snapshot milestone: " + e.getMessage());
            }
            return null;
        }
    }

    /**
     * A snapshot of milestone state for checkpoint testing context.
     *
     * @param milestone  the milestone title
     * @param readyToTest closed issues — completed work available in this build
     * @param inProgress  open issues — work actively changing
     */
    public record TestingContext(String milestone,
                                 List<Issue> readyToTest,
                                 List<Issue> inProgress) {

        /**
         * Format as markdown for inclusion in checkpoint output.
         *
         * @return markdown-formatted testing context
         */
        public String toMarkdown() {
            StringBuilder sb = new StringBuilder();
            sb.append("## Testing Context: ").append(milestone).append("\n\n");

            if (!readyToTest.isEmpty()) {
                sb.append("### Ready to Test\n\n");
                for (Issue issue : readyToTest) {
                    sb.append("- ").append(issue.title())
                            .append(" (#").append(issue.number()).append(")\n");
                }
                sb.append("\n");
            }

            if (!inProgress.isEmpty()) {
                sb.append("### In Progress\n\n");
                for (Issue issue : inProgress) {
                    sb.append("- ").append(issue.title())
                            .append(" (#").append(issue.number()).append(")\n");
                }
                sb.append("\n");
            }

            if (readyToTest.isEmpty() && inProgress.isEmpty()) {
                sb.append("No issues in milestone.\n");
            }

            return sb.toString();
        }

        /**
         * Format as YAML for embedding in checkpoint YAML files.
         *
         * @param indent whitespace prefix for each line
         * @return YAML-formatted testing context
         */
        public String toYaml(String indent) {
            StringBuilder sb = new StringBuilder();
            sb.append(indent).append("testing-context:\n");
            sb.append(indent).append("  milestone: \"").append(milestone).append("\"\n");

            if (!readyToTest.isEmpty()) {
                sb.append(indent).append("  ready-to-test:\n");
                for (Issue issue : readyToTest) {
                    sb.append(indent).append("    - number: ").append(issue.number()).append("\n");
                    sb.append(indent).append("      title: \"").append(escapeYaml(issue.title())).append("\"\n");
                }
            }

            if (!inProgress.isEmpty()) {
                sb.append(indent).append("  in-progress:\n");
                for (Issue issue : inProgress) {
                    sb.append(indent).append("    - number: ").append(issue.number()).append("\n");
                    sb.append(indent).append("      title: \"").append(escapeYaml(issue.title())).append("\"\n");
                }
            }

            return sb.toString();
        }

        private static String escapeYaml(String s) {
            return s.replace("\"", "\\\"");
        }
    }

    static int findMilestone(String repo, String title)
            throws IOException, InterruptedException, MojoException {
        String url = API_BASE + "/repos/" + repo
                + "/milestones?state=all&per_page=100";
        List<Map<String, Object>> milestones = apiGetList(url);

        for (Map<String, Object> ms : milestones) {
            if (title.equals(ms.get("title"))) {
                return ((Number) ms.get("number")).intValue();
            }
        }

        return -1; // Not found — non-fatal
    }

    static List<Issue> fetchClosedIssues(String repo, int milestoneNumber)
            throws IOException, InterruptedException, MojoException {
        List<Issue> all = new ArrayList<>();
        int page = 1;

        while (true) {
            String url = API_BASE + "/repos/" + repo
                    + "/issues?milestone=" + milestoneNumber
                    + "&state=closed&per_page=100&page=" + page;
            List<Map<String, Object>> batch = apiGetList(url);

            if (batch.isEmpty()) break;

            for (Map<String, Object> item : batch) {
                if (item.containsKey("pull_request")) continue;

                int number = ((Number) item.get("number")).intValue();
                String itemTitle = (String) item.get("title");

                List<String> labels = new ArrayList<>();
                Object labelsObj = item.get("labels");
                if (labelsObj instanceof List<?> labelList) {
                    for (Object labelObj : labelList) {
                        if (labelObj instanceof Map<?, ?> labelMap) {
                            Object name = labelMap.get("name");
                            if (name instanceof String s) {
                                labels.add(s);
                            }
                        }
                    }
                }

                all.add(new Issue(number, itemTitle, labels));
            }

            page++;
        }

        return all;
    }

    static List<Issue> fetchOpenIssues(String repo, int milestoneNumber)
            throws IOException, InterruptedException, MojoException {
        List<Issue> all = new ArrayList<>();
        int page = 1;

        while (true) {
            String url = API_BASE + "/repos/" + repo
                    + "/issues?milestone=" + milestoneNumber
                    + "&state=open&per_page=100&page=" + page;
            List<Map<String, Object>> batch = apiGetList(url);

            if (batch.isEmpty()) break;

            for (Map<String, Object> item : batch) {
                if (item.containsKey("pull_request")) continue;

                int number = ((Number) item.get("number")).intValue();
                String itemTitle = (String) item.get("title");

                List<String> labels = new ArrayList<>();
                Object labelsObj = item.get("labels");
                if (labelsObj instanceof List<?> labelList) {
                    for (Object labelObj : labelList) {
                        if (labelObj instanceof Map<?, ?> labelMap) {
                            Object name = labelMap.get("name");
                            if (name instanceof String s) {
                                labels.add(s);
                            }
                        }
                    }
                }

                all.add(new Issue(number, itemTitle, labels));
            }

            page++;
        }

        return all;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> apiGetList(String url)
            throws IOException, InterruptedException, MojoException {
        String body = apiGet(url);
        Yaml yaml = new Yaml();
        Object parsed = yaml.load(body);

        if (parsed instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    result.add((Map<String, Object>) map);
                }
            }
            return result;
        }

        return List.of();
    }

    /**
     * Fetch a GitHub API endpoint. Tries {@code gh api} first
     * (authenticated via the gh CLI's token — {@code GH_TOKEN} or its
     * stored credential, 5,000 req/hr), then falls back to
     * {@code HttpClient} with an optional token from {@code GH_TOKEN}
     * (canonical) or {@code GITHUB_TOKEN} (legacy fallback); an
     * unauthenticated request is limited to 60 req/hr.
     */
    private static String apiGet(String url) throws IOException,
            InterruptedException, MojoException {
        // Extract the API path from the full URL for gh api
        String apiPath = url.replace(API_BASE + "/", "");

        // Try gh api first — authenticated, higher rate limit
        try {
            return ReleaseSupport.execCapture(new java.io.File("."),
                    "gh", "api", apiPath);
        } catch (MojoException e) {
            // gh not available or failed — fall through to HttpClient
        }

        // Fallback: HttpClient with optional GITHUB_TOKEN
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .GET();

        // Canonical GH_TOKEN (gh's own precedence variable), falling back to
        // the legacy GITHUB_TOKEN (IKE-Network/ike-issues#576).
        String token = System.getenv("GH_TOKEN");
        if (token == null || token.isBlank()) {
            token = System.getenv("GITHUB_TOKEN");
        }
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }

        HttpResponse<String> response = client.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new MojoException(
                    "GitHub API returned " + response.statusCode()
                            + " for " + url + ": " + response.body());
        }

        return response.body();
    }

    /**
     * Close a GitHub milestone by number using the {@code gh} CLI,
     * which handles authentication via its own keyring. This avoids
     * requiring {@code GITHUB_TOKEN} for write operations.
     */
    private static void closeMilestoneViaGh(String repo, int milestoneNumber)
            throws MojoException {
        ReleaseSupport.execCapture(new java.io.File("."),
                "gh", "api", "repos/" + repo + "/milestones/" + milestoneNumber,
                "-X", "PATCH", "-f", "state=closed");
    }

    // ── pending-release label removal ───────────────────────────────

    /**
     * A GitHub issue reference parsed from a closing-keyword commit
     * trailer.
     *
     * @param repo   {@code owner/repo} form (e.g., "IKE-Network/ike-issues")
     * @param number issue number
     */
    public record IssueRef(String repo, int number) {}

    /**
     * Matches GitHub's closing-keyword trailers in commit message bodies.
     *
     * <p>Captures: {@code (owner)?/(repo)?#(number)}. Owner and repo
     * are optional to support legacy bare {@code #N} references; the
     * IKE commit-message standard requires the full form.
     *
     * <p>Recognized keywords (case-insensitive): {@code close},
     * {@code closes}, {@code closed}, {@code fix}, {@code fixes},
     * {@code fixed}, {@code resolve}, {@code resolves}, {@code resolved}
     * — matching GitHub's documented auto-close keywords.
     */
    private static final Pattern CLOSING_TRAILER_PATTERN = Pattern.compile(
            "(?im)^\\s*(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?)\\b\\s*:?\\s+"
                    + "(?:([\\w.-]+)/([\\w.-]+))?#(\\d+)\\b");

    /**
     * Matches any IKE-COMMITS.md issue-association trailer — closing
     * keywords ({@code Fixes}, {@code Closes}, {@code Resolves} and
     * variants) or {@code Refs} / {@code Ref} for partial or
     * cross-repo references that should not auto-close.
     *
     * <p>Used by trailer-compliance preflight to verify every commit
     * in a release range references at least one tracked issue.
     */
    private static final Pattern ANY_ISSUE_TRAILER_PATTERN = Pattern.compile(
            "(?im)^\\s*(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?|refs?)"
                    + "\\b\\s*:?\\s+"
                    + "(?:[\\w.-]+/[\\w.-]+)?#\\d+\\b");

    /**
     * Like {@link #ANY_ISSUE_TRAILER_PATTERN} but <em>capturing</em> the
     * {@code (owner)?/(repo)?#(number)} groups, for rendering issue
     * references into a changelog. Matches both closing keywords and
     * {@code Refs}/{@code Ref}.
     */
    private static final Pattern ISSUE_REF_CAPTURE_PATTERN = Pattern.compile(
            "(?im)^\\s*(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?|refs?)"
                    + "\\b\\s*:?\\s+"
                    + "(?:([\\w.-]+)/([\\w.-]+))?#(\\d+)\\b");

    /**
     * Matches a trailing parenthetical of bare {@code #N} issue numbers
     * on a subject line (e.g. {@code " (#705, #706)"}) — stripped from
     * the changelog subject so the authoritative full-form refs from the
     * trailers aren't duplicated alongside ambiguous bare ones.
     */
    private static final Pattern TRAILING_BARE_REFS_PATTERN = Pattern.compile(
            "\\s*\\((?:#\\d+(?:\\s*,\\s*)?)+\\)\\s*$");

    /**
     * Release-machinery commit subjects, filtered out of a changelog —
     * the cadence commits a release produces have no changelog value.
     */
    private static final Pattern MACHINERY_SUBJECT_PATTERN = Pattern.compile(
            "(?i)^(?:release:|merge:|post-release:|workspace: pre-|bump |\\[maven-release).*");

    /**
     * Remove the {@code pending-release} label from every issue
     * referenced by a release-closing trailer ({@code Fixes},
     * {@code Closes}, {@code Resolves} and grammatical variants) in
     * commits between {@code previousTag} and {@code headRef}.
     *
     * <p>Implements the "label = live state" half of the
     * {@code pending-release} pattern defined in {@code IKE-COMMITS.md}:
     * a commit lands marking an issue {@code Fixes …}, the issue gets
     * the {@code pending-release} label as a not-yet-shipped marker,
     * and when the release actually ships the label comes off so
     * {@code is:closed label:pending-release} accurately reflects
     * fixes still awaiting a release.
     *
     * <p>Trailer references must use the full
     * {@code <owner>/<repo>#N} form; bare {@code #N} references are
     * resolved against {@code fallbackRepo}.
     *
     * <p>Pass {@code null} for {@code previousTag} to auto-derive it
     * via {@code git describe --tags --abbrev=0 <headRef>^}. If no
     * previous tag is reachable, label removal is skipped with an
     * informational message.
     *
     * <p>Non-fatal: any failure (missing {@code gh} CLI, missing
     * label, network error, auth error) is logged and the method
     * continues processing the remaining references. The release is
     * already done at this point.
     *
     * @param gitDir       the git working tree
     * @param previousTag  the previous release tag, or null to auto-derive
     * @param headRef      the new release commit or tag (e.g., "v57")
     * @param fallbackRepo {@code owner/repo} for bare {@code #N} refs;
     *                     may be null to ignore bare refs
     * @param log          Maven logger (may be null)
     * @return number of issues from which the label was actually removed
     */
    public static int removePendingReleaseLabels(File gitDir,
                                                  String previousTag,
                                                  String headRef,
                                                  String fallbackRepo,
                                                  Log log) {
        try {
            String prev = previousTag != null ? previousTag
                    : resolvePreviousTag(gitDir, headRef);
            if (prev == null || prev.isBlank()) {
                if (log != null) {
                    log.info("No previous release tag found; "
                            + "skipping pending-release label removal");
                }
                return 0;
            }

            Set<IssueRef> refs = collectClosingTrailerRefs(
                    gitDir, prev, headRef, fallbackRepo);
            if (refs.isEmpty()) {
                if (log != null) {
                    log.info("No release-closing trailers found in "
                            + prev + ".." + headRef);
                }
                return 0;
            }

            if (log != null) {
                log.info("Removing pending-release label from "
                        + refs.size() + " referenced issue(s)...");
            }
            int removed = 0;
            for (IssueRef ref : refs) {
                if (removePendingReleaseLabelOnIssue(ref, log)) {
                    removed++;
                }
            }
            if (log != null) {
                log.info("Removed pending-release label from "
                        + removed + " of " + refs.size()
                        + " referenced issue(s)");
            }
            return removed;
        } catch (Exception e) {
            if (log != null) {
                log.warn("Could not process pending-release labels: "
                        + e.getMessage());
            }
            return 0;
        }
    }

    /**
     * Auto-derive the previous release tag via
     * {@code git describe --tags --abbrev=0 <headRef>^}. Returns null
     * if no previous tag is reachable (e.g., first release of a repo).
     */
    static String resolvePreviousTag(File gitDir, String headRef) {
        try {
            return ReleaseSupport.execCapture(gitDir, "git", "describe",
                    "--tags", "--abbrev=0", headRef + "^");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Collect unique closing-trailer issue references from commits in
     * {@code previousTag..headRef}. Delegates to
     * {@link #parseClosingTrailers} for the actual parsing.
     */
    static Set<IssueRef> collectClosingTrailerRefs(File gitDir,
                                                    String previousTag,
                                                    String headRef,
                                                    String fallbackRepo) {
        try {
            String body = ReleaseSupport.execCapture(gitDir, "git", "log",
                    "--format=%B%n--end--", previousTag + ".." + headRef);
            return parseClosingTrailers(body, fallbackRepo);
        } catch (Exception e) {
            return new LinkedHashSet<>();
        }
    }

    /**
     * The full commit messages (subject + body) in
     * {@code fromRef..toRef}, newest first — the input
     * {@link #formatChangelog} consumes.
     *
     * <p>Reads via {@code git log}, so it works on the release worktree
     * (a full checkout). Returns an empty list if the range can't be
     * read (e.g. a shallow checkout, or no previous tag).
     *
     * @param gitDir  the git working tree
     * @param fromRef the exclusive lower bound (e.g. the previous tag)
     * @param toRef   the inclusive upper bound (e.g. {@code HEAD} or a tag)
     * @return the commit messages, newest first
     */
    public static List<String> commitMessagesBetween(File gitDir,
            String fromRef, String toRef) {
        List<String> messages = new ArrayList<>();
        try {
            String sep = "--ike-commit-end--";
            String out = ReleaseSupport.execCapture(gitDir, "git", "log",
                    "--format=%B%n" + sep, fromRef + ".." + toRef);
            for (String chunk : out.split(Pattern.quote(sep))) {
                String message = chunk.strip();
                if (!message.isEmpty()) {
                    messages.add(message);
                }
            }
        } catch (Exception e) {
            // unreadable range (shallow checkout, missing ref) -> empty
            return new ArrayList<>();
        }
        return messages;
    }

    /**
     * Returns {@code true} if {@code commitMessage} contains at least
     * one IKE-COMMITS.md issue trailer ({@code Fixes}, {@code Closes},
     * {@code Resolves}, {@code Refs} and grammatical variants) with a
     * {@code #N} or {@code <owner>/<repo>#N} reference.
     *
     * <p>Used by release-time preflight to flag commits that violate
     * the "every commit references a tracked issue" rule.
     *
     * @param commitMessage commit message body, including subject and trailers
     * @return true if any issue trailer is present
     */
    public static boolean hasAnyIssueTrailer(String commitMessage) {
        if (commitMessage == null || commitMessage.isEmpty()) {
            return false;
        }
        return ANY_ISSUE_TRAILER_PATTERN.matcher(commitMessage).find();
    }

    /**
     * Parse closing-keyword trailers (e.g., {@code Fixes}, {@code Closes},
     * {@code Resolves} and grammatical variants) from a block of commit
     * message text. Returns unique references in encounter order.
     *
     * <p>Trailers without an explicit {@code owner/repo} prefix are
     * resolved against {@code fallbackRepo}; if {@code fallbackRepo} is
     * null, bare references are ignored.
     *
     * <p>Public so the workspace plugin (in a different module) can
     * call this from {@code ws:checkpoint-publish} per
     * IKE-Network/ike-issues#394 — checkpoint reporting needs the
     * same trailer parser that release-time label removal uses.
     *
     * @param commitMessages concatenated commit message bodies
     * @param fallbackRepo   {@code owner/repo} for bare references, or null
     * @return ordered set of unique issue references found
     */
    public static Set<IssueRef> parseClosingTrailers(String commitMessages,
                                                      String fallbackRepo) {
        Set<IssueRef> refs = new LinkedHashSet<>();
        if (commitMessages == null || commitMessages.isEmpty()) {
            return refs;
        }
        Matcher matcher = CLOSING_TRAILER_PATTERN.matcher(commitMessages);
        while (matcher.find()) {
            String owner = matcher.group(1);
            String repo = matcher.group(2);
            int number = Integer.parseInt(matcher.group(3));
            String fullRepo = (owner != null && repo != null)
                    ? owner + "/" + repo
                    : fallbackRepo;
            if (fullRepo != null) {
                refs.add(new IssueRef(fullRepo, number));
            }
        }
        return refs;
    }

    /**
     * Remove the {@code pending-release} label from a single issue
     * via the {@code gh} CLI. Returns true on success; returns false
     * (and logs at debug level) when the label is not applied, which
     * is the most common case — gh returns HTTP 404 for "Label does
     * not exist" on the target issue.
     */
    private static boolean removePendingReleaseLabelOnIssue(IssueRef ref,
                                                             Log log) {
        try {
            ReleaseSupport.execCapture(new File("."),
                    "gh", "api", "-X", "DELETE",
                    "/repos/" + ref.repo() + "/issues/" + ref.number()
                            + "/labels/pending-release");
            if (log != null) {
                log.info("  Removed pending-release from " + ref.repo()
                        + "#" + ref.number());
            }
            return true;
        } catch (Exception e) {
            if (log != null) {
                log.debug("  pending-release not removed from "
                        + ref.repo() + "#" + ref.number()
                        + " (label not applied or remove failed): "
                        + e.getMessage());
            }
            return false;
        }
    }

    /**
     * Generate a full release history page as AsciiDoc, covering all
     * closed milestones and any closed issues without a milestone.
     * Each milestone becomes a section with categorized issues.
     *
     * @param repo      GitHub repository in owner/repo format
     * @param outputDir directory to write release-notes.adoc into
     * @param log       Maven logger
     * @return the written file path, or null on failure
     * @throws MojoException if the GitHub API call fails
     */
    public static Path generateFullHistory(String repo, Path outputDir, Log log)
            throws MojoException {
        try {
            String url = API_BASE + "/repos/" + repo
                    + "/milestones?state=all&per_page=100&direction=desc&sort=completeness";
            List<Map<String, Object>> milestones = apiGetList(url);

            StringBuilder adoc = new StringBuilder();
            adoc.append("= Release Notes\n\n");

            boolean hasContent = false;

            // Process each milestone (newest first)
            for (Map<String, Object> ms : milestones) {
                String title = (String) ms.get("title");
                int number = ((Number) ms.get("number")).intValue();
                String state = (String) ms.get("state");
                int closedCount = ((Number) ms.get("closed_issues")).intValue();

                if (closedCount == 0) continue;

                List<Issue> closed = fetchClosedIssues(repo, number);
                if (closed.isEmpty()) continue;

                hasContent = true;
                String stateMarker = "open".equals(state) ? " _(in progress)_" : "";
                adoc.append("== ").append(title).append(stateMarker).append("\n\n");

                List<Issue> fixes = new ArrayList<>();
                List<Issue> enhancements = new ArrayList<>();
                List<Issue> internal = new ArrayList<>();

                for (Issue issue : closed) {
                    if (issue.labels.contains("bug")) {
                        fixes.add(issue);
                    } else if (issue.labels.contains("enhancement")) {
                        enhancements.add(issue);
                    } else {
                        internal.add(issue);
                    }
                }

                Comparator<Issue> byNumber = Comparator.comparingInt(i -> i.number);
                fixes.sort(byNumber);
                enhancements.sort(byNumber);
                internal.sort(byNumber);

                appendAsciidocSection(adoc, "Fixes", fixes, repo);
                appendAsciidocSection(adoc, "Enhancements", enhancements, repo);
                appendAsciidocSection(adoc, "Internal", internal, repo);
            }

            if (!hasContent) {
                adoc.append("No release milestones found. See the\n");
                adoc.append("https://github.com/").append(repo)
                        .append("/issues[issue tracker] for details.\n");
            }

            Files.createDirectories(outputDir);
            Path outFile = outputDir.resolve("release-notes.adoc");
            Files.writeString(outFile, adoc.toString(), StandardCharsets.UTF_8);
            return outFile;
        } catch (IOException | InterruptedException e) {
            if (log != null) {
                log.warn("Full release history generation failed: "
                        + e.getMessage());
            }
            return null;
        }
    }

    /**
     * Generate a full release history as an XHTML fragment suitable for
     * inclusion in the Maven site via {@code generatedSiteDirectory}.
     * The fragment is wrapped in a root {@code <div>} with an
     * {@code <h1>} title, matching the format that
     * {@code maven-site-plugin} expects from generated content.
     *
     * @param repo      GitHub repository in owner/repo format
     * @param outputDir directory to write release-notes.xhtml into
     * @param log       Maven logger
     * @return the written file path, or null on failure
     * @throws MojoException if the GitHub API call fails
     */
    public static Path generateFullHistoryXhtml(String repo, Path outputDir,
                                                 Log log)
            throws MojoException {
        try {
            String url = API_BASE + "/repos/" + repo
                    + "/milestones?state=all&per_page=100&direction=desc";
            List<Map<String, Object>> milestones = apiGetList(url);

            StringBuilder html = new StringBuilder();
            html.append("<div class=\"ike-release-notes\">\n");
            html.append("<h1>Release Notes</h1>\n");

            boolean hasContent = false;

            for (Map<String, Object> ms : milestones) {
                String title = (String) ms.get("title");
                int number = ((Number) ms.get("number")).intValue();
                int closedCount = ((Number) ms.get("closed_issues")).intValue();

                if (closedCount == 0) continue;

                List<Issue> closed = fetchClosedIssues(repo, number);
                if (closed.isEmpty()) continue;

                hasContent = true;
                html.append("<h2>").append(escapeHtml(title)).append("</h2>\n");

                List<Issue> fixes = new ArrayList<>();
                List<Issue> enhancements = new ArrayList<>();
                List<Issue> internal = new ArrayList<>();

                for (Issue issue : closed) {
                    if (issue.labels.contains("bug")) fixes.add(issue);
                    else if (issue.labels.contains("enhancement")) enhancements.add(issue);
                    else internal.add(issue);
                }

                appendHtmlSection(html, "Fixes", fixes, repo);
                appendHtmlSection(html, "Enhancements", enhancements, repo);
                appendHtmlSection(html, "Internal", internal, repo);
            }

            if (!hasContent) {
                html.append("<p>No release milestones found. See the ");
                html.append("<a href=\"https://github.com/").append(repo);
                html.append("/milestones\">issue tracker</a> for details.</p>\n");
            }

            html.append("</div>\n");

            Files.createDirectories(outputDir);
            Path outFile = outputDir.resolve("release-notes.xhtml");
            Files.writeString(outFile, html.toString(), StandardCharsets.UTF_8);
            return outFile;
        } catch (IOException | InterruptedException e) {
            if (log != null) {
                log.warn("Release history XHTML generation failed: "
                        + e.getMessage());
            }
            return null;
        }
    }

    private static void appendHtmlSection(StringBuilder html, String heading,
                                           List<Issue> issues, String repo) {
        if (issues.isEmpty()) return;

        html.append("<h3>").append(heading).append("</h3>\n<ul>\n");
        for (Issue issue : issues) {
            html.append("<li>").append(escapeHtml(issue.title()))
                    .append(" (<a href=\"https://github.com/").append(repo)
                    .append("/issues/").append(issue.number())
                    .append("\">#").append(issue.number())
                    .append("</a>)</li>\n");
        }
        html.append("</ul>\n");
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Generate release notes as AsciiDoc and write to a file, suitable
     * for inclusion in the Maven site build. Returns the path, or null
     * if the milestone is not found.
     *
     * @param repo      GitHub repository in owner/repo format
     * @param milestone milestone title
     * @param outputDir directory to write the AsciiDoc file into
     * @param log       Maven logger
     * @return path to the written file, or null on failure
     * @throws MojoException if the GitHub API call fails
     */
    public static Path generateAsciidocToFile(String repo, String milestone,
                                               Path outputDir, Log log)
            throws MojoException {
        try {
            int milestoneNumber = findMilestone(repo, milestone);
            if (milestoneNumber < 0) return null;

            List<Issue> issues = fetchClosedIssues(repo, milestoneNumber);
            String adoc = formatAsciidoc(milestone, issues, repo);

            Files.createDirectories(outputDir);
            Path outFile = outputDir.resolve("release-notes.adoc");
            Files.writeString(outFile, adoc, StandardCharsets.UTF_8);
            return outFile;
        } catch (IOException | InterruptedException e) {
            if (log != null) {
                log.warn("AsciiDoc release notes generation failed: "
                        + e.getMessage());
            }
            return null;
        }
    }

    /**
     * Format release notes as AsciiDoc for site integration.
     *
     * @param milestoneName the milestone title
     * @param issues        closed issues from the milestone
     * @param repo          GitHub repository (e.g., "IKE-Network/ike-issues")
     * @return AsciiDoc-formatted release notes
     */
    public static String formatAsciidoc(String milestoneName, List<Issue> issues,
                                         String repo) {
        List<Issue> fixes = new ArrayList<>();
        List<Issue> enhancements = new ArrayList<>();
        List<Issue> internal = new ArrayList<>();

        for (Issue issue : issues) {
            if (issue.labels.contains("bug")) {
                fixes.add(issue);
            } else if (issue.labels.contains("enhancement")) {
                enhancements.add(issue);
            } else {
                internal.add(issue);
            }
        }

        Comparator<Issue> noteworthy = Comparator
                .<Issue, Boolean>comparing(i -> !i.labels.contains("release-notes"))
                .thenComparingInt(i -> i.number);

        fixes.sort(noteworthy);
        enhancements.sort(noteworthy);
        internal.sort(noteworthy);

        StringBuilder sb = new StringBuilder();
        sb.append("= Release Notes: ").append(milestoneName).append("\n\n");

        appendAsciidocSection(sb, "Fixes", fixes, repo);
        appendAsciidocSection(sb, "Enhancements", enhancements, repo);
        appendAsciidocSection(sb, "Internal", internal, repo);

        if (issues.isEmpty()) {
            sb.append("No closed issues in this milestone.\n");
        }

        return sb.toString();
    }

    private static void appendAsciidocSection(StringBuilder sb, String heading,
                                               List<Issue> issues, String repo) {
        if (issues.isEmpty()) return;

        sb.append("== ").append(heading).append("\n\n");
        for (Issue issue : issues) {
            sb.append("* ").append(issue.title())
                    .append(" (https://github.com/").append(repo)
                    .append("/issues/").append(issue.number())
                    .append("[#").append(issue.number()).append("])\n");
        }
        sb.append("\n");
    }

    // ── Formatting (Markdown) ───────────────────────────────────────

    /**
     * Format release notes as Markdown for GitHub Release bodies.
     *
     * @param milestoneName the milestone title
     * @param issues        closed issues from the milestone
     * @return Markdown-formatted release notes
     */
    public static String formatNotes(String milestoneName, List<Issue> issues) {
        return formatNotes(milestoneName, issues, List.of());
    }

    /**
     * Format release notes as Markdown, including a "Foundation
     * upgrades" section for cascade version bumps
     * (IKE-Network/ike-issues#706).
     *
     * @param milestoneName the milestone title
     * @param issues        closed issues from the milestone
     * @param upgrades      the upstream-version bumps the release applied (may be empty)
     * @return Markdown-formatted release notes
     */
    public static String formatNotes(String milestoneName, List<Issue> issues,
            List<CascadeBump> upgrades) {
        List<CascadeBump> ups = upgrades == null ? List.of() : upgrades;
        List<Issue> fixes = new ArrayList<>();
        List<Issue> enhancements = new ArrayList<>();
        List<Issue> internal = new ArrayList<>();

        for (Issue issue : issues) {
            if (issue.labels.contains("bug")) {
                fixes.add(issue);
            } else if (issue.labels.contains("enhancement")) {
                enhancements.add(issue);
            } else {
                internal.add(issue);
            }
        }

        Comparator<Issue> noteworthy = Comparator
                .<Issue, Boolean>comparing(i -> !i.labels.contains("release-notes"))
                .thenComparingInt(i -> i.number);

        fixes.sort(noteworthy);
        enhancements.sort(noteworthy);
        internal.sort(noteworthy);

        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(milestoneName).append("\n\n");

        appendSection(sb, "Fixes", fixes);
        appendSection(sb, "Enhancements", enhancements);
        appendSection(sb, "Internal", internal);
        appendFoundationUpgrades(sb, ups);

        // Only the genuinely-empty case prints the placeholder. A
        // cascade-only rebuild has no closed issues but real foundation
        // upgrades — that is "what changed," not "no changes" (#706).
        if (issues.isEmpty() && ups.isEmpty()) {
            sb.append("No closed issues in this milestone.\n");
        }

        return sb.toString();
    }

    /**
     * Appends a "Foundation upgrades" section announcing the upstream
     * rebuild — a one-line "Rebuilt against …" summary plus the
     * per-artifact old→new transitions (IKE-Network/ike-issues#706).
     */
    /**
     * Whether a commit subject is release machinery (a cadence commit a
     * release itself produces), and so should be filtered from a
     * changelog.
     *
     * @param subject the commit subject line
     * @return {@code true} for release/merge/post-release/bump machinery
     */
    public static boolean isMachineryCommit(String subject) {
        return subject != null
                && MACHINERY_SUBJECT_PATTERN.matcher(subject.strip()).matches();
    }

    /**
     * Extract every issue reference from a commit message's trailers, in
     * display form, de-duplicated and in first-seen order.
     *
     * <p>The full {@code owner/repo#N} form is preserved verbatim
     * (so a consumer — a Zulip linkifier, a GitHub release body — links
     * it in whatever repo it lives, with no tracker assumed). A bare
     * {@code #N} reference is returned as {@code #N}: its repository is
     * ambiguous and is deliberately <em>not</em> guessed.
     *
     * @param commitMessage the full commit message (subject + body)
     * @return the referenced issues in display form, e.g.
     *         {@code ["IKE-Network/ike-issues#705", "ikmdev/komet-desktop#12"]}
     */
    public static List<String> parseIssueRefs(String commitMessage) {
        List<String> out = new ArrayList<>();
        if (commitMessage == null) {
            return out;
        }
        Matcher m = ISSUE_REF_CAPTURE_PATTERN.matcher(commitMessage);
        while (m.find()) {
            String owner = m.group(1);
            String repo = m.group(2);
            String number = m.group(3);
            String display = (owner != null && repo != null)
                    ? owner + "/" + repo + "#" + number
                    : "#" + number;
            if (!out.contains(display)) {
                out.add(display);
            }
        }
        return out;
    }

    /**
     * Compose a "What's changed" changelog from a list of commit
     * messages: one bullet per substantive commit (release machinery
     * filtered out), each annotated with the full-form issue references
     * parsed from its trailers.
     *
     * <p>Repo-agnostic: no issue tracker is assumed or hardcoded — each
     * reference carries its own {@code owner/repo} from the commit
     * trailer ({@code IKE-COMMITS.md} mandates the full form for exactly
     * this cross-repo reason). A trailing bare-{@code #N} parenthetical
     * on the subject is stripped so refs aren't shown twice.
     *
     * <p>Returns the empty string when nothing substantive remains —
     * the caller omits the whole "What's changed" block in that case.
     *
     * @param commitMessages full commit messages (subject + body) in
     *                       display order (typically newest-first from a
     *                       {@code git log}/compare range)
     * @return the Markdown bullet list, or {@code ""} if empty
     */
    public static String formatChangelog(List<String> commitMessages) {
        if (commitMessages == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String message : commitMessages) {
            if (message == null || message.isBlank()) {
                continue;
            }
            String subject = message.strip().split("\\R", 2)[0].strip();
            if (subject.isEmpty() || isMachineryCommit(subject)) {
                continue;
            }
            List<String> refs = parseIssueRefs(message);
            if (!refs.isEmpty()) {
                // Drop a trailing "(#705, #706)" so the authoritative
                // full-form refs aren't duplicated by ambiguous bare ones.
                subject = TRAILING_BARE_REFS_PATTERN.matcher(subject)
                        .replaceFirst("");
            }
            sb.append("- ").append(subject);
            if (!refs.isEmpty()) {
                sb.append(" (").append(String.join(", ", refs)).append(")");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    // ── Release-cascade Zulip topic grouping (#699) ─────────────────
    //
    // A whole cascade threads under ONE Zulip topic instead of scattering
    // one post per release. Correlation is the topic itself, not a
    // recomputed date: the first build creates the "… — in progress"
    // topic; later builds (even across midnight) find that single open
    // topic and post there; the terminal (ike-platform) renames it to
    // carry the ike-parent version. The date is a human label stamped
    // ONCE by the creator — in the release server's own zone, labelled —
    // so there is no cross-build/cross-timezone date to disagree on.

    /** Suffix marking the open (not-yet-complete) cascade topic. */
    private static final String IN_PROGRESS_SUFFIX =
            " Release Cascade — in progress";

    private static final DateTimeFormatter CASCADE_LABEL_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd z", Locale.US);

    /**
     * The human label for a cascade topic — the creating build's date in
     * its own zone, with the zone abbreviation appended so it is
     * unambiguous across the operators' time zones (e.g.
     * {@code "2026-06-19 PDT"}).
     *
     * <p>Computed once, by the build that creates the topic; never
     * recomputed by other builds (they find the open topic instead), so
     * there is no need for a midnight-safe rollover.
     *
     * @param now  the creating build's instant
     * @param zone the release server's zone (e.g. {@code ZoneId.systemDefault()})
     * @return the label, e.g. {@code "2026-06-19 PDT"}
     */
    public static String cascadeTopicLabel(Instant now, ZoneId zone) {
        return CASCADE_LABEL_FORMAT.format(now.atZone(zone));
    }

    /**
     * The open-cascade topic name for a label, e.g.
     * {@code "2026-06-19 PDT Release Cascade — in progress"}. The
     * {@code "— in progress"} suffix is the correlation key: at most one
     * such topic exists at a time (releases run serially and completion
     * renames it away), so a build finds the cascade by matching it.
     *
     * @param label the {@link #cascadeTopicLabel} value
     * @return the in-progress topic name
     */
    public static String inProgressCascadeTopic(String label) {
        return label + IN_PROGRESS_SUFFIX;
    }

    /**
     * The completed-cascade topic name, carrying the ike-parent version
     * the terminal release establishes, e.g.
     * {@code "2026-06-19 PDT ike-parent Release Cascade v66"}.
     *
     * @param label         the {@link #cascadeTopicLabel} value
     * @param parentVersion the released ike-parent (ike-platform) version
     * @return the completed topic name
     */
    public static String completedCascadeTopic(String label, String parentVersion) {
        return label + " ike-parent Release Cascade v" + parentVersion;
    }

    /**
     * Recover the {@link #cascadeTopicLabel} from an in-progress topic
     * name, so the terminal build can derive the completed name from the
     * topic it found, or {@code null} if {@code topic} is not an
     * in-progress cascade topic.
     *
     * @param topic a Zulip topic name
     * @return the label, or {@code null} if it isn't an in-progress cascade topic
     */
    public static String cascadeLabelOf(String topic) {
        if (topic == null || !topic.endsWith(IN_PROGRESS_SUFFIX)) {
            return null;
        }
        return topic.substring(0, topic.length() - IN_PROGRESS_SUFFIX.length());
    }

    /**
     * A shell-sourceable env snippet carrying the cascade label and
     * in-progress topic name, so a notify step gets everything from one
     * {@code ike:release-changelog} call without recomputing the date in
     * shell. Values are single-quoted; safe because a label is only
     * digits, hyphens, spaces, and a zone abbreviation — never a quote.
     *
     * <pre>
     * CASCADE_LABEL='2026-06-19 PDT'
     * CASCADE_TOPIC_INPROGRESS='2026-06-19 PDT Release Cascade — in progress'
     * </pre>
     *
     * @param label the {@link #cascadeTopicLabel} value
     * @return the env-file content (newline-terminated)
     */
    public static String cascadeMetaEnv(String label) {
        return "CASCADE_LABEL='" + label + "'\n"
                + "CASCADE_TOPIC_INPROGRESS='"
                + inProgressCascadeTopic(label) + "'\n";
    }

    /**
     * A repo released as part of a cascade — for the completion summary.
     *
     * @param artifact the released repo's artifactId (e.g. {@code ike-tooling})
     * @param version  the released version (e.g. {@code 223})
     */
    public record CascadeMember(String artifact, String version) {}

    /**
     * The cascade-completion summary the terminal build posts, naming the
     * ike-parent version and listing every repo released in the cascade.
     *
     * @param parentVersion the released ike-parent (ike-platform) version
     * @param members       the repos released, in cascade order
     * @return the Markdown summary
     */
    public static String formatCascadeSummary(String parentVersion,
            List<CascadeMember> members) {
        StringBuilder sb = new StringBuilder();
        sb.append("**Release cascade complete — ike-parent v")
                .append(parentVersion).append("** ✅\n\n");
        if (members != null) {
            for (CascadeMember m : members) {
                sb.append("- ").append(m.artifact())
                        .append(" v").append(m.version()).append('\n');
            }
        }
        return sb.toString();
    }

    private static void appendFoundationUpgrades(StringBuilder sb,
            List<CascadeBump> upgrades) {
        if (upgrades.isEmpty()) return;

        sb.append("### Foundation upgrades\n\n");
        String rebuiltAgainst = upgrades.stream()
                .map(b -> b.artifactId() + " " + b.latest())
                .collect(java.util.stream.Collectors.joining(", "));
        sb.append("Rebuilt against ").append(rebuiltAgainst).append(".\n\n");
        for (CascadeBump b : upgrades) {
            sb.append("- `").append(b.ga()).append("` ")
                    .append(b.current()).append(" → ").append(b.latest())
                    .append('\n');
        }
        sb.append("\n");
    }

    private static void appendSection(StringBuilder sb, String heading,
                                       List<Issue> issues) {
        if (issues.isEmpty()) return;

        sb.append("### ").append(heading).append("\n\n");
        for (Issue issue : issues) {
            sb.append("- ").append(issue.title)
                    .append(" (#").append(issue.number).append(")\n");
        }
        sb.append("\n");
    }
}
