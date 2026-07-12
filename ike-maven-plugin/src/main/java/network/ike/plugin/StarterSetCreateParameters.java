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

import org.apache.maven.api.plugin.annotations.Parameter;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * The shared input surface of the {@code ike:starter-set-create-*} pair — required:
 * the set name and the groupId; everything else curated-but-defaulted or derived
 * (IKE-Network/ike-issues#866).
 */
abstract class StarterSetCreateParameters {

    /**
     * The set's human name — the one decision that drives the whole derived family,
     * e.g. {@code "CQL Clause"}. Required.
     */
    @Parameter(property = "setName")
    String setName;

    /**
     * The Maven groupId placing the set in its domain family, e.g.
     * {@code network.ike.cql}. Required — there is no single convention to derive it.
     */
    @Parameter(property = "groupId")
    String groupId;

    /**
     * The semantic tag, without parentheses. Defaults to the compact class name of the
     * set name — reviewed in the draft as a headline item, because the tag lives in
     * every birth FQN forever.
     */
    @Parameter(property = "semanticTag")
    String semanticTag;

    /**
     * The set UUID. Absent (the default), publish mints a fresh one and prints it
     * prominently; supply one for pre-allocated identities. Draft never mints.
     */
    @Parameter(property = "setUuid")
    String setUuid;

    /**
     * The generated project's version.
     */
    @Parameter(property = "version", defaultValue = "1-SNAPSHOT")
    String version = "1-SNAPSHOT";

    /**
     * The ike-parent version the generated project inherits.
     */
    @Parameter(property = "parentVersion", defaultValue = "131")
    String parentVersion = "131";

    /**
     * The engine version property value. INTERIM default: the chronology-builder
     * sibling snapshot; flips to the released version when the feature merges.
     */
    @Parameter(property = "chronologyStoreVersion",
               defaultValue = "1.127.2-chronology-builder-SNAPSHOT")
    String chronologyStoreVersion = "1.127.2-chronology-builder-SNAPSHOT";

    /**
     * The ike-knowledge-provider version. INTERIM default until the provider releases.
     */
    @Parameter(property = "providerVersion", defaultValue = "1-chronology-builder-SNAPSHOT")
    String providerVersion = "1-chronology-builder-SNAPSHOT";

    /**
     * The koncept-asciidoc-extension version the doc module pins.
     */
    @Parameter(property = "konceptExtensionVersion", defaultValue = "90-SNAPSHOT")
    String konceptExtensionVersion = "90-SNAPSHOT";

    /**
     * The kb module's base input line ({@code ROLE spec}). Defaults to the current
     * starter data; flips to the IKE starter set once it exists.
     */
    @Parameter(property = "baseArtifact",
               defaultValue = "PB dev.ikm.data.tinkar:tinkar-starter-data:zip:reasoned-pb:20251009")
    String baseArtifact = "PB dev.ikm.data.tinkar:tinkar-starter-data:zip:reasoned-pb:20251009";

    /**
     * The directory to create the project in. Defaults to the artifact root under the
     * invocation directory.
     */
    @Parameter(property = "targetDirectory")
    String targetDirectory;

    /**
     * Builds the derived plan from these inputs.
     *
     * @param uuidForPlan the UUID the plan carries (a placeholder in draft; the minted
     *                    or supplied value in publish)
     * @return the plan
     */
    StarterSetPlan plan(String uuidForPlan) {
        String uuid = (setUuid == null || setUuid.isBlank()) ? uuidForPlan : setUuid.strip();
        return new StarterSetPlan(setName, groupId, semanticTag, uuid, version,
                parentVersion, chronologyStoreVersion, providerVersion,
                StarterSetCreateSupport.pluginVersion(), konceptExtensionVersion,
                baseArtifact,
                LocalDate.now(ZoneOffset.UTC) + "T00:00:00Z");
    }

    /**
     * Resolves the target directory for the generated project.
     *
     * @param plan the derived plan
     * @return the absolute target directory
     */
    Path targetDirectory(StarterSetPlan plan) {
        if (targetDirectory != null && !targetDirectory.isBlank()) {
            return Path.of(targetDirectory).toAbsolutePath();
        }
        return Path.of(System.getProperty("user.dir"), plan.token("artifactRoot")).toAbsolutePath();
    }
}
