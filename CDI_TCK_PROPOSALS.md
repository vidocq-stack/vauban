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
