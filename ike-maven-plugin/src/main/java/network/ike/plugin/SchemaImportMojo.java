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

import network.ike.plugin.schema.LedgerSectionWriter;
import network.ike.plugin.schema.SchemaSignature;
import network.ike.plugin.schema.XmlSchemaReader;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Imports an XML Schema signature as a ledger section (IKE-Network/ike-issues#1104): reads
 * the schema files, one signature across all of them, and writes a KnowledgeSet section
 * class in which every named type, argument position, schema primitive, and enumerated
 * value is a concept, with the schema's own documentation as each definition and the
 * extension base as the is-a. The first use is HL7's ELM, the Expression Logical Model of
 * the CQL specification, whose signature is five schemas.
 *
 * <p>The goal runs on demand, not in a lifecycle phase: the section it writes is source to
 * check in and regenerate when the pinned schema release changes, never to edit. It needs
 * no store and no fork; the JDK's XML support is enough.
 */
@Mojo(name = IkeGoal.NAME_SCHEMA_IMPORT)
public class SchemaImportMojo implements org.apache.maven.api.plugin.Mojo {

    /** Creates the goal. */
    public SchemaImportMojo() {
    }

    @Inject
    private org.apache.maven.api.plugin.Log log;

    /** The schema files, comma-separated paths, read in the order given. */
    @Parameter(property = "ike.schemaImport.schemas", required = true)
    String schemas;

    /** The source root the section is written under, by default the project's main sources. */
    @Parameter(property = "ike.schemaImport.outputDirectory",
               defaultValue = "${project.basedir}/src/main/java")
    String outputDirectory;

    /** The generated class's package. */
    @Parameter(property = "ike.schemaImport.packageName", required = true)
    String packageName;

    /** The generated class's simple name, for example {@code ElmSignatureSet}. */
    @Parameter(property = "ike.schemaImport.className", required = true)
    String className;

    /** The fully-qualified-name tag written in parentheses after every label, for example {@code ELM}. */
    @Parameter(property = "ike.schemaImport.tag", required = true)
    String tag;

    /** The prefix every label carries; by default the tag followed by a space. */
    @Parameter(property = "ike.schemaImport.labelPrefix")
    String labelPrefix;

    /**
     * The label prefix per namespace, as {@code namespace=prefix} pairs separated by
     * semicolons, for example {@code urn:hl7-org:elm-types:r1=ELM System }; a namespace
     * not listed uses the default label prefix.
     */
    @Parameter(property = "ike.schemaImport.namespacePrefixes")
    String namespacePrefixes;

    /** Whose definitions these are, for example {@code the ELM specification}. */
    @Parameter(property = "ike.schemaImport.attribution", required = true)
    String attribution;

    /** The release the schemas were taken from, for example a repository tag. */
    @Parameter(property = "ike.schemaImport.pin", required = true)
    String pin;

    /** The Java expression of the inception stamp the section declares at. */
    @Parameter(property = "ike.schemaImport.stampExpression", defaultValue = "Ike.INCEPTION")
    String stampExpression;

    /** The class holding the field data-type constants the pattern uses. */
    @Parameter(property = "ike.schemaImport.termsClass", defaultValue = "IkeTerm")
    String termsClass;

    /** The Java expression of the family root's parent concept. */
    @Parameter(property = "ike.schemaImport.rootParentExpression", defaultValue = "IkeTerm.MODEL_CONCEPT")
    String rootParentExpression;

    /** Skips the goal. */
    @Parameter(property = "ike.schemaImport.skip", defaultValue = "false")
    boolean skip;

    @Override
    public void execute() {
        if (skip) {
            log.info("ike:schema-import skipped (ike.schemaImport.skip=true)");
            return;
        }
        List<Path> files = new ArrayList<>();
        for (String schema : schemas.split(",")) {
            String trimmed = schema.trim();
            if (!trimmed.isEmpty()) {
                Path path = Path.of(trimmed);
                if (!Files.isRegularFile(path)) {
                    throw new MojoException("Schema file not found: " + path);
                }
                files.add(path);
            }
        }
        if (files.isEmpty()) {
            throw new MojoException("ike.schemaImport.schemas names no schema file");
        }
        String prefix = labelPrefix == null || labelPrefix.isEmpty() ? tag + " " : labelPrefix;
        SchemaSignature signature;
        try {
            signature = new XmlSchemaReader().read(files);
        } catch (IOException e) {
            throw new MojoException("Cannot read the schemas: " + e.getMessage(), e);
        }
        Map<String, String> prefixes = new LinkedHashMap<>();
        if (namespacePrefixes != null && !namespacePrefixes.isBlank()) {
            for (String pair : namespacePrefixes.split(";")) {
                int equals = pair.indexOf('=');
                if (equals <= 0) {
                    throw new MojoException("ike.schemaImport.namespacePrefixes entry is not namespace=prefix: " + pair);
                }
                prefixes.put(pair.substring(0, equals).trim(), pair.substring(equals + 1));
            }
        }
        String source = LedgerSectionWriter.write(signature, new LedgerSectionWriter.Options(
                packageName, className, tag, prefix, prefixes, attribution, pin, stampExpression, termsClass,
                rootParentExpression));
        Path target = Path.of(outputDirectory).resolve(packageName.replace('.', '/')).resolve(className + ".java");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, source, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MojoException("Cannot write " + target + ": " + e.getMessage(), e);
        }
        log.info("Wrote " + target + ": " + signature.types().size() + " types and "
                + signature.enumerations().size() + " enumerations from " + files.size()
                + " schema file(s), pinned to " + pin);
    }
}
