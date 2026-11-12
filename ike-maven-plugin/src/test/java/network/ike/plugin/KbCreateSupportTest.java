/*
 * Copyright © 2026 IKE Network (support@ike.network)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package network.ike.plugin;

import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The knowledge-base genesis mechanics against the real scaffold of ike-build-standards in
 * this reactor: its templates, its foundation parent, and its project scope applied to a
 * new folder.
 */
class KbCreateSupportTest {

    @Test
    @DisplayName("The kb templates are read by their path within kb/")
    void readsTemplates() {
        Map<String, String> templates = KbCreateSupport.templates(KbCreatePlanTest.SCAFFOLD);
        assertThat(templates).containsKeys(KbCreatePlan.POM_TEMPLATE, "files/README.adoc", "files/rkb.xml", "files/gitkeep",
                "files/jvm.config", "fragments/property.xml", "fragments/dependency.xml", "fragments/input.xml",
                "fragments/assembly-plugins.xml", "fragments/source-layer-plugins.xml",
                "fragments/rocks-execution.xml", "fragments/rocks-assembly-plugin.xml", "fragments/komet-profile.xml");
    }

    @Test
    @DisplayName("Every pom.xml a release reads in this repository is a Maven model")
    void everyPomTheReleaseReadsParses() {
        // ike:release-publish reads every pom.xml outside target and dot-directories as a Maven
        // model before it tags. A template named pom.xml with a token where XML allows none
        // stopped the release of 280 at that step (IKE-Network/ike-issues#1370).
        List<File> poms = ReleaseSupport.findPomFiles(Path.of("..").toAbsolutePath().normalize().toFile());

        assertThat(poms).as("the repository's own POMs and the scaffold's templates named pom.xml")
                .extracting(File::getName).isNotEmpty().containsOnly("pom.xml");
        assertThat(poms).as("the knowledge-base POM template is not one of them: it is no model until rendered")
                .noneMatch(pom -> pom.toPath().endsWith(Path.of("scaffold", "kb", "pom.xml")));
        assertThatCode(() -> SnapshotScanner.scanForSnapshotVersions(poms)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A scaffold without kb/ templates is refused with the version hint")
    void refusesScaffoldWithoutTemplates(@TempDir Path temp) {
        assertThatThrownBy(() -> KbCreateSupport.templates(temp))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("versions must match");
    }

    @Test
    @DisplayName("The default parent is the scaffold's tested foundation parent")
    void foundationParent() {
        assertThat(KbCreateSupport.foundationParentVersion(KbCreatePlanTest.SCAFFOLD)).matches("\\d+");
    }

    @Test
    @DisplayName("The project scope lays down the wrapper, .mvn, .gitignore, CLAUDE.md, README.md and the lockfile,"
            + " and nothing in the user's home")
    void appliesProjectScope(@TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("my-kb"));
        Path home = Files.createDirectories(temp.resolve("home"));
        Files.writeString(project.resolve("pom.xml"), "<project/>\n");

        List<String> written = KbCreateSupport.applyProjectScaffold(KbCreatePlanTest.SCAFFOLD, project, home);

        assertThat(written).contains("mvnw", "mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties",
                ".mvn/extensions.xml", ".mvn/maven.config", ".gitignore", "CLAUDE.md", "README.md",
                ".ike/scaffold.lock");
        assertThat(Files.isExecutable(project.resolve("mvnw"))).isTrue();
        assertThat(Files.readString(project.resolve(".mvn/wrapper/maven-wrapper.properties"))).contains("apache-maven-4");
        assertThat(Files.readString(project.resolve(".ike/scaffold.lock"))).contains("mvnw");
        assertThat(home).isEmptyDirectory();
    }

    @Test
    @DisplayName("Files are written under the folder; a folder holding a project is refused")
    void writesAndRefuses(@TempDir Path temp) throws Exception {
        Path project = temp.resolve("my-kb");
        Files.createDirectories(project);
        Files.writeString(project.resolve("mvnw"), "#!/bin/sh\n");
        KbCreatePublishMojo.refuseExisting(project);

        List<String> written = KbCreatePublishMojo.write(project, Map.of(
                "pom.xml", "<project/>\n", "src/assembly/rkb.xml", "<assembly/>\n"));

        assertThat(written).containsExactlyInAnyOrder("pom.xml", "src/assembly/rkb.xml");
        assertThat(project.resolve("src/assembly/rkb.xml")).hasContent("<assembly/>");
        assertThatThrownBy(() -> KbCreatePublishMojo.refuseExisting(project))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("runs once");
        Path withGit = Files.createDirectories(temp.resolve("other-kb/.git")).getParent();
        assertThatThrownBy(() -> KbCreatePublishMojo.refuseExisting(withGit)).isInstanceOf(MojoException.class);
    }
}
