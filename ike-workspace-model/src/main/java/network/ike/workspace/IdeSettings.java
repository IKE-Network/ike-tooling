package network.ike.workspace;

/**
 * Optional IntelliJ IDEA project settings that should be shared
 * across collaborators via {@code workspace.yaml}.
 *
 * <p>These values drive the curated slice of {@code .idea/} files
 * that workspace tooling keeps in sync (see the {@code ws:upgrade}
 * goal). When a field is {@code null}, the corresponding IDE setting
 * is left untouched.
 *
 * <p>Typical usage in {@code workspace.yaml}:
 *
 * <pre>{@code
 * ide:
 *   language-level: JDK_25_PREVIEW
 *   jdk-name: "25"
 * }</pre>
 *
 * @param languageLevel IntelliJ language-level enum value written to
 *                      {@code .idea/misc.xml} (e.g., {@code JDK_25},
 *                      {@code JDK_25_PREVIEW}). Null means "do not
 *                      enforce" — IntelliJ's detected level stands.
 * @param jdkName       IntelliJ JDK alias written as
 *                      {@code project-jdk-name} in {@code .idea/misc.xml}.
 *                      Null means "do not enforce".
 */
public record IdeSettings(
        String languageLevel,
        String jdkName
) {

    /** A sentinel value equivalent to the {@code ide:} section being absent. */
    public static final IdeSettings EMPTY = new IdeSettings(null, null);

    /**
     * Whether the {@code ide} section contributed any enforceable
     * value. Convenient guard for callers that want to skip IDE
     * updates when workspace.yaml does not specify settings.
     *
     * @return {@code true} if at least one field is non-null
     */
    public boolean hasAnyValue() {
        return languageLevel != null || jdkName != null;
    }
}
