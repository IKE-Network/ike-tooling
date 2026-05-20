package network.ike.plugin;

import org.junit.jupiter.api.Test;

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
        assertThat(OrgSiteSupport.mavenCentralBadge("example-project"))
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
}
