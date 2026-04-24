package network.ike.plugin.scaffold;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PathResolverTest {

    private final Path userHome = Paths.get("/home/kec");
    private final Path projectRoot = Paths.get("/work/proj");

    private static ManifestEntry userEntry(String dest) {
        return new ManifestEntry(
                dest, ScaffoldScope.USER,
                ScaffoldTier.TRACKED, dest, null, Map.of());
    }

    private static ManifestEntry projectEntry(String dest) {
        return new ManifestEntry(
                dest, ScaffoldScope.PROJECT,
                ScaffoldTier.TRACKED, dest, null, Map.of());
    }

    @Test
    void userScopeResolvesTildePrefix() {
        PathResolver r = new PathResolver(userHome, projectRoot);
        Path p = r.resolve(userEntry("~/.m2/settings.xml"));
        assertThat(p).isEqualTo(
                Paths.get("/home/kec/.m2/settings.xml"));
    }

    @Test
    void userScopeRejectsMissingTilde() {
        PathResolver r = new PathResolver(userHome, projectRoot);
        assertThatThrownBy(() ->
                r.resolve(userEntry(".m2/settings.xml")))
                .isInstanceOf(ScaffoldException.class)
                .hasMessageContaining("must start with '~/'");
    }

    @Test
    void projectScopeRejectsTilde() {
        PathResolver r = new PathResolver(userHome, projectRoot);
        assertThatThrownBy(() ->
                r.resolve(projectEntry("~/mvnw")))
                .isInstanceOf(ScaffoldException.class)
                .hasMessageContaining("must not start with '~/'");
    }

    @Test
    void projectScopeResolvesRelative() {
        PathResolver r = new PathResolver(userHome, projectRoot);
        Path p = r.resolve(projectEntry(".mvn/maven.config"));
        assertThat(p).isEqualTo(
                Paths.get("/work/proj/.mvn/maven.config"));
    }

    @Test
    void projectScopeStripsProjectRootPlaceholder() {
        PathResolver r = new PathResolver(userHome, projectRoot);
        Path p = r.resolve(projectEntry("{project.root}/mvnw"));
        assertThat(p).isEqualTo(Paths.get("/work/proj/mvnw"));
    }

    @Test
    void projectScopeRequiresProjectRoot() {
        PathResolver r = new PathResolver(userHome, null);
        assertThatThrownBy(() ->
                r.resolve(projectEntry("mvnw")))
                .isInstanceOf(ScaffoldException.class)
                .hasMessageContaining("projectRoot");
    }

    @Test
    void userScopeWorksWithoutProjectRoot() {
        PathResolver r = new PathResolver(userHome, null);
        Path p = r.resolve(userEntry("~/.gitconfig"));
        assertThat(p).isEqualTo(Paths.get("/home/kec/.gitconfig"));
    }

    @Test
    void nullUserHomeRejected() {
        assertThatThrownBy(() ->
                new PathResolver(null, projectRoot))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("userHome");
    }

    @Test
    void paths_are_normalised() {
        PathResolver r = new PathResolver(userHome, projectRoot);
        Path p = r.resolve(projectEntry("foo/./bar/../baz"));
        assertThat(p).isEqualTo(Paths.get("/work/proj/foo/baz"));
    }
}
