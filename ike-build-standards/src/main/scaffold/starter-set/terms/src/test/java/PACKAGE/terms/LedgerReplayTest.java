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
package @basePackage@.terms;

import dev.ikm.tinkar.common.service.CachingService;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.service.ServiceKeys;
import dev.ikm.tinkar.common.service.ServiceProperties;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.builder.KnowledgeSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replays the @tag@ ledger into the real in-memory store and verifies the seed
 * declarations — the no-mocks house rule; one store lifecycle per JVM.
 */
class LedgerReplayTest {

    private static KnowledgeSet set;

    @BeforeAll
    static void replay() throws Exception {
        CachingService.clearAll();
        ServiceProperties.set(ServiceKeys.DATA_STORE_ROOT,
                Files.createTempDirectory("@slug@-replay").toFile());
        PrimitiveData.selectControllerByName("Load Ephemeral Store");
        PrimitiveData.start();
        set = new @className@Source().compose();
        set.write();
        set.write(); // replay is idempotent
    }

    @AfterAll
    static void stop() {
        PrimitiveData.stop();
    }

    @Test
    @DisplayName("The seed declarations replay: module and root, anchored")
    void seedDeclarations() {
        assertEquals(1, EntityHandle.get(@className@.MODULE.nid()).expectConcept()
                .versions().size());
        assertEquals(1, EntityHandle.get(@className@.ROOT.nid()).expectConcept()
                .versions().size());
        assertTrue(set.declarations().size() >= 2);
    }
}
