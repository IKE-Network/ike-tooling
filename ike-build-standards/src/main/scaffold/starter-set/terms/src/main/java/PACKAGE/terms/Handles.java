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

import dev.ikm.tinkar.entity.builder.KnowledgeSet;
import dev.ikm.tinkar.terms.EntityProxy;

/**
 * Ledger-internal handles for the @tag@ knowledge set: the session root and the
 * reference handles the sections cite. Handles are conveniences over derived
 * identities — never identity sources. Consumers use the generated bindings class
 * (@basePackage@.bindings.@className@Terms), not this one.
 */
public final class @className@ {

    private @className@() {
    }

    /**
     * The knowledge set — its UUID is the permanent type-5 namespace from which
     * every identity in the set derives. Never change it.
     */
    public static final KnowledgeSet SET = KnowledgeSet.of("@setUuid@");

    /** The set's module concept — the export dimension for this knowledge. */
    public static final EntityProxy.Concept MODULE =
            SET.conceptRef("@tag@ module (@tag@)");

    /** The set's root concept. */
    public static final EntityProxy.Concept ROOT =
            SET.conceptRef("@tag@ root (@tag@)");
}
