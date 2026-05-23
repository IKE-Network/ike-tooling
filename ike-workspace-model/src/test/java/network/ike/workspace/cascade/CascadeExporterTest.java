package network.ike.workspace.cascade;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the cascade machine-readable export formats
 * (IKE-Network/ike-issues#403, #420).
 */
class CascadeExporterTest {

    private static final CascadeEdge TOOLING = new CascadeEdge(
            "network.ike.tooling", "ike-tooling", "ike-tooling",
            "https://github.com/IKE-Network/ike-tooling.git");
    private static final CascadeEdge DOCS = new CascadeEdge(
            "network.ike.docs", "ike-docs", "ike-docs",
            "https://github.com/IKE-Network/ike-docs.git");

    private static final CascadeRepo TOOLING_NODE = new CascadeRepo(
            "network.ike.tooling", "ike-tooling", "ike-tooling",
            "https://github.com/IKE-Network/ike-tooling.git",
            new ProjectCascade(1, true, List.of(), false, List.of(DOCS)));
    private static final CascadeRepo DOCS_NODE = new CascadeRepo(
            "network.ike.docs", "ike-docs", "ike-docs",
            "https://github.com/IKE-Network/ike-docs.git",
            new ProjectCascade(1, false, List.of(TOOLING),
                    true, List.of()));

    private static final ReleaseCascade CASCADE = new ReleaseCascade(
            List.of(TOOLING_NODE, DOCS_NODE));

    @Test
    void toJson_emits_the_full_graph() {
        String json = CascadeExporter.toJson(CASCADE);

        assertThat(json).contains("\"groupId\": \"network.ike.tooling\"");
        assertThat(json).contains("\"artifactId\": \"ike-tooling\"");
        assertThat(json).contains(
                "\"url\": \"https://github.com/IKE-Network/ike-docs.git\"");
        assertThat(json).contains("\"consumes\": [\"network.ike.tooling\"]");
        assertThat(json).contains("\"consumes\": []");
        assertThat(json).contains("\"terminal\": true");
    }

    @Test
    void toProperties_emits_order_and_edges() {
        String props = CascadeExporter.toProperties(CASCADE);

        assertThat(props).contains("cascade.repos=ike-tooling,ike-docs");
        assertThat(props).contains(
                "cascade.ike-docs.groupId=network.ike.docs");
        assertThat(props).contains(
                "cascade.ike-docs.url=https://github.com/IKE-Network/ike-docs.git");
        assertThat(props).contains(
                "cascade.ike-docs.consumes=network.ike.tooling");
        assertThat(props).contains("cascade.ike-docs.terminal=true");
        // Head of the cascade — no upstream.
        assertThat(props).contains("cascade.ike-tooling.consumes=\n");
    }
}
