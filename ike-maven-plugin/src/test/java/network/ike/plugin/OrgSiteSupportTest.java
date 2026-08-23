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
                        "ike-platform",
                        "ike-starter-set");
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
    void badge_present_for_every_released_foundation_member() {
        for (String id : OrgSiteSupport.FOUNDATION.keySet()) {
            if (OrgSiteSupport.NOT_ON_MAVEN_CENTRAL.contains(id)) {
                continue;
            }
            assertThat(OrgSiteSupport.mavenCentralBadge(id))
                    .as("badge for foundation member " + id)
                    .isNotNull();
        }
    }

    @Test
    void badge_is_null_for_foundation_members_not_yet_on_central() {
        // Foundation membership states a project's TIER, not that it has
        // been released. A pre-release member belongs in the Foundation
        // section from the outset, but a badge pointing at coordinates
        // that do not resolve renders as not-found — so it is omitted
        // until first release. IKE-Network/ike-issues#930.
        assertThat(OrgSiteSupport.NOT_ON_MAVEN_CENTRAL)
                .contains("ike-starter-set");
        assertThat(OrgSiteSupport.FOUNDATION)
                .containsKey("ike-starter-set");
        assertThat(OrgSiteSupport.mavenCentralBadge("ike-starter-set"))
                .isNull();
    }

    @Test
    void every_not_on_central_entry_is_a_foundation_member() {
        // The set only suppresses badges for foundation members; an entry
        // naming a non-member would be dead configuration.
        assertThat(OrgSiteSupport.FOUNDATION.keySet())
                .containsAll(OrgSiteSupport.NOT_ON_MAVEN_CENTRAL);
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

        // The diagram is emitted as an image:: macro pointing at the
        // committed static SVG in the org repo's site resources — no
        // Kroki URL, no runtime diagram-service dependency (see
        // IKE-DIAGRAMS.md site pages, IKE-Network/ike-issues#797).
        assertThat(index)
                .contains(".Build/release dependency order")
                .contains("image::" + OrgSiteSupport.FOUNDATION_DIAGRAM_SVG)
                .doesNotContain("kroki")
                .contains("[Build/release dependency order]")
                // The prose that frames the diagram is unchanged.
                .contains("can release in either order or in parallel");
    }

    @Test
    void foundation_diagram_svg_reference_is_a_site_relative_path() {
        // The foundation diagram is a committed static SVG under the
        // org repo's src/site/resources/images/ — the reference must be
        // a plain site-relative image path (served as images/...), never
        // an absolute URL or a diagram-service (Kroki) URL.
        assertThat(OrgSiteSupport.FOUNDATION_DIAGRAM_SVG)
                .isEqualTo("images/foundation-dependency.svg")
                .doesNotContain("http")
                .doesNotContain("kroki");
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

    @Test
    void regenerateSiteXml_uses_fragment_recorded_urls_over_composition() throws Exception {
        File orgRoot = newOrgRepoWithFragments("doc-example");
        // A fragment whose artifactId differs from its repository —
        // the registration recorded the true URLs in its headers
        // (ike-issues#1074).
        Files.writeString(tempDir.resolve("projects/ike-lease.adoc"), """
                // IKE Project Registration Fragment
                // project-id: ike-lease
                // project-url: https://ike.network/ike-lease-plugin/
                // github-url: https://github.com/IKE-Network/ike-lease-plugin

                = IKE Working-Set Leases
                """);
        Path siteXml = orgRoot.toPath().resolve("src/site/site.xml");
        Files.createDirectories(siteXml.getParent());
        Files.writeString(siteXml, """
                <site><body>
                    <menu name="Foundation"><item name="x" href="y"/></menu>
                    <menu name="Examples"><item name="x" href="y"/></menu>
                    <menu name="Source"><item name="x" href="y"/></menu>
                </body></site>
                """);

        OrgSiteSupport.regenerateSiteXml(orgRoot);
        String updated = Files.readString(siteXml);

        // The recorded URLs win, and the label is the repository name.
        assertThat(updated)
                .contains("href=\"https://github.com/IKE-Network/ike-lease-plugin\"")
                .contains("href=\"https://ike.network/ike-lease-plugin/\"")
                .contains("<item name=\"ike-lease-plugin\"")
                .doesNotContain("href=\"https://github.com/IKE-Network/ike-lease\"");

        // The header-less fragment still falls back to composition.
        assertThat(updated)
                .contains("href=\"https://github.com/IKE-Network/doc-example\"")
                .contains("href=\"https://ike.network/doc-example/\"");
    }

    @Test
    void fragmentHeader_reads_only_the_leading_comment_block() throws Exception {
        Path projects = Files.createDirectories(tempDir.resolve("frag"));
        Files.writeString(projects.resolve("a.adoc"), """
                // github-url: https://github.com/IKE-Network/real

                = Body
                // github-url: https://github.com/IKE-Network/decoy
                """);
        assertThat(OrgSiteSupport.fragmentHeader(projects, "a", "github-url"))
                .isEqualTo("https://github.com/IKE-Network/real");
        assertThat(OrgSiteSupport.fragmentHeader(projects, "a", "project-url"))
                .isNull();
        assertThat(OrgSiteSupport.fragmentHeader(projects, "absent", "github-url"))
                .isNull();
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
