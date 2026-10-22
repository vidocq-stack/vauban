# Naming bindings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `java:` names are declared by the integrator (`JndiBindings` beans, or `vidocq.naming.*` keys in Vidocq),
in four distinct namespaces, bound to a bean (type + qualifiers) or linked to another name, validated at startup.

**Architecture:** `vauban-naming` gets the declaration API (`Namespace`, `JndiName`, `JndiTarget`, `JndiBindings`,
`Binder`) and a default `NamingService` built on a table of declared names; its built-in `jdbc/` rule goes.
Qualifiers written as text are built by a new `vauban-api` service that `vauban-core` provides with its existing
`AnnotationInstances`. Then Vidocq's naming extension turns `vidocq.naming.<name> = <target>` keys into a
`JndiBindings` bean.

**Tech Stack:** Java 25, Java Modules, CDI 4.1 Lite (Vauban), JNDI (`java.naming`), JUnit 5, Maven 3.9.16.

**Spec:** `docs/superpowers/specs/2026-10-22-naming-bindings-design.md` (this repository). Read it before any task.

## Global Constraints

- English for code, Javadoc, comments, commits, docs. Say "Java Modules", never "JPMS".
- TDD: the failing test first, run it red, then the code.
- No new reflection in `vauban-naming`: no `Method.invoke`, no `setAccessible`, no `java.lang.reflect.Proxy`.
  Loading a class by name (`Class.forName(name, false, loader)`) is allowed.
- `vauban-naming` depends only on `jakarta.cdi`, `java.naming`, `io.vidocq.vauban.api` at runtime; `vauban-core`
  stays test-scoped there.
- No default binding in Vauban: `DataSourceNames` is deleted.
- A name declared twice is an error, never "last one wins".
- Commits: `git commit -S -s`, Conventional Commits, scope `naming` (Vauban) / `naming` (Vidocq, with `(#<issue>)`
  when an issue exists), trailers `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and
  `Claude-Session: https://claude.ai/code/session_013VVdjwGXo5SpdK7yhipsHC`.
- Build: `./mvnw -ntp -pl <module> -am install` from the repository root; tests `./mvnw -ntp -pl <module> test`.
  If the IDE compiled into `target/` (`Unresolved compilation problems`), run `./mvnw clean install` first.
- Repositories: Vauban worktree `/Users/yblazart/projects/perso/vidocq-workspace/vauban/feat/naming-bindings`;
  Vidocq worktree to create for Tasks 7-8: `BRANCH=feat/naming-bindings BASE=origin/main mani run wt-add -p vidocq`.

## Review Focus

1. **Spaces in a declaration** — `com.acme.Toto   @Named( "Toto" )` and a trailing space from a properties line
   parse like the compact form. Test in Task 3 (`spacesAroundTokensAreIgnored`).
2. **Quoted strings holding grammar characters** — `@com.acme.Region("a,b)@c")` and an escaped quote `"say \"hi\""`
   keep their content. Test in Task 3 (`aStringMemberKeepsCommasParenthesesAtSignsAndEscapedQuotes`).
3. **Lookup during startup** — an `@Initialized(ApplicationScoped.class)` observer that looks up a declared name
   before the `Startup` observer ran gets the bean. Test in Task 4 (`aLookupBeforeStartupFindsTheDeclaredNames`).
4. **A restarted container with other declarations** — the second container answers its own names, not the first
   one's table. Test in Task 4 (`aNewContainerAnswersItsOwnDeclarations`).
5. **A `java:`-prefixed or dotted Vidocq key** — `vidocq.naming.comp.env.toto` (dots) and
   `vidocq.naming.java\:comp/env/toto` fail the start as unknown naming keys. Test in Task 7
   (`aKeyOutsideTheFourNamespacesFailsTheStart`).

---

## Vauban (worktree `vauban/feat/naming-bindings`)

### Task 1: Annotation instances from member literals (`vauban-api` service, `vauban-core` provider)

**Files:**
- Create: `vauban-api/src/main/java/io/vidocq/vauban/api/AnnotationInstanceFactory.java`
- Modify: `vauban-api/src/main/java/module-info.java` (`uses io.vidocq.vauban.api.AnnotationInstanceFactory;` only if
  `find()` lives in this module, which it does)
- Create: `vauban-core/src/main/java/io/vidocq/vauban/core/annotation/CoreAnnotationInstanceFactory.java`
- Modify: `vauban-core/src/main/java/module-info.java` (`provides io.vidocq.vauban.api.AnnotationInstanceFactory with
  io.vidocq.vauban.core.annotation.CoreAnnotationInstanceFactory;`)
- Test: `vauban-core/src/test/java/io/vidocq/vauban/core/annotation/CoreAnnotationInstanceFactoryTest.java` with
  test annotations `Region(String value)`, `Tier(int level)`, `Zoned(Zone value)` (enum `Zone { EU, US }`),
  `Typed(Class<?> value)`, `Primary` (no member), `WithDefault(String value() default "x")`.

**Interfaces:**
- Produces:
  ```java
  public interface AnnotationInstanceFactory {
      /** members: member name -> Java source literal: "\"eu\"", "2", "true", "'c'", "com.acme.Zone.EU", "com.acme.Foo.class" */
      <A extends Annotation> A create(Class<A> type, Map<String, String> members, ClassLoader loader);
      /** The provider on the module path / class path (ServiceLoader on AnnotationInstanceFactory's own loader, then the TCCL). */
      static Optional<AnnotationInstanceFactory> find();
  }
  ```
  `CoreAnnotationInstanceFactory` converts each literal to an `AnnotationValue` using the member's declared type, builds
  an `AnnotationInfo(DotName.of(type.getName()), values)` and returns `AnnotationInstances.create(type, info, loader)`.
  Errors: `IllegalArgumentException` whose message names the annotation and the member (unknown member, literal not
  of the member's type, member without default left out, array or annotation member).

- [ ] **Step 1: Write the failing tests**
  - `aMemberLessQualifierEqualsTheJdkInstance`: `create(Primary.class, Map.of(), loader)` equals
    `Annotated.class.getAnnotation(Primary.class)`.
  - `eachLiteralKindBecomesTheMembersType`: `Region("eu")`, `Tier(2)`, `Zoned(Zone.EU)`, `Typed(String.class)` each
    equal the JDK instance read from a class annotated the same way; `hashCode` equal too.
  - `aLeftOutMemberTakesItsDefault`: `create(WithDefault.class, Map.of())` has `value() == "x"`.
  - `anUnknownMemberIsRefused`, `aLiteralOfTheWrongTypeIsRefused` (`Tier` with `"\"two\""`),
    `aMemberWithoutDefaultLeftOutIsRefused` (`Region` with no member): `IllegalArgumentException`, message contains
    the annotation's simple name and the member name.
  - `theProviderIsFoundThroughServiceLoader`: `AnnotationInstanceFactory.find()` is present and of type
    `CoreAnnotationInstanceFactory`.
- [ ] **Step 2: Run** `./mvnw -ntp -pl vauban-core -am test -Dtest=CoreAnnotationInstanceFactoryTest -Dsurefire.failIfNoSpecifiedTests=false` — expected: compilation failure (types missing).
- [ ] **Step 3: Implement** the interface, the provider, both `module-info.java` lines. Member types are read from the
  annotation type the way `AnnotationInstances` already does (core's existing mechanism; nothing new in naming).
- [ ] **Step 4: Run** the same command — expected: all green. Then `./mvnw -ntp -pl vauban-api,vauban-core install -DskipTests`.
- [ ] **Step 5: Commit** `feat(api): build annotation instances from member literals`.

### Task 2: `Namespace` and `JndiName`

**Files:**
- Create: `vauban-naming/src/main/java/io/vidocq/vauban/naming/Namespace.java`, `JndiName.java`
- Test: `vauban-naming/src/test/java/io/vidocq/vauban/naming/JndiNameTest.java`

**Interfaces:**
- Produces:
  ```java
  public enum Namespace { COMP, MODULE, APP, GLOBAL;  public String prefix(); /* "java:comp" ... */ }
  public record JndiName(Namespace namespace, String path) {
      public static JndiName parse(String name) throws InvalidNameException;
      public boolean isRoot();                 // path empty
      public JndiName child(String relative);  // java:comp + "env/x" -> java:comp/env/x
      public boolean startsWith(JndiName prefix);
      @Override public String toString();      // "java:comp/env/jdbc/orders", root "java:comp"
  }
  ```
  `path` never starts or ends with `/` (a trailing `/` is dropped by `parse`).

- [ ] **Step 1: Write the failing tests** — `parse("java:comp/env/jdbc/orders")` gives `(COMP, "env/jdbc/orders")`
  and `toString()` round-trips; `parse("java:global/x")` is `GLOBAL`; `parse("java:comp/env/")` equals
  `parse("java:comp/env")`; `parse("java:comp")` is a root; `parse("java:other/x")`, `parse("comp/env/x")`,
  `parse("java:")`, `parse("java:comp//x")` throw `InvalidNameException`; `startsWith` true for
  `java:comp/env` vs `java:comp/env/jdbc/orders`, false for `java:comp/en` vs `java:comp/env`; a path keeps its
  dots (`java:comp/env/com.acme.Orders/ds`).
- [ ] **Step 2: Run** `./mvnw -ntp -pl vauban-naming test -Dtest=JndiNameTest` — expected: compilation failure.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — expected: green.
- [ ] **Step 5: Commit** `feat(naming): java: names in four namespaces`.

### Task 3: `JndiTarget`, and bean declarations as text

**Files:**
- Create: `vauban-naming/src/main/java/io/vidocq/vauban/naming/JndiTarget.java`,
  `BeanDeclarations.java` (package-private parser)
- Test: `vauban-naming/src/test/java/io/vidocq/vauban/naming/BeanDeclarationsTest.java`, test qualifiers
  `Region`, `Tier`, `Primary` in the test package (with `@Qualifier`, `@Retention(RUNTIME)`).

**Interfaces:**
- Consumes: `AnnotationInstanceFactory.find()` and `create(type, members, loader)` (Task 1); `JndiName.parse` (Task 2).
- Produces:
  ```java
  public sealed interface JndiTarget {
      record Bean(Class<?> type, Set<Annotation> qualifiers, String declaration) implements JndiTarget {}
      record Link(JndiName name) implements JndiTarget {}
      static JndiTarget bean(Class<?> type, Annotation... qualifiers);   // declaration = "Type @Q(...)" from toString
      static JndiTarget bean(String declaration, ClassLoader loader);    // IllegalArgumentException on any error
      static JndiTarget link(String name);                               // IllegalArgumentException wrapping InvalidNameException
  }
  ```
  An empty qualifier set means `@Default` (the lookup passes no qualifier to `select`). Grammar: spec section "Bean
  declarations as text". `@Named("x")` → `NamedLiteral.of("x")`, `@Default` → `Default.Literal.INSTANCE`,
  `@Any` → `Any.Literal.INSTANCE`, every other annotation through `AnnotationInstanceFactory`; no provider found
  → `IllegalArgumentException` "qualifier @<name> needs Vauban's annotation instances (vauban-core) on the module path".

- [ ] **Step 1: Write the failing tests**
  - `aTypeAloneIsTheDefaultBean`: `bean("java.lang.String", loader)` → `Bean(String.class, Set.of(), ...)`.
  - `theShortNamesAreJakartaQualifiers`: `@Named("orders")`, `@Default`, `@Any` give the three literals.
  - `aFullyQualifiedQualifierWithMembers`: `"java.lang.String @io.vidocq.vauban.naming.Region(\"eu\") @io.vidocq.vauban.naming.Tier(level = 2)"` → qualifiers equal `{Region("eu"), Tier(2)}` as JDK instances.
  - `spacesAroundTokensAreIgnored` (Review Focus 1): `"  java.lang.String   @Named( \"x\" )  "` equals the compact form.
  - `aStringMemberKeepsCommasParenthesesAtSignsAndEscapedQuotes` (Review Focus 2): `Region("a,b)@c")` and
    `Region("say \"hi\"")` keep their content.
  - Errors, each an `IllegalArgumentException` whose message contains the declaration: unknown class; `@Named` with no
    value; a short name other than the three (`@Primary`); an annotation that is not a qualifier is **not** checked
    here (see Task 5); an array literal `{"a"}` ("arrays are not supported"); unbalanced quote or parenthesis; empty
    declaration.
  - `aLinkParsesItsName`: `link("java:comp/env/jdbc/orders")` → `Link(JndiName(COMP, "env/jdbc/orders"))`;
    `link("jdbc/orders")` → `IllegalArgumentException`.
- [ ] **Step 2: Run** `./mvnw -ntp -pl vauban-naming test -Dtest=BeanDeclarationsTest` — expected: compilation failure.
- [ ] **Step 3: Implement** `BeanDeclarations.parse(String, ClassLoader) -> JndiTarget.Bean`: a hand-written tokenizer
  (type, then `@name`, optional `( ... )` split on top-level commas outside quotes, `name = literal` or a single
  `literal` for `value`). Literals pass to the factory as written (quotes included).
- [ ] **Step 4: Run** — expected: green.
- [ ] **Step 5: Commit** `feat(naming): name a bean, or another name, as a target`.

### Task 4: Declared names and the new default `NamingService`

**Files:**
- Create: `vauban-naming/src/main/java/io/vidocq/vauban/naming/JndiBindings.java`, `Binder.java`,
  `NameTable.java` (package-private: the table and its building)
- Modify: `DefaultNamingService.java` (rewrite), `NamingService.java` (Javadoc), `JndiNameProvider.java`
  (signature), `CdiContext.java` (only if needed: it keeps asking `isContext` then `lookup`)
- Delete: `DataSourceNames.java`; `module-info.java`: drop `requires java.sql` if nothing else needs it
- Modify tests: `JavaNamespaceTest`, `CdiContextTest`, `JavaNamingTest` and test beans (`QueueNames`,
  `RivalQueueNames`, `DependentQueueNames`): declare what they look up through a test `JndiBindings` bean
  (`OrdersNames`: `java:comp/env/jdbc/orders` → `DataSource @Named("orders")`) instead of `DataSourceNames`.
- Test: `vauban-naming/src/test/java/io/vidocq/vauban/naming/DeclaredNamesTest.java`

**Interfaces:**
- Consumes: Tasks 2-3.
- Produces:
  ```java
  public interface JndiBindings { void bind(Binder binder); }
  public interface Binder { Binder bind(String name, JndiTarget target); }   // IllegalArgumentException on an invalid name
  public interface JndiNameProvider {
      Optional<Object> lookup(JndiName name) throws NamingException;
      default boolean isContext(JndiName name) { return false; }
  }
  ```
  `DefaultNamingService` (`@ApplicationScoped`, constructor-injected `@Any Instance<JndiBindings>` and
  `@Any Instance<JndiNameProvider>`): builds its `NameTable` once, on the first `lookup`/`isContext` or in
  `void validate(@Observes Startup)` (Task 5), whichever comes first; `@Dependent` `JndiBindings` destroyed after
  `bind`. A name bound twice → `IllegalStateException` naming the name and both declaring classes (Task 5 turns it
  into a failed start; on a lookup before `Startup` it surfaces as a `NamingException`).

  Lookup order: table (bean → `CDI.current().select(type, qualifiers...).get()`; link → `lookup(link.toString())`),
  then providers (two answers → `NamingException` naming both), else `NameNotFoundException`.
  Contexts (spec "Contexts"): the four roots, the three `<ns>/env`, any proper prefix of a declared name, any name a
  provider's `isContext` accepts.

- [ ] **Step 1: Write the failing tests** in `DeclaredNamesTest` (each starts a `VaubanContainer` with
  `DefaultNamingService` and the test beans, as `JavaNamespaceTest.start` does):
  - `aDeclaredBeanNameAnswersTheBean`, `aDeclaredNameWithoutQualifierAnswersTheDefaultBean`.
  - `aLinkAnswersWhatItsTargetAnswers`, `aChainOfLinksIsFollowed`.
  - `theNamespacesAreDistinct`: `java:comp/env/jdbc/orders` declared; `java:module/env/jdbc/orders` and
    `java:app/env/jdbc/orders` → `NameNotFoundException`.
  - `aGlobalNameIsServed`: `java:global/orders` declared and answered.
  - `aDeclaredNamesParentsAreContexts`: `java:comp/env/jdbc` is a `Context`; `((Context) lookup("java:comp/env/jdbc")).lookup("orders")` answers.
  - `theEnvironmentsAreContextsWithNothingDeclared`: `java:comp/env`, `java:module/env`, `java:app/env`, `java:global` are `Context`s with no `JndiBindings` bean.
  - `aProviderAnswersAnUndeclaredName` and `aProviderIsNotAskedForADeclaredName` (a counting provider stays at 0).
  - `aProviderCanDeclareAContext`: `isContext(java:comp/env/jms)` true makes it a `Context`.
  - `aDependentBindingsBeanIsDestroyedOnceItHasBound` (`@PreDestroy` counter = 1 after the first lookup).
  - `aLookupBeforeStartupFindsTheDeclaredNames` (Review Focus 3): an `@ApplicationScoped` bean observing
    `@Initialized(ApplicationScoped.class)` looks up `java:comp/env/jdbc/orders` and stores the result; it is the bean.
  - `aNewContainerAnswersItsOwnDeclarations` (Review Focus 4): container 1 declares `java:comp/env/a`, is closed;
    container 2 declares only `java:comp/env/b`; through the same `InitialContext`, `a` → `NameNotFoundException`,
    `b` answers.
  - Adapt the existing tests; delete those of the removed behaviour (`theApplicationAndModuleNamespacesAreAliasesOfTheComponentOne`, the `jdbc/` rule).
- [ ] **Step 2: Run** `./mvnw -ntp -pl vauban-naming test` — expected: compilation failure, then red.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./mvnw -ntp -pl vauban-naming test` — expected: all green.
- [ ] **Step 5: Commit** `feat(naming)!: names are declared, in four distinct namespaces`.

### Task 5: Validation at startup

**Files:**
- Modify: `DefaultNamingService.java` (observer `void validate(@Observes Startup startup)`), `NameTable.java`
- Test: `vauban-naming/src/test/java/io/vidocq/vauban/naming/DeclarationValidationTest.java`

**Interfaces:**
- Consumes: Task 4. Uses `BeanManager.isQualifier`, `Instance.isResolvable/isAmbiguous`, `Bean.getScope()`.
- Produces: a failed `VaubanContainer.builder()...build()` (a `DeploymentException`, as Vauban turns a throwing
  `Startup` observer) whose message names the name, and for a bean target its declaration.

- [ ] **Step 1: Write the failing tests** — each asserts the build throws and the message contains the name:
  `aNameDeclaredTwiceFailsTheStart` (message names both `JndiBindings` classes), `anUnsatisfiedBeanFailsTheStart`,
  `anAmbiguousBeanFailsTheStart`, `aDependentBeanFailsTheStart`, `aLinkToAnUndeclaredNameFailsTheStart`,
  `aLoopOfLinksFailsTheStart` (a → b → a, message shows the cycle), `aNonQualifierAnnotationFailsTheStart`
  (`JndiTarget.bean(String.class, someNonQualifierAnnotation)`), `validDeclarationsStart`.
- [ ] **Step 2: Run** `./mvnw -ntp -pl vauban-naming test -Dtest=DeclarationValidationTest` — expected: red.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./mvnw -ntp -pl vauban-naming test` — expected: all green (whole module).
- [ ] **Step 5: Commit** `feat(naming): validate the declared names when the container starts`.

### Task 6: Vauban documentation, full build, pull request

**Files:**
- Modify: `docs/en/modules/ROOT/pages/jndi.adoc` (rewrite per spec "Documentation"), `whats-new.adoc`,
  `reference.adoc`, `index.adoc` (wherever they describe `vauban-naming` or the `jdbc/` rule); `README.md` of the repo
  if it mentions the `jdbc/` rule (`grep -rn "jdbc/" README.md docs/en`).

- [ ] **Step 1:** Rewrite `jndi.adoc`: lead; Getting started (dependency, `requires`, `installAsDefault`, an
  `OrdersNames` `JndiBindings` example); Names and namespaces (four namespaces, no fallback, contexts); Declaring
  names (`Binder`, `JndiTarget.bean`/`link`, the text grammar with a table of value forms); What is checked at
  startup (the list of Task 5, one line each); Names not known in advance (`JndiNameProvider`, `isContext`);
  Replacing the naming service; Installation and the JDK's own naming (unchanged); What changed since the first
  version (the three points of the spec); Not covered yet (literal values, `@Resource`, `resource-ref`,
  `@DataSourceDefinition`, listing). Keep the `[#providers]` anchor if other pages link it
  (`grep -rn "jndi.adoc#" docs`).
- [ ] **Step 2: Run** `grep -rn "jdbc/<name>\|DataSourceNames\|aliases of java:comp" docs/en vauban-naming/src/main` —
  expected: no line describing the removed rule.
- [ ] **Step 3: Run** `./mvnw -ntp clean install` from the root — expected: `BUILD SUCCESS`.
- [ ] **Step 4: Commit** `docs(naming): declared names, the four namespaces, the text grammar`.
- [ ] **Step 5:** Push `feat/naming-bindings`, open the Vauban PR with `tea pr create` (body ends with the Claude
  Code line and the session URL), wait for `pr-validate`, `governance-checks`, `sonar`, read the SonarQube comment,
  fix any new issue. Ask the maintainer to post `/merge`; wait for Vauban `main` to deploy the snapshot before
  merging Task 8's PR.

## Vidocq (worktree `vidocq/feat/naming-bindings`)

### Task 7: `vidocq.naming.*` keys become declarations

**Files:**
- Create: `vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-naming-extension/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/naming/ConfiguredNames.java`
  (a `JndiBindings` bean of the extension module, fed from a static holder like `MansartPoolHolder`, or registered
  with `VaubanContainerBuilder.addFactory`: implementer's choice)
- Modify: `NamingExtension.java` (read keys in `configure`, register the bean in `beforeStart`, report rows),
  the module's `module-info.java` (bean index / provider as the module already does)
- Test: `.../naming/NamingExtensionTest.java` (existing), new `ConfiguredNamesTest.java`

**Interfaces:**
- Consumes: Vauban `JndiBindings`, `Binder`, `JndiTarget.bean(String, ClassLoader)`, `JndiTarget.link(String)`.
- Produces: `static Map<String, String> NamingExtension.declarations(VidocqConfiguration config)` — JNDI name
  (`"java:" + rest`) → raw value, sorted by name; `IllegalStateException("unknown naming key vidocq.naming.<rest>: a
  name starts with comp/, module/, app/ or global/")` for any other key under `vidocq.naming.`.

- [ ] **Step 1: Write the failing tests**
  - `aKeyDeclaresTheNameAfterJava`: `vidocq.naming.comp/env/jdbc/orders` → `java:comp/env/jdbc/orders`.
  - `aValueStartingWithJavaIsALink`, `anyOtherValueIsABeanDeclaration` (through a recording `Binder`).
  - `aKeyOutsideTheFourNamespacesFailsTheStart` (Review Focus 5): `vidocq.naming.comp.env.toto` and
    `vidocq.naming.java:comp/env/toto` → `IllegalStateException` naming the key.
  - `theReportListsEachDeclaredName`: rows `java:comp/env/jdbc/legacy → java:comp/env/jdbc/orders` and
    `java:comp/env/toto → com.acme.Toto @Named("Toto")` after the "java: names" row.
  - `withoutKeysNothingIsDeclared`.
- [ ] **Step 2: Run** `./mvnw -ntp -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-naming-extension test` — expected: red.
- [ ] **Step 3: Implement.** Values are trimmed; bean declarations use the TCCL at `beforeStart`.
- [ ] **Step 4: Run** — expected: green.
- [ ] **Step 5: Commit** `feat(naming): declare java: names in vidocq.properties`.

### Task 8: Integration test, Vidocq documentation, pull request

**Files:**
- Modify: `vidocq-runtime-integration-tests/vidocq-runtime-it-naming/src/main/resources/vidocq.properties`,
  `NamingModulePathTest.java`, `NamingRestartModulePathTest.java`
- Modify: `docs/en/modules/ROOT/pages/modules/vidocq-runtime-extensions.adoc` (section `#naming`); the configuration
  properties page if one lists keys (`grep -rln "vidocq.pool.<name>" docs/en`).

- [ ] **Step 1: Write the failing test changes** — `vidocq.properties` gains
  `vidocq.naming.comp/env/jdbc/audit = javax.sql.DataSource @Named("audit")` and
  `vidocq.naming.app/env/jdbc/reporting = java:comp/env/jdbc/audit`; new test
  `an_alias_in_another_namespace_answers_the_same_pool` (`java:app/env/jdbc/reporting` is the audit bean);
  `java:module/env/jdbc/audit` → `NameNotFoundException` (`the_namespaces_are_distinct`).
- [ ] **Step 2: Run** `./mvnw -ntp -pl vidocq-runtime-integration-tests/vidocq-runtime-it-naming -am install` against
  the Vauban snapshot (or a local Vauban `install`) — expected: green once Task 7 is in; red without the keys.
- [ ] **Step 3:** Rewrite the `#naming` section: key format (`vidocq.naming.` + name without `java:`), the two value
  forms with the complete example of the spec, reserved keys, environment variables (override only), the startup
  report rows, the start failures (link to `xref:vauban::jndi.adoc`), no default data-source names.
- [ ] **Step 4: Run** `./mvnw -ntp clean install` from the root — expected: `BUILD SUCCESS`.
- [ ] **Step 5: Commit** `docs(naming): the vidocq.naming keys` (and the test changes in a `test(naming): ...`
  commit), push, open the Vidocq PR, checks green, SonarQube read; `/merge` by the maintainer only after Vauban's
  snapshot with Tasks 1-5 is deployed.
