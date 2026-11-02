# VAU-PERF-005 — Where does "BCE effects baked in" live for dependency archives?

Status: design note for a decision, nothing implemented. See `BUG.md` VAU-PERF-005.

## Problem

`VaubanContainerBuilder` skips the runtime Build Compatible Extension (BCE) lifecycle only when
`allSourcesProcessed` is true: every bean archive comes from a source carrying `META-INF/vauban-bce-processed`.
`VaubanProcessor` writes that marker only in the module whose compilation ran the BCEs. A brick jar
(cassini-core, ravel-cdi-vauban, …) cannot carry it: BCEs of *other* bricks, present only in the final
application, may enhance its classes. So in a Vidocq application, the app jar is marked, the 18 brick archives
are not, and every boot replays the whole lifecycle (≈ 940 `Method.invoke` plus a full discovery).

The only build that sees the full BCE set **and** every bean archive is the application build (codegen bundles,
dependency bean archives since VAU-APT-006). Whatever we choose, that build must record the fact, and the runtime
must be able to check that the fact still holds.

## Options

### A. Marker in the application build output (recommended)

The application's processor run already computes the BCE effects on dependency archives. It writes, next to the
existing replay list / frozen enhancement patch, a manifest `META-INF/vauban-bce-processed-archives` listing each
dependency archive it covered (module name + content hash) and a fingerprint of the BCE set it ran. The frozen
patch is extended to dependency classes. At runtime, an archive counts as processed only if it is listed with a
matching hash and the BCE set on the module path has the same fingerprint; otherwise the runtime replay runs, as
today.

- Weld / Open Liberty: nothing changes. They never read `META-INF/vauban-*`; the dependency jars are untouched.
- Jars not enriched / not seen by the app build (added later on the module path, different version at run time):
  hash mismatch or absent from the list → replay for the whole deployment (same as today). No silent skip.
- Cost: the app build output grows by a small manifest and the dependency part of the patch; hashing jars at boot
  can be replaced by comparing the module version + jar size if hashing turns out to be visible.

### B. Marker inside `vidocq:generate` enriched copies

`vidocq:generate` already writes enriched copies of dependency jars (generated classes added). It would also add
`META-INF/vauban-bce-processed` to each copy.

- Weld / Open Liberty: unaffected as long as they run on the original jars; if an enriched copy is deployed to
  them, the extra marker is ignored (harmless).
- Jars not enriched: no marker → replay, fine.
- Breaks: the marker is *not scoped* to a BCE set. A copy produced for one application (or before an extension
  was added) is reused elsewhere and claims "processed" for a BCE set it never saw → enhancements silently skipped.
  To be safe it must carry the same fingerprint as option A, at which point it is A with the data spread over N jars.
  It also ties the fix to the Vidocq Maven plugin: a plain Maven/Gradle build running only the Vauban processor
  would never get it.

### C. Other: per-brick "closed world" declaration

A brick declares in its own jar that no foreign BCE may enhance it (marker written by its own processor run).
Cheap, but false for most bricks (`@Interceptor` bindings, `@Priority` overrides and synthetic beans from other
bricks do touch them) and impossible to verify at run time. Rejected.

## Recommendation

Option A. It keeps the fact where the knowledge is (the application build), is checked at boot with a cheap
fingerprint, falls back to today's full replay for anything not covered, and does not depend on `vidocq:generate`
or touch the jars Weld/Liberty load. Option B's fingerprint requirement makes it a scattered variant of A.

Constraints to carry into the implementation: CDI Lite TCK 774/774; the BUG-20261030-27 lesson (BCE run twice when
the codegen bundle is on the processor path → check boot with the bundles, not only unit tests); a test proving
the replay still runs when the manifest is missing or stale.
