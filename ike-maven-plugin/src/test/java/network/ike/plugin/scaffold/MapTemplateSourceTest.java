package network.ike.plugin.scaffold;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MapTemplateSourceTest {

    @Test
    void readsBytesForKnownSource() {
        byte[] body = "hello\n".getBytes(StandardCharsets.UTF_8);
        MapTemplateSource src = new MapTemplateSource(
                Map.of("mvnw", body));
        assertThat(src.read("mvnw")).isEqualTo(body);
    }

    @Test
    void unknownSourceThrows() {
        MapTemplateSource src = new MapTemplateSource(Map.of());
        assertThatThrownBy(() -> src.read("missing"))
                .isInstanceOf(ScaffoldException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void readReturnsACopyNotTheBackingArray() {
        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
        MapTemplateSource src = new MapTemplateSource(
                Map.of("mvnw", body));
        byte[] first = src.read("mvnw");
        first[0] = 'H';
        byte[] second = src.read("mvnw");
        assertThat(second[0]).isEqualTo((byte) 'h');
    }

    @Test
    void ofStringsEncodesUtf8() {
        MapTemplateSource src = MapTemplateSource.ofStrings(
                Map.of("greeting", "héllo"));
        byte[] bytes = src.read("greeting");
        assertThat(new String(bytes, StandardCharsets.UTF_8))
                .isEqualTo("héllo");
    }
}
