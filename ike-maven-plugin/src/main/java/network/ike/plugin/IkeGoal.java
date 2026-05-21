package network.ike.plugin;

import network.ike.plugin.support.GoalRef;
import network.ike.support.enums.ConstantBackedEnum;
import org.apache.maven.api.plugin.Mojo;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Compile-time identity for every {@code ike:*} goal in this plugin. Each
 * value wraps the bare goal name, the mojo class that implements it, and
 * a short human description. Draft/publish siblings expose each other
 * through {@link #pair()}.
 *
 * <p>Parallels {@code network.ike.plugin.ws.WsGoal} in the ws plugin.
 * Callers that invoke ike goals from Java — for subprocess exec, for
 * report identification, for javadoc examples that survive a rename —
 * should reference these enum values rather than string literals.
 *
 * <p>Implements {@link ConstantBackedEnum}: each constant is paired with
 * a {@code public static final String NAME_*} mirror, and class-load
 * verification keeps the two in lockstep. The mirror is a constant
 * expression, so the {@code @Mojo(name = IkeGoal.NAME_*)} annotation can
 * reference the enum as its single source of truth.
 *
 * <p>See issues #166 and #453.
 */
public enum IkeGoal implements GoalRef, ConstantBackedEnum {

    /** {@code ike:cascade-export} — export the release cascade topology for CI. */
    CASCADE_EXPORT(IkeGoal.NAME_CASCADE_EXPORT, IkeCascadeExportMojo.class,
            "Export the foundation release cascade topology as JSON "
                    + "or .properties so a CI meta-runner can generate "
                    + "build-chain edges from release-cascade.yaml "
                    + "instead of hand-wiring them."),
    /** {@code ike:central-status} — report async Maven Central deploy state (#484). */
    CENTRAL_STATUS(IkeGoal.NAME_CENTRAL_STATUS, CentralStatusMojo.class,
            "Report the status of asynchronous Maven Central deploys "
                    + "spawned by ike:release-publish "
                    + "-Dike.deploy.central.async=true. Walks the "
                    + "sentinel cache (~/.cache/ike-release/) and "
                    + "reports state, attempts, and log paths."),
    /** {@code ike:codesign-natives} — sign macOS native binaries in a runtime image. */
    CODESIGN_NATIVES(IkeGoal.NAME_CODESIGN_NATIVES, CodesignNativesMojo.class,
            "Sign macOS native libraries and executables inside a runtime image."),
    /** {@code ike:codesign-pkg} — sign a {@code .pkg} installer with Developer ID. */
    CODESIGN_PKG(IkeGoal.NAME_CODESIGN_PKG, CodesignPkgMojo.class,
            "Sign a .pkg installer with a Developer ID Installer certificate."),
    /** {@code ike:env} — print runtime environment / terminal diagnostics. */
    ENV(IkeGoal.NAME_ENV, IkeEnvMojo.class,
            "Print runtime environment diagnostics — terminal/console "
                    + "capability, stdin, and relevant system properties. "
                    + "Run from both IntelliJ's Maven tool window and the "
                    + "Terminal tool window to compare (ike-issues#385)."),
    /** {@code ike:generate-bom} — generate the auto-managed BOM. */
    GENERATE_BOM(IkeGoal.NAME_GENERATE_BOM, GenerateBomMojo.class,
            "Generate the auto-managed BOM from the current dependencyManagement."),
    /** {@code ike:help} — list {@code ike:*} goals from the plugin descriptor. */
    HELP(IkeGoal.NAME_HELP, IkeHelpMojo.class,
            "List ike:* goals discovered from the plugin descriptor."),
    /** {@code ike:inject-breadcrumb} — inject breadcrumbs into rendered HTML. */
    INJECT_BREADCRUMB(IkeGoal.NAME_INJECT_BREADCRUMB, InjectBreadcrumbMojo.class,
            "Inject breadcrumb navigation into rendered HTML."),
    /** {@code ike:jpackage-props} — emit jpackage properties from reactor config. */
    JPACKAGE_PROPS(IkeGoal.NAME_JPACKAGE_PROPS, JpackagePropsMojo.class,
            "Emit jpackage properties files from reactor configuration."),
    /** {@code ike:notarize} — submit a {@code .pkg}/{@code .app} to Apple notary. */
    NOTARIZE(IkeGoal.NAME_NOTARIZE, NotarizeMojo.class,
            "Submit a .pkg or .app to Apple notary service and staple the ticket."),
    /** {@code ike:release-draft} — preview releasing the current project. */
    RELEASE_DRAFT(IkeGoal.NAME_RELEASE_DRAFT, ReleaseDraftMojo.class,
            "Preview releasing the current project."),
    /** {@code ike:release-publish} — release the current project (tag + publish). */
    RELEASE_PUBLISH(IkeGoal.NAME_RELEASE_PUBLISH, ReleasePublishMojo.class,
            "Release the current project (tag + publish)."),
    /** {@code ike:release-cascade} — release the whole foundation cascade in order. */
    RELEASE_CASCADE(IkeGoal.NAME_RELEASE_CASCADE, IkeReleaseCascadeMojo.class,
            "Walk the decentralized release cascade assembled from "
                    + "the per-project release-cascade.yaml manifests "
                    + "and run ike:release-publish on every foundation "
                    + "repo that has unreleased changes, in topological "
                    + "order."),
    /** {@code ike:rename} — rename output files to a canonical pattern. */
    RENAME(IkeGoal.NAME_RENAME, RenameMojo.class,
            "Rename output files to a canonical pattern."),
    /** {@code ike:built-with} — generate a Built With page from the SBOM and a curated supplement (#336). */
    BUILT_WITH(IkeGoal.NAME_BUILT_WITH, BuiltWithMojo.class,
            "Generate a curated Built-With page from the CycloneDX "
                    + "SBOM and an optional project-wide supplement "
                    + "YAML. Replaces the legacy 'Third-Party Notices' "
                    + "naming with a friendlier scannable label and "
                    + "supports per-module rendering by walking up "
                    + "the filesystem to find the reactor's "
                    + "supplement.yaml."),
    /** {@code ike:render-sbom-viewer} — generate dependencies.adoc from the CycloneDX SBOM (#341). */
    RENDER_SBOM_VIEWER(IkeGoal.NAME_RENDER_SBOM_VIEWER, RenderSbomViewerMojo.class,
            "Generate a Web-friendly dependencies.adoc page from "
                    + "the CycloneDX SBOM with a sortable component "
                    + "table. Maven Site renders it to "
                    + "dependencies.html, replacing the auto-"
                    + "generated dependency report."),
    /** {@code ike:render-spdx-licenses} — generate licenses.adoc from the CycloneDX SBOM (#335). */
    RENDER_SPDX_LICENSES(IkeGoal.NAME_RENDER_SPDX_LICENSES, RenderSpdxLicensesMojo.class,
            "Generate an SPDX-grouped licenses.adoc page from the "
                    + "CycloneDX SBOM. Maven Site renders it to "
                    + "licenses.html with the project skin chrome."),
    /** {@code ike:scaffold-draft} — preview ike-build-standards scaffold changes. */
    SCAFFOLD_DRAFT(IkeGoal.NAME_SCAFFOLD_DRAFT, ScaffoldDraftMojo.class,
            "Preview what ike:scaffold-publish would apply from the "
                    + "ike-build-standards scaffold."),
    /** {@code ike:scaffold-publish} — apply the scaffold to disk. */
    SCAFFOLD_PUBLISH(IkeGoal.NAME_SCAFFOLD_PUBLISH, ScaffoldPublishMojo.class,
            "Apply the ike-build-standards scaffold to disk and "
                    + "update per-project and per-user lockfiles."),
    /** {@code ike:scaffold-revert} — undo a previous scaffold-publish. */
    SCAFFOLD_REVERT(IkeGoal.NAME_SCAFFOLD_REVERT, ScaffoldRevertMojo.class,
            "Undo a previous ike:scaffold-publish, leaving "
                    + "user-edited files alone."),
    /** {@code ike:site-draft} — report deployed-site drift (#398). */
    SITE_DRAFT(IkeGoal.NAME_SITE_DRAFT, IkeSiteDraftMojo.class,
            "Report drift in deployed-site state — version on "
                    + "server vs current project version, landing-page "
                    + "registration status — with copy-paste opt-out "
                    + "commands inline."),
    /** {@code ike:site-publish} — apply deployed-site convergence (#398). */
    SITE_PUBLISH(IkeGoal.NAME_SITE_PUBLISH, IkeSitePublishMojo.class,
            "Deploy the current version to gh-pages and update the "
                    + "IKE Network landing page registration. Flags: "
                    + "-DupdateSite=false, -DupdateRegistration=false, "
                    + "-Dsite=removed (uninstall: deregister + cleanup)."),
    /** {@code ike:setup} — one-time setup for an IKE development machine. */
    SETUP(IkeGoal.NAME_SETUP, SetupMojo.class,
            "One-time setup for an IKE development machine."),
    /** {@code ike:unpack-zip} — unpack a zip artifact into a target directory. */
    UNPACK_ZIP(IkeGoal.NAME_UNPACK_ZIP, UnpackZipMojo.class,
            "Unpack a zip artifact into a target directory."),
    /** {@code ike:verify-release-published} — verify all post-release publication targets (#374). */
    VERIFY_RELEASE_PUBLISHED(IkeGoal.NAME_VERIFY_RELEASE_PUBLISHED,
            VerifyReleasePublishedMojo.class,
            "Verify all post-release publication targets are "
                    + "reachable for the current project + version: "
                    + "site (current/versioned/latest), org-site "
                    + "landing, Nexus artifact, GitHub release tag. "
                    + "Read-only; exits non-zero on any failure.");

    // ── Mirror constants (ConstantBackedEnum) ───────────────────
    // One public static final String per constant, named NAME_<CONSTANT>.
    // These are the constant expressions referenced by @Mojo(name = ...)
    // and by each enum constant's constructor call above (a forward
    // reference to a constant variable, permitted by JLS 8.3.3).
    // ConstantBackedEnum.verify() — run from the static initializer
    // below — fails class-load if any constant and its mirror drift.

    /** Mirror for {@link #CASCADE_EXPORT}. */
    public static final String NAME_CASCADE_EXPORT = "cascade-export";
    /** Mirror for {@link #CENTRAL_STATUS}. */
    public static final String NAME_CENTRAL_STATUS = "central-status";
    /** Mirror for {@link #CODESIGN_NATIVES}. */
    public static final String NAME_CODESIGN_NATIVES = "codesign-natives";
    /** Mirror for {@link #CODESIGN_PKG}. */
    public static final String NAME_CODESIGN_PKG = "codesign-pkg";
    /** Mirror for {@link #ENV}. */
    public static final String NAME_ENV = "env";
    /** Mirror for {@link #GENERATE_BOM}. */
    public static final String NAME_GENERATE_BOM = "generate-bom";
    /** Mirror for {@link #HELP}. */
    public static final String NAME_HELP = "help";
    /** Mirror for {@link #INJECT_BREADCRUMB}. */
    public static final String NAME_INJECT_BREADCRUMB = "inject-breadcrumb";
    /** Mirror for {@link #JPACKAGE_PROPS}. */
    public static final String NAME_JPACKAGE_PROPS = "jpackage-props";
    /** Mirror for {@link #NOTARIZE}. */
    public static final String NAME_NOTARIZE = "notarize";
    /** Mirror for {@link #RELEASE_DRAFT}. */
    public static final String NAME_RELEASE_DRAFT = "release-draft";
    /** Mirror for {@link #RELEASE_PUBLISH}. */
    public static final String NAME_RELEASE_PUBLISH = "release-publish";
    /** Mirror for {@link #RELEASE_CASCADE}. */
    public static final String NAME_RELEASE_CASCADE = "release-cascade";
    /** Mirror for {@link #RENAME}. */
    public static final String NAME_RENAME = "rename";
    /** Mirror for {@link #BUILT_WITH}. */
    public static final String NAME_BUILT_WITH = "built-with";
    /** Mirror for {@link #RENDER_SBOM_VIEWER}. */
    public static final String NAME_RENDER_SBOM_VIEWER = "render-sbom-viewer";
    /** Mirror for {@link #RENDER_SPDX_LICENSES}. */
    public static final String NAME_RENDER_SPDX_LICENSES = "render-spdx-licenses";
    /** Mirror for {@link #SCAFFOLD_DRAFT}. */
    public static final String NAME_SCAFFOLD_DRAFT = "scaffold-draft";
    /** Mirror for {@link #SCAFFOLD_PUBLISH}. */
    public static final String NAME_SCAFFOLD_PUBLISH = "scaffold-publish";
    /** Mirror for {@link #SCAFFOLD_REVERT}. */
    public static final String NAME_SCAFFOLD_REVERT = "scaffold-revert";
    /** Mirror for {@link #SITE_DRAFT}. */
    public static final String NAME_SITE_DRAFT = "site-draft";
    /** Mirror for {@link #SITE_PUBLISH}. */
    public static final String NAME_SITE_PUBLISH = "site-publish";
    /** Mirror for {@link #SETUP}. */
    public static final String NAME_SETUP = "setup";
    /** Mirror for {@link #UNPACK_ZIP}. */
    public static final String NAME_UNPACK_ZIP = "unpack-zip";
    /** Mirror for {@link #VERIFY_RELEASE_PUBLISHED}. */
    public static final String NAME_VERIFY_RELEASE_PUBLISHED = "verify-release-published";

    /** Shared {@code ike:} prefix for all goals in this plugin. */
    public static final String PLUGIN_PREFIX = "ike";

    private static final String DRAFT_SUFFIX = "-draft";
    private static final String PUBLISH_SUFFIX = "-publish";

    /** Reverse lookup by bare goal name; also a duplicate-literal guard. */
    private static final Map<String, IkeGoal> BY_NAME;

    static {
        // Class-load guard: every constant must have a matching NAME_*
        // mirror whose value is the kebab-case form of the constant
        // name. A drift here makes IkeGoal unloadable rather than
        // letting a stale @Mojo name reach plugin.xml.
        ConstantBackedEnum.verify(IkeGoal.class,
                ConstantBackedEnum.defaultPrefix(),
                (g, v) -> v.equals(
                        g.name().toLowerCase(Locale.ROOT).replace('_', '-')));
        BY_NAME = ConstantBackedEnum.index(IkeGoal.class);
    }

    private final String goalName;
    private final Class<? extends Mojo> mojoClass;
    private final String description;

    IkeGoal(String goalName,
            Class<? extends Mojo> mojoClass,
            String description) {
        this.goalName = goalName;
        this.mojoClass = mojoClass;
        this.description = description;
    }

    /**
     * The bare goal name as it appears in {@code @Mojo(name = ...)}.
     *
     * @return the bare goal name
     */
    @Override
    public String goalName() {
        return goalName;
    }

    /**
     * The String literal carried by this constant — the bare goal name.
     * Satisfies {@link ConstantBackedEnum}.
     *
     * @return the bare goal name
     */
    @Override
    public String literalName() {
        return goalName;
    }

    /**
     * The {@code ike} plugin prefix — shared by every goal in this enum.
     *
     * @return {@link #PLUGIN_PREFIX}
     */
    @Override
    public String pluginPrefix() {
        return PLUGIN_PREFIX;
    }

    /**
     * The fully-qualified goal invocation, e.g. {@code "ike:release-publish"}.
     *
     * @return the fully-qualified goal invocation
     */
    @Override
    public String qualified() {
        return PLUGIN_PREFIX + ":" + goalName;
    }

    /**
     * The mojo class that implements this goal.
     *
     * @return the mojo class
     */
    public Class<? extends Mojo> mojoClass() {
        return mojoClass;
    }

    /**
     * One-line human description of what this goal does.
     *
     * @return the human description
     */
    @Override
    public String description() {
        return description;
    }

    /**
     * True if this is the {@code -draft} counterpart of a draft/publish pair.
     *
     * @return {@code true} when this goal is a draft variant
     */
    public boolean isDraft() {
        return goalName.endsWith(DRAFT_SUFFIX);
    }

    /**
     * True if this is the {@code -publish} counterpart of a draft/publish pair.
     *
     * @return {@code true} when this goal is a publish variant
     */
    public boolean isPublish() {
        return goalName.endsWith(PUBLISH_SUFFIX);
    }

    /**
     * The paired draft/publish sibling, if this goal belongs to a pair.
     *
     * @return the sibling goal, or empty if this goal is a singleton
     */
    public Optional<IkeGoal> pair() {
        if (isDraft()) {
            return byName(stripSuffix(goalName, DRAFT_SUFFIX) + PUBLISH_SUFFIX);
        }
        if (isPublish()) {
            return byName(stripSuffix(goalName, PUBLISH_SUFFIX) + DRAFT_SUFFIX);
        }
        return Optional.empty();
    }

    /**
     * Look up a goal by its bare name (e.g. {@code "release-publish"}).
     *
     * @param goalName the bare goal name, without the {@code ike:} prefix
     * @return the matching goal, or empty if none
     */
    public static Optional<IkeGoal> byName(String goalName) {
        return Optional.ofNullable(BY_NAME.get(goalName));
    }

    private static String stripSuffix(String s, String suffix) {
        return s.substring(0, s.length() - suffix.length());
    }
}
