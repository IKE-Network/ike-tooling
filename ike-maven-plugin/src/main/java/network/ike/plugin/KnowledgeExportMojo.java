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

import network.ike.knowledge.spi.ExportRequest;
import network.ike.knowledge.spi.ExportResult;
import network.ike.knowledge.spi.IkeServiceBootstrap;
import network.ike.knowledge.spi.KnowledgeExporter;
import network.ike.knowledge.spi.ViewSpec;
import org.apache.maven.api.ProducedArtifact;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.services.ProjectManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * Exports a ledger-form knowledge set as its protobuf change-set artifact and attaches
 * it as the {@code changeset} classifier — the released form of the set. A starter set
 * is exactly this artifact applied to an empty base.
 *
 * <p>The goal is a thin face over the knowledge-pipeline SPI
 * ({@code network.ike.tooling:ike-knowledge-spi}): it builds a typed
 * {@link ExportRequest}, and the {@link KnowledgeExporter} implementation is resolved by
 * ServiceLoader from the <em>project's</em> runtime classpath inside a forked seam —
 * declare {@code network.ike.knowledge:ike-knowledge-provider} at the use site (or rely
 * on the parent POM's default wiring). This plugin never depends on a store engine.
 *
 * <p>Typical use — a {@code *-changeset} module depending on its {@code *-terms} ledger
 * module plus the provider:
 *
 * <pre>{@code
 * <plugin>
 *     <groupId>network.ike.tooling</groupId>
 *     <artifactId>ike-maven-plugin</artifactId>
 *     <executions>
 *         <execution>
 *             <goals><goal>knowledge-export</goal></goals>
 *         </execution>
 *     </executions>
 * </plugin>
 * }</pre>
 *
 * @since 234
 */
@Mojo(name = IkeGoal.NAME_KNOWLEDGE_EXPORT,
      defaultPhase = "package")
public class KnowledgeExportMojo implements org.apache.maven.api.plugin.Mojo {

    /** Creates this goal instance. */
    public KnowledgeExportMojo() {}

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
     * The change-set file to write and attach.
     */
    @Parameter(property = "ike.knowledgeExport.outputFile",
               defaultValue = "${project.build.directory}/${project.artifactId}-${project.version}-changeset.zip")
    String outputFile;

    /**
     * Fully qualified name of the {@code KnowledgeSetSource} implementation to compose.
     * Optional: when absent, exactly one implementation must be discoverable on the
     * project's runtime classpath.
     */
    @Parameter(property = "ike.knowledgeExport.sourceClass")
    String sourceClass;

    /**
     * Optional koncepts YAML to extract from the same loaded store — the standard
     * glossary definition source. Extracting here reuses the single change-set
     * materialization rather than a parallel read of the set.
     */
    @Parameter(property = "ike.knowledgeExport.konceptsYmlFile")
    String konceptsYmlFile;

    /**
     * The view specification's dotted dimension keys (IKE-KNOWLEDGE-VIEW), stating only
     * what differs from the implementation's defaults — for example
     * {@code <view><stamp.allowedStates>Active</stamp.allowedStates></view>}.
     */
    @Parameter
    Map<String, String> view = Map.of();

    /**
     * The knowledge-service implementation's simple class name, when the classpath
     * carries several.
     */
    @Parameter(property = "ike.knowledgeExport.implementation")
    String implementation;

    /**
     * Fork the seam (the default posture — the store lifecycle is JVM-global, and the
     * fork survives anything the implementation does). Disable only for debugging:
     * same classpath, same bootstrap, no process boundary.
     */
    @Parameter(property = "ike.knowledgeExport.fork", defaultValue = "true")
    boolean fork;

    /**
     * Extra child-JVM arguments for the forked seam (heap, flags). The child inherits
     * {@code --enable-preview} from the Maven JVM automatically.
     */
    @Parameter
    List<String> forkJvmArguments = List.of();

    /**
     * The project's classes/resources directory, included on the seam classpath so the
     * module's own {@code META-INF/services} registrations are discoverable.
     */
    @Parameter(property = "ike.knowledgeExport.classesDirectory",
               defaultValue = "${project.build.outputDirectory}")
    String classesDirectory;

    /**
     * Attach the exported file to the project as the {@code changeset} classifier
     * (extension {@code zip}), so it installs and deploys with the module.
     */
    @Parameter(property = "ike.knowledgeExport.attach", defaultValue = "true")
    boolean attach;

    /**
     * Skip change-set export.
     */
    @Parameter(property = "ike.knowledgeExport.skip", defaultValue = "false")
    boolean skip;

    /**
     * The build directory hosting the seam's request/result files (under
     * {@code ike-knowledge/}).
     */
    @Parameter(property = "ike.knowledgeExport.buildDirectory",
               defaultValue = "${project.build.directory}")
    String buildDirectory;

    /**
     * Builds the typed export request, runs the {@link KnowledgeExporter} across the
     * forked seam on the project's runtime classpath, and attaches the change-set
     * artifact.
     *
     * @throws MojoException if the classpath cannot be resolved, the seam fails, or the
     *                       output file was not produced
     */
    @Override
    public void execute() {
        if (skip) {
            getLog().info("ike:knowledge-export skipped (ike.knowledgeExport.skip=true)");
            return;
        }

        ExportRequest request = new ExportRequest(
                Path.of(outputFile),
                Optional.ofNullable(konceptsYmlFile).filter(s -> !s.isBlank()).map(Path::of),
                Optional.ofNullable(sourceClass).filter(s -> !s.isBlank()),
                ViewSpec.of(view));
        Properties wire = request.toProperties();
        if (implementation != null && !implementation.isBlank()) {
            wire.setProperty(IkeServiceBootstrap.IMPLEMENTATION_KEY, implementation);
        }

        Properties resultWire = new KnowledgeServiceRunner(getLog()).run(
                KnowledgeExporter.class.getName(), wire, seamClasspath(),
                Path.of(buildDirectory, "ike-knowledge"),
                fork, forkJvmArguments);
        ExportResult result = ExportResult.fromProperties(resultWire);

        Path produced = result.outputFile();
        if (!Files.isRegularFile(produced)) {
            throw new MojoException("Change-set export produced no file at " + produced);
        }
        getLog().info("Change set exported: " + produced.getFileName() + " — "
                + result.counts().total() + " entities (" + result.counts().concepts()
                + " concepts, " + result.counts().semantics() + " semantics, "
                + result.counts().patterns() + " patterns, " + result.counts().stamps()
                + " stamps)");
        result.konceptsYmlFile().ifPresent(yml ->
                getLog().info("Koncepts extracted: " + yml));

        if (attach) {
            ProducedArtifact artifact = session.createProducedArtifact(
                    project.getGroupId(), project.getArtifactId(), project.getVersion(),
                    "changeset", "zip", "zip");
            session.getService(ProjectManager.class).attachArtifact(project, artifact, produced);
            getLog().info("Attached change set " + produced.getFileName()
                    + " (classifier: changeset, extension: zip)");
        } else {
            getLog().info("Change set written (not attached): " + produced);
        }
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
