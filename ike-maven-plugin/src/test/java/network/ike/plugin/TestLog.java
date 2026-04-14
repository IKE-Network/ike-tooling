package network.ike.plugin;

import org.apache.maven.api.plugin.Log;
import java.util.function.Supplier;

/**
 * Simple Log implementation for tests, replacing Maven 3's SystemStreamLog.
 */
public class TestLog implements Log {
    @Override public boolean isDebugEnabled() { return false; }
    @Override public void debug(CharSequence c) {}
    @Override public void debug(CharSequence c, Throwable e) {}
    @Override public void debug(Throwable e) {}
    @Override public void debug(Supplier<String> c) {}
    @Override public void debug(Supplier<String> c, Throwable e) {}
    @Override public boolean isInfoEnabled() { return true; }
    @Override public void info(CharSequence c) { System.out.println("[INFO] " + c); }
    @Override public void info(CharSequence c, Throwable e) { System.out.println("[INFO] " + c); }
    @Override public void info(Throwable e) { System.out.println("[INFO] " + e); }
    @Override public void info(Supplier<String> c) { System.out.println("[INFO] " + c.get()); }
    @Override public void info(Supplier<String> c, Throwable e) { System.out.println("[INFO] " + c.get()); }
    @Override public boolean isWarnEnabled() { return true; }
    @Override public void warn(CharSequence c) { System.err.println("[WARN] " + c); }
    @Override public void warn(CharSequence c, Throwable e) { System.err.println("[WARN] " + c); }
    @Override public void warn(Throwable e) { System.err.println("[WARN] " + e); }
    @Override public void warn(Supplier<String> c) { System.err.println("[WARN] " + c.get()); }
    @Override public void warn(Supplier<String> c, Throwable e) { System.err.println("[WARN] " + c.get()); }
    @Override public boolean isErrorEnabled() { return true; }
    @Override public void error(CharSequence c) { System.err.println("[ERROR] " + c); }
    @Override public void error(CharSequence c, Throwable e) { System.err.println("[ERROR] " + c); }
    @Override public void error(Throwable e) { System.err.println("[ERROR] " + e); }
    @Override public void error(Supplier<String> c) { System.err.println("[ERROR] " + c.get()); }
    @Override public void error(Supplier<String> c, Throwable e) { System.err.println("[ERROR] " + c.get()); }
}
