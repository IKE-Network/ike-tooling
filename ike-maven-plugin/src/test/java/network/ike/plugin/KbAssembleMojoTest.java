package network.ike.plugin;

import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How a {@code SOURCE} input entry splits into its importer, release, and options.
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
}
