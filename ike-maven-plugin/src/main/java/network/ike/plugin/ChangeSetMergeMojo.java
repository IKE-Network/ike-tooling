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

import network.ike.knowledge.spi.ChangeSetMerger;
import network.ike.knowledge.spi.ChangeSetReport;
import network.ike.knowledge.spi.ChangeSetRequest;
import network.ike.knowledge.spi.IkeServiceBootstrap;
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
import java.util.List;
import java.util.Properties;

/**
 * {@code ike:changeset-merge} — merge a directory of change sets into one: each verified
 * against its format, all loaded together so an entity in several keeps every version, and
 * written as a single change set in the current format, attached under the {@code changeset}
 * classifier. A knowledge base's extension, authored over many browser sessions with one
 * change set each, becomes one artifact a knowledge-base build replays and a consumer depends
 * on. The work is done by the {@link ChangeSetMerger} on the project's runtime classpath, in
 * a forked JVM by default.
 */
@Mojo(name = IkeGoal.NAME_CHANGESET_MERGE, defaultPhase = "package")
public class ChangeSetMergeMojo implements org.apache.maven.api.plugin.Mojo {

    /** Instantiated by Maven. */
    public ChangeSetMergeMojo() {
    }

    @Inject
    private org.apache.maven.api.plugin.Log log;

    @Inject
    private Session session;

    @Inject
    private Project project;

    /** The directory of change sets to merge (every {@code .zip} in it), or one change set. */
    @Parameter(property = "ike.changeset.directory", required = true)
    String directory;

    /** The merged change set to write. */
    @Parameter(property = "ike.changeset.target",
            defaultValue = "${project.build.directory}/${project.artifactId}-${project.version}-changeset.zip")
    String target;

    /**
     * The modules, by UUID, whose versions the merge keeps; empty keeps every version. A
     * knowledge base's extension names its own module, so a session that also carries what a
     * browser wrote on its own merges into the extension's content alone.
     */
    @Parameter
    List<String> changeSetModules = List.of();

    /** Attach the merged change set to the project under the {@code changeset} classifier. */
    @Parameter(property = "ike.changeset.attach", defaultValue = "true")
    boolean attach;

    /** The implementation's simple class name, when several are on the classpath. */
    @Parameter(property = "ike.changeset.implementation")
    String implementation;

    @Parameter(property = "ike.changeset.fork", defaultValue = "true")
    boolean fork;

    @Parameter
    List<String> forkJvmArguments = List.of();

    @Parameter(property = "ike.changeset.classesDirectory", defaultValue = "${project.build.outputDirectory}")
    String classesDirectory;

    @Parameter(property = "ike.changeset.buildDirectory", defaultValue = "${project.build.directory}")
    String buildDirectory;

    @Parameter(property = "ike.changeset.skip", defaultValue = "false")
    boolean skip;

    @Override
    public void execute() {
        if (skip) {
            log.info("ike:changeset-merge skipped (ike.changeset.skip=true)");
            return;
        }
        Path source = Path.of(directory);
        if (!Files.exists(source)) {
            throw new MojoException("No change sets to merge at " + source);
        }
        Path merged = Path.of(target);
        Properties wire = new ChangeSetRequest(source, java.util.Optional.of(merged),
                changeSetModules.stream().map(String::strip).collect(java.util.stream.Collectors.toSet())).toProperties();
        if (implementation != null && !implementation.isBlank()) {
            wire.setProperty(IkeServiceBootstrap.IMPLEMENTATION_KEY, implementation);
        }
        Properties resultWire = new KnowledgeServiceRunner(log).run(ChangeSetMerger.class.getName(), wire,
                ChangeSetToolSupport.seamClasspath(session, project, classesDirectory),
                Path.of(buildDirectory, "ike-knowledge"), fork, forkJvmArguments);
        ChangeSetReport report = ChangeSetReport.fromProperties(resultWire);
        report.lines().forEach(line -> {
            if (line.strip().startsWith("error")) {
                log.error(line);
            } else {
                log.info(line);
            }
        });
        if (!report.ok()) {
            throw new MojoException(report.summary());
        }
        log.info(report.summary());
        if (attach) {
            ProducedArtifact artifact = session.createProducedArtifact(project.getGroupId(), project.getArtifactId(),
                    project.getVersion(), "changeset", "zip", "zip");
            session.getService(ProjectManager.class).attachArtifact(project, artifact, merged);
            log.info("Attached merged change set " + merged.getFileName() + " (classifier: changeset)");
        }
    }
}
