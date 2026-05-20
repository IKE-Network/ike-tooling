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
@Mojo(name = IkeGoal.NAME_VERIFY_RELEASE_PUBLISHED, projectRequired = false)
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
     * Skip the gh-pages tree walk. The walk asks the GitHub API for
     * the project's published {@code gh-pages} tree, enumerates every
     * {@code index.html}, and HEAD-checks the corresponding URL. This
     * is what catches submodule-publish gaps and depth-mismatch bugs
     * the fixed-six-URL checks miss (e.g. ike-issues#358 #363
     * regressions where some subsite under a multi-module reactor
     * lands at the wrong depth and 404s). On by default.
     */
    @Parameter(property = "skipGhPagesTreeWalk", defaultValue = "false")
    boolean skipGhPagesTreeWalk;

    /**
     * Path-prefix patterns to skip during the gh-pages tree walk.
     * Comma-separated. Defaults skip the auto-generated javadoc and
     * source-xref trees (huge, page-by-page checking adds little
     * value) and version dirs that aren't the current release
     * (avoids N×N checks across release history).
     *
     * <p>Each entry is matched as a literal prefix against the
     * gh-pages relative path of the index.html's containing
     * directory. {@code "*"} matches any single path segment.
     */
    @Parameter(property = "ghPagesTreeSkip",
               defaultValue = "apidocs/,*/apidocs/,xref/,xref-test/,"
                       + "*/xref/,*/xref-test/")
    String ghPagesTreeSkip;

    /**
     * Skip the org-site landing page check. The org site updates
     * asynchronously after the release tag is pushed; use this flag
     * to verify a release before the org-site sync has run.
     */
    @Parameter(property = "skipOrgSite", defaultValue = "false")
    boolean skipOrgSite;

    /**
     * Skip the subproject topology cross-reference. The cross-
     * reference reads the reactor pom's {@code <subprojects>} or
     * legacy {@code <modules>}, and for each subproject HEAD-checks
     * three canonical publish URLs: {@code <site-base><projectId>/<sub>/},
     * {@code <site-base><projectId>/<version>/<sub>/}, and
     * {@code <site-base><projectId>/latest/<sub>/}. Catches missing-
     * from-tree paths the gh-pages tree walk can't see (the walk
     * only verifies paths that DO exist; this verifies paths that
     * SHOULD exist per the reactor topology). ike-issues#382.
     */
    @Parameter(property = "skipSubprojectTopology", defaultValue = "false")
    boolean skipSubprojectTopology;

    /**
     * Submodule names to skip in the subproject topology check.
     * Comma-separated. Use when a declared subproject doesn't
     * publish a site (rare in IKE; flag for build-only modules).
     */
    @Parameter(property = "subprojectTopologySkip", defaultValue = "")
    String subprojectTopologySkip;

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

        // Subproject topology checks — cross-reference the reactor's
        // <subprojects>/<modules> declarations against gh-pages.
        // Asserts every (submodule × {root, /<version>/, /latest/})
        // URL is reachable. Catches missing-from-tree paths that the
        // gh-pages tree walk can't see (it only HEAD-checks paths
        // already in the tree). ike-issues#382.
        if (skipSubprojectTopology) {
            results.add(CheckResult.skipped("Subproject topology",
                    "(skipped)"));
        } else {
            results.addAll(runSubprojectChecks());
        }

        // gh-pages tree walk — verify every published index.html is
        // actually reachable. Catches submodule-publish gaps and
        // depth-mismatch bugs that the fixed checks above miss.
        if (skipGhPagesTreeWalk) {
            results.add(CheckResult.skipped("gh-pages tree walk",
                    "(skipped)"));
        } else {
            results.addAll(runGhPagesTreeChecks());
        }

        return results;
    }

    /**
     * Cross-reference the reactor's declared subprojects against
     * gh-pages. For each subproject, HEAD-check the three canonical
     * publish URLs:
     * <ul>
     *   <li>{@code <site-base><projectId>/<sub>/} — current alias</li>
     *   <li>{@code <site-base><projectId>/<version>/<sub>/} — versioned</li>
     *   <li>{@code <site-base><projectId>/latest/<sub>/} — latest alias</li>
     * </ul>
     *
     * <p>The submodule directory name is used as both the path
     * segment and (by IKE convention) the artifactId. When a submodule
     * doesn't match this convention or doesn't publish a site, list
     * it in {@link #subprojectTopologySkip} to exclude.
     *
     * <p>Returns a header CheckResult followed by 3×N entries (one
     * per submodule × canonical URL). Empty header-only result when
     * the pom declares no subprojects (single-module project).
     *
     * <p>ike-issues#382.
     */
    List<CheckResult> runSubprojectChecks() {
        List<String> subs = readPomSubprojects(pomFile);
        List<String> skipExplicit = parseSkipPatterns(subprojectTopologySkip);
        String reactorUrlPrefix = siteBase + projectId + "/";
        List<String> reactorSubs = new ArrayList<>();
        List<String> independentSubs = new ArrayList<>();
        File pomDir = pomFile == null ? null : pomFile.getParentFile();
        for (String sub : subs) {
            if (skipExplicit.contains(sub)) continue;
            // Read the submodule's declared <site><url> to decide
            // whether it publishes UNDER the reactor's gh-pages
            // (aggregator-pom case: ike-platform/ike-parent) or as
            // its OWN top-level gh-pages branch (workspace case:
            // workspace-example/doc-example, where doc-example has
            // its own repo and gh-pages branch under
            // https://ike.network/doc-example/).
            String subSiteUrl = null;
            if (pomDir != null) {
                File subPom = new File(new File(pomDir, sub), "pom.xml");
                if (subPom.isFile()) {
                    subSiteUrl = readPomSiteUrlInterpolated(subPom, sub);
                }
            }
            // Three cases:
            //  (1) Submodule declares its OWN <site> under reactor's
            //      URL prefix → in-reactor (ike-parent, ike-workspace-
            //      maven-plugin in ike-platform).
            //  (2) Submodule declares its OWN <site> NOT under
            //      reactor's prefix → independent (doc-example,
            //      project-example, integration-tests-example in
            //      workspace-example).
            //  (3) Submodule declares NO <site>, inherits from parent
            //      with default append-path=true → effectively
            //      under reactor's prefix (ike-bom in ike-platform).
            if (subSiteUrl == null
                    || subSiteUrl.startsWith(reactorUrlPrefix)) {
                reactorSubs.add(sub);
            } else {
                independentSubs.add(sub);
            }
        }
        List<CheckResult> results = new ArrayList<>();
        String header = reactorSubs.size() + " in-reactor"
                + (independentSubs.isEmpty()
                        ? ""
                        : ", " + independentSubs.size()
                                + " independent-skip")
                + " × 3 URL(s)";
        results.add(CheckResult.ok("Subproject topology", header));
        for (String sub : reactorSubs) {
            results.add(httpCheck("  " + sub + "/",
                    reactorUrlPrefix + sub + "/"));
            results.add(httpCheck("  " + version + "/" + sub + "/",
                    reactorUrlPrefix + version + "/" + sub + "/"));
            results.add(httpCheck("  latest/" + sub + "/",
                    reactorUrlPrefix + "latest/" + sub + "/"));
        }
        return results;
    }

    /**
     * Read a submodule pom's {@code <distributionManagement><site><url>}
     * with the two most-common Maven placeholders interpolated:
     * {@code ${project.artifactId}} → the submodule's artifactId (or
     * its directory name as a fallback) and {@code ${project.version}}
     * → the {@link #version} parameter. Returns {@code null} when the
     * pom doesn't declare a site URL.
     *
     * <p>Used by {@link #runSubprojectChecks()} to decide whether a
     * declared subproject publishes under the reactor's gh-pages or
     * as its own top-level branch.
     */
    String readPomSiteUrlInterpolated(File subPom, String dirName) {
        String content;
        try {
            content = Files.readString(subPom.toPath(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
        int distOpen = content.indexOf("<distributionManagement>");
        if (distOpen < 0) return null;
        int distClose = content.indexOf("</distributionManagement>",
                distOpen);
        if (distClose < 0) return null;
        String distBlock = content.substring(distOpen, distClose);
        int siteOpen = distBlock.indexOf("<site");
        if (siteOpen < 0) return null;
        int siteClose = distBlock.indexOf("</site>", siteOpen);
        if (siteClose < 0) return null;
        String siteBlock = distBlock.substring(siteOpen, siteClose);
        int urlOpen = siteBlock.indexOf("<url>");
        if (urlOpen < 0) return null;
        int urlClose = siteBlock.indexOf("</url>", urlOpen);
        if (urlClose < 0) return null;
        String raw = siteBlock.substring(urlOpen + "<url>".length(),
                urlClose).trim();
        String artifactId = readPomField(subPom, "artifactId");
        if (artifactId == null || artifactId.isBlank()) {
            artifactId = dirName;
        }
        return raw
                .replace("${project.artifactId}", artifactId)
                .replace("${project.version}", version);
    }

    /**
     * Fetch the project's gh-pages tree from the GitHub API, find
     * every {@code index.html}, derive the corresponding ike.network
     * URL, and HEAD-check each. Skips paths matching
     * {@link #ghPagesTreeSkip} prefixes and skips version-prefixed
     * paths other than the current release (avoids N×N history
     * checks).
     *
     * <p>Returns one {@code CheckResult} per checked URL — labeled by
     * the gh-pages path so failures point at the specific file.
     */
    List<CheckResult> runGhPagesTreeChecks() {
        String apiUrl = "https://api.github.com/repos/"
                + githubOrg + "/" + projectId
                + "/git/trees/gh-pages?recursive=true";
        String body;
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Accept", "application/vnd.github+json")
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req,
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return List.of(CheckResult.fail("gh-pages tree walk",
                        apiUrl, "GitHub API HTTP " + resp.statusCode()));
            }
            body = resp.body();
        } catch (Exception e) {
            return List.of(CheckResult.fail("gh-pages tree walk",
                    apiUrl,
                    e.getClass().getSimpleName()
                            + (e.getMessage() != null
                                    ? ": " + e.getMessage() : "")));
        }

        List<String> skipPrefixes = parseSkipPatterns(ghPagesTreeSkip);
        List<String> indexHtmlPaths = extractIndexHtmlPaths(body);
        List<String> urlsToCheck = new ArrayList<>();
        for (String path : indexHtmlPaths) {
            String containingDir = path.substring(0,
                    path.length() - "index.html".length());
            // Strip trailing slash for prefix matching
            String normalized = containingDir.endsWith("/")
                    ? containingDir.substring(0, containingDir.length() - 1)
                    : containingDir;
            if (shouldSkipPath(normalized, version, skipPrefixes)) continue;
            String url = siteBase + projectId + "/" + containingDir;
            urlsToCheck.add(url);
        }

        List<CheckResult> walkResults = new ArrayList<>();
        // Summary first so the table has a marker even when 0 paths
        // were eligible (e.g. fresh repo with no index.html outside
        // the skipped trees).
        walkResults.add(CheckResult.ok("gh-pages tree walk",
                urlsToCheck.size() + " path(s) to check"));
        for (String url : urlsToCheck) {
            // Use short path-derived label for clarity in the table.
            String relPath = url.substring(siteBase.length()
                    + projectId.length() + 1);
            if (relPath.isEmpty()) relPath = "/";
            walkResults.add(httpCheck("  " + relPath, url));
        }
        return walkResults;
    }

    /**
     * Pure-string extract of every {@code "path": "..."} value from
     * GitHub's git/trees JSON response where the path ends with
     * {@code index.html}. Inlined regex; the alternative is a JSON
     * parser dependency the rest of the goal doesn't need.
     */
    static List<String> extractIndexHtmlPaths(String body) {
        if (body == null) return List.of();
        List<String> result = new ArrayList<>();
        // Match paths that are EXACTLY "index.html" at the root OR
        // end with "/index.html" — exclude javadoc-style filenames
        // like "apidocs/allclasses-index.html" that just happen to
        // contain "index.html" as a suffix without a directory-
        // boundary slash.
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "\"path\"\\s*:\\s*\"((?:[^\"]+?/)?index\\.html)\"");
        java.util.regex.Matcher m = p.matcher(body);
        while (m.find()) {
            result.add(m.group(1));
        }
        return result;
    }

    /**
     * Parse the comma-separated skip patterns into a list. Trims
     * whitespace; drops empty entries.
     */
    static List<String> parseSkipPatterns(String patterns) {
        if (patterns == null || patterns.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String entry : patterns.split(",")) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    /**
     * Return {@code true} when the given path should be skipped per
     * the rules: matches a literal or {@code *}-prefixed skip
     * pattern, OR starts with a numeric version dir other than the
     * current release (avoids N×N checks across release history).
     *
     * @param path           the gh-pages path (without trailing slash)
     * @param currentVersion the release version being verified
     * @param skipPrefixes   parsed prefix patterns from
     *                       {@link #ghPagesTreeSkip}
     */
    static boolean shouldSkipPath(String path, String currentVersion,
                                    List<String> skipPrefixes) {
        // Skip numeric-prefixed version dirs that aren't this release.
        // Recognize a leading path segment that's all digits (or
        // ends in "-checkpoint.<stuff>") as a version-snapshot dir.
        int firstSlash = path.indexOf('/');
        String firstSegment = firstSlash < 0 ? path
                : path.substring(0, firstSlash);
        if (looksLikeVersionSegment(firstSegment)
                && !firstSegment.equals(currentVersion)
                && !firstSegment.equals("latest")) {
            return true;
        }
        // Apply user-configurable prefix patterns.
        for (String pattern : skipPrefixes) {
            if (matchesPrefix(pattern, path)) return true;
        }
        return false;
    }

    /**
     * Check whether a path segment looks like a version snapshot
     * directory. Pre-release / post-release IKE versions are
     * single-segment integers (e.g. {@code 21}, {@code 165}) or
     * checkpoint forms ({@code 7-checkpoint.20260228.1}).
     */
    static boolean looksLikeVersionSegment(String segment) {
        if (segment == null || segment.isEmpty()) return false;
        if (!Character.isDigit(segment.charAt(0))) return false;
        // Numeric prefix is enough — checkpoint suffixes start with a
        // digit too. Anything starting with a digit at the gh-pages
        // top level is a snapshot dir, not a sub-site.
        return true;
    }

    /**
     * Match a literal prefix pattern (or one beginning with
     * {@code *}/) against a path. The {@code *} matches any single
     * path segment.
     */
    static boolean matchesPrefix(String pattern, String path) {
        if (pattern.startsWith("*/")) {
            // *<rest> matches any first segment followed by /<rest>
            String rest = pattern.substring(2);
            int firstSlash = path.indexOf('/');
            if (firstSlash < 0) return false;
            String afterFirst = path.substring(firstSlash + 1);
            return afterFirst.startsWith(rest)
                    || afterFirst.equals(rest.endsWith("/")
                            ? rest.substring(0, rest.length() - 1)
                            : rest);
        }
        // Literal prefix
        String normalizedPattern = pattern.endsWith("/")
                ? pattern.substring(0, pattern.length() - 1)
                : pattern;
        return path.startsWith(pattern)
                || path.equals(normalizedPattern);
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
     * Read the pom's declared subprojects — both top-level
     * {@code <subprojects><subproject>...</subproject></subprojects>}
     * (Maven 4) and legacy {@code <modules><module>...</module></modules>}
     * and including subprojects declared inside any
     * {@code <profile>} block (file-activated submodule includes
     * are the standard IKE workspace pattern).
     *
     * <p>Returns the declared directory names in source order, with
     * duplicates removed. Empty list when the pom declares none
     * (single-module project) or cannot be read.
     *
     * <p>Used by {@link #runSubprojectChecks()} for the #382 topology
     * cross-reference.
     */
    static List<String> readPomSubprojects(File pom) {
        if (pom == null || !pom.isFile()) return List.of();
        String content;
        try {
            content = Files.readString(pom.toPath(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of();
        }
        java.util.LinkedHashSet<String> result =
                new java.util.LinkedHashSet<>();
        for (String tag : new String[]{"subproject", "module"}) {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "<" + tag + ">\\s*([^<\\s][^<]*?)\\s*</" + tag + ">");
            java.util.regex.Matcher m = p.matcher(content);
            while (m.find()) {
                String name = m.group(1).trim();
                if (!name.isEmpty()) result.add(name);
            }
        }
        return new ArrayList<>(result);
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
