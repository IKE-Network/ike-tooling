package network.ike.plugin.adoc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** How a sibling module not in a {@code -pl} build is found under the reactor root by its pom. */
class ReactorLayoutTest {

    @TempDir
    Path root;

    @Test
    void aModuleIsFoundByItsOwnOrInheritedGroupAndItsArtifact() throws Exception {
        AdocFixtures.write(root.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.1.0" root="true">
                  <modelVersion>4.1.0</modelVersion>
                  <groupId>network.ike.lab</groupId>
                  <artifactId>lab-documents</artifactId>
                  <version>1-SNAPSHOT</version>
                  <packaging>pom</packaging>
                </project>
                """);
        AdocFixtures.write(root.resolve("topics/pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.1.0">
                  <modelVersion>4.1.0</modelVersion>
                  <parent>
                    <groupId>network.ike.lab</groupId>
                    <artifactId>lab-documents</artifactId>
                  </parent>
                  <artifactId>topics</artifactId>
                  <packaging>pom</packaging>
                </project>
                """);
        AdocFixtures.write(root.resolve("nested/proposals/pom.xml"), """
                <project>
                  <groupId>network.ike.lab</groupId>
                  <artifactId>unlv-topics</artifactId>
                  <version>3</version>
                </project>
                """);
        AdocFixtures.write(root.resolve("topics/target/classes/pom.xml"), "<project><artifactId>stale</artifactId></project>");
        AdocFixtures.write(root.resolve("other/pom.xml"), "<project><groupId>com.example</groupId><artifactId>topics</artifactId></project>");
        AdocFixtures.write(root.resolve("broken/pom.xml"), "<project><artifactId>");

        assertThat(ReactorLayout.memberBasedir(root, "network.ike.lab", "topics")).contains(root.resolve("topics"));
        assertThat(ReactorLayout.memberBasedir(root, "network.ike.lab", "unlv-topics")).contains(root.resolve("nested/proposals"));
        assertThat(ReactorLayout.memberBasedir(root, "network.ike.lab", "stale")).isEmpty();
        assertThat(ReactorLayout.memberBasedir(root, "network.ike.lab", "absent")).isEmpty();
        assertThat(ReactorLayout.memberBasedir(root.resolve("nowhere"), "network.ike.lab", "topics")).isEmpty();
    }

    /**
     * In a parallel build another module's clean deletes its target while the search runs.
     * A directory the walk cannot enter stands in for one that vanished: the search never
     * enters a target, and skips any other directory it cannot read.
     */
    @Test
    void aTargetIsNeverEnteredAndAnUnreadableDirectoryIsPassedOver() throws Exception {
        AdocFixtures.write(root.resolve("pom.xml"), "<project><groupId>network.ike.lab</groupId><artifactId>root</artifactId></project>");
        AdocFixtures.write(root.resolve("assembly/target/bom/pom.xml"), "<project><artifactId>stale</artifactId></project>");
        AdocFixtures.write(root.resolve("locked/inner/pom.xml"), "<project><artifactId>hidden</artifactId></project>");
        AdocFixtures.write(root.resolve("library/pom.xml"), "<project><groupId>network.ike.lab</groupId><artifactId>library</artifactId></project>");
        Path target = root.resolve("assembly/target");
        Path locked = root.resolve("locked");
        assertThat(target.toFile().setReadable(false) && target.toFile().setExecutable(false)).isTrue();
        assertThat(locked.toFile().setReadable(false) && locked.toFile().setExecutable(false)).isTrue();
        try {
            assertThat(ReactorLayout.memberBasedir(root, "network.ike.lab", "library")).contains(root.resolve("library"));
            assertThat(ReactorLayout.memberBasedir(root, "network.ike.lab", "absent")).isEmpty();
        } finally {
            target.toFile().setReadable(true);
            target.toFile().setExecutable(true);
            locked.toFile().setReadable(true);
            locked.toFile().setExecutable(true);
        }
    }
}
