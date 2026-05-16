package network.ike.workspace.cascade;

import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the cascade machine-readable export formats
 * (IKE-Network/ike-issues#403).
 */
class CascadeExporterTest {

    private static final ReleaseCascade CASCADE = ReleaseCascadeIo.read(
            new StringReader("""
            standards-version: 177
            cascade:
              - groupId: network.ike.tooling
                artifactId: ike-tooling
                url: https://github.com/IKE-Network/ike-tooling.git
              - groupId: network.ike.docs
                artifactId: ike-docs
                url: https://github.com/IKE-Network/ike-docs.git
                consumes:
                  - network.ike.tooling
                terminal: true
            """));

    @Test
    void toJson_emits_the_full_graph() {
        String json = CascadeExporter.toJson(CASCADE);

        assertThat(json).contains("\"standards-version\": \"177\"");
        assertThat(json).contains("\"groupId\": \"network.ike.tooling\"");
        assertThat(json).contains("\"artifactId\": \"ike-tooling\"");
        assertThat(json).contains(
                "\"url\": \"https://github.com/IKE-Network/ike-docs.git\"");
        assertThat(json).contains("\"consumes\": [\"network.ike.tooling\"]");
        assertThat(json).contains("\"consumes\": []");
    }

    @Test
    void toProperties_emits_order_and_edges() {
        String props = CascadeExporter.toProperties(CASCADE);

        assertThat(props).contains("cascade.standards-version=177");
        assertThat(props).contains("cascade.repos=ike-tooling,ike-docs");
        assertThat(props).contains(
                "cascade.ike-docs.groupId=network.ike.docs");
        assertThat(props).contains(
                "cascade.ike-docs.url=https://github.com/IKE-Network/ike-docs.git");
        assertThat(props).contains(
                "cascade.ike-docs.consumes=network.ike.tooling");
        // Root of the cascade — no upstream.
        assertThat(props).contains("cascade.ike-tooling.consumes=\n");
    }
}
