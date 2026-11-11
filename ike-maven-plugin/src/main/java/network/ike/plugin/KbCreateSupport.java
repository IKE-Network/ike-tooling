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

import network.ike.plugin.scaffold.DirectoryTemplateSource;
import network.ike.plugin.scaffold.ManifestEntry;
import network.ike.plugin.scaffold.ModelAdapters;
import network.ike.plugin.scaffold.PathResolver;
import network.ike.plugin.scaffold.ScaffoldApplier;
import network.ike.plugin.scaffold.ScaffoldLockfile;
import network.ike.plugin.scaffold.ScaffoldLockfileIo;
import network.ike.plugin.scaffold.ScaffoldManifest;
import network.ike.plugin.scaffold.ScaffoldMojoSupport;
import network.ike.plugin.scaffold.ScaffoldPlan;
import network.ike.plugin.scaffold.ScaffoldPlanner;
import network.ike.plugin.scaffold.ScaffoldScope;
import network.ike.plugin.scaffold.TierHandlers;
import org.apache.maven.api.Session;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Shared mechanics of the {@code ike:kb-create-*} goals (IKE-Network/ike-issues#1370):
 * the {@code kb/} templates and the project scaffold both come from the
 * {@code ike-build-standards} scaffold zip of this plugin's own version, the one source
 * the starter-set genesis and {@code ike:scaffold-publish} read too.
 */
final class KbCreateSupport {

    /** The template directory inside the scaffold artifact. */
    static final String TEMPLATE_ROOT = "kb";

    private KbCreateSupport() {
    }

    /**
     * Unpacks the {@code ike-build-standards} scaffold zip matching this plugin's version.
     *
     * @param session the Maven session, for artifact resolution
     * @param into    an empty directory to unpack into
     * @return the scaffold tree's root, holding {@code scaffold-manifest.yaml}
     * @throws MojoException if the artifact cannot be resolved or unpacked
     */
    static Path unpackScaffold(Session session, Path into) {
        String coordinates = "network.ike.tooling:ike-build-standards:zip:scaffold:"
                + StarterSetCreateSupport.pluginVersion();
        Path zipPath;
        try {
            zipPath = session.resolveArtifact(session.createArtifactCoordinates(coordinates)).getPath();
        } catch (RuntimeException e) {
            throw new MojoException("Cannot resolve the standards scaffold artifact " + coordinates
                    + " carrying the knowledge-base templates", e);
        }
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            Path root = into.toAbsolutePath().normalize();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path target = root.resolve(entry.getName()).normalize();
                if (!target.startsWith(root)) {
                    throw new MojoException("The scaffold artifact holds an entry outside its root: "
                            + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return root;
        } catch (IOException e) {
            throw new MojoException("Cannot unpack the standards scaffold artifact " + zipPath, e);
        }
    }

    /**
     * Reads the {@code kb/} templates of a scaffold tree.
     *
     * @param scaffoldRoot the scaffold tree's root
     * @return template path within {@code kb/} to its text, sorted
     * @throws MojoException if the tree has no {@code kb/} templates
     */
    static Map<String, String> templates(Path scaffoldRoot) {
        Path root = scaffoldRoot.resolve(TEMPLATE_ROOT);
        if (!Files.isDirectory(root)) {
            throw new MojoException("The scaffold at " + scaffoldRoot + " carries no knowledge-base"
                    + " templates (kb/) — ike-build-standards and ike-maven-plugin versions must match");
        }
        Map<String, String> templates = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                templates.put(relative, Files.readString(file, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new MojoException("Cannot read the knowledge-base templates under " + root, e);
        }
        return templates;
    }

    /**
     * The {@code ike-parent} version the scaffold tested its standards with: the default
     * parent of a new project.
     *
     * @param scaffoldRoot the scaffold tree's root
     * @return the version
     * @throws MojoException if the manifest names no foundation parent
     */
    static String foundationParentVersion(Path scaffoldRoot) {
        ScaffoldManifest manifest = ScaffoldMojoSupport.loadManifest(scaffoldRoot);
        if (manifest.foundation() == null || manifest.foundation().parent() == null) {
            throw new MojoException("The scaffold manifest names no foundation parent; set -DparentVersion");
        }
        return manifest.foundation().parent().version();
    }

    /**
     * The files the project scope of the scaffold writes, by project-relative path: the
     * wrapper, {@code .mvn}, {@code .gitignore}, {@code CLAUDE.md}, {@code README.md}.
     *
     * @param scaffoldRoot the scaffold tree's root
     * @return the paths
     */
    static List<String> projectScaffoldFiles(Path scaffoldRoot) {
        ScaffoldManifest manifest = ScaffoldMojoSupport.loadManifest(scaffoldRoot);
        List<String> paths = new ArrayList<>();
        for (ManifestEntry entry : manifest.entriesInScope(ScaffoldScope.PROJECT)) {
            paths.add(entry.dest());
        }
        paths.add(".ike/scaffold.lock");
        return paths;
    }

    /**
     * Applies the scaffold's project scope to a new project, as {@code ike:scaffold-publish}
     * does, and writes its lockfile. The user scope ({@code ~/.gitconfig},
     * {@code ~/.m2/settings.xml}) is left alone: creating a project changes nothing outside
     * it.
     *
     * @param scaffoldRoot the scaffold tree's root
     * @param projectRoot  the new project
     * @param userHome     the user's home, which the manifest's paths resolve against
     * @return the project-relative paths the scaffold wrote, lockfile included
     */
    static List<String> applyProjectScaffold(Path scaffoldRoot, Path projectRoot, Path userHome) {
        ScaffoldManifest manifest = ScaffoldMojoSupport.loadManifest(scaffoldRoot);
        PathResolver resolver = new PathResolver(userHome, projectRoot);
        ScaffoldPlanner planner = new ScaffoldPlanner(new TierHandlers(), new ModelAdapters());
        Path lockPath = ScaffoldMojoSupport.projectLockfilePath(projectRoot);
        ScaffoldLockfile lockfile = ScaffoldMojoSupport.loadLockfileOrEmpty(lockPath);
        ScaffoldPlan plan = planner.plan(manifest, lockfile, ScaffoldScope.PROJECT, resolver,
                new DirectoryTemplateSource(scaffoldRoot));
        ScaffoldLockfile applied = new ScaffoldApplier().apply(plan, lockfile);
        ScaffoldLockfileIo.write(applied, lockPath);
        List<String> written = new ArrayList<>();
        for (String path : projectScaffoldFiles(scaffoldRoot)) {
            if (Files.exists(projectRoot.resolve(path))) {
                written.add(path);
            }
        }
        return written;
    }

    /**
     * Runs a command in a directory, logging its output, and fails on a non-zero exit.
     *
     * @param log       where its output goes
     * @param directory where it runs
     * @param command   the command and its arguments
     * @throws MojoException if it cannot run or exits non-zero
     */
    static void run(Log log, Path directory, String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                while (line != null) {
                    log.info("[kb-create] " + line);
                    line = reader.readLine();
                }
            }
            int exit = process.waitFor();
            if (exit != 0) {
                throw new MojoException("Step failed (exit " + exit + "): " + String.join(" ", command));
            }
        } catch (IOException e) {
            throw new MojoException("Cannot run: " + String.join(" ", command), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoException("Interrupted running: " + String.join(" ", command), e);
        }
    }
}
