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
import network.ike.knowledge.spi.ChangeSetCompactor;
import network.ike.knowledge.spi.ChangeSetRequest;
import org.apache.maven.api.DownloadedArtifact;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@code ike:changeset-compact} — rewrite a format-1 or format-2 change set in format 3: a component table, one entry per pattern, references by sequence.
 * The work is done by the {@link ChangeSetCompactor} on the project's runtime classpath,
 * in a forked JVM by default. With no knowledge bases no store is opened, and a reference the
 * change set does not carry keeps the pattern it had, none for one written by a store whose nids
 * carry no pattern. With knowledge bases, which hold what the change set references, every
 * reference is written with its pattern, so a store that mints by pattern can load the result
 * without a base; this also rewrites a format-3 change set whose references name no pattern.
 */
@Mojo(name = IkeGoal.NAME_CHANGESET_COMPACT)
public class ChangeSetCompactMojo implements org.apache.maven.api.plugin.Mojo {

    /** Instantiated by Maven. */
    public ChangeSetCompactMojo() {
    }

    @Inject
    private org.apache.maven.api.plugin.Log log;

    @Inject
    private Session session;

    @Inject
    private Project project;

    /** The change set zip to read. */
    @Parameter(property = "ike.changeset.file", required = true)
    String file;

    @Parameter(property = "ike.changeset.target", required = true)
    String target;

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

    /**
     * The knowledge bases holding what the change set references but does not carry, each a
     * Maven coordinate ({@code g:a:zip:classifier:v}) or a file: protobuf exports or change
     * sets. A UUID names the same component in every one of them, so each reference resolves to
     * its kind and pattern. Empty rewrites the layout alone.
     */
    @Parameter(property = "ike.changeset.knowledgeBases")
    List<String> knowledgeBases = List.of();

    @Override
    public void execute() {
        if (skip) {
            log.info("ike:changeset-compact skipped (ike.changeset.skip=true)");
            return;
        }
        ChangeSetToolSupport.Parameters parameters = new ChangeSetToolSupport.Parameters(file, target, implementation,
                fork, forkJvmArguments, classesDirectory, buildDirectory);
        if (knowledgeBases.isEmpty()) {
            ChangeSetToolSupport.run(log, session, project, IkeGoal.NAME_CHANGESET_COMPACT, ChangeSetCompactor.class,
                    true, parameters);
            return;
        }
        Path changeSet = Path.of(file);
        if (!Files.isRegularFile(changeSet)) {
            throw new MojoException("Change set does not exist: " + changeSet);
        }
        if (target == null || target.isBlank()) {
            throw new MojoException("ike:changeset-compact writes a file: set ike.changeset.target");
        }
        List<ArtifactInput> bases = new ArrayList<>();
        for (String spec : knowledgeBases) {
            bases.add(resolve(spec.strip()));
        }
        ChangeSetToolSupport.run(log, session, project, IkeGoal.NAME_CHANGESET_COMPACT, ChangeSetCompactor.class,
                new ChangeSetRequest(changeSet, Optional.of(Path.of(target)), Set.of(), bases), parameters);
    }

    /** A knowledge base by coordinate (two or more colons) or by file. */
    private ArtifactInput resolve(String spec) {
        if (spec.chars().filter(c -> c == ':').count() >= 2) {
            try {
                DownloadedArtifact artifact = session.resolveArtifact(session.createArtifactCoordinates(spec));
                return new ArtifactInput(ArtifactInput.Role.PB, artifact.getPath())
                        .withCoordinate(spec, artifact.getVersion().toString());
            } catch (Exception e) {
                throw new MojoException("Cannot resolve knowledge base " + spec, e);
            }
        }
        Path base = Path.of(spec);
        if (!Files.isRegularFile(base)) {
            throw new MojoException("Knowledge base file does not exist: " + base);
        }
        return new ArtifactInput(ArtifactInput.Role.PB, base);
    }
}
