package network.ike.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

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
    void foundation_contains_workspace_extension_between_docs_and_platform() {
        // The LinkedHashMap insertion order drives the rendered
        // Foundation section ordering. ike-workspace-extension is a
        // standalone Tier-0 artifact that ike-platform consumes at
        // workspace runtime — list it immediately before ike-platform.
        assertThat(OrgSiteSupport.FOUNDATION.keySet())
                .containsExactly(
                        "ike-base-parent",
                        "ike-tooling",
                        "ike-docs",
                        "ike-workspace-extension",
                        "ike-platform");
    }

    @Test
    void foundation_coordinates_use_full_groupId() {
        assertThat(OrgSiteSupport.FOUNDATION.get("ike-base-parent"))
                .isEqualTo("network.ike:ike-base-parent");
        assertThat(OrgSiteSupport.FOUNDATION.get("ike-workspace-extension"))
                .isEqualTo("network.ike.tooling:ike-workspace-extension");
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

        assertThat(index)
                .contains(".Build/release dependency order")
                .contains("[source]")
                .contains("ike-base-parent")
                .contains("ike-tooling")
                .contains("ike-workspace-extension")
                .contains("ike-docs")
                .contains("ike-platform")
                // The diagram explains the parallel level for tooling/extension.
                .contains("can release in either order or in parallel");
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
