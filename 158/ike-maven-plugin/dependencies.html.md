---
date_published: 2026-05-11
date_modified: 2026-05-11
canonical_url: https://ike.network/ike-tooling/ike-maven-plugin/dependencies.html
---

# Project Dependencies

## [compile](#compile)

The following is a list of compile dependencies for this project. These dependencies are required to compile and run the application:

| GroupId | ArtifactId | Version | Type | Licenses |
| --- | --- | --- | --- | --- |
| network.ike.pipeline | [koncept-asciidoc-extension](https://github.com/IKE-Network/ike-pipeline)[1] | 84 | jar | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| network.ike.tooling | [ike-maven-plugin-support](https://ike.network/ike-tooling/ike-maven-plugin-support/)[3] | 158 | jar | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| network.ike.tooling | [ike-workspace-model](https://ike.network/ike-tooling/ike-workspace-model/)[4] | 158 | jar | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| org.asciidoctor | [asciidoctorj](https://github.com/asciidoctor/asciidoctorj)[5] | 3.0.1 | jar | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
| org.asciidoctor | [asciidoctorj-diagram](https://github.com/asciidoctor/asciidoctorj-diagram)[7] | 3.2.1 | jar | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
| org.asciidoctor | [asciidoctorj-pdf](https://github.com/asciidoctor/asciidoctorj-pdf)[8] | 2.3.23 | jar | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
| org.jruby | [jruby](https://github.com/jruby/jruby/jruby-artifacts/jruby)[9] | 10.0.3.0 | jar | [GPL-2.0](http://www.gnu.org/licenses/gpl-2.0-standalone.html)[10][LGPL-2.1](http://www.gnu.org/licenses/lgpl-2.1-standalone.html)[11][EPL-2.0](http://www.eclipse.org/legal/epl-v20.html)[12] |
| org.openrewrite | [rewrite-xml](https://github.com/openrewrite/rewrite)[13] | 8.79.2 | jar | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |

## [test](#test)

The following is a list of test dependencies for this project. These dependencies are only required to compile and run unit tests for the application:

| GroupId | ArtifactId | Version | Type | Licenses |
| --- | --- | --- | --- | --- |
| org.apache.maven | [maven-di](https://maven.apache.org/ref/4.0.0-rc-5/maven-impl-modules/maven-di/)[14] | 4.0.0-rc-5 | jar | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| org.assertj | [assertj-core](https://assertj.github.io/doc/#assertj-core)[15] | 3.27.3 | jar | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| org.junit.jupiter | [junit-jupiter](https://junit.org/)[16] | 6.0.0 | jar | [Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[17] |
| org.testcontainers | [testcontainers](https://java.testcontainers.org)[18] | 2.0.4 | jar | [MIT](http://opensource.org/licenses/MIT)[19] |
| org.testcontainers | [testcontainers-junit-jupiter](https://java.testcontainers.org)[18] | 2.0.4 | jar | [MIT](http://opensource.org/licenses/MIT)[19] |

## [provided](#provided)

The following is a list of provided dependencies for this project. These dependencies are required to compile the application, but should be provided by default when using the library:

| GroupId | ArtifactId | Version | Classifier | Type | Licenses |
| --- | --- | --- | --- | --- | --- |
| network.ike.tooling | [ike-build-standards](https://ike.network/ike-tooling/ike-build-standards/)[20] | 158 | claude | zip | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| org.apache.maven | [maven-api-core](https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-core/)[21] | 4.0.0-rc-5 | - | jar | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| org.apache.maven | [maven-api-di](https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-di/)[22] | 4.0.0-rc-5 | - | jar | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| org.apache.maven | [maven-api-plugin](https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-plugin/)[23] | 4.0.0-rc-5 | - | jar | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |

# Project Transitive Dependencies

The following is a list of transitive dependencies for this project. Transitive dependencies are the dependencies of the project dependencies.

## [compile](#compile_2)

The following is a list of compile dependencies for this project. These dependencies are required to compile and run the application:

| GroupId | ArtifactId | Version | Classifier | Type | Licenses |
| --- | --- | --- | --- | --- | --- |
| com.fasterxml.jackson.core | [jackson-annotations](https://github.com/FasterXML/jackson)[24] | 2.21 | - | jar | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| com.fasterxml.jackson.core | [jackson-core](https://github.com/FasterXML/jackson-core)[25] | 2.21.2 | - | jar | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| com.fasterxml.jackson.core | [jackson-databind](https://github.com/FasterXML/jackson)[24] | 2.21.2 | - | jar | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| com.fasterxml.jackson.dataformat | [jackson-dataformat-smile](https://github.com/FasterXML/jackson-dataformats-binary)[26] | 2.21.2 | - | jar | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| com.fasterxml.jackson.datatype | [jackson-datatype-jsr310](https://github.com/FasterXML/jackson-modules-java8/jackson-datatype-jsr310)[27] | 2.21.2 | - | jar | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
| com.fasterxml.jackson.module | [jackson-module-parameter-names](https://github.com/FasterXML/jackson-modules-java8/jackson-module-parameter-names)[28] | 2.21.2 | - | jar | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
| com.fasterxml.woodstox | [woodstox-core](https://github.com/FasterXML/woodstox)[29] | 7.1.1 | - | jar | [The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
| com.github.jnr | [jffi](http://github.com/jnr/jffi)[30] | 1.3.14 | native | jar | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6][GNU Lesser General Public License version 3](https://www.gnu.org/licenses/lgpl-3.0.txt)[31] |
| com.github.jnr | [jffi](http://github.com/jnr/jffi)[30] | 1.3.14 | - | jar | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6][GNU Lesser General Public License version 3](https://www.gnu.org/licenses/lgpl-3.0.txt)[31] |
| com.github.jnr | [jnr-a64asm](http://nexus.sonatype.org/oss-repository-hosting.html/jnr-a64asm)[32] | 1.0.0 | - | jar | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
| com.github.jnr | [jnr-constants](http://github.com/jnr/jnr-constants)[33] | 0.10.4 | - | jar | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
| com.github.jnr | [jnr-enxio](http://github.com/jnr/jnr-enxio)[34] | 0.32.19 | - | jar | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
| com.github.jnr | [jnr-ffi](http://github.com/jnr/jnr-ffi)[35] | 2.2.18 | - | jar | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
| com.github.jnr | [jnr-netdb](http://github.com/jnr/jnr-netdb)[36] | 1.2.0 | - | jar | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
| com.github.jnr | [jnr-posix](http://github.com/jnr/jnr-posix)[37] | 3.1.21 | - | jar | [Eclipse Public License - v 2.0](https://www.eclipse.org/legal/epl-2.0/)[38][GNU General Public License Version 2](http://www.gnu.org/copyleft/gpl.html)[39][GNU Lesser General Public License Version 2.1](http://www.gnu.org/licenses/lgpl.html)[40] |
| com.github.jnr | [jnr-unixsocket](http://github.com/jnr/jnr-unixsocket)[41] | 0.38.24 | - | jar | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
| com.github.jnr | [jnr-x86asm](http://github.com/jnr/jnr-x86asm)[42] | 1.0.2 | - | jar | [MIT License](http://www.opensource.org/licenses/mit-license.php)[43] |
| com.headius | [backport9](http://nexus.sonatype.org/oss-repository-hosting.html/backport9)[44] | 1.13 | - | jar | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
| com.headius | [invokebinder](http://maven.apache.org)[45] | 1.14 | - | jar | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
| com.headius | [options](https://github.com/headius/options)[46] | 1.6 | - | jar | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
| joda-time | [joda-time](https://www.joda.org/joda-time/)[47] | 2.14.0 | - | jar | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| me.qmx.jitescript | [jitescript](https://github.com/qmx/jitescript)[48] | 0.4.1 | - | jar | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
| org.apache.maven | [maven-api-annotations](https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-annotations/)[49] | 4.0.0-rc-5 | - | jar | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| org.apache.maven | [maven-api-metadata](https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-metadata/)[50] | 4.0.0-rc-5 | - | jar | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| org.apache.maven | [maven-api-model](https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-model/)[51] | 4.0.0-rc-5 | - | jar | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| org.apache.maven | [maven-api-settings](https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-settings/)[52] | 4.0.0-rc-5 | - | jar | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| org.apache.maven | [maven-api-toolchain](https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-toolchain/)[53] | 4.0.0-rc-5 | - | jar | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| org.apache.maven | [maven-api-xml](https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-xml/)[54] | 4.0.0-rc-5 | - | jar | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| org.apache.maven | [maven-support](https://maven.apache.org/ref/4.0.0-rc-5/maven-impl-modules/maven-support/)[55] | 4.0.0-rc-5 | - | jar | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| org.apache.maven | [maven-xml](https://maven.apache.org/ref/4.0.0-rc-5/maven-impl-modules/maven-xml/)[56] | 4.0.0-rc-5 | - | jar | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| org.asciidoctor | [asciidoctorj-api](https://github.com/asciidoctor/asciidoctorj)[5] | 3.0.1 | - | jar | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
| org.codehaus.woodstox | [stax2-api](http://github.com/FasterXML/stax2-api)[57] | 4.2.2 | - | jar | [The BSD 2-Clause License](http://www.opensource.org/licenses/bsd-license.php)[58] |
| org.crac | [crac](https://github.com/crac/org.crac)[59] | 1.5.0 | - | jar | [BSD-2-Clause](https://opensource.org/licenses/BSD-2-Clause)[60] |
| org.jetbrains | [annotations](https://github.com/JetBrains/java-annotations)[61] | 26.1.0 | - | jar | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| org.jruby | [dirgra](https://github.com/jruby/dirgra)[62] | 0.5 | - | jar | [EPL](http://www.eclipse.org/legal/epl-v10.html)[63] |
| org.jruby | [jruby-base](https://github.com/jruby/jruby/jruby-base)[64] | 10.0.3.0 | - | jar | [GPL-2.0](http://www.gnu.org/licenses/gpl-2.0-standalone.html)[10][LGPL-2.1](http://www.gnu.org/licenses/lgpl-2.1-standalone.html)[11][EPL-2.0](http://www.eclipse.org/legal/epl-v20.html)[12] |
| org.jruby | [jruby-stdlib](https://github.com/jruby/jruby/jruby-stdlib)[65] | 10.0.3.0 | - | jar | [GPL-2.0](http://www.gnu.org/licenses/gpl-2.0-standalone.html)[10][LGPL-2.1](http://www.gnu.org/licenses/lgpl-2.1-standalone.html)[11][EPL-2.0](http://www.eclipse.org/legal/epl-v20.html)[12] |
| org.jruby | [jzlib](http://www.jcraft.com/jzlib/)[66] | 1.1.5 | - | jar | [BSD](http://www.jcraft.com/jzlib/LICENSE.txt)[67] |
| org.jruby.jcodings | [jcodings](http://nexus.sonatype.org/oss-repository-hosting.html/jcodings)[68] | 1.0.63 | - | jar | [MIT License](http://www.opensource.org/licenses/mit-license.php)[43] |
| org.jruby.joni | [joni](http://nexus.sonatype.org/oss-repository-hosting.html/joni)[69] | 2.2.6 | - | jar | [MIT License](http://www.opensource.org/licenses/mit-license.php)[43] |
| org.jspecify | [jspecify](http://jspecify.org/)[70] | 1.0.0 | - | jar | [The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
| org.openrewrite | [rewrite-core](https://github.com/openrewrite/rewrite)[13] | 8.79.2 | - | jar | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
| org.openrewrite.tools | [jgit](https://github.com/openrewrite/jgit)[71] | 1.4.1 | - | jar | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
| org.ow2.asm | [asm](http://asm.ow2.io/)[72] | 9.7.1 | - | jar | [BSD-3-Clause](https://asm.ow2.io/license.html)[73] |
| org.ow2.asm | [asm-analysis](http://asm.ow2.io/)[72] | 9.7.1 | - | jar | [BSD-3-Clause](https://asm.ow2.io/license.html)[73] |
| org.ow2.asm | [asm-commons](http://asm.ow2.io/)[72] | 9.7.1 | - | jar | [BSD-3-Clause](https://asm.ow2.io/license.html)[73] |
| org.ow2.asm | [asm-tree](http://asm.ow2.io/)[72] | 9.7.1 | - | jar | [BSD-3-Clause](https://asm.ow2.io/license.html)[73] |
| org.ow2.asm | [asm-util](http://asm.ow2.io/)[72] | 9.7.1 | - | jar | [BSD-3-Clause](https://asm.ow2.io/license.html)[73] |
| org.yaml | [snakeyaml](https://bitbucket.org/snakeyaml/snakeyaml)[74] | 2.2 | - | jar | [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |

## [runtime](#runtime)

The following is a list of runtime dependencies for this project. These dependencies are required to run the application:

| GroupId | ArtifactId | Version | Type | Licenses |
| --- | --- | --- | --- | --- |
| com.github.ben-manes.caffeine | [caffeine](https://github.com/ben-manes/caffeine)[75] | 2.9.3 | jar | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| com.google.errorprone | [error_prone_annotations](https://errorprone.info/error_prone_annotations)[76] | 2.10.0 | jar | [Apache 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
| com.googlecode.javaewah | [JavaEWAH](https://github.com/lemire/javaewah)[77] | 1.1.13 | jar | [Apache 2](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
| com.univocity | [univocity-parsers](http://github.com/univocity/univocity-parsers)[78] | 2.9.1 | jar | [Apache 2](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
| io.github.classgraph | [classgraph](https://github.com/classgraph/classgraph)[79] | 4.8.184 | jar | [The MIT License (MIT)](http://opensource.org/licenses/MIT)[19] |
| io.micrometer | [micrometer-core](https://github.com/micrometer-metrics/micrometer)[80] | 1.9.17 | jar | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
| io.moderne | [jsonrpc](https://github.com/moderneinc/jsonrpc)[81] | 1.0.5 | jar | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
| io.quarkus.gizmo | [gizmo](http://www.jboss.org/gizmo)[82] | 1.0.11.Final | jar | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
| net.java.dev.jna | [jna](https://github.com/java-native-access/jna)[83] | 5.18.1 | jar | [LGPL-2.1-or-later](https://www.gnu.org/licenses/old-licenses/lgpl-2.1)[84][Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| net.java.dev.jna | [jna-platform](https://github.com/java-native-access/jna)[83] | 5.18.1 | jar | [LGPL-2.1-or-later](https://www.gnu.org/licenses/old-licenses/lgpl-2.1)[84][Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| org.antlr | [antlr4-runtime](https://www.antlr.org/antlr4-runtime/)[85] | 4.13.2 | jar | [BSD-3-Clause](https://www.antlr.org/license.html)[86] |
| org.apache.commons | [commons-lang3](https://commons.apache.org/proper/commons-lang/)[87] | 3.20.0 | jar | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| org.apache.commons | [commons-text](https://commons.apache.org/proper/commons-text)[88] | 1.15.0 | jar | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| org.checkerframework | [checker-qual](https://checkerframework.org)[89] | 3.19.0 | jar | [The MIT License](http://opensource.org/licenses/MIT)[19] |
| org.hdrhistogram | [HdrHistogram](http://hdrhistogram.github.io/HdrHistogram/)[90] | 2.1.12 | jar | [Public Domain, per Creative Commons CC0](http://creativecommons.org/publicdomain/zero/1.0/)[91][BSD-2-Clause](https://opensource.org/licenses/BSD-2-Clause)[60] |
| org.jboss | [jandex](http://www.jboss.org/jandex)[92] | 2.4.2.Final | jar | [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
| org.latencyutils | [LatencyUtils](http://latencyutils.github.io/LatencyUtils/)[93] | 2.0.3 | jar | [Public Domain, per Creative Commons CC0](http://creativecommons.org/publicdomain/zero/1.0/)[91] |
| org.objenesis | [objenesis](https://objenesis.org/objenesis)[94] | 3.5 | jar | [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
| org.openrewrite.tools | [java-object-diff](https://github.com/openrewrite/java-object-diff)[95] | 1.0.1 | jar | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |

## [test](#test_2)

The following is a list of test dependencies for this project. These dependencies are only required to compile and run unit tests for the application:

| GroupId | ArtifactId | Version | Type | Licenses |
| --- | --- | --- | --- | --- |
| com.github.docker-java | [docker-java-api](https://github.com/docker-java/docker-java)[96] | 3.7.1 | jar | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
| com.github.docker-java | [docker-java-transport](https://github.com/docker-java/docker-java)[96] | 3.7.1 | jar | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
| com.github.docker-java | [docker-java-transport-zerodep](https://github.com/docker-java/docker-java)[96] | 3.7.1 | jar | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
| commons-codec | [commons-codec](https://commons.apache.org/proper/commons-codec/)[97] | 1.19.0 | jar | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| commons-io | [commons-io](https://commons.apache.org/proper/commons-io/)[98] | 2.20.0 | jar | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| net.bytebuddy | [byte-buddy](https://bytebuddy.net/byte-buddy)[99] | 1.15.11 | jar | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| org.apache.commons | [commons-compress](https://commons.apache.org/proper/commons-compress/)[100] | 1.28.0 | jar | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| org.apiguardian | [apiguardian-api](https://github.com/apiguardian-team/apiguardian)[101] | 1.1.2 | jar | [The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
| org.junit.jupiter | [junit-jupiter-api](https://junit.org/)[16] | 6.0.0 | jar | [Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[17] |
| org.junit.jupiter | [junit-jupiter-engine](https://junit.org/)[16] | 6.0.0 | jar | [Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[17] |
| org.junit.jupiter | [junit-jupiter-params](https://junit.org/)[16] | 6.0.0 | jar | [Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[17] |
| org.junit.platform | [junit-platform-commons](https://junit.org/)[16] | 6.0.0 | jar | [Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[17] |
| org.junit.platform | [junit-platform-engine](https://junit.org/)[16] | 6.0.0 | jar | [Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[17] |
| org.opentest4j | [opentest4j](https://github.com/ota4j-team/opentest4j)[102] | 1.3.0 | jar | [The Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| org.rnorth.duct-tape | [duct-tape](https://github.com/rnorth/duct-tape)[103] | 1.0.8 | jar | [MIT](http://opensource.org/licenses/MIT)[19] |
| org.slf4j | [slf4j-api](http://www.slf4j.org)[104] | 1.7.36 | jar | [MIT License](http://www.opensource.org/licenses/mit-license.php)[43] |

# Project Dependency Graph

## [Dependency Tree](#dependency-tree)

- network.ike.tooling:ike-maven-plugin:maven-plugin:158 ** 
  
  | IKE Maven Plugin |
  | --- |
  | **Description: **Cross-platform Maven plugin providing release, documentation rendering, site deployment, and build management goals for IKE projects. Workspace goals are in ike-workspace-maven-plugin (ws: prefix). **URL: **[https://ike.network/ike-tooling/ike-maven-plugin/](https://ike.network/ike-tooling/ike-maven-plugin/)[105] **Project Licenses: **[Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
  
    - network.ike.tooling:ike-build-standards:zip:claude:158 (provided) ** 
      
      | IKE Build Standards |
      | --- |
      | **Description: **Versioned Claude instruction files for IKE projects. Modular standards (Maven, Java, IKE-specific) distributed as a classified Maven artifact. **URL: **[https://ike.network/ike-tooling/ike-build-standards/](https://ike.network/ike-tooling/ike-build-standards/)[20] **Project Licenses: **[Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
    - network.ike.tooling:ike-workspace-model:jar:158 (compile) ** 
      
      | IKE Workspace Model |
      | --- |
      | **Description: **Typed model for workspace.yaml manifests with graph algorithms (topological sort, cascade analysis, cycle detection) and version manipulation utilities. Foundation for ike-maven-plugin workspace goals. **URL: **[https://ike.network/ike-tooling/ike-workspace-model/](https://ike.network/ike-tooling/ike-workspace-model/)[4] **Project Licenses: **[Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
      
          - org.yaml:snakeyaml:jar:2.2 (compile) ** 
            
            | SnakeYAML |
            | --- |
            | **Description: **YAML 1.1 parser and emitter for Java **URL: **[https://bitbucket.org/snakeyaml/snakeyaml](https://bitbucket.org/snakeyaml/snakeyaml)[74] **Project Licenses: **[Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
          - org.apache.maven:maven-support:jar:4.0.0-rc-5 (compile) ** 
            
            | Maven 4 Model Support |
            | --- |
            | **Description: **Provides the Maven 4 Model Support **URL: **[https://maven.apache.org/ref/4.0.0-rc-5/maven-impl-modules/maven-support/](https://maven.apache.org/ref/4.0.0-rc-5/maven-impl-modules/maven-support/)[55] **Project Licenses: **[Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
            
                  - org.apache.maven:maven-api-metadata:jar:4.0.0-rc-5 (compile) ** 
                    
                    | Maven 4 API :: Repository Metadata |
                    | --- |
                    | **Description: **Maven 4 API - Immutable Repository Metadata model. **URL: **[https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-metadata/](https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-metadata/)[50] **Project Licenses: **[Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
                  - org.apache.maven:maven-xml:jar:4.0.0-rc-5 (compile) ** 
                    
                    | Maven 4 XML Implementation |
                    | --- |
                    | **Description: **Provides the implementation classes for the Maven XML **URL: **[https://maven.apache.org/ref/4.0.0-rc-5/maven-impl-modules/maven-xml/](https://maven.apache.org/ref/4.0.0-rc-5/maven-impl-modules/maven-xml/)[56] **Project Licenses: **[Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
                    
                            - com.fasterxml.woodstox:woodstox-core:jar:7.1.1 (compile) ** 
                              
                              | Woodstox |
                              | --- |
                              | **Description: **Woodstox is a high-performance XML processor that implements Stax (JSR-173), SAX2 and Stax2 APIs **URL: **[https://github.com/FasterXML/woodstox](https://github.com/FasterXML/woodstox)[29] **Project Licenses: **[The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
                            - org.codehaus.woodstox:stax2-api:jar:4.2.2 (compile) ** 
                              
                              | Stax2 API |
                              | --- |
                              | **Description: **Stax2 API is an extension to basic Stax 1.0 API that adds significant new functionality, such as full-featured bi-direction validation interface and high-performance Typed Access API. **URL: **[http://github.com/FasterXML/stax2-api](http://github.com/FasterXML/stax2-api)[57] **Project Licenses: **[The BSD 2-Clause License](http://www.opensource.org/licenses/bsd-license.php)[58] |
    - network.ike.tooling:ike-maven-plugin-support:jar:158 (compile) ** 
      
      | IKE Maven Plugin Support |
      | --- |
      | **Description: **Shared library for IKE Maven plugins: goal identifier interface, base Mojo with per-goal report writing, self-healing gitignore, and interactive parameter resolution helpers. **URL: **[https://ike.network/ike-tooling/ike-maven-plugin-support/](https://ike.network/ike-tooling/ike-maven-plugin-support/)[3] **Project Licenses: **[Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
    - org.apache.maven:maven-api-core:jar:4.0.0-rc-5 (provided) ** 
      
      | Maven 4 API :: Core |
      | --- |
      | **Description: **Maven 4 API - Maven Core API **URL: **[https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-core/](https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-core/)[21] **Project Licenses: **[Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
      
          - org.apache.maven:maven-api-annotations:jar:4.0.0-rc-5 (compile) ** 
            
            | Maven 4 API :: Meta annotations |
            | --- |
            | **Description: **Maven 4 API - Java meta annotations. **URL: **[https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-annotations/](https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-annotations/)[49] **Project Licenses: **[Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
          - org.apache.maven:maven-api-model:jar:4.0.0-rc-5 (compile) ** 
            
            | Maven 4 API :: Model |
            | --- |
            | **Description: **Maven 4 API - Immutable Model for Maven POM (Project Object Model). **URL: **[https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-model/](https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-model/)[51] **Project Licenses: **[Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
          - org.apache.maven:maven-api-settings:jar:4.0.0-rc-5 (compile) ** 
            
            | Maven 4 API :: Settings |
            | --- |
            | **Description: **Maven 4 API - Immutable Settings model. **URL: **[https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-settings/](https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-settings/)[52] **Project Licenses: **[Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
          - org.apache.maven:maven-api-toolchain:jar:4.0.0-rc-5 (compile) ** 
            
            | Maven 4 API :: Toolchain |
            | --- |
            | **Description: **Maven 4 API - Immutable Toolchain model. **URL: **[https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-toolchain/](https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-toolchain/)[53] **Project Licenses: **[Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
          - org.apache.maven:maven-api-xml:jar:4.0.0-rc-5 (compile) ** 
            
            | Maven 4 API :: XML |
            | --- |
            | **Description: **Maven 4 API - Immutable XML. **URL: **[https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-xml/](https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-xml/)[54] **Project Licenses: **[Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
    - org.apache.maven:maven-api-di:jar:4.0.0-rc-5 (provided) ** 
      
      | Maven 4 API :: Dependency Injection |
      | --- |
      | **Description: **Maven 4 API - Dependency Injection **URL: **[https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-di/](https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-di/)[22] **Project Licenses: **[Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
    - org.apache.maven:maven-api-plugin:jar:4.0.0-rc-5 (provided) ** 
      
      | Maven 4 API :: Plugin |
      | --- |
      | **Description: **Maven 4 API - Immutable Plugin model. **URL: **[https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-plugin/](https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-plugin/)[23] **Project Licenses: **[Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
    - org.asciidoctor:asciidoctorj:jar:3.0.1 (compile) ** 
      
      | asciidoctorj |
      | --- |
      | **Description: **AsciidoctorJ provides Java bindings for the Asciidoctor RubyGem (asciidoctor) using JRuby. **URL: **[https://github.com/asciidoctor/asciidoctorj](https://github.com/asciidoctor/asciidoctorj)[5] **Project Licenses: **[The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
      
          - org.asciidoctor:asciidoctorj-api:jar:3.0.1 (compile) ** 
            
            | asciidoctorj-api |
            | --- |
            | **Description: **API for AsciidoctorJ **URL: **[https://github.com/asciidoctor/asciidoctorj](https://github.com/asciidoctor/asciidoctorj)[5] **Project Licenses: **[The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
    - org.asciidoctor:asciidoctorj-pdf:jar:2.3.23 (compile) ** 
      
      | asciidoctorj-pdf |
      | --- |
      | **Description: **AsciidoctorJ PDF bundles the Asciidoctor PDF RubyGem (asciidoctor-pdf) so it can be loaded into the JVM using JRuby. **URL: **[https://github.com/asciidoctor/asciidoctorj-pdf](https://github.com/asciidoctor/asciidoctorj-pdf)[8] **Project Licenses: **[The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
    - org.asciidoctor:asciidoctorj-diagram:jar:3.2.1 (compile) ** 
      
      | asciidoctorj-diagram |
      | --- |
      | **Description: **AsciidoctorJ Diagram bundles the Asciidoctor Diagram RubyGem (asciidoctor-diagram) so it can be loaded into the JVM using JRuby. **URL: **[https://github.com/asciidoctor/asciidoctorj-diagram](https://github.com/asciidoctor/asciidoctorj-diagram)[7] **Project Licenses: **[The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
    - org.jruby:jruby:jar:10.0.3.0 (compile) ** 
      
      | JRuby Main Maven Artifact |
      | --- |
      | **Description: **JRuby is the effort to recreate the Ruby (https://www.ruby-lang.org) interpreter in Java. **URL: **[https://github.com/jruby/jruby/jruby-artifacts/jruby](https://github.com/jruby/jruby/jruby-artifacts/jruby)[9] **Project Licenses: **[GPL-2.0](http://www.gnu.org/licenses/gpl-2.0-standalone.html)[10], [LGPL-2.1](http://www.gnu.org/licenses/lgpl-2.1-standalone.html)[11], [EPL-2.0](http://www.eclipse.org/legal/epl-v20.html)[12] |
      
          - org.jruby:jruby-base:jar:10.0.3.0 (compile) ** 
            
            | JRuby Base |
            | --- |
            | **Description: **JRuby is the effort to recreate the Ruby (https://www.ruby-lang.org) interpreter in Java. **URL: **[https://github.com/jruby/jruby/jruby-base](https://github.com/jruby/jruby/jruby-base)[64] **Project Licenses: **[GPL-2.0](http://www.gnu.org/licenses/gpl-2.0-standalone.html)[10], [LGPL-2.1](http://www.gnu.org/licenses/lgpl-2.1-standalone.html)[11], [EPL-2.0](http://www.eclipse.org/legal/epl-v20.html)[12] |
            
                  - org.ow2.asm:asm:jar:9.7.1 (compile) ** 
                    
                    | asm |
                    | --- |
                    | **Description: **ASM, a very small and fast Java bytecode manipulation framework **URL: **[http://asm.ow2.io/](http://asm.ow2.io/)[72] **Project Licenses: **[BSD-3-Clause](https://asm.ow2.io/license.html)[73] |
                  - org.ow2.asm:asm-commons:jar:9.7.1 (compile) ** 
                    
                    | asm-commons |
                    | --- |
                    | **Description: **Usefull class adapters based on ASM, a very small and fast Java bytecode manipulation framework **URL: **[http://asm.ow2.io/](http://asm.ow2.io/)[72] **Project Licenses: **[BSD-3-Clause](https://asm.ow2.io/license.html)[73] |
                    
                            - org.ow2.asm:asm-tree:jar:9.7.1 (compile) ** 
                              
                              | asm-tree |
                              | --- |
                              | **Description: **Tree API of ASM, a very small and fast Java bytecode manipulation framework **URL: **[http://asm.ow2.io/](http://asm.ow2.io/)[72] **Project Licenses: **[BSD-3-Clause](https://asm.ow2.io/license.html)[73] |
                  - org.ow2.asm:asm-util:jar:9.7.1 (compile) ** 
                    
                    | asm-util |
                    | --- |
                    | **Description: **Utilities for ASM, a very small and fast Java bytecode manipulation framework **URL: **[http://asm.ow2.io/](http://asm.ow2.io/)[72] **Project Licenses: **[BSD-3-Clause](https://asm.ow2.io/license.html)[73] |
                    
                            - org.ow2.asm:asm-analysis:jar:9.7.1 (compile) ** 
                              
                              | asm-analysis |
                              | --- |
                              | **Description: **Static code analysis API of ASM, a very small and fast Java bytecode manipulation framework **URL: **[http://asm.ow2.io/](http://asm.ow2.io/)[72] **Project Licenses: **[BSD-3-Clause](https://asm.ow2.io/license.html)[73] |
                  - com.github.jnr:jnr-netdb:jar:1.2.0 (compile) ** 
                    
                    | jnr-netdb |
                    | --- |
                    | **Description: **Lookup TCP and UDP services from java **URL: **[http://github.com/jnr/jnr-netdb](http://github.com/jnr/jnr-netdb)[36] **Project Licenses: **[The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
                  - com.github.jnr:jnr-enxio:jar:0.32.19 (compile) ** 
                    
                    | jnr-enxio |
                    | --- |
                    | **Description: **Native I/O access for java **URL: **[http://github.com/jnr/jnr-enxio](http://github.com/jnr/jnr-enxio)[34] **Project Licenses: **[The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
                  - com.github.jnr:jnr-unixsocket:jar:0.38.24 (compile) ** 
                    
                    | jnr-unixsocket |
                    | --- |
                    | **Description: **UNIX socket channels for java **URL: **[http://github.com/jnr/jnr-unixsocket](http://github.com/jnr/jnr-unixsocket)[41] **Project Licenses: **[The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
                  - com.github.jnr:jnr-posix:jar:3.1.21 (compile) ** 
                    
                    | jnr-posix |
                    | --- |
                    | **Description: **Common cross-project/cross-platform POSIX APIs **URL: **[http://github.com/jnr/jnr-posix](http://github.com/jnr/jnr-posix)[37] **Project Licenses: **[Eclipse Public License - v 2.0](https://www.eclipse.org/legal/epl-2.0/)[38], [GNU General Public License Version 2](http://www.gnu.org/copyleft/gpl.html)[39], [GNU Lesser General Public License Version 2.1](http://www.gnu.org/licenses/lgpl.html)[40] |
                  - com.github.jnr:jnr-constants:jar:0.10.4 (compile) ** 
                    
                    | jnr-constants |
                    | --- |
                    | **Description: **A set of platform constants (e.g. errno values) **URL: **[http://github.com/jnr/jnr-constants](http://github.com/jnr/jnr-constants)[33] **Project Licenses: **[The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
                  - com.github.jnr:jnr-ffi:jar:2.2.18 (compile) ** 
                    
                    | jnr-ffi |
                    | --- |
                    | **Description: **A library for invoking native functions from java **URL: **[http://github.com/jnr/jnr-ffi](http://github.com/jnr/jnr-ffi)[35] **Project Licenses: **[The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
                    
                            - com.github.jnr:jnr-a64asm:jar:1.0.0 (compile) ** 
                              
                              | jnr-a64asm |
                              | --- |
                              | **Description: **A pure-java A64 assembler **URL: **[http://nexus.sonatype.org/oss-repository-hosting.html/jnr-a64asm](http://nexus.sonatype.org/oss-repository-hosting.html/jnr-a64asm)[32] **Project Licenses: **[The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
                            - com.github.jnr:jnr-x86asm:jar:1.0.2 (compile) ** 
                              
                              | jnr-x86asm |
                              | --- |
                              | **Description: **A pure-java X86 and X86_64 assembler **URL: **[http://github.com/jnr/jnr-x86asm](http://github.com/jnr/jnr-x86asm)[42] **Project Licenses: **[MIT License](http://www.opensource.org/licenses/mit-license.php)[43] |
                  - com.github.jnr:jffi:jar:1.3.14 (compile) ** 
                    
                    | jffi |
                    | --- |
                    | **Description: **Java Foreign Function Interface **URL: **[http://github.com/jnr/jffi](http://github.com/jnr/jffi)[30] **Project Licenses: **[The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6], [GNU Lesser General Public License version 3](https://www.gnu.org/licenses/lgpl-3.0.txt)[31] |
                  - com.github.jnr:jffi:jar:native:1.3.14 (compile) ** 
                    
                    | jffi |
                    | --- |
                    | **Description: **Java Foreign Function Interface **URL: **[http://github.com/jnr/jffi](http://github.com/jnr/jffi)[30] **Project Licenses: **[The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6], [GNU Lesser General Public License version 3](https://www.gnu.org/licenses/lgpl-3.0.txt)[31] |
                  - org.jruby.joni:joni:jar:2.2.6 (compile) ** 
                    
                    | Joni |
                    | --- |
                    | **Description: **Java port of Oniguruma: http://www.geocities.jp/kosako3/oniguruma that uses byte arrays directly instead of java Strings and chars **URL: **[http://nexus.sonatype.org/oss-repository-hosting.html/joni](http://nexus.sonatype.org/oss-repository-hosting.html/joni)[69] **Project Licenses: **[MIT License](http://www.opensource.org/licenses/mit-license.php)[43] |
                  - org.jruby.jcodings:jcodings:jar:1.0.63 (compile) ** 
                    
                    | JCodings |
                    | --- |
                    | **Description: **Byte based encoding support library for java **URL: **[http://nexus.sonatype.org/oss-repository-hosting.html/jcodings](http://nexus.sonatype.org/oss-repository-hosting.html/jcodings)[68] **Project Licenses: **[MIT License](http://www.opensource.org/licenses/mit-license.php)[43] |
                  - org.jruby:dirgra:jar:0.5 (compile) ** 
                    
                    | Dirgra |
                    | --- |
                    | **Description: **Simple Directed Graph **URL: **[https://github.com/jruby/dirgra](https://github.com/jruby/dirgra)[62] **Project Licenses: **[EPL](http://www.eclipse.org/legal/epl-v10.html)[63] |
                  - com.headius:invokebinder:jar:1.14 (compile) ** 
                    
                    | invokebinder |
                    | --- |
                    | **Description: **Sonatype helps open source projects to set up Maven repositories on https://oss.sonatype.org/ **URL: **[http://maven.apache.org](http://maven.apache.org)[45] **Project Licenses: **[The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
                  - com.headius:options:jar:1.6 (compile) ** 
                    
                    | options |
                    | --- |
                    | **Description: **Sonatype helps open source projects to set up Maven repositories on https://oss.sonatype.org/ **URL: **[https://github.com/headius/options](https://github.com/headius/options)[46] **Project Licenses: **[The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
                  - org.jruby:jzlib:jar:1.1.5 (compile) ** 
                    
                    | JZlib |
                    | --- |
                    | **Description: **JZlib is a re-implementation of zlib in pure Java **URL: **[http://www.jcraft.com/jzlib/](http://www.jcraft.com/jzlib/)[66] **Project Licenses: **[BSD](http://www.jcraft.com/jzlib/LICENSE.txt)[67] |
                  - joda-time:joda-time:jar:2.14.0 (compile) ** 
                    
                    | Joda-Time |
                    | --- |
                    | **Description: **Date and time library to replace JDK date handling **URL: **[https://www.joda.org/joda-time/](https://www.joda.org/joda-time/)[47] **Project Licenses: **[Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
                  - me.qmx.jitescript:jitescript:jar:0.4.1 (compile) ** 
                    
                    | jitescript |
                    | --- |
                    | **Description: **Java API for Bytecode **URL: **[https://github.com/qmx/jitescript](https://github.com/qmx/jitescript)[48] **Project Licenses: **[The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
                  - com.headius:backport9:jar:1.13 (compile) ** 
                    
                    | backport9 |
                    | --- |
                    | **Description: **Sonatype helps open source projects to set up Maven repositories on https://oss.sonatype.org/ **URL: **[http://nexus.sonatype.org/oss-repository-hosting.html/backport9](http://nexus.sonatype.org/oss-repository-hosting.html/backport9)[44] **Project Licenses: **[The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
                  - org.crac:crac:jar:1.5.0 (compile) ** 
                    
                    | crac |
                    | --- |
                    | **Description: **A wrapper for OpenJDK CRaC API to build and run on any JDK **URL: **[https://github.com/crac/org.crac](https://github.com/crac/org.crac)[59] **Project Licenses: **[BSD-2-Clause](https://opensource.org/licenses/BSD-2-Clause)[60] |
          - org.jruby:jruby-stdlib:jar:10.0.3.0 (compile) ** 
            
            | JRuby Lib Setup |
            | --- |
            | **Description: **JRuby is the effort to recreate the Ruby (https://www.ruby-lang.org) interpreter in Java. **URL: **[https://github.com/jruby/jruby/jruby-stdlib](https://github.com/jruby/jruby/jruby-stdlib)[65] **Project Licenses: **[GPL-2.0](http://www.gnu.org/licenses/gpl-2.0-standalone.html)[10], [LGPL-2.1](http://www.gnu.org/licenses/lgpl-2.1-standalone.html)[11], [EPL-2.0](http://www.eclipse.org/legal/epl-v20.html)[12] |
    - network.ike.pipeline:koncept-asciidoc-extension:jar:84 (compile) ** 
      
      | Koncept AsciiDoc Extension |
      | --- |
      | **Description: **AsciidoctorJ extension providing inline Koncept markup (k:ConceptName[]) with SVG badge rendering and auto-generated Referenced Koncepts glossary with description logic axiom display. **URL: **[https://github.com/IKE-Network/ike-pipeline](https://github.com/IKE-Network/ike-pipeline)[1] **Project Licenses: **[Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
    - org.openrewrite:rewrite-xml:jar:8.79.2 (compile) ** 
      
      | rewrite-xml |
      | --- |
      | **Description: **Eliminate tech-debt. Automatically. **URL: **[https://github.com/openrewrite/rewrite](https://github.com/openrewrite/rewrite)[13] **Project Licenses: **[The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
      
          - org.openrewrite:rewrite-core:jar:8.79.2 (compile) ** 
            
            | rewrite-core |
            | --- |
            | **Description: **Eliminate tech-debt. Automatically. **URL: **[https://github.com/openrewrite/rewrite](https://github.com/openrewrite/rewrite)[13] **Project Licenses: **[The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
            
                  - org.openrewrite.tools:jgit:jar:1.4.1 (compile) ** 
                    
                    | jgit |
                    | --- |
                    | **Description: **Fork of jgit to maintain Java 8 compatibility **URL: **[https://github.com/openrewrite/jgit](https://github.com/openrewrite/jgit)[71] **Project Licenses: **[The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
                    
                            - com.googlecode.javaewah:JavaEWAH:jar:1.1.13 (runtime) ** 
                              
                              | JavaEWAH |
                              | --- |
                              | **Description: **The bit array data structure is implemented in Java as the BitSet class. Unfortunately, this fails to scale without compression. JavaEWAH is a word-aligned compressed variant of the Java bitset class. It uses a 64-bit run-length encoding (RLE) compression scheme. The goal of word-aligned compression is not to achieve the best compression, but rather to improve query processing time. Hence, we try to save CPU cycles, maybe at the expense of storage. However, the EWAH scheme we implemented is always more efficient storage-wise than an uncompressed bitmap (implemented in Java as the BitSet class). Unlike some alternatives, javaewah does not rely on a patented scheme. **URL: **[https://github.com/lemire/javaewah](https://github.com/lemire/javaewah)[77] **Project Licenses: **[Apache 2](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
                  - com.fasterxml.jackson.core:jackson-core:jar:2.21.2 (compile) ** 
                    
                    | Jackson-core |
                    | --- |
                    | **Description: **Core Jackson processing abstractions (aka Streaming API), implementation for JSON **URL: **[https://github.com/FasterXML/jackson-core](https://github.com/FasterXML/jackson-core)[25] **Project Licenses: **[The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
                  - com.fasterxml.jackson.core:jackson-databind:jar:2.21.2 (compile) ** 
                    
                    | jackson-databind |
                    | --- |
                    | **Description: **General data-binding functionality for Jackson: works on core streaming API **URL: **[https://github.com/FasterXML/jackson](https://github.com/FasterXML/jackson)[24] **Project Licenses: **[The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
                  - com.fasterxml.jackson.dataformat:jackson-dataformat-smile:jar:2.21.2 (compile) ** 
                    
                    | Jackson dataformat: Smile |
                    | --- |
                    | **Description: **Support for reading and writing Smile ("binary JSON") encoded data using Jackson abstractions (streaming API, data binding, tree model) **URL: **[https://github.com/FasterXML/jackson-dataformats-binary](https://github.com/FasterXML/jackson-dataformats-binary)[26] **Project Licenses: **[The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
                  - com.fasterxml.jackson.module:jackson-module-parameter-names:jar:2.21.2 (compile) ** 
                    
                    | Jackson-module-parameter-names |
                    | --- |
                    | **Description: **Add-on module for Jackson (https://github.com/FasterXML/jackson) to support introspection of method/constructor parameter names, without having to add explicit property name annotation. **URL: **[https://github.com/FasterXML/jackson-modules-java8/jackson-module-parameter-names](https://github.com/FasterXML/jackson-modules-java8/jackson-module-parameter-names)[28] **Project Licenses: **[The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
                  - com.fasterxml.jackson.datatype:jackson-datatype-jsr310:jar:2.21.2 (compile) ** 
                    
                    | Jackson datatype: JSR310 |
                    | --- |
                    | **Description: **Add-on module to support JSR-310 (Java 8 Date & Time API) data types. **URL: **[https://github.com/FasterXML/jackson-modules-java8/jackson-datatype-jsr310](https://github.com/FasterXML/jackson-modules-java8/jackson-datatype-jsr310)[27] **Project Licenses: **[The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
                  - org.jspecify:jspecify:jar:1.0.0 (compile) ** 
                    
                    | JSpecify annotations |
                    | --- |
                    | **Description: **An artifact of well-named and well-specified annotations to power static analysis checks **URL: **[http://jspecify.org/](http://jspecify.org/)[70] **Project Licenses: **[The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
                  - org.openrewrite.tools:java-object-diff:jar:1.0.1 (runtime) ** 
                    
                    | java-object-diff |
                    | --- |
                    | **Description: **Fork of object differ with the logging removed **URL: **[https://github.com/openrewrite/java-object-diff](https://github.com/openrewrite/java-object-diff)[95] **Project Licenses: **[The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
                  - io.quarkus.gizmo:gizmo:jar:1.0.11.Final (runtime) ** 
                    
                    | Gizmo |
                    | --- |
                    | **Description: **A bytecode generation library. **URL: **[http://www.jboss.org/gizmo](http://www.jboss.org/gizmo)[82] **Project Licenses: **[The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
                    
                            - org.jboss:jandex:jar:2.4.2.Final (runtime) ** 
                              
                              | Java Annotation Indexer |
                              | --- |
                              | **Description: **Parent POM for JBoss projects. Provides default project build configuration. **URL: **[http://www.jboss.org/jandex](http://www.jboss.org/jandex)[92] **Project Licenses: **[Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
                  - net.java.dev.jna:jna-platform:jar:5.18.1 (runtime) ** 
                    
                    | Java Native Access Platform |
                    | --- |
                    | **Description: **Java Native Access Platform **URL: **[https://github.com/java-native-access/jna](https://github.com/java-native-access/jna)[83] **Project Licenses: **[LGPL-2.1-or-later](https://www.gnu.org/licenses/old-licenses/lgpl-2.1)[84], [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
                  - com.univocity:univocity-parsers:jar:2.9.1 (runtime) ** 
                    
                    | univocity-parsers |
                    | --- |
                    | **Description: **univocity's open source parsers for processing different text formats using a consistent API **URL: **[http://github.com/univocity/univocity-parsers](http://github.com/univocity/univocity-parsers)[78] **Project Licenses: **[Apache 2](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
                  - io.github.classgraph:classgraph:jar:4.8.184 (runtime) ** 
                    
                    | ClassGraph |
                    | --- |
                    | **Description: **The uber-fast, ultra-lightweight classpath and module scanner for JVM languages. **URL: **[https://github.com/classgraph/classgraph](https://github.com/classgraph/classgraph)[79] **Project Licenses: **[The MIT License (MIT)](http://opensource.org/licenses/MIT)[19] |
                  - io.moderne:jsonrpc:jar:1.0.5 (runtime) ** 
                    
                    | jsonrpc |
                    | --- |
                    | **Description: **JSON-RPC 2.0 client and server library **URL: **[https://github.com/moderneinc/jsonrpc](https://github.com/moderneinc/jsonrpc)[81] **Project Licenses: **[The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
                  - org.objenesis:objenesis:jar:3.5 (runtime) ** 
                    
                    | Objenesis |
                    | --- |
                    | **Description: **A library for instantiating Java objects **URL: **[https://objenesis.org/objenesis](https://objenesis.org/objenesis)[94] **Project Licenses: **[Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
          - org.jetbrains:annotations:jar:26.1.0 (compile) ** 
            
            | JetBrains Java Annotations |
            | --- |
            | **Description: **A set of annotations used for code inspection support and code documentation. **URL: **[https://github.com/JetBrains/java-annotations](https://github.com/JetBrains/java-annotations)[61] **Project Licenses: **[The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
          - com.fasterxml.jackson.core:jackson-annotations:jar:2.21 (compile) ** 
            
            | Jackson-annotations |
            | --- |
            | **Description: **Core annotations used for value types, used by Jackson data binding package. **URL: **[https://github.com/FasterXML/jackson](https://github.com/FasterXML/jackson)[24] **Project Licenses: **[The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
          - org.antlr:antlr4-runtime:jar:4.13.2 (runtime) ** 
            
            | ANTLR 4 Runtime |
            | --- |
            | **Description: **The ANTLR 4 Runtime **URL: **[https://www.antlr.org/antlr4-runtime/](https://www.antlr.org/antlr4-runtime/)[85] **Project Licenses: **[BSD-3-Clause](https://www.antlr.org/license.html)[86] |
          - com.github.ben-manes.caffeine:caffeine:jar:2.9.3 (runtime) ** 
            
            | Caffeine cache |
            | --- |
            | **Description: **A high performance caching library **URL: **[https://github.com/ben-manes/caffeine](https://github.com/ben-manes/caffeine)[75] **Project Licenses: **[Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
            
                  - org.checkerframework:checker-qual:jar:3.19.0 (runtime) ** 
                    
                    | Checker Qual |
                    | --- |
                    | **Description: **checker-qual contains annotations (type qualifiers) that a programmer writes to specify Java code for type-checking by the Checker Framework. **URL: **[https://checkerframework.org](https://checkerframework.org)[89] **Project Licenses: **[The MIT License](http://opensource.org/licenses/MIT)[19] |
                  - com.google.errorprone:error_prone_annotations:jar:2.10.0 (runtime) ** 
                    
                    | error-prone annotations |
                    | --- |
                    | **Description: **Error Prone is a static analysis tool for Java that catches common programming mistakes at compile-time. **URL: **[https://errorprone.info/error_prone_annotations](https://errorprone.info/error_prone_annotations)[76] **Project Licenses: **[Apache 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
          - io.micrometer:micrometer-core:jar:1.9.17 (runtime) ** 
            
            | micrometer-core |
            | --- |
            | **Description: **Core module of Micrometer containing instrumentation API and implementation **URL: **[https://github.com/micrometer-metrics/micrometer](https://github.com/micrometer-metrics/micrometer)[80] **Project Licenses: **[The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
            
                  - org.hdrhistogram:HdrHistogram:jar:2.1.12 (runtime) ** 
                    
                    | HdrHistogram |
                    | --- |
                    | **Description: **HdrHistogram supports the recording and analyzing sampled data value counts across a configurable integer value range with configurable value precision within the range. Value precision is expressed as the number of significant digits in the value recording, and provides control over value quantization behavior across the value range and the subsequent value resolution at any given level. **URL: **[http://hdrhistogram.github.io/HdrHistogram/](http://hdrhistogram.github.io/HdrHistogram/)[90] **Project Licenses: **[Public Domain, per Creative Commons CC0](http://creativecommons.org/publicdomain/zero/1.0/)[91], [BSD-2-Clause](https://opensource.org/licenses/BSD-2-Clause)[60] |
                  - org.latencyutils:LatencyUtils:jar:2.0.3 (runtime) ** 
                    
                    | LatencyUtils |
                    | --- |
                    | **Description: **LatencyUtils is a package that provides latency recording and reporting utilities. **URL: **[http://latencyutils.github.io/LatencyUtils/](http://latencyutils.github.io/LatencyUtils/)[93] **Project Licenses: **[Public Domain, per Creative Commons CC0](http://creativecommons.org/publicdomain/zero/1.0/)[91] |
          - org.apache.commons:commons-text:jar:1.15.0 (runtime) ** 
            
            | Apache Commons Text |
            | --- |
            | **Description: **Apache Commons Text is a set of utility functions and reusable components for processing and manipulating text in a Java environment. **URL: **[https://commons.apache.org/proper/commons-text](https://commons.apache.org/proper/commons-text)[88] **Project Licenses: **[Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
            
                  - org.apache.commons:commons-lang3:jar:3.20.0 (runtime) ** 
                    
                    | Apache Commons Lang |
                    | --- |
                    | **Description: **Apache Commons Lang, a package of Java utility classes for the classes that are in java.lang's hierarchy, or are considered to be so standard as to justify existence in java.lang. The code is tested using the latest revision of the JDK for supported LTS releases: 8, 11, 17, 21 and 25 currently. See https://github.com/apache/commons-lang/blob/master/.github/workflows/maven.yml Please ensure your build environment is up-to-date and kindly report any build issues. **URL: **[https://commons.apache.org/proper/commons-lang/](https://commons.apache.org/proper/commons-lang/)[87] **Project Licenses: **[Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
    - org.junit.jupiter:junit-jupiter:jar:6.0.0 (test) ** 
      
      | JUnit Jupiter (Aggregator) |
      | --- |
      | **Description: **Module "junit-jupiter" of JUnit **URL: **[https://junit.org/](https://junit.org/)[16] **Project Licenses: **[Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[17] |
      
          - org.junit.jupiter:junit-jupiter-api:jar:6.0.0 (test) ** 
            
            | JUnit Jupiter API |
            | --- |
            | **Description: **Module "junit-jupiter-api" of JUnit **URL: **[https://junit.org/](https://junit.org/)[16] **Project Licenses: **[Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[17] |
            
                  - org.opentest4j:opentest4j:jar:1.3.0 (test) ** 
                    
                    | org.opentest4j:opentest4j |
                    | --- |
                    | **Description: **Open Test Alliance for the JVM **URL: **[https://github.com/ota4j-team/opentest4j](https://github.com/ota4j-team/opentest4j)[102] **Project Licenses: **[The Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
                  - org.junit.platform:junit-platform-commons:jar:6.0.0 (test) ** 
                    
                    | JUnit Platform Commons |
                    | --- |
                    | **Description: **Module "junit-platform-commons" of JUnit **URL: **[https://junit.org/](https://junit.org/)[16] **Project Licenses: **[Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[17] |
                  - org.apiguardian:apiguardian-api:jar:1.1.2 (test) ** 
                    
                    | org.apiguardian:apiguardian-api |
                    | --- |
                    | **Description: **@API Guardian **URL: **[https://github.com/apiguardian-team/apiguardian](https://github.com/apiguardian-team/apiguardian)[101] **Project Licenses: **[The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
          - org.junit.jupiter:junit-jupiter-params:jar:6.0.0 (test) ** 
            
            | JUnit Jupiter Params |
            | --- |
            | **Description: **Module "junit-jupiter-params" of JUnit **URL: **[https://junit.org/](https://junit.org/)[16] **Project Licenses: **[Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[17] |
          - org.junit.jupiter:junit-jupiter-engine:jar:6.0.0 (test) ** 
            
            | JUnit Jupiter Engine |
            | --- |
            | **Description: **Module "junit-jupiter-engine" of JUnit **URL: **[https://junit.org/](https://junit.org/)[16] **Project Licenses: **[Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[17] |
            
                  - org.junit.platform:junit-platform-engine:jar:6.0.0 (test) ** 
                    
                    | JUnit Platform Engine API |
                    | --- |
                    | **Description: **Module "junit-platform-engine" of JUnit **URL: **[https://junit.org/](https://junit.org/)[16] **Project Licenses: **[Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[17] |
    - org.assertj:assertj-core:jar:3.27.3 (test) ** 
      
      | AssertJ Core |
      | --- |
      | **Description: **Rich and fluent assertions for testing in Java **URL: **[https://assertj.github.io/doc/#assertj-core](https://assertj.github.io/doc/#assertj-core)[15] **Project Licenses: **[Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
      
          - net.bytebuddy:byte-buddy:jar:1.15.11 (test) ** 
            
            | Byte Buddy (without dependencies) |
            | --- |
            | **Description: **Byte Buddy is a Java library for creating Java classes at run time. This artifact is a build of Byte Buddy with all ASM dependencies repackaged into its own name space. **URL: **[https://bytebuddy.net/byte-buddy](https://bytebuddy.net/byte-buddy)[99] **Project Licenses: **[Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
    - org.apache.maven:maven-di:jar:4.0.0-rc-5 (test) ** 
      
      | Maven 4 Dependency Injection |
      | --- |
      | **Description: **Provides the implementation for the Dependency Injection mechanism in Maven **URL: **[https://maven.apache.org/ref/4.0.0-rc-5/maven-impl-modules/maven-di/](https://maven.apache.org/ref/4.0.0-rc-5/maven-impl-modules/maven-di/)[14] **Project Licenses: **[Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
    - org.testcontainers:testcontainers:jar:2.0.4 (test) ** 
      
      | Testcontainers Core |
      | --- |
      | **Description: **Isolated container management for Java code testing **URL: **[https://java.testcontainers.org](https://java.testcontainers.org)[18] **Project Licenses: **[MIT](http://opensource.org/licenses/MIT)[19] |
      
          - org.slf4j:slf4j-api:jar:1.7.36 (test) ** 
            
            | SLF4J API Module |
            | --- |
            | **Description: **The slf4j API **URL: **[http://www.slf4j.org](http://www.slf4j.org)[104] **Project Licenses: **[MIT License](http://www.opensource.org/licenses/mit-license.php)[43] |
          - org.apache.commons:commons-compress:jar:1.28.0 (test) ** 
            
            | Apache Commons Compress |
            | --- |
            | **Description: **Apache Commons Compress defines an API for working with compression and archive formats. These include bzip2, gzip, pack200, LZMA, XZ, Snappy, traditional Unix Compress, DEFLATE, DEFLATE64, LZ4, Brotli, Zstandard and ar, cpio, jar, tar, zip, dump, 7z, arj. **URL: **[https://commons.apache.org/proper/commons-compress/](https://commons.apache.org/proper/commons-compress/)[100] **Project Licenses: **[Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
            
                  - commons-codec:commons-codec:jar:1.19.0 (test) ** 
                    
                    | Apache Commons Codec |
                    | --- |
                    | **Description: **The Apache Commons Codec component contains encoders and decoders for formats such as Base16, Base32, Base64, digest, and Hexadecimal. In addition to these widely used encoders and decoders, the codec package also maintains a collection of phonetic encoding utilities. **URL: **[https://commons.apache.org/proper/commons-codec/](https://commons.apache.org/proper/commons-codec/)[97] **Project Licenses: **[Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
                  - commons-io:commons-io:jar:2.20.0 (test) ** 
                    
                    | Apache Commons IO |
                    | --- |
                    | **Description: **The Apache Commons IO library contains utility classes, stream implementations, file filters, file comparators, endian transformation classes, and much more. **URL: **[https://commons.apache.org/proper/commons-io/](https://commons.apache.org/proper/commons-io/)[98] **Project Licenses: **[Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
          - org.rnorth.duct-tape:duct-tape:jar:1.0.8 (test) ** 
            
            | Duct Tape |
            | --- |
            | **Description: **General purpose resilience utilities for Java 8 (circuit breakers, timeouts, rate limiters, and handlers for unreliable or inconsistent results) **URL: **[https://github.com/rnorth/duct-tape](https://github.com/rnorth/duct-tape)[103] **Project Licenses: **[MIT](http://opensource.org/licenses/MIT)[19] |
          - com.github.docker-java:docker-java-api:jar:3.7.1 (test) ** 
            
            | docker-java-api |
            | --- |
            | **Description: **Java API Client for Docker **URL: **[https://github.com/docker-java/docker-java](https://github.com/docker-java/docker-java)[96] **Project Licenses: **[The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
          - com.github.docker-java:docker-java-transport-zerodep:jar:3.7.1 (test) ** 
            
            | docker-java-transport-zerodep |
            | --- |
            | **Description: **Java API Client for Docker **URL: **[https://github.com/docker-java/docker-java](https://github.com/docker-java/docker-java)[96] **Project Licenses: **[The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
            
                  - com.github.docker-java:docker-java-transport:jar:3.7.1 (test) ** 
                    
                    | docker-java-transport |
                    | --- |
                    | **Description: **Java API Client for Docker **URL: **[https://github.com/docker-java/docker-java](https://github.com/docker-java/docker-java)[96] **Project Licenses: **[The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
                  - net.java.dev.jna:jna:jar:5.18.1 (runtime) ** 
                    
                    | Java Native Access |
                    | --- |
                    | **Description: **Java Native Access **URL: **[https://github.com/java-native-access/jna](https://github.com/java-native-access/jna)[83] **Project Licenses: **[LGPL-2.1-or-later](https://www.gnu.org/licenses/old-licenses/lgpl-2.1)[84], [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
    - org.testcontainers:testcontainers-junit-jupiter:jar:2.0.4 (test) ** 
      
      | Testcontainers :: JUnit Jupiter Extension |
      | --- |
      | **Description: **Isolated container management for Java code testing **URL: **[https://java.testcontainers.org](https://java.testcontainers.org)[18] **Project Licenses: **[MIT](http://opensource.org/licenses/MIT)[19] |

# Licenses

**EPL: **Dirgra

**The Apache License, Version 2.0: **JSpecify annotations, Woodstox, org.apiguardian:apiguardian-api, org.opentest4j:opentest4j

**Apache 2.0: **error-prone annotations

**MIT License: **JCodings, Joni, SLF4J API Module, jnr-x86asm

**GPL-2.0: **JRuby Base, JRuby Lib Setup, JRuby Main Maven Artifact

**Eclipse Public License v2.0: **JUnit Jupiter (Aggregator), JUnit Jupiter API, JUnit Jupiter Engine, JUnit Jupiter Params, JUnit Platform Commons, JUnit Platform Engine API

**Apache 2: **JavaEWAH, univocity-parsers

**BSD: **JZlib

**Public Domain, per Creative Commons CC0: **HdrHistogram, LatencyUtils

**LGPL-2.1-or-later: **Java Native Access, Java Native Access Platform

**The MIT License: **Checker Qual

**Apache License, Version 2.0: **Byte Buddy (without dependencies), Caffeine cache, IKE Build Standards, IKE Maven Plugin, IKE Maven Plugin Support, IKE Workspace Model, Java Annotation Indexer, Joda-Time, Koncept AsciiDoc Extension, Objenesis, SnakeYAML

**Apache-2.0: **Apache Commons Codec, Apache Commons Compress, Apache Commons IO, Apache Commons Lang, Apache Commons Text, AssertJ Core, Java Native Access, Java Native Access Platform, Maven 4 API :: Core, Maven 4 API :: Dependency Injection, Maven 4 API :: Meta annotations, Maven 4 API :: Model, Maven 4 API :: Plugin, Maven 4 API :: Repository Metadata, Maven 4 API :: Settings, Maven 4 API :: Toolchain, Maven 4 API :: XML, Maven 4 Dependency Injection, Maven 4 Model Support, Maven 4 XML Implementation

**GNU Lesser General Public License Version 2.1: **jnr-posix

**GNU Lesser General Public License version 3: **jffi

**LGPL-2.1: **JRuby Base, JRuby Lib Setup, JRuby Main Maven Artifact

**The BSD 2-Clause License: **Stax2 API

**BSD-3-Clause: **ANTLR 4 Runtime, asm, asm-analysis, asm-commons, asm-tree, asm-util

**BSD-2-Clause: **HdrHistogram, crac

**The MIT License (MIT): **ClassGraph

**MIT: **Duct Tape, Testcontainers :: JUnit Jupiter Extension, Testcontainers Core

**Eclipse Public License - v 2.0: **jnr-posix

**EPL-2.0: **JRuby Base, JRuby Lib Setup, JRuby Main Maven Artifact

**The Apache Software License, Version 2.0: **Gizmo, Jackson dataformat: Smile, Jackson datatype: JSR310, Jackson-annotations, Jackson-core, Jackson-module-parameter-names, JetBrains Java Annotations, asciidoctorj, asciidoctorj-api, asciidoctorj-diagram, asciidoctorj-pdf, backport9, docker-java-api, docker-java-transport, docker-java-transport-zerodep, invokebinder, jackson-databind, java-object-diff, jffi, jgit, jitescript, jnr-a64asm, jnr-constants, jnr-enxio, jnr-ffi, jnr-netdb, jnr-unixsocket, jsonrpc, micrometer-core, options, rewrite-core, rewrite-xml

**GNU General Public License Version 2: **jnr-posix

# Dependency File Details

| Total | Size | Entries | Classes | Packages | Java Version | Debug Information |
| --- | --- | --- | --- | --- | --- | --- |
| jackson-annotations-2.21.jar | 82.1 kB | 89 | 76 | 2 | 1.8 | Yes |
| jackson-core-2.21.2.jar | 595.5 kB | 286 | - | - | - | - |
|    • Root | - | 250 | 213 | 16 | 1.8 | Yes |
|    • Versioned | - | 2 | 1 | 1 | 9 | No |
|    • Versioned | - | 12 | 3 | 1 | 11 | Yes |
|    • Versioned | - | 11 | 2 | 1 | 17 | Yes |
|    • Versioned | - | 11 | 2 | 1 | 21 | Yes |
| jackson-databind-2.21.2.jar | 1.7 MB | 852 | - | - | - | - |
|    • Root | - | 850 | 812 | 23 | 1.8 | Yes |
|    • Versioned | - | 2 | 1 | 1 | 9 | No |
| jackson-dataformat-smile-2.21.2.jar | 97.4 kB | 41 | - | - | - | - |
|    • Root | - | 39 | 20 | 3 | 1.8 | Yes |
|    • Versioned | - | 2 | 1 | 1 | 9 | No |
| jackson-datatype-jsr310-2.21.2.jar | 136.6 kB | 88 | - | - | - | - |
|    • Root | - | 86 | 64 | 6 | 1.8 | Yes |
|    • Versioned | - | 2 | 1 | 1 | 9 | No |
| jackson-module-parameter-names-2.21.2.jar | 10.2 kB | 23 | - | - | - | - |
|    • Root | - | 21 | 4 | 1 | 1.8 | Yes |
|    • Versioned | - | 2 | 1 | 1 | 9 | No |
| woodstox-core-7.1.1.jar | 1.6 MB | 1091 | 942 | 78 | 1.8 | Yes |
| caffeine-2.9.3.jar | 912.1 kB | 703 | 692 | 4 | 1.8 | Yes |
| docker-java-api-3.7.1.jar | 500.1 kB | 409 | 394 | 5 | 1.8 | Yes |
| docker-java-transport-3.7.1.jar | 38.8 kB | 45 | 34 | 1 | 1.8 | Yes |
| docker-java-transport-zerodep-3.7.1.jar | 2.3 MB | 1475 | 1362 | 74 | 1.8 | Yes |
| jffi-1.3.14-native.jar | 1 MB | 49 | 0 | 0 | - | - |
| jffi-1.3.14.jar | 163.2 kB | 144 | 133 | 2 | 1.8 | Yes |
| jnr-a64asm-1.0.0.jar | 86.3 kB | 57 | 48 | 1 | 1.7 | Yes |
| jnr-constants-0.10.4.jar | 1.6 MB | 1063 | 1038 | 17 | 1.8 | Yes |
| jnr-enxio-0.32.19.jar | 34.6 kB | 37 | 27 | 1 | 1.8 | Yes |
| jnr-ffi-2.2.18.jar | 744.6 kB | 745 | 669 | 50 | 1.8 | Yes |
| jnr-netdb-1.2.0.jar | 63.1 kB | 55 | 46 | 1 | 1.8 | Yes |
| jnr-posix-3.1.21.jar | 289.7 kB | 256 | 245 | 3 | 1.8 | Yes |
| jnr-unixsocket-0.38.24.jar | 48.2 kB | 40 | 30 | 2 | 1.8 | Yes |
| jnr-x86asm-1.0.2.jar | 219.9 kB | 97 | 84 | 2 | 1.5 | Yes |
| error_prone_annotations-2.10.0.jar | 16 kB | 37 | 25 | 2 | 1.7 | Yes |
| JavaEWAH-1.1.13.jar | 166.9 kB | 120 | 106 | 5 | 1.8 | Yes |
| backport9-1.13.jar | 14 kB | 29 | 13 | 7 | 1.8 | Yes |
| invokebinder-1.14.jar | 53.1 kB | 34 | 23 | 3 | 1.8 | Yes |
| options-1.6.jar | 14.9 kB | 21 | 10 | 3 | 1.8 | Yes |
| univocity-parsers-2.9.1.jar | 447 kB | 299 | 273 | 16 | 1.6 | Yes |
| commons-codec-1.19.0.jar | 374.7 kB | 263 | - | - | - | - |
|    • Root | - | 262 | 115 | 7 | 1.8 | Yes |
|    • Versioned | - | 1 | 1 | 1 | 9 | No |
| commons-io-2.20.0.jar | 564 kB | 415 | - | - | - | - |
|    • Root | - | 414 | 387 | 15 | 1.8 | Yes |
|    • Versioned | - | 1 | 1 | 1 | 9 | No |
| classgraph-4.8.184.jar | 587 kB | 284 | - | - | - | - |
|    • Root | - | 282 | 253 | 13 | 1.8 | Yes |
|    • Versioned | - | 2 | 1 | 1 | 9 | No |
| micrometer-core-1.9.17.jar | 663.6 kB | 423 | 367 | 39 | 1.8 | Yes |
| jsonrpc-1.0.5.jar | 30.2 kB | 30 | 21 | 4 | 1.8 | Yes |
| gizmo-1.0.11.Final.jar | 101.3 kB | 77 | 66 | 1 | 1.8 | Yes |
| joda-time-2.14.0.jar | 639.8 kB | 770 | 248 | 7 | 1.5 | Yes |
| jitescript-0.4.1.jar | 23 kB | 20 | 9 | 2 | 1.6 | Yes |
| byte-buddy-1.15.11.jar | 8.5 MB | 5890 | - | - | - | - |
|    • Root | - | 2950 | 2897 | 38 | 1.5 | Yes |
|    • Versioned | - | 2940 | 2898 | 39 | 1.8 | Yes |
| jna-5.18.1.jar | 2 MB | 191 | 124 | 4 | 1.8 | Yes |
| jna-platform-5.18.1.jar | 1.4 MB | 1336 | 1288 | 15 | 1.8 | Yes |
| koncept-asciidoc-extension-84.jar | 27.8 kB | 26 | 11 | 1 | 25 | Yes |
| ike-build-standards-158-claude.zip | 81 kB | - | - | - | - | - |
| ike-maven-plugin-support-158.jar | 38 kB | 31 | 19 | 2 | 25 | Yes |
| ike-workspace-model-158.jar | 120.8 kB | 64 | 53 | 2 | 25 | Yes |
| antlr4-runtime-4.13.2.jar | 326.3 kB | 232 | 215 | 7 | 1.8 | Yes |
| commons-compress-1.28.0.jar | 1.1 MB | 642 | - | - | - | - |
|    • Root | - | 641 | 589 | 36 | 1.8 | Yes |
|    • Versioned | - | 1 | 1 | 1 | 9 | No |
| commons-lang3-3.20.0.jar | 713.9 kB | 454 | - | - | - | - |
|    • Root | - | 452 | 421 | 18 | 1.8 | Yes |
|    • Versioned | - | 2 | 1 | 1 | 9 | No |
| commons-text-1.15.0.jar | 264.9 kB | 191 | - | - | - | - |
|    • Root | - | 189 | 168 | 8 | 1.8 | Yes |
|    • Versioned | - | 2 | 1 | 1 | 9 | No |
| maven-api-annotations-4.0.0-rc-5.jar | 13.1 kB | 27 | 12 | 1 | 17 | Yes |
| maven-api-core-4.0.0-rc-5.jar | 218.3 kB | 257 | 237 | 7 | 17 | Yes |
| maven-api-di-4.0.0-rc-5.jar | 16.4 kB | 32 | 13 | 2 | 17 | Yes |
| maven-api-metadata-4.0.0-rc-5.jar | 41.8 kB | 45 | 30 | 1 | 17 | Yes |
| maven-api-model-4.0.0-rc-5.jar | 222.2 kB | 128 | 113 | 1 | 17 | Yes |
| maven-api-plugin-4.0.0-rc-5.jar | 82.1 kB | 77 | 60 | 2 | 17 | Yes |
| maven-api-settings-4.0.0-rc-5.jar | 84.7 kB | 67 | 52 | 1 | 17 | Yes |
| maven-api-toolchain-4.0.0-rc-5.jar | 41.5 kB | 45 | 30 | 1 | 17 | Yes |
| maven-api-xml-4.0.0-rc-5.jar | 36.5 kB | 42 | 27 | 1 | 17 | Yes |
| maven-di-4.0.0-rc-5.jar | 63.7 kB | 44 | 29 | 2 | 17 | Yes |
| maven-support-4.0.0-rc-5.jar | 299.4 kB | 81 | 55 | 6 | 17 | Yes |
| maven-xml-4.0.0-rc-5.jar | 51.8 kB | 47 | 30 | 1 | 17 | Yes |
| apiguardian-api-1.1.2.jar | 6.8 kB | 9 | 3 | 2 | 1.6 | Yes |
| asciidoctorj-3.0.1.jar | 1.9 MB | 1255 | 142 | 11 | 11 | Yes |
| asciidoctorj-api-3.0.1.jar | 60.3 kB | 91 | 82 | 6 | 11 | Yes |
| asciidoctorj-diagram-3.2.1.jar | 361.1 kB | 338 | 0 | 0 | - | - |
| asciidoctorj-pdf-2.3.23.jar | 5.3 MB | 1156 | 0 | 0 | - | - |
| assertj-core-3.27.3.jar | 1.4 MB | 881 | - | - | - | - |
|    • Root | - | 877 | 838 | 27 | 1.8 | Yes |
|    • Versioned | - | 4 | 1 | 1 | 9 | No |
| checker-qual-3.19.0.jar | 222.1 kB | 424 | 356 | 30 | 1.8 | Yes |
| stax2-api-4.2.2.jar | 195.9 kB | 146 | 125 | 12 | 1.6 | Yes |
| crac-1.5.0.jar | 13.4 kB | 24 | 14 | 3 | 1.8 | Yes |
| HdrHistogram-2.1.12.jar | 173.8 kB | 106 | 96 | 2 | 1.7 | Yes |
| jandex-2.4.2.Final.jar | 230.8 kB | 125 | 115 | 1 | 1.6 | Yes |
| annotations-26.1.0.jar | 31.1 kB | 72 | - | - | - | - |
|    • Root | - | 70 | 60 | 2 | 1.8 | Yes |
|    • Versioned | - | 2 | 1 | 1 | 9 | No |
| dirgra-0.5.jar | 17 kB | 21 | 11 | 2 | 1.8 | Yes |
| jruby-10.0.3.0.jar | 26.2 kB | 12 | 0 | 0 | - | - |
| jruby-base-10.0.3.0.jar | 9.4 MB | 6530 | 6346 | 115 | 21 | Yes |
| jruby-stdlib-10.0.3.0.jar | 19 MB | 3052 | 0 | 0 | - | - |
| jzlib-1.1.5.jar | 74.9 kB | 36 | 26 | 1 | 1.7 | Yes |
| jcodings-1.0.63.jar | 1.8 MB | 862 | 166 | 11 | 1.8 | Yes |
| joni-2.2.6.jar | 232.4 kB | 121 | 107 | 7 | 1.8 | Yes |
| jspecify-1.0.0.jar | 3.8 kB | 14 | - | - | - | - |
|    • Root | - | 10 | 4 | 1 | 1.8 | No |
|    • Versioned | - | 4 | 1 | 1 | 9 | No |
| junit-jupiter-6.0.0.jar | 6.4 kB | 5 | 1 | 1 | 17 | No |
| junit-jupiter-api-6.0.0.jar | 249.9 kB | 224 | 208 | 9 | 17 | Yes |
| junit-jupiter-engine-6.0.0.jar | 353.7 kB | 188 | 171 | 9 | 17 | Yes |
| junit-jupiter-params-6.0.0.jar | 293.7 kB | 215 | 194 | 9 | 17 | Yes |
| junit-platform-commons-6.0.0.jar | 171.1 kB | 103 | 87 | 10 | 17 | Yes |
| junit-platform-engine-6.0.0.jar | 277.6 kB | 193 | 175 | 9 | 17 | Yes |
| LatencyUtils-2.0.3.jar | 29.8 kB | 31 | 22 | 1 | 1.6 | Yes |
| objenesis-3.5.jar | 49.5 kB | 58 | 43 | 10 | 1.8 | Yes |
| rewrite-core-8.79.2.jar | 1 MB | 586 | 548 | 26 | 1.8 | Yes |
| rewrite-xml-8.79.2.jar | 437.7 kB | 244 | 221 | 11 | 1.8 | Yes |
| java-object-diff-1.0.1.jar | 163.5 kB | 172 | 152 | 15 | 1.8 | Yes |
| jgit-1.4.1.jar | 2.8 MB | 1589 | 1525 | 48 | 1.8 | Yes |
| opentest4j-1.3.0.jar | 14.3 kB | 15 | 9 | 2 | 1.6 | Yes |
| asm-9.7.1.jar | 126.1 kB | 45 | 39 | 3 | 1.5 | Yes |
| asm-analysis-9.7.1.jar | 35.1 kB | 22 | 15 | 2 | 1.5 | Yes |
| asm-commons-9.7.1.jar | 73.5 kB | 34 | 28 | 2 | 1.5 | Yes |
| asm-tree-9.7.1.jar | 51.9 kB | 45 | 39 | 2 | 1.5 | Yes |
| asm-util-9.7.1.jar | 94.5 kB | 33 | 27 | 2 | 1.5 | Yes |
| duct-tape-1.0.8.jar | 25.4 kB | 37 | 22 | 6 | 1.8 | Yes |
| slf4j-api-1.7.36.jar | 41.1 kB | 46 | 34 | 4 | 1.5 | Yes |
| testcontainers-2.0.4.jar | 17.8 MB | 12566 | 10639 | 451 | 22 | Yes |
| testcontainers-junit-jupiter-2.0.4.jar | 14.9 kB | 16 | 10 | 1 | 1.8 | Yes |
| snakeyaml-2.2.jar | 334.4 kB | 278 | - | - | - | - |
|    • Root | - | 270 | 229 | 23 | 1.7 | Yes |
|    • Versioned | - | 8 | 3 | 2 | 9 | Yes |
| 101 | 96.4 MB | 52436 | 38251 | 1462 | 25 | 93 |
| compile: 57 | compile: 53.5 MB | compile: 23096 | compile: 14940 | compile: 538 | 25 | compile: 51 |
| runtime: 19 | runtime: 8.5 MB | runtime: 5293 | runtime: 4803 | runtime: 195 | runtime: 19 |
| provided: 4 | provided: 397.8 kB | provided: 366 | provided: 310 | provided: 11 | provided: 3 |
| test: 21 | test: 34.1 MB | test: 23681 | test: 18198 | test: 718 | 22 | test: 20 |
