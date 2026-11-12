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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Giving a knowledge-base project its remote (IKE-Network/ike-issues#1370): the plan on
 * its own, and the goals' mechanics against a real repository with a bare repository
 * standing in for the git host.
 */
class KbRemoteTest {

    private static final String POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.1.0" root="true">
                <modelVersion>4.1.0</modelVersion>
                <groupId>network.ike.data</groupId>
                <artifactId>my-kb</artifactId>
                <version>1-SNAPSHOT</version>

                <!-- The repository's own folder until a remote is added. -->
                <scm>
                    <url>file:///work/my-kb/</url>
                    <connection>scm:git:file:///work/my-kb/</connection>
                    <developerConnection>scm:git:file:///work/my-kb/</developerConnection>
                </scm>
            </project>
            """;

    private static final KbRemotePlan.Probe EMPTY = new KbRemotePlan.Probe(true, "", Optional.empty(), false, 0);

    @Test
    @DisplayName("The scm names the remote as given, and the address a person opens")
    void scmForEachKindOfRemote() {
        assertThat(KbRemotePlan.scmFor("https://github.com/my-org/my-kb.git")).isEqualTo(new KbRemotePlan.Scm(
                "https://github.com/my-org/my-kb", "scm:git:https://github.com/my-org/my-kb.git",
                "scm:git:https://github.com/my-org/my-kb.git"));
        assertThat(KbRemotePlan.scmFor("git@github.com:my-org/my-kb.git").url())
                .as("an SSH remote is opened over HTTPS").isEqualTo("https://github.com/my-org/my-kb");
        assertThat(KbRemotePlan.scmFor("git@github.com:my-org/my-kb.git").developerConnection())
                .isEqualTo("scm:git:git@github.com:my-org/my-kb.git");
        assertThat(KbRemotePlan.scmFor("ssh://git@gitlab.example:2222/team/my-kb.git").url())
                .isEqualTo("https://gitlab.example/team/my-kb");
        assertThat(KbRemotePlan.scmFor("file:///srv/git/my-kb.git").url()).as("a local remote is its own address")
                .isEqualTo("file:///srv/git/my-kb.git");
    }

    @Test
    @DisplayName("The plan replaces the three scm URLs and keeps the rest of the POM as it was")
    void planRewritesScm() {
        KbRemotePlan plan = plan(POM, "https://github.com/my-org/my-kb.git", Optional.empty(), false, EMPTY, true);

        assertThat(plan.pomChanges()).isTrue();
        assertThat(plan.addOrigin()).isTrue();
        assertThat(plan.pushProblem()).isEmpty();
        assertThat(plan.newPom())
                .contains("<url>https://github.com/my-org/my-kb</url>",
                        "<connection>scm:git:https://github.com/my-org/my-kb.git</connection>",
                        "<developerConnection>scm:git:https://github.com/my-org/my-kb.git</developerConnection>",
                        "<!-- The repository's own folder until a remote is added. -->", "root=\"true\"")
                .doesNotContain("file:///work/my-kb/");
        assertThat(plan.report()).contains(
                "Add remote: my-kb",
                "  Remote:       https://github.com/my-org/my-kb.git  (empty)",
                "  origin:       added",
                "  pom.xml scm:  url                  file:///work/my-kb/ → https://github.com/my-org/my-kb",
                "  Commit:       Point my-kb at its remote (pom.xml alone)",
                "  Push:         branch main to origin, which it then tracks");
    }

    @Test
    @DisplayName("What cannot be done is refused before anything is changed")
    void refusals() {
        assertThatThrownBy(() -> plan(POM, " ", Optional.empty(), false, EMPTY, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("-Dremote=<git URL>");
        assertThatThrownBy(() -> plan(POM, "my-kb on github", Optional.empty(), false, EMPTY, true))
                .hasMessageContaining("is not a git URL");
        assertThatThrownBy(() -> plan(POM, "https://github.com/a/b.git", Optional.of("https://github.com/x/y.git"), false, EMPTY, true))
                .hasMessageContaining("origin is already https://github.com/x/y.git");
        assertThatThrownBy(() -> plan(POM, "https://github.com/a/b.git", Optional.empty(), true, EMPTY, true))
                .hasMessageContaining("pom.xml has uncommitted changes");
        assertThatThrownBy(() -> plan(POM.replace("file:///work/my-kb/", "https://github.com/x/y"),
                "https://github.com/a/b.git", Optional.empty(), false, EMPTY, true))
                .hasMessageContaining("pom.xml already names a remote");
        assertThatThrownBy(() -> plan("<project><artifactId>my-kb</artifactId></project>", "https://github.com/a/b.git",
                Optional.empty(), false, EMPTY, true)).hasMessageContaining("has no <scm>");
    }

    @Test
    @DisplayName("A push that would fail is known from what the remote answered")
    void pushProblems() {
        KbRemotePlan.Probe silent = new KbRemotePlan.Probe(false, "fatal: repository not found", Optional.empty(), false, 0);
        KbRemotePlan.Probe others = new KbRemotePlan.Probe(true, "", Optional.of("abc1234"), false, 1);
        KbRemotePlan.Probe own = new KbRemotePlan.Probe(true, "", Optional.of("abc1234"), true, 1);
        String remote = "https://github.com/my-org/my-kb.git";

        assertThat(plan(POM, remote, Optional.empty(), false, silent, true).pushProblem()).hasValueSatisfying(problem ->
                assertThat(problem).startsWith("The remote could not be read: fatal: repository not found.")
                        .contains("Create the repository on the git host first, empty"));
        assertThat(plan(POM, remote, Optional.empty(), false, others, true).pushProblem()).hasValueSatisfying(problem ->
                assertThat(problem).startsWith("The remote already has a branch main with commits this project does not have."));
        assertThat(plan(POM, remote, Optional.empty(), false, own, true).pushProblem())
                .as("a branch the project already contains is its own").isEmpty();
        assertThat(plan(POM, remote, Optional.empty(), false, silent, false).pushProblem())
                .as("with the push skipped the remote is not asked").isEmpty();
        assertThat(plan(POM, remote, Optional.empty(), false, silent, false).report())
                .contains("  Push:         skipped (-DskipPush); push it with git push -u origin main");
    }

    @Test
    @DisplayName("Publish sets origin, commits the POM alone, and pushes; a second run changes nothing")
    void publishAgainstARealRemote(@TempDir Path temp) throws IOException {
        Path project = project(temp.resolve("my-kb"));
        Path host = temp.resolve("host/my-kb.git");
        Files.createDirectories(host);
        git(host, "init", "-q", "--bare", "-b", "main");
        String remote = host.toUri().toString().replaceFirst("^file:/(?!/)", "file:///");
        Files.writeString(project.resolve("notes.txt"), "not committed, and not this goal's to commit\n");

        KbRemotePlan draft = KbRemoteSupport.plan(project, remote, true);
        assertThat(draft.report()).contains("  Remote:       " + remote + "  (empty)");
        assertThat(git(project, "status", "--porcelain").output()).as("a draft changes nothing").isEqualTo("?? notes.txt\n");
        assertThat(git(project, "remote").output()).isBlank();

        KbRemoteSupport.apply(new TestLog(), KbRemoteSupport.plan(project, remote, true));

        assertThat(git(project, "remote", "get-url", "origin").output().strip()).isEqualTo(remote);
        assertThat(Files.readString(project.resolve("pom.xml"))).contains("<connection>scm:git:" + remote + "</connection>")
                .doesNotContain("file:///work/my-kb/");
        assertThat(git(project, "log", "--format=%s").output().lines()).as("one commit for the POM, after the genesis")
                .containsExactly("Point my-kb at its remote", "Genesis");
        assertThat(git(project, "show", "--stat", "--format=", "HEAD").output()).contains("pom.xml").doesNotContain("notes.txt");
        assertThat(git(project, "status", "--porcelain").output()).as("what was uncommitted still is").isEqualTo("?? notes.txt\n");
        assertThat(git(host, "rev-parse", "refs/heads/main").output().strip()).as("the remote has the project's branch")
                .isEqualTo(git(project, "rev-parse", "HEAD").output().strip());
        assertThat(git(project, "rev-parse", "--abbrev-ref", "main@{upstream}").output().strip()).isEqualTo("origin/main");

        KbRemotePlan again = KbRemoteSupport.plan(project, remote, true);
        assertThat(again.pomChanges()).isFalse();
        assertThat(again.addOrigin()).isFalse();
        assertThat(again.pushProblem()).as("the remote's branch is the project's own").isEmpty();
        assertThat(again.report()).contains("  origin:       already set", "  pom.xml scm:  already names the remote");
        KbRemoteSupport.apply(new TestLog(), again);
        assertThat(git(project, "log", "--format=%s").output().lines()).hasSize(2);
    }

    @Test
    @DisplayName("A remote that does not answer, or holds another history, is refused with nothing changed")
    void refusesAPushThatWouldFail(@TempDir Path temp) throws IOException {
        Path project = project(temp.resolve("my-kb"));
        String absent = temp.resolve("host/absent.git").toUri().toString().replaceFirst("^file:/(?!/)", "file:///");
        Path other = project(temp.resolve("other-kb"));
        // Its own commit, so its history is not this project's even when both were made in the same second.
        Files.writeString(other.resolve("other.txt"), "another project's work\n");
        git(other, "add", "other.txt");
        git(other, "commit", "-q", "-m", "Other work");
        String taken = other.toUri().toString().replaceFirst("^file:/(?!/)", "file:///");
        String before = git(project, "rev-parse", "HEAD").output();

        assertThatThrownBy(() -> KbRemoteSupport.apply(new TestLog(), KbRemoteSupport.plan(project, absent, true)))
                .isInstanceOf(MojoException.class).hasMessageContaining("The remote could not be read: ")
                .hasMessageContaining("does not appear to be a git repository. Create the repository on the git host first")
                .hasMessageContaining("Nothing was changed.");
        assertThatThrownBy(() -> KbRemoteSupport.apply(new TestLog(), KbRemoteSupport.plan(project, taken, true)))
                .isInstanceOf(MojoException.class).hasMessageContaining("already has a branch main with commits this project does not have");

        assertThat(git(project, "remote").output()).isBlank();
        assertThat(git(project, "rev-parse", "HEAD").output()).isEqualTo(before);
        assertThat(Files.readString(project.resolve("pom.xml"))).isEqualTo(POM);

        KbRemoteSupport.apply(new TestLog(), KbRemoteSupport.plan(project, absent, false));
        assertThat(git(project, "remote", "get-url", "origin").output().strip()).as("with the push skipped the remote is set anyway")
                .isEqualTo(absent);
        assertThat(git(project, "log", "--format=%s").output().lines()).first().isEqualTo("Point my-kb at its remote");
    }

    @Test
    @DisplayName("A member of a working set has its repo: in workspace.yaml replaced and committed there")
    void workingSetMember(@TempDir Path temp) throws IOException {
        Path workingSet = Files.createDirectories(temp.resolve("my-ws"));
        Files.writeString(workingSet.resolve("workspace.yaml"), """
                subprojects:
                  other-kb:
                    repo: https://github.com/my-org/other-kb.git
                  my-kb:
                    repo: file:///work/my-ws/my-kb
                    branch: main
                """);
        Files.writeString(workingSet.resolve(".gitignore"), "/my-kb/\n");
        init(workingSet);
        Path project = project(workingSet.resolve("my-kb"));
        Path host = Files.createDirectories(temp.resolve("host/my-kb.git"));
        git(host, "init", "-q", "--bare", "-b", "main");
        String remote = host.toUri().toString().replaceFirst("^file:/(?!/)", "file:///");

        KbRemotePlan plan = KbRemoteSupport.plan(project, remote, true);
        assertThat(plan.report()).anyMatch(line -> line.startsWith("  Working set:  repo: of my-kb in ") && line.endsWith("→ " + remote + ", committed there"));
        KbRemoteSupport.apply(new TestLog(), plan);

        assertThat(Files.readString(workingSet.resolve("workspace.yaml")))
                .contains("    repo: " + remote + "\n    branch: main", "    repo: https://github.com/my-org/other-kb.git");
        assertThat(git(workingSet, "log", "--format=%s").output().lines()).first().isEqualTo("Point my-kb at its remote");
        assertThat(git(workingSet, "status", "--porcelain").output()).isBlank();
    }

    @Test
    @DisplayName("git's reason is its first fatal or error line, not the advice after it")
    void gitsReason() {
        assertThat(new KbRemoteSupport.Result(128, """
                fatal: '/srv/git/absent.git' does not appear to be a git repository
                fatal: Could not read from remote repository.

                Please make sure you have the correct access rights
                and the repository exists.
                """).reason()).isEqualTo("'/srv/git/absent.git' does not appear to be a git repository");
        assertThat(new KbRemoteSupport.Result(1, "To host\n ! [rejected] main -> main (fetch first)\n"
                + "error: failed to push some refs to 'host'\nhint: Updates were rejected\n").reason())
                .isEqualTo("failed to push some refs to 'host'");
        assertThat(new KbRemoteSupport.Result(1, "something else went wrong.\n").reason()).isEqualTo("something else went wrong");
        assertThat(new KbRemoteSupport.Result(-1, "").reason()).isEmpty();
    }

    private static KbRemotePlan plan(String pom, String remote, Optional<String> origin, boolean pomUncommitted,
                                     KbRemotePlan.Probe probe, boolean push) {
        return new KbRemotePlan(new KbRemotePlan.Inputs(Path.of("/work/my-kb"), pom, remote, origin, "main", pomUncommitted,
                probe, Optional.empty(), Optional.empty(), push));
    }

    /** A project as ike:kb-create-publish leaves it: a POM with a file: scm, one commit, no remote. */
    private static Path project(Path folder) throws IOException {
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("pom.xml"), POM);
        init(folder);
        return folder;
    }

    private static void init(Path folder) {
        git(folder, "init", "-q", "-b", "main");
        git(folder, "config", "user.name", "Test Author");
        git(folder, "config", "user.email", "test@example.invalid");
        git(folder, "config", "commit.gpgsign", "false");
        git(folder, "add", "-A");
        git(folder, "commit", "-q", "-m", "Genesis");
    }

    private static KbRemoteSupport.Result git(Path folder, String... args) {
        KbRemoteSupport.Result result = KbRemoteSupport.git(folder, 60, args);
        assertThat(result.exit()).as("git %s: %s", String.join(" ", args), result.output()).isIn(0, 1, 128);
        return result;
    }
}
