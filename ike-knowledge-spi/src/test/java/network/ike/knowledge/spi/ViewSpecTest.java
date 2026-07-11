package network.ike.knowledge.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The ViewSpec vocabulary: strict keys, overrides-only semantics, wire round-trip.
 */
class ViewSpecTest {

    @Test
    @DisplayName("Stated dimensions round-trip through the prefixed properties wire form")
    void propertiesRoundTrip() {
        ViewSpec spec = ViewSpec.of(Map.of(
                ViewSpec.STAMP_ALLOWED_STATES, "Active",
                ViewSpec.STAMP_POSITION_PATH, "#DEVELOPMENT_PATH",
                ViewSpec.EDIT_DEFAULT_MODULE, "RichSurfaceTerms#RICH_SURFACE_MODULE",
                "language.2.language", "#SPANISH_LANGUAGE"));
        Properties wire = spec.toProperties("view.");
        assertThat(wire.getProperty("view." + ViewSpec.STAMP_ALLOWED_STATES)).isEqualTo("Active");
        ViewSpec decoded = ViewSpec.fromProperties(wire, "view.");
        assertThat(decoded).isEqualTo(spec);
        assertThat(decoded.dimension(ViewSpec.EDIT_DEFAULT_MODULE))
                .contains("RichSurfaceTerms#RICH_SURFACE_MODULE");
    }

    @Test
    @DisplayName("Unknown dimension keys fail fast — a typo never becomes a silent default")
    void unknownKeyRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ViewSpec.of(Map.of("stamp.allowedstates", "Active")))
                .withMessageContaining("Unknown view dimension key");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ViewSpec.empty().withDimension("stamp.preset", "development-latest"))
                .withMessageContaining("stamp.preset");
    }

    @Test
    @DisplayName("Indexed fallback languages accept n >= 2 with known fields only")
    void indexedLanguageKeys() {
        assertThat(ViewSpec.of(Map.of("language.2.dialects", "#GB_DIALECT_PATTERN"))
                .dimension("language.2.dialects")).contains("#GB_DIALECT_PATTERN");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ViewSpec.of(Map.of("language.1.dialects", "#GB_DIALECT_PATTERN")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ViewSpec.of(Map.of("language.2.bogus", "value")));
    }

    @Test
    @DisplayName("Blank values are rejected; merge lets overrides win without mutating either spec")
    void valuesAndMerge() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ViewSpec.of(Map.of(ViewSpec.STAMP_POSITION_TIME, " ")))
                .withMessageContaining("requires a value");

        ViewSpec base = ViewSpec.of(Map.of(
                ViewSpec.STAMP_ALLOWED_STATES, "Active, Inactive",
                ViewSpec.LOGIC_CLASSIFIER, "#EXAMPLE_CLASSIFIER"));
        ViewSpec overrides = ViewSpec.of(Map.of(ViewSpec.STAMP_ALLOWED_STATES, "Active"));
        ViewSpec merged = base.merge(overrides);
        assertThat(merged.dimension(ViewSpec.STAMP_ALLOWED_STATES)).contains("Active");
        assertThat(merged.dimension(ViewSpec.LOGIC_CLASSIFIER)).contains("#EXAMPLE_CLASSIFIER");
        assertThat(base.dimension(ViewSpec.STAMP_ALLOWED_STATES)).contains("Active, Inactive");
        assertThat(ViewSpec.empty().merge(ViewSpec.empty()).dimensions()).isEmpty();
    }
}
