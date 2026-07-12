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
import dev.ikm.tinkar.entity.builder.KnowledgeSetSource;

/**
 * The @tag@ ledger's composition entry: build tooling discovers this source,
 * composes the sections, and replays the session.
 */
public final class @className@Source implements KnowledgeSetSource {

    /** Creates the source (ServiceLoader requirement). */
    public @className@Source() {
    }

    @Override
    public KnowledgeSet compose() {
        ConceptSet.compose(@className@.SET);
        // TODO: compose further functional sections here (patterns, meanings).
        return @className@.SET;
    }
}
