package network.ike.plugin;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Posting an announcement to a Zulip stream, split so that everything
 * except the network call is pure and testable
 * (IKE-Network/ike-issues#1016).
 *
 * <p>Exists because the release and checkpoint chains each carried their
 * own copy of this as shell inside a CI build step — unreproducible off
 * the server, and prone to a class of bug a goal cannot have (an
 * unresolved {@code %parameter%} reference silently makes a TeamCity
 * configuration incompatible with every agent).
 */
public final class AnnounceSupport {

    private AnnounceSupport() {}

    /**
     * A resolved announcement: where it goes and what it says.
     *
     * @param site    the Zulip site base URL, without a trailing slash
     * @param stream  the destination stream
     * @param topic   the destination topic within the stream
     * @param content the message body, Zulip-flavoured markdown
     */
    public record Announcement(String site, String stream, String topic,
                               String content) {}

    /**
     * Credentials for the posting bot.
     *
     * @param email the bot's email address
     * @param token the bot's API token
     */
    public record BotIdentity(String email, String token) {}

    /**
     * Validate an announcement, naming precisely what is missing rather
     * than letting the API answer with a generic rejection.
     *
     * @param a the announcement to check
     * @return the first problem found, or {@code null} when valid
     */
    public static String validate(Announcement a) {
        if (a == null) return "no announcement";
        if (isBlank(a.site())) return "zulip site URL is not set";
        if (isBlank(a.stream())) return "stream is not set";
        if (isBlank(a.topic())) return "topic is not set";
        if (isBlank(a.content())) return "message content is empty";
        if (a.content().length() > 10000) {
            return "message is " + a.content().length() + " characters; "
                    + "Zulip rejects anything over 10000";
        }
        return null;
    }

    /**
     * The form body Zulip's {@code /api/v1/messages} endpoint expects.
     * Built here rather than at the call site so a test can read it.
     *
     * @param a the announcement to encode
     * @return the URL-encoded form body
     */
    public static String formBody(Announcement a) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("type", "stream");
        fields.put("to", a.stream());
        fields.put("topic", a.topic());
        fields.put("content", a.content());
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            if (!body.isEmpty()) body.append('&');
            body.append(field.getKey()).append('=')
                    .append(URLEncoder.encode(field.getValue(),
                            StandardCharsets.UTF_8));
        }
        return body.toString();
    }

    /**
     * A one-line, credential-free description of where a message would
     * go — what the draft prints, and what the publish echoes.
     *
     * @param a the announcement
     * @return the destination, e.g. {@code chat.example/#Stream > topic}
     */
    public static String destination(Announcement a) {
        return a.site().replaceFirst("^https?://", "")
                + " · #" + a.stream() + " > " + a.topic();
    }

    /**
     * Post the announcement. Zulip authenticates with HTTP basic auth
     * over the bot's email and token.
     *
     * @param a   the announcement to post
     * @param bot the posting identity
     * @return the response body, which carries the message id on success
     * @throws Exception when the request fails or Zulip rejects it
     */
    public static String post(Announcement a, BotIdentity bot)
            throws Exception {
        String basic = Base64.getEncoder().encodeToString(
                (bot.email() + ":" + bot.token())
                        .getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(a.site() + "/api/v1/messages"))
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(formBody(a),
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15)).build()) {
            response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Zulip rejected the message ("
                    + response.statusCode() + "): " + response.body());
        }
        return response.body();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
