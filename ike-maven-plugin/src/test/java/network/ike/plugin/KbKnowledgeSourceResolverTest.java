/*
 * Copyright © 2026 IKE Network (support@ike.network)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package network.ike.plugin;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins knowledge sources against a real HTTP server standing in for a Maven repository: metadata is
 * served by GET, files answer HEAD with their length, and a login can be demanded.
 */
class KbKnowledgeSourceResolverTest {

    private static final String PREFIX = "/repository/ike-restricted/";

    private HttpServer server;
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private final List<String> requests = new ArrayList<>();
    private String requiredAuthorization;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(PREFIX, this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath().substring(PREFIX.length());
        synchronized (requests) {
            requests.add(exchange.getRequestMethod() + " " + path);
        }
        if (requiredAuthorization != null
                && !requiredAuthorization.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
            return;
        }
        byte[] body = files.get(path);
        if (body == null) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().add("Content-Length", Long.toString(body.length));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private KbKnowledgeSourceResolver resolver(String username, String password) {
        URI repository = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + PREFIX);
        return new KbKnowledgeSourceResolver(HttpClient.newHttpClient(), repository, username, password, Duration.ofSeconds(10));
    }

    private static String metadata(String... snapshotVersions) {
        StringBuilder entries = new StringBuilder();
        for (String entry : snapshotVersions) {
            String[] parts = entry.split("\\|");
            entries.append("<snapshotVersion>")
                    .append(parts[0].isEmpty() ? "" : "<classifier>" + parts[0] + "</classifier>")
                    .append("<extension>").append(parts[1]).append("</extension>")
                    .append("<value>").append(parts[2]).append("</value>")
                    .append("</snapshotVersion>");
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><metadata><groupId>network.ike.data</groupId>"
                + "<artifactId>snomedct-kb</artifactId><version>20260601.2-SNAPSHOT</version><versioning>"
                + "<snapshot><timestamp>20261111.020518</timestamp><buildNumber>1</buildNumber></snapshot>"
                + "<snapshotVersions>" + entries + "</snapshotVersions></versioning></metadata>";
    }

    @Test
    @DisplayName("A snapshot pins to the deploy its metadata names for its classifier, with its size")
    void pinsSnapshotByClassifier() throws Exception {
        String folder = "network/ike/data/snomedct-kb/20260601.2-SNAPSHOT/";
        files.put(folder + "maven-metadata.xml", metadata(
                "|pom|20260601.2-20261111.020518-1",
                "reasoned-pb|zip|20260601.2-20261110.000000-9",
                "unreasoned-pb|zip|20260601.2-20261111.020518-1").getBytes(StandardCharsets.UTF_8));
        files.put(folder + "snomedct-kb-20260601.2-20261111.020518-1-unreasoned-pb.zip", new byte[4096]);

        KbKnowledgeSource pinned = resolver(null, null)
                .pin(KbKnowledgeSource.parse("network.ike.data:snomedct-kb:zip:unreasoned-pb:20260601.2-SNAPSHOT"));

        assertThat(pinned.pinnedVersion()).isEqualTo("20260601.2-20261111.020518-1");
        assertThat(pinned.version()).isEqualTo("20260601.2-SNAPSHOT");
        assertThat(pinned.sizeBytes()).isEqualTo(4096);
    }

    @Test
    @DisplayName("Without a matching snapshotVersion, the last snapshot's timestamp is used")
    void fallsBackToLastSnapshot() throws Exception {
        String folder = "network/ike/data/snomedct-kb/20260601.2-SNAPSHOT/";
        files.put(folder + "maven-metadata.xml", metadata("|pom|20260601.2-20261111.020518-1")
                .getBytes(StandardCharsets.UTF_8));
        files.put(folder + "snomedct-kb-20260601.2-20261111.020518-1-unreasoned-pb.zip", new byte[10]);

        KbKnowledgeSource pinned = resolver(null, null)
                .pin(KbKnowledgeSource.parse("network.ike.data:snomedct-kb:zip:unreasoned-pb:20260601.2-SNAPSHOT"));

        assertThat(pinned.pinnedVersion()).isEqualTo("20260601.2-20261111.020518-1");
    }

    @Test
    @DisplayName("An exact version is checked by HEAD alone, never by metadata")
    void exactVersionNeedsNoMetadata() throws Exception {
        files.put("network/ike/data/gudid-origin/20261001/gudid-origin-20261001.zip", new byte[77]);

        KbKnowledgeSource pinned = resolver(null, null).pin(KbKnowledgeSource.parse("network.ike.data:gudid-origin:zip:20261001"));

        assertThat(pinned.pinnedVersion()).isEqualTo("20261001");
        assertThat(pinned.sizeBytes()).isEqualTo(77);
        assertThat(requests).containsExactly("HEAD network/ike/data/gudid-origin/20261001/gudid-origin-20261001.zip");
    }

    @Test
    @DisplayName("A snapshot never deployed is reported as such")
    void neverDeployed() {
        assertThatThrownBy(() -> resolver(null, null)
                .pin(KbKnowledgeSource.parse("network.ike.data:missing-kb:zip:unreasoned-pb:1-SNAPSHOT")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("has never been deployed");
    }

    @Test
    @DisplayName("A file the metadata names but the repository lacks is reported")
    void fileMissing() {
        String folder = "network/ike/data/snomedct-kb/20260601.2-SNAPSHOT/";
        files.put(folder + "maven-metadata.xml", metadata("unreasoned-pb|zip|20260601.2-20261111.020518-1")
                .getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> resolver(null, null)
                .pin(KbKnowledgeSource.parse("network.ike.data:snomedct-kb:zip:unreasoned-pb:20260601.2-SNAPSHOT")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("20260601.2-20261111.020518-1")
                .hasMessageContaining("is not on");
    }

    @Test
    @DisplayName("A refused login says where the login comes from; the right one is sent")
    void login() throws Exception {
        requiredAuthorization = "Basic " + Base64.getEncoder()
                .encodeToString("reader:secret".getBytes(StandardCharsets.UTF_8));
        files.put("network/ike/data/gudid-origin/20261001/gudid-origin-20261001.zip", new byte[1]);
        KbKnowledgeSource release = KbKnowledgeSource.parse("network.ike.data:gudid-origin:zip:20261001");

        assertThatThrownBy(() -> resolver(null, null).pin(release))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("401")
                .hasMessageContaining("settings.xml");
        assertThatThrownBy(() -> resolver("reader", "wrong").pin(release))
                .hasMessageContaining("was refused");
        assertThat(resolver("reader", "secret").pin(release).sizeBytes()).isEqualTo(1);
    }

    @Test
    @DisplayName("Metadata that is not XML is refused, and a DOCTYPE is never processed")
    void malformedMetadata() {
        KbKnowledgeSource knowledgeSource = KbKnowledgeSource.parse("network.ike.data:snomedct-kb:zip:unreasoned-pb:20260601.2-SNAPSHOT");
        assertThatThrownBy(() -> KbKnowledgeSourceResolver.snapshotVersion(knowledgeSource, "not xml".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IOException.class);
        String doctype = "<?xml version=\"1.0\"?><!DOCTYPE metadata [<!ENTITY x \"y\">]><metadata/>";
        assertThatThrownBy(() -> KbKnowledgeSourceResolver.snapshotVersion(knowledgeSource, doctype.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IOException.class);
    }
}
