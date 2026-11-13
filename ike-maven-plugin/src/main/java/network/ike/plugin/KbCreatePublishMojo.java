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

import org.apache.maven.api.Session;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Creates a new knowledge-base project, the executing half of the {@code ike:kb-create-*}
 * pair (IKE-Network/ike-issues#1370): pins every knowledge source, writes the POM and files the
 * {@code kb/} templates render, applies the standards scaffold's project scope (wrapper,
 * {@code .mvn}, {@code .gitignore}, {@code CLAUDE.md}, {@code README.md}), makes a local
 * git repository with one commit and no remote, and proves the project by running
 * {@code ./mvnw validate} in it.
 *
 * <p>Run {@code ike:kb-create-draft} first: publish creates exactly what the draft showed.
 * The folder may exist (a caller may have put the Maven wrapper in it), but never with a
 * {@code pom.xml} or a {@code .git} in it. The project's {@code <scm>} names its own folder
 * as a {@code file:} URL until a remote is added (decision 4).
 *
 * @since 280
 */
@Mojo(name = IkeGoal.NAME_KB_CREATE_PUBLISH, projectRequired = false)
public class KbCreatePublishMojo extends KbCreateParameters implements org.apache.maven.api.plugin.Mojo {

    /** Creates this goal instance. */
    public KbCreatePublishMojo() {}

    @Inject
    private org.apache.maven.api.plugin.Log log;

    @Inject
    private Session session;

    /**
     * Skip {@code ./mvnw validate} in the new project, for use without a network; the
     * project must then be built before first use.
     */
    @Parameter(property = "skipVerify", defaultValue = "false")
    boolean skipVerify;

    /**
     * Creates, commits and validates the project.
     *
     * @throws MojoException if the folder already holds a project, a knowledge source cannot be
     *                       pinned, a file cannot be written, git fails, or the new
     *                       project does not validate
     */
    @Override
    public void execute() {
        Path scaffold = null;
        try {
            scaffold = Files.createTempDirectory("ike-kb-create-");
            Path root = KbCreateSupport.unpackScaffold(session, scaffold);
            KbCreatePlan plan = plan(session, log, root);
            Path target = target();
            refuseExisting(target);
            Map<String, String> files = plan.render(KbCreateSupport.templates(root));
            List<String> written = new ArrayList<>(write(target, files));
            written.addAll(KbCreateSupport.applyProjectScaffold(root, target, Path.of(System.getProperty("user.home"))));
            commit(target, plan, written);
            if (skipVerify) {
                log.warn("Validation skipped (-DskipVerify): build the project before first use.");
            } else {
                log.info("Validating the new project (./mvnw validate) …");
                KbCreateSupport.run(log, target, wrapper(target), "-B", "-q", "validate");
                log.info("The new project validates.");
            }
            for (String line : plan.report(target.toString())) {
                log.info(line);
            }
            log.info("  Files:");
            written.forEach(file -> log.info("    " + file));
            log.info("Next:");
            log.info("  cd " + target);
            log.info("  ./mvnw verify    assemble and verify the knowledge base");
            log.info("  ./mvnw deploy    publish it to " + plan.inputs().distributionId());
            log.info("  To publish the repository: git remote add origin <url>, replace the three"
                    + " <scm> URLs in pom.xml with it, then git push -u origin main.");
        } catch (IOException e) {
            throw new MojoException("Cannot create the knowledge base: " + e.getMessage(), e);
        } finally {
            KbCreateDraftMojo.deleteQuietly(scaffold);
        }
    }

    /**
     * Refuses a folder that already holds a project: creation runs once.
     *
     * @param target the project's folder
     * @throws MojoException if it holds a {@code pom.xml} or a {@code .git}
     */
    static void refuseExisting(Path target) {
        if (Files.isRegularFile(target.resolve("pom.xml")) || Files.exists(target.resolve(".git"))) {
            throw new MojoException("Creation runs once — " + target + " already holds a project"
                    + " (a pom.xml or a .git). Choose another -Dname or -DtargetDirectory.");
        }
    }

    /**
     * Writes the rendered files.
     *
     * @param target the project's folder
     * @param files  project-relative path to content
     * @return the paths written
     * @throws IOException if a file cannot be written
     */
    static List<String> write(Path target, Map<String, String> files) throws IOException {
        List<String> written = new ArrayList<>();
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path path = target.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue(), StandardCharsets.UTF_8);
            written.add(file.getKey());
        }
        return written;
    }

    private void commit(Path target, KbCreatePlan plan, List<String> written) {
        KbCreateSupport.run(log, target, "git", "init", "-q", "-b", "main");
        List<String> add = new ArrayList<>(List.of("git", "add", "--"));
        add.addAll(written);
        KbCreateSupport.run(log, target, add.toArray(new String[0]));
        KbCreateSupport.run(log, target, "git", "commit", "-q", "-m",
                "Genesis of " + plan.inputs().artifactId() + " (" + plan.displayName() + ")\n\n"
                        + "Generated by ike:kb-create-publish: a " + plan.inputs().kind().label()
                        + " publishing " + String.join(", ", plan.publishes()) + ".");
    }

    private static String wrapper(Path target) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        return target.resolve(windows ? "mvnw.cmd" : "mvnw").toString();
    }
}
