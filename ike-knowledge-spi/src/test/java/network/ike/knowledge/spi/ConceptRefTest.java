package network.ike.knowledge.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The concept-reference grammar: three forms, canonical formatting, parse/format
 * round-trip.
 */
class ConceptRefTest {

    @Test
    @DisplayName("uuid: form carries one or more UUIDs — public ids are UUID sets")
    void uuidForm() {
        UUID first = UUID.fromString("3c9d1e7a-52f4-4b8e-b1a6-0d5e8c2f7a41");
        UUID second = UUID.fromString("6b4f2a8c-91d3-4e7f-a2c5-8e0b1d6f3c92");
        ConceptRef parsed = ConceptRef.parse("uuid:" + first + " , " + second);
        assertThat(parsed).isEqualTo(new ConceptRef.Uuids(List.of(first, second)));
        assertThat(ConceptRef.parse(parsed.format())).isEqualTo(parsed);
        assertThat(ConceptRef.ofUuid(first)).isEqualTo(new ConceptRef.Uuids(List.of(first)));

        assertThatIllegalArgumentException().isThrownBy(() -> ConceptRef.parse("uuid:"));
        assertThatIllegalArgumentException().isThrownBy(() -> ConceptRef.parse("uuid:not-a-uuid"));
    }

    @Test
    @DisplayName("Binding form names a constant, with the class optional (implementation default)")
    void bindingForm() {
        ConceptRef qualified = ConceptRef.parse("RichSurfaceTerms#RICH_SURFACE_MODULE");
        assertThat(qualified).isEqualTo(new ConceptRef.Binding("RichSurfaceTerms", "RICH_SURFACE_MODULE"));
        ConceptRef bare = ConceptRef.parse("#DEVELOPMENT_PATH");
        assertThat(bare).isEqualTo(new ConceptRef.Binding(null, "DEVELOPMENT_PATH"));
        assertThat(ConceptRef.parse(qualified.format())).isEqualTo(qualified);
        assertThat(ConceptRef.parse(bare.format())).isEqualTo(bare);

        assertThatIllegalArgumentException().isThrownBy(() -> ConceptRef.parse("SomeClass#"));
    }

    @Test
    @DisplayName("Text form is the bare fallback; the explicit prefix protects reserved shapes")
    void textForm() {
        assertThat(ConceptRef.parse("Journal element (RichSurfaceTerms)"))
                .isEqualTo(new ConceptRef.Text("Journal element (RichSurfaceTerms)"));
        assertThat(ConceptRef.parse("text:uuid:looks-reserved"))
                .isEqualTo(new ConceptRef.Text("uuid:looks-reserved"));

        ConceptRef hashText = new ConceptRef.Text("C# terminology");
        assertThat(hashText.format()).isEqualTo("text:C# terminology");
        assertThat(ConceptRef.parse(hashText.format())).isEqualTo(hashText);

        ConceptRef reservedText = new ConceptRef.Text("uuid:not really");
        assertThat(ConceptRef.parse(reservedText.format())).isEqualTo(reservedText);

        assertThatIllegalArgumentException().isThrownBy(() -> ConceptRef.parse("  "));
        assertThatIllegalArgumentException().isThrownBy(() -> ConceptRef.parse("text:  "));
    }
}
