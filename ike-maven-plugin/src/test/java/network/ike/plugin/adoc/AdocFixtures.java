package network.ike.plugin.adoc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Real files for the union tests: a library zip with its manifest, a reactor member's
 * source tree, and a local repository holding a zip at Maven's layout. No mocks.
 */
final class AdocFixtures {

    private AdocFixtures() {
    }

    /** A topic file's text: the header a registry reads, an anchor and a heading. */
    static String topic(String id, String title) {
        return "// " + id + "\n:topic-id: " + id + "\n:topic-type: concept\n:topic-status: draft\n"
                + ":topic-keywords: a, b, c\n\n[[" + id + "]]\n= " + title + "\n\nOne paragraph.\n";
    }

    /** A manifest's text for the given coordinates and packages, each {@code name=kind}. */
    static String manifest(String ga, Map<String, String> packages) {
        StringBuilder sb = new StringBuilder("coordinates: " + ga + "\npackages:\n");
        packages.forEach((name, kind) -> sb.append("  - name: ").append(name).append("\n    kind: ")
                .append(kind).append('\n'));
        String[] parts = ga.split(":");
        sb.append("registry: META-INF/ike/").append(parts[0]).append('/').append(parts[1])
                .append("/topic-registry.yaml\n");
        return sb.toString();
    }

    /** Writes a zip with the given entries, in order. */
    static Path zip(Path file, Map<String, String> entries) throws IOException {
        Files.createDirectories(file.getParent());
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return file;
    }

    /**
     * A packaged library zip: the packages' topics, the manifest and a registry stub under
     * {@code META-INF/ike/<group>/<artifact>/}.
     */
    static Path libraryZip(Path file, AdocCoordinates coordinates, Map<String, String> packages,
                           Map<String, String> files) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>(files);
        entries.put(coordinates.manifestPath(), manifest(coordinates.ga(), packages));
        entries.put(coordinates.metaInfPath() + "/topic-registry.yaml", "registry-version: \"2.0\"\n");
        return zip(file, entries);
    }

    /** Installs a library zip at Maven's layout under a local repository root. */
    static Path installed(Path localRepository, AdocCoordinates coordinates, Map<String, String> packages,
                          Map<String, String> files) throws IOException {
        Path dir = localRepository.resolve(coordinates.groupId().replace('.', '/'))
                .resolve(coordinates.artifactId()).resolve(coordinates.version());
        return libraryZip(dir.resolve(coordinates.artifactId() + "-" + coordinates.version() + "-adoc.zip"),
                coordinates, packages, files);
    }

    /** Writes a file, creating its directories. */
    static Path write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }
}
