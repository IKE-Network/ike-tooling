package network.ike.plugin;

import network.ike.knowledge.spi.ArtifactInput;
import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How a {@code SOURCE} input entry splits into its importer, release, and options, and how the
 * inputs are recorded in the knowledge-base descriptor.
 */
class KbAssembleMojoTest {

    @Test
    void sourceWordsKeepQuotedValuesWhole() {
        assertThat(KbAssembleMojo.words(
                "snomed-rf2  /data/loinc.zip release=full author=\"IHTSDO SNOMED CT LOINC Collaboration Author\""))
                .containsExactly("snomed-rf2", "/data/loinc.zip", "release=full",
                        "author=IHTSDO SNOMED CT LOINC Collaboration Author");
    }

    @Test
    void unbalancedQuoteIsRefused() {
        assertThatThrownBy(() -> KbAssembleMojo.words("rxnorm-owl a.owl releaseDate=\"2024-04-10"))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("Unbalanced quote");
    }

    @Test
    void theDescriptorRecordsEachInputByItsCoordinateOrName() {
        List<ArtifactInput> inputs = List.of(
                new ArtifactInput(ArtifactInput.Role.PB, Path.of("/m2/snomedct-kb-20260601.2-20261026.160451-9-unreasoned-pb.zip"))
                        .withCoordinate("network.ike.data:snomedct-kb:zip:unreasoned-pb:20260601.2-SNAPSHOT",
                                "20260601.2-20261026.160451-9"),
                new ArtifactInput(ArtifactInput.Role.PB, Path.of("/m2/ike-starter-set-1-unreasoned-pb.zip"))
                        .withCoordinate("network.ike.foundation:ike-starter-set:zip:unreasoned-pb:1", "1"),
                ArtifactInput.source("snomed-rf2", Path.of("/m2/rf2.zip"), Map.of("release", "full"))
                        .withCoordinate("org.snomed:rf2:zip:20260601"),
                new ArtifactInput(ArtifactInput.Role.PB, Path.of("target/solor-kb-1-SNAPSHOT-reasoned-pb.zip")),
                new ArtifactInput(ArtifactInput.Role.CHANGESET, Path.of("changesets/2026-10-25T10-00.pb.zip")));

        assertThat(KbAssembleMojo.descriptorInputs(inputs, "network.ike.data", "solor-kb", "1-SNAPSHOT")).containsExactly(
                new KnowledgeBaseDescriptor.Input("PB", "network.ike.data:snomedct-kb:zip:unreasoned-pb:20260601.2-SNAPSHOT",
                        Optional.of("20260601.2-20261026.160451-9"), Optional.empty()),
                new KnowledgeBaseDescriptor.Input("PB", "network.ike.foundation:ike-starter-set:zip:unreasoned-pb:1",
                        Optional.empty(), Optional.empty()),
                new KnowledgeBaseDescriptor.Input("SOURCE", "org.snomed:rf2:zip:20260601", Optional.empty(),
                        Optional.of("snomed-rf2")),
                new KnowledgeBaseDescriptor.Input("PB", "network.ike.data:solor-kb:zip:reasoned-pb:1-SNAPSHOT",
                        Optional.empty(), Optional.empty()),
                new KnowledgeBaseDescriptor.Input("CHANGESET", "file:2026-10-25T10-00.pb.zip", Optional.empty(),
                        Optional.empty()));
    }
}
