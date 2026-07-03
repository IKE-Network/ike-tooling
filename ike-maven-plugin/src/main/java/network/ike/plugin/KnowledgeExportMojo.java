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

import org.apache.maven.api.PathScope;
import org.apache.maven.api.ProducedArtifact;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.services.DependencyResolver;
import org.apache.maven.api.services.DependencyResolverResult;
import org.apache.maven.api.services.ProjectManager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Exports a ledger-form knowledge set as its protobuf change-set artifact: composes the
 * project's {@code KnowledgeSetSource}, replays the session into a fresh ephemeral
 * store, exports the store, and attaches the file as the {@code changeset} classifier —
 * the released form of the set. A starter set is exactly this artifact applied to an
 * empty base.
 *
 * <p>The exporter lives tinkar-side ({@code dev.ikm.tinkar.entity.builder.ChangeSetMain})
 * because this plugin stays tinkar-free (the foundation boundary): the goal builds a
 * classloader over the project's {@code MAIN_RUNTIME} dependency classpath — plus the
 * project's own classes directory — and invokes the entry point reflectively,
 * in-process. Unlike {@code ike:knowledge-bindings}, export replays into a store, so the
 * project must carry the tinkar ephemeral/entity/executor providers at runtime scope and
 * supply their legacy {@code META-INF/services} controller registrations in its own
 * resources (tinkar providers declare services in {@code module-info} only; the goal
 * runs on the classpath).
 *
 * <p>Typical use — a {@code *-changeset} module depending on its {@code *-terms} ledger
 * module plus the providers:
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

    /** The stable tinkar-side entry point — change in lockstep with tinkar-core. */
    static final String CHANGESET_MAIN = "dev.ikm.tinkar.entity.builder.ChangeSetMain";

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
     * Optional: when absent, exactly one implementation must be discoverable via
     * {@code META-INF/services} on the project's dependency classpath.
     */
    @Parameter(property = "ike.knowledgeExport.sourceClass")
    String sourceClass;

    /**
     * The project's classes/resources directory, included on the export classpath so the
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
     * Resolves the project's runtime dependency classpath, invokes the tinkar-side
     * exporter in a classloader over it, and attaches the change-set artifact.
     *
     * @throws MojoException if the classpath cannot be resolved, the exporter entry
     *                       point is absent, export fails, or the output file was not
     *                       produced
     */
    @Override
    public void execute() {
        if (skip) {
            getLog().info("ike:knowledge-export skipped (ike.knowledgeExport.skip=true)");
            return;
        }

        DependencyResolverResult resolved = session.getService(DependencyResolver.class)
                .resolve(session, project, PathScope.MAIN_RUNTIME);
        List<URL> classpath = new ArrayList<>();
        try {
            Path classesDir = Path.of(classesDirectory);
            if (Files.isDirectory(classesDir)) {
                classpath.add(classesDir.toUri().toURL());
            }
            for (Path path : resolved.getPaths()) {
                classpath.add(path.toUri().toURL());
            }
        } catch (Exception e) {
            throw new MojoException("Cannot assemble export classpath", e);
        }
        getLog().debug("knowledge-export classpath: " + classpath);

        List<String> args = new ArrayList<>(List.of(outputFile));
        if (sourceClass != null && !sourceClass.isBlank()) {
            args.add(sourceClass);
        }

        ClassLoader originalContext = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader("ike-knowledge-export",
                classpath.toArray(new URL[0]), ClassLoader.getPlatformClassLoader())) {
            Thread.currentThread().setContextClassLoader(loader);
            Class<?> mainClass = Class.forName(CHANGESET_MAIN, true, loader);
            Method main = mainClass.getMethod("main", String[].class);
            main.invoke(null, (Object) args.toArray(new String[0]));
        } catch (ClassNotFoundException e) {
            throw new MojoException(CHANGESET_MAIN + " is not on the project's dependency"
                    + " classpath — the ledger dependency chain must include dev.ikm.tinkar:entity"
                    + " (with the knowledge-set builder API)", e);
        } catch (InvocationTargetException e) {
            throw new MojoException("Change-set export failed: "
                    + e.getCause().getMessage(), e.getCause());
        } catch (MojoException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoException("Change-set export failed", e);
        } finally {
            Thread.currentThread().setContextClassLoader(originalContext);
        }

        Path produced = Path.of(outputFile);
        if (!Files.isRegularFile(produced)) {
            throw new MojoException("Change-set export produced no file at " + produced);
        }

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
}
