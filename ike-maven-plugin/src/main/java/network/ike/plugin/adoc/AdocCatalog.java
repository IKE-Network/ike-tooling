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
package network.ike.plugin.adoc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Finds which library ships a package, for the message a build prints when an include names
 * a path nothing declared ships. Two catalogs, in order: the manifests already in the union,
 * then the local repository, searched by the group prefix of the path. The goal never
 * fetches an undeclared library, because it would have to guess the version and the build
 * would stop being reproducible.
 */
final class AdocCatalog {

    private AdocCatalog() {
    }

    /**
     * A library found to ship the package that holds a path.
     *
     * @param coordinates the library, at the version it was found at
     * @param pkg         the package that holds the path
     */
    record Hit(AdocCoordinates coordinates, AdocPackage pkg) {
    }

    /**
     * Searches the manifests in the union.
     *
     * @param union the union directory
     * @param path  a path relative to the union, with forward slashes
     * @return the library in the union whose manifest ships the path's package
     */
    static Optional<Hit> inUnion(Path union, String path) {
        for (Path manifestFile : unionManifests(union)) {
            AdocManifest manifest = AdocManifest.read(manifestFile);
            Optional<AdocPackage> owner = manifest.packageOwning(path);
            if (owner.isPresent()) {
                String version = stampVersion(manifestFile.resolveSibling(UnionStamp.FILE_NAME));
                return Optional.of(new Hit(new AdocCoordinates(manifest.groupId(),
                        manifest.artifactId(), version), owner.get()));
            }
        }
        return Optional.empty();
    }

    /**
     * Every manifest in the union, {@code META-INF/ike/<group>/<artifact>/manifest.yaml}, in
     * path order.
     *
     * @param union the union directory
     * @return the manifest files
     */
    static List<Path> unionManifests(Path union) {
        List<Path> found = new ArrayList<>();
        Path area = union.resolve(AdocCoordinates.META_INF_IKE);
        if (!Files.isDirectory(area)) {
            return found;
        }
        for (Path group : children(area)) {
            if (!Files.isDirectory(group)) {
                continue;
            }
            for (Path artifact : children(group)) {
                Path manifest = artifact.resolve(AdocManifest.FILE_NAME);
                if (Files.isRegularFile(manifest)) {
                    found.add(manifest);
                }
            }
        }
        return found;
    }

    /**
     * Searches the local repository for an {@code adoc} zip whose manifest ships the path's
     * package. The group of the path, its first segment, names where to look:
     * {@code network.ike.lab/dev/x.adoc} is searched under {@code network/ike/lab}, then under
     * {@code network/ike} and {@code network}, because a library's packages begin with its own
     * group or with a declared prefix of it. The newest version found wins.
     *
     * @param localRepository the local repository root, {@code ~/.m2/repository}
     * @param path            a path relative to the union, with forward slashes
     * @return the library whose zip ships the path's package, at its newest local version
     */
    static Optional<Hit> inLocalRepository(Path localRepository, String path) {
        int slash = path.indexOf('/');
        String group = slash < 0 ? path : path.substring(0, slash);
        List<Hit> hits = new ArrayList<>();
        String[] labels = group.split("\\.");
        for (int depth = labels.length; depth >= 1; depth--) {
            String candidateGroup = String.join(".", List.of(labels).subList(0, depth));
            Path groupDir = localRepository.resolve(candidateGroup.replace('.', '/'));
            if (!Files.isDirectory(groupDir)) {
                continue;
            }
            for (Path artifactDir : children(groupDir)) {
                if (!Files.isDirectory(artifactDir)) {
                    continue;
                }
                String artifactId = artifactDir.getFileName().toString();
                for (Path versionDir : children(artifactDir)) {
                    if (!Files.isDirectory(versionDir)) {
                        continue;
                    }
                    String version = versionDir.getFileName().toString();
                    AdocCoordinates coordinates = new AdocCoordinates(candidateGroup, artifactId, version);
                    for (Path zip : children(versionDir)) {
                        String name = zip.getFileName().toString();
                        if (!name.startsWith(artifactId + "-")
                                || !name.endsWith("-" + AdocCoordinates.CLASSIFIER + "." + AdocCoordinates.TYPE)) {
                            continue;
                        }
                        Optional<AdocManifest> manifest = readQuietly(zip, coordinates);
                        manifest.flatMap(m -> m.packageOwning(path))
                                .ifPresent(pkg -> hits.add(new Hit(coordinates, pkg)));
                        break;
                    }
                }
            }
        }
        return hits.stream().max(Comparator.comparing(h -> h.coordinates().version(), VersionOrder.INSTANCE));
    }

    private static Optional<AdocManifest> readQuietly(Path zip, AdocCoordinates coordinates) {
        try {
            return AdocManifest.readFromZip(zip, coordinates);
        } catch (RuntimeException e) {
            // A zip that cannot be read or whose manifest is malformed is not a candidate.
            return Optional.empty();
        }
    }

    private static String stampVersion(Path stamp) {
        try {
            return UnionStamp.read(stamp).map(UnionStamp::version).filter(v -> !v.isBlank()).orElse("unknown");
        } catch (IOException e) {
            return "unknown";
        }
    }

    private static List<Path> children(Path dir) {
        List<Path> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            stream.forEach(result::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        result.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return result;
    }
}
