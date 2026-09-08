# Mocking support for the OSGi test suites — design

**Date:** 2026-07-28
**Status:** Implemented. Phase 1 on 2026-09-08 (`GreetHealthCheckTest`); phase 2 on 2026-09-08 (`MasterAppTest`, see the phase 2 notes for two deviations).
**Scope:** Add Mockito to the Tycho test fragments, with beginner-oriented worked examples.

---

## Goal

Give this project real mocking support, and use it as a teaching artifact. The existing
36 tests all exercise objects that have no collaborators — `Greet`, `Json`, `DockLayout`,
`CatalogServiceImpl`, the spike value objects. None of them needed a test double, so the
suite currently demonstrates nothing about testing code that depends on other services,
which is most production code.

Delivered in **two phases** so the one genuine unknown (does Mockito work inside Equinox?)
is settled before any breadth is attempted.

---

## Context and constraints

Four facts established by inspection before this design was written.

**Java 8 is not a constraint here.** All three test fragments declare
`Bundle-RequiredExecutionEnvironment: JavaSE-17`. The Java 8 target applies to shipped
bundles only. Mockito 5.14.2 requires `osgi.ee JavaSE 11`, so it fits.

**All required jars ship valid OSGi manifests**, so they can be added to the target
platform with `missingManifest="error"` exactly like the existing JUnit 5 location:

| Maven artifact | Bundle-SymbolicName | Role |
|---|---|---|
| `org.mockito:mockito-core:5.14.2` | `org.mockito.mockito-core` | the mocking API |
| `org.mockito:mockito-junit-jupiter:5.14.2` | `org.mockito.junit-jupiter` | `@Mock` / `@InjectMocks` support |
| `net.bytebuddy:byte-buddy:1.18.13-jdk5` | `net.bytebuddy.byte-buddy` | generates mock classes at runtime (was 1.15.4, see phase 2 notes) |
| `net.bytebuddy:byte-buddy-agent:1.18.13-jdk5` | `net.bytebuddy.byte-buddy-agent` | attach support |
| `org.objenesis:objenesis:3.3` | `org.objenesis` | constructor-free instantiation |

`org.mockito.junit-jupiter` imports `org.junit.jupiter.api.extension;version="[5.11,6)"`,
which the platform's JUnit 5.12.2 satisfies.

**There is a real classloader risk.** `mockito-core` ships **no `DynamicImport-Package`**
header. When ByteBuddy generates a mock of `IGreet` (exported by `com.kk.pde.ds.api`,
used by `com.kk.pde.ds.imp`), the generated class must reference both that interface and
`org.mockito.internal.creation.bytebuddy.MockMethodInterceptor`. No single bundle
classloader sees both. Mockito works around this with ByteBuddy's
`MultipleParentClassLoader`, which composes loaders rather than relying on OSGi wiring —
which is why it usually works in Equinox, and why failure appears as a `NoClassDefFoundError`
at mock-creation time rather than a clean resolution error at install time. This is the
reason for the spike gate below.

**`IGreet.greet()` returns `void`.** This rules out `when(...).thenReturn(...)` and
`when(...).thenThrow(...)` on it; void methods must be stubbed with
`doThrow(...).when(mock).greet()`. Rather than a limitation, this is treated as primary
teaching material — it is one of the most common beginner stumbles — and it is why the
non-void techniques are deferred to phase 2, where `ICatalogService` provides
value-returning methods.

---

## Approach

Chosen: **target platform + `Require-Bundle`, gated on a spike.**

Considered and rejected:

- **Explicit `Import-Package` on the fragments.** More precise and more OSGi-idiomatic,
  but diverges from the `Require-Bundle` convention the fragments already use for JUnit,
  and turns setup into a hunt for transitively-needed packages one `ClassNotFoundException`
  at a time. Held in reserve as fallback.
- **A plain-Maven (non-Tycho) test module.** Running mocks in an ordinary JVM would
  eliminate the OSGi classloader risk entirely, but abandons this project's distinctive
  property that tests execute inside a live OSGi framework, and adds a module unlike
  anything else in the reactor. Last-resort fallback only.

---

## Design

### 1. Target platform

Add one `<location>` block to `com.kk.pde.ds.target/com.kk.pde.ds.target.target`
containing the five bundles above, using `includeDependencyDepth="none"` and
`missingManifest="error"` to match the surrounding style, with every bundle enumerated so
the set stays visible and reproducible. A comment records that these are test-only and
never enter the product.

### 2. Fragment manifest

`com.kk.pde.ds.imp.tests/META-INF/MANIFEST.MF` gains two `Require-Bundle` entries:

```
org.mockito.mockito-core;bundle-version="[5.14,6.0)",
org.mockito.junit-jupiter;bundle-version="[5.14,6.0)"
```

BREE stays `JavaSE-17`. `com.kk.pde.ds.imp` itself is not modified — the shipped bundle
stays Mockito-free.

### 3. Spike gate

**Hard gate. No test-writing begins until this passes.**

A throwaway test that only calls `mock(IGreet.class)` and `verify(...)`, run with
`mvn verify -pl com.kk.pde.ds.imp.tests`. Escalation on failure:

1. Add `DynamicImport-Package: *` to the test fragment.
2. Fall back to explicit `Import-Package`.
3. Fall back to a plain-Maven test module.

The spike file is deleted once it has proved the point; it is scaffolding, not a
deliverable.

**Spike result (2026-09-08):** passed on the first run with no escalation. Mockito 5 uses
the *inline* mock maker by default, which attaches the ByteBuddy agent and retransforms
the mocked type in place rather than generating a subclass, so no generated class ever
needs to see two bundles at once. The classloader risk above applies to the subclass mock
maker only. Expected noise in the test log: a JDK warning that Mockito is "self-attaching",
which future JDKs may block; if that happens, pass the agent via `-javaagent` in the
`tycho-surefire-plugin` `argLine`.

### 4. Phase 1 deliverable

One new file: `com.kk.pde.ds.imp.tests/src/test/java/com/kk/pde/ds/imp/GreetHealthCheckTest.java`.

Subject is `GreetHealthCheck`, chosen because it is the only class in an existing test
fragment's host bundle with a genuine injected collaborator (`@Reference private IGreet
greetService`), and because its `catch` branch at `GreetHealthCheck.java:54` is
**unreachable with the real `Greet`**, which never throws. That branch is the honest
answer to "why not just use the real object?" — the strongest argument for mocking a
beginner can be shown.

Teaching arc, in file order:

| Step | Teaches |
|---|---|
| Class Javadoc | Vocabulary: dummy, stub, mock, spy, fake; state vs interaction testing; why a double is needed here |
| Hand-written `ThrowingGreet` | A mock is not magic — it is a class you could write yourself |
| The same test via `mock(IGreet.class)` | Exactly what the framework replaces, and what it adds |
| Unstubbed void call | An unstubbed void method simply does nothing |
| `doThrow(...).when(greet).greet()` | The void-method gotcha, and why `when()` cannot be used |
| `verify()`, `times(1)`, `verifyNoMoreInteractions` | Interaction testing, and when it is the only option |
| `@ExtendWith(MockitoExtension.class)`, `@Mock`, `@InjectMocks` | Injecting into a **private** field with no setter, and why setter injection is more testable |

Commentary style follows the five suites annotated in `b7552a9`: class-level Javadoc
carrying the concepts, per-method Javadoc explaining why each fixture is shaped as it is.

### 5. Phase 2 (designed now, implemented separately)

`com.kk.pde.ds.spike.tests` gains tests for `MasterApp` with a mocked `ICatalogService`.
This is where the value-returning techniques land, since `ICatalogService` methods return
real values:

- `when(...).thenReturn(...)`
- `ArgumentCaptor<DockLayout>` against `setLayout()`
- `InOrder` for call-ordering
- `spy()` for partial doubles

It also covers the awkward real-world shape phase 1 avoids: `MasterApp` reaches its
collaborator from inside Swing listeners and `SwingUtilities.invokeLater`, so the tests
need headless AWT and an EDT flush before verifying. Requires the same two `Require-Bundle`
entries added to `com.kk.pde.ds.spike.tests`.

Phase 2 scope is deliberately not fixed further here; it will be re-planned once phase 1
is merged and the Equinox behaviour is known.

**Phase 2 as implemented (2026-09-08).** Reading `MasterApp` invalidated the "headless AWT
plus EDT flush" assumption: its only entry point builds `JFrame`s and reads the screen
size, both of which throw `HeadlessException`, and CI has no display. Two deviations:

1. *A visibility-only seam in the shipped bundle.* `MasterApp.buildPanels` and its `Panel`
   holder became package-private. It creates only lightweight Swing components, so it runs
   headless, and it is where every collaborator interaction lives (`listItems()` →
   `thenReturn`; the selection listener → `ArgumentCaptor` and `InOrder`; the real
   `CatalogServiceImpl` → `spy()`). `spike.tests` forces `-Djava.awt.headless=true` via the
   surefire `argLine` so desktop and CI behave identically. `start()` gained a headless
   guard, because SCR activates the real `MasterApp` inside the test framework — before
   this, `mvn verify` opened App-1 windows on a desktop during the spike tests.
2. *ByteBuddy bumped to 1.18.13-jdk5.* The `spy()` tests retransform `java.lang.Object`,
   and ByteBuddy 1.15.4 refuses class-file version 70 when the test JVM is Java 26 (a
   Homebrew Maven picks that up locally; CI is on 21). Mockito stays at 5.14.2: its
   Jupiter adapter accepts JUnit `[5.11,6)`, whereas Mockito 5.23 needs `[5.13,6)` and
   would drag a JUnit upgrade along. Mockito imports ByteBuddy as `[1.6,2.0)`, so the
   newer ByteBuddy resolves cleanly.

---

## Verification

Phase 1 is complete only when all of the following hold:

1. `mvn clean verify` reports `BUILD SUCCESS`.
2. All **36 existing tests** still pass — no regressions.
3. The new `GreetHealthCheckTest` tests pass, including the `catch`-branch test.
4. Mockito does **not** leak into the product: `distribution/target/products/**` and the
   p2 repository contain no `org.mockito.*`, `net.bytebuddy.*` or `org.objenesis` bundle.

---

## Out of scope

- A `com.kk.pde.ds.rag.tests` fragment. `DocumentIngestionServiceImpl` is the strongest
  mocking subject in the repo (four `@Reference` collaborators, one of them network-bound),
  but it needs a brand-new module. Revisit after phase 2.
- Any change to shipped bundles.
- Replacing existing tests. The 36 annotated tests stay exactly as they are; mocking is
  additive.
