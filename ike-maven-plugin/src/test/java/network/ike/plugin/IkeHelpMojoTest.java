package network.ike.plugin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link IkeHelpMojo}'s console/engine demotion mapping
 * (ike-issues#601): the per-repo release and scaffold goals point to their
 * {@code ws:} console entry point, while the foundation self-release and the
 * build-lifecycle goals do not (they have no {@code ws:} form).
 */
class IkeHelpMojoTest {

    @Test
    void releaseAndScaffold_pointToTheirWsConsoleCommand() {
        assertThat(IkeHelpMojo.consoleEquivalent(IkeGoal.RELEASE_DRAFT))
                .isEqualTo("ws:release");
        assertThat(IkeHelpMojo.consoleEquivalent(IkeGoal.RELEASE_PUBLISH))
                .isEqualTo("ws:release");
        assertThat(IkeHelpMojo.consoleEquivalent(IkeGoal.SCAFFOLD_DRAFT))
                .isEqualTo("ws:scaffold");
        assertThat(IkeHelpMojo.consoleEquivalent(IkeGoal.SCAFFOLD_PUBLISH))
                .isEqualTo("ws:scaffold");
    }

    @Test
    void foundationCascadeAndEngineGoals_haveNoConsoleEquivalent() {
        // The foundation self-release stays an ike: command — the one
        // principled exception (it's downstream-of-ws bootstrap) — and the
        // build-lifecycle goals have no ws: form, so neither is demoted.
        assertThat(IkeHelpMojo.consoleEquivalent(IkeGoal.RELEASE_CASCADE)).isNull();
        assertThat(IkeHelpMojo.consoleEquivalent(IkeGoal.SCAFFOLD_REVERT)).isNull();
        assertThat(IkeHelpMojo.consoleEquivalent(IkeGoal.GENERATE_BOM)).isNull();
        assertThat(IkeHelpMojo.consoleEquivalent(IkeGoal.CODESIGN_PKG)).isNull();
        assertThat(IkeHelpMojo.consoleEquivalent(IkeGoal.SITE_PUBLISH)).isNull();
    }
}
