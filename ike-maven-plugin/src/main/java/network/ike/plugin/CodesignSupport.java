package network.ike.plugin;

import org.apache.maven.api.plugin.Log;

import java.io.File;

/**
 * Shared helpers for the {@code codesign-*} mojos.
 */
final class CodesignSupport {

    private CodesignSupport() {}

    /**
     * Check whether {@code identity} is present in the keychain as a
     * usable code-signing identity, by inspecting
     * {@code security find-identity -v -p codesigning}.
     *
     * <p>Used so the codesign mojos can skip gracefully on machines
     * that do not hold the configured Developer ID certificate instead
     * of failing the build with {@code codesign: no identity found}.
     *
     * @param identity the codesign identity name to look for
     * @param log      the mojo logger (debug output on lookup failure)
     * @return {@code true} if {@code security find-identity} lists the
     *         identity; {@code false} if it is absent or the lookup
     *         could not be run
     */
    static boolean identityInKeychain(String identity, Log log) {
        try {
            String out = ReleaseSupport.execCapture(new File("."),
                    "security", "find-identity", "-v", "-p", "codesigning");
            return out != null && out.contains(identity);
        } catch (RuntimeException e) {
            log.debug("security find-identity lookup failed: "
                    + e.getMessage());
            return false;
        }
    }
}
