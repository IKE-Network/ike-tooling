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

import dev.ikm.tinkar.entity.builder.ActiveStamp;
import dev.ikm.tinkar.entity.builder.KnowledgeSet;
import dev.ikm.tinkar.entity.builder.Stamp;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.terms.EntityProxy;
import dev.ikm.tinkar.terms.KernelConcept;

/**
 * The concept section of the @tag@ ledger: the module concept, the set root, and
 * the set's content concepts. Sections name what, never when — delivery phasing
 * lives in issues, not source.
 */
final class ConceptSet {

    // The base taxonomy's module and model-concept parents, named by identity: they are the IKE
    // starter set's components, and this set does not depend on that set's bindings.
    private static final EntityProxy.Concept MODULE_SOLOR =
            EntityProxy.Concept.make("Module (SOLOR)", PublicIds.of("40d1c869-b509-32f8-b735-836eac577a67"));
    private static final EntityProxy.Concept MODEL_CONCEPT =
            EntityProxy.Concept.make("Model concept (SOLOR)", PublicIds.of("7bbd4210-381c-11e7-9598-0800200c9a66"));

    private ConceptSet() {
    }

    /**
     * Composes this section's declarations into the session.
     *
     * @param set the knowledge set (the session)
     */
    static void compose(KnowledgeSet set) {
        // TODO(curator identity): author is KernelConcept.USER pending the curator
        // identity decision.
        ActiveStamp inception = Stamp.active("@inceptionTime@",
                KernelConcept.USER, @className@.MODULE, KernelConcept.DEVELOPMENT_PATH);

        // Both anchor into the base taxonomy so a KB that loads base + this set
        // navigates to it from the root — no orphan forest.
        set.concept("@tag@ module (@tag@)").at(inception)
                .synonym("@tag@ module")
                .definition("The module scoping every stamp of the @tag@ content"
                        + " set; the export dimension for this knowledge.")
                .isA(MODULE_SOLOR);

        set.concept("@tag@ root (@tag@)").at(inception)
                .synonym("@tag@ root")
                .definition("Root concept of the @setName@ @familyLabelLower@.")
                .isA(MODEL_CONCEPT);

        // TODO: declare this set's content concepts here, classified under the
        // set root (or a dual-parented sub-root; see dev-knowledge-set-creation).
    }
}
