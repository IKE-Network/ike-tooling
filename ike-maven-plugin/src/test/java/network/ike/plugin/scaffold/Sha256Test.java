package network.ike.plugin.scaffold;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class Sha256Test {

    /**
     * Known SHA-256 of the empty byte array (RFC test vector).
     */
    private static final String EMPTY_HASH =
            "sha256:"
                    + "e3b0c44298fc1c149afbf4c8996fb924"
                    + "27ae41e4649b934ca495991b7852b855";

    /**
     * Known SHA-256 of the UTF-8 encoding of "abc" (NIST test vector).
     */
    private static final String ABC_HASH =
            "sha256:"
                    + "ba7816bf8f01cfea414140de5dae2223"
                    + "b00361a396177a9cb410ff61f20015ad";

    @Test
    void hashOfEmptyArrayMatchesRfcVector() {
        assertThat(Sha256.of(new byte[0])).isEqualTo(EMPTY_HASH);
    }

    @Test
    void hashOfAbcMatchesNistVector() {
        assertThat(Sha256.of("abc")).isEqualTo(ABC_HASH);
    }

    @Test
    void nullInputTreatedAsEmpty() {
        assertThat(Sha256.of((byte[]) null)).isEqualTo(EMPTY_HASH);
        assertThat(Sha256.of((String) null)).isEqualTo(EMPTY_HASH);
    }

    @Test
    void prefixIsAlwaysSha256() {
        assertThat(Sha256.of("anything"))
                .startsWith(Sha256.PREFIX);
    }

    @Test
    void hashIsLowercaseHex() {
        String h = Sha256.of("abc");
        String digest = h.substring(Sha256.PREFIX.length());
        assertThat(digest).hasSize(64);
        assertThat(digest).matches("[0-9a-f]{64}");
    }

    @Test
    void ofFileMatchesOfBytes(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("sample.txt");
        Files.writeString(file, "abc");
        assertThat(Sha256.ofFile(file)).isEqualTo(ABC_HASH);
    }

    @Test
    void matchesReturnsTrueOnEqualHash(@TempDir Path tmp)
            throws IOException {
        Path file = tmp.resolve("sample.txt");
        Files.writeString(file, "abc");
        assertThat(Sha256.matches(file, ABC_HASH)).isTrue();
    }

    @Test
    void matchesReturnsFalseOnDifferentContent(@TempDir Path tmp)
            throws IOException {
        Path file = tmp.resolve("sample.txt");
        Files.writeString(file, "different content");
        assertThat(Sha256.matches(file, ABC_HASH)).isFalse();
    }

    @Test
    void matchesReturnsFalseOnNullExpected(@TempDir Path tmp)
            throws IOException {
        Path file = tmp.resolve("sample.txt");
        Files.writeString(file, "abc");
        assertThat(Sha256.matches(file, null)).isFalse();
    }

    @Test
    void ofFilePropagatesMissingFileAsScaffoldException(
            @TempDir Path tmp) {
        Path missing = tmp.resolve("no-such-file.txt");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        Sha256.ofFile(missing))
                .isInstanceOf(ScaffoldException.class)
                .hasMessageContaining("hash file");
    }
}
