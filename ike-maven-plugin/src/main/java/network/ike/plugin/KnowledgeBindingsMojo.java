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

import org.apache.maven.api.Language;
import org.apache.maven.api.PathScope;
import org.apache.maven.api.Project;
import org.apache.maven.api.ProjectScope;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates the bindings class for a ledger-form knowledge set — the {@code TinkarTerm}
 * idiom: one {@code EntityProxy} constant per declaration with its identity embedded as
 * a resolved UUID literal and its javadoc generated from the declaration's definition —
 * into {@code target/generated-sources/ike-knowledge}, registered as a compile source
 * root so it compiles the Maven way and autocompletes in any IDE that includes the
 * bindings artifact.
 *
 * <p>The generator itself lives tinkar-side ({@code
 * dev.ikm.tinkar.entity.builder.BindingsMain}) because this plugin stays tinkar-free
 * (the foundation boundary). This goal builds a classloader over the project's
 * {@code MAIN_RUNTIME} dependency classpath and invokes that one stable entry point
 * reflectively, in-process. The project's ledger dependency provides a
 * {@code KnowledgeSetSource} via {@code META-INF/services}; composition is store-free, so
 * no datastore or providers are needed on the classpath.
 *
 * <p>Typical use — a {@code *-bindings} module depending on its {@code *-terms} ledger
 * module:
 *
 * <pre>{@code
 * <plugin>
 *     <groupId>network.ike.tooling</groupId>
 *     <artifactId>ike-maven-plugin</artifactId>
 *     <executions>
 *         <execution>
 *             <goals><goal>knowledge-bindings</goal></goals>
 *             <configuration>
 *                 <packageName>network.ike.richsurface.bindings</packageName>
 *                 <className>RichSurfaceTerms</className>
 *             </configuration>
 *         </execution>
 *     </executions>
 * </plugin>
 * }</pre>
 *
 * @since 234
 */
@Mojo(name = IkeGoal.NAME_KNOWLEDGE_BINDINGS,
      defaultPhase = "generate-sources")
public class KnowledgeBindingsMojo implements org.apache.maven.api.plugin.Mojo {

    /** The stable tinkar-side entry point — change in lockstep with tinkar-core. */
    static final String BINDINGS_MAIN = "dev.ikm.tinkar.entity.builder.BindingsMain";

    /** Creates this goal instance. */
    public KnowledgeBindingsMojo() {}

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
     * Package of the generated bindings class.
     */
    @Parameter(property = "ike.knowledgeBindings.packageName", required = true)
    String packageName;

    /**
     * Simple name of the generated bindings class, for example {@code RichSurfaceTerms}.
     */
    @Parameter(property = "ike.knowledgeBindings.className", required = true)
    String className;

    /**
     * Fully qualified name of the {@code KnowledgeSetSource} implementation to compose.
     * Optional: when absent, exactly one implementation must be discoverable via
     * {@code META-INF/services} on the project's dependency classpath.
     */
    @Parameter(property = "ike.knowledgeBindings.sourceClass")
    String sourceClass;

    /**
     * Root directory for the generated sources; registered as a compile source root.
     */
    @Parameter(property = "ike.knowledgeBindings.outputDirectory",
               defaultValue = "${project.build.directory}/generated-sources/ike-knowledge")
    String outputDirectory;

    /**
     * Skip bindings generation.
     */
    @Parameter(property = "ike.knowledgeBindings.skip", defaultValue = "false")
    boolean skip;

    /**
     * Resolves the project's runtime dependency classpath, invokes the tinkar-side
     * generator in a classloader over it, and registers the generated-sources root.
     *
     * @throws MojoException if the classpath cannot be resolved, the generator entry
     *                       point is absent (the ledger dependency chain does not include
     *                       tinkar entity), or generation fails
     */
    @Override
    public void execute() {
        if (skip) {
            getLog().info("ike:knowledge-bindings skipped (ike.knowledgeBindings.skip=true)");
            return;
        }

        DependencyResolverResult resolved = session.getService(DependencyResolver.class)
                .resolve(session, project, PathScope.MAIN_RUNTIME);
        List<URL> classpath = new ArrayList<>();
        for (Path path : resolved.getPaths()) {
            try {
                classpath.add(path.toUri().toURL());
            } catch (Exception e) {
                throw new MojoException("Cannot convert classpath entry to URL: " + path, e);
            }
        }
        getLog().debug("knowledge-bindings classpath: " + classpath);

        List<String> args = new ArrayList<>(List.of(outputDirectory, packageName, className));
        if (sourceClass != null && !sourceClass.isBlank()) {
            args.add(sourceClass);
        }

        ClassLoader originalContext = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader("ike-knowledge-bindings",
                classpath.toArray(new URL[0]), ClassLoader.getPlatformClassLoader())) {
            Thread.currentThread().setContextClassLoader(loader);
            Class<?> mainClass = Class.forName(BINDINGS_MAIN, true, loader);
            Method main = mainClass.getMethod("main", String[].class);
            main.invoke(null, (Object) args.toArray(new String[0]));
        } catch (ClassNotFoundException e) {
            throw new MojoException(BINDINGS_MAIN + " is not on the project's dependency"
                    + " classpath — the ledger dependency chain must include dev.ikm.tinkar:entity"
                    + " (with the knowledge-set builder API)", e);
        } catch (InvocationTargetException e) {
            throw new MojoException("Bindings generation failed: "
                    + e.getCause().getMessage(), e.getCause());
        } catch (MojoException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoException("Bindings generation failed", e);
        } finally {
            Thread.currentThread().setContextClassLoader(originalContext);
        }

        Path sourceRoot = Path.of(outputDirectory);
        session.getService(ProjectManager.class)
                .addSourceRoot(project, ProjectScope.MAIN, Language.JAVA_FAMILY, sourceRoot);
        getLog().info("Generated " + packageName + "." + className
                + " into " + sourceRoot + " (registered as compile source root)");
    }
}
