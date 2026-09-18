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
package network.ike.plugin.schema;

import network.ike.plugin.schema.SchemaSignature.Enumeration;
import network.ike.plugin.schema.SchemaSignature.Position;
import network.ike.plugin.schema.SchemaSignature.TypeDefinition;
import network.ike.plugin.schema.SchemaSignature.TypeReference;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads HL7's ELM, the Expression Logical Model of the CQL specification, from the five
 * schemas pinned as fixtures, and checks the counts and the shape a ledger section will
 * carry (IKE-Network/ike-issues#1104).
 */
class ElmSignatureImportTest {

    private static final String ELM = "urn:hl7-org:elm:r1";
    private static final String SYSTEM = "urn:hl7-org:elm-types:r1";

    private static Path fixture(String name) throws URISyntaxException {
        return Path.of(ElmSignatureImportTest.class.getResource("/elm/" + name).toURI());
    }

    private static SchemaSignature elm() throws IOException, URISyntaxException {
        return new XmlSchemaReader().read(List.of(
                fixture("types.xsd"), fixture("expression.xsd"),
                fixture("clinicalexpression.xsd"), fixture("library.xsd")));
    }

    @Test
    void theFourSchemasHoldTheSignatureCounts() throws IOException, URISyntaxException {
        SchemaSignature signature = elm();

        assertThat(signature.types()).hasSize(23 + 215 + 27 + 5);
        assertThat(signature.enumerations()).extracting(Enumeration::name)
                .contains("DateTimePrecision");
        assertThat(signature.types()).filteredOn(type -> type.name().equals("Interval"))
                .extracting(TypeDefinition::namespace).containsExactlyInAnyOrder(ELM, SYSTEM);
    }

    @Test
    void existsExtendsUnaryExpressionAndCarriesTheSpecificationsWords() throws IOException, URISyntaxException {
        SchemaSignature signature = elm();

        TypeDefinition exists = signature.types().stream()
                .filter(type -> type.name().equals("Exists")).findFirst().orElseThrow();
        assertThat(exists.base()).isEqualTo(Optional.of(new TypeReference(ELM, "UnaryExpression")));
        assertThat(exists.documentation()).startsWith("The Exists operator returns true if the list contains any elements.");

        TypeDefinition retrieve = signature.types().stream()
                .filter(type -> type.name().equals("Retrieve")).findFirst().orElseThrow();
        assertThat(retrieve.base()).isEqualTo(Optional.of(new TypeReference(ELM, "Expression")));
        assertThat(retrieve.positions()).extracting(Position::name)
                .contains("codes", "dateRange", "dataType", "codeProperty");
    }

    @Test
    void everyBaseResolvesWithinTheSignature() throws IOException, URISyntaxException {
        SchemaSignature signature = elm();
        List<TypeReference> declared = signature.types().stream().map(TypeDefinition::reference).toList();

        for (TypeDefinition type : signature.types()) {
            type.base().ifPresent(base -> assertThat(declared)
                    .withFailMessage("%s extends %s, which no schema declares", type.name(), base)
                    .contains(base));
        }
    }

    @Test
    void theSectionRendersWithoutAnUnresolvedTypeReference() throws IOException, URISyntaxException {
        String source = LedgerSectionWriter.write(elm(), new LedgerSectionWriter.Options(
                "network.ike.foundation.ike.terms", "ElmSignatureSet", "ELM", "ELM ",
                Map.of(SYSTEM, "ELM System "), "the ELM specification",
                "cqframework/clinical_quality_language v5.3.0", "Ike.INCEPTION", "IkeTerm",
                "IkeTerm.MODEL_CONCEPT"));

        assertThat(source).contains("set.concept(\"ELM Exists (ELM)\").at(inception)");
        assertThat(source).contains("static final String ROOT_FQN = \"ELM node catalog (ELM)\";");
        assertThat(source).doesNotContain("ELM signature (ELM)");
        assertThat(source).contains("set.concept(\"ELM property form (ELM)\")");
        assertThat(source).contains("set.concept(\"ELM edge form (ELM)\")");
        assertThat(source).contains("set.concept(\"ELM form field (ELM)\")");
        // Retrieve's dataType is an attribute, its codes an element: property form and edge form.
        assertThat(source).containsPattern("Type position: ELM Retrieve dataType[^;]*propertyForm\\)");
        assertThat(source).containsPattern("Type position: ELM Retrieve codes[^;]*edgeForm\\)");
        assertThat(source).contains("set.concept(\"ELM System Interval (ELM)\")");
        assertThat(source).contains("set.concept(\"ELM Interval (ELM)\")");
        assertThat(source).contains("set.concept(\"ELM external CqlToElmBase (ELM)\")");
        assertThat(source).contains("set.concept(\"ELM DateTimePrecision Millisecond (ELM)\")");
        assertThat(source).doesNotContain(", root, ");
    }
}
