package network.ike.plugin.deploy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import network.ike.plugin.deploy.SnapshotMetadataHealer.Credentials;
import network.ike.plugin.deploy.SnapshotMetadataHealer.Outcome;
import network.ike.plugin.deploy.SnapshotMetadataHealer.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SnapshotMetadataHealer} against a real HTTP repository: an in-process
 * {@link HttpServer} that stores files, answers GET and DELETE, and can demand
 * Basic credentials or refuse deletes (IKE-Network/ike-issues#1160).
 */
class SnapshotMetadataHealerTest {

    private static final String DIR = "network/ike/platform/ike-parent/188-SNAPSHOT/";
    private static final String XML = DIR + "maven-metadata.xml";
    private static final byte[] METADATA =
            "<metadata><versioning><snapshot><buildNumber>1</buildNumber></snapshot></versioning></metadata>"
                    .getBytes(StandardCharsets.UTF_8);
    private static final byte[] REWRITTEN =
            "<metadata><versioning/></metadata>".getBytes(StandardCharsets.UTF_8);
    private static final Credentials DEPLOYER = new Credentials("deployer", "s3cret");

    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private final List<String> requests = new ArrayList<>();
    private volatile Credentials required;
    private volatile int deleteStatus = 204;
    private HttpServer server;
    private URI repository;
    private SnapshotMetadataHealer healer;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repository/ike-snapshots/", this::handle);
        server.start();
        repository = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                + "/repository/ike-snapshots/");
        healer = new SnapshotMetadataHealer(HttpClient.newHttpClient(), Duration.ofSeconds(5));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void consistentMetadataIsLeftAlone() {
        files.put(XML, METADATA);
        files.put(XML + ".sha1", digest("SHA-1", METADATA));
        files.put(XML + ".md5", digest("MD5", METADATA));

        Result result = heal("188-SNAPSHOT");

        assertThat(result.outcome()).isEqualTo(Outcome.CONSISTENT);
        assertThat(result.deleted()).isEmpty();
        assertThat(requests).noneMatch(r -> r.startsWith("DELETE"));
        assertThat(files).containsKeys(XML, XML + ".sha1", XML + ".md5");
    }

    @Test
    void rewrittenMetadataWithTheOldChecksumsIsDeletedWithEverySidecar() {
        // The Nexus fault: its rebuild replaced the XML; the deploy's checksums remain.
        files.put(XML, REWRITTEN);
        files.put(XML + ".sha1", digest("SHA-1", METADATA));
        files.put(XML + ".md5", digest("MD5", METADATA));
        files.put(DIR + "ike-parent-188-20260929.010327-1.pom", METADATA);

        Result result = heal("188-SNAPSHOT");

        assertThat(result.outcome()).isEqualTo(Outcome.HEALED);
        assertThat(result.detail()).contains("sha1 stored").contains("md5 stored");
        assertThat(result.deleted()).containsExactly(XML, XML + ".sha1", XML + ".md5");
        assertThat(files).doesNotContainKeys(XML, XML + ".sha1", XML + ".md5");
        assertThat(files)
                .as("artifacts in the version directory are never touched")
                .containsKey(DIR + "ike-parent-188-20260929.010327-1.pom");
        assertThat(requests)
                .as("the absent sha256 and sha512 are asked for too, harmlessly")
                .contains("DELETE /repository/ike-snapshots/" + XML + ".sha256",
                        "DELETE /repository/ike-snapshots/" + XML + ".sha512");
    }

    @Test
    void aSingleDisagreeingSidecarIsEnough() {
        files.put(XML, METADATA);
        files.put(XML + ".sha1", digest("SHA-1", METADATA));
        files.put(XML + ".md5", digest("MD5", REWRITTEN));

        Result result = heal("188-SNAPSHOT");

        assertThat(result.outcome()).isEqualTo(Outcome.HEALED);
        assertThat(result.detail()).contains("md5").doesNotContain("sha1 stored");
        assertThat(files).doesNotContainKey(XML);
    }

    @Test
    void sidecarsWrittenAsDigestAndFileNameAreRead() {
        files.put(XML, METADATA);
        files.put(XML + ".sha1", (HexFormat.of().formatHex(sha("SHA-1", METADATA))
                + "  maven-metadata.xml\n").getBytes(StandardCharsets.US_ASCII));

        assertThat(heal("188-SNAPSHOT").outcome()).isEqualTo(Outcome.CONSISTENT);
    }

    @Test
    void metadataWithoutSidecarsIsConsistent() {
        files.put(XML, METADATA);

        assertThat(heal("188-SNAPSHOT").outcome()).isEqualTo(Outcome.CONSISTENT);
        assertThat(files).containsKey(XML);
    }

    @Test
    void noMetadataYetIsAbsent() {
        Result result = heal("188-SNAPSHOT");

        assertThat(result.outcome()).isEqualTo(Outcome.ABSENT);
        assertThat(requests).containsExactly("GET /repository/ike-snapshots/" + XML);
    }

    @Test
    void aReleaseVersionIsNeverRead() {
        files.put("network/ike/platform/ike-parent/188/maven-metadata.xml", REWRITTEN);

        Result result = heal("188");

        assertThat(result.outcome()).isEqualTo(Outcome.NOT_A_SNAPSHOT);
        assertThat(requests).isEmpty();
    }

    @Test
    void credentialsAreSentWhenTheRepositoryDemandsThem() {
        required = DEPLOYER;
        files.put(XML, REWRITTEN);
        files.put(XML + ".sha1", digest("SHA-1", METADATA));

        Result result = healer.heal(repository, Optional.of(DEPLOYER),
                "network.ike.platform", "ike-parent", "188-SNAPSHOT");

        assertThat(result.outcome()).isEqualTo(Outcome.HEALED);
        assertThat(files).doesNotContainKey(XML);
    }

    @Test
    void anUnreadableRepositoryIsReportedAndNothingIsDeleted() {
        required = DEPLOYER;
        files.put(XML, REWRITTEN);
        files.put(XML + ".sha1", digest("SHA-1", METADATA));

        Result result = heal("188-SNAPSHOT");

        assertThat(result.outcome()).isEqualTo(Outcome.UNREACHABLE);
        assertThat(result.detail()).contains("401");
        assertThat(files).containsKeys(XML, XML + ".sha1");
    }

    @Test
    void aRefusedDeleteIsReportedWithTheMetadataLeftInPlace() {
        deleteStatus = 403;
        files.put(XML, REWRITTEN);
        files.put(XML + ".sha1", digest("SHA-1", METADATA));

        Result result = heal("188-SNAPSHOT");

        assertThat(result.outcome()).isEqualTo(Outcome.DELETE_FAILED);
        assertThat(result.detail()).contains("403");
        assertThat(result.deleted()).isEmpty();
        assertThat(files).containsKeys(XML, XML + ".sha1");
    }

    @Test
    void aRepositoryUrlWithoutTheTrailingSlashResolvesTheSame() {
        files.put(XML, REWRITTEN);
        files.put(XML + ".sha1", digest("SHA-1", METADATA));
        String noSlash = repository.toString().substring(0, repository.toString().length() - 1);

        Result result = healer.heal(URI.create(noSlash), Optional.empty(),
                "network.ike.platform", "ike-parent", "188-SNAPSHOT");

        assertThat(result.outcome()).isEqualTo(Outcome.HEALED);
    }

    @Test
    void anUnreachableHostIsReportedNotThrown() {
        server.stop(0);

        Result result = heal("188-SNAPSHOT");

        assertThat(result.outcome()).isEqualTo(Outcome.UNREACHABLE);
    }

    // ── Fixture ──────────────────────────────────────────────────────

    private Result heal(String version) {
        return healer.heal(repository, Optional.empty(), "network.ike.platform", "ike-parent", version);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        synchronized (requests) {
            requests.add(exchange.getRequestMethod() + " " + path);
        }
        String key = path.substring("/repository/ike-snapshots/".length());
        Credentials needed = required;
        if (needed != null && !needed.header().equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            respond(exchange, 401, null);
            return;
        }
        switch (exchange.getRequestMethod()) {
            case "GET" -> {
                byte[] body = files.get(key);
                respond(exchange, body == null ? 404 : 200, body);
            }
            case "DELETE" -> {
                if (!files.containsKey(key)) {
                    respond(exchange, 404, null);
                } else if (deleteStatus == 204) {
                    files.remove(key);
                    respond(exchange, 204, null);
                } else {
                    respond(exchange, deleteStatus, null);
                }
            }
            default -> respond(exchange, 405, null);
        }
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        if (body == null) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static byte[] digest(String algorithm, byte[] content) {
        return HexFormat.of().formatHex(sha(algorithm, content)).getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] sha(String algorithm, byte[] content) {
        try {
            return MessageDigest.getInstance(algorithm).digest(content);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
