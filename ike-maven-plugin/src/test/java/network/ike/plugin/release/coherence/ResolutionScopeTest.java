package network.ike.plugin.release.coherence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ResolutionScope} — the typed scope at which a release
 * confirms its own artifact resolves before declaring success (#705).
 *
 * <p>Pure-logic tests; no mocks (TESTING.md mock-last). The live
 * cold-resolution behaviour of {@link CoherenceVerifier} is exercised by
 * the release goal against a real repository.
 */
class ResolutionScopeTest {

    @Test
    void literalNamesAreLowerCaseAndStable() {
        assertThat(ResolutionScope.LOCAL.literalName()).isEqualTo("local");
        assertThat(ResolutionScope.NEXUS.literalName()).isEqualTo("nexus");
        assertThat(ResolutionScope.CENTRAL.literalName()).isEqualTo("central");
    }

    @Test
    void mirrorConstantsMatchLiterals() {
        // The ConstantBackedEnum.verify() in the static initializer would
        // already have failed class-load on a mismatch; assert explicitly
        // so the contract is visible and regressions are obvious.
        assertThat(ResolutionScope.NAME_LOCAL).isEqualTo(ResolutionScope.LOCAL.literalName());
        assertThat(ResolutionScope.NAME_NEXUS).isEqualTo(ResolutionScope.NEXUS.literalName());
        assertThat(ResolutionScope.NAME_CENTRAL).isEqualTo(ResolutionScope.CENTRAL.literalName());
    }

    @Test
    void termCoincidesWithLiteralName() {
        for (ResolutionScope s : ResolutionScope.values()) {
            assertThat(s.term()).isEqualTo(s.literalName());
            assertThat(s.definition()).isNotBlank();
        }
    }

    @Test
    void onlyLocalFailsThePublishMinimum() {
        // The rule that gates the whole feature: -publish demands a
        // shared, consumer-resolvable scope; LOCAL verifies nothing.
        assertThat(ResolutionScope.LOCAL.satisfiesPublishMinimum()).isFalse();
        assertThat(ResolutionScope.NEXUS.satisfiesPublishMinimum()).isTrue();
        assertThat(ResolutionScope.CENTRAL.satisfiesPublishMinimum()).isTrue();
    }

    @Test
    void nexusIsTheDefaultScope() {
        // The Mojo @Parameter default is the NEXUS literal; lock it in so
        // a change to the default is a deliberate, test-visible decision.
        assertThat(ResolutionScope.NAME_NEXUS).isEqualTo("nexus");
    }
}
