---
date_published: 2026-08-21
date_modified: 2026-08-21
canonical_url: https://ike.network/ike-tooling/ike-knowledge-spi/dependencies.html
---

# Project Dependencies

## [test](#test)

The following is a list of test dependencies for this project. These dependencies are only required to compile and run unit tests for the application:

| GroupId | ArtifactId | Version | Type | Licenses |
| --- | --- | --- | --- | --- |
| org.assertj | [assertj-core](https://assertj.github.io/doc/#assertj-core)[1] | 3.27.3 | jar | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| org.junit.jupiter | [junit-jupiter](https://junit.org/)[3] | 6.0.0 | jar | [Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[4] |

## [provided](#provided)

The following is a list of provided dependencies for this project. These dependencies are required to compile the application, but should be provided by default when using the library:

| GroupId | ArtifactId | Version | Classifier | Type | Licenses |
| --- | --- | --- | --- | --- | --- |
| network.ike | [ike-base-parent](https://ike.network/ike-base-parent/)[5] | 15 | site-theme | zip | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |

# Project Transitive Dependencies

The following is a list of transitive dependencies for this project. Transitive dependencies are the dependencies of the project dependencies.

## [test](#test_2)

The following is a list of test dependencies for this project. These dependencies are only required to compile and run unit tests for the application:

| GroupId | ArtifactId | Version | Type | Licenses |
| --- | --- | --- | --- | --- |
| net.bytebuddy | [byte-buddy](https://bytebuddy.net/byte-buddy)[6] | 1.15.11 | jar | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| org.apiguardian | [apiguardian-api](https://github.com/apiguardian-team/apiguardian)[7] | 1.1.2 | jar | [The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[8] |
| org.jspecify | [jspecify](http://jspecify.org/)[9] | 1.0.0 | jar | [The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[8] |
| org.junit.jupiter | [junit-jupiter-api](https://junit.org/)[3] | 6.0.0 | jar | [Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[4] |
| org.junit.jupiter | [junit-jupiter-engine](https://junit.org/)[3] | 6.0.0 | jar | [Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[4] |
| org.junit.jupiter | [junit-jupiter-params](https://junit.org/)[3] | 6.0.0 | jar | [Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[4] |
| org.junit.platform | [junit-platform-commons](https://junit.org/)[3] | 6.0.0 | jar | [Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[4] |
| org.junit.platform | [junit-platform-engine](https://junit.org/)[3] | 6.0.0 | jar | [Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[4] |
| org.opentest4j | [opentest4j](https://github.com/ota4j-team/opentest4j)[10] | 1.3.0 | jar | [The Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |

# Project Dependency Graph

## [Dependency Tree](#dependency-tree)

- network.ike.tooling:ike-knowledge-spi:jar:252-SNAPSHOT ** 
  
  | IKE Knowledge SPI |
  | --- |
  | **Description: **Zero-dependency contract module for the IKE knowledge pipeline: ServiceLoader service interfaces (assemble, export, bindings, verify), the ViewSpec dimension vocabulary, and the properties codec that crosses the forked-JVM execution seam. **URL: **[https://ike.network/ike-tooling/ike-knowledge-spi/](https://ike.network/ike-tooling/ike-knowledge-spi/)[11] **Project Licenses: **[Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
  
    - org.junit.jupiter:junit-jupiter:jar:6.0.0 (test) ** 
      
      | JUnit Jupiter (Aggregator) |
      | --- |
      | **Description: **Module "junit-jupiter" of JUnit **URL: **[https://junit.org/](https://junit.org/)[3] **Project Licenses: **[Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[4] |
      
          - org.junit.jupiter:junit-jupiter-api:jar:6.0.0 (test) ** 
            
            | JUnit Jupiter API |
            | --- |
            | **Description: **Module "junit-jupiter-api" of JUnit **URL: **[https://junit.org/](https://junit.org/)[3] **Project Licenses: **[Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[4] |
            
                  - org.opentest4j:opentest4j:jar:1.3.0 (test) ** 
                    
                    | org.opentest4j:opentest4j |
                    | --- |
                    | **Description: **Open Test Alliance for the JVM **URL: **[https://github.com/ota4j-team/opentest4j](https://github.com/ota4j-team/opentest4j)[10] **Project Licenses: **[The Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
                  - org.junit.platform:junit-platform-commons:jar:6.0.0 (test) ** 
                    
                    | JUnit Platform Commons |
                    | --- |
                    | **Description: **Module "junit-platform-commons" of JUnit **URL: **[https://junit.org/](https://junit.org/)[3] **Project Licenses: **[Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[4] |
                  - org.apiguardian:apiguardian-api:jar:1.1.2 (test) ** 
                    
                    | org.apiguardian:apiguardian-api |
                    | --- |
                    | **Description: **@API Guardian **URL: **[https://github.com/apiguardian-team/apiguardian](https://github.com/apiguardian-team/apiguardian)[7] **Project Licenses: **[The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[8] |
                  - org.jspecify:jspecify:jar:1.0.0 (test) ** 
                    
                    | JSpecify annotations |
                    | --- |
                    | **Description: **An artifact of well-named and well-specified annotations to power static analysis checks **URL: **[http://jspecify.org/](http://jspecify.org/)[9] **Project Licenses: **[The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[8] |
          - org.junit.jupiter:junit-jupiter-params:jar:6.0.0 (test) ** 
            
            | JUnit Jupiter Params |
            | --- |
            | **Description: **Module "junit-jupiter-params" of JUnit **URL: **[https://junit.org/](https://junit.org/)[3] **Project Licenses: **[Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[4] |
          - org.junit.jupiter:junit-jupiter-engine:jar:6.0.0 (test) ** 
            
            | JUnit Jupiter Engine |
            | --- |
            | **Description: **Module "junit-jupiter-engine" of JUnit **URL: **[https://junit.org/](https://junit.org/)[3] **Project Licenses: **[Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[4] |
            
                  - org.junit.platform:junit-platform-engine:jar:6.0.0 (test) ** 
                    
                    | JUnit Platform Engine API |
                    | --- |
                    | **Description: **Module "junit-platform-engine" of JUnit **URL: **[https://junit.org/](https://junit.org/)[3] **Project Licenses: **[Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[4] |
    - org.assertj:assertj-core:jar:3.27.3 (test) ** 
      
      | AssertJ Core |
      | --- |
      | **Description: **Rich and fluent assertions for testing in Java **URL: **[https://assertj.github.io/doc/#assertj-core](https://assertj.github.io/doc/#assertj-core)[1] **Project Licenses: **[Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
      
          - net.bytebuddy:byte-buddy:jar:1.15.11 (test) ** 
            
            | Byte Buddy (without dependencies) |
            | --- |
            | **Description: **Byte Buddy is a Java library for creating Java classes at run time. This artifact is a build of Byte Buddy with all ASM dependencies repackaged into its own name space. **URL: **[https://bytebuddy.net/byte-buddy](https://bytebuddy.net/byte-buddy)[6] **Project Licenses: **[Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
    - network.ike:ike-base-parent:zip:site-theme:15 (provided) ** 
      
      | IKE Base Parent |
      | --- |
      | **Description: **Tier 0 foundation parent for the IKE Network — the apex of the parent inheritance forest, inherited by ike-tooling, ike-docs, and ike-platform. Carries shared publishing metadata, GPG signing, and Maven Central publishing configuration. **URL: **[https://ike.network/ike-base-parent/](https://ike.network/ike-base-parent/)[5] **Project Licenses: **[Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |

# Licenses

**The Apache License, Version 2.0: **JSpecify annotations, org.apiguardian:apiguardian-api, org.opentest4j:opentest4j

**Apache License, Version 2.0: **Byte Buddy (without dependencies), IKE Base Parent, IKE Knowledge SPI

**Eclipse Public License v2.0: **JUnit Jupiter (Aggregator), JUnit Jupiter API, JUnit Jupiter Engine, JUnit Jupiter Params, JUnit Platform Commons, JUnit Platform Engine API

**Apache-2.0: **AssertJ Core

# Dependency File Details

| Total | Size | Entries | Classes | Packages | Java Version | Debug Information |
| --- | --- | --- | --- | --- | --- | --- |
| byte-buddy-1.15.11.jar | 8.5 MB | 5890 | - | - | - | - |
|    • Root | - | 2950 | 2897 | 38 | 1.5 | Yes |
|    • Versioned | - | 2940 | 2898 | 39 | 1.8 | Yes |
| ike-base-parent-15-site-theme.zip | 3.4 kB | - | - | - | - | - |
| apiguardian-api-1.1.2.jar | 6.8 kB | 9 | 3 | 2 | 1.6 | Yes |
| assertj-core-3.27.3.jar | 1.4 MB | 881 | - | - | - | - |
|    • Root | - | 877 | 838 | 27 | 1.8 | Yes |
|    • Versioned | - | 4 | 1 | 1 | 9 | No |
| jspecify-1.0.0.jar | 3.8 kB | 14 | - | - | - | - |
|    • Root | - | 10 | 4 | 1 | 1.8 | No |
|    • Versioned | - | 4 | 1 | 1 | 9 | No |
| junit-jupiter-6.0.0.jar | 6.4 kB | 5 | 1 | 1 | 17 | No |
| junit-jupiter-api-6.0.0.jar | 249.9 kB | 224 | 208 | 9 | 17 | Yes |
| junit-jupiter-engine-6.0.0.jar | 353.7 kB | 188 | 171 | 9 | 17 | Yes |
| junit-jupiter-params-6.0.0.jar | 293.7 kB | 215 | 194 | 9 | 17 | Yes |
| junit-platform-commons-6.0.0.jar | 171.1 kB | 103 | 87 | 10 | 17 | Yes |
| junit-platform-engine-6.0.0.jar | 277.6 kB | 193 | 175 | 9 | 17 | Yes |
| opentest4j-1.3.0.jar | 14.3 kB | 15 | 9 | 2 | 1.6 | Yes |
| 12 | 11.2 MB | 7737 | 4587 | 117 | 17 | 9 |
| provided: 1 | provided: 3.4 kB | - | - | - | - | - |
| test: 11 | test: 11.2 MB | test: 7737 | test: 4587 | test: 117 | 17 | test: 9 |
