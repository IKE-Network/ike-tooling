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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * What the union goal remembers about a library it put in the union, beside the library's
 * manifest as {@code META-INF/ike/<group>/<artifact>/union.properties}: the version, whether
 * the library was copied or linked, where it came from, and for a copy the digest of the
 * zip, so an unchanged library is not rewritten on the next run.
 *
 * @param version the library's version
 * @param mode    {@value #MODE_COPY} or {@value #MODE_LINK}
 * @param source  the zip copied from, or the source root linked to
 * @param sha256  the zip's SHA-256 for a copy, or null for a link
 */
record UnionStamp(String version, String mode, String source, String sha256) {

    /** The stamp's file name. */
    static final String FILE_NAME = "union.properties";

    /** The mode of a library copied from its zip. */
    static final String MODE_COPY = "copy";

    /** The mode of a reactor member linked into the union. */
    static final String MODE_LINK = "link";

    /** The mode of a reactor member copied from its source tree. */
    static final String MODE_TREE = "tree";

    static Optional<UnionStamp> read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        }
        return Optional.of(new UnionStamp(p.getProperty("version", ""), p.getProperty("mode", ""),
                p.getProperty("source", ""), p.getProperty("sha256")));
    }

    void write(Path file) throws IOException {
        Properties p = new Properties();
        p.setProperty("version", version);
        p.setProperty("mode", mode);
        p.setProperty("source", source);
        if (sha256 != null) {
            p.setProperty("sha256", sha256);
        }
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file)) {
            p.store(out, "written by ike:adoc-union; the union is rebuilt from the declared libraries");
        }
    }
}
