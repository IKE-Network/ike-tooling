package network.ike.support.enums;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link ConstantBackedEnum#verify} and
 * {@link ConstantBackedEnum#index}.
 *
 * <p>Each failure mode has its own small test enum, deliberately
 * mis-constructed. None runs {@code verify} from its own static
 * initializer — the tests call {@code verify}/{@code index} directly so
 * the failure surfaces as an {@code AssertionError} with a clean stack
 * trace rather than an {@code ExceptionInInitializerError}.
 */
class ConstantBackedEnumTest {

    /** Happy path — every constant has a matching mirror. */
    enum Good implements ConstantBackedEnum {
        ALPHA(Good.NAME_ALPHA),
        BETA_TWO(Good.NAME_BETA_TWO);

        public static final String NAME_ALPHA    = "alpha";
        public static final String NAME_BETA_TWO = "beta-two";

        private final String literal;
        Good(String l) { this.literal = l; }
        @Override public String literalName() { return literal; }
    }

    /** A constant with no {@code NAME_*} mirror field. */
    enum MissingMirror implements ConstantBackedEnum {
        ALPHA("alpha");

        private final String literal;
        MissingMirror(String l) { this.literal = l; }
        @Override public String literalName() { return literal; }
    }

    /** Mirror value disagrees with {@link #literalName()}. */
    enum ValueMismatch implements ConstantBackedEnum {
        ALPHA(ValueMismatch.NAME_ALPHA);

        public static final String NAME_ALPHA = "different";

        ValueMismatch(String ignored) { }
        @Override public String literalName() { return "alpha"; }
    }

    /** A {@code NAME_*} field with no matching constant. */
    enum OrphanConstant implements ConstantBackedEnum {
        ALPHA(OrphanConstant.NAME_ALPHA);

        public static final String NAME_ALPHA = "alpha";
        public static final String NAME_GHOST = "ghost";

        private final String literal;
        OrphanConstant(String l) { this.literal = l; }
        @Override public String literalName() { return literal; }
    }

    /** Two constants share a literal name. */
    enum DuplicateLiteral implements ConstantBackedEnum {
        ALPHA(DuplicateLiteral.NAME_ALPHA),
        BETA(DuplicateLiteral.NAME_BETA);

        public static final String NAME_ALPHA = "same";
        public static final String NAME_BETA  = "same";

        private final String literal;
        DuplicateLiteral(String l) { this.literal = l; }
        @Override public String literalName() { return literal; }
    }

    @Test
    void verify_passes_for_a_well_formed_enum() {
        assertThatNoException()
                .isThrownBy(() -> ConstantBackedEnum.verify(Good.class));
    }

    @Test
    void verify_rejects_a_missing_mirror_constant() {
        assertThatThrownBy(() -> ConstantBackedEnum.verify(MissingMirror.class))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Missing")
                .hasMessageContaining("NAME_ALPHA");
    }

    @Test
    void verify_rejects_a_value_mismatch() {
        assertThatThrownBy(() -> ConstantBackedEnum.verify(ValueMismatch.class))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("literalName");
    }

    @Test
    void verify_rejects_an_orphan_mirror_constant() {
        assertThatThrownBy(() -> ConstantBackedEnum.verify(OrphanConstant.class))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Orphan")
                .hasMessageContaining("NAME_GHOST");
    }

    @Test
    void verify_rejects_a_value_failing_the_shape_predicate() {
        assertThatThrownBy(() -> ConstantBackedEnum.verify(
                Good.class, ConstantBackedEnum.defaultPrefix(),
                (e, v) -> false))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("shape check");
    }

    @Test
    void verify_accepts_the_kebab_case_shape_predicate() {
        // The exact predicate the goal enums use: SCREAMING_SNAKE_CASE
        // constant name -> kebab-case literal.
        assertThatNoException().isThrownBy(() -> ConstantBackedEnum.verify(
                Good.class, ConstantBackedEnum.defaultPrefix(),
                (e, v) -> v.equals(e.name()
                        .toLowerCase(Locale.ROOT).replace('_', '-'))));
    }

    @Test
    void index_builds_a_literal_to_constant_lookup() {
        Map<String, Good> index = ConstantBackedEnum.index(Good.class);
        assertThat(index)
                .containsEntry("alpha", Good.ALPHA)
                .containsEntry("beta-two", Good.BETA_TWO)
                .hasSize(2);
    }

    @Test
    void index_rejects_duplicate_literal_names() {
        assertThatThrownBy(() -> ConstantBackedEnum.index(DuplicateLiteral.class))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Duplicate literalName");
    }
}
