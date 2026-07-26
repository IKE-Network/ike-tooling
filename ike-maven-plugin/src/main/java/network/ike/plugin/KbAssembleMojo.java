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
import network.ike.knowledge.spi.AssembleRequest;
import network.ike.knowledge.spi.AssembleResult;
import network.ike.knowledge.spi.IkeServiceBootstrap;
import network.ike.knowledge.spi.KnowledgeBaseAssembler;
import network.ike.knowledge.spi.ViewSpec;
import org.apache.maven.api.ProducedArtifact;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.services.ProjectManager;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * Assembles a knowledge base from ordered knowledge artifacts — base data plus change
 * sets into a store, classified by default — optionally exports the classified store
 * as a standalone {@code reasoned-pb} artifact (IKE-Network/ike-issues#933), and
 * optionally installs it into a
 * data-source directory a knowledge browser reads. The full-cycle goal: a starter set
 * becomes an openable, navigable KB in one build (IKE-Network/ike-issues#848, #850).
 *
 * <p>The goal is a thin face over the knowledge-pipeline SPI: it builds a typed
 * {@link AssembleRequest}, and the {@link KnowledgeBaseAssembler} implementation is
 * resolved by ServiceLoader from the <em>project's</em> runtime classpath inside a
 * forked seam — declare {@code network.ike.knowledge:ike-knowledge-provider} at the use
 * site (or rely on the parent POM's default wiring).
 *
 * <p>Inputs are ordered {@code ROLE spec} entries: the role is {@code STORE_SEED}
 * (first position only), {@code PB}, or {@code CHANGESET}; the spec is either a Maven
 * coordinate ({@code groupId:artifactId[:extension[:classifier]]:version}, resolved
 * from the repositories) or a file path — captured verification change sets feed the
 * next iteration as file inputs:
 *
 * <pre>{@code
 * <inputs>
 *     <input>PB dev.ikm.data.tinkar:tinkar-starter-data:zip:reasoned-pb:20251009</input>
 *     <input>CHANGESET ${project.basedir}/../my-changeset/target/my-changeset.zip</input>
 * </inputs>
 * }</pre>
 *
 * @since 235
 */
@Mojo(name = IkeGoal.NAME_KB_ASSEMBLE,
      defaultPhase = "package")
public class KbAssembleMojo implements org.apache.maven.api.plugin.Mojo {

    /** Creates this goal instance. */
    public KbAssembleMojo() {}

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
     * The ordered knowledge inputs, each {@code ROLE spec} — role
     * {@code STORE_SEED | PB | CHANGESET}, spec a Maven coordinate
     * ({@code g:a[:extension[:classifier]]:v}) or a file path. List order is load
     * order; the base comes first.
     */
    @Parameter
    List<String> inputs = List.of();

    /**
     * The store root to assemble into.
     */
    @Parameter(property = "ike.kbAssemble.storeRoot",
               defaultValue = "${project.build.directory}/kb")
    String storeRoot;

    /**
     * Delete the store root before assembling — the default, so every build assembles
     * the declared knowledge-state from scratch.
     */
    @Parameter(property = "ike.kbAssemble.cleanStart", defaultValue = "true")
    boolean cleanStart;

    /**
     * Run classification as the final assembly step — the default posture; the
     * assembled KB is navigable the moment a browser opens it.
     */
    @Parameter(property = "ike.kbAssemble.classify", defaultValue = "true")
    boolean classify;

    /**
     * The reasoner service's simple class name, when the classpath carries several.
     */
    @Parameter(property = "ike.kbAssemble.reasonerService")
    String reasonerService;

    /**
     * A directory to install the assembled store into — for example a data-source
     * directory a knowledge browser reads ({@code ${user.home}/Solor/<name>}).
     * Replaced wholesale on each install.
     */
    @Parameter(property = "ike.kbAssemble.installDirectory")
    String installDirectory;

    /**
     * Export the classified store as a full standalone reasoned protobuf — the
     * {@code reasoned-pb} classifier form, inferred results baked in, ready to open
     * without a reasoner run (IKE-Network/ike-issues#933). Requires {@link #classify}.
     */
    @Parameter(property = "ike.kbAssemble.reasonedPb", defaultValue = "false")
    boolean reasonedPb;

    /**
     * The reasoned-protobuf export file. When unset and {@link #reasonedPb} is true,
     * defaults to {@code <buildDirectory>/<artifactId>-<version>-reasoned-pb.zip}.
     * Setting this implies {@link #reasonedPb}.
     */
    @Parameter(property = "ike.kbAssemble.reasonedPbFile")
    String reasonedPbFile;

    /**
     * Attach the reasoned-protobuf export to the project under the
     * {@code reasoned-pb} classifier (extension {@code zip}), so it installs and
     * deploys with the module.
     */
    @Parameter(property = "ike.kbAssemble.attachReasonedPb", defaultValue = "true")
    boolean attachReasonedPb;

    /**
     * The view specification's dotted dimension keys (IKE-KNOWLEDGE-VIEW), stating only
     * what differs from the implementation's defaults.
     */
    @Parameter
    Map<String, String> view = Map.of();

    /**
     * The knowledge-service implementation's simple class name, when the classpath
     * carries several.
     */
    @Parameter(property = "ike.kbAssemble.implementation")
    String implementation;

    /**
     * Fork the seam (the default posture). Disable only for debugging.
     */
    @Parameter(property = "ike.kbAssemble.fork", defaultValue = "true")
    boolean fork;

    /**
     * Extra child-JVM arguments for the forked seam (heap, flags). The child inherits
     * {@code --enable-preview} from the Maven JVM automatically.
     */
    @Parameter
    List<String> forkJvmArguments = List.of();

    /**
     * The project's classes/resources directory, included on the seam classpath.
     */
    @Parameter(property = "ike.kbAssemble.classesDirectory",
               defaultValue = "${project.build.outputDirectory}")
    String classesDirectory;

    /**
     * The build directory hosting the seam's request/result files.
     */
    @Parameter(property = "ike.kbAssemble.buildDirectory",
               defaultValue = "${project.build.directory}")
    String buildDirectory;

    /**
     * Skip knowledge-base assembly.
     */
    @Parameter(property = "ike.kbAssemble.skip", defaultValue = "false")
    boolean skip;

    /**
     * Resolves the ordered inputs, builds the typed assembly request, and runs the
     * {@link KnowledgeBaseAssembler} across the forked seam.
     *
     * @throws MojoException if an input cannot be parsed or resolved, the seam fails,
     *                       or the store was not produced
     */
    @Override
    public void execute() {
        if (skip) {
            getLog().info("ike:kb-assemble skipped (ike.kbAssemble.skip=true)");
            return;
        }
        if (inputs.isEmpty()) {
            throw new MojoException("ike:kb-assemble requires at least one <inputs><input> entry"
                    + " (ROLE followed by a Maven coordinate or file path)");
        }

        Optional<Path> reasonedPbTarget = reasonedPbTarget();
        AssembleRequest request = new AssembleRequest(
                Path.of(storeRoot), cleanStart, resolveInputs(), ViewSpec.of(view), classify,
                Optional.ofNullable(reasonerService).filter(s -> !s.isBlank()),
                Optional.ofNullable(installDirectory).filter(s -> !s.isBlank()).map(Path::of),
                reasonedPbTarget);
        Properties wire = request.toProperties();
        if (implementation != null && !implementation.isBlank()) {
            wire.setProperty(IkeServiceBootstrap.IMPLEMENTATION_KEY, implementation);
        }

        Properties resultWire = new KnowledgeServiceRunner(getLog()).run(
                KnowledgeBaseAssembler.class.getName(), wire, seamClasspath(),
                Path.of(buildDirectory, "ike-knowledge"), fork, forkJvmArguments);
        AssembleResult result = AssembleResult.fromProperties(resultWire);

        if (!Files.isDirectory(Path.of(storeRoot))) {
            throw new MojoException("Knowledge-base assembly produced no store at " + storeRoot);
        }
        result.loads().forEach(load -> getLog().info("Loaded " + load.artifact() + " — "
                + load.counts().total() + " entities (" + load.counts().concepts()
                + " concepts, " + load.counts().semantics() + " semantics, "
                + load.counts().patterns() + " patterns, " + load.counts().stamps() + " stamps)"));
        result.classification().ifPresentOrElse(
                summary -> getLog().info("Classified by " + summary.service() + ": "
                        + summary.conceptCount() + " concepts, " + summary.inferredChanges()
                        + " inferred changes, " + summary.navigationChanges()
                        + " navigation changes in " + summary.elapsedMillis() + " ms"),
                () -> getLog().info("Classification skipped by request"));
        result.effectiveViewReport().ifPresent(report ->
                getLog().info("Effective view reported: " + report));
        if (installDirectory != null && !installDirectory.isBlank()) {
            getLog().info("Knowledge base installed: " + installDirectory);
        }
        if (reasonedPbTarget.isPresent()) {
            Path produced = result.reasonedPbFile().orElseThrow(() -> new MojoException(
                    "Reasoned-protobuf export was requested but the assembly reported none"));
            if (!Files.isRegularFile(produced)) {
                throw new MojoException("Reasoned-protobuf export produced no file at " + produced);
            }
            getLog().info("Reasoned knowledge set exported: " + produced.getFileName());
            if (attachReasonedPb) {
                ProducedArtifact artifact = session.createProducedArtifact(
                        project.getGroupId(), project.getArtifactId(), project.getVersion(),
                        "reasoned-pb", "zip", "zip");
                session.getService(ProjectManager.class).attachArtifact(project, artifact, produced);
                getLog().info("Attached reasoned knowledge export " + produced.getFileName()
                        + " (classifier: reasoned-pb, extension: zip)");
            }
        }
    }

    /**
     * The reasoned-protobuf export target: the explicit {@link #reasonedPbFile} when
     * set, the conventional {@code <artifactId>-<version>-reasoned-pb.zip} under the
     * build directory when {@link #reasonedPb} asked for one, empty otherwise.
     *
     * @return the export target, when an export was requested
     */
    private Optional<Path> reasonedPbTarget() {
        if (reasonedPbFile != null && !reasonedPbFile.isBlank()) {
            return Optional.of(Path.of(reasonedPbFile));
        }
        if (reasonedPb) {
            return Optional.of(Path.of(buildDirectory).resolve(
                    project.getArtifactId() + "-" + project.getVersion() + "-reasoned-pb.zip"));
        }
        return Optional.empty();
    }

    private List<ArtifactInput> resolveInputs() {
        List<ArtifactInput> resolved = new ArrayList<>();
        for (String entry : inputs) {
            String stripped = entry.strip();
            int space = stripped.indexOf(' ');
            if (space <= 0) {
                throw new MojoException("Input entry needs \"ROLE spec\": " + entry);
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
            throw new MojoException("Knowledge input file does not exist: " + file
                    + " — captured change sets must be present before assembly");
        }
        return file;
    }

    private List<Path> seamClasspath() {
        // Resolution goes through the plugin's serialized entry point — Maven 4 rc-5's
        // resolver cache is not thread-safe under -T (IKE-Network/ike-issues#901).
        List<Path> classpath = new ArrayList<>();
        Path classesDir = Path.of(classesDirectory);
        if (Files.isDirectory(classesDir)) {
            classpath.add(classesDir);
        }
        classpath.addAll(RuntimeClasspathResolver.mainRuntimePaths(session, project));
        return classpath;
    }
}
