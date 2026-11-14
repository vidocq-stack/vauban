# AGENTS.md

Contributor guidance for agents working on this repository. See also the companion `CLAUDE.md` file.

## Documentation (Antora) conventions

The project documentation lives in `docs/en` as an Antora component and is
aggregated by the **vidocq-docs** site, which provides a **shared UI bundle** (banner,
logo, fonts, colours, footer). **Never customise the documentation UI per project** —
all visual harmonisation is centralised in `vidocq-docs/ui-bundle`.

### Gold reference
**Vauban** is the reference implementation for documentation structure. Mirror its
`docs/en` layout when creating or updating docs. **Chappe** (HTTP server)
and **Vidocq** (runtime orchestrator) are *special cases*, not references: they are not
Jakarta EE / MicroProfile spec implementations.

### Repository layout
- `docs/en/antora.yml` → `name: <project>`, `title:`, versioned per branch (`dev` prerelease on `main`, `'<version>'` on `docs/<version>`), `project-version` attribute, `nav:`, `lang: en`.
- Pages in `modules/ROOT/pages/`, navigation in `modules/ROOT/nav.adoc`, images in
  `modules/ROOT/images/`.
- **English-only** (ADR 0004 in vidocq-docs): no French mirror — do not reintroduce one.

### Canonical navigation (section order)
`index` → `getting-started` → `usage` → `concepts` → `internals` → `tck` →
`performance` → `reference` → `migration`

Multi-module projects (e.g. Vidocq, Mansart) may append `modules/*` / `sub-modules/*`
sub-pages after `migration`.

### TCK / Performance rule (not mutually exclusive)
- Every **spec implementation** — i.e. **all projects except Chappe and Vidocq** — MUST
  have a **`tck`** section documenting TCK coverage/status.
- Projects with a performance story (e.g. **Chappe**) keep their **`performance`** section.
- When **both** sections exist, order them **TCK first, then Performance**.
- **Chappe** and **Vidocq** do not require a `tck` section (not spec implementations).

### `index.adoc` structure
Follow Vauban's `index.adoc`: page title (`= <Project>`), `:description:`, a centred logo
(`image::<project>-logo.png[...,role=module-logo]`), a `[.lead]` paragraph, then
`== Origin of the name`, an `== At a glance` table, and ecosystem / quick-links sections.

### Logo
Provide `modules/ROOT/images/<project>-logo.png` (PNG), referenced from `index.adoc`.

> When you change these documentation rules, keep `AGENTS.md` and `CLAUDE.md` in sync.

## Terminology

Use **Java Modules** (or **Java module** for a single module) when referring to
the Java Platform Module System. Do **not** use the abbreviation **JPMS** — in
prose, identifiers, or documentation.

## Architecture rules

Test-scope libraries are never shipped: JUnit, and **ArchUnit**
(`com.tngtech.archunit:archunit`, Apache-2.0, through the shared `vidocq-archrules`
library of `vidocq-parent`), used only by the test-only module `vauban-architecture-tests`
(Vidocq/vidocq#289). Its `ArchitectureRules` turns the workspace rules into
tests on the classes of the ten shipped modules: allowed dependencies per module, layers,
foundational bricks independent, no platform thread, no `ThreadLocal`, no reflection. The reflective
fallbacks (`REFLECTIVE_FALLBACKS`) and the `ThreadLocal` holders are listed class by class with
their reason, and checked for staleness by `ArchitectureRulesSelfTest`: add a class there only with
a reason documented in `internals.adoc`, never to silence a violation. Reference page:
"Architecture rules".

## Build compatible extensions work at build only

A CDI build compatible extension works at build, and only there. `vidocq:generate` runs the extensions of an
application once and records what they did; the packaged application loads that record instead of running them
(Vidocq/vauban#288). An extension that breaks these rules loses work or checks in a packaged application:

- compute from the deployment's classes only: no configuration value, environment variable, outside file or network
  resource in a phase;
- check the start environment in an observer of `jakarta.enterprise.event.Startup` that throws a
  `DeploymentException`, fed with what the build collected (`withParam`); models: Ravel's `ConfigStartCheck`,
  Cervantes' `JwtConfigurationCheck`;
- add annotations, never remove them; carry data in parameters, never in fields of the extension;
- ask `ExtensionPhase.isBuildTime()` only as a last resort, in a class of its own: an extension that asks runs again
  at each start (Heisenberg's `InterceptorPriorityExtension`);
- the brick's Vidocq runtime extension adds its own beans with `VaubanContainerBuilder.addHostBean`, never
  `addBeanClass`, and its `-codegen` bundle brings the brick's CDI jar to the processor path;
- list in `@io.vidocq.vauban.api.ClientProxies` every interface the extension registers as a synthetic bean of a
  normal scope (`@ApplicationScoped`, `@TransactionScoped`, ...). Vauban generates client proxies at build from the
  classes it compiles, and a synthetic bean has none: the freeze records its descriptor, not a proxy, and a deployment
  that runs its extensions at start has no build step. Without the list, the container builds a
  `java.lang.reflect.Proxy` at every start: every call goes through reflection, the boot is slower, nothing runs ahead
  of time, and every application prints the `RUNTIME CODE GENERATION FALLBACK` warning (Vauban BUG-20261113-19:
  Noether's `EntityManager` and `EntityManagerFactory`). Put it on a class of a module compiled with
  `vauban-processor` that provides its `_VaubanComponents`: the `*-cdi-vauban` extension, or, for a
  container-neutral brick, its Vidocq runtime extension (Vidocq's `NoetherPersistenceExtension`). Public interfaces
  only, no class, no `@Dependent` bean;
- test both ways: the extensions run at start (TCK, another container) and frozen (the startup report says
  `cdi extensions  N extensions frozen at build`, and an invalid environment still stops the start).

Guide: `docs/writing-a-build-compatible-extension.md` in `Vidocq/vidocq-workspace`, and Vauban's concepts page,
"Writing an extension the build can freeze" (`docs/en/modules/ROOT/pages/concepts.adoc#writing-a-frozen-extension`).
