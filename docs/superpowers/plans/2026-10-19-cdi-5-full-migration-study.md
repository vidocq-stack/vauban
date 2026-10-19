# Vauban migration to CDI 5 Lite and Full

Date: 19 October 2026. Code baseline: `7384ac10b19bd1fa1f89b557512c49210e44a9ee`.

**Recommended sequence: CDI 4.1 Lite → CDI 5.0 Lite → CDI 5.0 Full.** CDI 5.0 is final, with API and TCK artifacts at `5.0.0`; its release ballot concluded on 11 September 2026. CDI Full still exists in version 5, explicitly specified in Part I.B. There is therefore no specification reason to stop Full at 4.1. [CDI 5 release](https://jakarta.ee/specifications/cdi/5.0/), [CDI 5 Full](https://jakarta.ee/specifications/cdi/5.0/jakarta-cdi-spec-5.0.html#part_1b).

The Lite upgrade is feasible on Vauban's existing architecture. Full requires substantial deployment, extension and lifecycle work. The proposed budget is **25–45 engineer-days for Lite 5**, followed by **65–120 additional engineer-days for Full core and Java SE**, with EE integration estimated separately. These are planning ranges, not measured delivery commitments; re-estimate after the first TCK baseline and extension prototype.

## Choosing the target

| Route | Assessment |
| --- | --- |
| 4.1 Lite → 5.0 Lite → 5.0 Full | Recommended. One specification generation, reusable Lite milestone, then Full capabilities. |
| 4.1 Lite → 4.1 Full → 5.0 Full | Technically possible, but delays the requested upgrade and adds a second conformance transition. Useful only for a concrete 4.1 consumer requirement. |
| Maintain 5.0 Lite and 4.1 Full as separate products | Possible with separate distributions and validation pipelines. Highest maintenance cost; no benefit established by this audit. |

A Full implementation includes Lite. The long-term product can therefore offer a lightweight configuration and an optional Full implementation using one CDI 5 API and shared engine. A maintained 4.1 release line can serve existing consumers independently. Do not load the 4.1 and 5.0 API JARs into the same application: their Java packages overlap, even though the Maven coordinates differ.

“CDI Full” describes the CDI feature set. It does not by itself mean a Jakarta EE Platform implementation. Set the first Full target to the applicable standalone core and Java SE contracts, then qualify web, EL and transaction integrations against their respective requirements. The tagged TCK guide distinguishes core/SE execution from the separate CDI EE TCK. [TCK 5 execution guide](https://github.com/jakartaee/cdi-tck/blob/5.0.0/doc/reference/src/main/asciidoc/executing.asciidoc).

## Current implementation and evidence

The checkout targets CDI `4.1.0` in `pom.xml:85` and TCK `4.1.0` in `vauban-tck-runner/pom.xml:18`. Vauban already uses Java 25; CDI 5's Java 17 minimum does not require changing that baseline.

The local `vauban-tck-runner/target/surefire-reports/TestSuite.txt`, last modified on 1 October 2026, records **774 tests, zero failures, errors or skips**. This is historical evidence for the configured selection, not a new execution against the audited revision. The runner excludes `integration,javaee-full,se,cdi-full` at line 163. Its success does not establish Java SE coverage, Full coverage, or the completeness of a future CDI 5 selection. Preserve the old result as a regression reference; do not reuse 774 as the expected CDI 5 total.

| Area | Actual foundation | Gap that affects the plan |
| --- | --- | --- |
| Injection and resolution | Bean discovery, type resolution, qualifiers, alternatives and contextual instances exist. | New CDI 5 metadata must reach every discovery, generation and resolution path. |
| Build Compatible Extensions | BCE phases, synthetic beans, observers, language model and generated metadata already exist. | New synthetic injections and parameterized registration need implementation. |
| Invokers | Lookup and method-handle invocation exist. | Dependent lookup instances are currently destroyed when the invocation returns; asynchronous completion needs a different lifetime. |
| Interception | Binding-based chains, construction and lifecycle interception exist. | Direct interception declarations, decorators and `InterceptionFactory` are incomplete or absent. |
| Portable extensions | Some `BeanManager` and `InjectionTarget` operations are functional. | `addExtensions` is ignored; the portable extension lifecycle is absent. Several creation SPI methods throw. |
| Archives | Scanner and classloader infrastructure exist. | `ContainerScanner` treats presence of `beans.xml` as a trigger to scan all classes without parsing its contents. Archive identity and visibility are absent from the bean model. |
| Passivation | Context storage and internal bean identifiers exist. | No complete passivation contract, serialized proxy restoration or passivating dependency validation. |

The archive finding also deserves investigation during the Lite baseline: CDI Lite's `annotated`, `none` and rejection of `all` must be checked on the actual public startup paths. It should not be deferred solely because richer XML support belongs to Full. [Bean archives](https://jakarta.ee/specifications/cdi/5.0/jakarta-cdi-spec-5.0.html#bean_archive).

## Work required for CDI 5 Lite

### Dependencies and API contracts

Update the coordinates explicitly, including the test runner and manually constructed classpaths. API relocation artifacts exist, but the TCK has no equivalent relocation safety net. [Official coordinates and migration changes](https://jakarta.ee/specifications/cdi/5.0/).

| Current coordinate | CDI 5 coordinate |
| --- | --- |
| `jakarta.enterprise:jakarta.enterprise.cdi-api` | `jakarta.cdi:jakarta.cdi-api:5.0.0` |
| `jakarta.enterprise:jakarta.enterprise.lang-model` | `jakarta.cdi:jakarta.cdi-lang-model-api:5.0.0` |
| `jakarta.enterprise:jakarta.enterprise.cdi-el-api`, where needed | `jakarta.cdi:jakarta.cdi-el-api:5.0.0` |
| `jakarta.enterprise:cdi-tck-api` | `jakarta.cdi:jakarta.cdi-tck-api:5.0.0` |
| `jakarta.enterprise:cdi-tck-core-impl` | `jakarta.cdi:jakarta.cdi-tck-core-impl:5.0.0` |
| `jakarta.enterprise:cdi-tck-lang-model` | `jakarta.cdi:jakarta.cdi-tck-lang-model:5.0.0` |

TCK names are verified in its [parent POM](https://github.com/jakartaee/cdi-tck/blob/5.0.0/pom.xml), [core POM](https://github.com/jakartaee/cdi-tck/blob/5.0.0/impl/pom.xml) and [language model POM](https://github.com/jakartaee/cdi-tck/blob/5.0.0/lang-model/pom.xml).

The Java module names `jakarta.cdi` and `jakarta.cdi.lang.model` remain compatible with Vauban's current descriptors; a Maven rename does not imply a package or Java module rename. Audit service loading and application layers anyway. [CDI 4.1 descriptor](https://github.com/jakartaee/cdi/blob/4.1.0/api/src/main/java/module-info.java), [CDI 5 descriptor](https://github.com/jakartaee/cdi/blob/5.0.0/api/src/main/java/module-info.java).

Remove or relocate implementations of the EL methods removed from `BeanManager`; keep any actual EL integration behind `ELAwareBeanManager` and the supplemental API. Implement the new SE BCE registration contract. Audit all implemented CDI interfaces, including new default methods: compilation alone can succeed while new bean attributes remain incorrectly false. [Bean attributes](https://github.com/jakartaee/cdi/blob/5.0.0/api/src/main/java/jakarta/enterprise/inject/spi/BeanAttributes.java), [SE initializer](https://github.com/jakartaee/cdi/blob/5.0.0/api/src/main/java/jakarta/enterprise/inject/se/SeContainerInitializer.java).

### Behavior and implementation surfaces

| Feature | Required Vauban work | Acceptance example |
| --- | --- | --- |
| `@Reserve` | Carry reserve metadata through discovery, stereotypes, producer beans, synthetic beans and generated descriptors; implement enablement, priority and resolution rules. | A selected reserve supplies a default; a matching ordinary bean wins. Invalid combinations and ambiguous reserves fail as specified. |
| `@Eager` | Extend bean metadata and startup orchestration. Request the actual contextual instance instead of merely creating its lazy proxy. | An application-scoped constructor and initialization callback run by the required startup point, once per container. Invalid scopes are rejected. |
| `@AutoClose` | Integrate closing into managed and producer bean destruction, including generated factories and dependent ownership. | Destruction invokes `close()` with the specified ordering relative to callbacks/disposers; one failing close does not prevent remaining cleanup. |
| Global application scope | Verify context initialization/destruction ordering, cross-thread access and isolation between SE containers. | Two concurrent containers never share application instances; each has one application context active across its application threads. |
| Asynchronous invokers | Discover handlers and implement matching, completion ownership and delayed cleanup in `VaubanInvoker`. | Dependent lookup instances survive until asynchronous completion and are released once on success or failure. |
| `SyntheticInjections` | Register declared injection points, validate them at deployment and implement creator/disposer injection access and ownership. Preserve deprecated callback compatibility where required. | Invalid synthetic dependencies fail before startup; creator and disposer injection lifetimes obey their separate contracts. |
| BCE and SE additions | Handle parameterized `@Registration` using generic assignability; connect programmatic BCE registration to the existing processor. | Registration distinguishes parameterized bean types; programmatically supplied extensions run through the expected phases. |
| Clarifications and TCK additions | Check `CDI.current()` injection-point behavior, records in the language model, sealed unproxyable types and archive rules. | New official tests run without carrying over unexplained exclusions. |

Behavior references: [`Reserve`](https://jakarta.ee/specifications/cdi/5.0/apidocs/jakarta/enterprise/inject/Reserve.html), [`Eager`](https://jakarta.ee/specifications/cdi/5.0/apidocs/jakarta/enterprise/context/Eager.html), [`AutoClose`](https://jakarta.ee/specifications/cdi/5.0/apidocs/jakarta/enterprise/context/AutoClose.html), [global scopes](https://jakarta.ee/specifications/cdi/5.0/jakarta-cdi-spec-5.0.html#global_scope), [`AsyncHandler`](https://jakarta.ee/specifications/cdi/5.0/apidocs/jakarta/enterprise/invoke/AsyncHandler.html), [`SyntheticInjections`](https://jakarta.ee/specifications/cdi/5.0/apidocs/jakarta/enterprise/inject/build/compatible/spi/SyntheticInjections.html), [`Registration`](https://jakarta.ee/specifications/cdi/5.0/apidocs/jakarta/enterprise/inject/build/compatible/spi/Registration.html), [release changes](https://jakarta.ee/specifications/cdi/5.0/).

Metadata propagation is a cross-cutting task. `BeanDescriptor`, `ManagedBean`, `VaubanBceBeanInfo`, the synthetic converter, the metadata serializer and the annotation processor must agree on the same attributes. Add a metadata format compatibility decision before publishing generated CDI 5 artifacts; stale 4.1 metadata must not silently discard new behavior.

### Lite milestones

Effort below assumes one engineer familiar with Vauban and CDI. Days include focused tests and review. Independent work can overlap, but the ranges should not be divided mechanically by an agent or engineer count.

| Stage | Deliverable and completion gate | Engineer-days |
| --- | --- | --- |
| L0 | Reproduce the 4.1 baseline; capture revision, dependencies, selection and reports; characterize archive handling and generated versus startup discovery. | 2–3 |
| L1 | Migrate API/TCK coordinates and interface contracts; compile the reactor; establish a CDI 5 failure inventory. Check duplicate API dependencies. | 2–4 |
| L2 | Implement reserve/eager/auto-close metadata and behavior, including stereotypes and producers, on both generated and startup paths. | 5–8 |
| L3 | Implement synthetic injections, parameterized registration and programmatic SE BCE registration. Validate creation/destruction ownership. | 4–7 |
| L4 | Implement asynchronous invokers and validate global scope lifecycle. Exercise success, failure, cancellation where applicable, and container shutdown. | 4–7 |
| L5 | Resolve remaining CDI 5 differences and existing SE gaps, including `addPackages(false, ...)` ignoring `scanRecursively`; run the applicable Lite, language model, SE and AtInject checks; make failures block CI. | 5–9 |
| L6 | Validate downstream components and packaging, record performance differences, and publish the exact supported scope and migration notes. | 3–6 |

Total: approximately **25–45 engineer-days**, or **5–9 working weeks** with one dedicated engineer. L0 → L1 → implementation stages → L5 → L6 is the delivery sequence. Release Lite 5 when its validation gate passes; Full need not delay that release.

## Architecture and work required for Full

### Share the engine and make deployment extensible

Retain `vauban-core`, the processor, indexer, classloader and existing interception engine. Prototype an optional `vauban-full` module for the additional implementation, with narrow deployment hooks in core. Keep integration adapters separate. This is a proposed module boundary; validate it with a portable extension prototype before committing to the packaging.

The central addition is a deployment model that records archives, effective annotations and types, bean attributes, injection points, enablement and visibility. It remains mutable during the specified extension phases and becomes immutable only after those phases and validation. Resolution and generated executable plans must use this final model.

Portable extensions can veto classes, replace metadata and wrap injection targets. A generated factory based on earlier metadata may then be wrong. Define an explicit rule for reuse, regeneration or a supported dynamic path after each relevant mutation. Preserve fast runtime dispatch after startup. Do not promise universal build-time-only processing for arbitrary portable extensions, which expose runtime reflection and mutation contracts.

BCE support remains part of Full. Implement its coexistence with portable extensions, including phase ordering and `@SkipIfPortableExtensionPresent`, according to the specification. Translating every portable extension into a BCE is not a general solution. [Portable extensions](https://jakarta.ee/specifications/cdi/5.0/jakarta-cdi-spec-5.0.html#spi_full).

### Full milestones after Lite 5

| Stage | Main work | Completion gate | Engineer-days |
| --- | --- | --- | --- |
| F0 | Add a Full core/SE runner and inventory missing features by TCK requirement. Select representative extension fixtures. | Repeatable failure map with justified environmental exclusions. | 3–5 |
| F1 | Introduce deployment/archives model, effective metadata and generation invalidation. | A metadata modification changes real injection behavior on both execution paths; archive visibility is testable. | 8–12 |
| F2 | Implement portable extension discovery, lifecycle events, observers, configurators and errors together with missing `BeanManager`, `ProducerFactory`, `InjectionTarget` and bean creation operations. | An extension can add/veto/modify a type, replace an injection target and register beans/observers; invalid changes fail in the correct phase. | 18–30 |
| F3 | Complete XML and programmatic enablement, specialization, direct interception and `InterceptionFactory`. | Archive-scoped selection, specialization chains and interception ordering pass focused tests and their TCK groups. | 10–18 |
| F4 | Implement decorators, delegates, generic delegate resolution, ordering and invocation chains. | Multiple decorators compose with interceptors, producers and extension-modified beans. | 6–12 |
| F5 | Implement passivation capabilities, stable contextual identity, proxy restoration and dependency validation. | Round trips restore working references; illegal passivating graphs fail as required. | 10–20 |
| F6 | Resolve cross-feature TCK failures; validate generated paths, Java Modules, lifecycle cleanup and representative third-party extensions; publish conformance evidence. | All tests applicable to the declared Full environment pass, with documented official exclusions only. | 10–20 |

Total: approximately **65–120 additional engineer-days**, or **13–24 working weeks**. Combined with Lite: roughly **90–165 engineer-days**, or **18–33 working weeks**, before any additional EE integration scope. The widest uncertainty is F2 and the interaction of extensions, archives, decorators and passivation. Re-estimate after F0/F1; a count of unimplemented SPI methods is not a reliable effort model.

F1 and F2 are the critical architectural dependency. Adding decorators or serialization before effective metadata and identity are settled creates substantial rework. All applicable Full requirements, including custom passivating scopes, remain part of the Full gate; the separate EE track is not a reason to omit them.

### Web and EE integration

Keep web sessions, conversation integration, EL and transaction service integration as an explicit additional scope. For a named Servlet/EE target, validate every requirement applicable to that environment and run the corresponding integration tests/CDI EE TCK. Full core success alone must not be described as an EE platform certification.

Foy already has a CDI adapter: `VaubanWebContextsListener` binds/unbinds sessions and forwards session initialization/destruction to `io.vidocq.vauban.webcontexts.WebContexts`. Its POM references `vauban-webcontexts`, but that module is absent from this Vauban checkout and reactor. Resolve the branch/published-artifact relationship before estimating or rebuilding this functionality. Reuse Foy's session infrastructure rather than introducing a second session manager.

A preliminary **15–35 additional engineer-days** may cover adapter work if the referenced web contexts implementation is recoverable and usable. This is the least certain range: it excludes implementing an entire Servlet, EL or transaction engine. Investigate conversation lifecycle, propagation and locking; ensure serialization is integrated with session handling; connect transaction observers to the existing transaction service where required.

## Validation and release strategy

1. **Pin the evidence.** Record the implementation revision, Java/Maven versions, dependency graph, official TCK release, suite selection and exclusions for each result. Execute the appropriate canonical suite and required ancillary checks from that release's guide. Do not treat a Maven `BUILD SUCCESS` line as the result.
2. **Test both implementation paths.** The existing Arquillian runner exercises startup discovery. Add representative fixtures compiled through the processor/plugin for every new feature, plus mixed generated/unprocessed dependencies. The standard TCK result and generated-path checks establish different facts.
3. **Make CI enforce results.** Fail on test failures/errors, missing reports, an unexpectedly empty suite or unexplained skips. Separate Lite, Full, language model and SE reporting; retain AtInject and integration results. Remove exclusions only when the environment actually supports the relevant tests.
4. **Exercise Java Modules and lifetimes.** Cover application layers, non-public members, produced types, service loading, repeated containers and reloads. For tests that define classes irreversibly, run both established test orders. Track retained classloaders and dependent instances after shutdown.
5. **Check downstream convergence.** Audit Vidocq's BOM/runtime, Foy, Cassini, Mansart and the other CDI adapters. Different old/new GAVs can evade ordinary Maven version mediation; explicitly prevent duplicate CDI API packages. Update manually embedded dependency paths in examples and launch scripts.
6. **Measure the product tradeoff.** Compare cold startup, loaded classes, retained memory, injection/lookup, event delivery and interception against the same 4.1 baseline. Agree regression limits from these measurements; no performance estimate is assumed here.
7. **State the exact release claim.** Distinguish tested feature support, complete applicable TCK success and any formal compatible-implementation listing. Keep the 4.1 maintenance line independent; promote Lite 5 and then Full 5 through separate release gates.

The first implementation increment should be L0/L1: a trustworthy baseline, complete dependency/API inventory and an executable CDI 5 failure map. That makes the remainder of the Lite estimate concrete before undertaking the larger Full deployment model.

## Local implementation references

Paths below are relative to the Vauban repository unless prefixed `../foy/`. Line numbers describe the audited revision. These are entry points for implementation, not a claim that every method in the surrounding class is missing.

| Path | Line | Evidence or change location |
| --- | --- | --- |
| `pom.xml` | 85 | CDI 4.1 property. |
| `vauban-tck-runner/pom.xml` | 163 | Current excluded test groups. |
| `vauban-examples/example-test/pom.xml` | 44 | Hardcoded old CDI JAR coordinates and version. |
| `vauban-core/src/main/java/io/vidocq/vauban/core/bean/model/BeanDescriptor.java` | 31 | Existing bean attributes; no reserve/eager/auto-close or archive identity. |
| `vauban-core/src/main/java/io/vidocq/vauban/core/bean/discovery/BeanDiscovery.java` | 374 | Managed bean descriptor construction; producer construction follows later. |
| `vauban-core/src/main/java/io/vidocq/vauban/core/container/VaubanContainerBuilder.java` | 555 | Existing BCE orchestration; later validation and container creation. |
| `vauban-core/src/main/java/io/vidocq/vauban/core/container/ContainerScanner.java` | 197 | `beans.xml` presence handling. |
| `vauban-core/src/main/java/io/vidocq/vauban/core/container/VaubanSeContainerInitializer.java` | 74 | Portable extension registration without implementation. |
| `vauban-core/src/main/java/io/vidocq/vauban/core/container/VaubanBeanManager.java` | 582 | Empty decorator resolution; later methods include incomplete creation SPI. |
| `vauban-core/src/main/java/io/vidocq/vauban/core/container/VaubanBeanManager.java` | 638 | Existing injection target implementation to extend. |
| `vauban-core/src/main/java/io/vidocq/vauban/core/container/ManagedBean.java` | 42 | Bean implementation and passivation work. |
| `vauban-core/src/main/java/io/vidocq/vauban/core/extensions/VaubanInvoker.java` | 167 | Immediate cleanup of invocation lookup dependencies. |
| `vauban-core/src/main/java/io/vidocq/vauban/core/container/SyntheticComponentRegistrar.java` | 338 | Existing synthetic creator API invocation; disposer path follows. |
| `vauban-core/src/main/java/io/vidocq/vauban/core/extensions/BceProcessor.java` | 465 | Registration currently based on `registration.types()`. |
| `vauban-core/src/main/java/io/vidocq/vauban/core/extensions/BceTypeMatcher.java` | 165 | Parameterized type matching reduced to raw name. |
| `vauban-processor/src/main/java/io/vidocq/vauban/processor/VaubanProcessor.java` | 337 | Synthetic metadata serialization/validation on the generated path. |
| `vauban-core/src/main/java/io/vidocq/vauban/core/container/VaubanContainer.java` | 187 | Default Application, Request, Dependent and Singleton contexts. |
| `../foy/foy-cdi-vauban/pom.xml` | 38 | Dependency on `vauban-webcontexts`. |
| `../foy/foy-cdi-vauban/src/main/java/io/vidocq/foy/cdi/vauban/VaubanWebContextsListener.java` | 51 | Existing session context integration. |

This task produces a study and implementation plan. No runtime, dependency or TCK configuration has been migrated, and no new conformance execution is claimed.
