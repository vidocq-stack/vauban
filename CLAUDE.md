# Vauban - Claude Code Guidelines

## 1. Plan Mode Default

- Enter plan mode for any non-trivial task (3+ steps or architectural decisions)
- If something goes wrong, STOP and re-plan immediately — don't keep pushing
- Use plan mode for verification steps, not just building
- Write detailed specs upfront to reduce ambiguity

## 2. Subagent Strategy

- Use subagents frequently to keep the main context window clean
- Offload research, exploration, and parallel analysis to subagents
- For complex problems, throw more compute via subagents
- Assign one task per subagent for focused execution

## 3. Self-Improvement Loop

- After any correction from the user, update `tasks/lessons.md` with the pattern
- Write rules for yourself to prevent repeating the same mistake
- Ruthlessly iterate on these lessons until the mistake rate drops
- Review lessons at the start of each session

## 4. Verification Before Done

- Never mark a task complete without proving it works
- Diff behavior between main and your changes when relevant
- Ask yourself: "Would a staff engineer approve this?"
- Run tests, check logs, and demonstrate correctness

## 5. Demand Elegance (Balanced)

- For non-trivial changes, ask: "Is there a more elegant solution?"
- If a fix feels hacky, ask: "Knowing everything I know now, implement the elegant solution."
- Skip this for simple fixes — don't over-engineer
- Challenge your own work before presenting it

## 6. Autonomous Bug Fixing

- When given a bug report: just fix it
- Use logs, errors, and failing tests to diagnose
- Require zero context switching from the user
- Fix failing CI tests automatically

## Task Management

1. **Plan First** – Write the plan in `tasks/todo.md` with checkable items
2. **Verify Plan** – Confirm the plan before implementation
3. **Track Progress** – Mark items complete as you go
4. **Explain Changes** – Provide a high-level summary at each step
5. **Document Results** – Add a review section to `tasks/todo.md`
6. **Capture Lessons** – Update `tasks/lessons.md` after corrections

## Environment

- Use **sdkman** to manage Java and Maven versions
- Required: **Java 25** (`sdk use java 25.ea.4-open` or equivalent)
- Required: **Maven 3.9.16** (`sdk use maven 3.9.16`)
- All POMs use `modelVersion 4.0.0` (the workspace moved off Maven 4 RC to a stable 3.9 GA)

## Context Mode

- Use `ctx_batch_execute` for commands producing large output (builds, tests, logs)
- Use `ctx_search` for follow-up searches after a batch_execute
- Use `ctx_execute` / `ctx_execute_file` for data analysis, log parsing, transformations
- **Never** use Bash for commands producing >20 lines of output — go through context-mode
- **Never** use ctx_execute/ctx_execute_file to create or modify files — use Write/Edit
- Read is reserved for files you will edit next — for analysis, use ctx_execute_file

## Core Principles

### Simplicity First
Make every change as simple as possible and minimize code impact.

### No Laziness
Find root causes. Avoid temporary fixes. Maintain senior-level engineering standards.

### Language

Commit messages, Javadoc, and the content of all `.md` files must be written in **English**.

## Documentation (Antora) conventions

The project documentation lives in `docs/en` and `docs/fr` as Antora modules and is
aggregated by the **vidocq-docs** site, which provides a **shared UI bundle** (banner,
logo, fonts, colours, footer). **Never customise the documentation UI per project** —
all visual harmonisation is centralised in `vidocq-docs/ui-bundle`.

### Gold reference
**Vauban** is the reference implementation for documentation structure. Mirror its
`docs/en` + `docs/fr` layout when creating or updating docs. **Chappe** (HTTP server)
and **Vidocq** (runtime orchestrator) are *special cases*, not references: they are not
Jakarta EE / MicroProfile spec implementations.

### Repository layout
- `docs/en/antora.yml` → `name: <project>`, `title:`, `version: ~`, `nav:`, `lang: en`.
- `docs/fr/antora.yml` → `name: <project>-fr`, same `title`, `lang: fr`.
- Pages in `modules/ROOT/pages/`, navigation in `modules/ROOT/nav.adoc`, images in
  `modules/ROOT/images/`.
- **EN/FR parity**: every page exists in both languages with translated content.

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
