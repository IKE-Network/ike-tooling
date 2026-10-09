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

import network.ike.knowledge.spi.ChangeSetReport;
import network.ike.knowledge.spi.ChangeSetRequest;
import network.ike.knowledge.spi.IkeServiceBootstrap;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * What the four change set goals share: the request from the goal's parameters, the run
 * through the forked-JVM seam, and the report to the log, failing the build when the report
 * is not ok.
 */
final class ChangeSetToolSupport {

    private ChangeSetToolSupport() {
    }

    /** The parameters every change set goal takes. */
    record Parameters(String file, String target, String implementation, boolean fork, List<String> forkJvmArguments,
                      String classesDirectory, String buildDirectory) {
    }

    static void run(Log log, Session session, Project project, String goal, Class<?> service, boolean writesTarget,
                    Parameters parameters) {
        Path changeSet = Path.of(parameters.file());
        if (!Files.isRegularFile(changeSet)) {
            throw new MojoException("Change set does not exist: " + changeSet);
        }
        ChangeSetRequest request;
        if (writesTarget) {
            if (parameters.target() == null || parameters.target().isBlank()) {
                throw new MojoException("ike:" + goal + " writes a file: set ike.changeset.target");
            }
            request = ChangeSetRequest.of(changeSet, Path.of(parameters.target()));
        } else {
            request = ChangeSetRequest.of(changeSet);
        }
        Properties wire = request.toProperties();
        if (parameters.implementation() != null && !parameters.implementation().isBlank()) {
            wire.setProperty(IkeServiceBootstrap.IMPLEMENTATION_KEY, parameters.implementation());
        }
        Properties resultWire = new KnowledgeServiceRunner(log).run(service.getName(), wire,
                seamClasspath(session, project, parameters.classesDirectory()),
                Path.of(parameters.buildDirectory(), "ike-knowledge"), parameters.fork(), parameters.forkJvmArguments());
        ChangeSetReport report = ChangeSetReport.fromProperties(resultWire);
        log.info(report.summary());
        for (String line : report.lines()) {
            if (line.strip().startsWith("error")) {
                log.error(line);
            } else if (line.strip().startsWith("warning")) {
                log.warn(line);
            } else {
                log.info(line);
            }
        }
        if (!report.ok()) {
            throw new MojoException("ike:" + goal + " found errors in " + changeSet.getFileName()
                    + " — see the lines above (IKE-Network/ike-issues#1275)");
        }
    }

    private static List<Path> seamClasspath(Session session, Project project, String classesDirectory) {
        List<Path> classpath = new ArrayList<>();
        Path classesDir = Path.of(classesDirectory);
        if (Files.isDirectory(classesDir)) {
            classpath.add(classesDir);
        }
        classpath.addAll(RuntimeClasspathResolver.mainRuntimePaths(session, project));
        return classpath;
    }
}
