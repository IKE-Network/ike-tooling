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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The one {@code .asciidoctorconfig} of a repository, at its root, so the IDE preview of every
 * module resolves includes in the union (design topic {@code dev-adoc-packages}). The file comes
 * from the {@code asciidoctorconfig} classifier of {@code ike-build-standards}.
 *
 * <p>It is installed by the goal that writes the union and not by a stock unpack, because every
 * module with AsciiDoc sources would write the one file: under {@code -T}, which IKE repositories
 * use by default, a stock unpack in two modules at once fails the build. The caller holds the
 * union's lock. A file that already has this content is left alone, and a new one arrives by
 * atomic move, so a concurrent reader never sees a partial file.
 */
public final class RootConfig {

    /** The file's name, at the root of the classifier's zip and of the repository. */
    public static final String FILE_NAME = ".asciidoctorconfig";

    /** The classifier of the {@code ike-build-standards} zip that holds the file. */
    public static final String CLASSIFIER = "asciidoctorconfig";

    private RootConfig() {
    }

    /**
     * Installs the file from a classifier zip into a repository root.
     *
     * @param zip  the classifier's zip
     * @param root the repository root
     * @return whether the file was written; false when it already had this content
     * @throws IOException if the zip holds no such file, or the file cannot be written
     */
    public static boolean install(Path zip, Path root) throws IOException {
        byte[] wanted = read(zip).orElseThrow(() -> new IOException(zip + " holds no " + FILE_NAME));
        Path target = root.resolve(FILE_NAME);
        if (Files.isRegularFile(target) && java.util.Arrays.equals(Files.readAllBytes(target), wanted)) {
            return false;
        }
        Path written = Files.createTempFile(root, FILE_NAME, ".tmp");
        try {
            Files.write(written, wanted);
            Files.move(written, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(written);
        }
        return true;
    }

    private static Optional<byte[]> read(Path zip) throws IOException {
        try (ZipFile file = new ZipFile(zip.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry entry = file.getEntry(FILE_NAME);
            if (entry == null) {
                return Optional.empty();
            }
            try (var in = file.getInputStream(entry)) {
                return Optional.of(in.readAllBytes());
            }
        }
    }
}
