package network.ike.workspace;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a {@code workspace.yaml} file into a typed {@link Manifest}.
 *
 * <p>SnakeYAML parses into raw Maps; this class maps the untyped
 * structure onto immutable Java records with validation.
 *
 * <p>As of #150, the manifest uses the {@code subprojects:} top-level
 * key. Legacy manifests with {@code components:} are rejected by
 * {@link #read(Path)} — run {@code mvn ws:align-publish} to migrate,
 * or call
 * {@link #migrateLegacySchemaIfNeeded(Path, java.util.function.Consumer)}
 * first as {@code ws:align-publish} does internally.
 */
public final class ManifestReader {

    private ManifestReader() {}

    /**
     * Read a workspace manifest from the given YAML file path.
     *
     * @param path path to workspace.yaml
     * @return the parsed manifest
     * @throws ManifestException if the file cannot be read, has invalid
     *                           structure, or uses the legacy
     *                           {@code components:} schema
     */
    public static Manifest read(Path path) {
        try (Reader reader = Files.newBufferedReader(path)) {
            return read(reader);
        } catch (IOException e) {
            throw new ManifestException("Cannot read " + path, e);
        }
    }

    /**
     * Read a workspace manifest from a Reader (useful for testing).
     *
     * @param reader YAML source
     * @return the parsed manifest
     * @throws ManifestException if the YAML has invalid structure or uses
     *                           the legacy {@code components:} schema
     */
    public static Manifest read(Reader reader) {
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(reader);
        if (root == null) {
            throw new ManifestException("Empty manifest");
        }
        // Hard-cut on the legacy schema (#150): the reader only accepts
        // the new `subprojects:` key. `ws:align-publish` migrates in-place
        // via migrateLegacySchemaIfNeeded before calling read().
        if (root.containsKey("components") && !root.containsKey("subprojects")) {
            throw new ManifestException(
                    "workspace.yaml uses legacy 'components:' schema. "
                            + "Run 'mvn ws:align-publish' to migrate to "
                            + "'subprojects:' (see #150).");
        }
        return parseManifest(root);
    }

    private static Manifest parseManifest(Map<String, Object> root) {
        String schemaVersion = stringField(root, "schema-version", "1.0");
        String generated = stringField(root, "generated", null);

        Defaults defaults = parseDefaults(mapField(root, "defaults"));
        // component-types: / subproject-types: is legacy; the concept of
        // a per-subproject type has been removed entirely. A present
        // section or per-subproject `type:` field is silently ignored;
        // ws:align-publish strips legacy structure from workspace.yaml.
        Map<String, Subproject> subprojects = parseSubprojects(
                mapField(root, "subprojects"), defaults);
        IdeSettings ide = parseIdeSettings(mapField(root, "ide"));
        // Schema 1.1 (#183): typed workspace-root coordinates. Absent on
        // legacy manifests; callers that need it check for null and
        // suggest ws:adopt-root (#184).
        WorkspaceRoot workspaceRoot = parseWorkspaceRoot(
                mapField(root, "workspace-root"));

        return new Manifest(schemaVersion, generated, defaults,
                workspaceRoot, subprojects, ide);
    }

    private static WorkspaceRoot parseWorkspaceRoot(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        return new WorkspaceRoot(
                stringField(map, "groupId", null),
                stringField(map, "artifactId", null),
                stringField(map, "version", null)
        );
    }

    private static IdeSettings parseIdeSettings(Map<String, Object> map) {
        if (map == null) {
            return IdeSettings.EMPTY;
        }
        return new IdeSettings(
                stringField(map, "language-level", null),
                stringField(map, "jdk-name", null)
        );
    }

    private static Defaults parseDefaults(Map<String, Object> map) {
        if (map == null) {
            return new Defaults("main", null);
        }
        return new Defaults(
                stringField(map, "branch", "main"),
                stringField(map, "maven-version", null)
        );
    }

    private static Map<String, Subproject> parseSubprojects(
            Map<String, Object> map, Defaults defaults) {
        if (map == null) {
            return Map.of();
        }
        Map<String, Subproject> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String name = entry.getKey();
            @SuppressWarnings("unchecked")
            Map<String, Object> fields = (Map<String, Object>) entry.getValue();
            result.put(name, parseSubproject(name, fields, defaults));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Subproject parseSubproject(String name,
                                              Map<String, Object> fields,
                                              Defaults defaults) {
        String branch = stringField(fields, "branch", defaults.branch());
        String version = stringField(fields, "version", null);
        // SnakeYAML reads YAML ~ (null) as Java null — handle it
        if ("~".equals(version)) {
            version = null;
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> depsRaw =
                (List<Map<String, Object>>) fields.get("depends-on");
        List<Dependency> deps = parseDependencies(depsRaw);

        // Alignment fields (#233 schema, sub-task 2). Legacy manifests
        // without these default to snapshot-aligned, preserving today's
        // behavior; tag/kind stay null in that case.
        String state = stringField(fields, "state", Subproject.STATE_SNAPSHOT);
        String tag = stringField(fields, "tag", null);
        String kind = stringField(fields, "kind", null);

        return new Subproject(
                name,
                stringField(fields, "description", ""),
                stringField(fields, "repo", ""),
                branch,
                version,
                stringField(fields, "groupId", ""),
                deps,
                stringField(fields, "notes", null),
                stringField(fields, "maven-version", null),
                stringField(fields, "parent", null),
                stringField(fields, "sha", null),
                state,
                tag,
                kind
        );
    }

    private static List<Dependency> parseDependencies(
            List<Map<String, Object>> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<Dependency> result = new ArrayList<>(raw.size());
        for (Map<String, Object> entry : raw) {
            result.add(new Dependency(
                    stringField(entry, "subproject", ""),
                    stringField(entry, "relationship", "build"),
                    stringField(entry, "version-property", null)
            ));
        }
        return Collections.unmodifiableList(result);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapField(Map<String, Object> map,
                                                 String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        return (Map<String, Object>) value;
    }

    private static String stringField(Map<String, Object> map, String key,
                                       String defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        return value.toString();
    }

    // ── Legacy schema migration (#150) ──────────────────────────────

    /** Matches {@code components:} as a top-level key (column 0). */
    private static final Pattern LEGACY_COMPONENTS_KEY =
            Pattern.compile("^components:", Pattern.MULTILINE);

    /** Matches {@code component-types:} as a top-level key (column 0). */
    private static final Pattern LEGACY_COMPONENT_TYPES_KEY =
            Pattern.compile("^component-types:", Pattern.MULTILINE);

    /** Matches {@code groups:} as a top-level key (column 0). */
    private static final Pattern LEGACY_GROUPS_KEY =
            Pattern.compile("^groups:", Pattern.MULTILINE);

    /** Matches a legacy {@code  - component:} depends-on entry (indent 2). */
    private static final Pattern LEGACY_COMPONENT_DEP =
            Pattern.compile("^(\\s+)- component:", Pattern.MULTILINE);

    /**
     * Idempotently migrate a workspace.yaml file from the legacy
     * {@code components:} schema to the new {@code subprojects:} schema.
     *
     * <p>If the file does not contain any legacy markers this is a no-op
     * (no file write, no log output). If any legacy markers are present
     * the method rewrites in place:
     * <ul>
     *   <li>{@code ^components:} → {@code subprojects:}</li>
     *   <li>{@code ^component-types:} block — removed entirely (terminated
     *       by the next top-level key at column 0 or EOF)</li>
     *   <li>{@code ^groups:} block — removed entirely (same termination)</li>
     *   <li>{@code  - component:} (depends-on dash form) →
     *       {@code  - subproject:}</li>
     * </ul>
     *
     * <p>This is the entry point {@code ws:align-publish} calls before
     * reading the manifest, so legacy workspaces auto-migrate on first
     * align. Other callers get the hard-cut error from {@link #read(Path)}.
     *
     * @param manifestPath path to workspace.yaml
     * @param infoLog      optional info-level log sink (nullable)
     * @return {@code true} if the file was migrated, {@code false} if
     *         no legacy schema was present
     * @throws ManifestException if the file cannot be read or written
     */
    public static boolean migrateLegacySchemaIfNeeded(
            Path manifestPath,
            java.util.function.Consumer<String> infoLog) {
        String content;
        try {
            content = Files.readString(manifestPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ManifestException(
                    "Cannot read " + manifestPath + " for migration", e);
        }

        boolean hasLegacy = LEGACY_COMPONENTS_KEY.matcher(content).find()
                || LEGACY_COMPONENT_TYPES_KEY.matcher(content).find()
                || LEGACY_GROUPS_KEY.matcher(content).find()
                || LEGACY_COMPONENT_DEP.matcher(content).find();
        if (!hasLegacy) {
            return false;
        }

        String migrated = migrate(content);
        if (migrated.equals(content)) {
            return false;
        }

        try {
            Files.writeString(manifestPath, migrated, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ManifestException(
                    "Cannot write migrated " + manifestPath, e);
        }
        if (infoLog != null) {
            infoLog.accept(
                    "Migrated workspace.yaml: components: → subprojects:, "
                            + "stripped component-types:/groups:");
        }
        return true;
    }

    /**
     * Apply legacy-schema rewrites to yaml text. Pure function —
     * public for unit testing.
     *
     * @param yaml original workspace.yaml content
     * @return migrated content (same as input if already on new schema)
     */
    public static String migrate(String yaml) {
        String result = yaml;
        // 1. Remove entire component-types: block (first, so the
        //    components: match below does not see a surviving line
        //    inside what was component-types).
        result = stripTopLevelBlock(result, "component-types");
        // 2. Remove entire groups: block.
        result = stripTopLevelBlock(result, "groups");
        // 3. Rename components: → subprojects: (top-level key only).
        result = LEGACY_COMPONENTS_KEY.matcher(result)
                .replaceAll("subprojects:");
        // 4. Rename depends-on dash form: `  - component:` → `  - subproject:`
        result = LEGACY_COMPONENT_DEP.matcher(result)
                .replaceAll("$1- subproject:");
        return result;
    }

    /**
     * Strip a top-level YAML block that starts with {@code <key>:} at
     * column 0. The block continues through all indented lines and
     * intervening blank lines, and ends at the next non-indented,
     * non-blank line (top-level key, top-level comment, or EOF).
     *
     * <p>Trailing comments/section-headers are intentionally left in
     * place — they typically introduce the next section, not the block
     * being removed.
     */
    private static String stripTopLevelBlock(String yaml, String key) {
        Pattern start = Pattern.compile("^" + Pattern.quote(key) + ":",
                Pattern.MULTILINE);
        Matcher m = start.matcher(yaml);
        if (!m.find()) {
            return yaml;
        }

        int blockStart = m.start();

        // Find the newline that ends the `key:` line, then walk
        // forward consuming indented or blank lines.
        int blockEnd = yaml.length();
        int i = yaml.indexOf('\n', m.end());
        if (i < 0) {
            // Key line has no trailing newline — strip to EOF.
            blockEnd = yaml.length();
        } else {
            // Position i at the newline terminating `key:`. Step
            // forward line-by-line; each iteration classifies the line
            // starting at lineStart.
            while (true) {
                int lineStart = i + 1;
                if (lineStart >= yaml.length()) {
                    blockEnd = yaml.length();
                    break;
                }
                int lineEnd = yaml.indexOf('\n', lineStart);
                if (lineEnd < 0) lineEnd = yaml.length();

                char c = yaml.charAt(lineStart);
                if (c == ' ' || c == '\t'
                        || c == '\n' || c == '\r'
                        || lineStart == lineEnd) {
                    // Indented or blank — part of the block.
                    if (lineEnd == yaml.length()) {
                        blockEnd = yaml.length();
                        break;
                    }
                    i = lineEnd;
                    continue;
                }
                // Non-indented, non-blank: block ends here, preserving
                // whatever follows (comment banner, next key, etc.).
                blockEnd = lineStart;
                break;
            }
        }

        String head = yaml.substring(0, blockStart);
        String tail = yaml.substring(blockEnd);
        // Collapse multiple leading blank lines in the tail to a
        // single blank line so we don't leave `\n\n\n` at the seam.
        int trim = 0;
        while (trim < tail.length() && tail.charAt(trim) == '\n') {
            trim++;
        }
        // Keep one blank line if both sides are non-empty.
        String sep = (!head.isEmpty() && !tail.substring(trim).isEmpty()
                && trim > 0) ? "\n" : "";
        return head + sep + tail.substring(trim);
    }
}
