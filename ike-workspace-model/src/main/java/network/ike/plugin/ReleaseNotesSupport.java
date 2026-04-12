package network.ike.plugin;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

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
     */
    public static String generate(String repo, String milestone, Log log)
            throws MojoExecutionException {
        try {
            int milestoneNumber = findMilestone(repo, milestone);
            if (milestoneNumber < 0) return null;

            List<Issue> issues = fetchClosedIssues(repo, milestoneNumber);
            return formatNotes(milestone, issues);
        } catch (IOException | InterruptedException e) {
            if (log != null) {
                log.warn("Release notes generation failed: " + e.getMessage());
            }
            return null;
        }
    }

    /**
     * Try to generate release notes, writing to a temp file suitable
     * for {@code gh release create --notes-file}. Returns the path,
     * or null if notes could not be generated.
     */
    public static Path generateToFile(String repo, String milestone, Log log)
            throws MojoExecutionException {
        String notes = generate(repo, milestone, log);
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
     * @return true if closed, false if not found
     */
    public static boolean closeMilestone(String repo, String milestone, Log log)
            throws MojoExecutionException {
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
     * @return snapshot with closed (ready to test) and open (in progress) issues,
     *         or null if milestone not found
     */
    public static TestingContext snapshotMilestone(String repo, String milestone,
                                                   Log log)
            throws MojoExecutionException {
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
            throws IOException, InterruptedException, MojoExecutionException {
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
            throws IOException, InterruptedException, MojoExecutionException {
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
            throws IOException, InterruptedException, MojoExecutionException {
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
            throws IOException, InterruptedException, MojoExecutionException {
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
     * (authenticated via the gh CLI's keyring, 5,000 req/hr), then
     * falls back to {@code HttpClient} with an optional
     * {@code GITHUB_TOKEN} environment variable (60 req/hr
     * unauthenticated).
     */
    private static String apiGet(String url) throws IOException,
            InterruptedException, MojoExecutionException {
        // Extract the API path from the full URL for gh api
        String apiPath = url.replace(API_BASE + "/", "");

        // Try gh api first — authenticated, higher rate limit
        try {
            return ReleaseSupport.execCapture(new java.io.File("."),
                    "gh", "api", apiPath);
        } catch (MojoExecutionException e) {
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

        String token = System.getenv("GITHUB_TOKEN");
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }

        HttpResponse<String> response = client.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new MojoExecutionException(
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
            throws MojoExecutionException {
        ReleaseSupport.execCapture(new java.io.File("."),
                "gh", "api", "repos/" + repo + "/milestones/" + milestoneNumber,
                "-X", "PATCH", "-f", "state=closed");
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
     */
    public static Path generateFullHistory(String repo, Path outputDir, Log log)
            throws MojoExecutionException {
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
     */
    public static Path generateFullHistoryXhtml(String repo, Path outputDir,
                                                 Log log)
            throws MojoExecutionException {
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
     */
    public static Path generateAsciidocToFile(String repo, String milestone,
                                               Path outputDir, Log log)
            throws MojoExecutionException {
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

        if (issues.isEmpty()) {
            sb.append("No closed issues in this milestone.\n");
        }

        return sb.toString();
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
