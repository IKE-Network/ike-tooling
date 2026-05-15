package network.ike.plugin.reconcile;

import network.ike.plugin.ReleaseSupport;
import org.apache.maven.api.plugin.MojoException;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reconciler that keeps the project's deployed Maven site in sync with
 * the current POM version.
 *
 * <p>Subsumes the retired {@code ike:deploy-site-{draft,publish}} goals
 * (IKE-Network/ike-issues#398). The deployed site lives at
 * {@code https://ike.network/<artifactId>/} (served from the project's
 * own {@code gh-pages} branch) with a versioned mirror at
 * {@code .../<artifactId>/<version>/} and a {@code .../<artifactId>/latest/}
 * alias.
 *
 * <p><b>Detect</b>: probe {@code https://ike.network/<artifactId>/} and
 * inspect the rendered HTML for the deployed version. If the version
 * tag is missing or differs from {@link SiteContext#projectVersion},
 * report drift.
 *
 * <p><b>Apply</b>: run {@code mvnw site site:stage} and force-push the
 * resulting {@code target/staging/} to the project repo's
 * {@code gh-pages} branch via
 * {@link ReleaseSupport#publishProjectSiteToGhPages}. This is the same
 * publish path the retired {@code DeploySiteDraftMojo} used for
 * {@code siteType=release} after #304 retired the scpexe mirror.
 *
 * <p><b>Uninstall</b>: this reconciler does not invert. Removing a
 * deployed site is handled by the gh-pages branch being deleted at the
 * GitHub repo level — not something the build can do safely. The
 * paired {@link StaleSiteCleanupReconciler} handles legacy scpexe
 * cleanup.
 */
public class DeployedSiteReconciler implements SiteReconciler {

    /** Pattern to extract the deployed version from the rendered site HTML. */
    private static final Pattern DEPLOYED_VERSION_PATTERN = Pattern.compile(
            "(?:Version|version)\\s*[:|]\\s*<[^>]*>?\\s*([0-9][^<\\s]*)");

    /** HTTP timeout for drift probes. Kept tight to keep draft snappy. */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);

    @Override
    public String dimension() {
        return "Deployed site version";
    }

    @Override
    public String optOutFlag() {
        return "updateSite";
    }

    @Override
    public SiteDriftReport detect(SiteContext ctx) {
        String currentVersion = ctx.projectVersion();
        String deployedUrl = ctx.projectSiteUrl();
        if (deployedUrl == null || deployedUrl.isBlank()) {
            return SiteDriftReport.noDrift(dimension());
        }

        Probe probe = probeDeployedVersion(deployedUrl);

        if (!probe.reachable) {
            // Treat unreachable as drift — apply will deploy fresh.
            List<String> detail = List.of(
                    "Deployed URL: " + deployedUrl,
                    "Status: unreachable (" + probe.detail + ")");
            String optOut = "mvn ike:site-publish -D" + optOutFlag() + "=false";
            return new SiteDriftReport(
                    dimension(), true,
                    "Site at " + deployedUrl + " is unreachable",
                    detail,
                    "deploy current version " + currentVersion + " on site-publish",
                    optOut);
        }

        if (probe.version != null && probe.version.equals(currentVersion)) {
            return SiteDriftReport.noDrift(dimension());
        }

        String deployed = probe.version != null ? probe.version : "(unknown)";
        List<String> detail = List.of(
                "Current: " + deployedUrl + " serves " + deployed,
                "         → project is at " + currentVersion);
        String optOut = "mvn ike:site-publish -D" + optOutFlag() + "=false";
        return new SiteDriftReport(
                dimension(), true,
                deployed + " deployed vs " + currentVersion + " in project",
                detail,
                "deploy " + currentVersion + " on site-publish",
                optOut);
    }

    @Override
    public void apply(SiteContext ctx) {
        if (ctx.options().isOptedOut(optOutFlag())) {
            ctx.log().info("  " + dimension() + ": skipped (opted out via -D"
                    + optOutFlag() + "=false)");
            return;
        }

        File gitRoot = ctx.gitRoot();
        File mvnw = ReleaseSupport.resolveMavenWrapper(gitRoot, ctx.log());

        // Build + stage in a single mvnw invocation. Mirrors the pre-#398
        // DeploySiteDraftMojo path: `mvn site site:stage` produces
        // target/staging/ from which we force-push to gh-pages.
        ReleaseSupport.exec(gitRoot, ctx.log(),
                mvnw.getAbsolutePath(), "site", "site:stage", "-B");

        String remoteUrl = ReleaseSupport.getRemoteUrl(gitRoot, "origin");
        if (remoteUrl == null) {
            ctx.log().info("  " + dimension()
                    + ": skipped (no 'origin' remote)");
            return;
        }

        Path stagingDir = gitRoot.toPath()
                .resolve("target").resolve("staging");
        try {
            ReleaseSupport.publishProjectSiteToGhPages(
                    stagingDir, remoteUrl, ctx.log(),
                    ctx.projectId(), ctx.projectVersion());
            ctx.log().info("  " + dimension()
                    + ": deployed " + ctx.projectVersion()
                    + " to " + ctx.projectSiteUrl());
        } catch (MojoException e) {
            ctx.log().warn("  ⚠ " + dimension()
                    + ": gh-pages publish failed (non-fatal): "
                    + e.getMessage());
        }
    }

    // ── Drift probe ─────────────────────────────────────────────────

    /** Result of a deployed-site HTTP probe. */
    private record Probe(boolean reachable, String version, String detail) {}

    /**
     * Probe the deployed site root and try to extract the current
     * deployed version from the rendered HTML. Returns {@code null}
     * version when the page is reachable but no version marker can be
     * found (common for non-IKE-rendered sites).
     */
    private static Probe probeDeployedVersion(String url) {
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(PROBE_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(PROBE_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req,
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                return new Probe(false, null, "HTTP " + resp.statusCode());
            }
            String body = resp.body();
            Matcher m = DEPLOYED_VERSION_PATTERN.matcher(body);
            if (m.find()) {
                return new Probe(true, m.group(1).trim(), "");
            }
            // Reachable but no version pattern matched — treat as
            // probe-inconclusive (no drift reported).
            return new Probe(true, null, "no version marker found");
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new Probe(false, null,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Visible for testing — exposes the version-extraction regex so a
     * fixture HTML body can be parsed in isolation.
     *
     * @param html the rendered HTML body
     * @return the detected version string, or {@code null}
     */
    static String extractDeployedVersion(String html) {
        Matcher m = DEPLOYED_VERSION_PATTERN.matcher(html);
        return m.find() ? m.group(1).trim() : null;
    }
}
