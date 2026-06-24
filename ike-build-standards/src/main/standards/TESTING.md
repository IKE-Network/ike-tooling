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

## Plugins outside the komet reactor — classpath integration tests

A module parented directly on `ike-parent` (a standalone plugin such as
`komet-claude-plugin` or `complex-clause-plugin`, **not** a komet-reactor module) runs its
tests on the **classpath** (`useModulePath=false`), not the module path. The tinkar
datastore providers declare their services only through `module-info provides`, with no
`META-INF/services` entries, so a classpath `ServiceLoader` cannot discover them and the
store fails to start (`No controller found with name: "Load Ephemeral Store"`,
`No PathService found`).

Such a module **MUST** declare the providers the legacy way — one
`META-INF/services/<service-interface>` file per service under `src/test/resources/`:

- `dev.ikm.tinkar.common.service.DataServiceController`
- `dev.ikm.tinkar.common.service.ServiceLifecycle`
- `dev.ikm.tinkar.common.service.ExecutorController`
- `dev.ikm.tinkar.coordinate.PathService`

Copy the implementation lines from `complex-clause-plugin/src/test/resources/META-INF/services/`
— the impl classes are version-sensitive, so take them from a sibling plugin rather than
freezing them here.

Two rules go with this:

- **Do not register `CachingService`, and do not call `CachingService.clearAll()` in
  setup.** `clearAll()` drives `ExecutorProvider.reset()`, which looks up its *concrete*
  `Controller` via `ServiceLoader` — a type no module provides — and throws. Each `*IT`
  runs in its own fork (`reuseForks=false`), so no cross-class cache reset is needed.
- Name these tests `*IT` and bind `maven-failsafe-plugin` (these plugins have no JavaFX
  `*ITestFX` toolkit wiring); provision the starter zip under `target/data/` as above.

Komet-reactor modules (framework, kview, …) need none of this: they run `*ITestFX` on the
**module path**, where `module-info provides` is honored.

## High-blast-radius work

Framework and coordinate code is high blast radius: write the test matrix as the
safety proof and test-gate each step. A change to override resolution, the cascade,
or a save/restore contract is not "done" until an `*ITestFX` exercises it against
the starter data.
