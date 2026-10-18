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
import java.util.LinkedHashMap;
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
 * (first position only), {@code PB}, {@code CHANGESET}, or {@code SOURCE}; the spec is
 * either a Maven coordinate ({@code groupId:artifactId[:extension[:classifier]]:version},
 * resolved from the repositories) or a file path — captured verification change sets
 * feed the next iteration as file inputs. A {@code SOURCE} entry is
 * {@code SOURCE importer spec [name=value ...]}: a source release (a file, a directory,
 * or a coordinate) written into the store by the named knowledge importer
 * ({@code dev.ikm.tinkar:importer-api}), which the project's runtime classpath carries:
 *
 * <pre>{@code
 * <inputs>
 *     <input>PB network.ike.foundation:ike-starter-set:zip:unreasoned-pb:1-SNAPSHOT</input>
 *     <input>SOURCE snomed-rf2 ${snomed.release} release=full</input>
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
     * {@code STORE_SEED | PB | CHANGESET | SOURCE} (a source adds its importer before the
     * spec and options after it), spec a Maven coordinate
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
     * Replaced wholesale on each install; change sets captured in it are harvested first
     * (see {@link #changeSetDirectory}) or the install is refused.
     */
    @Parameter(property = "ike.kbAssemble.installDirectory")
    String installDirectory;

    /**
     * The project directory that keeps the change sets authored in the installed knowledge
     * base. Before the install is replaced, every change set captured there that the
     * directory lacks is copied into it; then every change set it keeps is replayed, in
     * name order, as a {@code CHANGESET} input after the configured inputs. Commit the
     * directory: it is the knowledge base's extension, the content no source release carries.
     */
    @Parameter(property = "ike.kbAssemble.changeSetDirectory")
    String changeSetDirectory;

    /**
     * Replace an installed knowledge base holding captured change sets without harvesting
     * them, when no {@link #changeSetDirectory} is set. The change sets are lost.
     */
    @Parameter(property = "ike.kbAssemble.discardChangeSets", defaultValue = "false")
    boolean discardChangeSets;

    /**
     * The extension's modules, by UUID. When set, only a captured change set whose stamps
     * reference one of them is harvested; the rest (what a browser writes on its own: plugin
     * bootstraps, assistant conversations) are parked in {@code <installDirectory>-sessions}
     * beside the store, kept but not replayed. Unset, every captured change set is harvested.
     */
    @Parameter
    List<String> changeSetModules = List.of();

    /**
     * Replay the change sets {@link #changeSetDirectory} keeps. Off when another module
     * merges them into an artifact this assembly loads as an input: the directory is then
     * only where captured change sets are harvested, which still guards the install.
     */
    @Parameter(property = "ike.kbAssemble.replayChangeSets", defaultValue = "true")
    boolean replayChangeSets;

    /**
     * Write the koncepts YAML of the assembled knowledge base's extension here: the concepts
     * and patterns with a version in one of {@link #konceptsModules}, read from the whole
     * classified store so a concept authored under a release's concept keeps its parent's
     * name. Commit it beside the extension: it is the extension's readable form.
     */
    @Parameter(property = "ike.kbAssemble.konceptsYmlFile")
    String konceptsYmlFile;

    /** The extension's modules, by UUID, for {@link #konceptsYmlFile}. */
    @Parameter
    List<String> konceptsModules = List.of();

    /**
     * Export what the {@code SOURCE} inputs wrote, and only that, to this file: every version
     * under a stamp an import created, unclassified, its references to the inputs before it by
     * public id. One source's artifact, layered on its base; attached under
     * {@link #sourceDeltaClassifier}.
     */
    @Parameter(property = "ike.kbAssemble.sourceDeltaFile")
    String sourceDeltaFile;

    /** The classifier the source delta is attached under; blank leaves it unattached. */
    @Parameter(property = "ike.kbAssemble.sourceDeltaClassifier", defaultValue = "unreasoned-pb")
    String sourceDeltaClassifier;

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
        List<ArtifactInput> resolvedInputs = new ArrayList<>(resolveInputs());
        resolvedInputs.addAll(changeSetInputs());
        AssembleRequest request = new AssembleRequest(
                Path.of(storeRoot), cleanStart, resolvedInputs, ViewSpec.of(view), classify,
                Optional.ofNullable(reasonerService).filter(s -> !s.isBlank()),
                Optional.ofNullable(installDirectory).filter(s -> !s.isBlank()).map(Path::of),
                reasonedPbTarget,
                Optional.ofNullable(konceptsYmlFile).filter(s -> !s.isBlank()).map(Path::of),
                konceptsModules.stream().map(String::strip).collect(java.util.stream.Collectors.toSet()),
                Optional.ofNullable(sourceDeltaFile).filter(s -> !s.isBlank()).map(Path::of));
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
        // A source's importer reports what it wrote in the summary's name, not entities by kind.
        result.loads().forEach(load -> getLog().info(load.counts().total() == 0
                ? "Imported " + load.artifact()
                : "Loaded " + load.artifact() + " — "
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
        if (sourceDeltaFile != null && !sourceDeltaFile.isBlank()) {
            Path delta = Path.of(sourceDeltaFile);
            if (!Files.isRegularFile(delta)) {
                throw new MojoException("Source delta export produced no file at " + delta);
            }
            getLog().info("Source delta exported: " + delta.getFileName());
            if (sourceDeltaClassifier != null && !sourceDeltaClassifier.isBlank()) {
                ProducedArtifact artifact = session.createProducedArtifact(project.getGroupId(),
                        project.getArtifactId(), project.getVersion(), sourceDeltaClassifier, "zip", "zip");
                session.getService(ProjectManager.class).attachArtifact(project, artifact, delta);
                getLog().info("Attached source delta " + delta.getFileName() + " (classifier: " + sourceDeltaClassifier + ")");
            }
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

    /**
     * Harvests the installed knowledge base's captured change sets into the change-set
     * directory, and returns every change set kept there as an input. Without a change-set
     * directory, an install holding captured change sets is refused unless discarding them
     * was asked for: the install replaces the store, and them with it.
     */
    private List<ArtifactInput> changeSetInputs() {
        List<Path> captured = Optional.ofNullable(installDirectory).filter(s -> !s.isBlank()).map(Path::of)
                .map(ChangeSetHarvest::captured).orElse(List.of());
        if (changeSetDirectory == null || changeSetDirectory.isBlank()) {
            if (!captured.isEmpty() && !discardChangeSets) {
                throw new MojoException("The installed knowledge base " + installDirectory + " holds "
                        + captured.size() + " captured change set(s) the install would destroy: " + captured
                        + ". Set <changeSetDirectory> to harvest and replay them, or"
                        + " -Dike.kbAssemble.discardChangeSets=true to replace the store without them.");
            }
            return List.of();
        }
        Path directory = Path.of(changeSetDirectory);
        if (!changeSetModules.isEmpty() && !captured.isEmpty()) {
            Path parking = Path.of(installDirectory + "-sessions");
            List<Path> extension = ChangeSetHarvest.sortOut(captured,
                    changeSetModules.stream().map(String::strip).collect(java.util.stream.Collectors.toSet()), parking);
            if (extension.size() < captured.size()) {
                getLog().info("Parked " + (captured.size() - extension.size()) + " change set(s) with no content in "
                        + changeSetModules + " in " + parking);
            }
            captured = extension;
        }
        List<Path> harvested = ChangeSetHarvest.harvest(captured, directory);
        harvested.forEach(file -> getLog().info("Harvested change set " + file.getFileName() + " into " + directory));
        if (!replayChangeSets) {
            return List.of();
        }
        List<Path> kept = ChangeSetHarvest.kept(directory);
        if (!kept.isEmpty()) {
            getLog().info("Replaying " + kept.size() + " change set(s) from " + directory);
        }
        return kept.stream().map(file -> new ArtifactInput(ArtifactInput.Role.CHANGESET, file)).toList();
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
                        + "\" — use STORE_SEED, PB, CHANGESET, or SOURCE", e);
            }
            String spec = stripped.substring(space + 1).strip();
            if (role == ArtifactInput.Role.SOURCE) {
                resolved.add(resolveSource(entry, spec));
            } else {
                resolved.add(withCoordinate(new ArtifactInput(role, resolveSpec(spec)), spec));
            }
        }
        return resolved;
    }

    /**
     * A source entry after its role: {@code importer spec [name=value ...]} — the knowledge
     * importer's name, the release (a Maven coordinate, a file, or a directory), and the
     * importer's options; a value with spaces is double-quoted ({@code author="IHTSDO SNOMED CT Author"}).
     */
    private ArtifactInput resolveSource(String entry, String rest) {
        String[] words = words(rest);
        if (words.length < 2) {
            throw new MojoException("A SOURCE input needs \"SOURCE importer spec [name=value ...]\": " + entry);
        }
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 2; i < words.length; i++) {
            int equals = words[i].indexOf('=');
            if (equals <= 0) {
                throw new MojoException("A SOURCE option is name=value, not \"" + words[i] + "\": " + entry);
            }
            options.put(words[i].substring(0, equals), words[i].substring(equals + 1));
        }
        String spec = words[1];
        Path release = isCoordinate(spec) || !Files.isDirectory(Path.of(spec))
                ? resolveSpec(spec)
                : Path.of(spec);
        return withCoordinate(ArtifactInput.source(words[0], release, options), spec);
    }

    /**
     * The input with the coordinate it was resolved from, when its spec is one: the assembly
     * records it in the knowledge base (IKE-Network/ike-issues#1304).
     */
    private ArtifactInput withCoordinate(ArtifactInput input, String spec) {
        if (!isCoordinate(spec)) {
            return input;
        }
        String version = resolvedVersions.get(spec);
        return version != null ? input.withCoordinate(spec, version) : input.withCoordinate(spec);
    }

    /** The version each coordinate resolved to, by the coordinate as written. */
    private final Map<String, String> resolvedVersions = new LinkedHashMap<>();

    private static boolean isCoordinate(String spec) {
        return spec.chars().filter(c -> c == ':').count() >= 2;
    }

    /**
     * Splits on whitespace, keeping a double-quoted stretch whole and dropping its quotes:
     * {@code a b="c d"} is {@code a} and {@code b=c d}.
     */
    static String[] words(String text) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (char c : text.strip().toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
                any = true;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (any) {
                    words.add(word.toString());
                    word.setLength(0);
                    any = false;
                }
            } else {
                word.append(c);
                any = true;
            }
        }
        if (quoted) {
            throw new MojoException("Unbalanced quote in \"" + text + "\"");
        }
        if (any) {
            words.add(word.toString());
        }
        return words.toArray(String[]::new);
    }

    /**
     * A spec with two or more colons is a Maven coordinate
     * ({@code g:a[:extension[:classifier]]:v}); anything else is a file path.
     */
    private Path resolveSpec(String spec) {
        if (isCoordinate(spec)) {
            try {
                org.apache.maven.api.DownloadedArtifact artifact =
                        session.resolveArtifact(session.createArtifactCoordinates(spec));
                resolvedVersions.put(spec, artifact.getVersion().toString());
                return artifact.getPath();
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
