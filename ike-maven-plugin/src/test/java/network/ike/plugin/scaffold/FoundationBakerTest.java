package network.ike.plugin.scaffold;

import network.ike.plugin.support.upgrade.CandidateVersionResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link FoundationBaker} (#414).
 */
class FoundationBakerTest {

    private static ScaffoldManifest.Foundation foundationAt(
            String parentVersion, String ikeDocs, String ikePlatform) {
        return new ScaffoldManifest.Foundation(
                new ScaffoldManifest.ParentRef(
                        "network.ike.platform", "ike-parent", parentVersion),
                Map.of(
                        "ike-tooling.version", "${project.version}",
                        "ike-docs.version", ikeDocs,
                        "ike-platform.version", ikePlatform));
    }

    /**
     * A fake resolver mapping {@code groupId:artifactId} to candidates.
     * Only {@code ike-parent} and {@code ike-docs} are ever queried —
     * the ike-platform pin shares the ike-parent resolution.
     */
    private static CandidateVersionResolver resolver(
            Map<String, List<String>> byCoord) {
        return (groupId, artifactId, currentVersion) ->
                byCoord.getOrDefault(groupId + ":" + artifactId, List.of());
    }

    private static final String MANIFEST = """
            # IKE scaffold manifest.
            schema: 1
            standards-version: "${project.version}"

            # IKE-foundation version pins.
            foundation:
              parent:
                groupId: network.ike.platform
                artifactId: ike-parent
                version: "35"
              properties:
                ike-tooling.version: "${project.version}"
                ike-docs.version: "13"
                ike-platform.version: "35"

            files:
              - dest: README.adoc
            """;

    // ── assess ──────────────────────────────────────────────────────

    @Test
    void assess_newerCandidate_isAhead() {
        List<FoundationBaker.Finding> findings = FoundationBaker.assess(
                foundationAt("35", "13", "35"),
                resolver(Map.of(
                        "network.ike.platform:ike-parent", List.of("35", "40"),
                        "network.ike.docs:ike-docs", List.of("13", "20"))));

        assertThat(findings).hasSize(3);
        FoundationBaker.Finding parent = findings.get(0);
        assertThat(parent.coordinate()).isEqualTo(FoundationBaker.IKE_PARENT);
        assertThat(parent.status()).isEqualTo(FoundationBaker.Status.AHEAD);
        assertThat(parent.latest()).isEqualTo("40");
        assertThat(findings.get(1).status())
                .isEqualTo(FoundationBaker.Status.AHEAD);
        // The ike-platform pin is answered by the ike-parent resolution.
        FoundationBaker.Finding platform = findings.get(2);
        assertThat(platform.coordinate())
                .isEqualTo(FoundationBaker.IKE_PLATFORM);
        assertThat(platform.status()).isEqualTo(FoundationBaker.Status.AHEAD);
        assertThat(platform.latest()).isEqualTo(parent.latest());
    }

    @Test
    void assess_pinNewerThanAnyGa_isBehind() {
        List<FoundationBaker.Finding> findings = FoundationBaker.assess(
                foundationAt("35", "13", "35"),
                resolver(Map.of(
                        "network.ike.platform:ike-parent", List.of("30", "33"),
                        "network.ike.docs:ike-docs", List.of("13"))));

        assertThat(findings.get(0).status())
                .isEqualTo(FoundationBaker.Status.BEHIND);
        assertThat(findings.get(0).latest()).isEqualTo("33");
        // ike-platform tracks the same resolution, so it is BEHIND too.
        assertThat(findings.get(2).status())
                .isEqualTo(FoundationBaker.Status.BEHIND);
    }

    @Test
    void assess_noCandidates_isUnresolved() {
        List<FoundationBaker.Finding> findings = FoundationBaker.assess(
                foundationAt("35", "13", "35"),
                resolver(Map.of(
                        "network.ike.platform:ike-parent", List.of("35"))));

        FoundationBaker.Finding docs = findings.get(1);
        assertThat(docs.coordinate()).isEqualTo(FoundationBaker.IKE_DOCS);
        assertThat(docs.status()).isEqualTo(FoundationBaker.Status.UNRESOLVED);
        assertThat(docs.latest()).isNull();
        // ike-parent / ike-platform still resolve and are current.
        assertThat(findings.get(0).status())
                .isEqualTo(FoundationBaker.Status.CURRENT);
        assertThat(findings.get(2).status())
                .isEqualTo(FoundationBaker.Status.CURRENT);
    }

    // ── rewrite ─────────────────────────────────────────────────────

    @Test
    void rewrite_appliesAheadBumps_preservesEverythingElse() {
        List<FoundationBaker.Finding> findings = FoundationBaker.assess(
                foundationAt("35", "13", "35"),
                resolver(Map.of(
                        "network.ike.platform:ike-parent", List.of("35", "40"),
                        "network.ike.docs:ike-docs", List.of("13", "20"))));

        String rewritten = FoundationBaker.rewrite(MANIFEST, findings);

        // parent and ike-platform both bake to the ike-parent resolution.
        assertThat(rewritten).contains("    version: \"40\"");
        assertThat(rewritten).contains("    ike-platform.version: \"40\"");
        assertThat(rewritten).contains("    ike-docs.version: \"20\"");
        // Filtered pin and the rest of the document untouched.
        assertThat(rewritten)
                .contains("    ike-tooling.version: \"${project.version}\"")
                .contains("# IKE-foundation version pins.")
                .contains("standards-version: \"${project.version}\"")
                .contains("  - dest: README.adoc");
    }

    @Test
    void rewrite_noAheadFindings_returnsInputUnchanged() {
        List<FoundationBaker.Finding> findings = FoundationBaker.assess(
                foundationAt("35", "13", "35"),
                resolver(Map.of(
                        "network.ike.platform:ike-parent", List.of("35"),
                        "network.ike.docs:ike-docs", List.of("13"))));

        assertThat(FoundationBaker.rewrite(MANIFEST, findings))
                .isEqualTo(MANIFEST);
    }
}
