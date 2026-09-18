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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Writes a {@link SchemaSignature} as a ledger section in the KnowledgeSet DSL, a node catalog, the same
 * shape the hand-written sections of a starter set have (IKE-Network/ike-issues#1104):
 * one concept per type with the schema's own documentation as its definition, is-a from
 * the extension base, one concept per argument position, per schema primitive, and per
 * type of another schema the catalog refers to, one concept per enumeration and per
 * enumerated value, and a type-position pattern whose semantics record, on each type, its
 * positions with their value type and cardinality.
 *
 * <p>Every label carries a prefix chosen per namespace, {@code ELM } for the expression
 * nodes and {@code ELM System } for the System types, because koncept identifiers are
 * derived from labels alone, a foundation already holds concepts named Concept, Code,
 * Quantity, Interval, and List, and ELM itself declares an Interval in both of its
 * namespaces. The generated class is a source file to check in and regenerate, never to
 * edit.
 */
public final class LedgerSectionWriter {

    private static final int FRAGMENT_WIDTH = 86;
    private static final String INDENT = "                        + \"";

    /**
     * What the writer needs beyond the signature.
     *
     * @param packageName          the generated class's package
     * @param className            the generated class's simple name
     * @param tag                  the fully-qualified-name tag, {@code ELM}, written in
     *                             parentheses after every label
     * @param labelPrefix          the prefix labels carry when their namespace has no prefix
     *                             of its own, {@code ELM }, also the prefix of the family's
     *                             own concepts
     * @param prefixByNamespace    the label prefix per namespace, {@code ELM System } for
     *                             the System types; a namespace absent here uses
     *                             {@code labelPrefix}
     * @param attribution          who the definitions come from, for example {@code the ELM
     *                             specification}
     * @param pin                  the version the schemas were taken from, for example a
     *                             repository tag
     * @param stampExpression      the Java expression of the inception stamp, {@code
     *                             Ike.INCEPTION}
     * @param termsClass           the class holding field data-type constants, {@code IkeTerm}
     * @param rootParentExpression the Java expression of the family root's parent, {@code
     *                             IkeTerm.MODEL_CONCEPT}
     */
    public record Options(String packageName, String className, String tag, String labelPrefix,
                          Map<String, String> prefixByNamespace, String attribution, String pin,
                          String stampExpression, String termsClass, String rootParentExpression) {
        /**
         * Creates the options, copying the prefix map.
         *
         * @param packageName          the package
         * @param className            the class name
         * @param tag                  the tag
         * @param labelPrefix          the default label prefix
         * @param prefixByNamespace    the prefix per namespace
         * @param attribution          the attribution
         * @param pin                  the pin
         * @param stampExpression      the stamp expression
         * @param termsClass           the terms class
         * @param rootParentExpression the root parent expression
         */
        public Options {
            labelPrefix = spaced(labelPrefix);
            Map<String, String> spacedPrefixes = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : prefixByNamespace.entrySet()) {
                spacedPrefixes.put(entry.getKey(), spaced(entry.getValue()));
            }
            prefixByNamespace = Map.copyOf(spacedPrefixes);
        }

        /** A prefix ends with one space, so that a label reads as two words whether or not the caller typed the space. */
        private static String spaced(String prefix) {
            String trimmed = prefix.strip();
            return trimmed.isEmpty() ? "" : trimmed + " ";
        }

        String prefixFor(String namespace) {
            return prefixByNamespace.getOrDefault(namespace, labelPrefix);
        }
    }

    private LedgerSectionWriter() {
    }

    /**
     * Renders the signature as the source text of a ledger section.
     *
     * @param signature the signature to write
     * @param options   the naming and wiring choices
     * @return the Java source of the section class
     */
    public static String write(SchemaSignature signature, Options options) {
        Naming naming = new Naming(options);
        List<TypeDefinition> ordered = topological(signature.types());
        Set<TypeReference> declared = new LinkedHashSet<>();
        for (TypeDefinition type : signature.types()) {
            declared.add(type.reference());
        }
        for (Enumeration enumeration : signature.enumerations()) {
            declared.add(new TypeReference(enumeration.namespace(), enumeration.name()));
        }
        Map<String, List<TypeDefinition>> positionsByName = new TreeMap<>();
        Set<String> primitives = new TreeSet<>();
        Map<String, TypeReference> externals = new TreeMap<>();
        int positionCount = 0;
        for (TypeDefinition type : signature.types()) {
            for (Position position : type.positions()) {
                positionsByName.computeIfAbsent(position.name(), ignored -> new ArrayList<>()).add(type);
                TypeReference value = position.valueType();
                if (value.isPrimitive()) {
                    primitives.add(value.name());
                } else if (!declared.contains(value)) {
                    externals.put(value.name(), value);
                }
                positionCount++;
            }
            type.base().ifPresent(base -> {
                if (!declared.contains(base) && !base.isPrimitive()) {
                    externals.put(base.name(), base);
                }
            });
        }
        int valueCount = 0;
        for (Enumeration enumeration : signature.enumerations()) {
            valueCount += enumeration.values().size();
        }

        naming.spellApart(positionsByName.keySet());
        StringBuilder src = new StringBuilder();
        header(src, options, signature, positionsByName.size(), primitives.size(), externals.size(),
                positionCount, valueCount);
        src.append("final class ").append(options.className()).append(" {\n\n");
        src.append("    /** The family root: the catalog itself. */\n");
        src.append("    static final String ROOT_FQN = ").append(quote(naming.fqn("node catalog"))).append(";\n\n");
        src.append("    /** The pattern that records, on each type, its positions. */\n");
        src.append("    static final String TYPE_POSITION_PATTERN_FQN = ")
                .append(quote(naming.fqn("type position pattern"))).append(";\n\n");
        src.append("    private ").append(options.className()).append("() {\n    }\n\n");
        src.append("    /**\n     * Composes this section's declarations into the session.\n");
        src.append("     *\n     * @param set the knowledge set (the session)\n     */\n");
        src.append("    static void compose(KnowledgeSet set) {\n");
        src.append("        ActiveStamp inception = ").append(options.stampExpression()).append(";\n\n");

        family(src, naming, options, signature, positionsByName.size(), primitives.size(), externals.size());
        pattern(src, naming, options);
        positions(src, naming, positionsByName);
        primitives(src, naming, primitives, options);
        externals(src, naming, externals, options);
        types(src, naming, options, ordered, declared);
        enumerations(src, naming, options, signature.enumerations());

        src.append("    }\n}\n");
        return src.toString();
    }

    private static void header(StringBuilder src, Options options, SchemaSignature signature,
                               int positionNames, int primitiveCount, int externalCount, int positionCount,
                               int valueCount) {
        src.append("""
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
                """);
        src.append("package ").append(options.packageName()).append(";\n\n");
        src.append("import dev.ikm.tinkar.common.id.PublicIds;\n");
        src.append("import dev.ikm.tinkar.entity.builder.ActiveStamp;\n");
        src.append("import dev.ikm.tinkar.entity.builder.KnowledgeSet;\n");
        src.append("import dev.ikm.tinkar.terms.EntityProxy;\n\n");
        src.append("/**\n");
        src.append(" * The ").append(options.tag()).append(" node catalog as a ledger section, imported from ")
                .append(options.attribution()).append(", ").append(options.pin())
                .append(", by {@code ike:schema-import} (IKE-Network/ike-issues#1104).\n");
        src.append(" * GENERATED FROM THE SCHEMAS: regenerate, never edit.\n");
        src.append(" * <p>").append(signature.types().size()).append(" types, ")
                .append(positionNames).append(" position names over ").append(positionCount)
                .append(" type positions, ").append(primitiveCount).append(" schema primitives, ")
                .append(externalCount).append(" types of other schemas referred to, ")
                .append(signature.enumerations().size()).append(" enumerations with ")
                .append(valueCount).append(" values.\n");
        src.append(" */\n");
    }

    private static void family(StringBuilder src, Naming naming, Options options, SchemaSignature signature,
                               int positionNames, int primitiveCount, int externalCount) {
        src.append("        // ── The family root and its parents ──\n");
        concept(src, naming.fqn("node catalog"), naming.label("node catalog"),
                "The catalog of the node kinds of " + options.attribution() + ": each kind, what it"
                        + " holds, and what each thing it holds may be. Its " + signature.types().size()
                        + " node kinds, each with its base and its positions, its "
                        + positionNames + " position names, its " + primitiveCount
                        + " schema primitives, its " + externalCount
                        + (externalCount == 1 ? " type" : " types") + " of other schemas it refers to, and its "
                        + signature.enumerations().size()
                        + (signature.enumerations().size() == 1 ? " enumeration" : " enumerations")
                        + ", imported from " + options.pin()
                        + " and regenerated from the schemas, never edited. A node kind is a kind of node"
                        + " a tree in this language can have; a position is a named place in a node that"
                        + " holds a child or a value.",
                options.rootParentExpression());
        src.append("        EntityProxy.Concept root = set.conceptRef(ROOT_FQN);\n\n");
        concept(src, naming.fqn("position"), naming.label("position"),
                "A position of the catalog: a named place in a node that holds a child"
                        + " or a value, such as operand or dataType, shared by every node type that has a"
                        + " position of that name. Which types have it, with what value type and how"
                        + " many, is recorded on each type by the type position pattern.",
                "root");
        src.append("        EntityProxy.Concept positionParent = set.conceptRef(")
                .append(quote(naming.fqn("position"))).append(");\n\n");
        concept(src, naming.fqn("primitive"), naming.label("primitive"),
                "A value type the XML Schema language itself supplies, string or QName, rather"
                        + " than a node kind of the catalog: what a position holds when it holds a plain"
                        + " value and not a node.",
                "root");
        src.append("        EntityProxy.Concept primitiveParent = set.conceptRef(")
                .append(quote(naming.fqn("primitive"))).append(");\n\n");
        concept(src, naming.fqn("external type"), naming.label("external type"),
                "A type declared by a schema outside this catalog that a position or a base refers"
                        + " to; the catalog names it so that every reference resolves, and says no"
                        + " more about it than its name and its namespace.",
                "root");
        src.append("        EntityProxy.Concept externalParent = set.conceptRef(")
                .append(quote(naming.fqn("external type"))).append(");\n\n");
    }

    private static void pattern(StringBuilder src, Naming naming, Options options) {
        src.append("        // ── The type position pattern: which positions each type has ──\n");
        concept(src, naming.fqn("type position"), naming.label("type position"),
                "What a type position semantic is: one position of one node kind, with the"
                        + " type of value it holds, the fewest and the most values the schema allows,"
                        + " the schema's own note on that position for that kind, and its form: whether"
                        + " our vertex holds it as a property or as an edge.",
                "root");
        concept(src, naming.fqn("catalog structure"), naming.label("catalog structure"),
                "Why type positions are recorded: so that a tree can be checked against the"
                        + " catalog and a reader can know what each node may hold.",
                "root");
        src.append("        EntityProxy.Concept structure = set.conceptRef(")
                .append(quote(naming.fqn("catalog structure"))).append(");\n");
        concept(src, naming.fqn("property form"), naming.label("property form"),
                "The form of a position that holds a plain value, an attribute in the schema: our"
                        + " vertex holds it as a property keyed by the position.",
                "root");
        concept(src, naming.fqn("edge form"), naming.label("edge form"),
                "The form of a position that holds a node, an element in the schema: our vertex"
                        + " holds it as an edge to the vertex below, named by the position.",
                "root");
        String[][] fields = {
            {"position field", "The position a type position semantic is about."},
            {"value type field", "The type of value that position holds on that type: a node kind of"
                    + " the catalog, a schema primitive, or a type of another schema."},
            {"minimum field", "The fewest values the schema allows in that position, zero when it is"
                    + " optional."},
            {"maximum field", "The most values the schema allows in that position, or minus one when"
                    + " the schema sets no limit."},
            {"position note field", "The schema's own note on that position for that type, empty when"
                    + " the schema gives none."},
            {"form field", "How our vertex holds that position on that type: the property form when"
                    + " it holds a plain value, the edge form when it holds a node."},
        };
        for (String[] field : fields) {
            concept(src, naming.fqn(field[0]), naming.label(field[0]), field[1], "root");
        }
        src.append("        set.pattern(TYPE_POSITION_PATTERN_FQN).at(inception)\n");
        src.append("                .meaning(set.conceptRef(").append(quote(naming.fqn("type position"))).append("))\n");
        src.append("                .purpose(structure)\n");
        src.append("                .field(set.conceptRef(").append(quote(naming.fqn("position field")))
                .append("), structure, ").append(options.termsClass()).append(".COMPONENT_FIELD)\n");
        src.append("                .field(set.conceptRef(").append(quote(naming.fqn("value type field")))
                .append("), structure, ").append(options.termsClass()).append(".COMPONENT_FIELD)\n");
        src.append("                .field(set.conceptRef(").append(quote(naming.fqn("minimum field")))
                .append("), structure, ").append(options.termsClass()).append(".INTEGER_FIELD)\n");
        src.append("                .field(set.conceptRef(").append(quote(naming.fqn("maximum field")))
                .append("), structure, ").append(options.termsClass()).append(".INTEGER_FIELD)\n");
        src.append("                .field(set.conceptRef(").append(quote(naming.fqn("position note field")))
                .append("), structure, ").append(options.termsClass()).append(".STRING)\n");
        src.append("                .field(set.conceptRef(").append(quote(naming.fqn("form field")))
                .append("), structure, ").append(options.termsClass()).append(".COMPONENT_FIELD);\n");
        src.append("        EntityProxy.Concept propertyForm = set.conceptRef(")
                .append(quote(naming.fqn("property form"))).append(");\n");
        src.append("        EntityProxy.Concept edgeForm = set.conceptRef(")
                .append(quote(naming.fqn("edge form"))).append(");\n");
        src.append("        EntityProxy.Pattern typePositions = set.patternRef(TYPE_POSITION_PATTERN_FQN);\n\n");
    }

    private static void positions(StringBuilder src, Naming naming, Map<String, List<TypeDefinition>> byName) {
        src.append("        // ── Positions: one concept per name, shared across types ──\n");
        for (Map.Entry<String, List<TypeDefinition>> entry : byName.entrySet()) {
            String name = entry.getKey();
            List<TypeDefinition> owners = entry.getValue();
            Set<PositionKind> kinds = new TreeSet<>();
            for (TypeDefinition owner : owners) {
                for (Position position : owner.positions()) {
                    if (position.name().equals(name)) {
                        kinds.add(position.kind());
                    }
                }
            }
            String kindText = kinds.size() == 2 ? "an element on some types and an attribute on others"
                    : kinds.contains(PositionKind.ATTRIBUTE) ? "an attribute" : "a child element";
            String definition = "The argument position named " + name + ", " + kindText + ", on "
                    + owners.size() + (owners.size() == 1 ? " node kind" : " node kinds") + " of the catalog."
                    + naming.spellingNote(name);
            concept(src, naming.positionFqn(name), naming.positionLabel(name), definition, "positionParent");
        }
        src.append('\n');
    }

    private static void primitives(StringBuilder src, Naming naming, Set<String> primitives, Options options) {
        src.append("        // ── Schema primitives the positions use ──\n");
        for (String primitive : primitives) {
            concept(src, naming.primitiveFqn(primitive), naming.primitiveLabel(primitive),
                    "The XML Schema primitive " + primitive + ", a plain value a position of "
                            + options.attribution() + " holds.",
                    "primitiveParent");
        }
        src.append('\n');
    }

    private static void externals(StringBuilder src, Naming naming, Map<String, TypeReference> externals,
                                  Options options) {
        if (externals.isEmpty()) {
            return;
        }
        src.append("        // ── Types of other schemas the catalog refers to ──\n");
        for (TypeReference external : externals.values()) {
            concept(src, naming.externalFqn(external.name()), naming.externalLabel(external.name()),
                    "The type " + external.name() + " of the namespace " + external.namespace()
                            + ", which " + options.attribution() + " refers to but does not declare.",
                    "externalParent");
        }
        src.append('\n');
    }

    private static void types(StringBuilder src, Naming naming, Options options, List<TypeDefinition> ordered,
                              Set<TypeReference> declared) {
        src.append("        // ── Types, each after its base ──\n");
        for (TypeDefinition type : ordered) {
            String parent = "root";
            if (type.base().isPresent()) {
                TypeReference base = type.base().get();
                parent = "set.conceptRef(" + quote(declared.contains(base)
                        ? naming.typeFqn(base) : naming.externalFqn(base.name())) + ")";
            }
            String abstractNote = type.isAbstract()
                    ? " The schema marks it abstract: a tree never holds it directly, only one of the"
                    + " types that extend it." : "";
            String definition = type.documentation().isEmpty()
                    ? "A type of " + options.attribution() + "; the schema gives no description." + abstractNote
                    : "From " + options.attribution() + ": " + type.documentation() + abstractNote;
            src.append("        set.concept(").append(quote(naming.typeFqn(type.reference()))).append(").at(inception)\n");
            src.append("                .synonym(").append(quote(naming.typeLabel(type.reference()))).append(")\n");
            definitionCall(src, definition);
            src.append("                .isA(").append(parent).append(')');
            for (Position position : type.positions()) {
                TypeReference value = position.valueType();
                String valueType = value.isPrimitive()
                        ? "set.conceptRef(" + quote(naming.primitiveFqn(value.name())) + ")"
                        : declared.contains(value)
                        ? "set.conceptRef(" + quote(naming.typeFqn(value)) + ")"
                        : "set.conceptRef(" + quote(naming.externalFqn(value.name())) + ")";
                src.append("\n                .semantic(typePositions, PublicIds.of(set.uuidFor(")
                        .append(quote("Type position: " + naming.typeLabel(type.reference()) + " " + position.name()))
                        .append(")),\n");
                src.append("                        set.conceptRef(").append(quote(naming.positionFqn(position.name())))
                        .append("), ").append(valueType).append(", ").append(position.minimum()).append(", ")
                        .append(position.maximum()).append(",\n");
                src.append("                        ").append(quote(position.documentation())).append(", ")
                        .append(position.kind() == PositionKind.ATTRIBUTE ? "propertyForm" : "edgeForm").append(')');
            }
            src.append(";\n");
        }
        src.append('\n');
    }

    private static void enumerations(StringBuilder src, Naming naming, Options options,
                                     List<Enumeration> enumerations) {
        if (enumerations.isEmpty()) {
            return;
        }
        src.append("        // ── Enumerations and their values ──\n");
        for (Enumeration enumeration : enumerations) {
            TypeReference reference = new TypeReference(enumeration.namespace(), enumeration.name());
            String definition = enumeration.documentation().isEmpty()
                    ? "An enumeration of " + options.attribution() + " with " + enumeration.values().size()
                    + " values; the schema gives no description."
                    : "From " + options.attribution() + ": " + enumeration.documentation();
            concept(src, naming.typeFqn(reference), naming.typeLabel(reference), definition, "root");
            for (String value : enumeration.values()) {
                concept(src, naming.valueFqn(reference, value), naming.valueLabel(reference, value),
                        "The " + enumeration.name() + " value " + value + " of " + options.attribution() + ".",
                        "set.conceptRef(" + quote(naming.typeFqn(reference)) + ")");
            }
        }
    }

    private static void concept(StringBuilder src, String fqn, String label, String definition, String parent) {
        src.append("        set.concept(").append(quote(fqn)).append(").at(inception)\n");
        src.append("                .synonym(").append(quote(label)).append(")\n");
        definitionCall(src, definition);
        src.append("                .isA(").append(parent).append(");\n");
    }

    private static void definitionCall(StringBuilder src, String definition) {
        List<String> fragments = fragments(definition);
        src.append("                .definition(").append(quote(fragments.get(0)));
        for (int i = 1; i < fragments.size(); i++) {
            src.append('\n').append(INDENT).append(escape(fragments.get(i))).append('"');
        }
        src.append(")\n");
    }

    /**
     * Splits a definition into fragments of at most {@link #FRAGMENT_WIDTH} characters on
     * spaces, each continuation fragment starting with the space that separates it, the
     * way the hand-written sections wrap their definitions.
     */
    static List<String> fragments(String text) {
        List<String> fragments = new ArrayList<>();
        String remaining = text;
        while (remaining.length() > FRAGMENT_WIDTH) {
            int cut = remaining.lastIndexOf(' ', FRAGMENT_WIDTH);
            if (cut <= 0) {
                cut = remaining.indexOf(' ', FRAGMENT_WIDTH);
                if (cut < 0) {
                    break;
                }
            }
            fragments.add(remaining.substring(0, cut));
            remaining = remaining.substring(cut);
        }
        fragments.add(remaining);
        return fragments;
    }

    /** Orders types so that every type follows its base; ties keep declaration order. */
    static List<TypeDefinition> topological(List<TypeDefinition> types) {
        Map<TypeReference, TypeDefinition> byReference = new LinkedHashMap<>();
        for (TypeDefinition type : types) {
            byReference.put(type.reference(), type);
        }
        List<TypeDefinition> ordered = new ArrayList<>();
        Set<TypeReference> placed = new LinkedHashSet<>();
        for (TypeDefinition type : types) {
            place(type, byReference, placed, ordered, new LinkedHashSet<>());
        }
        return ordered;
    }

    private static void place(TypeDefinition type, Map<TypeReference, TypeDefinition> byReference,
                              Set<TypeReference> placed, List<TypeDefinition> ordered, Set<TypeReference> visiting) {
        if (placed.contains(type.reference())) {
            return;
        }
        if (!visiting.add(type.reference())) {
            throw new IllegalArgumentException("Type " + type.name() + " extends itself through " + visiting);
        }
        if (type.base().isPresent()) {
            TypeDefinition base = byReference.get(type.base().get());
            if (base != null) {
                place(base, byReference, placed, ordered, visiting);
            }
        }
        visiting.remove(type.reference());
        placed.add(type.reference());
        ordered.add(type);
    }

    private static String quote(String text) {
        return '"' + escape(text) + '"';
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * The naming scheme: a prefix per namespace plus the schema name for labels, the tag in
     * parentheses for FQNs. Two position names that differ only by case, ELM's
     * {@code codeSystem} and {@code codesystem}, would collapse into one binding constant,
     * so such names are spelled apart in their labels.
     */
    private static final class Naming {
        private final Options options;
        private final Map<String, String> caseSuffixes = new LinkedHashMap<>();

        Naming(Options options) {
            this.options = options;
        }

        /** Finds position names that collide case-insensitively and gives each a suffix. */
        void spellApart(Set<String> positionNames) {
            Map<String, List<String>> byLowerCase = new TreeMap<>();
            for (String name : positionNames) {
                byLowerCase.computeIfAbsent(name.toLowerCase(java.util.Locale.ROOT), ignored -> new ArrayList<>())
                        .add(name);
            }
            for (List<String> group : byLowerCase.values()) {
                if (group.size() < 2) {
                    continue;
                }
                for (String name : group) {
                    String suffix = name.equals(name.toLowerCase(java.util.Locale.ROOT)) ? ", lower case"
                            : name.equals(name.toUpperCase(java.util.Locale.ROOT)) ? ", upper case" : ", mixed case";
                    if (caseSuffixes.containsValue(suffix) && group.stream().filter(other ->
                            caseSuffixes.getOrDefault(other, "").equals(suffix)).count() > 0) {
                        throw new IllegalArgumentException("Position names " + group
                                + " differ only by case in more than one mixed spelling; the writer cannot"
                                + " spell them apart");
                    }
                    caseSuffixes.put(name, suffix);
                }
            }
        }

        String spellingNote(String positionName) {
            String suffix = caseSuffixes.get(positionName);
            return suffix == null ? "" : " The schema spells it " + suffix.substring(2)
                    + ", and spells another position the same way but for case.";
        }

        String label(String word) {
            return options.labelPrefix() + word;
        }

        String fqn(String word) {
            return label(word) + " (" + options.tag() + ")";
        }

        String typeLabel(TypeReference type) {
            return options.prefixFor(type.namespace()) + type.name();
        }

        String typeFqn(TypeReference type) {
            return typeLabel(type) + " (" + options.tag() + ")";
        }

        String positionLabel(String name) {
            return options.labelPrefix() + name + " position" + caseSuffixes.getOrDefault(name, "");
        }

        String positionFqn(String name) {
            return positionLabel(name) + " (" + options.tag() + ")";
        }

        String primitiveLabel(String name) {
            return options.labelPrefix() + "primitive " + name;
        }

        String primitiveFqn(String name) {
            return primitiveLabel(name) + " (" + options.tag() + ")";
        }

        String externalLabel(String name) {
            return options.labelPrefix() + "external " + name;
        }

        String externalFqn(String name) {
            return externalLabel(name) + " (" + options.tag() + ")";
        }

        String valueLabel(TypeReference enumeration, String value) {
            return typeLabel(enumeration) + " " + value;
        }

        String valueFqn(TypeReference enumeration, String value) {
            return valueLabel(enumeration, value) + " (" + options.tag() + ")";
        }
    }
}
