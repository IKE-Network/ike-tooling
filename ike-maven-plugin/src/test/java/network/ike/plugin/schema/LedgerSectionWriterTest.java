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
import network.ike.plugin.schema.SchemaSignature.PositionKind;
import network.ike.plugin.schema.SchemaSignature.TypeDefinition;
import network.ike.plugin.schema.SchemaSignature.TypeReference;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerSectionWriterTest {

    private static final String ELM = "urn:hl7-org:elm:r1";
    private static final String SYSTEM = "urn:hl7-org:elm-types:r1";

    private static final LedgerSectionWriter.Options OPTIONS = new LedgerSectionWriter.Options(
            "network.ike.foundation.ike.terms", "ElmSignatureSet", "ELM", "ELM ",
            Map.of(SYSTEM, "ELM System "), "the ELM specification",
            "cqframework/clinical_quality_language v5.3.0", "Ike.INCEPTION", "IkeTerm",
            "IkeTerm.MODEL_CONCEPT");

    private static TypeReference elm(String name) {
        return new TypeReference(ELM, name);
    }

    /** The source with the string-concatenation seams removed, so a wrapped definition reads as one line. */
    private static String joined(String source) {
        return source.replace("\"\n                        + \"", "");
    }

    private static SchemaSignature small() {
        TypeDefinition element = new TypeDefinition(ELM, "Element", Optional.empty(), true, "",
                List.of(new Position("localId", PositionKind.ATTRIBUTE, TypeReference.primitive("string"), 0, 1, ""),
                        new Position("annotation", PositionKind.ELEMENT,
                                new TypeReference("urn:hl7-org:cql-annotations:r1", "CqlToElmBase"), 0, 1, "")));
        TypeDefinition unary = new TypeDefinition(ELM, "UnaryExpression", Optional.of(elm("Element")), true, "",
                List.of(new Position("operand", PositionKind.ELEMENT, elm("Element"), 1, 1, "The one operand.")));
        TypeDefinition exists = new TypeDefinition(ELM, "Exists", Optional.of(elm("UnaryExpression")), false,
                "The Exists operator returns true if the list contains any elements.", List.of());
        TypeDefinition interval = new TypeDefinition(ELM, "Interval", Optional.of(elm("Element")), false,
                "The Interval selector.", List.of());
        TypeDefinition systemInterval = new TypeDefinition(SYSTEM, "Interval", Optional.empty(), true, "", List.of());
        TypeDefinition dateTimeComponent = new TypeDefinition(ELM, "DateTimeComponentFrom",
                Optional.of(elm("UnaryExpression")), false, "",
                List.of(new Position("precision", PositionKind.ATTRIBUTE, elm("DateTimePrecision"), 0, 1, "")));
        Enumeration precision = new Enumeration(ELM, "DateTimePrecision", "", List.of("Year", "Day"));
        // Declared out of order on purpose: Exists before its base.
        return new SchemaSignature(List.of(exists, unary, element, interval, systemInterval, dateTimeComponent),
                List.of(precision));
    }

    @Test
    void writesEveryTypeAfterItsBaseWithItsDocumentationAsDefinition() {
        String source = LedgerSectionWriter.write(small(), OPTIONS);

        assertThat(source).contains("final class ElmSignatureSet {");
        assertThat(source).contains("set.concept(\"ELM Element (ELM)\").at(inception)");
        assertThat(source).contains("set.concept(\"ELM Exists (ELM)\").at(inception)");
        assertThat(source.indexOf("\"ELM Element (ELM)\").at(inception)"))
                .isLessThan(source.indexOf("\"ELM UnaryExpression (ELM)\").at(inception)"));
        assertThat(source.indexOf("\"ELM UnaryExpression (ELM)\").at(inception)"))
                .isLessThan(source.indexOf("\"ELM Exists (ELM)\").at(inception)"));
        assertThat(source).contains(".isA(set.conceptRef(\"ELM UnaryExpression (ELM)\"))");
        assertThat(joined(source)).contains("From the ELM specification: The Exists operator returns true if the list contains any elements.");
        assertThat(joined(source)).contains("The schema marks it abstract");
    }

    @Test
    void namesTypesByNamespaceSoTwoIntervalsStayApart() {
        String source = LedgerSectionWriter.write(small(), OPTIONS);

        assertThat(source).contains("set.concept(\"ELM Interval (ELM)\")");
        assertThat(source).contains("set.concept(\"ELM System Interval (ELM)\")");
        assertThat(source).contains("set.concept(\"ELM external CqlToElmBase (ELM)\")");
        assertThat(source).contains("set.conceptRef(\"ELM external CqlToElmBase (ELM)\"), 0, 1,");
        assertThat(source).contains("set.conceptRef(\"ELM DateTimePrecision (ELM)\"), 0, 1,");
        assertThat(source).doesNotContain("ELM external DateTimePrecision");
    }

    @Test
    void spellsApartPositionNamesThatDifferOnlyByCase() {
        TypeDefinition code = new TypeDefinition(ELM, "Code", Optional.empty(), false, "",
                List.of(new Position("codeSystem", PositionKind.ELEMENT, elm("Code"), 0, 1, "")));
        TypeDefinition codeDef = new TypeDefinition(ELM, "CodeDef", Optional.empty(), false, "",
                List.of(new Position("codesystem", PositionKind.ELEMENT, elm("Code"), 0, 1, "")));

        String source = LedgerSectionWriter.write(new SchemaSignature(List.of(code, codeDef), List.of()), OPTIONS);

        assertThat(source).contains("set.concept(\"ELM codeSystem position, mixed case (ELM)\")");
        assertThat(source).contains("set.concept(\"ELM codesystem position, lower case (ELM)\")");
        assertThat(source).contains("set.conceptRef(\"ELM codesystem position, lower case (ELM)\"), set.conceptRef(\"ELM Code (ELM)\"), 0, 1,");
        assertThat(joined(source)).contains("The schema spells it lower case, and spells another position the same way but for case.");
    }

    @Test
    void prefixesAlwaysEndWithOneSpace() {
        LedgerSectionWriter.Options typedWithoutSpaces = new LedgerSectionWriter.Options(
                "p", "C", "ELM", "ELM", Map.of(SYSTEM, "ELM System"), "the ELM specification", "v5.3.0",
                "Ike.INCEPTION", "IkeTerm", "IkeTerm.MODEL_CONCEPT");

        String source = LedgerSectionWriter.write(small(), typedWithoutSpaces);

        assertThat(source).contains("set.concept(\"ELM System Interval (ELM)\")");
        assertThat(source).contains("set.concept(\"ELM Exists (ELM)\")");
    }

    @Test
    void writesPositionsPrimitivesEnumerationsAndThePattern() {
        String source = LedgerSectionWriter.write(small(), OPTIONS);

        assertThat(source).contains("set.concept(\"ELM operand position (ELM)\")");
        assertThat(source).contains("set.concept(\"ELM localId position (ELM)\")");
        assertThat(source).contains("set.concept(\"ELM primitive string (ELM)\")");
        assertThat(source).contains("set.concept(\"ELM DateTimePrecision (ELM)\")");
        assertThat(source).contains("set.concept(\"ELM DateTimePrecision Year (ELM)\")");
        assertThat(source).contains("set.pattern(TYPE_POSITION_PATTERN_FQN).at(inception)");
        assertThat(source).contains("IkeTerm.INTEGER_FIELD");
        assertThat(source).contains("set.uuidFor(\"Type position: ELM UnaryExpression operand\")");
        assertThat(source).contains("set.conceptRef(\"ELM operand position (ELM)\"), set.conceptRef(\"ELM Element (ELM)\"), 1, 1,");
        assertThat(source).contains("\"The one operand.\")");
    }

    @Test
    void wrapsLongDefinitionsIntoFragmentsOnSpaces() {
        String text = "word ".repeat(40).trim();

        List<String> fragments = LedgerSectionWriter.fragments(text);

        assertThat(fragments).hasSizeGreaterThan(1);
        assertThat(String.join("", fragments)).isEqualTo(text);
        for (String fragment : fragments) {
            assertThat(fragment.length()).isLessThanOrEqualTo(87);
        }
        for (int i = 1; i < fragments.size(); i++) {
            assertThat(fragments.get(i)).startsWith(" ");
        }
    }

    @Test
    void escapesQuotesInDefinitions() {
        TypeDefinition quoted = new TypeDefinition(ELM, "Quoted", Optional.empty(), false,
                "Returns the \"string\" as is.", List.of());

        String source = LedgerSectionWriter.write(new SchemaSignature(List.of(quoted), List.of()), OPTIONS);

        assertThat(source).contains("Returns the \\\"string\\\" as is.");
    }
}
