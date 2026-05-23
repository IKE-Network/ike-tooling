package network.ike.workspace.cascade;

/**
 * The Maven model site that produced a {@link CascadeEdge}.
 *
 * <p>The release cascade derives edges from every version-bearing site
 * in an IKE POM, not just {@code <dependencies>}. The kind records
 * which site an edge came from, so the cascade can do
 * site-appropriate things on alignment — for example, rewriting a
 * parent's {@code <version>} differently from a dependency's, or
 * skipping a build-only plugin edge that the consumer POM doesn't
 * carry to its release.
 *
 * <p>Kinds:
 * <ul>
 *   <li>{@link #PARENT} — the project's {@code <parent>} block.</li>
 *   <li>{@link #DEPENDENCY} — a {@code <dependency>} (compile,
 *       runtime, or test scope) or a {@code <dependencyManagement>}
 *       entry without {@code <scope>import</scope>}.</li>
 *   <li>{@link #BOM} — a {@code <dependencyManagement>} entry whose
 *       scope is {@code import} (an imported BOM).</li>
 *   <li>{@link #PLUGIN} — a {@code <build><plugins>} or
 *       {@code <build><pluginManagement><plugins>} entry. Includes
 *       plugins carrying {@code <extensions>true</extensions>}.</li>
 *   <li>{@link #EXTENSION} — a {@code .mvn/extensions.xml} entry.
 *       Maven 4 build extensions resolve before the POM model loads
 *       and so cannot ride a plugin edge; this kind names them
 *       explicitly.</li>
 * </ul>
 *
 * <p>See IKE-Network/ike-issues#496 for the derivation specification
 * and the {@code dev-release-graph-derivation} topic in
 * {@code ike-lab-documents}.
 */
public enum EdgeKind {

    /** A {@code <parent>} edge. */
    PARENT,

    /**
     * A {@code <dependency>} or non-import
     * {@code <dependencyManagement>} edge.
     */
    DEPENDENCY,

    /**
     * A {@code <dependencyManagement>} entry with
     * {@code <scope>import</scope>}.
     */
    BOM,

    /**
     * A {@code <plugin>} or {@code <pluginManagement>} edge, including
     * extensions-carrying plugins.
     */
    PLUGIN,

    /** A {@code .mvn/extensions.xml} edge. */
    EXTENSION
}
