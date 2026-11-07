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

import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * One union of AsciiDoc packages per repository, written and verified from any module of
 * it. The union mirrors every declared library's root: a package such as
 * {@code network.ike.lab/dev} is copied from an external library's zip, or linked to a
 * reactor member's {@code src/docs/asciidoc/network.ike.lab/dev} so an edit reached through
 * an include changes the real source. Each library's manifest and a stamp are under
 * {@code META-INF/ike/<group>/<artifact>/}, which is how a later run knows what is there.
 *
 * <p>The rules, in the order they are applied:
 * <ul>
 *   <li>A zip without a manifest is a legacy library. With legacy mode on it is unpacked
 *       into the legacy per-module directory as the stock unpack did; otherwise the build
 *       refuses, naming the artifact.</li>
 *   <li>Two libraries that ship one package are refused, naming both. A library already in
 *       the union that no module declares anymore counts too, since the union is one per
 *       repository.</li>
 *   <li>An unchanged library is not rewritten: a copy is keyed by the zip's SHA-256, a link
 *       by its target.</li>
 *   <li>A stale entry whose library is not among those declared is left alone; a run writes
 *       only the libraries it was given.</li>
 * </ul>
 *
 * <p>Whether IntelliJ resolves includes, previews and navigates through a symbolic link in
 * {@code target/generated-sources} is to be confirmed by KEC on one machine; until then the
 * link stays the default and {@code link=false} copies a member's tree instead.
 */
public final class AdocUnion {

    /**
     * What one run did, by library.
     *
     * @param copied    external libraries copied from their zip
     * @param linked    reactor members linked, or copied from their tree when linking is off
     * @param unchanged libraries already in the union as they are
     * @param legacy    legacy libraries unpacked into the legacy directory
     */
    public record Result(List<AdocCoordinates> copied, List<AdocCoordinates> linked,
                         List<AdocCoordinates> unchanged, List<AdocCoordinates> legacy) {

        /**
         * One line for the build log.
         *
         * @return the counts, in plain words
         */
        public String summary() {
            return copied.size() + " copied, " + linked.size() + " linked, " + unchanged.size()
                    + " unchanged" + (legacy.isEmpty() ? "" : ", " + legacy.size() + " legacy");
        }
    }

    private final Path unionDirectory;
    private final Path legacyDirectory;
    private final boolean link;
    private final boolean legacy;
    private final Log log;
    private boolean legacyUnpacked;

    /**
     * A union at a directory.
     *
     * @param unionDirectory  the union, {@code <reactor root>/target/generated-sources/adoc}
     * @param legacyDirectory where a legacy library is unpacked when legacy mode is on
     * @param link            link a reactor member's packages rather than copying them
     * @param legacy          unpack a zip without a manifest rather than refusing it
     * @param log             the build log
     */
    public AdocUnion(Path unionDirectory, Path legacyDirectory, boolean link, boolean legacy, Log log) {
        this.unionDirectory = unionDirectory.toAbsolutePath().normalize();
        this.legacyDirectory = legacyDirectory.toAbsolutePath().normalize();
        this.link = link;
        this.legacy = legacy;
        this.log = log;
    }

    /**
     * The union directory.
     *
     * @return the absolute, normalized union directory
     */
    public Path unionDirectory() {
        return unionDirectory;
    }

    /**
     * Whether {@link #update} unpacked a legacy library into the legacy directory.
     *
     * @return true after a legacy unpack in this run
     */
    public boolean legacyUnpacked() {
        return legacyUnpacked;
    }

    /**
     * Puts the given libraries in the union, each according to the rules above.
     *
     * @param libraries the libraries one module declares, plus the module itself when it is
     *                  a library
     * @return what was done
     * @throws MojoException on a legacy zip outside legacy mode, two libraries shipping one
     *                       package, a manifest that names other coordinates than the
     *                       dependency, or a file that cannot be written
     */
    public Result update(List<AdocLibrary> libraries) {
        try {
            Files.createDirectories(unionDirectory);
            Map<AdocLibrary, AdocManifest> manifests = new LinkedHashMap<>();
            List<AdocLibrary> legacyLibraries = new ArrayList<>();
            for (AdocLibrary library : libraries) {
                if (library.isReactor()) {
                    manifests.put(library, AdocManifest.fromSourceTree(library.coordinates(), library.sourceRoot()));
                    continue;
                }
                Optional<AdocManifest> manifest = AdocManifest.readFromZip(library.zip(), library.coordinates());
                if (manifest.isPresent()) {
                    if (!manifest.get().ga().equals(library.coordinates().ga())) {
                        throw new MojoException("The manifest in " + library.zip() + " names "
                                + manifest.get().ga() + " but the dependency is " + library.coordinates().ga());
                    }
                    manifests.put(library, manifest.get());
                } else if (legacy) {
                    legacyLibraries.add(library);
                } else {
                    throw new MojoException(library.coordinates().gav() + " ships no "
                            + library.coordinates().manifestPath() + ": it is a legacy library with the"
                            + " topics/ layout. Rebuild it with the package layout, or unpack it as before"
                            + " into " + legacyDirectory + " with -Dike.adoc.legacy=true.");
                }
            }
            refuseSplitPackages(manifests);

            List<AdocCoordinates> copied = new ArrayList<>();
            List<AdocCoordinates> linked = new ArrayList<>();
            List<AdocCoordinates> unchanged = new ArrayList<>();
            List<AdocCoordinates> legacyDone = new ArrayList<>();
            for (Map.Entry<AdocLibrary, AdocManifest> entry : manifests.entrySet()) {
                AdocLibrary library = entry.getKey();
                boolean changed = library.isReactor()
                        ? (link ? linkMember(library, entry.getValue()) : copyTree(library, entry.getValue()))
                        : copyZip(library, entry.getValue());
                (changed ? (library.isReactor() ? linked : copied) : unchanged).add(library.coordinates());
            }
            for (AdocLibrary library : legacyLibraries) {
                unpackLegacy(library);
                legacyDone.add(library.coordinates());
            }
            return new Result(copied, linked, unchanged, legacyDone);
        } catch (IOException | UncheckedIOException e) {
            throw new MojoException("Cannot write the AsciiDoc union at " + unionDirectory + ": " + e.getMessage(), e);
        }
    }

    /**
     * Checks that every {@code {adoc}} and {@code {topics}} include under a module's own
     * AsciiDoc source root resolves in the union. An include into the legacy directory
     * through {@code {topics}} also resolves while a legacy library was unpacked there. An
     * unresolved include fails the build with the library that ships the package and the
     * exact dependency block to add, found in the union's manifests, then in the local
     * repository; nothing is fetched.
     *
     * @param sourceDirectory the module's {@code src/docs/asciidoc}
     * @param localRepository the local repository root, searched for the suggestion
     * @return the number of includes that resolved
     * @throws MojoException when an include does not resolve
     */
    public int verifyIncludes(Path sourceDirectory, Path localRepository) {
        List<IncludeReference> references = IncludeScanner.scan(sourceDirectory);
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (IncludeReference reference : references) {
            if (reference.path().indexOf('{') >= 0) {
                // An attribute inside the path is the renderer's to expand.
                continue;
            }
            checked++;
            if (!resolves(reference)) {
                problems.add(describe(reference, sourceDirectory, localRepository));
            }
        }
        if (!problems.isEmpty()) {
            StringBuilder message = new StringBuilder();
            message.append(problems.size()).append(problems.size() == 1 ? " include" : " includes")
                    .append(" in ").append(sourceDirectory).append(problems.size() == 1 ? " does" : " do")
                    .append(" not resolve in the AsciiDoc union ").append(unionDirectory).append(":\n");
            problems.forEach(message::append);
            throw new MojoException(message.toString());
        }
        return checked;
    }

    private boolean resolves(IncludeReference reference) {
        Path inUnion = unionDirectory.resolve(reference.path()).normalize();
        if (inUnion.startsWith(unionDirectory) && Files.exists(inUnion)) {
            return true;
        }
        if ("topics".equals(reference.attribute()) && Files.isDirectory(legacyDirectory)) {
            Path inLegacy = legacyDirectory.resolve(reference.path()).normalize();
            return inLegacy.startsWith(legacyDirectory) && Files.exists(inLegacy);
        }
        return false;
    }

    private String describe(IncludeReference reference, Path sourceDirectory, Path localRepository) {
        Path shown = sourceDirectory.toAbsolutePath().normalize().relativize(
                reference.file().toAbsolutePath().normalize());
        StringBuilder out = new StringBuilder();
        out.append("  ").append(shown).append(':').append(reference.line()).append("  ")
                .append(reference.asWritten()).append('\n');
        Optional<AdocCatalog.Hit> inUnion = AdocCatalog.inUnion(unionDirectory, reference.path());
        if (inUnion.isPresent()) {
            AdocCatalog.Hit hit = inUnion.get();
            out.append("    ").append(hit.coordinates().ga()).append(" (version ")
                    .append(hit.coordinates().version()).append(") is in the union and ships the package ")
                    .append(hit.pkg().name()).append(", but not this file. Check the path.\n");
            return out.toString();
        }
        Optional<AdocCatalog.Hit> local = localRepository == null ? Optional.empty()
                : AdocCatalog.inLocalRepository(localRepository, reference.path());
        if (local.isPresent()) {
            AdocCatalog.Hit hit = local.get();
            out.append("    ").append(hit.coordinates().ga()).append(" ships the package ")
                    .append(hit.pkg().name()).append(" (version ").append(hit.coordinates().version())
                    .append(" in the local repository). Add it to this module's pom.xml:\n")
                    .append(hit.coordinates().dependencyBlock());
            return out.toString();
        }
        out.append("    No library in the union or in the local repository ships a package holding ")
                .append(reference.path()).append(". Declare the library that ships it; the build never")
                .append(" fetches an undeclared library.\n");
        return out.toString();
    }

    // ── exclusivity ──────────────────────────────────────────────────────

    private void refuseSplitPackages(Map<AdocLibrary, AdocManifest> manifests) {
        Map<String, AdocCoordinates> owners = new HashMap<>();
        Set<String> declared = new HashSet<>();
        for (Map.Entry<AdocLibrary, AdocManifest> entry : manifests.entrySet()) {
            AdocCoordinates coordinates = entry.getKey().coordinates();
            declared.add(coordinates.ga());
            for (AdocPackage pkg : entry.getValue().packages()) {
                AdocCoordinates other = owners.putIfAbsent(pkg.name(), coordinates);
                if (other != null && !other.ga().equals(coordinates.ga())) {
                    throw new MojoException("Two libraries ship the package " + pkg.name() + ": "
                            + other.gav() + " and " + coordinates.gav()
                            + ". A package has one owner; rename or move it in one of them.");
                }
            }
        }
        for (Path manifestFile : AdocCatalog.unionManifests(unionDirectory)) {
            AdocManifest existing = AdocManifest.read(manifestFile);
            if (declared.contains(existing.ga())) {
                continue;
            }
            for (AdocPackage pkg : existing.packages()) {
                AdocCoordinates incoming = owners.get(pkg.name());
                if (incoming != null) {
                    throw new MojoException("Two libraries ship the package " + pkg.name() + ": "
                            + existing.ga() + ", already in the union at " + unionDirectory
                            + " and declared by no module of this build, and " + incoming.gav()
                            + ". If " + existing.ga() + " is no longer used, delete "
                            + manifestFile.getParent() + " and the package, or clean the root.");
                }
            }
        }
    }

    // ── one library ──────────────────────────────────────────────────────

    private boolean copyZip(AdocLibrary library, AdocManifest manifest) throws IOException {
        AdocCoordinates coordinates = library.coordinates();
        Path meta = unionDirectory.resolve(coordinates.metaInfPath());
        Path stampFile = meta.resolve(UnionStamp.FILE_NAME);
        String sha256 = sha256(library.zip());
        Optional<UnionStamp> stamp = UnionStamp.read(stampFile);
        if (stamp.isPresent() && UnionStamp.MODE_COPY.equals(stamp.get().mode())
                && sha256.equals(stamp.get().sha256())
                && Files.isRegularFile(meta.resolve(AdocManifest.FILE_NAME))) {
            log.debug("adoc-union: " + coordinates.gav() + " unchanged");
            return false;
        }
        removeEntry(meta);
        extract(library.zip(), unionDirectory);
        for (AdocPackage pkg : manifest.packages()) {
            if (!Files.isDirectory(unionDirectory.resolve(pkg.name()))) {
                log.warn("adoc-union: " + coordinates.gav() + " lists the package " + pkg.name()
                        + " in its manifest but ships no such directory");
            }
        }
        new UnionStamp(coordinates.version(), UnionStamp.MODE_COPY, library.zip().toString(), sha256)
                .write(stampFile);
        log.info("adoc-union: copied " + coordinates.gav() + " (" + manifest.packages().size()
                + " packages) into " + unionDirectory);
        return true;
    }

    private boolean linkMember(AdocLibrary library, AdocManifest manifest) throws IOException {
        AdocCoordinates coordinates = library.coordinates();
        Path meta = unionDirectory.resolve(coordinates.metaInfPath());
        boolean changed = dropPackagesNoLongerShipped(meta, manifest);
        for (AdocPackage pkg : manifest.packages()) {
            Path target = library.sourceRoot().resolve(pkg.name()).toAbsolutePath().normalize();
            Path at = unionDirectory.resolve(pkg.name());
            if (Files.isSymbolicLink(at) && Files.readSymbolicLink(at).equals(target)) {
                continue;
            }
            deleteTree(at);
            Files.createDirectories(at.getParent());
            Files.createSymbolicLink(at, target);
            changed = true;
        }
        changed |= writeIfDifferent(meta.resolve(AdocManifest.FILE_NAME), manifest.toYaml());
        changed |= writeStampIfDifferent(meta.resolve(UnionStamp.FILE_NAME), new UnionStamp(
                coordinates.version(), UnionStamp.MODE_LINK, library.sourceRoot().toString(), null));
        if (changed) {
            log.info("adoc-union: linked " + coordinates.gav() + " (" + manifest.packages().size()
                    + " packages) from " + library.sourceRoot());
        } else {
            log.debug("adoc-union: " + coordinates.gav() + " unchanged");
        }
        return changed;
    }

    private boolean copyTree(AdocLibrary library, AdocManifest manifest) throws IOException {
        AdocCoordinates coordinates = library.coordinates();
        Path meta = unionDirectory.resolve(coordinates.metaInfPath());
        boolean changed = dropPackagesNoLongerShipped(meta, manifest);
        for (AdocPackage pkg : manifest.packages()) {
            Path source = library.sourceRoot().resolve(pkg.name());
            Path at = unionDirectory.resolve(pkg.name());
            if (Files.isSymbolicLink(at)) {
                Files.delete(at);
                changed = true;
            }
            changed |= syncDirectory(source, at);
        }
        changed |= writeIfDifferent(meta.resolve(AdocManifest.FILE_NAME), manifest.toYaml());
        changed |= writeStampIfDifferent(meta.resolve(UnionStamp.FILE_NAME), new UnionStamp(
                coordinates.version(), UnionStamp.MODE_TREE, library.sourceRoot().toString(), null));
        if (changed) {
            log.info("adoc-union: copied " + coordinates.gav() + " (" + manifest.packages().size()
                    + " packages) from " + library.sourceRoot());
        } else {
            log.debug("adoc-union: " + coordinates.gav() + " unchanged");
        }
        return changed;
    }

    private void unpackLegacy(AdocLibrary library) throws IOException {
        Files.createDirectories(legacyDirectory);
        extract(library.zip(), legacyDirectory);
        legacyUnpacked = true;
        log.warn("adoc-union: " + library.coordinates().gav() + " is a legacy library; unpacked into "
                + legacyDirectory + " (ike.adoc.legacy=true)");
    }

    /** Removes the packages an earlier manifest listed that the new one no longer ships. */
    private boolean dropPackagesNoLongerShipped(Path meta, AdocManifest manifest) throws IOException {
        Path manifestFile = meta.resolve(AdocManifest.FILE_NAME);
        if (!Files.isRegularFile(manifestFile)) {
            return false;
        }
        Set<String> kept = new HashSet<>();
        manifest.packages().forEach(p -> kept.add(p.name()));
        boolean changed = false;
        for (AdocPackage old : AdocManifest.read(manifestFile).packages()) {
            if (!kept.contains(old.name())) {
                changed |= deleteTree(unionDirectory.resolve(old.name()));
            }
        }
        return changed;
    }

    /** Removes a library's packages, as its manifest in the union lists them, and its META-INF area. */
    private void removeEntry(Path meta) throws IOException {
        Path manifestFile = meta.resolve(AdocManifest.FILE_NAME);
        if (Files.isRegularFile(manifestFile)) {
            for (AdocPackage old : AdocManifest.read(manifestFile).packages()) {
                deleteTree(unionDirectory.resolve(old.name()));
            }
        }
        deleteTree(meta);
    }

    // ── files ────────────────────────────────────────────────────────────

    private static void extract(Path zip, Path into) throws IOException {
        Path root = into.toAbsolutePath().normalize();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                Path target = root.resolve(entry.getName()).normalize();
                if (!target.startsWith(root)) {
                    throw new IOException("Zip entry outside the target directory: " + entry.getName() + " in " + zip);
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    if (Files.isSymbolicLink(target)) {
                        Files.delete(target);
                    }
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                in.closeEntry();
            }
        }
    }

    /** Makes {@code into} hold exactly the files of {@code from}; returns whether anything changed. */
    private static boolean syncDirectory(Path from, Path into) throws IOException {
        boolean changed = false;
        Set<Path> wanted = new HashSet<>();
        if (Files.isDirectory(from)) {
            try (Stream<Path> walk = Files.walk(from)) {
                for (Path source : walk.filter(Files::isRegularFile).toList()) {
                    Path relative = from.relativize(source);
                    Path target = into.resolve(relative.toString());
                    wanted.add(target);
                    if (!Files.isRegularFile(target)
                            || Files.size(target) != Files.size(source)
                            || !Files.getLastModifiedTime(target).equals(Files.getLastModifiedTime(source))) {
                        Files.createDirectories(target.getParent());
                        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.COPY_ATTRIBUTES);
                        changed = true;
                    }
                }
            }
        }
        if (Files.isDirectory(into)) {
            try (Stream<Path> walk = Files.walk(into)) {
                for (Path present : walk.filter(Files::isRegularFile).toList()) {
                    if (!wanted.contains(present)) {
                        Files.delete(present);
                        changed = true;
                    }
                }
            }
        }
        return changed;
    }

    /** Deletes a file, a symbolic link without following it, or a whole directory. */
    private static boolean deleteTree(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(path);
            return true;
        }
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
        return true;
    }

    private static boolean writeIfDifferent(Path file, String content) throws IOException {
        if (Files.isRegularFile(file) && Files.readString(file, StandardCharsets.UTF_8).equals(content)) {
            return false;
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return true;
    }

    private static boolean writeStampIfDifferent(Path file, UnionStamp stamp) throws IOException {
        if (UnionStamp.read(file).filter(stamp::equals).isPresent()) {
            return false;
        }
        stamp.write(file);
        return true;
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
