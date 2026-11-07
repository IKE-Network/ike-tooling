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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds every include line under one AsciiDoc source root that goes through the
 * {@code {adoc}} attribute or its {@code {topics}} alias. A line inside a comment block,
 * a commented line and an escaped {@code \include::} are not includes.
 */
final class IncludeScanner {

    private static final Pattern INCLUDE = Pattern.compile(
            "^\\s*include::\\{(adoc|topics)}/([^\\[\\s]+)\\[");

    private IncludeScanner() {
    }

    /**
     * Scans every {@code .adoc} file under a root, in path order.
     *
     * @param root the source root; a missing root yields nothing
     * @return the include references found, in file and line order
     */
    static List<IncludeReference> scan(Path root) {
        List<IncludeReference> found = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return found;
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(f -> Files.isRegularFile(f) && f.toString().endsWith(".adoc"))
                    .sorted(Comparator.comparing(Path::toString)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (Path file : files) {
            List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                // A file that is not UTF-8 text holds no include worth checking.
                continue;
            }
            boolean inCommentBlock = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.startsWith("////")) {
                    inCommentBlock = !inCommentBlock;
                    continue;
                }
                if (inCommentBlock) {
                    continue;
                }
                Matcher m = INCLUDE.matcher(line);
                if (m.find()) {
                    found.add(new IncludeReference(file, i + 1, m.group(1), m.group(2)));
                }
            }
        }
        return found;
    }
}
