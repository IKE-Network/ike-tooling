package network.ike.workspace;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads and writes {@code releases/release-<cycle>.yaml} — the on-disk
 * form of a {@link ReleaseRecord} (IKE-Network/ike-issues#973).
 *
 * <p>Unlike {@link ManifestWriter}, which performs comment-preserving
 * surgical edits on the hand-maintained {@code workspace.yaml}, the
 * release record is machine-owned ({@code ws:record-release} writes it,
 * nothing hand-edits it), so this class renders the whole file from the
 * model on every write — symmetric read/write over one canonical form,
 * in the style of the checkpoint YAML files.
 *
 * <p>Scalar values that could parse as non-string YAML types (versions,
 * shas, dates) are written quoted so a read-back always yields strings.
 */
public final class ReleaseRecordFile {

    private ReleaseRecordFile() {}

    /**
     * The canonical record path for a cycle:
     * {@code <root>/releases/release-<cycle>.yaml}.
     *
     * @param workspaceRoot the workspace root directory
     * @param cycle         the cycle label, e.g. {@code komet-wsr-1}
     * @return the record file path
     * @throws ManifestException if {@code cycle} is null or blank
     */
    public static Path pathFor(Path workspaceRoot, String cycle) {
        if (cycle == null || cycle.isBlank()) {
            throw new ManifestException("cycle must not be null or blank");
        }
        return workspaceRoot.resolve("releases")
                .resolve("release-" + cycle + ".yaml");
    }

    /**
     * Read a release record from disk.
     *
     * @param path the record file path
     * @return the parsed record
     * @throws ManifestException if the file cannot be read, is not a YAML
     *                           map, or fails {@link ReleaseRecord}
     *                           validation
     */
    public static ReleaseRecord read(Path path) {
        Map<String, Object> raw;
        try (Reader reader = Files.newBufferedReader(path,
                StandardCharsets.UTF_8)) {
            Object loaded = new Yaml().load(reader);
            if (!(loaded instanceof Map)) {
                throw new ManifestException("Release record " + path
                        + " is not a YAML map");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) loaded;
            raw = cast;
        } catch (IOException e) {
            throw new ManifestException("Cannot read release record "
                    + path + ": " + e.getMessage(), e);
        }

        String cycle = stringValue(raw.get("cycle"));
        String started = stringValue(raw.get("started"));

        Map<String, ReleaseRecord.MemberRelease> members = new LinkedHashMap<>();
        Object membersRaw = raw.get("members");
        if (membersRaw instanceof Map<?, ?> membersMap) {
            for (Map.Entry<?, ?> entry : membersMap.entrySet()) {
                String name = stringValue(entry.getKey());
                if (!(entry.getValue() instanceof Map<?, ?> row)) {
                    throw new ManifestException("Release record " + path
                            + ": member '" + name + "' is not a YAML map");
                }
                members.put(name, new ReleaseRecord.MemberRelease(
                        stringValue(row.get("version")),
                        stringValue(row.get("tag")),
                        stringValue(row.get("sha")),
                        stringValue(row.get("recorded"))));
            }
        } else if (membersRaw != null) {
            throw new ManifestException("Release record " + path
                    + ": members is not a YAML map");
        }

        return new ReleaseRecord(cycle, started, members);
    }

    /**
     * Write a release record to disk, creating the {@code releases/}
     * directory when absent. The rendered form is deterministic for a
     * given record, so re-writing an unchanged record is byte-stable.
     *
     * @param path   the record file path
     * @param record the record to render
     * @throws IOException if the directory or file cannot be written
     */
    public static void write(Path path, ReleaseRecord record)
            throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, render(record), StandardCharsets.UTF_8);
    }

    /**
     * Render a record to its canonical YAML text.
     *
     * @param record the record to render
     * @return the YAML document text
     */
    static String render(ReleaseRecord record) {
        StringBuilder sb = new StringBuilder();
        sb.append("# release-").append(record.cycle()).append(".yaml — ")
                .append("one release cycle of this working set.\n");
        sb.append("# Written by ws:record-release ")
                .append("(IKE-Network/ike-issues#973); one file per cycle,\n");
        sb.append("# one row per released member. Do not hand-edit.\n");
        sb.append("\n");
        sb.append("cycle: ").append(quoted(record.cycle())).append("\n");
        if (record.started() != null) {
            sb.append("started: ").append(quoted(record.started()))
                    .append("\n");
        }
        sb.append("\n");
        if (record.members().isEmpty()) {
            sb.append("members: {}\n");
            return sb.toString();
        }
        sb.append("members:\n");
        for (Map.Entry<String, ReleaseRecord.MemberRelease> entry
                : record.members().entrySet()) {
            ReleaseRecord.MemberRelease row = entry.getValue();
            sb.append("  ").append(entry.getKey()).append(":\n");
            sb.append("    version: ").append(quoted(row.version()))
                    .append("\n");
            sb.append("    tag: ").append(quoted(row.tag())).append("\n");
            sb.append("    sha: ").append(quoted(row.sha())).append("\n");
            if (row.recorded() != null) {
                sb.append("    recorded: ").append(quoted(row.recorded()))
                        .append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Quote a scalar for the rendered form so read-back always yields a
     * string regardless of YAML's scalar typing rules.
     *
     * @param value the scalar text
     * @return the double-quoted form
     */
    private static String quoted(String value) {
        return "\"" + value + "\"";
    }

    /**
     * Normalize a loaded YAML scalar to its string form. SnakeYAML types
     * bare scalars (dates, integers); the record model is string-typed.
     *
     * @param value the loaded scalar, possibly null
     * @return the string form, or null when {@code value} is null
     */
    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }
}
