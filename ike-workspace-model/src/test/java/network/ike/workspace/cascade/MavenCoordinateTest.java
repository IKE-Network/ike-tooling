package network.ike.workspace.cascade;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link MavenCoordinate} — the (groupId, artifactId)
 * value type that replaces the two-String idiom across the cascade
 * model.
 */
class MavenCoordinateTest {

    @Test
    void canonical_constructor_validates_both_components() {
        MavenCoordinate c = new MavenCoordinate(
                "network.ike.tooling", "ike-tooling");
        assertThat(c.groupId()).isEqualTo("network.ike.tooling");
        assertThat(c.artifactId()).isEqualTo("ike-tooling");
    }

    @Test
    void null_or_blank_groupId_is_rejected() {
        assertThatThrownBy(
                () -> new MavenCoordinate(null, "ike-tooling"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                () -> new MavenCoordinate(" ", "ike-tooling"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void null_or_blank_artifactId_is_rejected() {
        assertThatThrownBy(
                () -> new MavenCoordinate("network.ike.tooling", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                () -> new MavenCoordinate("network.ike.tooling", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void of_factory_is_equivalent_to_constructor() {
        assertThat(MavenCoordinate.of(
                "network.ike.tooling", "ike-tooling"))
                .isEqualTo(new MavenCoordinate(
                        "network.ike.tooling", "ike-tooling"));
    }

    @Test
    void tryOf_returns_present_for_valid_inputs() {
        assertThat(MavenCoordinate.tryOf(
                "network.ike.tooling", "ike-tooling"))
                .contains(new MavenCoordinate(
                        "network.ike.tooling", "ike-tooling"));
    }

    @Test
    void tryOf_returns_empty_when_either_component_missing() {
        assertThat(MavenCoordinate.tryOf(null, "ike-tooling")).isEmpty();
        assertThat(MavenCoordinate.tryOf("", "ike-tooling")).isEmpty();
        assertThat(MavenCoordinate.tryOf(" ", "ike-tooling")).isEmpty();
        assertThat(MavenCoordinate.tryOf(
                "network.ike.tooling", null)).isEmpty();
        assertThat(MavenCoordinate.tryOf(
                "network.ike.tooling", " ")).isEmpty();
    }

    @Test
    void parse_splits_on_first_colon() {
        assertThat(MavenCoordinate.parse(
                "network.ike.tooling:ike-tooling"))
                .isEqualTo(new MavenCoordinate(
                        "network.ike.tooling", "ike-tooling"));
    }

    @Test
    void parse_rejects_strings_without_a_colon() {
        assertThatThrownBy(() -> MavenCoordinate.parse("no-colon-here"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MavenCoordinate.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ga_returns_the_display_form() {
        assertThat(new MavenCoordinate(
                "network.ike.tooling", "ike-tooling").ga())
                .isEqualTo("network.ike.tooling:ike-tooling");
    }

    @Test
    void versionProperty_uses_middle_dot() {
        assertThat(new MavenCoordinate(
                "network.ike.tooling", "ike-tooling").versionProperty())
                .isEqualTo("network.ike.tooling·ike-tooling");
    }

    @Test
    void toString_equals_ga() {
        MavenCoordinate c = new MavenCoordinate(
                "network.ike.tooling", "ike-tooling");
        assertThat(c.toString()).isEqualTo(c.ga());
    }

    @Test
    void equals_and_hashCode_are_value_based() {
        MavenCoordinate a = new MavenCoordinate(
                "network.ike.tooling", "ike-tooling");
        MavenCoordinate b = MavenCoordinate.parse(
                "network.ike.tooling:ike-tooling");
        MavenCoordinate c = new MavenCoordinate(
                "network.ike.tooling", "ike-docs");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).isNotEqualTo(c);
    }
}
