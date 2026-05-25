package network.ike.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the foundation-membership and badge-generation logic in
 * {@link OrgSiteSupport}, exercising the contract changes from
 * IKE-Network/ike-issues#465.
 *
 * <p>The regenerated landing page on https://ike.network/ classifies
 * registered projects into a "Foundation" section (Maven Central
 * artifacts everyone inherits) and an "Examples" section (worked
 * examples that aren't on Central). The {@link OrgSiteSupport#FOUNDATION}
 * map is the source of truth for that split AND for which entries get
 * a Maven Central version badge.
 */
class OrgSiteSupportTest {

    // ── Foundation membership (#465) ────────────────────────────────

    @Test
    void foundation_ordering_is_dependency_direction() {
        // The LinkedHashMap insertion order drives the rendered
        // Foundation section ordering, top-to-bottom in the
        // dependency direction (upstream → downstream):
        //   ike-base-parent (Tier-0 parent)
        //     → ike-java-support (Tier-0 value types; IKE-Network/ike-issues#498)
        //       → ike-tooling, ike-docs, ike-workspace-extension (Tier-1)
        //       → ike-version-management-extension (Tier-1 build extension)
        //         → ike-platform (Tier-2)
        // ike-workspace-extension is consumed by ike-platform at
        // workspace runtime (#460); ike-version-management-extension
        // is registered, not resolved, so its order relative to its
        // siblings is by logical grouping.
        assertThat(OrgSiteSupport.FOUNDATION.keySet())
                .containsExactly(
                        "ike-base-parent",
                        "ike-java-support",
                        "ike-tooling",
                        "ike-docs",
                        "ike-workspace-extension",
                        "ike-version-management-extension",
                        "ike-platform");
    }

    @Test
    void foundation_coordinates_use_full_groupId() {
        assertThat(OrgSiteSupport.FOUNDATION.get("ike-base-parent"))
                .isEqualTo("network.ike:ike-base-parent");
        assertThat(OrgSiteSupport.FOUNDATION.get("ike-java-support"))
                .isEqualTo("network.ike:ike-java-support");
        assertThat(OrgSiteSupport.FOUNDATION.get("ike-workspace-extension"))
                .isEqualTo("network.ike.tooling:ike-workspace-extension");
        assertThat(OrgSiteSupport.FOUNDATION.get("ike-version-management-extension"))
                .isEqualTo("network.ike.tooling:ike-version-management-extension");
        assertThat(OrgSiteSupport.FOUNDATION.get("ike-platform"))
                .isEqualTo("network.ike.platform:ike-platform");
    }

    // ── Maven Central badge (#465) ──────────────────────────────────

    @Test
    void badge_uses_shields_io_with_full_coordinates() {
        String badge = OrgSiteSupport.mavenCentralBadge("ike-workspace-extension");
        assertThat(badge)
                .contains("https://img.shields.io/maven-central/v/"
                        + "network.ike.tooling/ike-workspace-extension")
                .contains("https://central.sonatype.com/artifact/"
                        + "network.ike.tooling/ike-workspace-extension");
    }

    @Test
    void badge_is_null_for_non_foundation_projects() {
        // Examples (doc-example, etc.) don't get a Central badge —
        // they're deliberately not on Maven Central.
        assertThat(OrgSiteSupport.mavenCentralBadge("doc-example"))
                .isNull();
        assertThat(OrgSiteSupport.mavenCentralBadge("project-example"))
                .isNull();
    }

    @Test
    void badge_present_for_every_foundation_member() {
        for (String id : OrgSiteSupport.FOUNDATION.keySet()) {
            assertThat(OrgSiteSupport.mavenCentralBadge(id))
                    .as("badge for foundation member " + id)
                    .isNotNull();
        }
    }

    // ── Foundation dependency diagram in the landing-page preamble ──

    @TempDir
    Path tempDir;

    @Test
    void regenerated_index_includes_dependency_diagram() throws Exception {
        // Seed two foundation fragments so the Foundation section
        // (which carries the diagram) is rendered. Diagram presence
        // is gated on having a non-empty foundation listing.
        File orgRoot = newOrgRepoWithFragments("ike-base-parent", "ike-platform");

        OrgSiteSupport.regenerateIndex(orgRoot);

        String index = Files.readString(orgRoot.toPath()
                .resolve("src/site/asciidoc/index.adoc"));

        // The diagram is emitted as an image:: macro pointing at a
        // Kroki-rendered SVG; the artifact IDs live inside the
        // base64-encoded GraphViz source, not in the index text.
        assertThat(index)
                .contains(".Build/release dependency order")
                .contains("image::" + OrgSiteSupport.KROKI_BASE
                        + "/graphviz/svg/")
                .contains("[Build/release dependency order]")
                // The prose that frames the diagram is unchanged.
                .contains("can release in either order or in parallel");
    }

    @Test
    void foundation_diagram_source_names_every_member() {
        // The diagram's GraphViz source must mention every foundation
        // member by artifactId — otherwise the rendered SVG silently
        // drops a node and the published landing page disagrees with
        // the FOUNDATION map (the source of truth).
        for (String id : OrgSiteSupport.FOUNDATION.keySet()) {
            assertThat(OrgSiteSupport.FOUNDATION_DIAGRAM)
                    .as("diagram source references " + id)
                    .contains(id);
        }
    }

    @Test
    void krokiUrl_produces_a_kroki_https_path() {
        // The URL must start with the configured base, name the diagram
        // type, declare svg output, and end with a non-empty base64url
        // segment. We don't pin the exact encoded value because zlib
        // output differs across JDK builds at the byte level.
        String url = OrgSiteSupport.krokiUrl("graphviz", "digraph G { a -> b }");

        assertThat(url)
                .startsWith(OrgSiteSupport.KROKI_BASE + "/graphviz/svg/")
                .matches(".*/svg/[A-Za-z0-9_=-]+$");
    }

    @Test
    void regenerated_index_omits_diagram_when_no_foundation_members() throws Exception {
        // Examples-only org (no foundation fragments) should NOT
        // render the Foundation section or the diagram.
        File orgRoot = newOrgRepoWithFragments("doc-example");

        OrgSiteSupport.regenerateIndex(orgRoot);

        String index = Files.readString(orgRoot.toPath()
                .resolve("src/site/asciidoc/index.adoc"));

        assertThat(index)
                .doesNotContain("== Foundation")
                .doesNotContain("Build/release dependency order")
                .contains("== Examples");
    }

    // ── site.xml regeneration (#520) ─────────────────────────────────

    @Test
    void displayTitle_uses_IKE_acronym_and_TitleCase_for_other_tokens() {
        assertThat(OrgSiteSupport.displayTitle("ike-base-parent"))
                .isEqualTo("IKE Base Parent");
        assertThat(OrgSiteSupport.displayTitle("ike-version-management-extension"))
                .isEqualTo("IKE Version Management Extension");
        assertThat(OrgSiteSupport.displayTitle("ike-java-support"))
                .isEqualTo("IKE Java Support");
        assertThat(OrgSiteSupport.displayTitle("doc-example"))
                .isEqualTo("Doc Example");
    }

    @Test
    void renderSiteMenu_emits_item_per_id_with_url_and_title() {
        String menu = OrgSiteSupport.renderSiteMenu(
                "Foundation",
                List.of("ike-base-parent", "ike-tooling"),
                id -> "https://ike.network/" + id + "/",
                OrgSiteSupport::displayTitle);

        assertThat(menu)
                .contains("<menu name=\"Foundation\">")
                .contains("<item name=\"IKE Base Parent\"")
                .contains("href=\"https://ike.network/ike-base-parent/\"")
                .contains("<item name=\"IKE Tooling\"")
                .contains("href=\"https://ike.network/ike-tooling/\"")
                .endsWith("</menu>");
    }

    @Test
    void replaceMenu_replaces_only_the_matching_named_block() {
        String src = """
                <body>
                    <menu name="Foundation">
                        <item name="Old" href="x"/>
                    </menu>
                    <menu name="Examples">
                        <item name="Keep" href="k"/>
                    </menu>
                </body>
                """;

        String replacement = """
                        <menu name="Foundation">
                            <item name="New" href="n"/>
                        </menu>""";

        String out = OrgSiteSupport.replaceMenu(src, "Foundation", replacement);

        assertThat(out)
                .contains("<item name=\"New\" href=\"n\"/>")
                .doesNotContain("<item name=\"Old\"")
                .contains("<item name=\"Keep\" href=\"k\"/>");
    }

    @Test
    void replaceMenu_returns_source_unchanged_when_block_absent() {
        String src = "<body><menu name=\"Other\"><item/></menu></body>";
        assertThat(OrgSiteSupport.replaceMenu(src, "Foundation", "x"))
                .isEqualTo(src);
    }

    @Test
    void regenerateSiteXml_rewrites_Foundation_Examples_and_Source_in_place() throws Exception {
        File orgRoot = newOrgRepoWithFragments(
                "ike-base-parent", "ike-tooling", "ike-platform",
                "doc-example", "workspace-reactor-example");
        Path siteXml = orgRoot.toPath().resolve("src/site/site.xml");
        Files.createDirectories(siteXml.getParent());
        // Minimal site descriptor — the regenerator should preserve
        // the licence comment and replace only the three menu blocks.
        Files.writeString(siteXml, """
                <site>
                    <!-- preserved licence header -->
                    <body>
                        <menu name="Foundation">
                            <item name="Stale" href="https://example.org/stale/"/>
                        </menu>
                        <menu name="Examples">
                            <item name="stale-example"
                                  href="https://example.org/stale/"/>
                        </menu>
                        <menu name="Source">
                            <item name="stale" href="https://example.org/stale"/>
                        </menu>
                    </body>
                </site>
                """);

        OrgSiteSupport.regenerateSiteXml(orgRoot);

        String updated = Files.readString(siteXml);

        // Licence comment preserved (the regenerator touched only menus).
        assertThat(updated).contains("preserved licence header");

        // Foundation: in FOUNDATION-map order, with IKE-acronym titles.
        int basePos = updated.indexOf("IKE Base Parent");
        int toolPos = updated.indexOf("IKE Tooling");
        int platPos = updated.indexOf("IKE Platform");
        assertThat(basePos).isPositive();
        assertThat(toolPos).isGreaterThan(basePos);
        assertThat(platPos).isGreaterThan(toolPos);

        // Examples: alphabetical, raw artifact IDs as titles, ike.network URLs.
        assertThat(updated)
                .contains("<item name=\"doc-example\"")
                .contains("href=\"https://ike.network/doc-example/\"")
                .contains("<item name=\"workspace-reactor-example\"");

        // Source: GitHub URLs for both foundation and examples.
        assertThat(updated)
                .contains("href=\"https://github.com/IKE-Network/ike-base-parent\"")
                .contains("href=\"https://github.com/IKE-Network/doc-example\"");

        // Stale entries are gone.
        assertThat(updated)
                .doesNotContain("stale")
                .doesNotContain("Stale");
    }

    @Test
    void regenerateSiteXml_is_a_no_op_when_site_xml_absent() throws Exception {
        File orgRoot = newOrgRepoWithFragments("ike-base-parent");
        // No site.xml at src/site/.
        OrgSiteSupport.regenerateSiteXml(orgRoot);
        assertThat(Files.exists(orgRoot.toPath().resolve("src/site/site.xml")))
                .isFalse();
    }

    private File newOrgRepoWithFragments(String... artifactIds) throws Exception {
        Path projects = Files.createDirectories(
                tempDir.resolve("projects"));
        for (String id : artifactIds) {
            Files.writeString(projects.resolve(id + ".adoc"),
                    "= " + id + "\n");
        }
        return tempDir.toFile();
    }
}
