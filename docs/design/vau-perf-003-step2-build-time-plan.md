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
