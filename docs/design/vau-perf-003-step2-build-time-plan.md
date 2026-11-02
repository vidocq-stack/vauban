# VAU-PERF-003 step 2 of 2 — the creation plan written at build

Status: design note. Step 1 (`perf/creation-plan-cache`) computes the plan once per class by reflection and caches
it in `ClassValue`s; this step writes it at build so the first creation reflects on nothing. Step 1's cache stays
as the fallback.

## What a creation reads by reflection today (once per class after step 1)

1. Injection order: the `@Inject` fields and initializer methods of the hierarchy, supertypes first, with the
   JSR-330 override rules (`BeanInjector.injectionOrder`).
2. Lifecycle callbacks: `@PostConstruct` / `@PreDestroy` of the hierarchy, overrides removed
   (`BeanLifecycle.collectLifecycleMethodsInHierarchy`).
3. The `@Inject` constructor, its generic parameter types, qualifiers and `@TransientReference` flags
   (`InjectionConstructorFactory.plan`).
4. The type-variable mapping of the class (`ManagedBean.buildTypeVariableMapping`).
5. Interceptor bindings per business method and the `@AroundConstruct` chain (`InterceptorManager`,
   `VaubanInvocationContext.getInterceptorBindings`).

## Proposal

The processor already sees all of this on its elements, and the index (VAU-PERF-007/-008) carries the generic types
and annotations. It writes one more SPI method on the generated `_VaubanComponents`:

```java
default CreationPlan creationPlan(String className) { return null; }   // vauban-api
```

`CreationPlan` is a plain data record in `vauban-api` (no `java.lang.reflect`): the ordered injection members as
`(declaringClass, kind FIELD|METHOD, name, erased descriptor)`, the lifecycle callbacks the same way, the constructor
descriptor with per-parameter qualifier/transient flags, and per business method `(name, descriptor) -> binding
names`. The generated code is a `switch` on the class name returning constants, like `create`/`injectField`.

At run time `BeanInjector`/`BeanLifecycle`/`InterceptorManager` ask the provider first. The members of a plan are
executed through what the provider already generates (`injectField`, `invoke`, `create(name, args)`), so a class
whose plan and members are all covered needs no `Field`/`Method` object at all. A class without a plan — no
processor, Weld/Open Liberty, a class compiled before this version, a member the provider does not cover — goes
through the step-1 `ClassValue` cache, which computes the plan by reflection as today. That path is unchanged and
keeps its tests (`CreationPlanCacheTest`); a new test boots a bean with and without the generated plan and asserts
the same injection and callback order.

## What it breaks / risks

- Override rules computed at build must match the runtime ones exactly (package-private overrides across packages,
  bridge methods). Mitigation: a shadow comparison over the CDI Lite TCK, as done for VAU-PERF-008, before wiring.
- BCE `@Enhancement` can add `@Inject` / bindings at boot. A plan is only trusted for classes no runtime enhancement
  touched (the container already knows which ones it enhanced); others use the cache.
- Binary compatibility: older providers return the `null` default, so they keep step 1's behaviour.

## Order of work

1. `CreationPlan` record + SPI default (vauban-api), and the generator in both back ends (APT source, Class-File).
2. Shadow comparison plan-vs-reflection over the TCK, then wire injection order and lifecycle.
3. Constructor plan and interceptor bindings, same way.

## What was built (option 1)

CDI hands a `java.lang.reflect.Member` to `InjectionPoint.getMember()`, and the whole injection path of Vauban works
on `Field`/`Method` objects, so a plan cannot do without them. Option 1, decided on 2026-11-02: the plan comes from
the index (`IndexCreationPlans`; a side-by-side run over the CDI Lite TCK found no difference with the reflective
computation), and each planned member is looked up directly — `getDeclaredField(name)`,
`getDeclaredMethod(name, types)` — instead of scanning every member and annotation of the hierarchy
(`IndexPlans`). The container registers at boot the index of each bean class, keyed by the `Class`, so a reloaded
class never sees a stale plan. An unregistered class, a superclass outside the index, a member that cannot be found
or an injection point an `@Enhancement` added sends the class to the step-1 reflective computation, unchanged.

## Follow-up: option 2, the member made only when asked for

What is left of the reflection is one lookup per injected member and callback, at the first creation of each class.
Removing it means the container no longer needs a `Member` to inject:

- **Injection** goes through the generated provider by name — `injectField(bean, class, field, value)` and
  `invoke(target, class, methodId, args)` already exist — with the injection point's type and qualifiers taken from
  the bean descriptor (the index), not from the member.
- **`InjectionPoint.getMember()`** returns a `Member` created on first call, by the same direct lookup, and kept.
  Only a bean that injects `InjectionPoint`, a portable or build-compatible extension, or the dev console asks for
  it; most applications never do.
- **Callbacks** are invoked through `invoke(...)` too; `Method` objects are made only for the interceptor
  `InvocationContext.getMethod()`, lazily as well.

Cost and risk: `VaubanInjectionPoint`, `BeanInjector`, `BeanLifecycle`, `ManagedBean` and `CodegenCoverage` take
`Member`s today and would take a member *description* instead; the fallback (no provider, Weld/Open Liberty, a class
the provider does not cover) keeps materializing the member up front, exactly as now. It is a larger refactor than
everything in this note so far, for a one-time cost per class; worth it mainly for start-up time on large
applications and for AOT images, where every reflective lookup must be declared.
