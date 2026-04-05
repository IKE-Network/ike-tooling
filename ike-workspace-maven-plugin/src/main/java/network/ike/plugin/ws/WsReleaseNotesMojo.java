package network.ike.plugin.ws;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
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
 * Generate release notes from a GitHub milestone's closed issues.
 *
 * <p>Queries the GitHub REST API to find the named milestone, lists
 * its closed issues, and categorizes them by label into Fixes,
 * Enhancements, and Internal sections. JSON responses are parsed
 * via SnakeYAML (JSON is valid YAML), which is already on the
 * classpath for workspace.yaml processing.
 *
 * <pre>{@code
 * mvn ws:release-notes -Dmilestone="ike-tooling v57"
 * mvn ws:release-notes -Dmilestone="ike-tooling v57" -Doutput=release-notes.md
 * }</pre>
 */
@Mojo(name = "release-notes", requiresProject = false, threadSafe = true)
public class WsReleaseNotesMojo extends AbstractWorkspaceMojo {

    /**
     * Milestone name (e.g., "ike-tooling v57"). Prompts if omitted.
     */
    @Parameter(property = "milestone")
    String milestone;

    /**
     * GitHub repository in owner/repo format for the issue tracker.
     */
    @Parameter(property = "repo", defaultValue = "IKE-Network/ike-issues")
    String repo;

    /**
     * Output file path. If omitted, prints to Maven log (stdout).
     */
    @Parameter(property = "output")
    String output;

    /** Creates this goal instance. */
    public WsReleaseNotesMojo() {}

    @Override
    public void execute() throws MojoExecutionException {
        milestone = requireParam(milestone, "milestone", "Milestone name");

        try {
            int milestoneNumber = findMilestone(milestone);
            List<Issue> issues = fetchClosedIssues(milestoneNumber);
            String notes = formatNotes(milestone, issues);

            if (output != null && !output.isBlank()) {
                Path outPath = Path.of(output);
                Files.writeString(outPath, notes, StandardCharsets.UTF_8);
                getLog().info("Release notes written to " + outPath.toAbsolutePath());
            } else {
                for (String line : notes.lines().toList()) {
                    getLog().info(line);
                }
            }
        } catch (IOException | InterruptedException e) {
            throw new MojoExecutionException(
                    "Failed to generate release notes: " + e.getMessage(), e);
        }
    }

    // ── GitHub API ──────────────────────────────────────────────────

    private static final String API_BASE = "https://api.github.com";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private int findMilestone(String title) throws MojoExecutionException,
            IOException, InterruptedException {
        String url = API_BASE + "/repos/" + repo + "/milestones?state=all&per_page=100";
        List<Map<String, Object>> milestones = apiGetList(url);

        for (Map<String, Object> ms : milestones) {
            if (title.equals(ms.get("title"))) {
                return ((Number) ms.get("number")).intValue();
            }
        }

        throw new MojoExecutionException(
                "Milestone not found: \"" + title + "\" in " + repo
                        + ". Use -Dmilestone=\"exact title\" from GitHub.");
    }

    private List<Issue> fetchClosedIssues(int milestoneNumber)
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
                // Skip pull requests (they have a "pull_request" key)
                if (item.containsKey("pull_request")) continue;

                int number = ((Number) item.get("number")).intValue();
                String title = (String) item.get("title");

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

                all.add(new Issue(number, title, labels));
            }

            page++;
        }

        return all;
    }

    /**
     * GET a GitHub API endpoint and parse the JSON array response
     * using SnakeYAML (JSON is valid YAML).
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> apiGetList(String url)
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

    private String apiGet(String url) throws IOException, InterruptedException,
            MojoExecutionException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .GET();

        // Use GITHUB_TOKEN if available (avoids rate limits)
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

    // ── Formatting ──────────────────────────────────────────────────

    private String formatNotes(String milestoneName, List<Issue> issues) {
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

    private void appendSection(StringBuilder sb, String heading,
                               List<Issue> issues) {
        if (issues.isEmpty()) return;

        sb.append("### ").append(heading).append("\n\n");
        for (Issue issue : issues) {
            sb.append("- ").append(issue.title)
                    .append(" (#").append(issue.number).append(")\n");
        }
        sb.append("\n");
    }

    // ── Data ────────────────────────────────────────────────────────

    /** Package-private for test access. */
    record Issue(int number, String title, List<String> labels) {}
}
