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
import org.apache.maven.api.plugin.MojoException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Shared mechanics of the starter-set genesis goals: loading the templates from the
 * standards scaffold artifact (the same channel the conformance scaffold consumes —
 * one template source, two lifecycle moments) and rendering the plan report the draft
 * and publish goals both print.
 */
final class StarterSetCreateSupport {

    /** The template directory inside the scaffold artifact. */
    static final String TEMPLATE_ROOT = "starter-set/";

    private StarterSetCreateSupport() {
    }

    /**
     * Loads the starter-set templates from the standards scaffold zip matching this
     * plugin's own version.
     *
     * @param session the Maven session, for artifact resolution
     * @return template-relative path to template text, in zip order
     * @throws MojoException if the scaffold artifact cannot be resolved or read
     */
    static Map<String, String> loadTemplates(Session session) {
        String coordinates = "network.ike.tooling:ike-build-standards:zip:scaffold:" + pluginVersion();
        Path zipPath;
        try {
            zipPath = session.resolveArtifact(session.createArtifactCoordinates(coordinates)).getPath();
        } catch (Exception e) {
            throw new MojoException("Cannot resolve the standards scaffold artifact "
                    + coordinates + " carrying the starter-set templates", e);
        }
        Map<String, String> templates = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getName().startsWith(TEMPLATE_ROOT)) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        templates.put(entry.getName().substring(TEMPLATE_ROOT.length()),
                                new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    }
                }
            }
        } catch (IOException e) {
            throw new MojoException("Cannot read the starter-set templates from " + zipPath, e);
        }
        if (templates.isEmpty()) {
            throw new MojoException("The scaffold artifact " + coordinates
                    + " carries no starter-set templates — standards and plugin versions must match");
        }
        return templates;
    }

    /**
     * This plugin's own version, read from its build descriptor — the lockstep key for
     * resolving the matching standards artifact.
     *
     * @return the plugin version
     * @throws MojoException if the descriptor is unreadable
     */
    static String pluginVersion() {
        String descriptor = "/META-INF/maven/network.ike.tooling/ike-maven-plugin/pom.properties";
        try (InputStream in = StarterSetCreateSupport.class.getResourceAsStream(descriptor)) {
            if (in == null) {
                throw new MojoException("Plugin build descriptor missing: " + descriptor);
            }
            Properties properties = new Properties();
            properties.load(in);
            return properties.getProperty("version");
        } catch (IOException e) {
            throw new MojoException("Cannot read the plugin build descriptor", e);
        }
    }

    /**
     * Renders the genesis plan — identical in draft and publish, so what was reviewed
     * is what happens.
     *
     * @param plan      the plan
     * @param templates the loaded templates
     * @param targetDir the directory the project lands in
     * @param mintFresh whether publish will mint a fresh UUID
     * @return the report text
     */
    static String report(StarterSetPlan plan, Map<String, String> templates,
                         Path targetDir, boolean mintFresh) {
        StringBuilder out = new StringBuilder();
        out.append("Starter set: ").append(plan.token("setName")).append('\n');
        out.append("  Semantic tag (permanent — in every birth FQN): (")
                .append(plan.token("tag")).append(")\n");
        out.append("  Set UUID ").append(mintFresh
                        ? "(will be minted at publish): " : "(supplied): ")
                .append(plan.token("setUuid")).append('\n');
        out.append("  Coordinates: ").append(plan.token("groupId")).append(':')
                .append(plan.token("artifactRoot")).append(':')
                .append(plan.token("version")).append('\n');
        out.append("  Package: ").append(plan.token("basePackage")).append('\n');
        out.append("  Directory: ").append(targetDir).append('\n');
        out.append("  Files:\n");
        for (String templatePath : templates.keySet()) {
            out.append("    ").append(plan.targetPath(templatePath)).append('\n');
        }
        out.append("  Next steps after publish:\n");
        out.append("    gh repo create <org>/").append(plan.token("artifactRoot"))
                .append(" --public\n");
        out.append("    git remote add origin <url> ; git push -u origin main\n");
        out.append("    mvn ws:add -Drepo=<url> -Dsubproject=").append(plan.token("artifactRoot"))
                .append("   (inside a workspace)\n");
        out.append("    mvn install   (ledger -> bindings, change set, glossary, KB at ~/Solor/")
                .append(plan.token("slug")).append("-kb)\n");
        out.append("  Pending curation: stamp author is TinkarTerm#USER until the curator-identity decision.\n");
        return out.toString();
    }
}
