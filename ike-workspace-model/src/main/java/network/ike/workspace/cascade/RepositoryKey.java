package network.ike.workspace.cascade;

import org.apache.maven.api.model.Scm;

/**
 * The identity of a release-cascade node — a repository, named by
 * its {@code <scm>} URL (IKE-Network/ike-issues#496 part C).
 *
 * <p>A release node is a repository, not a coordinate. A single
 * reactor produces many coordinates ({@code ike-tooling} alone
 * produces {@code ike-maven-plugin},
 * {@code ike-build-standards}, {@code ike-workspace-model}, and
 * more) but the cascade releases the repository as one unit. The
 * join key that collapses many coordinates onto one node is the
 * {@code <scm>} URL each coordinate inherits from its reactor-root
 * POM. {@code RepositoryKey} is that join key.
 *
 * <p>SCM URLs arrive in many syntactic forms — {@code scm:git:https://}
 * prefixed, plain {@code https://}, {@code git@host:owner/repo}
 * SSH shorthand, {@code ssh://} explicit, with or without a trailing
 * {@code .git}. {@code RepositoryKey} canonicalises every form to a
 * single normalised {@code https://host/owner/repo} string, so two
 * keys derived from different syntactic variants of the same URL
 * compare equal.
 *
 * @param url the canonical {@code https://host/owner/repo} form of
 *            the SCM URL
 */
public record RepositoryKey(String url) {

    /**
     * Canonical constructor — validates and normalises the URL.
     */
    public RepositoryKey {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException(
                    "RepositoryKey url is required");
        }
        url = normalise(url);
    }

    /**
     * Builds a key from a Maven {@link Scm} block, preferring
     * {@code <url>} over {@code <connection>} when both are
     * declared.
     *
     * @param scm the SCM block; may be {@code null}
     * @return the key, or {@code null} when {@code scm} is
     *         {@code null} or declares neither a URL nor a
     *         connection
     */
    public static RepositoryKey fromScm(Scm scm) {
        if (scm == null) {
            return null;
        }
        String url = scm.getUrl();
        if (url == null || url.isBlank()) {
            url = scm.getConnection();
        }
        if (url == null || url.isBlank()) {
            return null;
        }
        return new RepositoryKey(url);
    }

    /**
     * Builds a key from any syntactic form of an SCM URL.
     *
     * @param scmUrl the SCM URL or connection string; must not be
     *               {@code null} or blank
     * @return the canonicalised key
     * @throws IllegalArgumentException if {@code scmUrl} is null or
     *                                  blank
     */
    public static RepositoryKey of(String scmUrl) {
        return new RepositoryKey(scmUrl);
    }

    /**
     * Normalises any common Git URL form to
     * {@code https://host/owner/repo}.
     *
     * <p>Handles: Maven {@code scm:provider:} prefixes,
     * {@code git@host:path} SSH shorthand, {@code ssh://} URLs,
     * trailing {@code .git}, and trailing slashes.
     */
    private static String normalise(String raw) {
        String s = raw.trim();
        // Strip Maven SCM provider prefix — "scm:git:", "scm:hg:", etc.
        if (s.startsWith("scm:")) {
            int colon = s.indexOf(':', 4);
            s = colon >= 0 ? s.substring(colon + 1) : s.substring(4);
        }
        // Convert SSH shorthand "git@host:owner/repo" → "https://host/owner/repo"
        if (s.startsWith("git@") && !s.contains("://")) {
            int colon = s.indexOf(':', 4);
            if (colon > 0) {
                String host = s.substring(4, colon);
                String path = s.substring(colon + 1);
                s = "https://" + host + "/" + path;
            }
        }
        // Convert "ssh://git@host/path" or "ssh://host/path" → "https://host/path"
        if (s.startsWith("ssh://git@")) {
            s = "https://" + s.substring("ssh://git@".length());
        } else if (s.startsWith("ssh://")) {
            s = "https://" + s.substring("ssh://".length());
        }
        // Drop trailing slashes BEFORE the ".git" check, so inputs
        // like "...ike-tooling.git/" canonicalise correctly.
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        // Drop a trailing ".git".
        if (s.endsWith(".git")) {
            s = s.substring(0, s.length() - 4);
        }
        return s;
    }
}
