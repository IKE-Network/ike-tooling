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

import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Shared mechanics of the {@code ike:kb-remote-*} goals (IKE-Network/ike-issues#1370):
 * reading the project and its git repository into a {@link KbRemotePlan}, and applying
 * one. git is run by name, as {@code ike:kb-create-publish} runs it, and never asks at a
 * terminal: a remote that wants a password it cannot get from a credential helper fails
 * with git's own message.
 */
final class KbRemoteSupport {

    /** How long git may take to ask the remote for its branches, in seconds. */
    static final int PROBE_SECONDS = 30;

    /** How long a push may take, in seconds. */
    static final int PUSH_SECONDS = 600;

    private KbRemoteSupport() {
    }

    /**
     * How a git command ended.
     *
     * @param exit   its exit code, or {@code -1} when it ran out of time
     * @param output what it wrote, standard error included
     */
    record Result(int exit, String output) {

        /**
         * Whether the command succeeded.
         *
         * @return whether it exited with zero
         */
        boolean ok() {
            return exit == 0;
        }

        /**
         * Why it failed, in git's words: the first line git marks {@code fatal:} or
         * {@code error:}, without the mark, since what follows it is advice; else the last
         * line it wrote. It ends with no full stop, so it can end a sentence.
         *
         * @return the reason, or blank
         */
        String reason() {
            String line = output.lines().map(String::strip)
                    .filter(text -> text.startsWith("fatal: ") || text.startsWith("error: ")).findFirst()
                    .map(text -> text.substring(text.indexOf(' ') + 1))
                    .orElseGet(() -> output.lines().filter(text -> !text.isBlank())
                            .reduce((first, second) -> second).orElse("").strip());
            return line.endsWith(".") ? line.substring(0, line.length() - 1) : line;
        }
    }

    /**
     * Reads a project and its repository, and asks the remote for its branches when the
     * plan is to push.
     *
     * @param root   the project's folder
     * @param remote the remote's URL
     * @param push   whether the branch is pushed once the remote is set
     * @return the plan
     * @throws MojoException            if the folder is no project with its own git
     *                                  repository, or no branch is checked out
     * @throws IllegalArgumentException if the plan breaks a rule; see {@link KbRemotePlan}
     */
    static KbRemotePlan plan(Path root, String remote, boolean push) {
        Path pom = root.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            throw new MojoException(root + " has no pom.xml. Run the goal in the project's folder, or name it"
                    + " with -DprojectRoot.");
        }
        if (!Files.exists(root.resolve(".git"))) {
            throw new MojoException(root + " is not the root of a git repository. A project made by"
                    + " ike:kb-create-publish is one.");
        }
        Result head = git(root, 20, "symbolic-ref", "--short", "-q", "HEAD");
        if (!head.ok() || head.output().isBlank()) {
            throw new MojoException("No branch is checked out in " + root + ". Check one out first.");
        }
        String branch = head.output().strip();
        Result origin = git(root, 20, "remote", "get-url", "origin");
        boolean pomUncommitted = !git(root, 20, "status", "--porcelain", "--", "pom.xml").output().isBlank();
        String url = remote == null ? "" : remote.strip();
        KbRemotePlan.Probe probe = push && !url.isEmpty() ? probe(root, url, branch) : KbRemotePlan.Probe.NOT_ASKED;
        Optional<Path> manifest = Optional.ofNullable(root.getParent()).map(parent -> parent.resolve("workspace.yaml"))
                .filter(Files::isRegularFile);
        try {
            Optional<String> yaml = manifest.isPresent()
                    ? Optional.of(Files.readString(manifest.get(), StandardCharsets.UTF_8)) : Optional.empty();
            return new KbRemotePlan(new KbRemotePlan.Inputs(root, Files.readString(pom, StandardCharsets.UTF_8), url,
                    origin.ok() ? Optional.of(origin.output().strip()) : Optional.empty(), branch, pomUncommitted,
                    probe, manifest, yaml, push));
        } catch (IOException e) {
            throw new MojoException("Cannot read the project in " + root + ": " + e.getMessage(), e);
        }
    }

    /**
     * Asks a remote for its branches.
     *
     * @param root   the project, whose history says whether the remote's branch is its own
     * @param remote the remote's URL
     * @param branch the project's branch
     * @return what the remote answered
     */
    static KbRemotePlan.Probe probe(Path root, String remote, String branch) {
        Result heads = git(root, PROBE_SECONDS, "ls-remote", "--heads", remote);
        if (!heads.ok()) {
            return new KbRemotePlan.Probe(false, heads.exit() < 0 ? "it did not answer in " + PROBE_SECONDS + " seconds"
                    : heads.reason(), Optional.empty(), false, 0);
        }
        List<String> lines = heads.output().lines().filter(line -> line.contains("refs/heads/")).toList();
        Optional<String> branchHead = lines.stream().filter(line -> line.endsWith("refs/heads/" + branch))
                .map(line -> line.split("\\s+")[0]).findFirst();
        boolean contained = branchHead.isPresent()
                && git(root, 20, "merge-base", "--is-ancestor", branchHead.get(), "HEAD").ok();
        return new KbRemotePlan.Probe(true, "", branchHead, contained, lines.size());
    }

    /**
     * Applies a plan: sets {@code origin}, rewrites and commits the POM, rewrites and
     * commits the working set's manifest, and pushes. A push that would fail is refused
     * before anything is changed.
     *
     * @param log  where progress goes
     * @param plan the plan
     * @throws MojoException if the push would fail, a file cannot be written, or git fails;
     *                       when the push itself fails, the remote is set and the POM
     *                       committed, and the goal can be run again to push
     */
    static void apply(Log log, KbRemotePlan plan) {
        KbRemotePlan.Inputs in = plan.inputs();
        Path root = in.root();
        plan.pushProblem().ifPresent(problem -> {
            throw new MojoException(problem + " Nothing was changed.");
        });
        try {
            if (plan.addOrigin()) {
                must(git(root, 20, "remote", "add", "origin", plan.remote()), "git remote add origin");
                log.info("origin is " + plan.remote());
            }
            if (plan.pomChanges()) {
                Files.writeString(root.resolve("pom.xml"), plan.newPom(), StandardCharsets.UTF_8);
                must(git(root, 60, "commit", "-q", "--only", "pom.xml", "-m", plan.commitMessage()), "git commit pom.xml");
                log.info("pom.xml names the remote, committed");
            }
            if (plan.newManifest().isPresent() && in.manifest().isPresent()) {
                Path manifest = in.manifest().get();
                Files.writeString(manifest, plan.newManifest().get(), StandardCharsets.UTF_8);
                Path workingSet = manifest.getParent();
                if (Files.exists(workingSet.resolve(".git"))) {
                    must(git(workingSet, 60, "commit", "-q", "--only", "workspace.yaml", "-m",
                            "Point " + root.getFileName() + " at its remote\n\nGenerated by ike:kb-remote-publish:"
                                    + " repo: of " + root.getFileName() + " names " + plan.remote() + "."),
                            "git commit workspace.yaml");
                    log.info("workspace.yaml names the remote, committed in " + workingSet);
                } else {
                    log.warn("workspace.yaml names the remote; " + workingSet + " is no git repository, so it is not committed");
                }
            }
        } catch (IOException e) {
            throw new MojoException("Cannot write the project in " + root + ": " + e.getMessage(), e);
        }
        if (in.push()) {
            Result push = git(root, PUSH_SECONDS, "push", "-u", "origin", in.branch());
            if (!push.ok()) {
                throw new MojoException("The remote is set and pom.xml names it, but the push failed: "
                        + (push.exit() < 0 ? "it did not finish in " + PUSH_SECONDS + " seconds" : push.reason())
                        + ". Run the goal again to push, once the cause is dealt with.");
            }
            log.info("Pushed " + in.branch() + " to origin");
        }
    }

    private static void must(Result result, String what) {
        if (!result.ok()) {
            throw new MojoException(what + " failed: " + result.reason());
        }
    }

    /**
     * Runs git in a folder. It never asks at a terminal.
     *
     * @param folder  where it runs
     * @param seconds how long it may take
     * @param args    git's arguments
     * @return how it ended
     * @throws MojoException if git cannot be started
     */
    static Result git(Path folder, int seconds, String... args) {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        try {
            Path output = Files.createTempFile("ike-kb-remote-", ".out");
            try {
                ProcessBuilder builder = new ProcessBuilder(command).directory(folder.toFile())
                        .redirectErrorStream(true).redirectOutput(output.toFile());
                builder.environment().put("GIT_TERMINAL_PROMPT", "0");
                Process process = builder.start();
                if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                    process.descendants().forEach(ProcessHandle::destroy);
                    process.destroy();
                    return new Result(-1, Files.readString(output, StandardCharsets.UTF_8));
                }
                return new Result(process.exitValue(), Files.readString(output, StandardCharsets.UTF_8));
            } finally {
                Files.deleteIfExists(output);
            }
        } catch (IOException e) {
            throw new MojoException("Cannot run: " + String.join(" ", command), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoException("Interrupted running: " + String.join(" ", command), e);
        }
    }
}
