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

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Pins a knowledge base's layers against a Maven repository without downloading them
 * (IKE-Network/ike-issues#1370): a snapshot is fixed to the exact deploy its
 * {@code maven-metadata.xml} names for the layer's extension and classifier, and a
 * {@code HEAD} of the file proves it is there and says its size, which decides whether a
 * Rocks store is built (decision 6). A layer of several gigabytes is never transferred.
 */
final class KbLayerResolver {

    private final HttpClient client;
    private final URI repository;
    private final Optional<String> authorization;
    private final Duration timeout;

    /**
     * Creates a resolver against one repository.
     *
     * @param client     the HTTP client
     * @param repository the repository's URL, such as
     *                   {@code https://nexus.tinkar.org/repository/ike-restricted/}
     * @param username   the login, or {@code null} to read anonymously
     * @param password   the password, or {@code null} to read anonymously
     * @param timeout    the timeout of each request
     */
    KbLayerResolver(HttpClient client, URI repository, String username, String password, Duration timeout) {
        this.client = client;
        String url = repository.toString();
        this.repository = URI.create(url.endsWith("/") ? url : url + "/");
        this.authorization = username == null || password == null
                ? Optional.empty()
                : Optional.of("Basic " + Base64.getEncoder().encodeToString(
                        (username + ":" + password).getBytes(StandardCharsets.UTF_8)));
        this.timeout = timeout;
    }

    /**
     * The repository this resolver reads.
     *
     * @return its URL, ending in {@code /}
     */
    URI repository() {
        return repository;
    }

    /**
     * Pins a layer and reads its size.
     *
     * @param layer the layer as requested
     * @return the layer at its exact version, with its size ({@code -1} when the
     *         repository does not say)
     * @throws IOException          if the repository cannot be read, holds no deploy of a
     *                              requested snapshot, or does not hold the file
     * @throws InterruptedException if interrupted
     */
    KbLayer pin(KbLayer layer) throws IOException, InterruptedException {
        String exact = layer.exact() ? layer.version() : snapshotVersion(layer);
        KbLayer pinned = layer.pinned(exact, -1);
        HttpResponse<Void> head = send(pinned.filePath(), "HEAD", HttpResponse.BodyHandlers.discarding());
        if (head.statusCode() == 404) {
            throw new IOException(pinned.coordinate(exact) + " is not on " + repository);
        }
        if (head.statusCode() != 200) {
            throw new IOException("HEAD " + pinned.filePath() + " on " + repository
                    + " answered " + head.statusCode() + unauthorizedHint(head.statusCode()));
        }
        OptionalLong length = head.headers().firstValueAsLong("Content-Length");
        return layer.pinned(exact, length.orElse(-1));
    }

    /**
     * The exact deploy a snapshot names, from its version's {@code maven-metadata.xml}.
     *
     * @param layer the snapshot layer
     * @return the timestamped version
     * @throws IOException          if the metadata cannot be read or names no deploy of
     *                              the layer's extension and classifier
     * @throws InterruptedException if interrupted
     */
    private String snapshotVersion(KbLayer layer) throws IOException, InterruptedException {
        String metadataPath = layer.folderPath() + "maven-metadata.xml";
        HttpResponse<byte[]> metadata = send(metadataPath, "GET", HttpResponse.BodyHandlers.ofByteArray());
        if (metadata.statusCode() == 404) {
            throw new IOException(layer.coordinate(layer.version()) + " has never been deployed to " + repository);
        }
        if (metadata.statusCode() != 200) {
            throw new IOException("GET " + metadataPath + " on " + repository + " answered "
                    + metadata.statusCode() + unauthorizedHint(metadata.statusCode()));
        }
        return snapshotVersion(layer, metadata.body()).orElseThrow(() -> new IOException(
                metadataPath + " on " + repository + " names no deploy of "
                        + layer.coordinate(layer.version())));
    }

    /**
     * Reads the exact deploy a version's metadata names for a layer: the
     * {@code snapshotVersion} entry of its extension and classifier, else the version's
     * last {@code snapshot} timestamp and build number.
     *
     * @param layer    the snapshot layer
     * @param metadata the bytes of its version's {@code maven-metadata.xml}
     * @return the timestamped version, when the metadata names one
     * @throws IOException if the metadata is not well-formed XML
     */
    static Optional<String> snapshotVersion(KbLayer layer, byte[] metadata) throws IOException {
        Document document = parse(metadata);
        NodeList entries = document.getElementsByTagName("snapshotVersion");
        for (int index = 0; index < entries.getLength(); index++) {
            Element entry = (Element) entries.item(index);
            String extension = text(entry, "extension");
            String classifier = text(entry, "classifier");
            String value = text(entry, "value");
            if (layer.extension().equals(extension) && layer.classifier().equals(classifier) && !value.isEmpty()) {
                return Optional.of(value);
            }
        }
        NodeList snapshots = document.getElementsByTagName("snapshot");
        if (snapshots.getLength() > 0) {
            Element snapshot = (Element) snapshots.item(0);
            String timestamp = text(snapshot, "timestamp");
            String buildNumber = text(snapshot, "buildNumber");
            if (!timestamp.isEmpty() && !buildNumber.isEmpty()) {
                String base = KbLayer.baseVersion(layer.version());
                return Optional.of(base.substring(0, base.length() - "SNAPSHOT".length())
                        + timestamp + "-" + buildNumber);
            }
        }
        return Optional.empty();
    }

    private static Document parse(byte[] xml) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(xml));
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Not well-formed maven-metadata.xml: " + e.getMessage(), e);
        }
    }

    private static String text(Element parent, String child) {
        NodeList nodes = parent.getElementsByTagName(child);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().strip();
    }

    private <T> HttpResponse<T> send(String path, String method, HttpResponse.BodyHandler<T> body)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(repository.resolve(path))
                .timeout(timeout)
                .method(method, HttpRequest.BodyPublishers.noBody());
        authorization.ifPresent(value -> request.header("Authorization", value));
        return client.send(request.build(), body);
    }

    private String unauthorizedHint(int status) {
        if (status != 401 && status != 403) {
            return "";
        }
        return authorization.isEmpty()
                ? " (no login: add the repository's server to ~/.m2/settings.xml)"
                : " (the login in ~/.m2/settings.xml was refused)";
    }
}
