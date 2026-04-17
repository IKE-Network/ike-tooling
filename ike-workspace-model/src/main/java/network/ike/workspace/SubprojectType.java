package network.ike.workspace;

/**
 * The closed vocabulary of workspace subproject types. Each entry
 * carries its build command and checkpoint mechanism as compile-time
 * data — the workspace.yaml no longer has a {@code subproject-types:}
 * section (legacy {@code component-types:} sections are ignored by the
 * reader and stripped by {@code ws:align}).
 *
 * <p>This replaces the prior {@code ComponentType} record + manifest
 * configuration map with a compiler-visible enum, per the compiler-
 * visibility discipline and #150.
 */
public enum SubprojectType {

    /** Java libraries and applications. */
    SOFTWARE("software",
            "Java libraries and applications",
            "mvn clean install",
            CheckpointMechanism.GIT_TAG),

    /** Build tooling, parent POMs, shared resources. */
    INFRASTRUCTURE("infrastructure",
            "Build tooling, parent POMs, shared resources",
            "mvn clean install",
            CheckpointMechanism.GIT_TAG),

    /** AsciiDoc topic libraries and assemblies. */
    DOCUMENT("document",
            "AsciiDoc topic libraries and assemblies",
            "mvn clean verify",
            CheckpointMechanism.GIT_TAG);

    /** How a subproject records a reproducible state for checkpoints. */
    public enum CheckpointMechanism {
        /** Tag the git commit that represents the checkpoint. */
        GIT_TAG,
        /** Composite: record an aggregation of underlying subprojects. */
        COMPOSITE
    }

    private final String yamlName;
    private final String description;
    private final String buildCommand;
    private final CheckpointMechanism checkpointMechanism;

    SubprojectType(String yamlName, String description, String buildCommand,
                   CheckpointMechanism checkpointMechanism) {
        this.yamlName = yamlName;
        this.description = description;
        this.buildCommand = buildCommand;
        this.checkpointMechanism = checkpointMechanism;
    }

    /** The lowercase identifier used in {@code workspace.yaml} {@code type:} fields. */
    public String yamlName() {
        return yamlName;
    }

    /** Short human description of what this category of subproject is. */
    public String description() {
        return description;
    }

    /** The default Maven invocation for building a subproject of this type. */
    public String buildCommand() {
        return buildCommand;
    }

    /** How a subproject of this type records reproducible state for a checkpoint. */
    public CheckpointMechanism checkpointMechanism() {
        return checkpointMechanism;
    }

    /**
     * Parse a {@code type:} value from workspace.yaml.
     *
     * @param yamlName the lowercase type identifier (e.g. {@code "software"})
     * @return the matching enum value
     * @throws IllegalArgumentException if the name is not a known type
     */
    public static SubprojectType fromYamlName(String yamlName) {
        for (SubprojectType t : values()) {
            if (t.yamlName.equals(yamlName)) return t;
        }
        throw new IllegalArgumentException(
                "Unknown subproject type: \"" + yamlName
                        + "\". Known types: software, infrastructure, document.");
    }
}
