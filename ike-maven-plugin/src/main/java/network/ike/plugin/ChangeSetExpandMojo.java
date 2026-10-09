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

import network.ike.knowledge.spi.ChangeSetExpander;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.util.List;

/**
 * {@code ike:changeset-expand} — rewrite a format-3 change set in the format-2 layout, every reference by UUID, for a reader that knows no sequences.
 * The work is done by the {@link ChangeSetExpander} on the project's runtime classpath,
 * in a forked JVM by default; no store is opened.
 */
@Mojo(name = IkeGoal.NAME_CHANGESET_EXPAND)
public class ChangeSetExpandMojo implements org.apache.maven.api.plugin.Mojo {

    public ChangeSetExpandMojo() {
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

    @Override
    public void execute() {
        if (skip) {
            log.info("ike:changeset-expand skipped (ike.changeset.skip=true)");
            return;
        }
        ChangeSetToolSupport.run(log, session, project, IkeGoal.NAME_CHANGESET_EXPAND, ChangeSetExpander.class, true,
                new ChangeSetToolSupport.Parameters(file, target, implementation, fork, forkJvmArguments,
                        classesDirectory, buildDirectory));
    }
}
