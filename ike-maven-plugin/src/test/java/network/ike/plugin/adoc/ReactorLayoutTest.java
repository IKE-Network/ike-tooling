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
}
