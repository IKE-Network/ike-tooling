package network.ike.workspace.cascade;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads the {@code release-cascade.yaml} manifest
 * (IKE-Network/ike-issues#402).
 *
 * <p>The manifest is shipped by {@code ike-build-standards} as the
 * {@code classifier=cascade} artifact; the release goals never write
 * it, only read it. Parsing is lenient about unknown top-level keys
 * (forward compatibility) but strict about the {@code cascade} list.
 *
 * <p>The manifest <em>location</em> is not discovered — it is a
 * declared Maven property ({@code ike.release.cascade.manifest},
 * set in {@code ike-parent} and in {@code ike-tooling}'s own root
 * POM). The release mojos resolve that property to a path and pass
 * it to {@link #load(Path)}, which returns an empty {@link Optional}
 * when the file is absent so a cascade-aware goal degrades gracefully
 * into cascade-blind behaviour.
 */
public final class ReleaseCascadeIo {

    /** Conventional manifest file name. */
    public static final String MANIFEST_NAME = "release-cascade.yaml";

    /**
     * Maven property naming the manifest location. Declared in
     * {@code ike-parent}'s {@code <properties>} (and in
     * {@code ike-tooling}'s root POM), so the path is visible in the
     * effective POM rather than discovered by filesystem heuristics.
     */
    public static final String MANIFEST_PROPERTY =
            "ike.release.cascade.manifest";

    private ReleaseCascadeIo() {}

    /**
     * Parses a manifest from a file path.
     *
     * @param path path to the YAML manifest
     * @return the parsed cascade
     * @throws UncheckedIOException if the file cannot be read
     * @throws IllegalArgumentException if the manifest is malformed
     */
    public static ReleaseCascade read(Path path) {
        try (Reader reader = Files.newBufferedReader(
                path, StandardCharsets.UTF_8)) {
            return read(reader);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Cannot read release-cascade manifest: " + path, e);
        }
    }

    /**
     * Parses a manifest from an open reader.
     *
     * @param reader the YAML source
     * @return the parsed cascade
     * @throws IllegalArgumentException if the manifest is malformed
     */
    public static ReleaseCascade read(Reader reader) {
        Object root = new Yaml().load(reader);
        if (!(root instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(
                    "release-cascade.yaml must be a YAML mapping");
        }
        String standardsVersion = stringOrNull(map.get("standards-version"));
        Object rawCascade = map.get("cascade");
        if (!(rawCascade instanceof List<?> entries)) {
            throw new IllegalArgumentException(
                    "release-cascade.yaml must have a 'cascade' list");
        }
        List<CascadeRepo> repos = new ArrayList<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> e)) {
                throw new IllegalArgumentException(
                        "each 'cascade' entry must be a mapping");
            }
            String groupId = stringOrNull(e.get("groupId"));
            String artifactId = stringOrNull(e.get("artifactId"));
            if (groupId == null || groupId.isBlank()) {
                throw new IllegalArgumentException(
                        "each 'cascade' entry must have a 'groupId'");
            }
            if (artifactId == null || artifactId.isBlank()) {
                throw new IllegalArgumentException(
                        "each 'cascade' entry must have an 'artifactId'");
            }
            String repo = stringOrNull(e.get("repo"));
            String url = stringOrNull(e.get("url"));
            List<String> consumes = new ArrayList<>();
            if (e.get("consumes") instanceof List<?> raw) {
                for (Object c : raw) {
                    consumes.add(String.valueOf(c));
                }
            }
            repos.add(new CascadeRepo(
                    groupId, artifactId, repo, url, consumes));
        }
        return new ReleaseCascade(standardsVersion, repos);
    }

    /**
     * Reads the cascade manifest from inside the
     * {@code ike-build-standards} {@code cascade} classified artifact
     * (a ZIP containing {@code release-cascade.yaml} at its root).
     *
     * <p>This is the resolution path for foundation repos that cannot
     * see the manifest on disk — {@code ike-docs} and {@code ike-platform}
     * are upstream of {@code ike-parent} and so neither inherit the
     * unpack execution nor carry the manifest in-tree
     * (IKE-Network/ike-issues#404). The caller resolves the artifact
     * (via the Maven session) and passes its ZIP path here.
     *
     * @param zipPath path to the {@code cascade} classified ZIP
     * @return the parsed cascade
     * @throws UncheckedIOException if the ZIP cannot be read
     * @throws IllegalArgumentException if the ZIP has no
     *         {@code release-cascade.yaml} entry or it is malformed
     */
    public static ReleaseCascade readFromZip(Path zipPath) {
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            ZipEntry entry = zip.getEntry(MANIFEST_NAME);
            if (entry == null) {
                throw new IllegalArgumentException(
                        "no " + MANIFEST_NAME + " entry in " + zipPath);
            }
            try (Reader reader = new InputStreamReader(
                    zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                return read(reader);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Cannot read cascade manifest from " + zipPath, e);
        }
    }

    /**
     * Loads the cascade manifest from a declared path, degrading
     * gracefully when no manifest is present.
     *
     * @param manifestPath the manifest path resolved from the
     *                     {@link #MANIFEST_PROPERTY} property; may be
     *                     {@code null}
     * @return the parsed cascade, or empty if {@code manifestPath} is
     *         {@code null} or does not point at a regular file
     * @throws IllegalArgumentException if the manifest exists but is
     *                                  malformed
     */
    public static Optional<ReleaseCascade> load(Path manifestPath) {
        if (manifestPath == null || !Files.isRegularFile(manifestPath)) {
            return Optional.empty();
        }
        return Optional.of(read(manifestPath));
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
