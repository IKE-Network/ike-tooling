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

/**
 * One {@code include::{adoc}/...[]} or {@code include::{topics}/...[]} line found in a
 * module's AsciiDoc sources.
 *
 * @param file      the file holding the line
 * @param line      the one-based line number
 * @param attribute {@code adoc} or {@code topics}
 * @param path      the path after the attribute, relative to the union, with forward slashes
 */
record IncludeReference(Path file, int line, String attribute, String path) {

    /** The include line as written, for a message. */
    String asWritten() {
        return "include::{" + attribute + "}/" + path + "[]";
    }
}
