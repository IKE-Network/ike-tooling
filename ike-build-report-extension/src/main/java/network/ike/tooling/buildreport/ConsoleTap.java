package network.ike.tooling.buildreport;

import java.lang.reflect.Field;
import java.util.function.Consumer;

import org.slf4j.LoggerFactory;

/**
 * Listens in on the lines Maven writes to the console, passing every
 * one through unchanged.
 *
 * <p>Maven has no SPI for log output: neither {@code EventSpy} nor the
 * new API sees a plugin's warnings, and SLF4J's API has no appenders.
 * What Maven 4 does have is a single sink —
 * {@code MavenSimpleLogger.logSink} — through which every rendered log
 * line passes on its way to the terminal, including the stderr of
 * in-process tools, which Maven re-logs as {@code [WARNING] [stderr]}.
 * This tap wraps that sink. The logger class is not exported to
 * extensions, so it is reached reflectively through the logger
 * factory's own class loader (verified on 4.0.0-rc-5 and rc-7).</p>
 *
 * <p>Everything here is best-effort. When the sink is absent or the
 * logger is a different implementation — the IDE's maven-server, a
 * future Maven — {@link #install()} returns false, the receipt says
 * console capture was unavailable, and the build is untouched.</p>
 */
final class ConsoleTap implements Consumer<String> {

    private static final String LOGGER_CLASS = "org.apache.maven.slf4j.MavenSimpleLogger";
    private static final String SINK_FIELD = "logSink";

    private final Consumer<String> delegate;

    private ConsoleTap(Consumer<String> delegate) {
        this.delegate = delegate;
    }

    @Override
    public void accept(String line) {
        try {
            ReportSession.addConsoleLine(line);
        } catch (RuntimeException e) {
            // Listening must never cost the build its console output.
        }
        delegate.accept(line);
    }

    /**
     * Wraps Maven's console sink; a no-op when already wrapped.
     *
     * <p>Called at every session and project start rather than once,
     * because Maven may set the sink after extensions are initialized.</p>
     *
     * @return true when the tap is in place
     */
    @SuppressWarnings("unchecked")
    static synchronized boolean install() {
        try {
            Field sink = sinkField();
            Object current = sink.get(null);
            if (current instanceof ConsoleTap) {
                return true;
            }
            if (!(current instanceof Consumer)) {
                return false;
            }
            sink.set(null, new ConsoleTap((Consumer<String>) current));
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    /**
     * Restores the sink this tap wrapped, so a long-lived JVM does not
     * accumulate taps across sessions.
     */
    static synchronized void uninstall() {
        try {
            Field sink = sinkField();
            if (sink.get(null) instanceof ConsoleTap tap) {
                sink.set(null, tap.delegate);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // Nothing to restore.
        }
    }

    private static Field sinkField() throws ReflectiveOperationException {
        ClassLoader loader = LoggerFactory.getILoggerFactory().getClass().getClassLoader();
        Field sink = Class.forName(LOGGER_CLASS, false, loader).getDeclaredField(SINK_FIELD);
        sink.setAccessible(true);
        return sink;
    }
}
