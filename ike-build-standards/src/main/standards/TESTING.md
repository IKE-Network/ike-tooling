# Testing Standards

How IKE modules are tested. The governing principle: **test behavior the way it
runs in production, not the way it is convenient to fake.** Real datastore-backed
behavior is verified against real data; only pure mechanism is verified in isolation.

## Two kinds of test, and when to use each

| Kind | Suffix | Runs against | Use for |
|------|--------|--------------|---------|
| Hermetic unit | `*Test` | nothing — no datastore, no JavaFX toolkit | pure algorithms, value-object math, the override **primitive** (`OverrideOf`), and type / wiring / sealed-hierarchy contracts |
| Integration | `*ITestFX` | the **starter dataset** in an ephemeral store, on the JavaFX thread | anything that composes real coordinates, entities, calculators, or UI gadgets |

`src/test/java` mirrors `src/main/java` package-for-package. One invariant per
`@Test`; the method name states the invariant. JUnit 5 (Jupiter).

## Coordinates and observable coordinates — integration, never mocks

Coordinate and observable-coordinate **behavior** — override pin/inherit,
cascade resolution, view-level composition (`setOverrides` / `setExceptOverrides`),
and save/restore round-trips — **MUST** be verified with integration tests against
the starter dataset.

- **Do not** mock or stub the datastore.
- **Do not** substitute hand-built records for behavior that resolves through real
  coordinates. Not having a running production store is not a reason to skip or
  fake these tests — **the starter set is the fixture.**
- Hermetic coverage is correct **only** for the pure mechanism (e.g. `OverrideOf`
  pinning/clearing/propagating over a plain parent property) and for type/wiring
  contracts (sealed `permits`, hierarchy).

The boundary: **mechanism → hermetic; composed coordinate behavior → integration.**
A view coordinate has constituent coordinates (stamp, language, logic, navigation,
edit), each with its own dimensions — exercising the *composition* requires a real
`ViewCoordinateRecord` from the store, not a constructed one.

## Canonical integration-test bootstrap

```java
@ExtendWith(JavaFXThreadExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FooITestFX {
    private static final File STARTER =
            new File("target/data", "tinkar-starter-data-reasoned-pb.zip");

    @BeforeAll
    void startStore() {
        CachingService.clearAll();
        PrimitiveData.selectControllerByName("Load Ephemeral Store");
        PrimitiveData.start();
    }

    @Test @RunOnJavaFXThread @Order(1)
    void loadStarterData() {
        new LoadEntitiesFromProtobufFile(STARTER).compute();   // guard with STARTER.exists()
    }

    @AfterAll
    void stopStore() { PrimitiveData.stop(); }
}
```

- Obtain coordinates from the loaded store — `Calculators.View.Default()` and the
  default `ViewCoordinateRecord` — never hand-built records.
- Every test that touches the store or a JavaFX node runs on the FX thread
  (`@RunOnJavaFXThread`); order data-loading first (`@Order(1)`).
- The starter zip (`tinkar-starter-data-reasoned-pb.zip`) is provisioned under
  `target/data/` by the build; reference it there.

## High-blast-radius work

Framework and coordinate code is high blast radius: write the test matrix as the
safety proof and test-gate each step. A change to override resolution, the cascade,
or a save/restore contract is not "done" until an `*ITestFX` exercises it against
the starter data.
