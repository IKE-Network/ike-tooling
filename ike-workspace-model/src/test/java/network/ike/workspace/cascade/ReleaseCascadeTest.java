package network.ike.workspace.cascade;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the assembled release cascade graph and
 * {@link CascadeAssembler} (IKE-Network/ike-issues#402, #420).
 */
class ReleaseCascadeTest {

    private static final CascadeEdge TOOLING = new CascadeEdge(
            "network.ike.tooling", "ike-tooling", "ike-tooling",
            "https://github.com/IKE-Network/ike-tooling.git", null);
    private static final CascadeEdge DOCS = new CascadeEdge(
            "network.ike.docs", "ike-docs", "ike-docs",
            "https://github.com/IKE-Network/ike-docs.git", null);
    private static final CascadeEdge PLATFORM = new CascadeEdge(
            "network.ike.platform", "ike-platform", "ike-platform",
            "https://github.com/IKE-Network/ike-platform.git", null);

    /**
     * ike-tooling: head — downstream ike-docs and ike-platform (both
     * carry {@code ${ike-tooling.version}}, so both are reciprocal
     * downstream edges).
     */
    private static final ProjectCascade TOOLING_CASCADE = new ProjectCascade(
            1, true, List.of(), false, List.of(DOCS, PLATFORM));
    /** ike-docs: middle — upstream ike-tooling, downstream ike-platform. */
    private static final ProjectCascade DOCS_CASCADE = new ProjectCascade(
            1, false, List.of(upstream(TOOLING, "ike-tooling.version")),
            false, List.of(PLATFORM));
    /** ike-platform: terminal — upstream ike-tooling + ike-docs. */
    private static final ProjectCascade PLATFORM_CASCADE = new ProjectCascade(
            1, false,
            List.of(upstream(TOOLING, "ike-tooling.version"),
                    upstream(DOCS, "ike-docs.version")),
            true, List.of());

    private static final Map<String, ProjectCascade> FOUNDATION = Map.of(
            "network.ike.tooling:ike-tooling", TOOLING_CASCADE,
            "network.ike.docs:ike-docs", DOCS_CASCADE,
            "network.ike.platform:ike-platform", PLATFORM_CASCADE);

    private static CascadeEdge upstream(CascadeEdge identity,
                                        String versionProperty) {
        return new CascadeEdge(identity.groupId(), identity.artifactId(),
                identity.repo(), identity.url(), versionProperty);
    }

    private static ReleaseCascade assembleFrom(CascadeEdge start,
                                               ProjectCascade startCascade,
                                               Map<String, ProjectCascade> all) {
        return CascadeAssembler.assemble(start, startCascade,
                edge -> all.get(edge.ga()));
    }

    @Test
    void assembles_the_foundation_in_topological_order() {
        ReleaseCascade cascade = assembleFrom(
                TOOLING, TOOLING_CASCADE, FOUNDATION);

        assertThat(cascade.repos()).extracting(CascadeRepo::artifactId)
                .containsExactly("ike-tooling", "ike-docs", "ike-platform");
        assertThat(cascade.find("network.ike.docs:ike-docs")).get()
                .extracting(CascadeRepo::ga)
                .isEqualTo("network.ike.docs:ike-docs");
    }

    @Test
    void assembles_the_whole_graph_from_a_middle_repo() {
        // Starting at ike-docs still walks upstream and downstream.
        ReleaseCascade cascade = assembleFrom(
                DOCS, DOCS_CASCADE, FOUNDATION);

        assertThat(cascade.repos()).extracting(CascadeRepo::artifactId)
                .containsExactly("ike-tooling", "ike-docs", "ike-platform");
    }

    @Test
    void downstreamOf_returns_transitive_consumers_in_order() {
        ReleaseCascade cascade = assembleFrom(
                TOOLING, TOOLING_CASCADE, FOUNDATION);

        assertThat(cascade.downstreamOf("network.ike.tooling:ike-tooling")
                        .stream().map(CascadeRepo::artifactId))
                .containsExactly("ike-docs", "ike-platform");
        assertThat(cascade.downstreamOf("network.ike.docs:ike-docs")
                        .stream().map(CascadeRepo::artifactId))
                .containsExactly("ike-platform");
        assertThat(cascade.downstreamOf("network.ike.platform:ike-platform"))
                .isEmpty();
        assertThat(cascade.downstreamOf("dev.ikm.komet:komet")).isEmpty();
        assertThat(cascade.contains("dev.ikm.komet:komet")).isFalse();
    }

    @Test
    void terminal_node_is_marked() {
        ReleaseCascade cascade = assembleFrom(
                TOOLING, TOOLING_CASCADE, FOUNDATION);

        assertThat(cascade.find("network.ike.platform:ike-platform")).get()
                .extracting(CascadeRepo::terminal).isEqualTo(true);
        assertThat(cascade.find("network.ike.tooling:ike-tooling")).get()
                .extracting(CascadeRepo::head).isEqualTo(true);
    }

    @Test
    void one_sided_downstream_edge_is_rejected() {
        // ike-tooling claims ike-docs downstream, but this ike-docs
        // manifest declares itself the head with no upstream edge.
        ProjectCascade orphanDocs = new ProjectCascade(
                1, true, List.of(), false, List.of(PLATFORM));
        Map<String, ProjectCascade> broken = Map.of(
                "network.ike.tooling:ike-tooling", TOOLING_CASCADE,
                "network.ike.docs:ike-docs", orphanDocs,
                "network.ike.platform:ike-platform", PLATFORM_CASCADE);

        assertThatThrownBy(() -> assembleFrom(
                TOOLING, TOOLING_CASCADE, broken))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one-sided cascade edge");
    }

    @Test
    void unresolvable_edge_is_rejected() {
        Map<String, ProjectCascade> missing = Map.of(
                "network.ike.tooling:ike-tooling", TOOLING_CASCADE,
                "network.ike.docs:ike-docs", DOCS_CASCADE);

        assertThatThrownBy(() -> assembleFrom(
                TOOLING, TOOLING_CASCADE, missing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ike-platform");
    }

    // ──────────────────────────────────────────────────────────────────
    // #466 regression: two cascade heads sharing a groupId.
    //
    // Before the fix, CascadeAssembler's internal maps were keyed by
    // groupId alone. With ike-tooling and ike-workspace-extension both
    // under network.ike.tooling, the second head silently dropped out
    // of the assembled graph and the topological sort then reported
    // a false-positive cycle.
    // ──────────────────────────────────────────────────────────────────

    private static final CascadeEdge EXTENSION = new CascadeEdge(
            "network.ike.tooling", "ike-workspace-extension",
            "ike-workspace-extension",
            "https://github.com/IKE-Network/ike-workspace-extension.git",
            null);

    /** ike-workspace-extension: second head — downstream ike-platform only. */
    private static final ProjectCascade EXTENSION_CASCADE = new ProjectCascade(
            1, true, List.of(), false, List.of(PLATFORM));

    /**
     * ike-platform with both ike-tooling, ike-docs, AND
     * ike-workspace-extension upstream (today's real shape).
     */
    private static final ProjectCascade PLATFORM_CASCADE_WITH_EXTENSION =
            new ProjectCascade(1, false,
                    List.of(upstream(TOOLING, "ike-tooling.version"),
                            upstream(DOCS, "ike-docs.version"),
                            upstream(EXTENSION,
                                    "ike-workspace-extension.version")),
                    true, List.of());

    @Test
    void two_heads_same_groupId_assemble_without_false_cycle() {
        // ike-tooling and ike-workspace-extension both live under
        // network.ike.tooling. The assembler's internal maps must key
        // by ga (groupId:artifactId), not groupId alone, or one head
        // collapses into the other and the topo sort reports a
        // bogus cycle (#466).
        Map<String, ProjectCascade> foundation = Map.of(
                "network.ike.tooling:ike-tooling", TOOLING_CASCADE,
                "network.ike.docs:ike-docs", DOCS_CASCADE,
                "network.ike.tooling:ike-workspace-extension",
                        EXTENSION_CASCADE,
                "network.ike.platform:ike-platform",
                        PLATFORM_CASCADE_WITH_EXTENSION);

        ReleaseCascade cascade = assembleFrom(
                TOOLING, TOOLING_CASCADE, foundation);

        // All four members present.
        assertThat(cascade.repos()).extracting(CascadeRepo::ga)
                .containsExactlyInAnyOrder(
                        "network.ike.tooling:ike-tooling",
                        "network.ike.docs:ike-docs",
                        "network.ike.tooling:ike-workspace-extension",
                        "network.ike.platform:ike-platform");
        // Terminal lands last; both heads land before it.
        assertThat(cascade.repos().get(cascade.repos().size() - 1).ga())
                .isEqualTo("network.ike.platform:ike-platform");
        // The two same-groupId members are distinct nodes.
        assertThat(cascade.find("network.ike.tooling:ike-tooling")).get()
                .extracting(CascadeRepo::head).isEqualTo(true);
        assertThat(cascade.find(
                "network.ike.tooling:ike-workspace-extension")).get()
                .extracting(CascadeRepo::head).isEqualTo(true);
    }

    @Test
    void two_heads_same_groupId_assemble_starting_from_terminal() {
        // Same graph, but start the walk from the terminal so the
        // assembler walks up to both heads via the upstream list.
        // Exercises the byGa-collision path on the upstream side too.
        Map<String, ProjectCascade> foundation = Map.of(
                "network.ike.tooling:ike-tooling", TOOLING_CASCADE,
                "network.ike.docs:ike-docs", DOCS_CASCADE,
                "network.ike.tooling:ike-workspace-extension",
                        EXTENSION_CASCADE,
                "network.ike.platform:ike-platform",
                        PLATFORM_CASCADE_WITH_EXTENSION);

        ReleaseCascade cascade = assembleFrom(
                PLATFORM, PLATFORM_CASCADE_WITH_EXTENSION, foundation);

        assertThat(cascade.repos()).extracting(CascadeRepo::ga)
                .containsExactlyInAnyOrder(
                        "network.ike.tooling:ike-tooling",
                        "network.ike.docs:ike-docs",
                        "network.ike.tooling:ike-workspace-extension",
                        "network.ike.platform:ike-platform");
    }

    @Test
    void downstreamOf_distinguishes_same_groupId_heads() {
        // After fix: downstreamOf treats GA, not groupId, so the two
        // heads' stale sets are independent (the extension does not
        // make ike-docs stale; releasing ike-tooling does).
        Map<String, ProjectCascade> foundation = Map.of(
                "network.ike.tooling:ike-tooling", TOOLING_CASCADE,
                "network.ike.docs:ike-docs", DOCS_CASCADE,
                "network.ike.tooling:ike-workspace-extension",
                        EXTENSION_CASCADE,
                "network.ike.platform:ike-platform",
                        PLATFORM_CASCADE_WITH_EXTENSION);
        ReleaseCascade cascade = assembleFrom(
                TOOLING, TOOLING_CASCADE, foundation);

        assertThat(cascade.downstreamOf(
                "network.ike.tooling:ike-tooling")
                        .stream().map(CascadeRepo::artifactId))
                .containsExactly("ike-docs", "ike-platform");
        assertThat(cascade.downstreamOf(
                "network.ike.tooling:ike-workspace-extension")
                        .stream().map(CascadeRepo::artifactId))
                .containsExactly("ike-platform");
    }

    @Test
    void reporter_publish_footer_names_downstream_and_next_step() {
        List<String> footer = CascadeReporter.publishFooter(
                TOOLING_CASCADE, "ike-tooling");

        assertThat(footer).anyMatch(l -> l.contains("ike-tooling released"));
        assertThat(footer).anyMatch(l -> l.contains("cd ../ike-docs"));
        assertThat(footer).anyMatch(l -> l.contains("ike:release-cascade"));
    }

    @Test
    void reporter_publish_footer_marks_end_of_cascade() {
        List<String> footer = CascadeReporter.publishFooter(
                PLATFORM_CASCADE, "ike-platform");

        assertThat(footer).anyMatch(l -> l.contains("End of the foundation"));
    }

    @Test
    void reporter_draft_preview_names_downstream_repos() {
        List<String> preview = CascadeReporter.draftPreview(
                TOOLING_CASCADE, "ike-tooling");

        assertThat(preview).anyMatch(l -> l.contains("ike-docs"));
        assertThat(preview).anyMatch(l -> l.contains("ike-platform"));
        assertThat(preview).anyMatch(
                l -> l.contains("ike:release-cascade"));
    }
}
