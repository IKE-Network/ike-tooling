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

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * What a new knowledge-base project builds (IKE-Network/ike-issues#1370): the two POM
 * shapes the existing knowledge bases take.
 */
enum KbKind {

    /**
     * A source release imported onto its base layers and exported unclassified as an
     * {@code unreasoned-pb}, the shape of {@code nuva-kb}: one {@code SOURCE} input, a
     * {@code knowledge-verify} against the base, no Rocks store, no authoring.
     */
    SOURCE_LAYER("source-layer", "source layer", "unreasoned-pb"),

    /**
     * Layers combined, with the edits harvested from Komet, classified and exported as a
     * {@code reasoned-pb}, the shape of {@code pizza-kb}: a round-trip
     * {@code knowledge-verify}, a Rocks store when the size warrants it, and a
     * {@code komet} profile for authoring.
     */
    ASSEMBLY("assembly", "assembly", "reasoned-pb");

    private final String parameterValue;
    private final String label;
    private final String publishedClassifier;

    KbKind(String parameterValue, String label, String publishedClassifier) {
        this.parameterValue = parameterValue;
        this.label = label;
        this.publishedClassifier = publishedClassifier;
    }

    /**
     * The value {@code -Dkind} takes for this kind.
     *
     * @return such as {@code source-layer}
     */
    String parameterValue() {
        return parameterValue;
    }

    /**
     * How a report names this kind.
     *
     * @return such as {@code source layer}
     */
    String label() {
        return label;
    }

    /**
     * The classifier of the protobuf this kind publishes.
     *
     * @return {@code unreasoned-pb} or {@code reasoned-pb}
     */
    String publishedClassifier() {
        return publishedClassifier;
    }

    /**
     * Reads {@code -Dkind}: {@code source-layer} or {@code assembly}, case and
     * underscores ignored; absent means {@link #ASSEMBLY}.
     *
     * @param value the parameter value, or {@code null}
     * @return the kind
     * @throws IllegalArgumentException if the value names no kind
     */
    static KbKind parse(String value) {
        if (value == null || value.isBlank()) {
            return ASSEMBLY;
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT).replace('_', '-');
        for (KbKind kind : values()) {
            if (kind.parameterValue.equals(normalized)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("-Dkind=" + value + " names no kind; use one of "
                + Arrays.stream(values()).map(KbKind::parameterValue).collect(Collectors.joining(", ")));
    }
}
