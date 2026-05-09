package network.ike.plugin;

import network.ike.plugin.support.GoalRef;
import org.apache.maven.api.plugin.Mojo;

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
 * <p>See issue #166.
 */
public enum IkeGoal implements GoalRef {

    /** {@code ike:adocstudio} — edit the project in AsciiDocFX. */
    ADOCSTUDIO("adocstudio", AdocStudioMojo.class,
            "Edit the project in AsciiDocFX."),
    /** {@code ike:asciidoc} — render AsciiDoc to HTML. */
    ASCIIDOC("asciidoc", AsciidocMojo.class,
            "Render AsciiDoc to HTML."),
    /** {@code ike:clean-site} — clear stale site content before redeployment. */
    CLEAN_SITE("clean-site", CleanSiteMojo.class,
            "Clear stale site content before redeployment."),
    /** {@code ike:codesign-natives} — sign macOS native binaries in a runtime image. */
    CODESIGN_NATIVES("codesign-natives", CodesignNativesMojo.class,
            "Sign macOS native libraries and executables inside a runtime image."),
    /** {@code ike:codesign-pkg} — sign a {@code .pkg} installer with Developer ID. */
    CODESIGN_PKG("codesign-pkg", CodesignPkgMojo.class,
            "Sign a .pkg installer with a Developer ID Installer certificate."),
    /** {@code ike:copy-default-pdf} — copy the default-renderer PDF to the site. */
    COPY_DEFAULT_PDF("copy-default-pdf", CopyDefaultPdfMojo.class,
            "Copy the project's default-renderer PDF to the site."),
    /** {@code ike:copy-docs} — copy rendered docs into the site. */
    COPY_DOCS("copy-docs", CopyDocsToSiteMojo.class,
            "Copy rendered docs into the site."),
    /** {@code ike:deploy-site-draft} — preview deploying the aggregated site. */
    DEPLOY_SITE_DRAFT("deploy-site-draft", DeploySiteDraftMojo.class,
            "Preview deploying the aggregated site to its target."),
    /** {@code ike:deploy-site-publish} — deploy the aggregated site to its target. */
    DEPLOY_SITE_PUBLISH("deploy-site-publish", DeploySitePublishMojo.class,
            "Deploy the aggregated site to its target."),
    /** {@code ike:deregister-site-draft} — preview deregistering a site alias. */
    DEREGISTER_SITE_DRAFT("deregister-site-draft", DeregisterSiteDraftMojo.class,
            "Preview deregistering a site alias from the site registry."),
    /** {@code ike:deregister-site-publish} — deregister a site alias. */
    DEREGISTER_SITE_PUBLISH("deregister-site-publish", DeregisterSitePublishMojo.class,
            "Deregister a site alias from the site registry."),
    /** {@code ike:fix-svg} — post-process SVGs for PDF renderer compatibility. */
    FIX_SVG("fix-svg", FixSvgMojo.class,
            "Post-process generated SVGs to work in all PDF renderers."),
    /** {@code ike:generate-bom} — generate the auto-managed BOM. */
    GENERATE_BOM("generate-bom", GenerateBomMojo.class,
            "Generate the auto-managed BOM from the current dependencyManagement."),
    /** {@code ike:help} — list {@code ike:*} goals from the plugin descriptor. */
    HELP("help", IkeHelpMojo.class,
            "List ike:* goals discovered from the plugin descriptor."),
    /** {@code ike:inject-breadcrumb} — inject breadcrumbs into rendered HTML. */
    INJECT_BREADCRUMB("inject-breadcrumb", InjectBreadcrumbMojo.class,
            "Inject breadcrumb navigation into rendered HTML."),
    /** {@code ike:jpackage-props} — emit jpackage properties from reactor config. */
    JPACKAGE_PROPS("jpackage-props", JpackagePropsMojo.class,
            "Emit jpackage properties files from reactor configuration."),
    /** {@code ike:notarize} — submit a {@code .pkg}/{@code .app} to Apple notary. */
    NOTARIZE("notarize", NotarizeMojo.class,
            "Submit a .pkg or .app to Apple notary service and staple the ticket."),
    /** {@code ike:patch-docbook} — apply local patches to DocBook XSL output. */
    PATCH_DOCBOOK("patch-docbook", PatchDocbookMojo.class,
            "Apply local patches to the DocBook XSL output."),
    /** {@code ike:prepare-renderer-output} — prepare per-renderer output dirs. */
    PREPARE_RENDERER_OUTPUT("prepare-renderer-output", PrepareRendererOutputMojo.class,
            "Prepare per-renderer output directories."),
    /** {@code ike:register-site-draft} — preview registering a site alias. */
    REGISTER_SITE_DRAFT("register-site-draft", RegisterSiteDraftMojo.class,
            "Preview registering a site alias in the site registry."),
    /** {@code ike:register-site-publish} — register a site alias. */
    REGISTER_SITE_PUBLISH("register-site-publish", RegisterSitePublishMojo.class,
            "Register a site alias in the site registry."),
    /** {@code ike:release-draft} — preview releasing the current project. */
    RELEASE_DRAFT("release-draft", ReleaseDraftMojo.class,
            "Preview releasing the current project."),
    /** {@code ike:release-publish} — release the current project (tag + publish). */
    RELEASE_PUBLISH("release-publish", ReleasePublishMojo.class,
            "Release the current project (tag + publish)."),
    /** {@code ike:rename} — rename output files to a canonical pattern. */
    RENAME("rename", RenameMojo.class,
            "Rename output files to a canonical pattern."),
    /** {@code ike:render-pdf} — render AsciiDoc to PDF via a configured renderer. */
    RENDER_PDF("render-pdf", RenderPdfMojo.class,
            "Render AsciiDoc to PDF via a configured renderer."),
    /** {@code ike:scaffold-draft} — preview ike-build-standards scaffold changes. */
    SCAFFOLD_DRAFT("scaffold-draft", ScaffoldDraftMojo.class,
            "Preview what ike:scaffold-publish would apply from the "
                    + "ike-build-standards scaffold."),
    /** {@code ike:scaffold-publish} — apply the scaffold to disk. */
    SCAFFOLD_PUBLISH("scaffold-publish", ScaffoldPublishMojo.class,
            "Apply the ike-build-standards scaffold to disk and "
                    + "update per-project and per-user lockfiles."),
    /** {@code ike:scaffold-revert} — undo a previous scaffold-publish. */
    SCAFFOLD_REVERT("scaffold-revert", ScaffoldRevertMojo.class,
            "Undo a previous ike:scaffold-publish, leaving "
                    + "user-edited files alone."),
    /** {@code ike:scan-logs} — scan renderer logs for warnings and errors. */
    SCAN_LOGS("scan-logs", ScanRendererLogsMojo.class,
            "Scan renderer logs for warnings and errors."),
    /** {@code ike:setup} — one-time setup for an IKE development machine. */
    SETUP("setup", SetupMojo.class,
            "One-time setup for an IKE development machine."),
    /** {@code ike:unpack-zip} — unpack a zip artifact into a target directory. */
    UNPACK_ZIP("unpack-zip", UnpackZipMojo.class,
            "Unpack a zip artifact into a target directory."),
    /** {@code ike:versions-upgrade-draft} — preview proposed version upgrades. */
    VERSIONS_UPGRADE_DRAFT("versions-upgrade-draft",
            VersionsUpgradeDraftMojo.class,
            "Preview version upgrades against the configured ruleset."),
    /** {@code ike:versions-upgrade-publish} — apply the version-upgrade plan. */
    VERSIONS_UPGRADE_PUBLISH("versions-upgrade-publish",
            VersionsUpgradePublishMojo.class,
            "Apply the version-upgrade plan to the project's POM.");

    /** Shared {@code ike:} prefix for all goals in this plugin. */
    public static final String PLUGIN_PREFIX = "ike";

    private static final String DRAFT_SUFFIX = "-draft";
    private static final String PUBLISH_SUFFIX = "-publish";

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
        for (IkeGoal g : values()) {
            if (g.goalName.equals(goalName)) return Optional.of(g);
        }
        return Optional.empty();
    }

    private static String stripSuffix(String s, String suffix) {
        return s.substring(0, s.length() - suffix.length());
    }
}
