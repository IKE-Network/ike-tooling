package network.ike.plugin;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Preview an announcement to a Zulip stream without posting it
 * (IKE-Network/ike-issues#1016).
 *
 * <p>Announcing is outward-facing — other people read the channel — so
 * the draft form is not a convenience. It prints the destination and the
 * exact message, so an operator can see what would be said before
 * anything is said.
 *
 * <p>Credentials come from the environment ({@code ZULIP_BOT_EMAIL},
 * {@code ZULIP_BOT_TOKEN}), which is what lets the same goal run locally
 * under {@code op run} and on CI from its own parameters — the goal
 * itself never holds a secret.
 */
@Mojo(name = IkeGoal.NAME_ANNOUNCE_DRAFT, projectRequired = false)
public class IkeAnnounceDraftMojo implements org.apache.maven.api.plugin.Mojo {

    @org.apache.maven.api.di.Inject
    private org.apache.maven.api.plugin.Log log;

    /**
     * Access the Maven logger.
     *
     * @return the logger
     */
    protected org.apache.maven.api.plugin.Log getLog() { return log; }

    /** Zulip site base URL, e.g. {@code https://ike.zulipchat.com}. */
    @Parameter(property = "zulip.url", defaultValue = "https://ike.zulipchat.com")
    String zulipUrl;

    /** Destination stream. */
    @Parameter(property = "zulip.stream", required = true)
    String stream;

    /** Destination topic within the stream. */
    @Parameter(property = "zulip.topic", required = true)
    String topic;

    /** Message body (Zulip-flavoured markdown). Ignored when {@link #contentFile} is set. */
    @Parameter(property = "zulip.content")
    String content;

    /** File holding the message body — the usual form for generated notes. */
    @Parameter(property = "zulip.contentFile")
    String contentFile;

    /**
     * Internal toggle: {@code true} for {@code announce-publish},
     * {@code false} for {@code announce-draft}.
     */
    @Parameter(property = "publish", defaultValue = "false")
    boolean publish;

    /** Creates this goal instance. */
    public IkeAnnounceDraftMojo() {}

    @Override
    public void execute() throws MojoException {
        AnnounceSupport.Announcement announcement =
                new AnnounceSupport.Announcement(trimTrailingSlash(zulipUrl),
                        stream, topic, resolveContent());

        String problem = AnnounceSupport.validate(announcement);
        if (problem != null) {
            throw new MojoException("Cannot announce: " + problem);
        }

        getLog().info("");
        getLog().info(publish ? IkeGoal.ANNOUNCE_PUBLISH.qualified()
                : IkeGoal.ANNOUNCE_DRAFT.qualified());
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  To: " + AnnounceSupport.destination(announcement));
        getLog().info("");
        announcement.content().lines()
                .forEach(line -> getLog().info("  │ " + line));
        getLog().info("");

        if (!publish) {
            getLog().info("  [DRAFT] Nothing posted. Re-run as "
                    + IkeGoal.ANNOUNCE_PUBLISH.qualified() + " to post.");
            return;
        }

        AnnounceSupport.BotIdentity bot = new AnnounceSupport.BotIdentity(
                requiredEnv("ZULIP_BOT_EMAIL"), requiredEnv("ZULIP_BOT_TOKEN"));
        try {
            String response = AnnounceSupport.post(announcement, bot);
            getLog().info("  ✓ posted — " + response);
        } catch (Exception e) {
            throw new MojoException("Announcement failed: " + e.getMessage(), e);
        }
    }

    /**
     * The message body, from the file when one is named.
     *
     * @return the resolved content
     * @throws MojoException when the named file cannot be read
     */
    private String resolveContent() {
        if (contentFile == null || contentFile.isBlank()) return content;
        try {
            return Files.readString(Path.of(contentFile), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MojoException("Cannot read zulip.contentFile "
                    + contentFile + ": " + e.getMessage(), e);
        }
    }

    /**
     * Read a required credential from the environment, failing with the
     * variable's name rather than an authentication error later.
     *
     * @param name the environment variable to read
     * @return its value
     * @throws MojoException when unset or empty
     */
    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new MojoException(name + " is not set — locally this comes"
                    + " from `op run --env-file`, on CI from a project"
                    + " parameter of the same name");
        }
        return value;
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) return null;
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
