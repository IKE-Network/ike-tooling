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
package network.ike.plugin.deploy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Repairs a snapshot version's {@code maven-metadata.xml} whose checksum
 * sidecars no longer match it, so the next deploy can proceed
 * (IKE-Network/ike-issues#1160).
 *
 * <p>Nexus rebuilds metadata in the background after a deploy and can
 * rewrite a version's {@code maven-metadata.xml} without writing a matching
 * {@code .sha1} (IKE-Network/ike-issues#1107). The next deploy of that
 * version downloads the pair, fails checksum validation, and stops; nothing
 * on the client side repairs it. Deleting the metadata and its sidecars lets
 * that deploy write them fresh, because the metadata is derived from the
 * version's files and carries nothing of its own.
 *
 * <p>The healer touches only {@code <group>/<artifact>/<version>/maven-metadata.xml}
 * and its {@code .sha1}, {@code .md5}, {@code .sha256} and {@code .sha512}
 * sidecars, only for a {@code -SNAPSHOT} version, and only when a sidecar
 * that is present disagrees with the XML. Artifacts and release versions
 * are never touched. A repository that cannot be read is reported and left
 * to the deploy, which reports the underlying problem itself.
 */
public final class SnapshotMetadataHealer {

    /** The metadata file this healer checks. */
    static final String METADATA = "maven-metadata.xml";

    /** What the healer found and did for one version. */
    public enum Outcome {
        /** The version is not a snapshot; nothing was read. */
        NOT_A_SNAPSHOT,
        /** No metadata yet: the version was never deployed, or it was cleaned up. */
        ABSENT,
        /** The metadata matches every checksum sidecar present, or none is present. */
        CONSISTENT,
        /** A sidecar disagreed; the metadata and its sidecars were deleted. */
        HEALED,
        /** A sidecar disagreed, but the metadata could not be deleted. */
        DELETE_FAILED,
        /** The repository could not be read; the deploy reports why. */
        UNREACHABLE
    }

    /**
     * The result for one version.
     *
     * @param outcome what was found and done
     * @param detail  a one-line explanation for the build log
     * @param deleted the repository-relative paths deleted, in order
     */
    public record Result(Outcome outcome, String detail, List<String> deleted) {

        /**
         * Creates a result, copying {@code deleted}.
         *
         * @param outcome what was found and done
         * @param detail  a one-line explanation for the build log
         * @param deleted the repository-relative paths deleted
         */
        public Result {
            deleted = List.copyOf(deleted);
        }
    }

    /**
     * Credentials for the repository, as Maven's settings give them for the
     * deploy target's server id.
     *
     * @param username the user name
     * @param password the password
     */
    public record Credentials(String username, String password) {

        /** @return the value of an HTTP {@code Authorization} header for Basic auth */
        String header() {
            String pair = username + ":" + password;
            return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
        }
    }

    private final HttpClient client;
    private final Duration timeout;

    /**
     * Creates a healer.
     *
     * @param client  the HTTP client to reach the repository with
     * @param timeout the timeout for each request
     */
    public SnapshotMetadataHealer(HttpClient client, Duration timeout) {
        this.client = client;
        this.timeout = timeout;
    }

    /**
     * Checks one version's metadata and repairs it when a checksum sidecar
     * disagrees with it.
     *
     * @param repository  the deploy repository's base URL
     * @param credentials the repository credentials, or empty to send none
     * @param groupId     the artifact's group id
     * @param artifactId  the artifact id
     * @param version     the version being deployed
     * @return what was found and done
     */
    public Result heal(URI repository, Optional<Credentials> credentials,
                       String groupId, String artifactId, String version) {
        if (!version.endsWith("-SNAPSHOT")) {
            return new Result(Outcome.NOT_A_SNAPSHOT, version + " is not a snapshot", List.of());
        }
        String directory = groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/";
        String metadataPath = directory + METADATA;
        try {
            HttpResponse<byte[]> metadata = send(repository, metadataPath, "GET", credentials);
            if (metadata.statusCode() == 404) {
                return new Result(Outcome.ABSENT, metadataPath + " does not exist yet", List.of());
            }
            if (metadata.statusCode() != 200) {
                return new Result(Outcome.UNREACHABLE,
                        "GET " + metadataPath + " answered " + metadata.statusCode(), List.of());
            }
            List<String> mismatches = new ArrayList<>();
            for (Checksum checksum : Checksum.values()) {
                HttpResponse<byte[]> sidecar =
                        send(repository, metadataPath + checksum.suffix, "GET", credentials);
                if (sidecar.statusCode() == 404) {
                    continue;
                }
                if (sidecar.statusCode() != 200) {
                    return new Result(Outcome.UNREACHABLE, "GET " + metadataPath + checksum.suffix
                            + " answered " + sidecar.statusCode(), List.of());
                }
                String stored = storedDigest(sidecar.body());
                String actual = checksum.of(metadata.body());
                if (!actual.equals(stored)) {
                    mismatches.add(checksum.suffix.substring(1) + " stored " + abbreviate(stored)
                            + ", actual " + abbreviate(actual));
                }
            }
            if (mismatches.isEmpty()) {
                return new Result(Outcome.CONSISTENT, metadataPath + " matches its checksums",
                        List.of());
            }
            return delete(repository, credentials, metadataPath, String.join("; ", mismatches));
        } catch (IOException e) {
            return new Result(Outcome.UNREACHABLE,
                    "could not read " + metadataPath + ": " + e.getMessage(), List.of());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(Outcome.UNREACHABLE,
                    "interrupted reading " + metadataPath, List.of());
        }
    }

    private Result delete(URI repository, Optional<Credentials> credentials, String metadataPath,
                          String mismatch) throws IOException, InterruptedException {
        List<String> deleted = new ArrayList<>();
        List<String> targets = new ArrayList<>();
        targets.add(metadataPath);
        for (Checksum checksum : Checksum.values()) {
            targets.add(metadataPath + checksum.suffix);
        }
        for (String target : targets) {
            int status = send(repository, target, "DELETE", credentials).statusCode();
            if (status == 200 || status == 202 || status == 204) {
                deleted.add(target);
            } else if (status != 404) {
                return new Result(Outcome.DELETE_FAILED, mismatch + "; DELETE " + target
                        + " answered " + status, deleted);
            }
        }
        return new Result(Outcome.HEALED, mismatch, deleted);
    }

    private HttpResponse<byte[]> send(URI repository, String path, String method,
                                      Optional<Credentials> credentials)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(resolve(repository, path))
                .timeout(timeout)
                .method(method, HttpRequest.BodyPublishers.noBody());
        credentials.ifPresent(c -> request.header("Authorization", c.header()));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static URI resolve(URI repository, String path) {
        String base = repository.toString();
        return URI.create(base.endsWith("/") ? base + path : base + "/" + path);
    }

    /**
     * The digest in a checksum sidecar: its first whitespace-separated token,
     * lower case. Sidecars written as {@code <digest>  <file name>} parse too.
     */
    static String storedDigest(byte[] sidecar) {
        String text = new String(sidecar, StandardCharsets.US_ASCII).strip();
        int space = indexOfWhitespace(text);
        return (space < 0 ? text : text.substring(0, space)).toLowerCase(Locale.ROOT);
    }

    private static int indexOfWhitespace(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static String abbreviate(String digest) {
        return digest.length() > 8 ? digest.substring(0, 8) + "…" : digest;
    }

    /** The checksum sidecars that are compared with the metadata. */
    private enum Checksum {
        SHA1(".sha1", "SHA-1"),
        MD5(".md5", "MD5"),
        SHA256(".sha256", "SHA-256"),
        SHA512(".sha512", "SHA-512");

        private final String suffix;
        private final String algorithm;

        Checksum(String suffix, String algorithm) {
            this.suffix = suffix;
            this.algorithm = algorithm;
        }

        String of(byte[] content) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(content));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(algorithm + " is a required JDK algorithm", e);
            }
        }
    }
}
