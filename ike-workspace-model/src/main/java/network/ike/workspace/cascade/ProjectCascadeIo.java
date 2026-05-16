package network.ike.workspace.cascade;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads a project's own {@code src/main/cascade/release-cascade.yaml}
 * into a {@link ProjectCascade} (IKE-Network/ike-issues#420).
 *
 * <p>Each foundation project version-controls this file in its own
 * git tree, so resolution is a plain on-disk read of a fixed path —
 * no artifact resolution, no central manifest. {@link CascadeAssembler}
 * stitches the per-project files into the full ordered graph.
 *
 * <p>Parsing is lenient about unknown top-level keys (forward
 * compatibility) but strict about edge shape.
 */
public final class ProjectCascadeIo {

    /** Conventional manifest file name. */
    public static final String MANIFEST_NAME = "release-cascade.yaml";

    /**
     * Conventional manifest location relative to a repository root —
     * {@code src/main/cascade/release-cascade.yaml}. Every foundation
     * project carries the file at this same relative path.
     */
    public static final String MANIFEST_RELATIVE_PATH =
            "src/main/cascade/" + MANIFEST_NAME;

    private ProjectCascadeIo() {}

    /**
     * Parses a per-project manifest from a file path.
     *
     * @param path path to the YAML manifest
     * @return the parsed manifest
     * @throws UncheckedIOException if the file cannot be read
     * @throws IllegalArgumentException if the manifest is malformed
     */
    public static ProjectCascade read(Path path) {
        try (Reader reader = Files.newBufferedReader(
                path, StandardCharsets.UTF_8)) {
            return read(reader);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Cannot read release-cascade manifest: " + path, e);
        }
    }

    /**
     * Parses a per-project manifest from an open reader.
     *
     * @param reader the YAML source
     * @return the parsed manifest
     * @throws IllegalArgumentException if the manifest is malformed
     */
    public static ProjectCascade read(Reader reader) {
        Object root = new Yaml().load(reader);
        if (!(root instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(
                    "release-cascade.yaml must be a YAML mapping");
        }
        int schema = map.get("schema") instanceof Number n
                ? n.intValue() : 1;
        boolean head = Boolean.TRUE.equals(map.get("head"));
        boolean terminal = Boolean.TRUE.equals(map.get("terminal"));
        List<CascadeEdge> upstream = readEdges(map.get("upstream"));
        List<CascadeEdge> downstream = readEdges(map.get("downstream"));
        return new ProjectCascade(schema, head, upstream,
                terminal, downstream);
    }

    /**
     * Loads a per-project manifest from a path, degrading gracefully
     * when no manifest is present.
     *
     * @param manifestPath the manifest path; may be {@code null}
     * @return the parsed manifest, or empty if {@code manifestPath} is
     *         {@code null} or does not point at a regular file
     * @throws IllegalArgumentException if the manifest exists but is
     *                                  malformed
     */
    public static Optional<ProjectCascade> load(Path manifestPath) {
        if (manifestPath == null || !Files.isRegularFile(manifestPath)) {
            return Optional.empty();
        }
        return Optional.of(read(manifestPath));
    }

    private static List<CascadeEdge> readEdges(Object raw) {
        List<CascadeEdge> edges = new ArrayList<>();
        if (!(raw instanceof List<?> entries)) {
            return edges;
        }
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> e)) {
                throw new IllegalArgumentException(
                        "each cascade edge must be a mapping");
            }
            edges.add(new CascadeEdge(
                    stringOrNull(e.get("groupId")),
                    stringOrNull(e.get("artifactId")),
                    stringOrNull(e.get("repo")),
                    stringOrNull(e.get("url")),
                    stringOrNull(e.get("version-property"))));
        }
        return edges;
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
