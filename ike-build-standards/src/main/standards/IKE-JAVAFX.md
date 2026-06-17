# IKE JavaFX Property and Binding Patterns

Conventions for reactive UI code in the Komet modules
(`framework`, `kview`, `knowledge-layout`). Target runtime is
JavaFX 21+, so the `Subscription` and `map`/`flatMap`/`when` APIs
are always available — use them.

The root distinction is **state vs. events**. Observable values and
`Property` hold *state*: persistent, queryable, always correct when
asked. `EventHandler`s react to *events* — ephemeral happenings
(a click, a key, a `Task` completing) routed through the scene graph.
Observe state with bindings/subscriptions; handle gestures with
`EventHandler`s. Never model a state change as an event, and never
poll state you could have bound.

## Decision Order

When you need a value to track another value, ask in this order:

1. **Can a binding express it?** `target.bind(source...)` — declarative,
   self-initializing, leak-free. Prefer it.
2. **Can `map`/`flatMap` derive it?** For a *computed* value, build the
   derivation into the graph (below) and bind the result.
3. **Do I need imperative side effects on change?** Use `subscribe(...)`,
   keep the returned `Subscription`, and tear it down.
4. **Is it a discrete user/lifecycle action?** Use an `EventHandler`.

Reach for a `Listener`/`Subscription` only at step 3. A copy-on-change
listener that just writes another property is the #1 anti-pattern in
this codebase — it reinvents binding badly.

```java
// WRONG — listener copies a value binding already maintains
coord.addListener((obs, ov, nv) -> label.setText(format(nv)));

// RIGHT — declarative, self-seeding, nothing to remove
label.textProperty().bind(coord.map(this::format));
```

## Prefer Subscription Over Listener (JavaFX 21)

When you genuinely need imperative reaction code, use `subscribe(...)`,
not `addListener(...)`.

- `addListener` returns `void`; a lambda or method-reference listener
  **cannot be removed** unless you store it in a field. `subscribe`
  returns a `Subscription` handle whose `unsubscribe()` replaces
  `removeListener`.
- `subscribe` is terser and composes: `Subscription.combine(...)` /
  `.and(...)` bundle many subscriptions so a whole window or cell tears
  down with one call.

There is virtually no reason to choose a raw `Listener` over a
`Subscription` on JavaFX 21+. New code uses `subscribe`.

```java
Subscription s = viewCoordinate.subscribe(this::recomputeFromCoordinate);
// later, on dispose:
s.unsubscribe();
```

### The Three `subscribe` Overloads — Pick by Intent

| Overload | Fires on subscribe? | Hands you | Use when |
|----------|--------------------|-----------|----------|
| `subscribe(Runnable)` | no | nothing | "something changed, recompute / repaint" — the `InvalidationListener` replacement; **the only `subscribe` form on `ObservableList`/`Set`/`Map`** |
| `subscribe(Consumer<T>)` | **yes**, with current value | new value | you need the value AND want the callback seeded now — removes the "init-then-listen" two-step |
| `subscribe(BiConsumer<T,T>)` | no | (old, new) | you must compare old vs. new — the `ChangeListener` replacement |

Default to `subscribe(Consumer)` for value-driven UI: it fires
immediately with the current value, so the view is seeded on wiring.
Use `subscribe(Runnable)` when you ignore the value. Use
`subscribe(BiConsumer)` only when you actually read the old value —
remember it does **not** fire on subscribe, so it is wrong for
first-paint seeding.

```java
coord.subscribe(this::repaint);                       // don't care about value
coord.subscribe(c -> render(c));                      // new value, eager seed
coord.subscribe((oldC, newC) -> animate(oldC, newC)); // need both, no seed
```

## InvalidationListener vs. ChangeListener

Default to invalidation (`subscribe(Runnable)` / `InvalidationListener`)
for "this is now stale, recompute" triggers. Reserve change semantics
(`subscribe(BiConsumer)` / `ChangeListener`) for logic that branches on
the old or new value.

- "Invalid" is lazy: an `Observable` goes invalid when its value *might*
  have changed and recomputes only on `getValue()`. It cannot become
  "more invalid," so you will not get redundant fires.
- `ChangeListener` eagerly recomputes the value to compare old vs. new.
  If you never read the old/new pair, it is overkill — and registering
  **both** an `InvalidationListener` and a `ChangeListener` on the same
  source doubles the callbacks for every change.

```java
p.addListener((obs, ov, nv) -> render(nv)); // need the value
p.subscribe(this::markDirty);               // just "it went stale"
```

## Derive Values with map / flatMap (JavaFX 19+)

Compute derived view values declaratively instead of writing a listener
that recomputes and stores into another property.

- `map(fn).orElse(default)` — derive one observable from another;
  null-short-circuits (no NPE), `orElse` supplies a concrete fallback.
- `flatMap(fn)` — observe a property reached *through* another changing
  property; it re-subscribes to the inner observable as the outer one
  changes. This replaces the reflection-based `Bindings.select()`.
- `when(condition)` — gate observation; the derived value stops updating
  while the condition is false (a null condition counts as false).

```java
// follow nested observables with no nested listeners, no NPE
ObservableValue<Boolean> showing = node.sceneProperty()
        .flatMap(Scene::windowProperty)
        .flatMap(Window::showingProperty)
        .orElse(false);

// suspend expensive recomputation while a pane is inactive
boundCoord.bind(sourceCoord.when(paneActiveProperty));
```

`map`/`flatMap`/`when` return an `ObservableValue`, **not** a `Property`.
The result is read-only: use it on the right side of a unidirectional
`bind()` only. A control that must write a value *back* needs a real
`Property` and `bindBidirectional` — you cannot bind-bidirectional a
mapped value.

## Sync Lists with bindContent, Not Manual Copy

To keep one `ObservableList` mirroring another, use the platform — do
not hand-roll copy-on-change.

- `Bindings.bindContent(target, source)` — keeps `target`'s contents
  equal to `source` (one-way).
- `Bindings.bindContentBidirectional(a, b)` — keeps two lists mutually
  in sync.

```java
// WRONG — manual copy reruns on every change, easy to desync
source.addListener((ListChangeListener<T>) c -> {
    target.setAll(source);
});

// RIGHT
Bindings.bindContent(target, source);
```

Two cases where you must NOT bind and the manual path is correct:
(1) a list property that *serializes* / snapshots independently of its
source (it is a copyable value, not a bound view); (2) the custom
override properties (`ListPropertyWithOverride` etc.), which throw
`UnsupportedOperationException` for `bindContent`/`bindContentBidirectional`
because they implement copy-on-write override semantics. In those cases,
document the reason and, for two-way self-pushing wiring, see the
reentrancy note below.

## Bean Naming and Property Fields

Expose every observable value with the canonical three-member bean idiom.

```java
private final ObjectProperty<ViewCoordinate> coord =
        new SimpleObjectProperty<>(this, "coord");
public ObjectProperty<ViewCoordinate> coordProperty() { return coord; }
public ViewCoordinate getCoord()        { return coord.get(); }
public void setCoord(ViewCoordinate v)  { coord.set(v); }
```

- `xxxProperty()` returns the **property** (so callers can bind/observe).
  `getXxx()`/`setXxx()` are for imperative one-shot access.
- Declare property fields `final` and instantiate **once**. Never
  reassign the field — listeners and bindings attach to the *instance*,
  so swapping it silently orphans every subscriber. `final` makes that a
  compile error.
- Type the field as the abstract interface (`ObjectProperty<T>`,
  `StringProperty`) but instantiate the `Simple*` concrete class. Pass
  `(this, "name")` so `getBean()`/`getName()` are populated for
  debugging, CSS, and tooling.
- A method named `*Property()` is expected to return a bindable
  `ObservableValue`. Do not name a plain value accessor `*Property()`.
- To hand out a read-only property, back it with a `ReadOnly*Wrapper`
  and expose only `getReadOnlyProperty()`; never leak the writable
  `Simple*Property`.
- Numeric quirk: `IntegerProperty` is `Property<Number>`, not
  `Property<Integer>`. Bridge with `asObject()`/`asString()`; call
  `get()` (not `getValue()`) on numeric observables to avoid boxing.
- Never `set()` a property after `bind()`ing it — it throws at runtime.
  `bind()` takes ownership; `unbind()` first to regain imperative
  control. `bindBidirectional` is the only form where both ends stay
  settable.

## Always Retain the Subscription and Tear It Down

A `Subscription`'s entire advantage over `addListener` is the disposal
handle. A dangling listener pins both the source `Observable` and the
enclosing object against GC and fires stale callbacks on disposed UI —
the classic leak in reused cells and closed inner windows.

- Accumulate a node/window/cell's subscriptions into **one** compound
  `Subscription` and `unsubscribe()` it once on `dispose()` / window
  close / `updateItem(empty)`.
- Always unsubscribe observers of long-lived **shared** properties
  (e.g. view-coordinate properties) when the short-lived UI that
  observes them goes away. The shared property outlives the UI; a missed
  teardown leaks every closed window.
- If you must use a raw `Listener` (only on a pre-21 path), store the
  method reference in a `final` field and remove the **same instance**.
  A re-created method reference cannot be removed.

```java
Subscription subs = Subscription.EMPTY;
subs = subs.and(coord.subscribe(this::repaint));
subs = subs.and(showing.subscribe(this::onShowingChanged));
// on window close / dispose:
subs.unsubscribe();
```

## Reentrancy Guards Are a Smell — Bind If You Can

A pair of boolean guard flags (`fromView`/`fromFilter`,
`settingUp`, ...) threaded through both directions of a sync is the
unmistakable signature of two listeners writing into each other's
source — a loop that bidirectional binding normally removes. Prefer a
binding. When binding is genuinely unavailable (e.g. the override
properties above), then:

- Wrap **every** guard toggle in `try { ... } finally { flag = false; }`.
  A throwing setter that strands the flag `true` silently deadens all
  further propagation in that direction.
- Document the invariant at the guard's declaration.
- Track "make this bindable" as a real issue rather than letting the
  guard pair metastasize.
