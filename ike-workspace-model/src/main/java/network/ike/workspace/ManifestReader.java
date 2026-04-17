package network.ike.workspace;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a {@code workspace.yaml} file into a typed {@link Manifest}.
 *
 * <p>SnakeYAML parses into raw Maps; this class maps the untyped
 * structure onto immutable Java records with validation.
 */
public final class ManifestReader {

    private ManifestReader() {}

    /**
     * Read a workspace manifest from the given YAML file path.
     *
     * @param path path to workspace.yaml
     * @return the parsed manifest
     * @throws ManifestException if the file cannot be read or has invalid structure
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
     * @throws ManifestException if the YAML has invalid structure
     */
    public static Manifest read(Reader reader) {
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(reader);
        if (root == null) {
            throw new ManifestException("Empty manifest");
        }
        return parseManifest(root);
    }

    private static Manifest parseManifest(Map<String, Object> root) {
        String schemaVersion = stringField(root, "schema-version", "1.0");
        String generated = stringField(root, "generated", null);

        Defaults defaults = parseDefaults(mapField(root, "defaults"));
        // component-types: / subproject-types: is legacy: SubprojectType
        // now carries the build command and checkpoint mechanism as
        // compile-time data. A present section is silently ignored;
        // ws:align strips it from workspace.yaml.
        Map<String, Component> components = parseComponents(
                mapField(root, "components"), defaults);
        IdeSettings ide = parseIdeSettings(mapField(root, "ide"));

        return new Manifest(schemaVersion, generated, defaults,
                components, ide);
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

    private static Map<String, Component> parseComponents(
            Map<String, Object> map, Defaults defaults) {
        if (map == null) {
            return Map.of();
        }
        Map<String, Component> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String name = entry.getKey();
            @SuppressWarnings("unchecked")
            Map<String, Object> fields = (Map<String, Object>) entry.getValue();
            result.put(name, parseComponent(name, fields, defaults));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Component parseComponent(String name,
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

        return new Component(
                name,
                SubprojectType.fromYamlName(
                        stringField(fields, "type", "software")),
                stringField(fields, "description", ""),
                stringField(fields, "repo", ""),
                branch,
                version,
                stringField(fields, "groupId", ""),
                deps,
                stringField(fields, "notes", null),
                stringField(fields, "maven-version", null),
                stringField(fields, "parent", null),
                stringField(fields, "sha", null)
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
                    stringField(entry, "component", ""),
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
}
