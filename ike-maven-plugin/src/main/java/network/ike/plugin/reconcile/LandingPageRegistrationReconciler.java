package network.ike.plugin.reconcile;

import network.ike.plugin.OrgSiteSupport;
import network.ike.plugin.ReleaseSupport;
import org.apache.maven.api.plugin.MojoException;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reconciler that keeps the project's entry on the IKE Network
 * landing page ({@code https://ike.network/}) in sync with the
 * current project version.
 *
 * <p>Subsumes the retired {@code ike:register-site-{draft,publish}}
 * and {@code ike:deregister-site-{draft,publish}} goals
 * (IKE-Network/ike-issues#398).
 *
 * <p><b>Detect</b>: probe
 * {@code https://ike.network/projects/<artifactId>.html} and look for
 * the version cell rendered from the project's {@code projects/<id>.adoc}
 * fragment. Drift = missing fragment, or version cell differs from
 * {@link SiteContext#projectVersion}.
 *
 * <p><b>Apply</b>: clone the org-site source repo, write a fresh
 * fragment via {@link OrgSiteSupport#registerProject}, build the site,
 * push the source repo, and publish the rendered HTML to the publish
 * repo.
 *
 * <p><b>Uninstall</b>: clone the source repo, delete the fragment,
 * rebuild, and publish — the same flow as the retired
 * {@code DeregisterSiteDraftMojo}.
 */
public class LandingPageRegistrationReconciler implements SiteReconciler {

    /** Creates this reconciler instance. */
    public LandingPageRegistrationReconciler() {}

    /** Default org-site SOURCE repo (kept here as a fallback). */
    static final String DEFAULT_SRC_REPO =
            "https://github.com/IKE-Network/ike-network-site.git";

    /** Default org-site PUBLISH repo (kept here as a fallback). */
    static final String DEFAULT_PUB_REPO =
            "https://github.com/IKE-Network/IKE-Network.github.io.git";

    /** Default org-site source branch. */
    static final String DEFAULT_SRC_BRANCH = "main";

    /** Default org-site publish branch. */
    static final String DEFAULT_PUB_BRANCH = "main";

    /** Pattern to extract the registered version from the landing-page HTML. */
    private static final Pattern REGISTERED_VERSION_PATTERN = Pattern.compile(
            "Version</[a-z]+>\\s*<[a-z]+[^>]*>\\s*([0-9][^<\\s]*)");

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);

    @Override
    public String dimension() {
        return "Landing page registration";
    }

    @Override
    public String optOutFlag() {
        return "updateRegistration";
    }

    @Override
    public SiteDriftReport detect(SiteContext ctx) {
        String currentVersion = ctx.projectVersion();
        String projectId = ctx.projectId();
        if (projectId == null || projectId.isBlank()) {
            return SiteDriftReport.noDrift(dimension());
        }

        Probe probe = probeRegisteredVersion(projectId);

        if (!probe.reachable) {
            // Treat unreachable as drift (registration missing).
            String optOut = "mvn ike:site-publish -D" + optOutFlag() + "=false";
            return new SiteDriftReport(
                    dimension(), true,
                    projectId + " not found on landing page",
                    List.of("Probe: " + probe.detail),
                    "register " + currentVersion + " on site-publish",
                    optOut);
        }

        if (probe.version != null && probe.version.equals(currentVersion)) {
            return SiteDriftReport.noDrift(dimension());
        }

        String registered = probe.version != null ? probe.version : "(unknown)";
        List<String> detail = List.of(
                projectId + " on landing page: " + registered,
                "         → project is at " + currentVersion);
        String optOut = "mvn ike:site-publish -D" + optOutFlag() + "=false";
        return new SiteDriftReport(
                dimension(), true,
                registered + " registered vs " + currentVersion + " in project",
                detail,
                "update registration to " + currentVersion + " on site-publish",
                optOut);
    }

    @Override
    public void apply(SiteContext ctx) {
        if (ctx.options().isOptedOut(optOutFlag())) {
            ctx.log().info("  " + dimension() + ": skipped (opted out via -D"
                    + optOutFlag() + "=false)");
            return;
        }
        String remoteUrl = ReleaseSupport.getRemoteUrl(ctx.gitRoot(), "origin");
        if (remoteUrl == null) {
            ctx.log().info("  " + dimension()
                    + ": skipped (no 'origin' remote)");
            return;
        }

        List<String> modules = readReactorModules(ctx.gitRoot());

        try {
            OrgSiteSupport.registerProject(
                    ctx.gitRoot(), ctx.log(),
                    ctx.srcRepoUrl(), ctx.pubRepoUrl(),
                    ctx.srcBranch(), ctx.pubBranch(),
                    ctx.projectId(),
                    ctx.projectName() != null ? ctx.projectName() : ctx.projectId(),
                    ctx.projectDescription(),
                    ctx.projectVersion(),
                    ctx.projectSiteUrl(),
                    ctx.githubUrl(),
                    modules);
            ctx.log().info("  " + dimension()
                    + ": registered " + ctx.projectId()
                    + " " + ctx.projectVersion());
        } catch (MojoException e) {
            ctx.log().warn("  ⚠ " + dimension()
                    + ": registration failed (non-fatal): " + e.getMessage());
        }
    }

    @Override
    public void uninstall(SiteContext ctx) {
        if (ctx.options().isOptedOut(optOutFlag())) {
            ctx.log().info("  " + dimension() + ": skipped (opted out via -D"
                    + optOutFlag() + "=false)");
            return;
        }
        try {
            OrgSiteSupport.deregisterProject(
                    ctx.log(),
                    ctx.srcRepoUrl(), ctx.pubRepoUrl(),
                    ctx.srcBranch(), ctx.pubBranch(),
                    ctx.projectId());
            ctx.log().info("  " + dimension()
                    + ": deregistered " + ctx.projectId()
                    + " from landing page");
        } catch (MojoException e) {
            ctx.log().warn("  ⚠ " + dimension()
                    + ": deregistration failed (non-fatal): " + e.getMessage());
        }
    }

    // ── Drift probe ─────────────────────────────────────────────────

    /** Result of a landing-page HTTP probe. */
    private record Probe(boolean reachable, String version, String detail) {}

    /**
     * Probe {@code https://ike.network/projects/<artifactId>.html} and
     * extract the registered version. Returns {@code reachable = false}
     * for 404 (project not registered) or network errors.
     */
    private static Probe probeRegisteredVersion(String projectId) {
        String url = "https://ike.network/projects/" + projectId + ".html";
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
            if (resp.statusCode() == 404) {
                return new Probe(false, null, "HTTP 404 (not registered)");
            }
            if (resp.statusCode() / 100 != 2) {
                return new Probe(false, null, "HTTP " + resp.statusCode());
            }
            Matcher m = REGISTERED_VERSION_PATTERN.matcher(resp.body());
            if (m.find()) {
                return new Probe(true, m.group(1).trim(), "");
            }
            return new Probe(true, null, "no version cell found");
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new Probe(false, null,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Read reactor module names from the project's root POM. Handles
     * both Maven 3 {@code <module>} and Maven 4 {@code <subproject>}
     * tags. Empty list for non-reactor projects.
     *
     * <p>Lifted from the retired {@code RegisterSiteDraftMojo} so the
     * registration fragment includes the same module list it always
     * did.
     */
    private static List<String> readReactorModules(File gitRoot) {
        File rootPom = new File(gitRoot, "pom.xml");
        if (!rootPom.isFile()) return List.of();
        try {
            String pom = Files.readString(rootPom.toPath());
            Pattern p = Pattern.compile(
                    "<(?:module|subproject)>([^<]+)</(?:module|subproject)>");
            Matcher m = p.matcher(pom);
            List<String> modules = new ArrayList<>();
            while (m.find()) {
                modules.add(m.group(1));
            }
            return modules;
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * Visible for testing — exposes the version-extraction regex so a
     * fixture HTML body can be parsed in isolation.
     *
     * @param html the rendered HTML body
     * @return the detected version string, or {@code null}
     */
    static String extractRegisteredVersion(String html) {
        Matcher m = REGISTERED_VERSION_PATTERN.matcher(html);
        return m.find() ? m.group(1).trim() : null;
    }
}
