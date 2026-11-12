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

import network.ike.workspace.ManifestWriter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What giving a project its remote does (IKE-Network/ike-issues#1370, decision 4): a
 * knowledge-base project is created with no remote and its own folder, as a {@code file:}
 * URL, in the {@code <scm>} of its POM and in the {@code repo:} of a working set's
 * {@code workspace.yaml}. Adding a remote sets {@code origin}, replaces those URLs, commits
 * the change, and pushes. The remote is the project's own: the Maven project that builds
 * the knowledge base, not the knowledge base it builds.
 *
 * <p>The plan is pure: everything it is made from was read beforehand, so it can be
 * checked and shown before anything is changed. Publish applies exactly this plan.
 */
final class KbRemotePlan {

    /** A URL with a scheme git reads. */
    private static final Pattern URL = Pattern.compile("^(https?|ssh|git|file)://\\S+$");

    /** The short form, {@code user@host:path}. */
    private static final Pattern SCP = Pattern.compile("^([\\w.-]+)@([\\w.-]+):(\\S+)$");

    /** {@code ssh://user@host[:port]/path}. */
    private static final Pattern SSH = Pattern.compile("^ssh://(?:[\\w.-]+@)?([\\w.-]+)(?::\\d+)?/(\\S+)$");

    /**
     * What a POM's {@code <scm>} names.
     *
     * @param url                 where a person reads the repository
     * @param connection          how it is read, as {@code scm:git:…}
     * @param developerConnection how it is written to, as {@code scm:git:…}
     */
    record Scm(String url, String connection, String developerConnection) {

        /**
         * The values by the name of their element.
         *
         * @return element name to value
         */
        Map<String, String> values() {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("url", url);
            values.put("connection", connection);
            values.put("developerConnection", developerConnection);
            return values;
        }
    }

    /**
     * What the remote answered when it was asked for its branches.
     *
     * @param reachable  whether it answered
     * @param error      why it did not, or blank
     * @param branchHead the commit its branch of the project's name is at, when it has one
     * @param contained  whether the project's history already contains that commit, so a
     *                   push only adds to it
     * @param branches   how many branches it has
     */
    record Probe(boolean reachable, String error, Optional<String> branchHead, boolean contained, int branches) {

        /** A remote that was not asked: the push is skipped. */
        static final Probe NOT_ASKED = new Probe(false, "", Optional.empty(), false, 0);
    }

    /**
     * Everything the plan is made from.
     *
     * @param root            the project's folder, the root of its git repository
     * @param pom             the text of its {@code pom.xml}
     * @param remote          the remote's URL, as given
     * @param origin          the URL {@code origin} already names, when there is one
     * @param branch          the branch checked out
     * @param pomUncommitted  whether {@code pom.xml} has uncommitted changes
     * @param probe           what the remote answered
     * @param manifest        the {@code workspace.yaml} of the working set the project is a
     *                        member of, when it is one
     * @param manifestYaml    that file's text
     * @param push            whether the branch is pushed once the remote is set
     */
    record Inputs(Path root, String pom, String remote, Optional<String> origin, String branch, boolean pomUncommitted,
                  Probe probe, Optional<Path> manifest, Optional<String> manifestYaml, boolean push) {
    }

    private final Inputs in;
    private final String remote;
    private final Scm current;
    private final Scm wanted;
    private final String newPom;
    private final Optional<String> newManifest;

    /**
     * Builds and checks the plan.
     *
     * @param inputs everything it is made from
     * @throws IllegalArgumentException if the remote is no git URL, the POM has no
     *                                  {@code <scm>} or already names another remote,
     *                                  {@code origin} already names another remote, or
     *                                  {@code pom.xml} has uncommitted changes
     */
    KbRemotePlan(Inputs inputs) {
        this.in = Objects.requireNonNull(inputs, "inputs");
        this.remote = inputs.remote() == null ? "" : inputs.remote().strip();
        if (remote.isEmpty()) {
            throw new IllegalArgumentException("Name the remote with -Dremote=<git URL>, such as"
                    + " -Dremote=https://github.com/my-org/my-kb.git");
        }
        if (!URL.matcher(remote).matches() && !SCP.matcher(remote).matches()) {
            throw new IllegalArgumentException("-Dremote is not a git URL: " + remote + ". Use https://…,"
                    + " ssh://…, or user@host:path.");
        }
        Map<String, String> scm = PomRewriter.readScm(inputs.pom());
        if (scm.isEmpty()) {
            throw new IllegalArgumentException("pom.xml has no <scm> to point at the remote. A project made"
                    + " by ike:kb-create-publish has one.");
        }
        this.current = new Scm(scm.getOrDefault("url", ""), scm.getOrDefault("connection", ""),
                scm.getOrDefault("developerConnection", ""));
        this.wanted = scmFor(remote);
        boolean local = current.connection().startsWith("scm:git:file:") || current.url().startsWith("file:");
        if (!local && !current.equals(wanted)) {
            throw new IllegalArgumentException("pom.xml already names a remote, " + current.url()
                    + ". To move the project to another, change its <scm> and origin by hand.");
        }
        if (inputs.origin().isPresent() && !inputs.origin().get().equals(remote)) {
            throw new IllegalArgumentException("origin is already " + inputs.origin().get()
                    + ". To move the project to another remote, change origin by hand.");
        }
        this.newPom = PomRewriter.updateScm(inputs.pom(), wanted.values());
        if (pomChanges() && inputs.pomUncommitted()) {
            throw new IllegalArgumentException("pom.xml has uncommitted changes. Commit them first, so the"
                    + " commit that names the remote contains nothing else.");
        }
        String member = inputs.root().getFileName().toString();
        this.newManifest = inputs.manifestYaml()
                .map(yaml -> ManifestWriter.updateSubprojectField(yaml, member, "repo", remote))
                .filter(yaml -> !yaml.equals(inputs.manifestYaml().get()));
    }

    /**
     * What a POM's {@code <scm>} names for a remote: the URL as given to read from and
     * write to, and the address a person opens, which for an SSH remote is the same
     * repository over HTTPS.
     *
     * @param remote the remote's URL
     * @return the three values
     */
    static Scm scmFor(String remote) {
        String browse = remote;
        Matcher scp = SCP.matcher(remote);
        Matcher ssh = SSH.matcher(remote);
        if (scp.matches()) {
            browse = "https://" + scp.group(2) + "/" + scp.group(3);
        } else if (ssh.matches()) {
            browse = "https://" + ssh.group(1) + "/" + ssh.group(2);
        }
        if (!browse.startsWith("file:") && browse.endsWith(".git")) {
            browse = browse.substring(0, browse.length() - ".git".length());
        }
        return new Scm(browse, "scm:git:" + remote, "scm:git:" + remote);
    }

    /**
     * What the plan is made from.
     *
     * @return the inputs
     */
    Inputs inputs() {
        return in;
    }

    /**
     * The remote, as it is given to git.
     *
     * @return the URL
     */
    String remote() {
        return remote;
    }

    /**
     * Whether {@code pom.xml} is rewritten and committed.
     *
     * @return {@code false} when it already names the remote
     */
    boolean pomChanges() {
        return !newPom.equals(in.pom());
    }

    /**
     * The POM with its {@code <scm>} naming the remote.
     *
     * @return the POM's text
     */
    String newPom() {
        return newPom;
    }

    /**
     * Whether {@code origin} is added.
     *
     * @return {@code false} when it already names the remote
     */
    boolean addOrigin() {
        return in.origin().isEmpty();
    }

    /**
     * The working set's {@code workspace.yaml} with the member's {@code repo:} naming the
     * remote, when the project is a member and the file changes.
     *
     * @return the file's new text
     */
    Optional<String> newManifest() {
        return newManifest;
    }

    /**
     * Why the push would fail, known before anything is changed: the remote did not
     * answer, or its branch has commits the project does not.
     *
     * @return the reason, a sentence, or empty when the push can go ahead or is skipped
     */
    Optional<String> pushProblem() {
        if (!in.push()) {
            return Optional.empty();
        }
        Probe probe = in.probe();
        if (!probe.reachable()) {
            return Optional.of("The remote could not be read" + (probe.error().isBlank() ? "" : ": " + probe.error())
                    + ". Create the repository on the git host first, empty, and check that you are signed in.");
        }
        if (probe.branchHead().isPresent() && !probe.contained()) {
            return Optional.of("The remote already has a branch " + in.branch() + " with commits this project"
                    + " does not have. Use an empty repository, or one that is this project's own.");
        }
        return Optional.empty();
    }

    /**
     * The commit message for the POM.
     *
     * @return the message
     */
    String commitMessage() {
        return "Point " + in.root().getFileName() + " at its remote\n\n"
                + "Generated by ike:kb-remote-publish: the <scm> of pom.xml names " + remote
                + " in place of the project's own folder.";
    }

    /**
     * The report the draft and publish goals both print.
     *
     * @return the report's lines
     */
    List<String> report() {
        List<String> lines = new ArrayList<>();
        lines.add("Add remote: " + in.root().getFileName());
        lines.add("  Folder:       " + in.root());
        lines.add("  Remote:       " + remote + "  (" + remoteState() + ")");
        lines.add("  origin:       " + (addOrigin() ? "added" : "already set"));
        if (pomChanges()) {
            lines.add("  pom.xml scm:  url                  " + current.url() + " → " + wanted.url());
            lines.add("                connection           " + current.connection() + " → " + wanted.connection());
            lines.add("                developerConnection  " + current.developerConnection() + " → "
                    + wanted.developerConnection());
            lines.add("  Commit:       Point " + in.root().getFileName() + " at its remote (pom.xml alone)");
        } else {
            lines.add("  pom.xml scm:  already names the remote");
        }
        newManifest.ifPresent(yaml -> lines.add("  Working set:  repo: of " + in.root().getFileName() + " in "
                + in.manifest().map(Path::toString).orElse("workspace.yaml") + " → " + remote + ", committed there"));
        lines.add("  Push:         " + (in.push() ? "branch " + in.branch() + " to origin, which it then tracks"
                : "skipped (-DskipPush); push it with git push -u origin " + in.branch()));
        return lines;
    }

    private String remoteState() {
        Probe probe = in.probe();
        if (!in.push()) {
            return "not asked";
        }
        if (!probe.reachable()) {
            return "could not be read";
        }
        if (probe.branchHead().isEmpty()) {
            return probe.branches() == 0 ? "empty" : "has no branch " + in.branch();
        }
        return probe.contained() ? "has branch " + in.branch() + ", which this project already contains"
                : "has branch " + in.branch() + " with other commits";
    }
}
