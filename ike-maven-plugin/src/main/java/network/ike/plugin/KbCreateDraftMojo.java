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

import org.apache.maven.api.Session;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Previews a new knowledge-base project, the side-effect-free half of the
 * {@code ike:kb-create-*} pair (IKE-Network/ike-issues#1370): the coordinates, every layer
 * pinned to the exact deploy it will be built from and its size, whether a Rocks store is
 * built and why, the heap, where it deploys, and every file publish would write. The
 * repository is read to pin the layers; nothing is written. Rerun as
 * {@code kb-create-publish} with the same inputs to create exactly what this draft shows.
 *
 * <pre>{@code
 * mvn ike:kb-create-draft -Dname=my-kb \
 *     -Dlayers=network.ike.data:snomedct-kb:zip:unreasoned-pb:20260601.2-SNAPSHOT
 * }</pre>
 *
 * @since 280
 */
@Mojo(name = IkeGoal.NAME_KB_CREATE_DRAFT, projectRequired = false)
public class KbCreateDraftMojo extends KbCreateParameters implements org.apache.maven.api.plugin.Mojo {

    /** Creates this goal instance. */
    public KbCreateDraftMojo() {}

    @Inject
    private org.apache.maven.api.plugin.Log log;

    @Inject
    private Session session;

    /**
     * Prints the plan.
     *
     * @throws MojoException if a parameter is malformed, a layer cannot be pinned, or the
     *                       templates cannot be read
     */
    @Override
    public void execute() {
        Path scaffold = null;
        try {
            scaffold = Files.createTempDirectory("ike-kb-create-");
            Path root = KbCreateSupport.unpackScaffold(session, scaffold);
            KbCreatePlan plan = plan(session, log, root);
            Path target = target();
            Map<String, String> files = plan.render(KbCreateSupport.templates(root));
            for (String line : plan.report(target.toString())) {
                log.info(line);
            }
            log.info("  Files:");
            for (String file : files.keySet()) {
                log.info("    " + file);
            }
            for (String file : KbCreateSupport.projectScaffoldFiles(root)) {
                log.info("    " + file + " (standards scaffold)");
            }
            if (Files.isRegularFile(target.resolve("pom.xml")) || Files.exists(target.resolve(".git"))) {
                log.warn("  " + target + " already holds a project; publish will refuse it.");
            }
            log.info("Draft only — run ike:kb-create-publish with the same inputs to create it.");
        } catch (IOException e) {
            throw new MojoException("Cannot unpack the standards scaffold", e);
        } finally {
            deleteQuietly(scaffold);
        }
    }

    /**
     * Removes a temporary tree, ignoring what cannot be removed.
     *
     * @param root the tree, or {@code null}
     */
    static void deleteQuietly(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> deepestFirst = paths.sorted(Comparator.reverseOrder()).toList();
            for (Path path : deepestFirst) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // A temporary folder the system removes in time.
        }
    }
}
