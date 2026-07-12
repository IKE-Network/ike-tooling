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

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The genesis plan of a starter-set project: every name derived from the one human
 * decision (the set name), the expansion tokens, and the template-to-target file
 * mapping. Pure derivation — no I/O — so the plan is unit-testable and the draft and
 * publish goals render exactly the same facts.
 *
 * <p>Derivations from {@code setName} (each overridable at the goal): slug
 * ({@code cql-clause}), artifact root ({@code cql-clause-starter-knowledge}),
 * compact class name ({@code CqlClause}), semantic tag (defaults to the class name —
 * a headline item, never silently mechanical: it lives in every birth FQN forever),
 * and base package ({@code <groupId>.cqlclause}).
 */
final class StarterSetPlan {

    private final Map<String, String> tokens = new LinkedHashMap<>();

    /**
     * Builds the plan.
     *
     * @param setName                 the set's human name, e.g. {@code "CQL Clause"}
     * @param groupId                 the Maven groupId placing the set in its family
     * @param semanticTag             the semantic tag, or null for the derived default
     * @param setUuid                 the set UUID (already minted or supplied)
     * @param version                 the project version
     * @param parentVersion           the ike-parent version
     * @param chronologyStoreVersion  the engine version property value
     * @param providerVersion         the ike-knowledge-provider version
     * @param toolingVersion          the ike-maven-plugin version to pin
     * @param konceptExtensionVersion the koncept-asciidoc-extension version
     * @param baseArtifactSpec        the kb module's base input line
     * @param inceptionTime           the inception stamp's literal ISO time
     * @throws IllegalArgumentException if the set name or groupId is blank
     */
    StarterSetPlan(String setName, String groupId, String semanticTag, String setUuid,
                   String version, String parentVersion, String chronologyStoreVersion,
                   String providerVersion, String toolingVersion,
                   String konceptExtensionVersion, String baseArtifactSpec,
                   String inceptionTime) {
        if (setName == null || setName.isBlank()) {
            throw new IllegalArgumentException("A starter set requires -DsetName");
        }
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("A starter set requires -DgroupId"
                    + " — the groupId places the set in its domain family");
        }
        String slug = slugOf(setName);
        String className = classNameOf(setName);
        tokens.put("setName", setName.strip());
        tokens.put("groupId", groupId.strip());
        tokens.put("slug", slug);
        tokens.put("artifactRoot", slug + "-starter-knowledge");
        tokens.put("className", className);
        tokens.put("tag", (semanticTag == null || semanticTag.isBlank())
                ? className : semanticTag.strip());
        tokens.put("basePackage", groupId.strip() + "." + slug.replace("-", ""));
        tokens.put("setUuid", Objects.requireNonNull(setUuid, "setUuid"));
        tokens.put("version", version);
        tokens.put("parentVersion", parentVersion);
        tokens.put("chronologyStoreVersion", chronologyStoreVersion);
        tokens.put("providerVersion", providerVersion);
        tokens.put("toolingVersion", toolingVersion);
        tokens.put("konceptExtensionVersion", konceptExtensionVersion);
        tokens.put("baseArtifactSpec", baseArtifactSpec);
        tokens.put("inceptionTime", inceptionTime);
    }

    /**
     * Derives the kebab-case slug of a set name.
     *
     * @param setName the human name
     * @return the slug, e.g. {@code cql-clause}
     */
    static String slugOf(String setName) {
        String slug = setName.strip().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (slug.isEmpty()) {
            throw new IllegalArgumentException("Set name yields no usable slug: " + setName);
        }
        return slug;
    }

    /**
     * Derives the compact class name of a set name.
     *
     * @param setName the human name
     * @return the class name, e.g. {@code CqlClause}
     */
    static String classNameOf(String setName) {
        StringBuilder name = new StringBuilder();
        for (String word : setName.strip().split("[^A-Za-z0-9]+")) {
            if (!word.isEmpty()) {
                name.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) {
                    name.append(word.substring(1));
                }
            }
        }
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Set name yields no usable class name: " + setName);
        }
        return name.toString();
    }

    /**
     * One token's value.
     *
     * @param key the token name
     * @return the value
     */
    String token(String key) {
        return tokens.get(key);
    }

    /**
     * All tokens, in declaration order — the draft report's facts table.
     *
     * @return an unmodifiable view of the tokens
     */
    Map<String, String> tokens() {
        return java.util.Collections.unmodifiableMap(tokens);
    }

    /**
     * Expands every {@code @token@} in template text.
     *
     * @param template the template text
     * @return the expanded text
     * @throws IllegalStateException if an unknown token remains after expansion
     */
    String expand(String template) {
        String expanded = template;
        for (Map.Entry<String, String> token : tokens.entrySet()) {
            expanded = expanded.replace("@" + token.getKey() + "@", token.getValue());
        }
        java.util.regex.Matcher leftover =
                java.util.regex.Pattern.compile("@([a-zA-Z]+)@").matcher(expanded);
        if (leftover.find()) {
            throw new IllegalStateException("Unknown template token: @" + leftover.group(1) + "@");
        }
        return expanded;
    }

    /**
     * Maps a template path (relative, within {@code starter-set/}) to its target path
     * inside the generated project.
     *
     * @param templatePath the template-relative path
     * @return the project-relative target path
     */
    String targetPath(String templatePath) {
        String target = templatePath
                .replace("PACKAGE", tokens.get("basePackage").replace('.', '/'))
                .replace("Handles.java", tokens.get("className") + ".java")
                .replace("SetSource.java", tokens.get("className") + "Source.java")
                .replace("guide.adoc", tokens.get("slug") + "-guide.adoc");
        if (target.equals("gitignore")) {
            return ".gitignore";
        }
        if (target.startsWith("mvn/")) {
            return ".mvn/" + target.substring(4);
        }
        for (String module : new String[]{"terms", "bindings", "changeset", "doc", "kb"}) {
            if (target.equals(module + "/pom.xml") || target.startsWith(module + "/src/")) {
                return tokens.get("slug") + "-" + target;
            }
        }
        return target;
    }
}
