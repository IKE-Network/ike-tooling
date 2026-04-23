package network.ike.plugin.support;

/**
 * Compile-time identifier for a goal exported by an IKE Maven plugin.
 *
 * <p>Each plugin provides an enum implementing this interface — e.g.
 * {@code network.ike.plugin.IkeGoal} for {@code ike-maven-plugin}
 * (prefix {@code ike:}), {@code network.ike.pipeline.plugin.doc.IdocGoal}
 * for {@code ike-doc-maven-plugin} (prefix {@code idoc:}). Callers that
 * invoke goals from Java — for report identification, javadoc examples
 * that survive a rename, or subprocess exec — reference enum values
 * rather than string literals, so the compiler catches every goal
 * rename the moment it happens.
 *
 * <p>The {@link #qualified()} default implementation combines
 * {@link #pluginPrefix()} and {@link #goalName()} with a single colon,
 * matching Maven's prefix:goal invocation syntax.
 *
 * <p>See <a href="https://github.com/IKE-Network/ike-issues/issues/215">
 * ike-issues #215</a> for the split that introduced this interface.
 */
public interface GoalRef {

    /**
     * The plugin prefix, e.g. {@code "ike"} for {@code ike-maven-plugin}
     * or {@code "idoc"} for {@code ike-doc-maven-plugin}. Every enum
     * value for a single plugin returns the same prefix.
     *
     * @return the plugin prefix without a trailing colon
     */
    String pluginPrefix();

    /**
     * The bare goal name as it appears in the mojo's {@code @Mojo(name = ...)}
     * annotation, e.g. {@code "release-publish"}, {@code "asciidoc"}.
     *
     * @return the bare goal name
     */
    String goalName();

    /**
     * The fully-qualified goal invocation, e.g. {@code "ike:release-publish"}
     * or {@code "idoc:asciidoc"}.
     *
     * @return {@code pluginPrefix() + ":" + goalName()}
     */
    default String qualified() {
        return pluginPrefix() + ":" + goalName();
    }

    /**
     * One-line human description of what this goal does. Used in
     * {@code help} goal output and per-goal report headers.
     *
     * @return the human description
     */
    String description();
}
