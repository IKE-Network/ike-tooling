package network.ike.plugin.scaffold;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read and write {@link ScaffoldLockfile} instances in the on-disk
 * YAML format documented in the
 * {@code dev-ike-scaffold-architecture} design note.
 *
 * <p>Uses SnakeYAML for parsing; emits a stable, human-friendly
 * representation via {@link DumperOptions.FlowStyle#BLOCK block
 * style} with insertion-order preserved. The writer writes the
 * schema version first, then the top-level stamps, then the
 * {@code files:} map — so a hand-read diff of
 * {@code .ike/scaffold.lock} stays easy to follow across publishes.
 *
 * <p>All emitted timestamps are in UTC ISO-8601 ({@code "Z"}).
 */
public final class ScaffoldLockfileIo {

    private ScaffoldLockfileIo() {}

    // ── Read ────────────────────────────────────────────────────────

    /**
     * Parse a lockfile from a file path.
     *
     * @param path path to the YAML lockfile
     * @return the parsed lockfile
     * @throws ScaffoldException if the file cannot be read, is empty,
     *                           or has an unsupported schema version
     */
    public static ScaffoldLockfile read(Path path) {
        try (Reader reader = Files.newBufferedReader(
                path, StandardCharsets.UTF_8)) {
            return read(reader);
        } catch (IOException e) {
            throw new ScaffoldException(
                    "Cannot read scaffold lockfile " + path, e);
        }
    }

    /**
     * Parse a lockfile from a Reader.
     *
     * @param reader source of YAML text
     * @return the parsed lockfile
     * @throws ScaffoldException if the YAML is empty or has an
     *                           unsupported schema version
     */
    public static ScaffoldLockfile read(Reader reader) {
        Object raw = new Yaml().load(reader);
        if (raw == null) {
            throw new ScaffoldException(
                    "Empty scaffold lockfile");
        }
        if (!(raw instanceof Map<?, ?> rootMap)) {
            throw new ScaffoldException(
                    "Scaffold lockfile root must be a YAML mapping, "
                            + "got " + raw.getClass().getSimpleName());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) rootMap;

        int schema = intField(root, "schema", ScaffoldLockfile.CURRENT_SCHEMA);
        if (schema != ScaffoldLockfile.CURRENT_SCHEMA) {
            throw new ScaffoldException(
                    "Unsupported scaffold lockfile schema: " + schema
                            + " (supported: "
                            + ScaffoldLockfile.CURRENT_SCHEMA + ")");
        }

        String standardsVersion = stringField(
                root, "standards-version", null);
        Instant applied = instantField(root, "applied");

        Map<String, Object> filesYaml = mapField(root, "files");
        Map<String, LockfileEntry> files = new LinkedHashMap<>();
        if (filesYaml != null) {
            for (Map.Entry<String, Object> e : filesYaml.entrySet()) {
                files.put(e.getKey(),
                        parseEntry(e.getKey(), e.getValue()));
            }
        }

        return new ScaffoldLockfile(
                schema, standardsVersion, applied, files);
    }

    private static LockfileEntry parseEntry(String key, Object raw) {
        if (!(raw instanceof Map<?, ?> entryMap)) {
            throw new ScaffoldException(
                    "Lockfile entry '" + key
                            + "' must be a YAML mapping");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) entryMap;

        String tierValue = stringField(entry, "tier", null);
        if (tierValue == null) {
            throw new ScaffoldException(
                    "Lockfile entry '" + key
                            + "' missing required 'tier' field");
        }
        ScaffoldTier tier = ScaffoldTier.fromManifestValue(tierValue);

        String templateSha = stringField(entry, "template-sha", null);
        String appliedSha = stringField(entry, "applied-sha", null);

        List<ManagedElement> managed = new ArrayList<>();
        Object me = entry.get("managed-elements");
        if (me instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> itemMap)) {
                    throw new ScaffoldException(
                            "managed-elements entry in '" + key
                                    + "' must be a YAML mapping");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> item = (Map<String, Object>) itemMap;
                String elemPath = stringField(item, "path", null);
                Instant installedAt = instantField(item, "installed-at");
                String stdVer = stringField(
                        item, "standards-version", null);
                if (elemPath == null || installedAt == null
                        || stdVer == null) {
                    throw new ScaffoldException(
                            "managed-elements entry in '" + key
                                    + "' requires path, installed-at, "
                                    + "and standards-version");
                }
                managed.add(new ManagedElement(
                        elemPath, installedAt, stdVer));
            }
        }

        return new LockfileEntry(tier, templateSha, appliedSha, managed);
    }

    // ── Write ───────────────────────────────────────────────────────

    /**
     * Serialise a lockfile to YAML text. Does not touch disk.
     *
     * @param lockfile the lockfile to serialise
     * @return its YAML representation
     */
    public static String writeToString(ScaffoldLockfile lockfile) {
        StringWriter out = new StringWriter();
        write(lockfile, out);
        return out.toString();
    }

    /**
     * Serialise a lockfile to a file path, creating parent
     * directories if needed.
     *
     * @param lockfile the lockfile to serialise
     * @param path     destination path
     * @throws ScaffoldException if the file cannot be written
     */
    public static void write(ScaffoldLockfile lockfile, Path path) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter out = Files.newBufferedWriter(
                    path, StandardCharsets.UTF_8)) {
                write(lockfile, out);
            }
        } catch (IOException e) {
            throw new ScaffoldException(
                    "Cannot write scaffold lockfile " + path, e);
        }
    }

    /**
     * Serialise a lockfile through any {@link Writer}.
     *
     * @param lockfile the lockfile to serialise
     * @param writer   destination writer (not closed)
     */
    public static void write(ScaffoldLockfile lockfile, Writer writer) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", lockfile.schema());
        if (lockfile.standardsVersion() != null) {
            root.put("standards-version", lockfile.standardsVersion());
        }
        if (lockfile.applied() != null) {
            root.put("applied", toIso(lockfile.applied()));
        }
        Map<String, Object> filesOut = new LinkedHashMap<>();
        for (Map.Entry<String, LockfileEntry> e
                : lockfile.files().entrySet()) {
            filesOut.put(e.getKey(), entryToMap(e.getValue()));
        }
        root.put("files", filesOut);

        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setPrettyFlow(true);
        opts.setIndent(2);
        opts.setIndicatorIndent(0);
        opts.setLineBreak(DumperOptions.LineBreak.UNIX);
        try {
            new Yaml(opts).dump(root, writer);
        } catch (Exception e) {
            throw new ScaffoldException(
                    "Failed to serialise scaffold lockfile", e);
        }
    }

    private static Map<String, Object> entryToMap(LockfileEntry entry) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tier", entry.tier().manifestValue());
        if (entry.templateSha() != null) {
            m.put("template-sha", entry.templateSha());
        }
        if (entry.appliedSha() != null) {
            m.put("applied-sha", entry.appliedSha());
        }
        if (!entry.managedElements().isEmpty()) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (ManagedElement e : entry.managedElements()) {
                Map<String, Object> em = new LinkedHashMap<>();
                em.put("path", e.path());
                em.put("installed-at", toIso(e.installedAt()));
                em.put("standards-version", e.standardsVersion());
                list.add(em);
            }
            m.put("managed-elements", list);
        }
        return m;
    }

    // ── YAML helpers ────────────────────────────────────────────────

    private static String stringField(
            Map<String, Object> map, String key, String fallback) {
        Object v = map.get(key);
        if (v == null) {
            return fallback;
        }
        return v.toString();
    }

    private static int intField(
            Map<String, Object> map, String key, int fallback) {
        Object v = map.get(key);
        if (v == null) {
            return fallback;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            throw new ScaffoldException(
                    "Expected integer for '" + key + "', got " + v);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapField(
            Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) {
            return null;
        }
        if (!(v instanceof Map<?, ?>)) {
            throw new ScaffoldException(
                    "Expected mapping for '" + key + "'");
        }
        return (Map<String, Object>) v;
    }

    private static Instant instantField(
            Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Instant i) {
            return i;
        }
        if (v instanceof java.util.Date d) {
            return d.toInstant();
        }
        String s = v.toString().trim();
        try {
            return DateTimeFormatter.ISO_INSTANT.parse(s, Instant::from);
        } catch (DateTimeParseException e) {
            throw new ScaffoldException(
                    "Invalid timestamp for '" + key + "': " + s, e);
        }
    }

    private static String toIso(Instant i) {
        return DateTimeFormatter.ISO_INSTANT.format(i);
    }
}
