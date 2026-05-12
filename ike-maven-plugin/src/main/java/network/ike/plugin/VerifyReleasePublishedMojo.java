package network.ike.plugin;

import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

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
import java.util.ArrayList;
import java.util.List;

/**
 * Verify all post-release publication targets are reachable for a given
 * project + version. Runs six read-only HTTP checks against the canonical
 * post-release landing spots documented in {@code cutting-a-release.adoc}.
 *
 * <p>Replaces the operator's manual curl-around-six-URLs sequence with
 * a single goal that hits everything in parallel and reports green/red
 * for each. Composes into release scripts and CI: exits non-zero when
 * any check fails so a shell pipeline can branch on success.
 *
 * <p>All checks are HTTP HEAD or GET against public URLs — no auth, no
 * subprocess to {@code gh}, no Nexus credentials required. The goal is
 * deliberately read-only and safe to run any time.
 *
 * <p>ike-issues#374.
 *
 * <p>Usage:
 * <pre>
 * mvn ike:verify-release-published
 * mvn ike:verify-release-published -DprojectId=ike-tooling -Dversion=163
 * </pre>
 */
@Mojo(name = "verify-release-published", projectRequired = false)
public class VerifyReleasePublishedMojo implements org.apache.maven.api.plugin.Mojo {

    @Inject
    Log log;

    /** Access the Maven logger. */
    Log getLog() { return log; }

    /**
     * Project artifactId being verified. Defaults to the current
     * directory's pom.xml artifactId.
     */
    @Parameter(property = "projectId")
    String projectId;

    /**
     * Release version being verified. Defaults to the current pom.xml
     * version with any {@code -SNAPSHOT} suffix stripped. For
     * post-release worktrees (where pom.xml is already bumped to the
     * next SNAPSHOT), pass the just-released version explicitly via
     * {@code -Dversion=N}.
     */
    @Parameter(property = "version")
    String version;

    /**
     * Site base URL. The released site is expected at
     * {@code <siteBase><projectId>/} and friends.
     */
    @Parameter(property = "siteBase", defaultValue = "https://ike.network/")
    String siteBase;

    /**
     * GitHub org slug. Used to look up the GitHub release and the
     * source repo for the org-site landing page.
     */
    @Parameter(property = "githubOrg", defaultValue = "IKE-Network")
    String githubOrg;

    /**
     * Nexus repository base URL. The released artifact is expected at
     * {@code <nexusBase><groupPath>/<projectId>/<version>/}.
     */
    @Parameter(property = "nexusBase",
               defaultValue = "https://nexus.tinkar.org/repository/ike-public/")
    String nexusBase;

    /**
     * Filesystem path to the pom.xml whose coordinates default the
     * other parameters. Override only when verifying a release from
     * outside its own checkout.
     */
    @Parameter(property = "pomFile", defaultValue = "${project.basedir}/pom.xml")
    File pomFile;

    /**
     * Skip the GitHub release check. Useful when the release tag is
     * already validated by another mechanism or when running offline.
     */
    @Parameter(property = "skipGithubRelease", defaultValue = "false")
    boolean skipGithubRelease;

    /**
     * Skip the org-site landing page check. The org site updates
     * asynchronously after the release tag is pushed; use this flag
     * to verify a release before the org-site sync has run.
     */
    @Parameter(property = "skipOrgSite", defaultValue = "false")
    boolean skipOrgSite;

    /**
     * HTTP request timeout per check, in seconds.
     */
    @Parameter(property = "timeoutSeconds", defaultValue = "10")
    int timeoutSeconds;

    /** Creates this goal instance. */
    public VerifyReleasePublishedMojo() {}

    @Override
    public void execute() throws MojoException {
        resolveDefaults();

        getLog().info("");
        getLog().info("Verifying release " + projectId + " " + version);
        getLog().info("");

        List<CheckResult> results = runChecks();

        getLog().info("  " + padRight("Target", 32)
                + padRight("Result", 10) + "URL");
        getLog().info("  " + "─".repeat(32 + 10 + 40));
        int failures = 0;
        for (CheckResult r : results) {
            String marker = r.ok ? "✓ ok" : (r.skipped ? "— skip" : "✗ FAIL");
            getLog().info("  " + padRight(r.target, 32)
                    + padRight(marker, 10) + r.url);
            if (!r.ok && !r.skipped) failures++;
        }
        getLog().info("");

        if (failures == 0) {
            getLog().info("All checks passed.");
        } else {
            throw new MojoException(failures
                    + " release verification check(s) failed. "
                    + "See output above for details. ike-issues#374.");
        }
    }

    /**
     * Fill in unset parameters from {@link #pomFile}. Called once at
     * the start of {@link #execute()}.
     *
     * @throws MojoException when the pom cannot be read and required
     *                       parameters are not explicitly set
     */
    void resolveDefaults() throws MojoException {
        if (projectId == null || projectId.isBlank()) {
            projectId = readPomField(pomFile, "artifactId");
        }
        if (version == null || version.isBlank()) {
            String pomVersion = readPomField(pomFile, "version");
            if (pomVersion != null) {
                version = pomVersion.replaceFirst("-SNAPSHOT$", "");
            }
        }
        if (projectId == null || projectId.isBlank()) {
            throw new MojoException(
                    "Could not determine projectId. Pass -DprojectId=<id>.");
        }
        if (version == null || version.isBlank()) {
            throw new MojoException(
                    "Could not determine version. Pass -Dversion=<N>.");
        }
    }

    /**
     * Run all six verification checks. Returns one result per check in
     * the order they appear in the output table. Skipped checks (per
     * {@link #skipGithubRelease}, {@link #skipOrgSite}) appear with
     * {@code skipped=true} so the table is complete.
     */
    List<CheckResult> runChecks() {
        List<CheckResult> results = new ArrayList<>();

        // Site: current at root
        results.add(httpCheck("Site (current)",
                siteBase + projectId + "/"));
        // Site: versioned
        results.add(httpCheck("Site (version " + version + ")",
                siteBase + projectId + "/" + version + "/"));
        // Site: latest alias
        results.add(httpCheck("Site (latest)",
                siteBase + projectId + "/latest/"));

        // Org-site landing
        if (skipOrgSite) {
            results.add(CheckResult.skipped("Org-site landing", siteBase));
        } else {
            results.add(httpCheck("Org-site landing", siteBase));
        }

        // Nexus artifact
        String groupPath = readPomGroupPath(pomFile);
        String nexusUrl;
        if (groupPath == null) {
            nexusUrl = nexusBase;
            results.add(CheckResult.fail("Nexus artifact",
                    nexusUrl,
                    "Could not read groupId from pom.xml — pass "
                            + "-DnexusBase=<full URL to artifact dir>."));
        } else {
            nexusUrl = nexusBase + groupPath + "/" + projectId
                    + "/" + version + "/" + projectId + "-"
                    + version + ".pom";
            results.add(httpCheck("Nexus artifact", nexusUrl));
        }

        // GitHub release
        if (skipGithubRelease) {
            results.add(CheckResult.skipped("GitHub release v" + version,
                    "(skipped)"));
        } else {
            String ghUrl = "https://api.github.com/repos/"
                    + githubOrg + "/" + projectId
                    + "/releases/tags/v" + version;
            results.add(httpCheck("GitHub release v" + version, ghUrl));
        }

        return results;
    }

    /**
     * Perform one HEAD-or-GET check against {@code url}. Returns
     * {@code ok=true} for HTTP 2xx, {@code ok=false} otherwise (including
     * timeouts, DNS failures, and connection refused). Tries HEAD first;
     * if the server doesn't support HEAD (some Nexus / GitHub Pages
     * configurations return 405), retries with GET.
     */
    CheckResult httpCheck(String target, String url) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        for (String method : new String[]{"HEAD", "GET"}) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .method(method, HttpRequest.BodyPublishers.noBody())
                        .build();
                HttpResponse<Void> resp = client.send(req,
                        HttpResponse.BodyHandlers.discarding());
                int code = resp.statusCode();
                if (code >= 200 && code < 300) {
                    return CheckResult.ok(target, url);
                }
                if (code == 405 && "HEAD".equals(method)) {
                    continue; // try GET
                }
                return CheckResult.fail(target, url,
                        "HTTP " + code);
            } catch (Exception e) {
                if ("HEAD".equals(method)) continue; // try GET on transport error
                return CheckResult.fail(target, url, e.getClass().getSimpleName()
                        + (e.getMessage() != null ? ": " + e.getMessage() : ""));
            }
        }
        // Both methods failed without producing a definitive result.
        return CheckResult.fail(target, url, "no response");
    }

    /**
     * Extract a top-level POM field value by name. Skips any preceding
     * {@code <parent>} block so we don't return parent coordinates.
     * Returns {@code null} when the field is absent or the file
     * cannot be read.
     */
    static String readPomField(File pom, String fieldName) {
        if (pom == null || !pom.isFile()) return null;
        try {
            String content = Files.readString(pom.toPath(),
                    StandardCharsets.UTF_8);
            int searchFrom = 0;
            int parentOpen = content.indexOf("<parent>");
            if (parentOpen >= 0) {
                int parentClose = content.indexOf("</parent>", parentOpen);
                if (parentClose > parentOpen) {
                    searchFrom = parentClose + "</parent>".length();
                }
            }
            String openTag = "<" + fieldName + ">";
            String closeTag = "</" + fieldName + ">";
            int open = content.indexOf(openTag, searchFrom);
            if (open < 0) return null;
            int valueStart = open + openTag.length();
            int close = content.indexOf(closeTag, valueStart);
            if (close < 0) return null;
            return content.substring(valueStart, close).trim();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Read the pom's groupId and convert it to a Nexus repo path
     * (dots → slashes). Falls back to the parent groupId when the
     * project itself doesn't declare one (the common case).
     */
    static String readPomGroupPath(File pom) {
        if (pom == null || !pom.isFile()) return null;
        String groupId = readPomField(pom, "groupId");
        if (groupId == null) {
            // Try parent groupId
            groupId = readParentField(pom, "groupId");
        }
        return groupId == null ? null : groupId.replace('.', '/');
    }

    /**
     * Extract a field from the {@code <parent>} block of a POM, if
     * present. Used as the groupId fallback when the project doesn't
     * declare its own.
     */
    static String readParentField(File pom, String fieldName) {
        if (pom == null || !pom.isFile()) return null;
        try {
            String content = Files.readString(pom.toPath(),
                    StandardCharsets.UTF_8);
            int parentOpen = content.indexOf("<parent>");
            if (parentOpen < 0) return null;
            int parentClose = content.indexOf("</parent>", parentOpen);
            if (parentClose < 0) return null;
            String parentBlock = content.substring(parentOpen, parentClose);
            String openTag = "<" + fieldName + ">";
            String closeTag = "</" + fieldName + ">";
            int open = parentBlock.indexOf(openTag);
            if (open < 0) return null;
            int valueStart = open + openTag.length();
            int close = parentBlock.indexOf(closeTag, valueStart);
            if (close < 0) return null;
            return parentBlock.substring(valueStart, close).trim();
        } catch (IOException e) {
            return null;
        }
    }

    static String padRight(String s, int width) {
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
    }

    /**
     * One verification check's outcome.
     *
     * @param target   short name shown in the output table
     * @param url      the URL that was checked
     * @param ok       {@code true} when the check passed
     * @param skipped  {@code true} when the check was skipped per a flag
     * @param reason   failure detail (HTTP status, exception class),
     *                 {@code null} on success
     */
    record CheckResult(String target, String url, boolean ok,
                       boolean skipped, String reason) {
        static CheckResult ok(String target, String url) {
            return new CheckResult(target, url, true, false, null);
        }
        static CheckResult fail(String target, String url, String reason) {
            return new CheckResult(target, url, false, false, reason);
        }
        static CheckResult skipped(String target, String url) {
            return new CheckResult(target, url, false, true, null);
        }
    }
}
