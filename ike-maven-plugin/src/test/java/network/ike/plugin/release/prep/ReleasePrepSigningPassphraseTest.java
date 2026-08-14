package network.ike.plugin.release.prep;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ReleasePrep#signingPassphraseWarning} — the preflight
 * check that stops a release before it cuts a tag it cannot finish.
 *
 * <p>Motivating incident (IKE-Network/ike-issues#1013): a release ran its
 * whole local phase — branch, version, build, commit, tag, merge, bump —
 * and then died at {@code gpg:sign} because {@code MAVEN_GPG_PASSPHRASE}
 * was absent. Recovery meant a manual deploy from the tag, and the obvious
 * manual deploy omits {@code -P release,signArtifacts}, so the artifact
 * shipped with no signatures and no sources or javadoc jars. Every one of
 * those consequences follows from a missing environment variable that was
 * knowable before anything happened.
 */
class ReleasePrepSigningPassphraseTest {

    @Test
    @DisplayName("a deploy with no passphrase is warned about, before the tag is cut")
    void deployingWithoutPassphraseWarns() {
        Optional<String> warning =
                ReleasePrep.signingPassphraseWarning(true, null);

        assertThat(warning).isPresent();
        assertThat(warning.get())
                .as("the warning has to name the variable and the fix, or it "
                        + "sends the reader back to the stack trace")
                .contains("MAVEN_GPG_PASSPHRASE")
                .contains("op run");
    }

    @Test
    @DisplayName("a blank passphrase is treated as absent, not as a value")
    void blankPassphraseWarns() {
        assertThat(ReleasePrep.signingPassphraseWarning(true, "   "))
                .as("an empty-string env var is how this actually shows up "
                        + "when an env file is present but unpopulated")
                .isPresent();
        assertThat(ReleasePrep.signingPassphraseWarning(true, ""))
                .isPresent();
    }

    @Test
    @DisplayName("a passphrase present means the release may proceed")
    void passphrasePresentIsSilent() {
        assertThat(ReleasePrep.signingPassphraseWarning(true, "s3cret"))
                .isEmpty();
    }

    @Test
    @DisplayName("no deploy phase needs no passphrase")
    void noDeployNeedsNoPassphrase() {
        assertThat(ReleasePrep.signingPassphraseWarning(false, null))
                .as("nothing signs when nothing deploys; warning here would "
                        + "block local-only releases for no reason")
                .isEmpty();
        assertThat(ReleasePrep.signingPassphraseWarning(false, "s3cret"))
                .isEmpty();
    }
}
