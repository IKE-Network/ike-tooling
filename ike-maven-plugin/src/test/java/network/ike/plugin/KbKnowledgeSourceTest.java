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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class KbKnowledgeSourceTest {

    @Test
    @DisplayName("A five-part coordinate carries a classifier; a four-part one does not")
    void parsesCoordinates() {
        KbKnowledgeSource knowledgeSource = KbKnowledgeSource.parse("network.ike.data:snomedct-kb:zip:unreasoned-pb:20260601.2-SNAPSHOT");
        assertThat(knowledgeSource.groupId()).isEqualTo("network.ike.data");
        assertThat(knowledgeSource.artifactId()).isEqualTo("snomedct-kb");
        assertThat(knowledgeSource.extension()).isEqualTo("zip");
        assertThat(knowledgeSource.classifier()).isEqualTo("unreasoned-pb");
        assertThat(knowledgeSource.version()).isEqualTo("20260601.2-SNAPSHOT");
        assertThat(knowledgeSource.pinnedVersion()).isEqualTo(knowledgeSource.version());
        assertThat(knowledgeSource.sizeBytes()).isEqualTo(-1);

        KbKnowledgeSource release = KbKnowledgeSource.parse("network.ike.data:gudid-origin:zip:20261001");
        assertThat(release.classifier()).isEmpty();
        assertThat(release.version()).isEqualTo("20261001");
    }

    @Test
    @DisplayName("Anything but a coordinate is refused")
    void refusesNonCoordinates() {
        assertThatIllegalArgumentException().isThrownBy(() -> KbKnowledgeSource.parse(""));
        assertThatIllegalArgumentException().isThrownBy(() -> KbKnowledgeSource.parse("g:a:v"));
        assertThatIllegalArgumentException().isThrownBy(() -> KbKnowledgeSource.parse("g:a:zip:c:v:extra"));
        assertThatIllegalArgumentException().isThrownBy(() -> KbKnowledgeSource.parse("g::zip:c:v"));
        assertThatIllegalArgumentException().isThrownBy(() -> KbKnowledgeSource.parse("g:a b:zip:c:v"));
    }

    @Test
    @DisplayName("A path is not a coordinate")
    void tellsPathsFromCoordinates() {
        assertThat(KbKnowledgeSource.isCoordinate("network.ike.data:gudid-origin:zip:20261001")).isTrue();
        assertThat(KbKnowledgeSource.isCoordinate("/data/releases/SnomedCT_NUVA.zip")).isFalse();
        assertThat(KbKnowledgeSource.isCoordinate("C:\\releases\\nuva.zip")).isFalse();
        assertThat(KbKnowledgeSource.isCoordinate("releases.zip")).isFalse();
        assertThat(KbKnowledgeSource.isCoordinate(null)).isFalse();
    }

    @Test
    @DisplayName("A timestamped snapshot is exact and lives under its SNAPSHOT folder")
    void snapshotsAndFolders() {
        KbKnowledgeSource snapshot = KbKnowledgeSource.parse("network.ike.data:snomedct-kb:zip:unreasoned-pb:20260601.2-SNAPSHOT");
        assertThat(snapshot.snapshot()).isTrue();
        assertThat(snapshot.exact()).isFalse();

        KbKnowledgeSource timestamped = KbKnowledgeSource.parse("network.ike.data:snomedct-kb:zip:unreasoned-pb:20260601.2-20261111.020518-1");
        assertThat(timestamped.snapshot()).isTrue();
        assertThat(timestamped.exact()).isTrue();
        assertThat(timestamped.folderPath()).isEqualTo("network/ike/data/snomedct-kb/20260601.2-SNAPSHOT/");
        assertThat(timestamped.filePath()).isEqualTo("network/ike/data/snomedct-kb/20260601.2-SNAPSHOT/"
                + "snomedct-kb-20260601.2-20261111.020518-1-unreasoned-pb.zip");

        KbKnowledgeSource release = KbKnowledgeSource.parse("network.ike.data:gudid-origin:zip:20261001");
        assertThat(release.snapshot()).isFalse();
        assertThat(release.exact()).isTrue();
        assertThat(release.filePath()).isEqualTo("network/ike/data/gudid-origin/20261001/gudid-origin-20261001.zip");
        assertThat(KbKnowledgeSource.baseVersion("nuva-sct-ext-NE1002000_20250509T120000Z+1.0.0-20261019.164040-1"))
                .isEqualTo("nuva-sct-ext-NE1002000_20250509T120000Z+1.0.0-SNAPSHOT");
    }

    @Test
    @DisplayName("Pinning keeps the requested version and records the exact one and the size")
    void pinning() {
        KbKnowledgeSource knowledgeSource = KbKnowledgeSource.parse("network.ike.data:snomedct-kb:zip:unreasoned-pb:20260601.2-SNAPSHOT")
                .pinned("20260601.2-20261111.020518-1", 484_000_000L);
        assertThat(knowledgeSource.version()).isEqualTo("20260601.2-SNAPSHOT");
        assertThat(knowledgeSource.pinnedVersion()).isEqualTo("20260601.2-20261111.020518-1");
        assertThat(knowledgeSource.sizeBytes()).isEqualTo(484_000_000L);
        assertThat(knowledgeSource.coordinate(knowledgeSource.pinnedVersion()))
                .isEqualTo("network.ike.data:snomedct-kb:zip:unreasoned-pb:20260601.2-20261111.020518-1");
    }

    @Test
    @DisplayName("The version property is the typed marker; the input names it")
    void typedMarker() {
        KbKnowledgeSource knowledgeSource = KbKnowledgeSource.parse("network.ike.data:snomedct-kb:zip:unreasoned-pb:20260601.2-SNAPSHOT");
        assertThat(knowledgeSource.versionProperty()).isEqualTo("network.ike.data__GA__snomedct-kb__VERSION");
        assertThat(knowledgeSource.coordinate())
                .isEqualTo("network.ike.data:snomedct-kb:zip:unreasoned-pb:${network.ike.data__GA__snomedct-kb__VERSION}");
    }

    @Test
    @DisplayName("The starter set and the two protobuf kinds are recognized")
    void recognizesKinds() {
        assertThat(KbKnowledgeSource.parse("network.ike.foundation:ike-starter-set:zip:unreasoned-pb:1-SNAPSHOT").starterSet()).isTrue();
        assertThat(KbKnowledgeSource.parse("network.ike.data:ike-starter-set:zip:unreasoned-pb:1-SNAPSHOT").starterSet()).isFalse();
        assertThat(KbKnowledgeSource.parse("network.ike.data:solor-kb:zip:reasoned-pb:1-SNAPSHOT").reasoned()).isTrue();
        assertThat(KbKnowledgeSource.parse("network.ike.data:nuva-kb:zip:unreasoned-pb:1-SNAPSHOT").unreasoned()).isTrue();
        assertThat(KbKnowledgeSource.parse("dev.ikm.tinkar.data:pizzakb:zip:pb:1.0.0").reasoned()).isFalse();
        assertThat(KbKnowledgeSource.parse("dev.ikm.tinkar.data:pizzakb:zip:pb:1.0.0").unreasoned()).isFalse();
    }

    @Test
    @DisplayName("Each kind reads from its parameter value")
    void kinds() {
        assertThat(KbKind.parse(null)).isEqualTo(KbKind.ASSEMBLY);
        assertThat(KbKind.parse(" ")).isEqualTo(KbKind.ASSEMBLY);
        assertThat(KbKind.parse("assembly")).isEqualTo(KbKind.ASSEMBLY);
        assertThat(KbKind.parse("Source")).isEqualTo(KbKind.SOURCE);
        assertThatIllegalArgumentException().as("the name before 282 is refused with the new one")
                .isThrownBy(() -> KbKind.parse("Source_Layer"))
                .withMessage("-Dkind=source-layer is now -Dkind=source. The knowledge sources were called layers before"
                        + " ike-maven-plugin 282.");
        assertThat(KbKind.SOURCE.publishedClassifier()).isEqualTo("unreasoned-pb");
        assertThat(KbKind.ASSEMBLY.publishedClassifier()).isEqualTo("reasoned-pb");
        assertThatIllegalArgumentException().isThrownBy(() -> KbKind.parse("store"))
                .withMessageContaining("source, assembly");
    }
}
