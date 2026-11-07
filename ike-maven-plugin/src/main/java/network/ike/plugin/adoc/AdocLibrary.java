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

import java.nio.file.Path;
import java.util.Objects;

/**
 * One library to put in the union: either an external zip resolved from a repository, or a
 * module of the same reactor whose AsciiDoc source root is on this machine. Exactly one of
 * {@link #zip()} and {@link #sourceRoot()} is set.
 *
 * @param coordinates the library's coordinates
 * @param zip         the resolved {@code adoc} zip, or null for a reactor member
 * @param sourceRoot  the reactor member's {@code src/docs/asciidoc}, or null for a zip
 */
public record AdocLibrary(AdocCoordinates coordinates, Path zip, Path sourceRoot) {

    /**
     * Checks that the coordinates are present and that exactly one source is set.
     *
     * @param coordinates the coordinates
     * @param zip         the zip, or null
     * @param sourceRoot  the source root, or null
     */
    public AdocLibrary {
        Objects.requireNonNull(coordinates, "coordinates");
        if ((zip == null) == (sourceRoot == null)) {
            throw new IllegalArgumentException(
                    "A library is either a zip or a reactor source root: " + coordinates.gav());
        }
    }

    /**
     * A library resolved as a zip from a repository.
     *
     * @param coordinates the coordinates
     * @param zip         the zip file
     * @return the library
     */
    public static AdocLibrary external(AdocCoordinates coordinates, Path zip) {
        return new AdocLibrary(coordinates, zip, null);
    }

    /**
     * A library that is a module of the same reactor.
     *
     * @param coordinates the coordinates
     * @param sourceRoot  the module's AsciiDoc source root, {@code src/docs/asciidoc}
     * @return the library
     */
    public static AdocLibrary reactor(AdocCoordinates coordinates, Path sourceRoot) {
        return new AdocLibrary(coordinates, null, sourceRoot);
    }

    /**
     * Whether this library is a module of the same reactor.
     *
     * @return true for a reactor member, false for a zip
     */
    public boolean isReactor() {
        return sourceRoot != null;
    }
}
