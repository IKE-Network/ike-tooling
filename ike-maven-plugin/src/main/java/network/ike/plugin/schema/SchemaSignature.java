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

import javax.xml.XMLConstants;
import java.util.List;
import java.util.Optional;

/**
 * The signature an XML Schema declares, read once and held as plain records: the named
 * complex types with their base, their documentation, and their argument positions; and
 * the named simple types that enumerate values (IKE-Network/ike-issues#1104).
 *
 * <p>A signature is what a language's abstract syntax is: a finite set of node types,
 * each with typed positions. HL7's ELM, the Expression Logical Model of the CQL
 * specification, publishes its signature this way in two namespaces, the expression
 * nodes and the System types, and a type is identified by its namespace and its name,
 * since both namespaces declare an Interval.
 *
 * @param types        the named complex types, in declaration order across the schemas
 * @param enumerations the named simple types that restrict a primitive to enumerated values
 */
public record SchemaSignature(List<TypeDefinition> types, List<Enumeration> enumerations) {

    /**
     * Creates a signature, copying both lists.
     *
     * @param types        the named complex types
     * @param enumerations the named enumerations
     */
    public SchemaSignature {
        types = List.copyOf(types);
        enumerations = List.copyOf(enumerations);
    }

    /**
     * Whether a position holds an element, a child in the document's sequence, or an
     * attribute, a named value on the element itself.
     */
    public enum PositionKind {
        /** A child element of the type. */
        ELEMENT,
        /** An attribute of the type. */
        ATTRIBUTE
    }

    /**
     * A reference to a type by namespace and name: a type of the signature, a type of
     * another schema the signature refers to, or a primitive of the XML Schema language.
     *
     * @param namespace the namespace the type is declared in, the XML Schema namespace for
     *                  a primitive
     * @param name      the type's local name, {@code Expression} or {@code string}
     */
    public record TypeReference(String namespace, String name) {
        /**
         * A reference to an XML Schema primitive, {@code string} or {@code QName}.
         *
         * @param name the primitive's local name
         * @return the reference
         */
        public static TypeReference primitive(String name) {
            return new TypeReference(XMLConstants.W3C_XML_SCHEMA_NS_URI, name);
        }

        /**
         * Whether this refers to a primitive of the XML Schema language rather than a
         * declared type.
         *
         * @return true for a schema primitive
         */
        public boolean isPrimitive() {
            return XMLConstants.W3C_XML_SCHEMA_NS_URI.equals(namespace);
        }
    }

    /**
     * One argument position of a type: a child element or an attribute, with the type of
     * value it holds and how many of them the schema allows.
     *
     * @param name          the position's name as the schema spells it, {@code operand}
     * @param kind          element or attribute
     * @param valueType     the type of value the position holds
     * @param minimum       the fewest values allowed, {@code 0} for optional
     * @param maximum       the most values allowed, or {@link #UNBOUNDED}
     * @param documentation the schema's own description of this position on this type,
     *                      empty when the schema gives none
     */
    public record Position(String name, PositionKind kind, TypeReference valueType, int minimum,
                           int maximum, String documentation) {
        /** The maximum recorded for a position the schema leaves unbounded. */
        public static final int UNBOUNDED = -1;
    }

    /**
     * One named complex type of the schema.
     *
     * @param namespace     the namespace the type is declared in
     * @param name          the type's name as the schema spells it, {@code Exists}
     * @param base          the type it extends, if any; {@code Exists} extends
     *                      {@code UnaryExpression}
     * @param isAbstract    whether the schema marks the type abstract
     * @param documentation the schema's own description, empty when the schema gives none
     * @param positions     the type's own positions, not including those inherited from
     *                      its base, in schema order
     */
    public record TypeDefinition(String namespace, String name, Optional<TypeReference> base,
                                 boolean isAbstract, String documentation, List<Position> positions) {
        /**
         * Creates a type definition, copying the positions.
         *
         * @param namespace     the namespace
         * @param name          the type name
         * @param base          the base type, if any
         * @param isAbstract    whether abstract
         * @param documentation the description
         * @param positions     the positions
         */
        public TypeDefinition {
            positions = List.copyOf(positions);
        }

        /**
         * This type as a reference.
         *
         * @return the reference to this type
         */
        public TypeReference reference() {
            return new TypeReference(namespace, name);
        }
    }

    /**
     * One named simple type that enumerates its values, {@code DateTimePrecision} with
     * {@code Year} through {@code Millisecond}.
     *
     * @param namespace     the namespace the enumeration is declared in
     * @param name          the enumeration's name as the schema spells it
     * @param documentation the schema's own description, empty when the schema gives none
     * @param values        the enumerated values in schema order
     */
    public record Enumeration(String namespace, String name, String documentation, List<String> values) {
        /**
         * Creates an enumeration, copying the values.
         *
         * @param namespace     the namespace
         * @param name          the enumeration name
         * @param documentation the description
         * @param values        the values
         */
        public Enumeration {
            values = List.copyOf(values);
        }
    }
}
