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
package network.ike.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The genesis plan's pure derivations: name family, token expansion, and file
 * mapping — the facts the draft shows are exactly the facts publish executes.
 */
class StarterSetPlanTest {

    private static StarterSetPlan plan(String setName, String tag) {
        return plan(setName, tag, null);
    }

    private static StarterSetPlan plan(String setName, String tag, String artifactSuffix) {
        return new StarterSetPlan(setName, "network.ike.cql", tag, artifactSuffix,
                "7b6a5c4d-3e2f-5a1b-8c9d-0e1f2a3b4c5d", "1-SNAPSHOT", "131",
                "1.127.2-SNAPSHOT", "1-SNAPSHOT", "235-SNAPSHOT", "90-SNAPSHOT",
                "PB g:a:zip:reasoned-pb:1", "2026-07-11T00:00:00Z");
    }

    @Test
    @DisplayName("The name family derives from the one human decision")
    void nameFamily() {
        StarterSetPlan plan = plan("CQL Clause", null);
        assertThat(plan.token("slug")).isEqualTo("cql-clause");
        // artifactSuffix defaults to "starter-set" when omitted/blank.
        assertThat(plan.token("artifactRoot")).isEqualTo("cql-clause-starter-set");
        // Acronym casing is preserved — the ratified (IKE) tag is all-caps.
        assertThat(plan.token("className")).isEqualTo("CQLClause");
        assertThat(plan.token("tag")).isEqualTo("CQLClause");
        assertThat(plan.token("basePackage")).isEqualTo("network.ike.cql.cqlclause");

        assertThat(plan("CQL Clause", "IKE").token("tag")).isEqualTo("IKE");
        assertThat(plan("CQL Clause", null, "starter-knowledge").token("artifactRoot"))
                .isEqualTo("cql-clause-starter-knowledge");
        // familyLabel/familyLabelLower are the human-readable prose form of the
        // suffix — free text (POM <name>, READMEs, docs) must not hardcode
        // "knowledge" regardless of the chosen artifactSuffix.
        assertThat(plan.token("familyLabel")).isEqualTo("Starter Set");
        assertThat(plan.token("familyLabelLower")).isEqualTo("starter set");
        assertThat(plan("CQL Clause", null, "starter-knowledge").token("familyLabel"))
                .isEqualTo("Starter Knowledge");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> plan("  ", null)).withMessageContaining("setName");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StarterSetPlan("X", " ", null, null, "u", "v", "p",
                        "c", "pr", "t", "k", "b", "i"))
                .withMessageContaining("groupId");
    }

    @Test
    @DisplayName("Expansion replaces every token and rejects unknown ones")
    void expansion() {
        StarterSetPlan plan = plan("CQL Clause", null);
        assertThat(plan.expand("module @basePackage@.terms // (@tag@) ${project.version}"))
                .isEqualTo("module network.ike.cql.cqlclause.terms // (CQLClause) ${project.version}");
        assertThatIllegalStateException()
                .isThrownBy(() -> plan.expand("@noSuchToken@"))
                .withMessageContaining("noSuchToken");
    }

    @Test
    @DisplayName("Template paths map to the generated project's layout")
    void fileMapping() {
        StarterSetPlan plan = plan("CQL Clause", null);
        assertThat(plan.targetPath("gitignore")).isEqualTo(".gitignore");
        assertThat(plan.targetPath("mvn/jvm.config")).isEqualTo(".mvn/jvm.config");
        assertThat(plan.targetPath("pom.xml")).isEqualTo("pom.xml");
        assertThat(plan.targetPath("terms/pom.xml")).isEqualTo("cql-clause-terms/pom.xml");
        assertThat(plan.targetPath("terms/src/main/java/PACKAGE/terms/Handles.java"))
                .isEqualTo("cql-clause-terms/src/main/java/network/ike/cql/cqlclause/terms/CQLClause.java");
        assertThat(plan.targetPath("terms/src/main/java/PACKAGE/terms/SetSource.java"))
                .isEqualTo("cql-clause-terms/src/main/java/network/ike/cql/cqlclause/terms/CQLClauseSource.java");
        assertThat(plan.targetPath("doc/src/docs/asciidoc/guide.adoc"))
                .isEqualTo("cql-clause-doc/src/docs/asciidoc/cql-clause-guide.adoc");
        assertThat(plan.targetPath("kb/pom.xml")).isEqualTo("cql-clause-kb/pom.xml");
    }
}
