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

import network.ike.knowledge.spi.ArtifactInput;
import network.ike.knowledge.spi.Finding;
import network.ike.knowledge.spi.IkeServiceBootstrap;
import network.ike.knowledge.spi.KnowledgeVerifier;
import network.ike.knowledge.spi.VerifyReport;
import network.ike.knowledge.spi.VerifyRequest;
import network.ike.knowledge.spi.ViewSpec;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * Verifies a knowledge artifact in a pristine store — the round-trip gate
 * (IKE-Network/ike-issues#951): the artifact must load into a fresh store exactly as a
 * consumer's import would load it, manifest and count-equality checks intact, and
 * (with {@code FIT_REFERENCES}) every reference must resolve within the artifact plus
 * its declared base. A defect surfaces here, in the producing build, instead of in a
 * consumer's import dialog.
 * <p>The goal is a thin face over the knowledge-pipeline SPI: it builds a typed
 * {@link VerifyRequest}, and the {@link KnowledgeVerifier} implementation is resolved
 * by ServiceLoader from the <em>project's</em> runtime classpath inside a forked seam.
 * The build fails when the report carries any {@code ERROR} finding.
 */
@Mojo(name = IkeGoal.NAME_KNOWLEDGE_VERIFY,
      defaultPhase = "verify")
public class KnowledgeVerifyMojo implements org.apache.maven.api.plugin.Mojo {

    /** Creates this goal instance. */
    public KnowledgeVerifyMojo() {}

    @Inject
    private org.apache.maven.api.plugin.Log log;

    /**
     * Access the Maven logger.
     *
     * @return the logger
     */
    protected org.apache.maven.api.plugin.Log getLog() {
        return log;
    }

    @Inject
    private Session session;

    @Inject
    private Project project;

    /**
     * The artifact under verification — a protobuf zip produced earlier in this build.
     */
    @Parameter(property = "ike.knowledgeVerify.artifact", required = true)
    String artifact;

    /**
     * The base the artifact must fit, in load order — each entry
     * {@code "ROLE spec"} where ROLE is STORE_SEED, PB, or CHANGESET and spec is a
     * Maven coordinate ({@code g:a[:extension[:classifier]]:v}) or a file path. Empty
     * for a self-contained (standalone) artifact.
     */
    @Parameter
    List<String> baseInputs = List.of();

    /**
     * The checks to run. Defaults to {@code PRESENCE} — the consumer-import round
     * trip; add {@code FIT_REFERENCES} for the reference-resolution sweep.
     */
    @Parameter
    List<String> checks = List.of("PRESENCE");

    /**
     * The view specification's dotted dimension keys, stating only what differs from
     * the implementation's defaults.
     */
    @Parameter
    Map<String, String> view = Map.of();

    /**
     * The knowledge-service implementation's simple class name, when the classpath
     * carries several.
     */
    @Parameter(property = "ike.knowledgeVerify.implementation")
    String implementation;

    /**
     * Fork the seam (the default posture). Disable only for debugging.
     */
    @Parameter(property = "ike.knowledgeVerify.fork", defaultValue = "true")
    boolean fork;

    /**
     * Extra child-JVM arguments for the forked seam (heap, flags).
     */
    @Parameter
    List<String> forkJvmArguments = List.of();

    /**
     * The project's classes/resources directory, included on the seam classpath.
     */
    @Parameter(property = "ike.knowledgeVerify.classesDirectory",
               defaultValue = "${project.build.outputDirectory}")
    String classesDirectory;

    /**
     * The build directory hosting the seam's request/result files.
     */
    @Parameter(property = "ike.knowledgeVerify.buildDirectory",
               defaultValue = "${project.build.directory}")
    String buildDirectory;

    /**
     * Skip knowledge verification.
     */
    @Parameter(property = "ike.knowledgeVerify.skip", defaultValue = "false")
    boolean skip;

    /**
     * Builds the typed verification request, runs the {@link KnowledgeVerifier} across
     * the forked seam, logs every finding, and fails the build on any ERROR.
     *
     * @throws MojoException if the artifact is missing, an input cannot be parsed or
     *                       resolved, the seam fails, or the report carries an ERROR
     */
    @Override
    public void execute() {
        if (skip) {
            getLog().info("ike:knowledge-verify skipped (ike.knowledgeVerify.skip=true)");
            return;
        }
        Path artifactPath = Path.of(artifact);
        if (!Files.isRegularFile(artifactPath)) {
            throw new MojoException("Artifact under verification does not exist: " + artifactPath
                    + " — run the producing goal first");
        }
        Set<VerifyRequest.Check> requestedChecks = EnumSet.noneOf(VerifyRequest.Check.class);
        for (String check : checks) {
            try {
                requestedChecks.add(VerifyRequest.Check.valueOf(check.strip()));
            } catch (IllegalArgumentException e) {
                throw new MojoException("Unknown check \"" + check + "\" — use PRESENCE,"
                        + " FIT_REFERENCES, IDENTITY, DELTA, or CLASSIFICATION_FIT", e);
            }
        }

        VerifyRequest request = new VerifyRequest(artifactPath, resolveInputs(), requestedChecks,
                Optional.empty(), ViewSpec.of(view));
        Properties wire = request.toProperties();
        if (implementation != null && !implementation.isBlank()) {
            wire.setProperty(IkeServiceBootstrap.IMPLEMENTATION_KEY, implementation);
        }

        Properties resultWire = new KnowledgeServiceRunner(getLog()).run(
                KnowledgeVerifier.class.getName(), wire, seamClasspath(),
                Path.of(buildDirectory, "ike-knowledge"), fork, forkJvmArguments);
        VerifyReport report = VerifyReport.fromProperties(resultWire);

        long errors = 0;
        for (Finding finding : report.findings()) {
            String line = finding.check() + " " + finding.component() + " — " + finding.message();
            switch (finding.severity()) {
                case ERROR -> {
                    errors++;
                    getLog().error(line);
                }
                case WARNING -> getLog().warn(line);
                default -> getLog().info(line);
            }
        }
        if (errors > 0) {
            throw new MojoException("Knowledge verification failed: " + errors + " error finding"
                    + (errors == 1 ? "" : "s") + " for " + artifactPath.getFileName()
                    + " — see the findings above (IKE-Network/ike-issues#951)");
        }
        getLog().info("Knowledge verification passed: " + artifactPath.getFileName()
                + " (" + report.findings().size() + " finding(s), none blocking)");
    }

    private List<ArtifactInput> resolveInputs() {
        List<ArtifactInput> resolved = new ArrayList<>();
        for (String entry : baseInputs) {
            String stripped = entry.strip();
            int space = stripped.indexOf(' ');
            if (space <= 0) {
                throw new MojoException("Base input entry needs \"ROLE spec\": " + entry);
            }
            ArtifactInput.Role role;
            try {
                role = ArtifactInput.Role.valueOf(stripped.substring(0, space));
            } catch (IllegalArgumentException e) {
                throw new MojoException("Unknown input role in \"" + entry
                        + "\" — use STORE_SEED, PB, or CHANGESET", e);
            }
            String spec = stripped.substring(space + 1).strip();
            resolved.add(new ArtifactInput(role, resolveSpec(spec)));
        }
        return resolved;
    }

    /**
     * A spec with two or more colons is a Maven coordinate
     * ({@code g:a[:extension[:classifier]]:v}); anything else is a file path.
     */
    private Path resolveSpec(String spec) {
        if (spec.chars().filter(c -> c == ':').count() >= 2) {
            try {
                return session.resolveArtifact(session.createArtifactCoordinates(spec)).getPath();
            } catch (Exception e) {
                throw new MojoException("Cannot resolve knowledge input " + spec, e);
            }
        }
        Path file = Path.of(spec);
        if (!Files.isRegularFile(file)) {
            throw new MojoException("Knowledge input file does not exist: " + file);
        }
        return file;
    }

    private List<Path> seamClasspath() {
        List<Path> classpath = new ArrayList<>();
        Path classesDir = Path.of(classesDirectory);
        if (Files.isDirectory(classesDir)) {
            classpath.add(classesDir);
        }
        classpath.addAll(RuntimeClasspathResolver.mainRuntimePaths(session, project));
        return classpath;
    }
}
