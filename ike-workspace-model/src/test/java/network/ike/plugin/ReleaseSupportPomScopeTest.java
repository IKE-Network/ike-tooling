package network.ike.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ReleaseSupport#isProjectPom} — which POMs a release is
 * allowed to rewrite.
 *
 * <p>Motivating incident (IKE-Network/ike-issues#1014): an orphaned agent
 * worktree under {@code .claude/worktrees/} held a complete copy of the
 * reactor. The release version-set its POMs along with the real ones, then
 * staged them, and {@code git add} refused — <em>"The following paths are
 * ignored by one of your .gitignore files"</em> — after the release branch
 * had already been cut and the build had run.
 */
class ReleaseSupportPomScopeTest {

    @Test
    @DisplayName("the reactor's own POMs are in scope")
    void projectPomsAreIncluded() {
        assertThat(ReleaseSupport.isProjectPom(Path.of("pom.xml"))).isTrue();
        assertThat(ReleaseSupport.isProjectPom(
                Path.of("ike-maven-plugin/pom.xml"))).isTrue();
        assertThat(ReleaseSupport.isProjectPom(
                Path.of("ike-build-standards/src/main/scaffold/starter-set/kb/pom.xml")))
                .isTrue();
    }

    @Test
    @DisplayName("an orphaned agent worktree is out of scope")
    void agentWorktreePomsAreExcluded() {
        assertThat(ReleaseSupport.isProjectPom(
                Path.of(".claude/worktrees/amazing-curran-20c837/pom.xml")))
                .as("this exact path failed a release")
                .isFalse();
        assertThat(ReleaseSupport.isProjectPom(
                Path.of(".claude/worktrees/amazing-curran-20c837/"
                        + "ike-maven-plugin/pom.xml")))
                .isFalse();
    }

    @Test
    @DisplayName("any dot-directory is out of scope, not just the one that broke us")
    void dotDirectoriesAreExcluded() {
        assertThat(ReleaseSupport.isProjectPom(Path.of(".mvn/pom.xml"))).isFalse();
        assertThat(ReleaseSupport.isProjectPom(Path.of(".idea/x/pom.xml"))).isFalse();
        assertThat(ReleaseSupport.isProjectPom(
                Path.of("module/.hidden/pom.xml")))
                .as("a dot-directory anywhere in the path, not only at the root")
                .isFalse();
    }

    @Test
    @DisplayName("build output is out of scope at any depth")
    void targetDirectoriesAreExcluded() {
        assertThat(ReleaseSupport.isProjectPom(Path.of("target/pom.xml"))).isFalse();
        assertThat(ReleaseSupport.isProjectPom(
                Path.of("module/target/classes/pom.xml"))).isFalse();
    }

    @Test
    @DisplayName("a directory merely containing 'target' in its name stays in scope")
    void substringMatchDoesNotOverreach() {
        assertThat(ReleaseSupport.isProjectPom(Path.of("mytarget/pom.xml")))
                .as("the old rule matched the substring \"target/\" and would "
                        + "have silently skipped this module's POM")
                .isTrue();
        assertThat(ReleaseSupport.isProjectPom(Path.of("targeting/pom.xml")))
                .isTrue();
    }

    @Test
    @DisplayName("a file literally named pom.xml at the root is judged on its own path")
    void rootPomHasNoParentSegments() {
        assertThat(ReleaseSupport.isProjectPom(Path.of("pom.xml")))
                .as("no parent segments to reject; the root POM is always the "
                        + "one being released")
                .isTrue();
    }
}
