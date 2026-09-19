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
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class XmlSchemaReaderTest {

    private static final String SMALL = """
            <?xml version="1.0" encoding="UTF-8"?>
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:test" xmlns="urn:test" xmlns:a="urn:other">
              <xs:complexType name="Element" abstract="true">
                <xs:annotation><xs:documentation>The base of every node.</xs:documentation></xs:annotation>
                <xs:attribute name="localId" type="xs:string" use="optional"/>
              </xs:complexType>
              <xs:complexType name="Expression" abstract="true">
                <xs:complexContent><xs:extension base="Element"/></xs:complexContent>
              </xs:complexType>
              <xs:complexType name="UnaryExpression" abstract="true">
                <xs:complexContent>
                  <xs:extension base="Expression">
                    <xs:sequence>
                      <xs:element name="operand" type="Expression" minOccurs="1" maxOccurs="1">
                        <xs:annotation><xs:documentation>The one operand.</xs:documentation></xs:annotation>
                      </xs:element>
                    </xs:sequence>
                  </xs:extension>
                </xs:complexContent>
              </xs:complexType>
              <xs:complexType name="Exists">
                <xs:annotation><xs:documentation>The Exists operator returns true if the list
                  contains any elements.

                  If the argument is null, the result is false.</xs:documentation></xs:annotation>
                <xs:complexContent><xs:extension base="UnaryExpression"/></xs:complexContent>
              </xs:complexType>
              <xs:complexType name="Retrieve">
                <xs:complexContent>
                  <xs:extension base="Expression">
                    <xs:sequence>
                      <xs:element name="codes" type="Expression" minOccurs="0" maxOccurs="1"/>
                      <xs:element name="include" type="Element" minOccurs="0" maxOccurs="unbounded"/>
                      <xs:element name="annotation" type="a:Note" minOccurs="0" maxOccurs="1"/>
                    </xs:sequence>
                    <xs:attribute name="dataType" type="xs:QName" use="required"/>
                  </xs:extension>
                </xs:complexContent>
              </xs:complexType>
              <xs:simpleType name="DateTimePrecision">
                <xs:annotation><xs:documentation>The calendar units.</xs:documentation></xs:annotation>
                <xs:restriction base="xs:string">
                  <xs:enumeration value="Year"/>
                  <xs:enumeration value="Day"/>
                </xs:restriction>
              </xs:simpleType>
              <xs:simpleType name="Name">
                <xs:restriction base="xs:string"/>
              </xs:simpleType>
            </xs:schema>
            """;

    @Test
    void readsTypesBasesDocumentationAndPositions(@TempDir Path dir) throws IOException {
        Path schema = dir.resolve("small.xsd");
        Files.writeString(schema, SMALL);

        SchemaSignature signature = new XmlSchemaReader().read(List.of(schema));

        assertThat(signature.types()).extracting(TypeDefinition::name)
                .containsExactly("Element", "Expression", "UnaryExpression", "Exists", "Retrieve");
        TypeDefinition exists = signature.types().get(3);
        assertThat(exists.base()).isEqualTo(Optional.of(new TypeReference("urn:test", "UnaryExpression")));
        assertThat(exists.namespace()).isEqualTo("urn:test");
        assertThat(exists.isAbstract()).isFalse();
        assertThat(exists.documentation()).isEqualTo(
                "The Exists operator returns true if the list contains any elements. If the argument is null, the result is false.");
        assertThat(exists.positions()).isEmpty();

        TypeDefinition element = signature.types().get(0);
        assertThat(element.isAbstract()).isTrue();
        assertThat(element.base()).isEmpty();
        assertThat(element.positions()).containsExactly(
                new Position("localId", PositionKind.ATTRIBUTE, TypeReference.primitive("string"), 0, 1, ""));

        TypeDefinition unary = signature.types().get(2);
        assertThat(unary.positions()).containsExactly(
                new Position("operand", PositionKind.ELEMENT, new TypeReference("urn:test", "Expression"), 1, 1,
                        "The one operand."));

        TypeDefinition retrieve = signature.types().get(4);
        assertThat(retrieve.positions()).containsExactly(
                new Position("codes", PositionKind.ELEMENT, new TypeReference("urn:test", "Expression"), 0, 1, ""),
                new Position("include", PositionKind.ELEMENT, new TypeReference("urn:test", "Element"), 0,
                        Position.UNBOUNDED, ""),
                new Position("annotation", PositionKind.ELEMENT, new TypeReference("urn:other", "Note"), 0, 1, ""),
                new Position("dataType", PositionKind.ATTRIBUTE, TypeReference.primitive("QName"), 1, 1, ""));
    }

    @Test
    void readsAnUnnamedInnerTypeAsAKindNamedAfterItsElement(@TempDir Path dir) throws IOException {
        Path schema = dir.resolve("library.xsd");
        Files.writeString(schema, """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:test"
                           xmlns="urn:test" elementFormDefault="qualified">
                  <xs:complexType name="UsingDef">
                    <xs:attribute name="localIdentifier" type="xs:string" use="required"/>
                  </xs:complexType>
                  <xs:complexType name="Library">
                    <xs:sequence>
                      <xs:element name="usings" minOccurs="0">
                        <xs:annotation><xs:documentation>Set of data models referenced.</xs:documentation></xs:annotation>
                        <xs:complexType>
                          <xs:sequence>
                            <xs:element name="def" type="UsingDef" minOccurs="0" maxOccurs="unbounded"/>
                          </xs:sequence>
                        </xs:complexType>
                      </xs:element>
                    </xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """);

        SchemaSignature signature = new XmlSchemaReader().read(List.of(schema));

        assertThat(signature.types()).extracting(TypeDefinition::name)
                .containsExactlyInAnyOrder("UsingDef", "Library", "Library usings");
        TypeDefinition usings = signature.types().stream()
                .filter(type -> type.name().equals("Library usings")).findFirst().orElseThrow();
        assertThat(usings.base()).isEmpty();
        assertThat(usings.isAbstract()).isFalse();
        assertThat(usings.documentation()).isEqualTo("Set of data models referenced.");
        assertThat(usings.positions()).containsExactly(
                new Position("def", PositionKind.ELEMENT, new TypeReference("urn:test", "UsingDef"), 0,
                        Position.UNBOUNDED, ""));
        TypeDefinition library = signature.types().stream()
                .filter(type -> type.name().equals("Library")).findFirst().orElseThrow();
        assertThat(library.positions()).containsExactly(
                new Position("usings", PositionKind.ELEMENT, new TypeReference("urn:test", "Library usings"), 0, 1,
                        "Set of data models referenced."));
    }

    @Test
    void readsEnumerationsAndSkipsPlainRestrictions(@TempDir Path dir) throws IOException {
        Path schema = dir.resolve("small.xsd");
        Files.writeString(schema, SMALL);

        SchemaSignature signature = new XmlSchemaReader().read(List.of(schema));

        assertThat(signature.enumerations()).containsExactly(
                new Enumeration("urn:test", "DateTimePrecision", "The calendar units.", List.of("Year", "Day")));
    }

    @Test
    void refusesSchemaFeaturesOutsideASignature(@TempDir Path dir) throws IOException {
        Path schema = dir.resolve("choice.xsd");
        Files.writeString(schema, """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:complexType name="Either">
                    <xs:choice><xs:element name="a" type="xs:string"/><xs:element name="b" type="xs:string"/></xs:choice>
                  </xs:complexType>
                </xs:schema>
                """);

        assertThatThrownBy(() -> new XmlSchemaReader().read(List.of(schema)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("xs:choice");
    }

    @Test
    void refusesATypeDeclaredTwiceInOneNamespaceAndAllowsItAcrossTwo(@TempDir Path dir) throws IOException {
        Path one = dir.resolve("one.xsd");
        Path two = dir.resolve("two.xsd");
        Path other = dir.resolve("other.xsd");
        String twin = """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:same">
                  <xs:complexType name="Twin"/>
                </xs:schema>
                """;
        Files.writeString(one, twin);
        Files.writeString(two, twin);
        Files.writeString(other, """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:other">
                  <xs:complexType name="Twin"/>
                </xs:schema>
                """);

        assertThatThrownBy(() -> new XmlSchemaReader().read(List.of(one, two)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Twin");
        assertThat(new XmlSchemaReader().read(List.of(one, other)).types()).hasSize(2);
    }
}
