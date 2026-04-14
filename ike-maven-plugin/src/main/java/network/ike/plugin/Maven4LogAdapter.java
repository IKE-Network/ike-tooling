package network.ike.plugin;

/**
 * Adapts Maven 3's {@link org.apache.maven.plugin.logging.Log} to Maven 4's
 * {@link org.apache.maven.api.plugin.Log} for compatibility with
 * ike-workspace-model utilities (ReleaseSupport, ReleaseNotesSupport)
 * which have been migrated to the Maven 4 API.
 *
 * <p>This adapter is temporary — it will be removed when ike-maven-plugin
 * is fully migrated to Maven 4's native plugin API. The migration requires
 * replacing MavenProject/MavenSession with Maven 4's Project/Session
 * equivalents, which is a separate effort.
 *
 * <p>Thread-safe: stateless delegation.
 */
final class Maven4LogAdapter implements org.apache.maven.api.plugin.Log {

    private final org.apache.maven.plugin.logging.Log delegate;

    Maven4LogAdapter(org.apache.maven.plugin.logging.Log delegate) {
        this.delegate = delegate;
    }

    /**
     * Wrap a Maven 3 Log as a Maven 4 Log.
     *
     * @param maven3Log the Maven 3 logger
     * @return a Maven 4 Log adapter
     */
    static org.apache.maven.api.plugin.Log wrap(
            org.apache.maven.plugin.logging.Log maven3Log) {
        return new Maven4LogAdapter(maven3Log);
    }

    @Override public boolean isDebugEnabled() { return delegate.isDebugEnabled(); }
    @Override public void debug(CharSequence c) { delegate.debug(c); }
    @Override public void debug(CharSequence c, Throwable e) { delegate.debug(c, e); }
    @Override public void debug(Throwable e) { delegate.debug(e); }
    @Override public void debug(java.util.function.Supplier<String> c) { if (delegate.isDebugEnabled()) delegate.debug(c.get()); }
    @Override public void debug(java.util.function.Supplier<String> c, Throwable e) { if (delegate.isDebugEnabled()) delegate.debug(c.get(), e); }

    @Override public boolean isInfoEnabled() { return delegate.isInfoEnabled(); }
    @Override public void info(CharSequence c) { delegate.info(c); }
    @Override public void info(CharSequence c, Throwable e) { delegate.info(c, e); }
    @Override public void info(Throwable e) { delegate.info(e); }
    @Override public void info(java.util.function.Supplier<String> c) { if (delegate.isInfoEnabled()) delegate.info(c.get()); }
    @Override public void info(java.util.function.Supplier<String> c, Throwable e) { if (delegate.isInfoEnabled()) delegate.info(c.get(), e); }

    @Override public boolean isWarnEnabled() { return delegate.isWarnEnabled(); }
    @Override public void warn(CharSequence c) { delegate.warn(c); }
    @Override public void warn(CharSequence c, Throwable e) { delegate.warn(c, e); }
    @Override public void warn(Throwable e) { delegate.warn(e); }
    @Override public void warn(java.util.function.Supplier<String> c) { if (delegate.isWarnEnabled()) delegate.warn(c.get()); }
    @Override public void warn(java.util.function.Supplier<String> c, Throwable e) { if (delegate.isWarnEnabled()) delegate.warn(c.get(), e); }

    @Override public boolean isErrorEnabled() { return delegate.isErrorEnabled(); }
    @Override public void error(CharSequence c) { delegate.error(c); }
    @Override public void error(CharSequence c, Throwable e) { delegate.error(c, e); }
    @Override public void error(Throwable e) { delegate.error(e); }
    @Override public void error(java.util.function.Supplier<String> c) { if (delegate.isErrorEnabled()) delegate.error(c.get()); }
    @Override public void error(java.util.function.Supplier<String> c, Throwable e) { if (delegate.isErrorEnabled()) delegate.error(c.get(), e); }
}
