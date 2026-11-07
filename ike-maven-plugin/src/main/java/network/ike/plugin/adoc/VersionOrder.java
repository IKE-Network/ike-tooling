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
package network.ike.plugin.adoc;

import java.util.Comparator;

/**
 * Orders Maven versions well enough to pick the newest library in a local repository: a
 * numeric segment compares as a number, any other as text, and a snapshot sorts before the
 * release of the same base. The goal only ever suggests a version; the build never fetches
 * one, so this order decides a message and nothing else.
 */
final class VersionOrder implements Comparator<String> {

    /** The one instance. */
    static final VersionOrder INSTANCE = new VersionOrder();

    private VersionOrder() {
    }

    @Override
    public int compare(String a, String b) {
        boolean aSnapshot = a.endsWith("-SNAPSHOT");
        boolean bSnapshot = b.endsWith("-SNAPSHOT");
        String[] as = strip(a, aSnapshot).split("[.\\-]");
        String[] bs = strip(b, bSnapshot).split("[.\\-]");
        for (int i = 0; i < Math.max(as.length, bs.length); i++) {
            String x = i < as.length ? as[i] : "0";
            String y = i < bs.length ? bs[i] : "0";
            int c = compareSegment(x, y);
            if (c != 0) {
                return c;
            }
        }
        return Boolean.compare(bSnapshot, aSnapshot);
    }

    private static String strip(String version, boolean snapshot) {
        return snapshot ? version.substring(0, version.length() - "-SNAPSHOT".length()) : version;
    }

    private static int compareSegment(String x, String y) {
        boolean xNumeric = x.chars().allMatch(Character::isDigit) && !x.isEmpty();
        boolean yNumeric = y.chars().allMatch(Character::isDigit) && !y.isEmpty();
        if (xNumeric && yNumeric) {
            return Long.compare(Long.parseLong(x), Long.parseLong(y));
        }
        if (xNumeric != yNumeric) {
            return xNumeric ? 1 : -1;
        }
        return x.compareTo(y);
    }
}
