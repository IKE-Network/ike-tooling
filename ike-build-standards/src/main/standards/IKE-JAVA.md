# IKE Java Patterns

## Domain Context

IKE (Integrated Knowledge Environment) projects work with knowledge representation, terminology systems, and description logic. Core concepts include:

- **Concepts** — named entities in a knowledge graph (e.g., clinical findings, anatomical structures)
- **Axioms** — description logic statements relating concepts (e.g., `Pneumonia ⊑ DiseaseOfLung`)
- **Koncepts** — IKE's notation for referencing concepts in documentation via `k:ConceptName[]` inline macros

## RocksDB Lifecycle Management

RocksDB is the default embedded storage for IKE knowledge bases.

- **Always close RocksDB instances** in a try-with-resources or explicit shutdown hook. Unclosed instances corrupt WAL files.
- **Column families** are the unit of isolation. Create separate column families for different data types (concepts, axioms, descriptions).
- **Use `WriteBatch`** for multi-key atomic writes. Never write related keys individually — partial writes corrupt the knowledge graph.
- **Iterator cleanup** — always close `RocksIterator` instances. They hold native memory that the GC cannot reclaim.

```java
try (var db = RocksDB.open(options, path);
     var batch = new WriteBatch()) {
    batch.put(cfHandle, key, value);
    db.write(writeOptions, batch);
}
```

## Knowledge Graph Module Conventions

- One Maven module per bounded context (e.g., `ike-terminology`, `ike-reasoner`, `ike-coordinate`).
- JPMS module names mirror the Maven artifactId: `network.ike.terminology`.
- Public API is in the root package. Implementation classes are in `.internal` subpackages.
- Service interfaces use `ServiceLoader` (JPMS `provides`/`uses` in `module-info.java`).

## gRPC Service Patterns

- Proto files in `src/main/proto/` with package `network.ike.<module>`.
- Use `protobuf-maven-plugin` for code generation (bound to `generate-sources`).
- Service implementations extend the generated `*ImplBase` class.
- Use virtual threads for gRPC server handlers (I/O-bound by nature).
- Deadline propagation: always set deadlines on client stubs, always check `Context.current().isCancelled()` in long-running handlers.

## Koncept Extension Conventions

The `koncept-asciidoc-extension` provides:
- **InlineMacro** (`k:ConceptName[]`) — registered via SPI, works with all backends.
- **Postprocessor** (glossary generation) — registered per-execution in asciidoctor-maven-plugin config. Cannot be registered via SPI because it crashes the asciidoctorj-pdf (Prawn) backend.

When adding new AsciiDoc extensions:
- InlineMacros and BlockMacros → SPI registration (`META-INF/services/`)
- Postprocessors and TreeProcessors → per-execution registration in POM
- Test with both HTML and PDF backends — Prawn's JRuby bridge has quirks

## Compiler Visibility — The Primary Design Discipline

Before writing any code, ask: **"Is this visible to the Java compiler,
and will the compiler help me evolve it safely?"**

Java 25 with preview features gives us the richest type system Java has
ever had. Use it. The compiler is a collaborator — code that hides
structure from the compiler has opted out of Java's primary safety
mechanism.

### Enums Over Strings

Every fixed vocabulary must be an enum. String literals used as typed
values bypass exhaustiveness checking, `Find Usages`, and refactoring
safety.

```java
// WRONG — compiler can't help when vocabularies change
case "release" -> deployRelease();
case "snapshot" -> deploySnapshot();

// RIGHT — exhaustive switch, compiler enforces all cases handled
case SiteType.RELEASE -> deployRelease();
case SiteType.SNAPSHOT -> deploySnapshot();
// Compiler error if CHECKPOINT is added without handling it
```

### Pattern Matching in Switch (Java 25 Preview)

Use pattern matching for type-safe decomposition of sealed hierarchies,
records, and polymorphic data. Prefer `switch` expressions over
`if`/`instanceof` chains.

```java
// WRONG — linear instanceof chain, no exhaustiveness
if (result instanceof Success s) { ... }
else if (result instanceof Failure f) { ... }

// RIGHT — exhaustive switch expression with pattern matching
return switch (result) {
    case Success(var value) -> process(value);
    case Failure(var error) -> recover(error);
};
```

### Records for Data Carriers

Use records for all immutable data carriers. Records participate in
pattern matching and provide compiler-generated `equals`, `hashCode`,
and `toString`.

```java
// WRONG — stringly-typed tuple
new String[] { componentName, version, "merged" }

// RIGHT — typed record, usable in pattern matching
record MergeResult(String component, String version, Status status) {}
```

### Sealed Hierarchies for Closed Type Sets

When a type has a fixed set of variants, seal it. The compiler
enforces exhaustive handling in switch expressions.

```java
sealed interface BuildResult permits Success, Failure, Skipped {}
record Success(Path artifact) implements BuildResult {}
record Failure(String reason) implements BuildResult {}
record Skipped(String reason) implements BuildResult {}
```

### Methods Over Subprocess Invocations

When one component depends on another's behavior, express that
dependency as a method call — not as a string-based subprocess
invocation. Each Maven goal should be a thin wrapper around a
callable support class.

```java
// WRONG — runtime string, compiler can't verify goal exists
ReleaseSupport.exec(dir, log, mvnw, "ike:inject-breadcrumb", "-B");

// RIGHT — compile-time reference, refactoring-safe
InjectBreadcrumbSupport.inject(siteDir, log);
```

### When Strings Are Acceptable

- External protocol values (HTTP headers, JSON keys, YAML field names)
- User-facing display text (log messages, prompts)
- File paths and URLs (inherently string-based)
- External CLI arguments we don't control (git subcommands, gh flags)

Even then, prefer constants or enums when the same value appears in
more than one location.

## Logging

- SLF4J API for all production code (`org.slf4j:slf4j-api`, scope `provided`).
- `slf4j-simple` for test scope only.
- Log at appropriate levels: ERROR for unrecoverable failures, WARN for degraded behavior, INFO for lifecycle events, DEBUG for diagnostic detail.
- Use parameterized messages: `log.info("Loaded {} concepts from {}", count, path)` — never string concatenation.
