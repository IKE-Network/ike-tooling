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
/**
 * The AsciiDoc package union behind {@code ike:adoc-union}
 * (IKE-Network/ike-issues#1342; design topic {@code dev-adoc-packages}).
 *
 * <p>A topic library lays its AsciiDoc root out as packages: the first directory is the
 * owning group as one dotted segment and the next is the domain, so one topic is
 * {@code network.ike.lab/dev/gitflow-overview.adoc} in the source tree, in the zip the
 * library ships, and in the union a build unpacks every library into. A library's own
 * metadata is under {@code META-INF/ike/<group>/<artifact>/}, keyed by coordinates so it
 * cannot collide. The classes here are the pure part of the goal: they take plain paths and
 * records and never touch a Maven session, so the tests exercise them against real zips and
 * a real reactor layout.
 *
 * <ul>
 *   <li>{@link network.ike.plugin.adoc.AdocUnion} writes and verifies the union.</li>
 *   <li>{@link network.ike.plugin.adoc.AdocManifest} reads a library's manifest from its
 *       zip, or derives one from a reactor member's tree.</li>
 *   <li>{@link network.ike.plugin.adoc.AdocLibrary} is one library to put in the union:
 *       an external zip, or a module of the same reactor.</li>
 * </ul>
 */
package network.ike.plugin.adoc;
