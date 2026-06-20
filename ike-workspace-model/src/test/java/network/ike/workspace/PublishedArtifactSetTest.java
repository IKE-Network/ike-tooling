package network.ike.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link PublishedArtifactSet}.
 *
 * <p>Covers artifact scanning and the groupId collision scenario
 * documented in issue #61: when multiple workspace components share
 * a groupId, the tool must distinguish them by artifactId.
 */
class PublishedArtifactSetTest {

    @TempDir Path tempDir;

    @Test
    void scanSingleModule() throws IOException {
        Path comp = tempDir.resolve("my-lib");
        writePom(comp.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>dev.ikm.example</groupId>
                    <artifactId>my-lib</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                </project>
                """);

        Set<PublishedArtifactSet.Artifact> artifacts =
                PublishedArtifactSet.scan(comp);

        assertThat(artifacts).containsExactly(
                new PublishedArtifactSet.Artifact("dev.ikm.example", "my-lib"));
    }

    @Test
    void scanReactorWithModules() throws IOException {
        Path comp = tempDir.resolve("reactor");
        writePom(comp.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>dev.ikm.example</groupId>
                    <artifactId>reactor-parent</artifactId>
                    <version>2.0.0-SNAPSHOT</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>module-a</module>
                        <module>module-b</module>
                    </modules>
                </project>
                """);
        writePom(comp.resolve("module-a/pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>dev.ikm.example</groupId>
                        <artifactId>reactor-parent</artifactId>
                        <version>2.0.0-SNAPSHOT</version>
                    </parent>
                    <artifactId>module-a</artifactId>
                </project>
                """);
        writePom(comp.resolve("module-b/pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>dev.ikm.example</groupId>
                        <artifactId>reactor-parent</artifactId>
                        <version>2.0.0-SNAPSHOT</version>
                    </parent>
                    <artifactId>module-b</artifactId>
                </project>
                """);

        Set<PublishedArtifactSet.Artifact> artifacts =
                PublishedArtifactSet.scan(comp);

        assertThat(artifacts).containsExactlyInAnyOrder(
                new PublishedArtifactSet.Artifact("dev.ikm.example", "reactor-parent"),
                new PublishedArtifactSet.Artifact("dev.ikm.example", "module-a"),
                new PublishedArtifactSet.Artifact("dev.ikm.example", "module-b"));
    }

    /**
     * Regression test for #61: two components sharing a groupId must
     * produce distinct artifact sets that can be used for correct
     * version alignment.
     */
    @Test
    void sharedGroupIdDistinguishedByArtifactId() throws IOException {
        // Component A: dev.ikm.ike:rocks-kb
        Path compA = tempDir.resolve("rocks-kb");
        writePom(compA.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>dev.ikm.ike</groupId>
                    <artifactId>rocks-kb</artifactId>
                    <version>0.1.0-SNAPSHOT</version>
                </project>
                """);

        // Component B: dev.ikm.ike:komet-desktop (different version!)
        Path compB = tempDir.resolve("komet-desktop");
        writePom(compB.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>dev.ikm.ike</groupId>
                    <artifactId>komet-desktop</artifactId>
                    <version>3.0.0-SNAPSHOT</version>
                </project>
                """);

        Set<PublishedArtifactSet.Artifact> artifactsA =
                PublishedArtifactSet.scan(compA);
        Set<PublishedArtifactSet.Artifact> artifactsB =
                PublishedArtifactSet.scan(compB);

        // Each scan returns distinct artifacts despite same groupId
        assertThat(artifactsA).containsExactly(
                new PublishedArtifactSet.Artifact("dev.ikm.ike", "rocks-kb"));
        assertThat(artifactsB).containsExactly(
                new PublishedArtifactSet.Artifact("dev.ikm.ike", "komet-desktop"));

        // matches() distinguishes them
        assertThat(PublishedArtifactSet.matches(artifactsA,
                "dev.ikm.ike", "rocks-kb")).isTrue();
        assertThat(PublishedArtifactSet.matches(artifactsA,
                "dev.ikm.ike", "komet-desktop")).isFalse();
        assertThat(PublishedArtifactSet.matches(artifactsB,
                "dev.ikm.ike", "komet-desktop")).isTrue();
        assertThat(PublishedArtifactSet.matches(artifactsB,
                "dev.ikm.ike", "rocks-kb")).isFalse();
    }

    @Test
    void matchesReturnsFalseForUnknownArtifact() throws IOException {
        Path comp = tempDir.resolve("lib");
        writePom(comp.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>org.example</groupId>
                    <artifactId>lib</artifactId>
                    <version>1.0.0</version>
                </project>
                """);

        Set<PublishedArtifactSet.Artifact> artifacts =
                PublishedArtifactSet.scan(comp);

        assertThat(PublishedArtifactSet.matches(artifacts,
                "org.example", "unknown")).isFalse();
        assertThat(PublishedArtifactSet.matches(artifacts,
                "org.other", "lib")).isFalse();
    }

    /**
     * Regression for #719: a Maven-4.1.0 module that uses a self-closing
     * {@code <parent/>} (inferred parent) and inherits its groupId must be
     * published under the inherited groupId — NOT the groupId of its first
     * dependency, and not the literal {@code ${project.groupId}}. This is the
     * komet/framework shape (first dependency {@code dev.ikm.tinkar.ext.owl})
     * that previously dropped komet's inter-subproject edges.
     */
    @Test
    void moduleInheritsGroupIdUnderSelfClosingParent() throws IOException {
        Path comp = tempDir.resolve("komet");
        writePom(comp.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.1.0">
                    <modelVersion>4.1.0</modelVersion>
                    <parent>
                        <groupId>network.ike.platform</groupId>
                        <artifactId>ike-parent</artifactId>
                    </parent>
                    <groupId>dev.ikm.komet</groupId>
                    <artifactId>komet</artifactId>
                    <subprojects>
                        <subproject>framework</subproject>
                    </subprojects>
                </project>
                """);
        writePom(comp.resolve("framework/pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.1.0">
                    <modelVersion>4.1.0</modelVersion>
                    <parent/>
                    <artifactId>framework</artifactId>
                    <dependencies>
                        <dependency>
                            <groupId>dev.ikm.tinkar.ext.owl</groupId>
                            <artifactId>owl-extension</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>${project.groupId}</groupId>
                            <artifactId>preferences</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);

        Set<PublishedArtifactSet.Artifact> artifacts =
                PublishedArtifactSet.scan(comp);

        assertThat(artifacts).containsExactlyInAnyOrder(
                new PublishedArtifactSet.Artifact("dev.ikm.komet", "komet"),
                new PublishedArtifactSet.Artifact("dev.ikm.komet", "framework"));
        // The first dependency's groupId must NOT leak in as the module's own.
        assertThat(PublishedArtifactSet.matches(artifacts,
                "dev.ikm.tinkar.ext.owl", "framework")).isFalse();
        assertThat(PublishedArtifactSet.matches(artifacts,
                "${project.groupId}", "framework")).isFalse();
    }

    private void writePom(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
