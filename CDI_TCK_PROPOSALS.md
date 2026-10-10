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
  proves the enhanced bean created by discovery follows the same rules. Add an initializer control
  whose parameters are not modified: they must still become injection points, keeping any source
  qualifier members. Check the promoted bean's member metadata in `@Registration`, not only at boot,
  so phase ordering cannot hide a missing enhancement application.
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

## TCK-GAP-004 — no test with two extensions: phase order across extensions, and `@Registration` on the bean set `@Enhancement` left

- **Date**: 2026-10-10 — **Status**: DRAFT (not yet reported upstream)
- **Spec**: CDI 4.1, Build Compatible Extensions (`DISCOVERY_PHASE`, `ENHANCEMENT_PHASE`,
  `REGISTRATION_PHASE` in `Sections`). Paraphrased:
  - the phases run in a fixed order: Discovery, Enhancement, Registration, Synthesis, Validation;
  - `@Registration` is given the beans, observers and interceptors as they exist after `@Enhancement`;
  - a class that gains a bean-defining annotation in `@Enhancement` (the pattern
    `CustomStereotypeExtension` uses) is therefore a bean in `@Registration`, for every extension.
- **Existing coverage that nearly covers it**:
  - Every test in `org.jboss.cdi.tck.tests.build.compatible.extensions` deploys exactly **one**
    extension. So nothing checks that the phases run across extensions (phase N of every extension
    before phase N+1 of any), instead of one extension running all its phases before the next starts.
  - `RegistrationTest` / `RegistrationExtension` count beans of a type that already has a scope in source.
  - `CustomStereotypeTest` adds `@ApplicationScoped` to a stereotype in `@Discovery`, then checks the
    bean at run time. No `@Registration` method observes the result.
- **Gap**: two failure modes pass the whole suite:
  - an implementation that runs extension A's `@Registration` before extension B's `@Enhancement`;
  - an implementation that hands `@Registration` the bean set as discovered, before enhancements apply.
- **Proposed TCK change**: one deployment with two extensions and an unscoped class `Plain`, with no
  bean-defining annotation, added through `ScannedClasses.add` in `@Discovery`.

  ```java
  public class AddScopeExtension implements BuildCompatibleExtension {
      @Discovery
      public void discover(ScannedClasses scan) { scan.add(Plain.class.getName()); }

      @Enhancement(types = Plain.class)
      public void addScope(ClassConfig clazz) { clazz.addAnnotation(Dependent.class); }
  }

  public class ObserveRegistrationExtension implements BuildCompatibleExtension {
      static final List<String> seen = new CopyOnWriteArrayList<>();

      @Priority(1) // runs before AddScopeExtension within a phase
      @Registration(types = Plain.class)
      public void record(BeanInfo bean) { seen.add(bean.declaringClass().name()); }

      @Validation
      public void validate(Messages msg) {
          if (!seen.contains(Plain.class.getName())) {
              msg.error("@Registration must see the bean created in @Enhancement by another extension");
          }
      }
  }

  public class RegistrationAfterEnhancementTest extends AbstractTest {
      @Deployment
      public static WebArchive createTestArchive() {
          return new WebArchiveBuilder().withTestClassPackage(RegistrationAfterEnhancementTest.class)
                  .withBuildCompatibleExtension(AddScopeExtension.class)
                  .withBuildCompatibleExtension(ObserveRegistrationExtension.class)
                  .build();
      }

      @Test
      @SpecAssertion(section = REGISTRATION_PHASE, id = "<new id: registration sees enhanced beans>")
      public void registrationSeesBeanCreatedByAnotherExtensionsEnhancement() {
          assertTrue(getContextualReference(Plain.class) != null);
          assertEquals(ObserveRegistrationExtension.seen, List.of(Plain.class.getName()));
      }
  }
  ```

  Give `ObserveRegistrationExtension` the higher priority on purpose. Then a container that runs each
  extension through all its phases in turn fails as well.
- **Exposed by**: Vauban `BUG.md` → `BUG-20261008-03` (vauban#131). Local regression:
  `BceCompileTimeTest.registrationSeesTheBeansEnhancementCreated` and
  `VaubanBceScopeInfoTest.scopeOutsideTheIndex`.
- **Why it matters**: integration extensions are built on this pattern. Foy's `FoyWebExtension`
  scopes `@WebServlet` classes in `@Enhancement` and indexes them in `@Registration`. When the order
  breaks, the extension builds an incomplete index, and nothing fails until a request hits the
  missing servlet.

## TCK-GAP-005 — an annotation added by `@Enhancement` is never checked for its member values

- **Date**: 2026-10-10 — **Status**: DRAFT (not yet reported upstream)
- **Spec**: CDI 4.1, Build Compatible Extensions, `ENHANCEMENT_PHASE` and `ClassConfig` /
  `FieldConfig.addAnnotation(Annotation | AnnotationInfo | Class)`. An added annotation is the
  annotation the container uses from then on, members included. That covers qualifier resolution
  (§2.3.6, members are binding unless `@Nonbinding`) and the bean name (`@Named` value, §2.6).
- **Existing coverage that nearly covers it**:
  - `ChangeBeanQualifierExtension`, `ChangeInjectionPointExtension` and
    `ChangeObserverQualifierExtension` add `MyQualifier`, which has **no member**.
  - `ChangeInterceptorBindingExtension` adds `new MyBinding.Literal("foo")`, but `MyBinding.value()`
    is `@Nonbinding`, so dropping the member changes nothing.
  - `CustomInterceptorBindingExtension` adds `@Priority(1)` built with `AnnotationBuilder`. No test
    reads that value back.
  - No test adds `@Named` to a class or a producer.
- **Gap**: an implementation can keep only the annotation *type* of an added annotation and still pass:
  - an added qualifier with a binding member matches any bean of that qualifier type;
  - an added `@Named("x")` leaves the default name.
- **Proposed TCK change**: a qualifier `@Channel(String value)` with a binding member, and two beans
  `AlphaSink` and `BetaSink`, both with no qualifier in source. The extension adds
  `@Channel("alpha")` to `AlphaSink`, `@Channel("beta")` to `BetaSink`, `@Channel("beta")` to the
  plain `@Inject Sink sink` field of a `Consumer` bean, and `@Named("renamed")` to a third bean.

  ```java
  @Enhancement(types = AlphaSink.class)
  public void alpha(ClassConfig clazz) { clazz.addAnnotation(new Channel.Literal("alpha")); }

  @Enhancement(types = BetaSink.class)
  public void beta(ClassConfig clazz) {
      clazz.addAnnotation(AnnotationBuilder.of(Channel.class).value("beta").build());
  }

  @Enhancement(types = Consumer.class)
  public void consumer(ClassConfig clazz) {
      clazz.fields().stream().filter(f -> f.info().name().equals("sink"))
              .forEach(f -> f.addAnnotation(new Channel.Literal("beta")));
  }

  @Enhancement(types = Renamed.class)
  public void rename(ClassConfig clazz) { clazz.addAnnotation(NamedLiteral.of("renamed")); }
  ```

  Test: `Consumer.sink` is the `BetaSink` (no ambiguity), `select(Sink.class, new Channel.Literal("alpha"))`
  is the `AlphaSink`, and the bean `Renamed` has name `"renamed"`. Run the assertions twice: in a
  `@Registration` of a second extension (through `BeanInfo.qualifiers()` and `BeanInfo.name()`), and at run time.
- **Exposed by**: Vauban `BUG.md` → `BUG-20261008-05` (vauban#135). Local regression:
  `BceEnhancementMembersTest`. On the unfixed build it fails with
  `Unsatisfied dependency ... @Channel(value=beta)`.
- **Why it matters**: an extension that routes beans by a qualifier member (a channel name, a
  data-source name, a tenant) silently loses that routing. An extension that names beans for EL
  or for lookup by name gets the default name instead.

## TCK-GAP-006 — `SyntheticBeanBuilder.withParam` / `SyntheticObserverBuilder.withParam` overloads are mostly untested

- **Date**: 2026-10-10 — **Status**: DRAFT (not yet reported upstream)
- **Spec**: CDI 4.1, Build Compatible Extensions, `SYNTHESIS_PHASE`. `SyntheticBeanBuilder` and
  `SyntheticObserverBuilder` declare `withParam` overloads for these types:
  - `boolean`, `int`, `long`, `double`, `String`, `Class<?>`, `Enum<?>`;
  - their arrays;
  - `ClassInfo` and `ClassInfo[]`;
  - `AnnotationInfo`, `Annotation` and their arrays;
  - `InvokerInfo` and `InvokerInfo[]`.

  The `Parameters` javadoc says how each type reads back: a `ClassInfo` as `Class`, an `AnnotationInfo`
  as the annotation, an `InvokerInfo` as `Invoker`.
- **Existing coverage that nearly covers it**:
  - `SyntheticBeanExtension` / `MyPojoCreator` use `withParam(String, String)` and
    `withParam(String, AnnotationInfo)` (`"data"`, read back as `MyComplexValue`).
  - `SyntheticObserverExtension` / `MyObserver` use a `String` only.
  - No test passes a primitive, `Class`, `Enum`, array, `ClassInfo` or `InvokerInfo`, or a param to a
    synthetic observer other than `String`.
- **Gap**: an implementation that records synthetic metadata at build time and replays it at boot,
  like Quarkus ArC or Vauban, can drop every other overload. The creator then gets `null`, with no
  build-time error.
- **Proposed TCK change**: extend `SyntheticBeanExtension` with one param per overload family. Read
  each one back in `MyPojoCreator` and expose the values on the created POJO:

  ```java
  syn.addBean(MyPojo.class).type(MyPojo.class)
          .withParam("flag", true).withParam("count", 42).withParam("big", 1L << 40).withParam("ratio", 0.5)
          .withParam("type", String.class).withParam("unit", TimeUnit.SECONDS)
          .withParam("ints", new int[] { 1, 2 }).withParam("types", new Class<?>[] { String.class, Integer.class })
          .withParam("units", new TimeUnit[] { TimeUnit.SECONDS })
          .withParam("classInfo", types.of(MyService.class).asClass().declaration())
          .withParam("anns", new Annotation[] { new MyQualifier.Literal() })
          .createWith(MyPojoCreator.class);
  ```

  Assert each value and its read-back type (`Class` for `ClassInfo`, the annotation instance for
  `AnnotationInfo`). Apply the same to `SyntheticObserverExtension`, and add one `InvokerInfo`
  param read back as `Invoker` and invoked.
- **Exposed by**: Vauban `BUG.md` → `BUG-20261008-02` (vauban#130). Local regression:
  `BceRuntimeParamsTest` (boot from the written metadata) and `SyntheticParamCodecTest`.
  The TCK runner never hit the bug, because it deploys at run time and the params never leave memory.
- **Why it matters**: these overloads let a build-time extension hand a creator the classes it
  found (`Class[]`, `ClassInfo`) or the methods to call (`InvokerInfo`). Foy hit the bug with
  `Class<?>[]` and had to fall back to a comma-joined `String`.

## TCK-GAP-007 — interception of inherited business methods: `getMethod()`, non-public methods, unproxyable beans

- **Date**: 2026-10-10 — **Status**: DRAFT (not yet reported upstream)
- **Spec**:
  - CDI 4.1 §4.2 (`MEMBER_LEVEL_INHERITANCE`): a bean inherits a superclass method, with its
    method-level interceptor bindings, unless it overrides it.
  - §7.2 (`BIZ_METHOD`): every non-private, non-static method of a managed bean, inherited or
    declared, public or not, is a business method.
  - Jakarta Interceptors 2.2 §2.4 (`INVOCATIONCONTEXT`): `getMethod()` returns the method of the
    target class for which the interceptor was invoked.
  - CDI 4.1 §3.10 and §8.3 (`UNPROXYABLE`): an intercepted bean that cannot be proxied is a
    *deployment* problem, whatever made it intercepted.
- **Existing coverage that nearly covers it**:
  - `InterceptorBindingInheritanceTest` (`Herb`/`Thyme`, `Shrub`/`Rosehip`) checks that an inherited
    **public** method is intercepted, or not, but never inspects `getMethod()`.
  - `InvocationContextTest.testGetTargetMethod` (`Interceptor3`) checks `getMethod()` only for
    `SimpleBean.testGetMethod`, which the bean class declares itself.
  - `FinalClassClassLevelInterceptorTest`, `DependentBeanFinalMethodInterceptorTest` and
    `NormalScopedBeanFinal*InterceptorTest` expect a `DeploymentException`, but each bean carries its
    binding in its own source.
- **Gap**: three failures pass the whole suite:
  - a subclass-based implementation hands interceptors its generated bridge method for an inherited
    method, with the wrong declaring class, a synthetic name and no annotations, so the method-level
    `getInterceptorBindings()` is lost too;
  - it skips inherited `protected` and package-private business methods;
  - it reports a final bean bound **only** through an inherited method as a `DefinitionException`.
- **Proposed TCK change**: in `interceptors/definition/inheritance`, add a superclass `Base` with a
  `@Traced public String inheritedPublic()`, a `@Traced protected String inheritedProtected()` and
  a `@Traced String inheritedPackagePrivate()`, plus a `@Dependent Child extends Base` that declares
  none of them. The interceptor records `ctx.getMethod()`:

  ```java
  @Test
  @SpecAssertion(section = INVOCATIONCONTEXT, id = "<getMethod of an inherited method>")
  @SpecAssertion(section = BIZ_METHOD, id = "<non-public inherited business method>")
  public void testInheritedMethodsAreInterceptedWithTheirDeclaration(Child child) throws Exception {
      child.inheritedPublic();
      child.inheritedProtected();      // same package as the test
      child.inheritedPackagePrivate();
      assertEquals(TracedInterceptor.methods(), List.of(
              Base.class.getDeclaredMethod("inheritedPublic"),
              Base.class.getDeclaredMethod("inheritedProtected"),
              Base.class.getDeclaredMethod("inheritedPackagePrivate")));
  }
  ```

  Add a broken deployment beside it: a `final` `@Dependent` class whose only binding comes from a
  `@Traced` method of its superclass, with `@ShouldThrowException(DeploymentException.class)`.
- **Exposed by**: Vauban `BUG.md`:
  - `BUG-20261004-01`: `getMethod()` returned the `$$super$` bridge. Local regression:
    `InheritedInterceptedMethodTest`, `InheritedMethodModulePathTest`.
  - `BUG-20261004-03`: inherited non-public methods were skipped by the run-time subclass. Local
    regression: `InheritedInterceptedMethodTest#inheritedProtected`, `#inheritedPackagePrivate`,
    `#inheritedProtectedFromAnotherPackage`.
  - `BUG-20261004-07`: `DefinitionException` for an unproxyable bean intercepted only through an
    inherited method. Local regression: `UnproxyableInterceptedBeanTest`.
- **Why it matters**: interceptors that name things after `getMethod()` (MicroProfile Telemetry span
  names and `code.function.name`, Metrics names, Fault Tolerance configuration keys) report wrong
  names for every inherited method. Base classes with `protected` template methods are common in
  framework code, and losing their interception silently drops a transaction or a retry.

## TCK-GAP-008 — interface default methods: interception and client-proxy forwarding (spec clarification needed)

- **Date**: 2026-10-10 — **Status**: DRAFT (needs a spec clarification before a TCK change)
- **Spec**: CDI 4.1 does not mention interface default methods.
  - §4.2 gives a superclass method's bindings to a bean that does not override it, and never
    inherits *type-level* metadata from interfaces.
  - A default method the bean does not override is still a member of the bean class (JLS 8.4.8),
    like a non-overridden superclass method, and a client proxy must forward every business method
    to the contextual instance (§5.4).
  - Weld treats default methods that way: `BackedAnnotatedType` lists them, and
    `InterceptionModelInitializer` reads their method-level bindings.
- **Existing coverage that nearly covers it**: none. No fixture in `org.jboss.cdi.tck.tests` or
  `org.jboss.cdi.tck.interceptors.tests` (outside `full`) declares an interface default method on a
  bean type.
- **Gap**: two behaviours go untested:
  - a client proxy may run a default method's body on the proxy instance itself, bypassing the
    contextual instance and its interceptors;
  - an interceptor binding on a default method may be listed by `getInterceptorBindings()` while the
    interceptor never runs.
- **Proposed TCK change**:
  1. Ask the CDI expert group to state that a non-overridden default method is a business method,
     with its own method-level bindings.
  2. Then add `interface Greeter { @Marked default String greet(String w) { return "hi " + w; } }`,
     an `@ApplicationScoped @Traced GreeterBean implements Greeter` that does not override `greet`, and
     interceptors for `@Marked` and `@Traced`. Assert that `greeter.greet("x")` through the client
     proxy runs both interceptors, and that the call reaches the contextual instance (it records
     `this`). Add a control in which the bean overrides `greet` without `@Marked`: only `@Traced` runs.
- **Exposed by**: Vauban `BUG.md`:
  - `BUG-20261004-02`: the client proxy did not forward the default method. Local regression:
    `DefaultMethodInterceptionTest#defaultMethodThroughTheClientProxy`.
  - `BUG-20261004-04`: the binding on the default method was ignored. Local regression:
    `DefaultMethodInterceptionTest#defaultMethodBindingApplies`, `#beanBoundOnlyByADefaultMethod`.
- **Why it matters**: APIs increasingly ship behaviour as default methods (repositories, MicroProfile
  Rest Client interfaces, handler interfaces). Two certified implementations can disagree on whether
  a call is intercepted, or even reaches the right instance.

## TCK-GAP-009 — SE default discovery is never checked against `bean-discovery-mode`

- **Date**: 2026-10-10 — **Status**: DRAFT (not yet reported upstream; group `se`)
- **Spec**: CDI 4.1 §12.1 (`BEAN_ARCHIVE`) and §15.1 (`SE_BOOTSTRAP`).
  - With no class or package added, `SeContainerInitializer.initialize()` discovers the bean
    archives of the class loader.
  - Each archive's `bean-discovery-mode` applies: `annotated` (also an empty `beans.xml`, since CDI
    4.0) keeps only classes with a bean-defining annotation, and `none` contributes no bean.
- **Existing coverage that nearly covers it**:
  - The deployment path is covered: `EmptyBeansXmlDiscoveryTest` (Lite) asserts that
    `SomeUnannotatedBean` is not a bean, and `full/deployment/discovery/BeanDiscoveryTest` (groups
    `cdi-full` and `integration`) has `EchoNotABean` and `JulietNotABean`.
  - The `se` group only checks positives. `ContextSETest`, `CustomRequestContextSETest`,
    `BootstrapSEContainerTest` and `CustomCDIProviderTest` all use an empty `beans.xml`, but every
    class in them is annotated or added explicitly. `TrimmedBeanArchiveSETest` covers `<trim/>`, and
    `ImplicitBeanArchiveSETest` covers an archive without `beans.xml`. No SE test places an
    unannotated class in an `annotated` or empty archive, or uses `bean-discovery-mode="none"`.
- **Gap**: an SE bootstrap that treats every `beans.xml` archive as `all` passes the `se` group. The
  deployment-path tests do not exercise the class-loader scan.
- **Proposed TCK change**: in `org.jboss.cdi.tck.tests.se.discovery`, add a test with three
  ShrinkWrap `JavaArchive`s on the SE class path:
  - an empty `beans.xml` with `Annotated` (`@Dependent`) and `Unannotated`;
  - `bean-discovery-mode="annotated"` with the same pair, in another package;
  - `bean-discovery-mode="none"` with an `@ApplicationScoped Skipped`.

  ```java
  @Test(groups = SE)
  @SpecAssertion(section = BEAN_ARCHIVE, id = "<annotated / none modes in SE>")
  @SpecAssertion(section = SE_BOOTSTRAP, id = "<default discovery>")
  public void testDefaultDiscoveryHonoursBeanDiscoveryMode() {
      try (SeContainer container = SeContainerInitializer.newInstance().initialize()) {
          assertTrue(container.select(Annotated.class).isResolvable());
          assertFalse(container.select(Unannotated.class).isResolvable());
          assertFalse(container.select(Skipped.class).isResolvable());
      }
  }
  ```
- **Exposed by**: Vauban `BUG.md` → `BUG-20261010-01` (vauban#141): every `beans.xml` was read as
  `all`. Local regression: `BeanArchiveDiscoveryModeTest` (7 cases, directory and jar). Vauban does
  not run the `se` group, but the group would not have caught it either.
- **Why it matters**: libraries ship `beans.xml` (`annotated`) so that any container discovers their
  beans. A container that reads it as `all` turns every helper class of the library into a bean,
  which can cause ambiguous or unsatisfied dependencies in applications that never asked for those
  classes.

---

## Not TCK gaps: bugs the TCK covers on a path Vauban's runner does not exercise

The bugs below passed the TCK because `vauban-tck-runner` deploys at run time, from a ShrinkWrap
archive. It never compiles the archive with `vauban-processor` or rewrites it with `vauban:generate`,
and it excludes the groups `integration,javaee-full,se,cdi-full`. They are Vauban-specific, not spec
gaps. What they suggest is a second runner mode that builds each test archive through the processor.

- `BUG-20261008-04`: an application stereotype was not bean-defining **at build time**. The processor
  indexed a fixed list of annotations. Run-time stereotype discovery is covered by
  `StereotypeDefinitionTest` and `CustomStereotypeTest`, and by `full/deployment/discovery/BeanDiscoveryTest`
  (`cdi-full`, `integration`).
- `BUG-20261009-02`: the processor's generated provider could not write a public field that only a
  run-time extension enhanced. This is code generation, not container behaviour.
- `BUG-20261009-03`: the frozen enhancement patch dropped member additions at boot. This is
  Vauban's build-time patch format; the run-time behaviour is the subject of TCK-GAP-002 and
  TCK-GAP-003.
