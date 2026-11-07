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

import network.ike.plugin.adoc.AdocCoordinates;
import network.ike.plugin.adoc.AdocLibrary;
import network.ike.plugin.adoc.AdocManifest;
import network.ike.plugin.adoc.AdocUnion;
import network.ike.plugin.adoc.ReactorLayout;
import network.ike.plugin.adoc.RootConfig;
import org.apache.maven.api.Dependency;
import org.apache.maven.api.Node;
import org.apache.maven.api.PathScope;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.services.ProjectManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Unpacks every topic library a module declares into one AsciiDoc union per repository,
 * {@code <reactor root>/target/generated-sources/adoc}, and checks that the module's own
 * includes resolve there (IKE-Network/ike-issues#1342; design topic {@code dev-adoc-packages}
 * in ike-lab-documents). It replaces the stock {@code unpack-dependencies} execution for
 * {@code adoc} zips.
 *
 * <p>A library lays its root out as packages, {@code <group>/<domain>/<topic>.adoc}, and ships
 * a manifest at {@code META-INF/ike/<group>/<artifact>/manifest.yaml} listing them. The goal:
 * <ul>
 *   <li>resolves the module's dependencies with classifier {@code adoc} and type {@code zip},
 *       transitively, as {@code unpack-dependencies} does;</li>
 *   <li>copies an external library's content from its zip, and links the package directories
 *       of a library that is a module of the same reactor to its {@code src/docs/asciidoc},
 *       so an edit reached through an include changes the real source
 *       ({@code ike.adoc.link}, default true). Whether IntelliJ resolves includes, previews
 *       and navigates through such a link is to be confirmed by KEC on one machine;</li>
 *   <li>treats a zip without a manifest as a legacy library: refused, naming the artifact,
 *       unless {@code ike.adoc.legacy=true}, which unpacks it into the legacy per-module
 *       directory as before;</li>
 *   <li>refuses two libraries that ship one package, naming both;</li>
 *   <li>is idempotent from every module, so a {@code -pl} build works: an unchanged library
 *       is not rewritten, and an entry whose library no module declares anymore is left
 *       alone, since the union is per repository and not per module;</li>
 *   <li>verifies that every {@code include::{adoc}/...} and {@code include::{topics}/...} in
 *       the module's {@code src/docs/asciidoc} resolves in the union, and fails the build for
 *       one that does not with the exact {@code <dependency>} block to add, found in the
 *       union's manifests and then in the local repository by the group prefix of the path.
 *       It never fetches an undeclared library;</li>
 *   <li>sets the project properties {@code adoc} and {@code topics} to the union, so the
 *       asciidoctor plugin's attributes can reference them. While a legacy library was
 *       unpacked, {@code topics} names the legacy directory instead, so the old include
 *       lines keep resolving until the last consumer migrates.</li>
 * </ul>
 *
 * <p>The module itself, when its own source root has the package layout, is linked into the
 * union too, so a library's own {@code {adoc}} includes resolve in a build of that module
 * alone.
 *
 * <p>Typical wiring, in ike-parent (IKE-Network/ike-issues#1343):
 *
 * <pre>{@code
 * <execution>
 *     <id>adoc-union</id>
 *     <phase>generate-sources</phase>
 *     <goals><goal>adoc-union</goal></goals>
 * </execution>
 * }</pre>
 *
 * <p>Modules of a {@code -T} reactor share the union, so the goal serializes its writes
 * JVM-wide.
 *
 * @since 276
 */
@Mojo(name = IkeGoal.NAME_ADOC_UNION,
      defaultPhase = "generate-sources")
public class AdocUnionMojo implements org.apache.maven.api.plugin.Mojo {

    /** The project property set to the union directory, which the {@code {adoc}} attribute reads. */
    public static final String PROPERTY_ADOC = "adoc";

    /** The project property set for the {@code {topics}} alias, which the migration keeps. */
    public static final String PROPERTY_TOPICS = "topics";

    /** The AsciiDoc source root under a module. */
    static final String SOURCE_ROOT = "src/docs/asciidoc";

    private static final ReentrantLock UNION_LOCK = new ReentrantLock();

    /** Creates this goal instance. */
    public AdocUnionMojo() {}

    @Inject
    private Log log;

    @Inject
    private Session session;

    @Inject
    private Project project;

    /**
     * The union directory, one per repository. Defaults to
     * {@code <reactor root>/target/generated-sources/adoc}: the reactor root is the directory
     * holding {@code .mvn} or the pom flagged {@code root="true"}. Three levels under the root
     * is deliberate: IntelliJ registers every immediate subfolder of
     * {@code target/generated-sources} as a source root, which is what gets the union
     * indexed for anchor navigation.
     */
    @Parameter(property = "ike.adoc.union")
    String unionDirectory;

    /**
     * Link the package directories of a library that is a module of the same reactor to its
     * {@code src/docs/asciidoc}, rather than copying them. Off, the member's tree is copied
     * and kept in sync.
     */
    @Parameter(property = "ike.adoc.link", defaultValue = "true")
    boolean link;

    /**
     * Unpack a zip that ships no manifest, a legacy library, into
     * {@link #legacyDirectory} as the stock unpack did. Off, such a zip fails the build.
     */
    @Parameter(property = "ike.adoc.legacy", defaultValue = "false")
    boolean legacy;

    /**
     * Where a legacy library is unpacked when {@link #legacy} is on: the per-module
     * directory the stock unpack used.
     */
    @Parameter(property = "ike.adoc.legacyDirectory",
               defaultValue = "${project.build.directory}/generated-sources/asciidoc/topics-asciidoc")
    String legacyDirectory;

    /**
     * The module's AsciiDoc source root, whose includes are verified against the union.
     */
    @Parameter(property = "ike.adoc.sourceDirectory", defaultValue = "${project.basedir}/" + SOURCE_ROOT)
    String sourceDirectory;

    /**
     * Verify that every {@code {adoc}} and {@code {topics}} include in the source root
     * resolves in the union, failing the build for one that does not.
     */
    @Parameter(property = "ike.adoc.verifyIncludes", defaultValue = "true")
    boolean verifyIncludes;

    /** Skip the goal. The properties are then not set. */
    @Parameter(property = "ike.adoc.skip", defaultValue = "false")
    boolean skip;

    /**
     * Resolves the declared libraries, updates the union, verifies the module's includes and
     * sets the {@code adoc} and {@code topics} properties.
     *
     * @throws MojoException on a legacy library outside legacy mode, two libraries shipping one
     *                       package, an include that does not resolve, or a file that cannot
     *                       be written
     */
    @Override
    public void execute() {
        if (skip) {
            log.info("ike:adoc-union skipped (ike.adoc.skip=true)");
            return;
        }
        Path union = resolveUnionDirectory();
        Path legacyDir = Path.of(legacyDirectory).toAbsolutePath().normalize();
        Path source = Path.of(sourceDirectory).toAbsolutePath().normalize();

        List<AdocLibrary> libraries = declaredLibraries();
        selfLibrary(source).ifPresent(libraries::add);

        AdocUnion.Result result;
        int verified = 0;
        boolean legacyUsed;
        UNION_LOCK.lock();
        try {
            AdocUnion adocUnion = new AdocUnion(union, legacyDir, link, legacy, log);
            result = adocUnion.update(libraries);
            if (verifyIncludes) {
                verified = adocUnion.verifyIncludes(source, session.getLocalRepository().getPath());
            }
            legacyUsed = adocUnion.legacyUnpacked();
            installRootConfig();
        } finally {
            UNION_LOCK.unlock();
        }

        ProjectManager projectManager = session.getService(ProjectManager.class);
        projectManager.setProperty(project, PROPERTY_ADOC, union.toString());
        projectManager.setProperty(project, PROPERTY_TOPICS, (legacyUsed ? legacyDir : union).toString());

        log.info("ike:adoc-union: " + libraries.size() + " libraries in " + union + " (" + result.summary()
                + "); " + verified + " includes verified; " + PROPERTY_ADOC + "=" + union
                + (legacyUsed ? "; " + PROPERTY_TOPICS + "=" + legacyDir : ""));
    }

    /**
     * The repository's one {@code .asciidoctorconfig}, from the classifier zip the doc pipeline
     * declares. Here, under the union's lock, because every module with AsciiDoc sources would
     * write the one file, and a stock unpack in two modules at once fails a {@code -T} build.
     * A module that does not declare the zip leaves the file as it is.
     */
    private void installRootConfig() {
        List<Node> nodes = RuntimeClasspathResolver.serialized(session, () -> session.flattenDependencies(
                session.collectDependencies(project, PathScope.MAIN_COMPILE), PathScope.MAIN_COMPILE));
        for (Node node : nodes) {
            Dependency dependency = node.getDependency();
            if (dependency == null || !RootConfig.CLASSIFIER.equals(dependency.getClassifier())) {
                continue;
            }
            try {
                Path zip = RuntimeClasspathResolver.serialized(session, () -> session.resolveArtifact(dependency).getPath());
                if (RootConfig.install(zip, reactorRoot())) {
                    log.info("ike:adoc-union: wrote " + reactorRoot().resolve(RootConfig.FILE_NAME));
                }
            } catch (java.io.IOException | RuntimeException e) {
                throw new MojoException("Cannot install " + RootConfig.FILE_NAME + ": " + e.getMessage(), e);
            }
            return;
        }
    }

    private Path resolveUnionDirectory() {
        if (unionDirectory != null && !unionDirectory.isBlank()) {
            return Path.of(unionDirectory).toAbsolutePath().normalize();
        }
        return reactorRoot().resolve("target").resolve("generated-sources").resolve("adoc").toAbsolutePath().normalize();
    }

    /**
     * The module's dependencies with classifier {@code adoc} and type {@code zip}, transitive
     * ones included. One that is a module of this reactor, with a source root on disk, is a
     * reactor member: found among the session's projects, or, in a {@code -pl} build that
     * left it out, by its pom under the reactor root. Every other is resolved to its zip.
     */
    private List<AdocLibrary> declaredLibraries() {
        Path root = reactorRoot();
        // Collection goes through the plugin's serialized entry point — Maven 4 rc-5's
        // resolver cache is not thread-safe under -T (IKE-Network/ike-issues#901).
        List<Node> nodes = RuntimeClasspathResolver.serialized(session, () -> session.flattenDependencies(
                session.collectDependencies(project, PathScope.MAIN_COMPILE), PathScope.MAIN_COMPILE));
        List<AdocLibrary> libraries = new ArrayList<>();
        for (Node node : nodes) {
            Dependency dependency = node.getDependency();
            if (dependency == null
                    || !AdocCoordinates.CLASSIFIER.equals(dependency.getClassifier())
                    || !AdocCoordinates.TYPE.equals(dependency.getExtension())) {
                continue;
            }
            AdocCoordinates coordinates = new AdocCoordinates(dependency.getGroupId(),
                    dependency.getArtifactId(), dependency.getVersion().toString());
            Optional<Path> memberDir = memberBasedir(root, coordinates);
            if (memberDir.isPresent()) {
                Path sourceRoot = memberDir.get().resolve(SOURCE_ROOT);
                if (Files.isDirectory(sourceRoot)) {
                    libraries.add(AdocLibrary.reactor(coordinates, sourceRoot));
                    continue;
                }
                log.warn("ike:adoc-union: reactor member " + coordinates.gav() + " has no " + sourceRoot
                        + "; resolving it from the repository");
            }
            Path zip;
            try {
                zip = session.resolveArtifact(dependency).getPath();
            } catch (RuntimeException e) {
                throw new MojoException("Cannot resolve the topic library " + coordinates.gav() + ": "
                        + e.getMessage(), e);
            }
            libraries.add(AdocLibrary.external(coordinates, zip));
        }
        return libraries;
    }

    /**
     * The directory of the reactor module with these coordinates: one of the session's
     * projects at the same version, else one found by its pom under the reactor root, which
     * is how a sibling left out of a {@code -pl} build is still linked rather than copied
     * from a stale zip.
     */
    private Optional<Path> memberBasedir(Path root, AdocCoordinates coordinates) {
        Optional<Project> inSession = session.getProjects().stream()
                .filter(p -> p.getGroupId().equals(coordinates.groupId())
                        && p.getArtifactId().equals(coordinates.artifactId()))
                .findFirst();
        if (inSession.isPresent()) {
            Project member = inSession.get();
            if (member.getVersion().equals(coordinates.version())) {
                return Optional.of(member.getBasedir());
            }
            log.warn("ike:adoc-union: " + coordinates.gav() + " is declared at a version other than"
                    + " the reactor's " + member.getVersion() + "; resolving it from the repository");
            return Optional.empty();
        }
        return ReactorLayout.memberBasedir(root, coordinates.groupId(), coordinates.artifactId());
    }

    private Path reactorRoot() {
        try {
            return project.getRootDirectory();
        } catch (IllegalStateException e) {
            return session.getTopDirectory();
        }
    }

    /** This module as a library of the union, when its own source root has the package layout. */
    private Optional<AdocLibrary> selfLibrary(Path source) {
        AdocCoordinates self = new AdocCoordinates(project.getGroupId(), project.getArtifactId(), project.getVersion());
        if (Files.isDirectory(source) && !AdocManifest.fromSourceTree(self, source).packages().isEmpty()) {
            return Optional.of(AdocLibrary.reactor(self, source));
        }
        return Optional.empty();
    }
}
