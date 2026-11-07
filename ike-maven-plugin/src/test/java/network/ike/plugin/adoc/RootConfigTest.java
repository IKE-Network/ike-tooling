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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The repository's one {@code .asciidoctorconfig}: written once, left alone when current, and
 * replaced whole, so modules building in parallel cannot fail on it.
 */
class RootConfigTest {

    private static final String CONFIG = ":adoc: {asciidoctorconfigdir}/target/generated-sources/adoc\n";

    @TempDir
    Path dir;

    private Path zip(String text) throws IOException {
        return AdocFixtures.zip(dir.resolve("config.zip"), Map.of(RootConfig.FILE_NAME, text));
    }

    @Test
    void writesTheFileOnceAndLeavesACurrentOneAlone() throws Exception {
        Path root = Files.createDirectories(dir.resolve("repo"));
        Path zip = zip(CONFIG);

        assertTrue(RootConfig.install(zip, root));
        assertEquals(CONFIG, Files.readString(root.resolve(RootConfig.FILE_NAME)));
        assertFalse(RootConfig.install(zip, root), "a file with this content is left alone");
    }

    @Test
    void replacesAnOutdatedFileAndLeavesNoTemporaryBehind() throws Exception {
        Path root = Files.createDirectories(dir.resolve("repo"));
        Files.writeString(root.resolve(RootConfig.FILE_NAME), ":topics: old\n");

        assertTrue(RootConfig.install(zip(CONFIG), root));

        assertEquals(CONFIG, Files.readString(root.resolve(RootConfig.FILE_NAME)));
        try (var entries = Files.list(root)) {
            assertEquals(1, entries.count(), "only the file itself");
        }
    }

    @Test
    void aZipWithoutTheFileIsAFailureNamingIt() throws Exception {
        Path root = Files.createDirectories(dir.resolve("repo"));
        Path zip = AdocFixtures.zip(dir.resolve("empty.zip"), Map.of("other.txt", "x"));

        IOException thrown = assertThrows(IOException.class, () -> RootConfig.install(zip, root));
        assertTrue(thrown.getMessage().contains(RootConfig.FILE_NAME), thrown.getMessage());
    }

    /** What failed under the stock unpack: many modules installing the one file at once. */
    @Test
    void parallelInstallsAllSucceedAndTheFileIsWhole() throws Exception {
        Path root = Files.createDirectories(dir.resolve("repo"));
        Path zip = zip(CONFIG);
        ExecutorService pool = Executors.newFixedThreadPool(12);
        try {
            List<Callable<Boolean>> installs = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                installs.add(() -> RootConfig.install(zip, root));
            }
            for (Future<Boolean> install : pool.invokeAll(installs)) {
                install.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(CONFIG, Files.readString(root.resolve(RootConfig.FILE_NAME)));
    }
}
