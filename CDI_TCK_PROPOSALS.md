# CDI TCK — coverage gaps found while implementing Vauban

> Purpose: collect, with evidence, the places where the Jakarta CDI TCK (which bundles the
> Jakarta Interceptors TCK) lets a spec violation pass, so they can be reported upstream to the
> CDI expert group / `jakartaee/cdi-tck`. Each entry documents the normative text, the existing
> TCK fixture that *almost* covers it, why it does not, the proposed assertion, and the Vauban
> bug that exposed the gap (so the fix can be validated locally before submission).
>
> Reference TCK: `jakarta.enterprise:cdi-tck-core-impl:4.1.0` (CDI 4.1 / Interceptors 2.2).
> Vauban runs it through `vauban-tck-runner` (profile `tck`, excluded groups
> `integration,javaee-full,se,cdi-full`). All tests cited below are executed and pass.

---

## TCK-GAP-001 — No negative assertion that `@AroundInvoke` is *not* applied to lifecycle callbacks

- **Date**: 2026-09-11 — **Status**: DRAFT (not yet reported upstream)
- **Spec**: CDI 4.1 §7.2 *Container invocations and interception* (normative):
  > Invocation of lifecycle callbacks by the container are not business method invocations, but
  > are intercepted by interceptors for lifecycle callbacks. […] A method invocation passes through
  > method interceptors if, and only if, it is a business method invocation.
  Corollary in Jakarta Interceptors 2.2 §2.2 / §5: `@AroundInvoke` methods are invoked for business
  method invocations only; lifecycle callbacks are interposed by `@PostConstruct` / `@PreDestroy`
  interceptor methods, for which `InvocationContext.getMethod()` returns `null`.
- **Exposed by**: Vauban `BUG.md` → `VAU-INT-006` (`@AroundInvoke` fires on the target bean's
  `@PostConstruct` / `@PreDestroy`; every cited TCK test is green on that build).

### What the TCK currently checks

1. `org.jboss.cdi.tck.interceptors.tests.contract.lifecycleCallback.LifecycleCallbackInterceptorTest`
   The fixture is *exactly* the failing configuration: `Goat` (also `Hen`, `Cow`) carries a
   class-level binding `@AnimalBinding` and declares `@PostConstruct public void postConstruct()`
   and `@PreDestroy public void preDestroy()`; `AnimalInterceptor` declares `@PostConstruct`,
   `@PreDestroy` **and** `@AroundInvoke`. But its `@AroundInvoke` is:
   ```java
   @AroundInvoke
   public Object intercept(InvocationContext ctx) throws Exception {
       if (ctx.getMethod().getName().equals("echo")) {
           return ctx.proceed() + ctx.getParameters()[0].toString();
       } else {
           return ctx.proceed();
       }
   }
   ```
   It records nothing, so an implementation that wraps `postConstruct()` in the business chain
   simply calls `proceed()` and the test only sees that the callback ran
   (`assertTrue(Goat.isPostConstructInterceptorCalled())`,
   `assertTrue(AnimalInterceptor.isPostConstructInterceptorCalled(GOAT))`). Positive path only.

2. `org.jboss.cdi.tck.tests.interceptors.invocation.InterceptorInvocationTest`
   The only negative assertions on the business chain (`assertFalse(AlmightyInterceptor.methodIntercepted)`)
   cover **`java.lang.Object` methods** (`testObjectMethodsAreNotIntercepted`, via `toString()`) and
   **`@Inject` initializer methods** (`testInitializerMethodsNotIntercepted`). No target bean in that
   package declares its own `@PostConstruct` / `@PreDestroy`; `lifecycleCallbackIntercepted` refers
   to the *interceptor's* `@PostConstruct`, not to a callback on the bean.

3. `interceptors/tests/contract/invocationContext` and `…/aroundConstruct`
   `assertNull(ctx.getMethod())` is asserted **inside lifecycle interceptor methods** only. An
   implementation with a correct lifecycle chain but an extra business-chain wrap around the
   callback passes: the lifecycle interceptor sees `null`, the spurious `@AroundInvoke` sees the
   callback `Method` and nobody asserts on it.

Net effect: §7.2's three exclusions are covered for two of them (Object methods, initializers) and
not for the third (lifecycle callbacks). An implementation whose "is this a business method?"
filter is derived from the TCK rather than from §7.2 ends up with precisely that hole.

### Proposed TCK change

Minimal, in `LifecycleCallbackInterceptorTest` / `AnimalInterceptor` (or a new dedicated test to
keep existing assertion ids stable):

```java
// AnimalInterceptor
private static final Set<String> aroundInvokeCalledFor = new HashSet<>();

@AroundInvoke
public Object intercept(InvocationContext ctx) throws Exception {
    aroundInvokeCalledFor.add(ctx.getMethod().getName());
    ...
}
public static boolean isAroundInvokeCalledFor(String method) { return aroundInvokeCalledFor.contains(method); }
```

```java
// LifecycleCallbackInterceptorTest
@Test
@SpecAssertion(section = BIZ_METHOD, id = "<new id: lifecycle callbacks are not business method invocations>")
@SpecAssertion(section = INT_METHODS_FOR_LIFECYCLE_EVENT_CALLBACKS, id = "b")
public void testAroundInvokeNotInvokedForLifecycleCallbacks() {
    Instance<Goat> instance = getContextualReference(Instance.class, ...);   // or a @Dependent lookup + destroy()
    Goat goat = instance.get();
    goat.echo("foo");
    instance.destroy(goat);

    assertTrue(AnimalInterceptor.isAroundInvokeCalledFor("echo"));
    assertFalse(AnimalInterceptor.isAroundInvokeCalledFor("postConstruct"));
    assertFalse(AnimalInterceptor.isAroundInvokeCalledFor("preDestroy"));
}
```

Optional hardening, same spirit:
- a variant where the callback is **package-private / protected** (Vauban's failing probe used a
  package-private `void init()`), since proxy/subclass-based implementations may treat visibility
  differently from public methods;
- the same negative assertion for `@AroundConstruct` targets (no `@AroundInvoke` around the
  constructor path) and, in CDI Full, for decorators (§7.2 text says "method interceptors and
  decorators").

Audit: `BIZ_METHOD` in `tck-audit-cdi.xml` currently has ids for Object methods (`m`), initializers
(`ad`), producer/disposer/observer (`ia`,`ic`,`ie`); a new id for the lifecycle-callback sentence of
§7.2 is needed so the assertion is traceable.

### Why it matters

Class-level bindings are the common case (`@Transactional`, `@Retry`, `@Timed`, `@Logged`, …). An
implementation with this hole opens a transaction around `@PostConstruct` / `@PreDestroy`, counts
phantom `init` / `dispose` invocations in metrics, and may retry a `@PostConstruct` — none of which
Weld or ArC do, so applications ported to such an implementation silently change behaviour while
the implementation remains "TCK-certified". Cheap to test, easy to get wrong in any
subclass-generation design.

### Local validation

Before submitting upstream, apply the proposed test to a local copy of the TCK (or mirror it as a
Vauban regression test in `vauban-module-it` / `vauban-core`) and check that it (a) fails on the
build that exhibits `VAU-INT-006` and (b) passes once the `shouldIntercept` filters exclude the
lifecycle annotations. Also run it against Weld 5.1 / 6.0 SE to confirm the reference
implementation is green.

---

## TCK-GAP-002 — `@Enhancement` adding `@Inject` does not create a field or initializer injection point

- **Date**: 2026-10-09 — **Status**: DRAFT (not yet reported upstream)
- **Spec**: CDI 4.1, Build Compatible Extensions chapter 28, `@Enhancement` phase. An enhancement
  changes the annotations CDI uses for the subsequent container lifecycle; adding `@Inject` to a
  field or initializer therefore makes that member an injection point just as if the annotation
  appeared in source. The CDI 4.1 injection-point rules in §5.2 apply to the resulting member.
- **Existing coverage that nearly covers it**:
  - `ChangeInjectionPointExtension` adds a qualifier to `MyOtherService.myService`, but that field
    already has `@Inject`. It tests qualifier mutation, not changing a plain field into an injection
    point.
  - `ChangeObserverQualifierExtension` changes a parameter qualifier on an observer. Observer
    discovery is a separate path; it does not exercise an initializer method added through
    `MethodConfig.addAnnotation(@Inject)`.
- **Gap**: there is no target with an unannotated instance field or ordinary method whose only
  `@Inject` annotation is added during `@Enhancement`. An implementation can pass the existing
  qualifier tests while never adding the new field/parameters to `BeanInfo.injectionPoints()`, never
  resolving them at creation, or never invoking the enhanced initializer.
- **Proposed TCK change**: add one test with a bean defining extension which adds `@Inject` to a
  plain field and to a plain initializer method. Give the field and initializer parameter an added
  qualifier with a non-default member value; provide only a bean with that qualifier. Assert both
  members receive the expected bean, the initializer runs, and `Bean.getInjectionPoints()` (and,
  where the test uses BCE registration, `BeanInfo.injectionPoints()`) reports both members with the
  same qualifier value. Include a class whose scope is also added by the extension so the fixture
  proves the enhanced bean created by discovery follows the same rules.
- **Exposed by**: Vauban `BUG.md` → `BUG-20261009-01`. `vauban-core` tests
  `BceEnhancementTest` and `BceInjectionEnhancementTest` provide the local regression; the direct
  descriptor tests were red before the Vauban change.
- **Why it matters**: extensions commonly add injection annotations as part of integration or
  framework wiring. If this mutation is ignored, deployment validation, bean metadata and runtime
  injection disagree with the annotations the extension requested.

## TCK-GAP-003 — qualifier changes to constructor and initializer parameters are not tested as injection-point changes

- **Date**: 2026-10-09 — **Status**: DRAFT (not yet reported upstream)
- **Spec**: CDI 4.1, Build Compatible Extensions chapter 28 (`MethodConfig.parameters()` and
  `ParameterConfig`), together with §5.2.2's injection-point qualifier completion rules. A parameter
  qualifier added during enhancement participates in resolution; an explicit qualifier replaces the
  implicit `@Default`, and its annotation member values remain part of qualifier matching.
- **Existing coverage that nearly covers it**:
  - `ChangeInjectionPointExtension` tests adding a qualifier to an existing injected *field*, not
    to an injected constructor or initializer parameter.
  - `ChangeObserverQualifierExtension` tests an observer event parameter, not an ordinary
    constructor or initializer injection parameter. It does not prove parameter resolution uses the
    enhanced injection-point qualifiers.
  - `ChangeBeanQualifierTest` changes bean qualifiers and does not cover member-level parameter
    configuration.
- **Gap**: an implementation can correctly enhance field and observer qualifiers but leave
  constructor parameters or initializer parameters at their source-level qualifiers. In particular,
  a parameter that acquires an explicit qualifier may incorrectly continue requiring `@Default`.
  Overloaded initializer methods also need distinct member identity so a qualifier change applies to
  the selected overload only.
- **Proposed TCK change**: add constructor and initializer cases where the source parameter is
  unqualified and the extension adds `@Q("selected")` using `AnnotationBuilder`. Define the only
  matching bean with `@Q("selected")` and no `@Default`; assert construction and initializer
  invocation succeed, the reported injection points carry the member value and not `@Default`, and
  an overloaded non-injected method is not invoked. Keep the existing field and observer cases as
  separate controls.
- **Exposed by**: Vauban `BUG.md` → `BUG-20261009-01`; the constructor and initializer regression
  also runs through the real container in `BceInjectionEnhancementTest`.
- **Why it matters**: parameter qualifier changes are part of the BCE public contract, and a stale
  default qualifier makes a valid enhanced injection unsatisfied. Signature ambiguity can silently
  apply another overload's metadata or fall back to source annotations.

---
