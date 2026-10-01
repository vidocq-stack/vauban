# Codegen Coverage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every generated `_VaubanComponents` provider declares what it covers; the Vauban container tells, per bean,
observer and interceptor, which generator covers each operation and which falls back to reflection; the Vidocq dev
console shows it in its CDI tables, and calls everything outside the application a `library`.

**Architecture:** A new record `GeneratedCoverage` in `vauban-api` and a default method
`VaubanComponentProvider.coverage()`, rendered by both generators from the very lists that feed their dispatch
switches. A new `CodegenCoverage` in `vauban-core/container` enumerates the operations each row needs, with the keys
the runtime passes to providers, and matches them against the declarations. The Vidocq `CdiInventory` adds two
columns per table and one summary row per table.

**Tech Stack:** Java 25 (Class-File API, JEP 484), Maven 3.9.16, JUnit 5, CDI 4.1 Lite (Vauban), Antora docs.

**Spec:** `vauban/docs/superpowers/specs/2026-10-01-codegen-coverage-design.md`

## Global Constraints

- Java 25 + Maven 3.9.16: run `sdk env` in `vauban/` and in `vidocq/` before any command.
- English for code, Javadoc, comments, commit messages and every `.md`/`.adoc` file.
- In `vauban`, write **Java Modules** in prose and identifiers, never the abbreviation.
- No new `<dependency>` in any `pom.xml`.
- Runtime dispatch does not change: the container calls providers exactly as before.
- TDD: write the test, watch it fail for the stated reason, then write the code.
- Every new Java file starts with the 19-line license header of
  `vauban/vauban-api/src/main/java/io/vidocq/vauban/api/ProxyLink.java` (lines 1–19), unchanged.
- Commits: Conventional Commits, `git commit -s` (DCO sign-off; GPG signing is on via `commit.gpgsign`), message
  ending with the trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Vauban commits add
  `Related: #109`.
- `n/a` covers synthetic beans, synthetic observers and built-in beans; they are not evaluated.
- Never push or open a pull request without the maintainer's explicit go.

## Review Focus

1. **Nested bean classes**: a member class's key must be the string the provider's switch compares, so a nested bean
   shows `APT` only when its provider really handles it. Pinned in Task 2 (parity of `coverage()` with `create`,
   `injectField`, `invoke` and `createClientProxy` on a compiled provider with a nested bean).
2. **A package served by an old provider and a new one**: covered operations keep their generator; the others are
   `unknown`, not `reflection`. Pinned in Task 4 (`mixedPackageIsUnknownOnlyWhereUncovered`).
3. **A deployment with no provider at all** (class path, no APT): every row `reflection`, no exception. Pinned in
   Task 4 (`noProviderAtAllIsReflection`).
4. **A synthetic observer or an interceptor whose class cannot be loaded**: `n/a`, no exception. Pinned in Task 6.
5. **A provider too large for one `coverage()` method**: the Class-File generator fails with a message naming the
   provider, instead of an invalid class. Pinned in Task 3.

---

## Part A — vauban

Work in `/Users/yblazart/projects/perso/vidocq/vauban`, branch `feat/codegen-coverage` (already created from
`origin/main`, holds the spec commit).

### Task 1: The `GeneratedCoverage` SPI

**Files:**
- Create: `vauban-api/src/main/java/io/vidocq/vauban/api/GeneratedCoverage.java`
- Modify: `vauban-api/src/main/java/io/vidocq/vauban/api/VaubanComponentProvider.java` (append a default method
  before the closing brace)
- Test: `vauban-api/src/test/java/io/vidocq/vauban/api/GeneratedCoverageTest.java`

**Interfaces:**
- Produces: `record GeneratedCoverage(Generator generator, Set<String> instantiated, Set<String> injectedFields,
  Set<String> invokedMethods, Set<String> clientProxies)`, `enum GeneratedCoverage.Generator { APT, CLASS_FILE }`,
  `static GeneratedCoverage of(Generator, String[], String[], String[], String[])`,
  `default GeneratedCoverage VaubanComponentProvider.coverage()` returning `null`.

- [ ] **Step 1: Write the failing test**

```java
package io.vidocq.vauban.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("GeneratedCoverage")
class GeneratedCoverageTest {

    @Test
    @DisplayName("of() builds the sets a generated provider declares, a repeated key kept once")
    void ofBuildsTheDeclaredSets() {
        var coverage = GeneratedCoverage.of(GeneratedCoverage.Generator.APT,
                new String[] {"app.Foo", "app.Foo"}, new String[] {"app.Foo#repo"},
                new String[] {"app.Foo#init()"}, new String[] {"app.Foo_ClientProxy"});

        assertEquals(GeneratedCoverage.Generator.APT, coverage.generator());
        assertEquals(Set.of("app.Foo"), coverage.instantiated());
        assertEquals(Set.of("app.Foo#repo"), coverage.injectedFields());
        assertEquals(Set.of("app.Foo#init()"), coverage.invokedMethods());
        assertEquals(Set.of("app.Foo_ClientProxy"), coverage.clientProxies());
    }

    @Test
    @DisplayName("a coverage names its generator")
    void generatorIsRequired() {
        assertThrows(NullPointerException.class,
                () -> new GeneratedCoverage(null, Set.of(), Set.of(), Set.of(), Set.of()));
    }

    @Test
    @DisplayName("a provider that predates coverage() declares nothing")
    void providerDefaultIsNull() {
        VaubanComponentProvider provider = className -> null;
        assertNull(provider.coverage());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -ntp -pl vauban-api test -Dtest=GeneratedCoverageTest`
Expected: COMPILATION ERROR — `cannot find symbol: class GeneratedCoverage`.

- [ ] **Step 3: Write `GeneratedCoverage`**

```java
package io.vidocq.vauban.api;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What a generated {@link VaubanComponentProvider} runs in-module, as it declares it: the components it instantiates,
 * the fields it injects, the methods it invokes and the client proxies it creates, each keyed exactly as the
 * container asks for it. For diagnostics only — the container never dispatches on it.
 *
 * @param generator      the code generator that wrote the provider
 * @param instantiated   the class names {@link VaubanComponentProvider#create(String)} and
 *                       {@link VaubanComponentProvider#create(String, Object[])} accept
 * @param injectedFields {@code <declaring class>#<field>} for each field {@link VaubanComponentProvider#injectField}
 *                       assigns
 * @param invokedMethods {@code <declaring class>#<methodId>} for each method {@link VaubanComponentProvider#invoke}
 *                       calls, {@code methodId} being {@code name(paramErasure,…)}
 * @param clientProxies  the keys {@link VaubanComponentProvider#createClientProxy} accepts
 */
public record GeneratedCoverage(Generator generator, Set<String> instantiated, Set<String> injectedFields,
                                Set<String> invokedMethods, Set<String> clientProxies) {

    /** The code generator that wrote a provider. */
    public enum Generator {
        /** The annotation processor, {@code vauban-processor}, as Java source. */
        APT,
        /** The Class-File API, as bytecode: {@code vauban:generate}, {@code vauban:enhance-dependencies}. */
        CLASS_FILE
    }

    public GeneratedCoverage {
        Objects.requireNonNull(generator, "generator");
        instantiated = Set.copyOf(instantiated);
        injectedFields = Set.copyOf(injectedFields);
        invokedMethods = Set.copyOf(invokedMethods);
        clientProxies = Set.copyOf(clientProxies);
    }

    /**
     * The coverage a generated provider declares, from the arrays its generated code builds. A key may repeat: it is
     * kept once.
     */
    public static GeneratedCoverage of(Generator generator, String[] instantiated, String[] injectedFields,
                                       String[] invokedMethods, String[] clientProxies) {
        return new GeneratedCoverage(generator, Set.copyOf(List.of(instantiated)),
                Set.copyOf(List.of(injectedFields)), Set.copyOf(List.of(invokedMethods)),
                Set.copyOf(List.of(clientProxies)));
    }
}
```

- [ ] **Step 4: Add the default method to `VaubanComponentProvider`**

Insert after the `invoke` method, before the interface's closing brace:

```java

    /**
     * What this provider runs in-module — the components it instantiates, the fields it injects, the methods it
     * invokes and the client proxies it creates — for diagnostics such as the Vidocq dev console. The container never
     * dispatches on it. Both generators render it from the very lists that feed {@link #create}, {@link #injectField},
     * {@link #invoke} and {@link #createClientProxy}, so it cannot diverge from them.
     *
     * <p>The default returns {@code null}: a provider that predates this method declares nothing, and its coverage is
     * reported as unknown rather than as reflection.
     *
     * @return what this provider covers, or {@code null} if it does not say
     */
    default GeneratedCoverage coverage() {
        return null;
    }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw -ntp -pl vauban-api test -Dtest=GeneratedCoverageTest`
Expected: `Tests run: 3, Failures: 0, Errors: 0` and BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add vauban-api/src/main/java/io/vidocq/vauban/api/GeneratedCoverage.java \
        vauban-api/src/main/java/io/vidocq/vauban/api/VaubanComponentProvider.java \
        vauban-api/src/test/java/io/vidocq/vauban/api/GeneratedCoverageTest.java
git commit -s -F - <<'EOF'
feat(api): let a generated provider declare what it covers

GeneratedCoverage lists the components, fields, methods and client
proxies a provider runs in-module, keyed as the container asks for
them. VaubanComponentProvider.coverage() returns it; the default null
marks a provider that predates it.

Related: #109

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

### Task 2: The APT generator renders `coverage()`

**Files:**
- Modify: `vauban-processor/src/main/java/io/vidocq/vauban/processor/codegen/provider/ComponentProviderGenerator.java`
  (full `generateFrom` overload, before `sb.append(annotations.methods());`, and one new private helper)
- Test: `vauban-processor/src/test/java/io/vidocq/vauban/processor/codegen/provider/ComponentProviderGeneratorTest.java`
- Test: `vauban-processor/src/test/java/io/vidocq/vauban/processor/apt/ComponentProviderCompileTimeTest.java`

**Interfaces:**
- Consumes: `GeneratedCoverage.of(Generator, String[], String[], String[], String[])` (Task 1).
- Produces: every APT-generated `_VaubanComponents` overrides `coverage()` with `Generator.APT`.

- [ ] **Step 1: Write the failing source test** — add to `ComponentProviderGeneratorTest`:

```java
    @Test
    @DisplayName("coverage() declares the keys of every switch, as APT")
    void declaresItsCoverage() {
        var gen = ComponentProviderGenerator.generateFrom("app",
                List.of(new Component("app.Foo", List.of())),
                List.of(new FieldInject("app.Foo", "repo", "app.Repo")),
                List.of(new MethodInvoke("app.Foo", "init", List.of(), false, true, "void")),
                List.of("app.Foo_ClientProxy"),
                List.of(new ComponentProviderGenerator.ProducerProxy(
                        "java.util.ArrayList_ClientProxy", "app.ArrayList_ClientProxy")));
        var s = gen.source();

        assertTrue(s.contains("public io.vidocq.vauban.api.GeneratedCoverage coverage() {"), s);
        assertTrue(s.contains("io.vidocq.vauban.api.GeneratedCoverage.Generator.APT"), s);
        assertTrue(s.contains("new String[] {\"app.Foo\"}"), s);
        assertTrue(s.contains("new String[] {\"app.Foo#repo\"}"), s);
        assertTrue(s.contains("new String[] {\"app.Foo#init()\"}"), s);
        assertTrue(s.contains("new String[] {\"app.Foo_ClientProxy\", \"java.util.ArrayList_ClientProxy\"}"), s);
    }
```

- [ ] **Step 2: Write the failing compile test** — add to `ComponentProviderCompileTimeTest` (Review Focus 1):

```java
    @Test
    @DisplayName("the compiled provider declares exactly what its switches handle, a nested bean included")
    void compiledProviderCoverageMatchesItsSwitches() throws Exception {
        var result = compile("Greeter", """
                package app;

                @jakarta.enterprise.context.ApplicationScoped
                public class Greeter {
                    @jakarta.inject.Inject jakarta.enterprise.inject.spi.BeanManager beanManager;

                    @jakarta.inject.Inject
                    void setUp(jakarta.enterprise.inject.spi.BeanManager beanManager) {}

                    @jakarta.enterprise.context.Dependent
                    public static class Inner {}
                }
                """);
        assertTrue(result.success(), "compilation should succeed. Messages: " + result.messages());

        try (var loader = new java.net.URLClassLoader(new java.net.URL[] {result.outputDir().toUri().toURL()},
                getClass().getClassLoader())) {
            var provider = (io.vidocq.vauban.api.VaubanComponentProvider)
                    loader.loadClass("app._VaubanComponents").getDeclaredConstructor().newInstance();
            var coverage = provider.coverage();
            var greeter = loader.loadClass("app.Greeter").getDeclaredConstructor().newInstance();

            assertEquals(io.vidocq.vauban.api.GeneratedCoverage.Generator.APT, coverage.generator());
            assertTrue(coverage.instantiated().contains("app.Greeter"), coverage.toString());
            for (var name : List.of("app.Greeter", "app.Greeter$Inner", "app.Greeter.Inner")) {
                assertEquals(provider.create(name) != null, coverage.instantiated().contains(name), name);
            }
            assertEquals(provider.injectField(greeter, "app.Greeter", "beanManager", null),
                    coverage.injectedFields().contains("app.Greeter#beanManager"), coverage.toString());
            var setUp = "setUp(jakarta.enterprise.inject.spi.BeanManager)";
            assertEquals(provider.invoke(greeter, "app.Greeter", setUp, new Object[] {null})
                            != io.vidocq.vauban.api.VaubanComponentProvider.NOT_INVOKED,
                    coverage.invokedMethods().contains("app.Greeter#" + setUp), coverage.toString());
            assertEquals(provider.createClientProxy("app.Greeter_ClientProxy", () -> null) != null,
                    coverage.clientProxies().contains("app.Greeter_ClientProxy"), coverage.toString());
        }
    }
```

If the file lacks `import java.util.List;`, add it.

- [ ] **Step 3: Run both tests to verify they fail**

Run: `./mvnw -ntp -pl vauban-processor -am test -Dtest='ComponentProviderGeneratorTest,ComponentProviderCompileTimeTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `declaresItsCoverage` asserts the source contains `coverage() {`; the compile test gets a `null`
coverage and fails with a `NullPointerException` on `coverage.generator()`.

- [ ] **Step 4: Render `coverage()`** — in the full `generateFrom` overload, insert just before
`sb.append(annotations.methods());`:

```java
        // coverage(): what this provider runs in-module, from the same lists as the switches above, so the
        // declaration cannot diverge from the dispatch. Diagnostics only (Vidocq dev console).
        var proxyKeys = new java.util.ArrayList<>(clientProxyFqns);
        producerProxies.forEach(pp -> proxyKeys.add(pp.key()));
        sb.append("    @Override\n");
        sb.append("    public io.vidocq.vauban.api.GeneratedCoverage coverage() {\n");
        sb.append("        return io.vidocq.vauban.api.GeneratedCoverage.of(")
                .append("io.vidocq.vauban.api.GeneratedCoverage.Generator.APT,\n");
        sb.append("                ").append(stringArray(components.stream().map(Component::fqn).toList()))
                .append(",\n");
        sb.append("                ").append(stringArray(fieldInjects.stream()
                .map(fi -> fi.declaringClassFqn() + "#" + fi.fieldName()).toList())).append(",\n");
        sb.append("                ").append(stringArray(methodInvokes.stream()
                .map(mi -> mi.declaringClassFqn() + "#" + mi.methodId()).toList())).append(",\n");
        sb.append("                ").append(stringArray(proxyKeys)).append(");\n");
        sb.append("    }\n");
```

and add the helper at the end of the class, before its closing brace:

```java
    /** {@code new String[] {"a", "b"}}: the keys never hold a quote or a backslash (class and member names). */
    private static String stringArray(List<String> values) {
        return values.stream().map(value -> "\"" + value + "\"")
                .collect(Collectors.joining(", ", "new String[] {", "}"));
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw -ntp -pl vauban-processor -am test -Dtest='ComponentProviderGeneratorTest,ComponentProviderCompileTimeTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all tests of both classes pass, BUILD SUCCESS.

- [ ] **Step 6: Run the whole processor suite** (the generated source changed for every provider)

Run: `./mvnw -ntp -pl vauban-processor -am test`
Expected: BUILD SUCCESS, no failure.

- [ ] **Step 7: Commit**

```bash
git add vauban-processor/src/main/java/io/vidocq/vauban/processor/codegen/provider/ComponentProviderGenerator.java \
        vauban-processor/src/test/java/io/vidocq/vauban/processor/codegen/provider/ComponentProviderGeneratorTest.java \
        vauban-processor/src/test/java/io/vidocq/vauban/processor/apt/ComponentProviderCompileTimeTest.java
git commit -s -F - <<'EOF'
feat(processor): declare the coverage of every APT-generated provider

_VaubanComponents.coverage() lists, as APT, the keys its create,
injectField, invoke and createClientProxy switches handle, rendered
from the same lists. A test compiles a provider and checks the
declaration against the switches themselves, a nested bean included.

Related: #109

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

### Task 3: The Class-File generator emits `coverage()`

**Files:**
- Modify: `vauban-core/src/main/java/io/vidocq/vauban/core/provider/ComponentProviderClassGenerator.java`
- Test: `vauban-core/src/test/java/io/vidocq/vauban/core/provider/ComponentProviderClassGeneratorTest.java`

**Interfaces:**
- Consumes: `GeneratedCoverage.of(...)` (Task 1).
- Produces: every Class-File-generated provider overrides `coverage()` with `Generator.CLASS_FILE`;
  `public static final int MAX_COVERAGE_KEYS = 7000` on `ComponentProviderClassGenerator`.

- [ ] **Step 1: Write the failing tests** — add to `ComponentProviderClassGeneratorTest` (imports:
`io.vidocq.vauban.api.GeneratedCoverage`, `java.util.Set`, `java.util.stream.IntStream`,
`static org.junit.jupiter.api.Assertions.assertThrows`):

```java
    @Test
    @DisplayName("coverage() declares the keys of every switch, as CLASS_FILE")
    void declaresItsCoverage() throws Exception {
        String bean = ProvidedBean.class.getName();
        var gen = ComponentProviderClassGenerator.generate(
                "io.vidocq.vauban.core.provider._CoverageTestComponents",
                List.of(new Component(bean, List.of())),
                List.of(new FieldInject(bean, "greeting", "java.lang.String")),
                List.of(new MethodInvoke(bean, "hello", List.of(), false, false, "java.lang.String")),
                List.of(bean + "_ClientProxy"),
                List.of(new ComponentProviderClassGenerator.ProducerProxy("java.util.ArrayList_ClientProxy",
                        "io.vidocq.vauban.core.provider.ArrayList_ClientProxy")));

        var loader = new ByteClassLoader(getClass().getClassLoader());
        var provider = (VaubanComponentProvider) loader.define(gen.className(), gen.bytecode())
                .getDeclaredConstructor().newInstance();
        var coverage = provider.coverage();

        assertEquals(GeneratedCoverage.Generator.CLASS_FILE, coverage.generator());
        assertEquals(Set.of(bean), coverage.instantiated());
        assertEquals(Set.of(bean + "#greeting"), coverage.injectedFields());
        assertEquals(Set.of(bean + "#hello()"), coverage.invokedMethods());
        assertEquals(Set.of(bean + "_ClientProxy", "java.util.ArrayList_ClientProxy"), coverage.clientProxies());
    }

    @Test
    @DisplayName("a provider too large for one coverage() method fails with its name, not as an invalid class")
    void tooManyCoverageKeysFailClearly() {
        var components = IntStream.rangeClosed(0, ComponentProviderClassGenerator.MAX_COVERAGE_KEYS)
                .mapToObj(i -> new Component("big.Bean" + i, List.of())).toList();

        var failure = assertThrows(IllegalStateException.class,
                () -> ComponentProviderClassGenerator.generate("big._VaubanComponents", components));

        assertTrue(failure.getMessage().contains("big._VaubanComponents"), failure.getMessage());
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -ntp -pl vauban-core -am test -Dtest=ComponentProviderClassGeneratorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: COMPILATION ERROR — `cannot find symbol: variable MAX_COVERAGE_KEYS`.

- [ ] **Step 3: Add the constants and the guard** — in `ComponentProviderClassGenerator`, after `MTD_setDelegate`:

```java
    private static final ClassDesc CD_Coverage = ClassDesc.of("io.vidocq.vauban.api.GeneratedCoverage");
    private static final ClassDesc CD_Generator = ClassDesc.of("io.vidocq.vauban.api.GeneratedCoverage$Generator");
    private static final MethodTypeDesc MTD_coverage = MethodTypeDesc.of(CD_Coverage);
    private static final MethodTypeDesc MTD_coverageOf = MethodTypeDesc.of(CD_Coverage, CD_Generator,
            CD_String.arrayType(), CD_String.arrayType(), CD_String.arrayType(), CD_String.arrayType());

    /**
     * The most keys one {@code coverage()} method holds: each costs 8 bytes of bytecode ({@code dup}, index,
     * {@code ldc_w}, {@code aastore}), and a method body stops at 64 KiB.
     */
    public static final int MAX_COVERAGE_KEYS = 7000;
```

In the full `generate(...)` overload (the one taking `producerProxies`), insert as the first statements:

```java
        var instantiatedKeys = components.stream().map(Component::fqn).toList();
        var fieldKeys = fieldInjects.stream().map(fi -> fi.declaringClassFqn() + "#" + fi.fieldName()).toList();
        var methodKeys = methodInvokes.stream().map(mi -> mi.declaringClassFqn() + "#" + mi.methodId()).toList();
        var proxyKeys = new java.util.ArrayList<>(clientProxyFqns);
        producerProxies.forEach(pp -> proxyKeys.add(pp.key()));
        int keys = instantiatedKeys.size() + fieldKeys.size() + methodKeys.size() + proxyKeys.size();
        if (keys > MAX_COVERAGE_KEYS) {
            throw new IllegalStateException(providerClassName + " would declare " + keys + " keys in coverage(), "
                    + "more than the " + MAX_COVERAGE_KEYS + " one method holds: split the package");
        }
```

- [ ] **Step 4: Emit `coverage()`** — inside the `GeneratedClassFile.build(providerCD, clb -> { ... })` lambda, after
the `createClientProxy` block and before the lambda's closing `});`:

```java
            // public GeneratedCoverage coverage() {
            //     return GeneratedCoverage.of(Generator.CLASS_FILE, new String[] {…}, …);
            // }
            // From the same lists as the switches above, so the declaration cannot diverge from the dispatch.
            clb.withMethodBody("coverage", MTD_coverage, ClassFile.ACC_PUBLIC, cob -> {
                cob.getstatic(CD_Generator, "CLASS_FILE", CD_Generator);
                pushStringArray(cob, instantiatedKeys);
                pushStringArray(cob, fieldKeys);
                pushStringArray(cob, methodKeys);
                pushStringArray(cob, proxyKeys);
                cob.invokestatic(CD_Coverage, "of", MTD_coverageOf);
                cob.areturn();
            });
```

and add the helper next to `loadIntConstant`:

```java
    /** Pushes {@code new String[] {values…}} on the stack. */
    private static void pushStringArray(java.lang.classfile.CodeBuilder cob, List<String> values) {
        loadIntConstant(cob, values.size());
        cob.anewarray(CD_String);
        for (int i = 0; i < values.size(); i++) {
            cob.dup();
            loadIntConstant(cob, i);
            cob.ldc(values.get(i));
            cob.aastore();
        }
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw -ntp -pl vauban-core -am test -Dtest=ComponentProviderClassGeneratorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all tests of the class pass, BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add vauban-core/src/main/java/io/vidocq/vauban/core/provider/ComponentProviderClassGenerator.java \
        vauban-core/src/test/java/io/vidocq/vauban/core/provider/ComponentProviderClassGeneratorTest.java
git commit -s -F - <<'EOF'
feat(core): declare the coverage of every Class-File-generated provider

The bytecode provider of vauban:generate and enhance-dependencies now
emits coverage(), as CLASS_FILE, from the lists its switches use. A
provider whose keys would overflow the method fails with its name.

Related: #109

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

### Task 4: `CodegenCoverage` for managed beans, and the container entry point

**Files:**
- Create: `vauban-core/src/main/java/io/vidocq/vauban/core/container/CodegenCoverage.java`
- Modify: `vauban-core/src/main/java/io/vidocq/vauban/core/container/VaubanContainer.java` (two methods)
- Modify: `vauban-core/src/main/java/io/vidocq/vauban/core/container/BeanInjector.java` (extract the member order)
- Modify: `vauban-core/src/main/java/io/vidocq/vauban/core/container/InterceptorBeanWrapper.java` (record the
  intercepted subclasses; `resolveProxyTargetClass` becomes static)
- Create: `vauban-core/src/test/java/io/vidocq/vauban/core/container/coverage/CoverageFixtures.java`
- Create: `vauban-core/src/test/java/io/vidocq/vauban/core/container/coverage/legacy/LegacyBean.java`
- Create: `vauban-core/src/test/java/io/vidocq/vauban/core/container/coverage/legacy/LegacyProvider.java`
- Test: `vauban-core/src/test/java/io/vidocq/vauban/core/container/coverage/CodegenCoverageTest.java`

**Interfaces:**
- Consumes: `VaubanComponentProvider.coverage()`, `GeneratedCoverage` (Task 1); `ComponentProviders.providers()`,
  `VaubanLookup.methodId(Method)`, `BeanLifecycle.collectLifecycleMethodsInHierarchy(Class, Class)`,
  `RuntimeClientProxyGenerator.proxyClassName(Class)`, `InterceptedShape.SUBCLASS_SUFFIX` (existing).
- Produces:
  - `public final class CodegenCoverage` with `public Coverage of(Bean<?>)`;
    `public enum Verdict { APT, CLASS_FILE, APT_AND_CLASS_FILE, PARTIAL, REFLECTION, UNKNOWN, NOT_APPLICABLE }`;
    `public record Coverage(Verdict verdict, List<String> byReflection)`;
    package-private `enum Kind { INSTANTIATE, INJECT_FIELD, INVOKE, CLIENT_PROXY, PRE_GENERATED, NONE }`,
    `record Operation(Kind kind, String key, String label, Class<?> owner)`, `List<Operation> operations(Bean<?>)`.
  - `public CodegenCoverage VaubanContainer.codegenCoverage()`;
    `Boolean VaubanContainer.interceptedSubclassPreGenerated(BeanId)`.
  - `static List<Member> BeanInjector.injectionOrder(Class<?>)`, `static List<Field> BeanInjector.injectedFields(Class<?>)`.
  - `Boolean InterceptorBeanWrapper.interceptedSubclassPreGenerated(BeanId)`;
    `static Class<?> InterceptorBeanWrapper.resolveProxyTargetClass(ManagedBean<?>)`.
  - Test fixtures `CoverageFixtures.{Dependency, CoveredBean, PrivateFieldBean, BareBean, ScopedBean, Audited,
    AuditInterceptor, AuditedBean}` and `CodegenCoverageTest.Declaring`, used by Tasks 5–7.

- [ ] **Step 1: Write the fixtures**

`CoverageFixtures.java`:

```java
package io.vidocq.vauban.core.container.coverage;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InterceptorBinding;
import jakarta.interceptor.InvocationContext;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The beans the codegen-coverage tests read; their keys are binary names, {@code CoverageFixtures$CoveredBean}. */
public final class CoverageFixtures {

    /** The binary-name prefix of every fixture. */
    public static final String PREFIX = CoverageFixtures.class.getName() + "$";

    private CoverageFixtures() {}

    @Dependent
    public static class Dependency {}

    @Dependent
    public static class CoveredBean {
        @Inject Dependency dependency;

        @Inject
        void setUp(Dependency dependency) {}

        @PostConstruct
        void init() {}
    }

    @Dependent
    public static class PrivateFieldBean {
        @Inject private Dependency dependency;
    }

    @Dependent
    public static class BareBean {}

    @ApplicationScoped
    public static class ScopedBean {
        public String hello() {
            return "hello";
        }
    }

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Audited {}

    @Audited @Interceptor @Priority(Interceptor.Priority.APPLICATION)
    public static class AuditInterceptor {
        @Inject Dependency dependency;

        @AroundInvoke
        public Object around(InvocationContext context) throws Exception {
            return context.proceed();
        }
    }

    @Audited @Dependent
    public static class AuditedBean {
        public void work() {}
    }
}
```

`legacy/LegacyBean.java`:

```java
package io.vidocq.vauban.core.container.coverage.legacy;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;

/** A bean whose package a provider predating coverage() serves. */
@Dependent
public class LegacyBean {
    @Inject BeanManager beanManager;
}
```

`legacy/LegacyProvider.java`:

```java
package io.vidocq.vauban.core.container.coverage.legacy;

import io.vidocq.vauban.api.VaubanComponentProvider;

/** A provider built before coverage() existed: it serves this package and declares nothing. */
public class LegacyProvider implements VaubanComponentProvider {
    @Override
    public Object create(String className) {
        return null;
    }
}
```

- [ ] **Step 2: Write the failing test** — `CodegenCoverageTest.java`:

```java
package io.vidocq.vauban.core.container.coverage;

import io.vidocq.vauban.api.GeneratedCoverage;
import io.vidocq.vauban.api.GeneratedCoverage.Generator;
import io.vidocq.vauban.api.VaubanComponentProvider;
import io.vidocq.vauban.core.container.CodegenCoverage.Coverage;
import io.vidocq.vauban.core.container.CodegenCoverage.Verdict;
import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.vauban.core.container.coverage.legacy.LegacyBean;
import io.vidocq.vauban.core.container.coverage.legacy.LegacyProvider;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static io.vidocq.vauban.core.container.coverage.CoverageFixtures.PREFIX;
import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("CodegenCoverage")
class CodegenCoverageTest {

    static final String COVERED = PREFIX + "CoveredBean";
    static final Set<String> COVERED_METHODS = Set.of(
            COVERED + "#setUp(" + PREFIX + "Dependency)", COVERED + "#init()");

    /** A provider that declares what it is given and runs nothing, so the container falls back as it would. */
    record Declaring(GeneratedCoverage coverage) implements VaubanComponentProvider {
        @Override
        public Object create(String className) {
            return null;
        }
    }

    static VaubanComponentProvider declaring(Generator generator, Set<String> instantiated, Set<String> fields,
                                             Set<String> methods, Set<String> proxies) {
        return new Declaring(new GeneratedCoverage(generator, instantiated, fields, methods, proxies));
    }

    static VaubanContainer container(List<VaubanComponentProvider> providers, Class<?>... beans) {
        var builder = VaubanContainer.builder().classLoader(CodegenCoverageTest.class.getClassLoader());
        providers.forEach(builder::addComponentProvider);
        for (Class<?> bean : beans) {
            builder.addBeanClass(bean);
        }
        return builder.build();
    }

    static Bean<?> bean(VaubanContainer container, Class<?> type) {
        BeanManager manager = container.getBeanManager();
        return manager.resolve(manager.getBeans(type));
    }

    static Coverage coverage(VaubanContainer container, Class<?> type) {
        return container.codegenCoverage().of(bean(container, type));
    }

    @Test
    @DisplayName("a bean whose every operation an APT provider declares is APT")
    void everyOperationDeclaredIsApt() {
        try (var container = container(List.of(declaring(Generator.APT, Set.of(COVERED),
                        Set.of(COVERED + "#dependency"), COVERED_METHODS, Set.of())),
                CoverageFixtures.Dependency.class, CoverageFixtures.CoveredBean.class)) {
            assertEquals(new Coverage(Verdict.APT, List.of()),
                    coverage(container, CoverageFixtures.CoveredBean.class));
        }
    }

    @Test
    @DisplayName("a bean a Class-File provider covers is CLASS_FILE")
    void classFileProviderIsNamed() {
        try (var container = container(List.of(declaring(Generator.CLASS_FILE, Set.of(COVERED),
                        Set.of(COVERED + "#dependency"), COVERED_METHODS, Set.of())),
                CoverageFixtures.Dependency.class, CoverageFixtures.CoveredBean.class)) {
            assertEquals(new Coverage(Verdict.CLASS_FILE, List.of()),
                    coverage(container, CoverageFixtures.CoveredBean.class));
        }
    }

    @Test
    @DisplayName("a bean both generators cover is APT_AND_CLASS_FILE")
    void bothGeneratorsAreNamed() {
        try (var container = container(List.of(
                        declaring(Generator.APT, Set.of(COVERED), Set.of(COVERED + "#dependency"), Set.of(), Set.of()),
                        declaring(Generator.CLASS_FILE, Set.of(), Set.of(), COVERED_METHODS, Set.of())),
                CoverageFixtures.Dependency.class, CoverageFixtures.CoveredBean.class)) {
            assertEquals(new Coverage(Verdict.APT_AND_CLASS_FILE, List.of()),
                    coverage(container, CoverageFixtures.CoveredBean.class));
        }
    }

    @Test
    @DisplayName("each operation no provider declares is named, in the order the container runs it")
    void uncoveredOperationsAreNamedInOrder() {
        try (var container = container(List.of(declaring(Generator.APT, Set.of(COVERED), Set.of(), Set.of(),
                        Set.of())),
                CoverageFixtures.Dependency.class, CoverageFixtures.CoveredBean.class)) {
            assertEquals(new Coverage(Verdict.PARTIAL, List.of("field dependency", "initializer setUp()",
                            "@PostConstruct init()")),
                    coverage(container, CoverageFixtures.CoveredBean.class));
        }
    }

    @Test
    @DisplayName("a private field no provider can assign makes the bean partial")
    void privateFieldIsPartial() {
        String bean = PREFIX + "PrivateFieldBean";
        try (var container = container(List.of(declaring(Generator.APT, Set.of(bean), Set.of(), Set.of(), Set.of())),
                CoverageFixtures.Dependency.class, CoverageFixtures.PrivateFieldBean.class)) {
            assertEquals(new Coverage(Verdict.PARTIAL, List.of("field dependency")),
                    coverage(container, CoverageFixtures.PrivateFieldBean.class));
        }
    }

    @Test
    @DisplayName("a bean no provider declares is reflection")
    void undeclaredBeanIsReflection() {
        try (var container = container(List.of(declaring(Generator.APT, Set.of(COVERED), Set.of(), Set.of(),
                        Set.of())),
                CoverageFixtures.BareBean.class)) {
            assertEquals(new Coverage(Verdict.REFLECTION, List.of("constructor")),
                    coverage(container, CoverageFixtures.BareBean.class));
        }
    }

    @Test
    @DisplayName("a deployment with no provider at all is reflection, row by row")
    void noProviderAtAllIsReflection() {
        try (var container = container(List.of(), CoverageFixtures.BareBean.class)) {
            assertEquals(new Coverage(Verdict.REFLECTION, List.of("constructor")),
                    coverage(container, CoverageFixtures.BareBean.class));
        }
    }

    @Test
    @DisplayName("a normal-scoped bean needs its client proxy")
    void normalScopedBeanNeedsItsClientProxy() {
        String bean = PREFIX + "ScopedBean";
        try (var container = container(List.of(declaring(Generator.APT, Set.of(bean), Set.of(), Set.of(), Set.of())),
                CoverageFixtures.ScopedBean.class)) {
            assertEquals(new Coverage(Verdict.PARTIAL, List.of("client proxy")),
                    coverage(container, CoverageFixtures.ScopedBean.class));
        }
        try (var container = container(List.of(declaring(Generator.APT, Set.of(bean), Set.of(), Set.of(),
                        Set.of(bean + "_ClientProxy"))),
                CoverageFixtures.ScopedBean.class)) {
            assertEquals(new Coverage(Verdict.APT, List.of()),
                    coverage(container, CoverageFixtures.ScopedBean.class));
        }
    }

    @Test
    @DisplayName("an intercepted bean is created through its subclass, defined at boot when not pre-generated")
    void interceptedBeanNeedsItsSubclass() {
        String subclass = PREFIX + "AuditedBean$$Intercepted";
        try (var container = container(List.of(declaring(Generator.APT, Set.of(subclass), Set.of(), Set.of(),
                        Set.of())),
                CoverageFixtures.Dependency.class, CoverageFixtures.AuditInterceptor.class,
                CoverageFixtures.AuditedBean.class)) {
            assertEquals(new Coverage(Verdict.PARTIAL, List.of("intercepted subclass")),
                    coverage(container, CoverageFixtures.AuditedBean.class));
        }
    }

    @Test
    @DisplayName("a built-in bean is not evaluated")
    void builtInBeanIsNotApplicable() {
        try (var container = container(List.of(), CoverageFixtures.BareBean.class)) {
            assertEquals(new Coverage(Verdict.NOT_APPLICABLE, List.of()), coverage(container, BeanManager.class));
        }
    }

    @Test
    @DisplayName("what a provider predating coverage() may cover is unknown, not reflection")
    void oldProviderMakesItsPackageUnknown() {
        try (var container = container(List.of(new LegacyProvider()), LegacyBean.class)) {
            assertEquals(new Coverage(Verdict.UNKNOWN, List.of("constructor", "field beanManager")),
                    coverage(container, LegacyBean.class));
        }
    }

    @Test
    @DisplayName("in a package an old and a new provider share, only the uncovered operations are unknown")
    void mixedPackageIsUnknownOnlyWhereUncovered() {
        try (var container = container(List.of(new LegacyProvider(), declaring(Generator.APT,
                        Set.of(LegacyBean.class.getName()), Set.of(), Set.of(), Set.of())),
                LegacyBean.class)) {
            assertEquals(new Coverage(Verdict.UNKNOWN, List.of("field beanManager")),
                    coverage(container, LegacyBean.class));
        }
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw -ntp -pl vauban-core -am test -Dtest=CodegenCoverageTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: COMPILATION ERROR — `cannot find symbol: class CodegenCoverage`.

- [ ] **Step 4: Extract the injection order in `BeanInjector`** — replace `performInjection` and
`injectFieldsByReflection` (lines 67–106) with:

```java
    void performInjection(Object instance, BeanDescriptor descriptor, CreationalContext<?> ctx) {
        var beanClass = unwrapInterceptedSubclass(instance.getClass());
        var ownerBean = container.findBeanForInstance(instance);
        var typeMapping = ManagedBean.buildTypeVariableMapping(beanClass);
        for (var member : injectionOrder(beanClass)) {
            if (member instanceof Field field) {
                injectSingleField(instance, field, descriptor, ctx, typeMapping);
            } else {
                injectSingleMethod(instance, (Method) member, descriptor, ctx, ownerBean, typeMapping);
            }
        }
    }

    /**
     * Field-only injection (used to wire {@code @Inject} fields of interceptor instances, which have
     * no initializer-method phase). Injects supertype fields before subtype fields; skips statics.
     */
    void injectFieldsByReflection(Object instance, BeanDescriptor descriptor, CreationalContext<?> parentCtx) {
        var beanClass = unwrapInterceptedSubclass(instance.getClass());
        var typeMapping = ManagedBean.buildTypeVariableMapping(beanClass);
        for (var field : injectedFields(beanClass)) {
            injectSingleField(instance, field, descriptor, parentCtx, typeMapping);
        }
    }

    /**
     * The members {@link #performInjection} injects, in its order, mandated by the Jakarta Dependency Injection
     * spec: supertype members before subtype members; within a class, the non-static {@code @Inject} fields, then
     * the non-static {@code @Inject} methods that no subtype overrides. {@link CodegenCoverage} reads the same list.
     */
    static List<java.lang.reflect.Member> injectionOrder(Class<?> beanClass) {
        var hierarchy = hierarchySuperFirst(unwrapInterceptedSubclass(beanClass));
        var members = new ArrayList<java.lang.reflect.Member>();
        for (int i = 0; i < hierarchy.size(); i++) {
            var clazz = hierarchy.get(i);
            for (var field : clazz.getDeclaredFields()) {
                if (field.isAnnotationPresent(jakarta.inject.Inject.class)
                        && !Modifier.isStatic(field.getModifiers())) {
                    members.add(field);
                }
            }
            var subclasses = hierarchy.subList(i + 1, hierarchy.size());
            for (var method : clazz.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(jakarta.inject.Inject.class)) continue;
                if (Modifier.isStatic(method.getModifiers())) continue;
                if (isOverriddenInSubclasses(method, subclasses)) continue;
                members.add(method);
            }
        }
        return members;
    }

    /** The fields of {@link #injectionOrder}, in its order: what {@link #injectFieldsByReflection} injects. */
    static List<Field> injectedFields(Class<?> beanClass) {
        return injectionOrder(beanClass).stream()
                .filter(Field.class::isInstance).map(Field.class::cast).toList();
    }
```

- [ ] **Step 5: Run the existing container tests to prove the refactor changes nothing**

Run: `./mvnw -ntp -pl vauban-core -am test -Dtest='*Injection*Test,*Initializer*Test,InterceptorBindingMemberTest,ComponentProviderRuntimeTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: the same tests pass as on `main` (compile errors only from `CodegenCoverageTest`; if the module does not
compile, temporarily move `CodegenCoverageTest.java` aside, run, then put it back).

- [ ] **Step 6: Record the intercepted subclasses in `InterceptorBeanWrapper`**

Add after the `proxyCache` field (line 50):

```java
    /**
     * Each intercepted bean, by id: whether its {@code $$Intercepted} subclass was pre-generated (Vauban APT or Maven
     * plugin) rather than generated and defined at boot. Read by {@link CodegenCoverage}; nothing dispatches on it.
     */
    private final java.util.Map<io.vidocq.vauban.core.bean.model.BeanId, Boolean> interceptedSubclasses =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Whether the bean {@code id}'s intercepted subclass was pre-generated; {@code null} if it is not intercepted. */
    Boolean interceptedSubclassPreGenerated(io.vidocq.vauban.core.bean.model.BeanId id) {
        return interceptedSubclasses.get(id);
    }
```

In `wrapInterceptedBeans`, change the subclass lookup (around line 633) from:

```java
                    Class<?> interceptedClass;
                    var interceptedName = beanClass.getName() + io.vidocq.vauban.core.interceptor.InterceptedShape.SUBCLASS_SUFFIX;
                    try {
```

to:

```java
                    Class<?> interceptedClass;
                    boolean preGenerated;
                    var interceptedName = beanClass.getName() + io.vidocq.vauban.core.interceptor.InterceptedShape.SUBCLASS_SUFFIX;
                    try {
```

then, just after `interceptedClass = Class.forName(interceptedName, false, beanClass.getClassLoader());`, add
`preGenerated = true;`, and as the first statement of `catch (ClassNotFoundException notPreGenerated) {` add
`preGenerated = false;`.

Before `container.beans.put(descriptor.id(), interceptedBean);` (around line 823) add:

```java
                    interceptedSubclasses.put(descriptor.id(), preGenerated);
```

Before `container.beans.put(descriptor.id(), ib2);` (around line 921) add:

```java
                        interceptedSubclasses.put(descriptor.id(), false);
```

Make `resolveProxyTargetClass` static — change `    Class<?> resolveProxyTargetClass(ManagedBean<?> bean) {`
to `    static Class<?> resolveProxyTargetClass(ManagedBean<?> bean) {` (its body reads only `bean`; its one caller, line
220, calls it unqualified).

- [ ] **Step 7: Add the container entry points** — in `VaubanContainer`, after `componentProviders()` (line 143–145):

```java

    /**
     * Which code generator covers what each bean, observer and interceptor needs, and what falls back to reflection —
     * for diagnostics such as the Vidocq dev console. Asks every provider for its coverage once; creates no bean.
     */
    public CodegenCoverage codegenCoverage() {
        return new CodegenCoverage(this);
    }

    /** Whether the bean {@code id}'s intercepted subclass was pre-generated; {@code null} if it is not intercepted. */
    Boolean interceptedSubclassPreGenerated(BeanId id) {
        return interceptorWrapper == null ? null : interceptorWrapper.interceptedSubclassPreGenerated(id);
    }
```

(`BeanId` is already imported: the `beans` map uses it.)

- [ ] **Step 8: Write `CodegenCoverage`** (managed beans now; producers, observers and interceptors come in Tasks 5 and
6, which replace the two `List.of()` branches and add two `of` overloads):

```java
package io.vidocq.vauban.core.container;

import io.vidocq.vauban.api.GeneratedCoverage;
import io.vidocq.vauban.api.VaubanComponentProvider;
import io.vidocq.vauban.core.interceptor.InterceptedShape;
import io.vidocq.vauban.core.proxy.RuntimeClientProxyGenerator;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.inject.spi.Bean;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Which code generator covers each operation a bean, an observer or an interceptor needs, and which operations fall
 * back to reflection: the build's answer, from what each generated {@link VaubanComponentProvider} declares in
 * {@link VaubanComponentProvider#coverage()}. For diagnostics, such as the Vidocq dev console.
 *
 * <p>It reads metadata only — declared fields and methods without {@code setAccessible}, and decisions the container
 * recorded at boot — creates no bean, reads no context and calls no provider method but {@code coverage()}, once per
 * provider when {@link VaubanContainer#codegenCoverage()} builds it.
 */
public final class CodegenCoverage {

    /** What covers a row, summed up. */
    public enum Verdict {
        /** Every operation runs code the annotation processor generated. */
        APT,
        /** Every operation runs code the Class-File API generated. */
        CLASS_FILE,
        /** Every operation runs generated code, from both generators. */
        APT_AND_CLASS_FILE,
        /** Some operations run generated code, the others fall back. */
        PARTIAL,
        /** No operation runs generated code. */
        REFLECTION,
        /** A provider predating {@code coverage()} serves an uncovered operation: whether it runs it is unknown. */
        UNKNOWN,
        /** Nothing to cover: a synthetic or built-in bean, or a synthetic observer. */
        NOT_APPLICABLE
    }

    /**
     * The coverage of one row.
     *
     * @param verdict      what covers it
     * @param byReflection the operations that fall back — or, for {@link Verdict#UNKNOWN}, those whose coverage is
     *                     unknown — such as {@code field logger} or {@code @PostConstruct init()}, in the order the
     *                     container runs them
     */
    public record Coverage(Verdict verdict, List<String> byReflection) {
        public Coverage {
            Objects.requireNonNull(verdict, "verdict");
            byReflection = List.copyOf(byReflection);
        }
    }

    /** How the container runs an operation: through which provider method, or none. */
    enum Kind {
        INSTANTIATE, INJECT_FIELD, INVOKE, CLIENT_PROXY,
        /** A class generated at build time and loaded as is: covered, with no generator of its own. */
        PRE_GENERATED,
        /** No provider method runs it: always reflection or generation at boot. */
        NONE
    }

    /**
     * One operation a row needs.
     *
     * @param kind  how the container runs it
     * @param key   the key the container passes to the provider for it, built as the container builds it
     * @param label how the dev console names it
     * @param owner the class whose package's provider would run it
     */
    record Operation(Kind kind, String key, String label, Class<?> owner) {}

    private record Declared(VaubanComponentProvider provider, GeneratedCoverage coverage) {}

    private final VaubanContainer container;
    private final List<Declared> declared;

    CodegenCoverage(VaubanContainer container) {
        this.container = container;
        this.declared = container.componentProviders().providers().stream()
                .map(provider -> new Declared(provider, provider.coverage())).toList();
    }

    /** The coverage of a bean the bean manager lists. */
    public Coverage of(Bean<?> bean) {
        return verdict(operations(bean));
    }

    List<Operation> operations(Bean<?> bean) {
        if (!(bean instanceof ManagedBean<?> managed) || managed.descriptor().kind() == null) {
            return List.of();
        }
        return switch (managed.descriptor().kind()) {
            case MANAGED -> managedOperations(managed);
            case PRODUCER_METHOD, PRODUCER_FIELD, SYNTHETIC -> List.of();
        };
    }

    private List<Operation> managedOperations(ManagedBean<?> bean) {
        Class<?> beanClass = bean.getBeanClass();
        var ops = new ArrayList<Operation>();
        Boolean preGenerated = container.interceptedSubclassPreGenerated(bean.descriptor().id());
        if (preGenerated == null) {
            ops.add(new Operation(Kind.INSTANTIATE, beanClass.getName(), "constructor", beanClass));
        } else {
            String subclass = beanClass.getName() + InterceptedShape.SUBCLASS_SUFFIX;
            ops.add(new Operation(Kind.INSTANTIATE, subclass, "constructor", beanClass));
            ops.add(new Operation(preGenerated ? Kind.PRE_GENERATED : Kind.NONE, subclass, "intercepted subclass",
                    beanClass));
        }
        for (Member member : BeanInjector.injectionOrder(beanClass)) {
            ops.add(member instanceof Field field ? field(field) : invoke((Method) member, "initializer"));
        }
        for (Method method : BeanLifecycle.collectLifecycleMethodsInHierarchy(beanClass, PostConstruct.class)) {
            ops.add(invoke(method, "@PostConstruct"));
        }
        for (Method method : BeanLifecycle.collectLifecycleMethodsInHierarchy(beanClass, PreDestroy.class)) {
            ops.add(invoke(method, "@PreDestroy"));
        }
        clientProxy(bean, ops);
        return ops;
    }

    /** A normal-scoped bean's client proxy, keyed as {@code InterceptorBeanWrapper.getOrCreateProxy} asks for it. */
    private static void clientProxy(ManagedBean<?> bean, List<Operation> ops) {
        var scope = bean.descriptor().scope();
        if (scope != null && scope.isNormal()) {
            Class<?> target = InterceptorBeanWrapper.resolveProxyTargetClass(bean);
            ops.add(new Operation(Kind.CLIENT_PROXY, RuntimeClientProxyGenerator.proxyClassName(target),
                    "client proxy", bean.getBeanClass()));
        }
    }

    private static Operation field(Field field) {
        Class<?> declaring = field.getDeclaringClass();
        return new Operation(Kind.INJECT_FIELD, declaring.getName() + "#" + field.getName(),
                "field " + field.getName(), declaring);
    }

    private static Operation invoke(Method method, String what) {
        Class<?> declaring = method.getDeclaringClass();
        return new Operation(Kind.INVOKE, declaring.getName() + "#" + VaubanLookup.methodId(method),
                what + " " + method.getName() + "()", declaring);
    }

    private static Operation none(String label, Class<?> owner) {
        return new Operation(Kind.NONE, "", label, owner);
    }

    private Coverage verdict(List<Operation> ops) {
        if (ops.isEmpty()) {
            return new Coverage(Verdict.NOT_APPLICABLE, List.of());
        }
        var generators = EnumSet.noneOf(GeneratedCoverage.Generator.class);
        var uncovered = new ArrayList<String>();
        var unknown = new ArrayList<String>();
        boolean anyCovered = false;
        for (Operation op : ops) {
            switch (op.kind()) {
                case PRE_GENERATED -> anyCovered = true;
                case NONE -> uncovered.add(op.label());
                default -> {
                    GeneratedCoverage.Generator generator = generatorOf(op);
                    if (generator != null) {
                        generators.add(generator);
                        anyCovered = true;
                    } else if (servedByUndeclaredProvider(op.owner())) {
                        unknown.add(op.label());
                    } else {
                        uncovered.add(op.label());
                    }
                }
            }
        }
        if (!unknown.isEmpty()) {
            return new Coverage(Verdict.UNKNOWN, unknown);
        }
        if (uncovered.isEmpty() && !generators.isEmpty()) {
            return new Coverage(generators.size() == 2 ? Verdict.APT_AND_CLASS_FILE
                    : generators.contains(GeneratedCoverage.Generator.APT) ? Verdict.APT : Verdict.CLASS_FILE,
                    List.of());
        }
        return new Coverage(anyCovered ? Verdict.PARTIAL : Verdict.REFLECTION, uncovered);
    }

    /** The generator of the first provider that declares {@code op}: the container asks providers in this order. */
    private GeneratedCoverage.Generator generatorOf(Operation op) {
        for (Declared candidate : declared) {
            GeneratedCoverage coverage = candidate.coverage();
            if (coverage != null && keys(coverage, op.kind()).contains(op.key())) {
                return coverage.generator();
            }
        }
        return null;
    }

    private static Set<String> keys(GeneratedCoverage coverage, Kind kind) {
        return switch (kind) {
            case INSTANTIATE -> coverage.instantiated();
            case INJECT_FIELD -> coverage.injectedFields();
            case INVOKE -> coverage.invokedMethods();
            case CLIENT_PROXY -> coverage.clientProxies();
            case PRE_GENERATED, NONE -> Set.of();
        };
    }

    /** Whether a provider predating {@code coverage()} lives in {@code owner}'s package: providers are per package. */
    private boolean servedByUndeclaredProvider(Class<?> owner) {
        String pkg = owner.getPackageName();
        return declared.stream().anyMatch(candidate -> candidate.coverage() == null
                && candidate.provider().getClass().getPackageName().equals(pkg));
    }
}
```

Note: `none(...)` is used from Task 5 on; the compiler only warns about an unused private method.

- [ ] **Step 9: Run the test to verify it passes**

Run: `./mvnw -ntp -pl vauban-core -am test -Dtest=CodegenCoverageTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Tests run: 12, Failures: 0, Errors: 0`.

If `interceptedBeanNeedsItsSubclass` fails because the container never intercepts `AuditedBean`, check the binding
resolves the way `InterceptorBindingMemberTest.memberValue` does (same annotations); do not weaken the assertion.

- [ ] **Step 10: Run the whole vauban-core suite**

Run: `./mvnw -ntp -pl vauban-core -am test`
Expected: BUILD SUCCESS.

- [ ] **Step 11: Commit**

```bash
git add vauban-core/src/main/java/io/vidocq/vauban/core/container/CodegenCoverage.java \
        vauban-core/src/main/java/io/vidocq/vauban/core/container/VaubanContainer.java \
        vauban-core/src/main/java/io/vidocq/vauban/core/container/BeanInjector.java \
        vauban-core/src/main/java/io/vidocq/vauban/core/container/InterceptorBeanWrapper.java \
        vauban-core/src/test/java/io/vidocq/vauban/core/container/coverage
git commit -s -F - <<'EOF'
feat(core): tell which generator covers each operation of a managed bean

VaubanContainer.codegenCoverage() matches the operations a managed bean
needs — constructor, intercepted subclass, fields, initializers,
lifecycle callbacks, client proxy — against what the providers declare,
with the keys the container passes them. BeanInjector's member order is
extracted so both read one rule; the interceptor wrapper records whether
each intercepted subclass was pre-generated.

Related: #109

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

### Task 5: Producers and disposers

**Files:**
- Modify: `vauban-core/src/main/java/io/vidocq/vauban/core/container/CodegenCoverage.java`
- Modify: `vauban-core/src/main/java/io/vidocq/vauban/core/container/DisposerInvoker.java` (record the wiring)
- Modify: `vauban-core/src/main/java/io/vidocq/vauban/core/container/VaubanContainer.java` (two helpers lose
  `private`)
- Modify: `vauban-core/src/test/java/io/vidocq/vauban/core/container/coverage/CoverageFixtures.java`
- Test: `vauban-core/src/test/java/io/vidocq/vauban/core/container/coverage/CodegenCoverageTest.java`

**Interfaces:**
- Consumes: `CodegenCoverage.Operation`, `invoke(...)`, `none(...)`, `clientProxy(...)` (Task 4).
- Produces: `DisposerDescriptor DisposerInvoker.disposerOf(BeanId)`; package-private
  `VaubanContainer.extractProducerMethodName(BeanId)` and `extractProducerFieldName(BeanId)`; fixtures
  `CoverageFixtures.{Widget, Gadget, Factory}`.

- [ ] **Step 1: Add the fixtures** — in `CoverageFixtures`, add (imports `jakarta.enterprise.inject.Disposes`,
`jakarta.enterprise.inject.Produces`):

```java
    public static class Widget {}

    public static class Gadget {}

    @Dependent
    public static class Factory {
        @Produces Gadget gadget = new Gadget();

        @Produces
        Widget widget() {
            return new Widget();
        }

        void dispose(@Disposes Widget widget) {}
    }
```

- [ ] **Step 2: Write the failing tests** — add to `CodegenCoverageTest`:

```java
    static final String FACTORY = PREFIX + "Factory";

    @Test
    @DisplayName("a producer method and its disposer are covered by the methods a provider invokes")
    void producerMethodAndDisposer() {
        Set<String> methods = Set.of(FACTORY + "#widget()", FACTORY + "#dispose(" + PREFIX + "Widget)");
        try (var container = container(List.of(declaring(Generator.APT, Set.of(), Set.of(), methods, Set.of())),
                CoverageFixtures.Factory.class)) {
            assertEquals(new Coverage(Verdict.APT, List.of()), coverage(container, CoverageFixtures.Widget.class));
        }
        try (var container = container(List.of(), CoverageFixtures.Factory.class)) {
            assertEquals(new Coverage(Verdict.REFLECTION, List.of("producer widget()", "disposer dispose()")),
                    coverage(container, CoverageFixtures.Widget.class));
        }
    }

    @Test
    @DisplayName("a producer field is always read by reflection: no provider method reads a field")
    void producerFieldIsReflection() {
        try (var container = container(List.of(declaring(Generator.APT, Set.of(FACTORY), Set.of(), Set.of(),
                        Set.of())),
                CoverageFixtures.Factory.class)) {
            assertEquals(new Coverage(Verdict.REFLECTION, List.of("producer field gadget")),
                    coverage(container, CoverageFixtures.Gadget.class));
        }
    }
```

- [ ] **Step 3: Run them to verify they fail**

Run: `./mvnw -ntp -pl vauban-core -am test -Dtest=CodegenCoverageTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — both report `NOT_APPLICABLE` (producers return no operation yet).

- [ ] **Step 4: Record the disposer wiring** — in `DisposerInvoker`, add after the constructor:

```java
    /** The disposer wired to each producer, by the producer's id. Read by {@link CodegenCoverage}. */
    private final java.util.Map<io.vidocq.vauban.core.bean.model.BeanId, DisposerDescriptor> wired =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** The disposer wired to {@code producer}, or {@code null} when it has none. */
    DisposerDescriptor disposerOf(io.vidocq.vauban.core.bean.model.BeanId producer) {
        return wired.get(producer);
    }
```

and in `wireDisposers`, just before `bean.setDestroyer((instance, ctx) -> callDisposer(instance, disposer, ctx));`:

```java
                wired.put(descriptor.id(), disposer);
```

- [ ] **Step 5: Open the producer-name helpers to the package** — in `VaubanContainer`, change
`private static String extractProducerMethodName(BeanId id) {` to `static String extractProducerMethodName(BeanId id) {`
and `private static String extractProducerFieldName(BeanId id) {` to `static String extractProducerFieldName(BeanId id) {`.

- [ ] **Step 6: Add the producer operations to `CodegenCoverage`** — replace the `operations(Bean<?>)` switch with:

```java
        return switch (managed.descriptor().kind()) {
            case MANAGED -> managedOperations(managed);
            case PRODUCER_METHOD -> producerMethodOperations(managed);
            case PRODUCER_FIELD -> producerFieldOperations(managed);
            case SYNTHETIC -> List.of();
        };
```

and add (import `io.vidocq.vauban.core.bean.model.DisposerDescriptor`):

```java
    private List<Operation> producerMethodOperations(ManagedBean<?> bean) {
        Class<?> declaring = bean.getBeanClass();
        String name = VaubanContainer.extractProducerMethodName(bean.descriptor().id());
        var ops = new ArrayList<Operation>();
        Method producer = producerMethod(declaring, name);
        ops.add(producer == null ? none("producer " + name + "()", declaring) : invoke(producer, "producer"));
        disposer(bean, declaring, ops);
        clientProxy(bean, ops);
        return ops;
    }

    private List<Operation> producerFieldOperations(ManagedBean<?> bean) {
        Class<?> declaring = bean.getBeanClass();
        var ops = new ArrayList<Operation>();
        ops.add(none("producer field " + VaubanContainer.extractProducerFieldName(bean.descriptor().id()), declaring));
        disposer(bean, declaring, ops);
        clientProxy(bean, ops);
        return ops;
    }

    private void disposer(ManagedBean<?> bean, Class<?> declaring, List<Operation> ops) {
        DisposerDescriptor disposer = container.disposerInvoker.disposerOf(bean.descriptor().id());
        if (disposer == null) {
            return;
        }
        Method method = disposerMethod(declaring, disposer);
        ops.add(method == null ? none("disposer " + disposer.methodName() + "()", declaring)
                : invoke(method, "disposer"));
    }

    /** The first declared method of that name, as {@code VaubanContainer.createProducerMethodFactory} takes it. */
    private static Method producerMethod(Class<?> declaring, String name) {
        for (Method method : declaring.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        return null;
    }

    /** The disposer's method, as {@code DisposerInvoker.callDisposer} takes it. */
    private static Method disposerMethod(Class<?> declaring, DisposerDescriptor disposer) {
        for (Method method : declaring.getDeclaredMethods()) {
            if (method.getName().equals(disposer.methodName())
                    && method.getParameterCount() > disposer.parameterIndex()) {
                return method;
            }
        }
        return null;
    }
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./mvnw -ntp -pl vauban-core -am test -Dtest=CodegenCoverageTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Tests run: 14, Failures: 0, Errors: 0`.

- [ ] **Step 8: Commit**

```bash
git add vauban-core/src/main/java/io/vidocq/vauban/core/container/CodegenCoverage.java \
        vauban-core/src/main/java/io/vidocq/vauban/core/container/DisposerInvoker.java \
        vauban-core/src/main/java/io/vidocq/vauban/core/container/VaubanContainer.java \
        vauban-core/src/test/java/io/vidocq/vauban/core/container/coverage
git commit -s -F - <<'EOF'
feat(core): cover producer methods, producer fields and disposers

A producer method and its disposer are covered by the methods a
provider invokes; a producer field never is, since no provider method
reads a field. DisposerInvoker records which disposer each producer got
when it wires them.

Related: #109

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

### Task 6: Observers and interceptors

**Files:**
- Modify: `vauban-core/src/main/java/io/vidocq/vauban/core/container/CodegenCoverage.java`
- Modify: `vauban-core/src/test/java/io/vidocq/vauban/core/container/coverage/CoverageFixtures.java`
- Test: `vauban-core/src/test/java/io/vidocq/vauban/core/container/coverage/CodegenCoverageTest.java`

**Interfaces:**
- Consumes: Task 4's `Operation`, `field`, `invoke`, `none`, `verdict`, `BeanInjector.injectedFields`.
- Produces: `public Coverage of(ObserverDescriptor)`, `public Coverage of(InterceptorDescriptor)`, package-private
  `List<Operation> operations(ObserverDescriptor)` and `operations(InterceptorDescriptor)`; fixtures
  `CoverageFixtures.{Ping, Pinger}`.

- [ ] **Step 1: Add the fixtures** — in `CoverageFixtures` (import `jakarta.enterprise.event.Observes`):

```java
    public record Ping(String value) {}

    @Dependent
    public static class Pinger {
        void onPing(@Observes Ping ping) {}
    }
```

- [ ] **Step 2: Write the failing tests** — add to `CodegenCoverageTest` (imports
`io.vidocq.vauban.core.bean.model.InterceptorDescriptor`, `io.vidocq.vauban.core.bean.model.ObserverDescriptor`,
`io.vidocq.vauban.indexer.model.DotName`, `io.vidocq.vauban.indexer.model.TypeInfo`):

```java
    static final String PINGER = PREFIX + "Pinger";
    static final String AUDIT = PREFIX + "AuditInterceptor";

    static ObserverDescriptor observer(VaubanContainer container, String declaringClass) {
        return container.eventDispatcher().observers().stream()
                .filter(observer -> observer.declaringClass().value().equals(declaringClass))
                .findFirst().orElseThrow();
    }

    static InterceptorDescriptor interceptor(VaubanContainer container, String type) {
        return container.interceptorManager().getInterceptors().stream()
                .filter(interceptor -> interceptor.interceptorClass().value().equals(type))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("an observer is covered by the method a provider invokes")
    void observerMethod() {
        Set<String> methods = Set.of(PINGER + "#onPing(" + PREFIX + "Ping)");
        try (var container = container(List.of(declaring(Generator.APT, Set.of(), Set.of(), methods, Set.of())),
                CoverageFixtures.Pinger.class)) {
            assertEquals(new Coverage(Verdict.APT, List.of()),
                    container.codegenCoverage().of(observer(container, PINGER)));
        }
        try (var container = container(List.of(), CoverageFixtures.Pinger.class)) {
            assertEquals(new Coverage(Verdict.REFLECTION, List.of("observer onPing()")),
                    container.codegenCoverage().of(observer(container, PINGER)));
        }
    }

    @Test
    @DisplayName("a synthetic observer is not evaluated")
    void syntheticObserverIsNotApplicable() {
        var synthetic = new ObserverDescriptor(DotName.of(PINGER), "synthetic",
                new TypeInfo.ClassType(DotName.of(PREFIX + "Ping")), List.of(), false, 0, null, null,
                (instance, qualifiers) -> {});
        try (var container = container(List.of(), CoverageFixtures.Pinger.class)) {
            assertEquals(new Coverage(Verdict.NOT_APPLICABLE, List.of()), container.codegenCoverage().of(synthetic));
        }
    }

    @Test
    @DisplayName("an interceptor's @AroundInvoke is always called by reflection, so the interceptor is partial")
    void interceptorAroundInvokeIsReflection() {
        try (var container = container(List.of(declaring(Generator.APT, Set.of(AUDIT),
                        Set.of(AUDIT + "#dependency"), Set.of(), Set.of())),
                CoverageFixtures.Dependency.class, CoverageFixtures.AuditInterceptor.class,
                CoverageFixtures.AuditedBean.class)) {
            assertEquals(new Coverage(Verdict.PARTIAL, List.of("@AroundInvoke around()")),
                    container.codegenCoverage().of(interceptor(container, AUDIT)));
        }
    }

    @Test
    @DisplayName("an interceptor whose class cannot be loaded is not evaluated")
    void unloadableInterceptorIsNotApplicable() {
        var missing = new InterceptorDescriptor(DotName.of("does.not.Exist"), Set.of(), "around", 1);
        try (var container = container(List.of(), CoverageFixtures.BareBean.class)) {
            assertEquals(new Coverage(Verdict.NOT_APPLICABLE, List.of()), container.codegenCoverage().of(missing));
        }
    }
```

- [ ] **Step 3: Run them to verify they fail**

Run: `./mvnw -ntp -pl vauban-core -am test -Dtest=CodegenCoverageTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: COMPILATION ERROR — `no suitable method found for of(ObserverDescriptor)`.

- [ ] **Step 4: Add the observer and interceptor operations** — in `CodegenCoverage` (imports
`io.vidocq.vauban.core.bean.model.InterceptorDescriptor`, `io.vidocq.vauban.core.bean.model.ObserverDescriptor`,
`io.vidocq.vauban.indexer.model.TypeInfo`, `jakarta.enterprise.event.Observes`, `jakarta.enterprise.event.ObservesAsync`,
`jakarta.interceptor.InvocationContext`, `java.lang.annotation.Annotation`), add after `of(Bean<?>)`:

```java
    /** The coverage of an observer method the event dispatcher lists. */
    public Coverage of(ObserverDescriptor observer) {
        return verdict(operations(observer));
    }

    /** The coverage of an interceptor the interceptor manager lists. */
    public Coverage of(InterceptorDescriptor interceptor) {
        return verdict(operations(interceptor));
    }

    List<Operation> operations(ObserverDescriptor observer) {
        if (observer.isSynthetic()) {
            return List.of();
        }
        Class<?> declaring = load(observer.declaringClass().value());
        if (declaring == null) {
            return List.of();
        }
        Method method = observerMethod(declaring, observer);
        return List.of(method == null ? none("observer " + observer.methodName() + "()", declaring)
                : invoke(method, "observer"));
    }

    List<Operation> operations(InterceptorDescriptor interceptor) {
        Class<?> type = load(interceptor.interceptorClass().value());
        if (type == null) {
            return List.of();
        }
        var ops = new ArrayList<Operation>();
        ops.add(new Operation(Kind.INSTANTIATE, type.getName(), "constructor", type));
        for (Field field : BeanInjector.injectedFields(type)) {
            ops.add(field(field));
        }
        Method own = ownPostConstruct(type);
        if (own != null) {
            ops.add(invoke(own, "@PostConstruct"));
        }
        // VaubanInvocationContext.InterceptorInvocation calls these by Method.invoke: no provider runs them (#109).
        if (interceptor.aroundInvokeMethod() != null) {
            ops.add(none("@AroundInvoke " + interceptor.aroundInvokeMethod() + "()", type));
        }
        if (interceptor.aroundConstructMethod() != null) {
            ops.add(none("@AroundConstruct " + interceptor.aroundConstructMethod() + "()", type));
        }
        for (Method method : lifecycleInterceptorMethods(type, PostConstruct.class)) {
            ops.add(none("@PostConstruct " + method.getName() + "(InvocationContext)", type));
        }
        for (Method method : lifecycleInterceptorMethods(type, PreDestroy.class)) {
            ops.add(none("@PreDestroy " + method.getName() + "(InvocationContext)", type));
        }
        return ops;
    }

    private Class<?> load(String name) {
        try {
            return container.loadClass(name);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    /**
     * The observer's method, as {@code EventDispatcher.findMethod} finds it, but by the declared event type: no event
     * is at hand. Up the hierarchy, a method of that name with an {@code @Observes} or {@code @ObservesAsync}
     * parameter of that type; failing that, the first such method of that name.
     */
    private static Method observerMethod(Class<?> declaring, ObserverDescriptor observer) {
        String event = rawName(observer.eventType());
        Method byName = null;
        for (Class<?> type = declaring; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(observer.methodName())) continue;
                for (var parameter : method.getParameters()) {
                    if (parameter.isAnnotationPresent(Observes.class)
                            || parameter.isAnnotationPresent(ObservesAsync.class)) {
                        if (event == null || parameter.getType().getName().equals(event)) {
                            return method;
                        }
                        if (byName == null) {
                            byName = method;
                        }
                    }
                }
            }
        }
        return byName;
    }

    private static String rawName(TypeInfo type) {
        return switch (type) {
            case TypeInfo.ClassType classType -> classType.name().value();
            case TypeInfo.ParameterizedType parameterized -> parameterized.rawType().value();
            case null, default -> null;
        };
    }

    /** An interceptor's own {@code @PostConstruct}, as {@code BeanLifecycle.callPostConstruct} takes it. */
    private static Method ownPostConstruct(Class<?> type) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.isAnnotationPresent(PostConstruct.class) && method.getParameterCount() == 0) {
                return method;
            }
        }
        return null;
    }

    /** The lifecycle callbacks an interceptor intercepts with: one {@link InvocationContext} parameter. */
    private static List<Method> lifecycleInterceptorMethods(Class<?> type, Class<? extends Annotation> annotation) {
        return BeanLifecycle.collectLifecycleMethodsInHierarchy(type, annotation).stream()
                .filter(method -> method.getParameterCount() == 1
                        && method.getParameterTypes()[0] == InvocationContext.class)
                .toList();
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw -ntp -pl vauban-core -am test -Dtest=CodegenCoverageTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Tests run: 18, Failures: 0, Errors: 0`.

- [ ] **Step 6: Commit**

```bash
git add vauban-core/src/main/java/io/vidocq/vauban/core/container/CodegenCoverage.java \
        vauban-core/src/test/java/io/vidocq/vauban/core/container/coverage
git commit -s -F - <<'EOF'
feat(core): cover observers and interceptors

An observer is covered by the method a provider invokes, found by its
declared event type. An interceptor's constructor, fields and own
@PostConstruct can be covered; its @AroundInvoke, @AroundConstruct and
lifecycle interceptor methods never are, until #109.

Related: #109

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

### Task 7: Key parity, full verification, documentation

**Files:**
- Test: `vauban-core/src/test/java/io/vidocq/vauban/core/container/CodegenCoverageParityTest.java`
- Modify: `docs/en/modules/ROOT/pages/internals.adoc` (the `_VaubanComponents` row, line 49–50)

**Interfaces:**
- Consumes: package-private `CodegenCoverage.Kind`, `Operation`, `operations(...)` (Tasks 4–6); fixtures.

- [ ] **Step 1: Write the parity test** — it must pass as soon as it compiles; a failure means a computed key is not
the one the container asks for, and the computation is what to fix:

```java
package io.vidocq.vauban.core.container;

import io.vidocq.vauban.api.VaubanComponentProvider;
import io.vidocq.vauban.core.container.coverage.CoverageFixtures;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("CodegenCoverage - the keys the container asks providers for")
class CodegenCoverageParityTest {

    /** Records every key the container asks for and runs nothing, so the container falls back as without it. */
    static final class Recording implements VaubanComponentProvider {
        final Set<String> instantiated = ConcurrentHashMap.newKeySet();
        final Set<String> injectedFields = ConcurrentHashMap.newKeySet();
        final Set<String> invokedMethods = ConcurrentHashMap.newKeySet();
        final Set<String> clientProxies = ConcurrentHashMap.newKeySet();

        @Override
        public Object create(String className) {
            instantiated.add(className);
            return null;
        }

        @Override
        public Object create(String className, Object[] args) {
            instantiated.add(className);
            return null;
        }

        @Override
        public boolean injectField(Object bean, String className, String fieldName, Object value) {
            injectedFields.add(className + "#" + fieldName);
            return false;
        }

        @Override
        public Object invoke(Object target, String className, String methodId, Object[] args) {
            invokedMethods.add(className + "#" + methodId);
            return NOT_INVOKED;
        }

        @Override
        public Object createClientProxy(String proxyClassName, Supplier<?> delegate) {
            clientProxies.add(proxyClassName);
            return null;
        }

        Set<String> asked(CodegenCoverage.Kind kind) {
            return switch (kind) {
                case INSTANTIATE -> instantiated;
                case INJECT_FIELD -> injectedFields;
                case INVOKE -> invokedMethods;
                case CLIENT_PROXY -> clientProxies;
                case PRE_GENERATED, NONE -> Set.of();
            };
        }
    }

    @Test
    @DisplayName("every key the coverage computes is one the container asked a provider for")
    void computedKeysAreAskedKeys() {
        var recording = new Recording();
        try (var container = VaubanContainer.builder()
                .classLoader(getClass().getClassLoader())
                .addComponentProvider(recording)
                .addBeanClass(CoverageFixtures.Dependency.class)
                .addBeanClass(CoverageFixtures.CoveredBean.class)
                .addBeanClass(CoverageFixtures.ScopedBean.class)
                .addBeanClass(CoverageFixtures.Factory.class)
                .addBeanClass(CoverageFixtures.Pinger.class)
                .addBeanClass(CoverageFixtures.AuditInterceptor.class)
                .addBeanClass(CoverageFixtures.AuditedBean.class)
                .build()) {
            BeanManager manager = container.getBeanManager();
            container.select(CoverageFixtures.CoveredBean.class);
            Bean<?> scoped = manager.resolve(manager.getBeans(CoverageFixtures.ScopedBean.class));
            ((CoverageFixtures.ScopedBean) manager.getReference(scoped, CoverageFixtures.ScopedBean.class,
                    manager.createCreationalContext(scoped))).hello();
            var widgets = manager.createInstance().select(CoverageFixtures.Widget.class);
            widgets.destroy(widgets.get());
            container.select(CoverageFixtures.AuditedBean.class).work();
            manager.getEvent().select(CoverageFixtures.Ping.class).fire(new CoverageFixtures.Ping("x"));

            var coverage = container.codegenCoverage();
            var operations = new ArrayList<CodegenCoverage.Operation>();
            for (Class<?> type : List.of(CoverageFixtures.CoveredBean.class, CoverageFixtures.ScopedBean.class,
                    CoverageFixtures.Widget.class, CoverageFixtures.AuditedBean.class)) {
                operations.addAll(coverage.operations(manager.resolve(manager.getBeans(type))));
            }
            container.eventDispatcher().observers().stream()
                    .filter(o -> o.declaringClass().value().equals(CoverageFixtures.Pinger.class.getName()))
                    .forEach(o -> operations.addAll(coverage.operations(o)));
            container.interceptorManager().getInterceptors().stream()
                    .filter(i -> i.interceptorClass().value().equals(CoverageFixtures.AuditInterceptor.class.getName()))
                    .forEach(i -> operations.addAll(coverage.operations(i)));

            assertFalse(operations.isEmpty(), "the fixtures need operations");
            for (var op : operations) {
                if (op.kind() == CodegenCoverage.Kind.NONE || op.kind() == CodegenCoverage.Kind.PRE_GENERATED) {
                    continue;
                }
                assertTrue(recording.asked(op.kind()).contains(op.key()),
                        op.label() + ": " + op.key() + " was never asked; asked " + recording.asked(op.kind()));
            }
        }
    }
}
```

- [ ] **Step 2: Run it**

Run: `./mvnw -ntp -pl vauban-core -am test -Dtest=CodegenCoverageParityTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. On a failure, the message names the operation and the keys actually asked: correct the key
computation in `CodegenCoverage` (or the runtime path it mirrors, named in its Javadoc), never the assertion.

- [ ] **Step 3: Update `internals.adoc`** — replace the `_VaubanComponents` row text:

```
| One per module. Implements `io.vidocq.vauban.api.VaubanComponentProvider`: instantiates the module's components in-module (`new X(…)`), injects fields, creates client proxies and invokes producers, observers, disposers and lifecycle callbacks — no reflection, no `opens`.
```

with:

```
| One per package. Implements `io.vidocq.vauban.api.VaubanComponentProvider`: instantiates the package's components in-module (`new X(…)`), injects fields, creates client proxies and invokes producers, observers, disposers and lifecycle callbacks — no reflection, no `opens`. Its `coverage()` declares all of these, keyed as the container asks for them; `VaubanContainer.codegenCoverage()` reads it to tell, per bean, observer and interceptor, which generator covers each operation and which falls back to reflection — what the Vidocq dev console shows.
```

- [ ] **Step 4: Full build from clean**

Run: `./mvnw -ntp clean install`
Expected: BUILD SUCCESS across the reactor.

- [ ] **Step 5: The TCKs** (the CI does not gate them, vauban#85)

Run: `./run-tck.sh`
Expected: both runners BUILD SUCCESS; `vauban-tck-runner/target/surefire-reports/` reports 774 tests, 0 failures,
0 errors, and the AtInject runner's reports 0 failures. Read the reports, not only the exit code.

- [ ] **Step 6: Commit**

```bash
git add vauban-core/src/test/java/io/vidocq/vauban/core/container/CodegenCoverageParityTest.java \
        docs/en/modules/ROOT/pages/internals.adoc
git commit -s -F - <<'EOF'
test(core): prove the coverage keys are the ones the container asks for

A recording provider runs the fixtures through the container and
checks every key CodegenCoverage computes was asked at run time. The
internals page says what coverage() declares, and that a provider is
generated per package.

Related: #109

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

- [ ] **Step 7: Hand over** — report to the maintainer: test counts, TCK counts, the branch's commits. Push and open
the pull request on CodeFloe only on their go.

---

## Part B — vidocq

Work in `/Users/yblazart/projects/perso/vidocq/vidocq`. Create the branch first:
`git fetch origin main && git switch -c feat/devconsole-codegen-coverage origin/main`.
Module: `vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension`
(below, `DC` stands for this directory; `PKG` for `src/{main,test}/java/io/vidocq/runtime/extensions/essentials/devconsole`).

### Task 8: `runtime` becomes `library`

Independent of Part A: it can be done and merged first.

**Files:**
- Modify: `DC/src/main/java/.../devconsole/CdiInventory.java` (constant, `from`, Javadoc)
- Modify: `DC/src/main/java/.../devconsole/CdiPanel.java` (`sides`)
- Modify: `DC/src/test/java/.../devconsole/CdiPanelTest.java`
- Modify: `docs/en/modules/ROOT/pages/dev-console.adoc` (the `cdi-panel` section)

- [ ] **Step 1: Update the test expectations** — in `CdiPanelTest`, replace every `"runtime"` cell with `"library"`
(lines 236, 237, 247, 258) and every `" of the runtime"` with `" of the libraries"` (lines 216, 218, 220).

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -ntp -pl vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension test -Dtest=CdiPanelTest`
Expected: FAIL — `expected: <library> but was: <runtime>` (and the `of the libraries` facts).

- [ ] **Step 3: Rename in the code**
  - `CdiInventory`: `private static final String RUNTIME = "runtime";` → `private static final String LIBRARY = "library";`;
    in `from(...)`, `RUNTIME` → `LIBRARY`; in the record Javadoc, `how the application's classes were told apart from
    the runtime's` → `how the application's classes were told apart from the libraries'`; in `ApplicationClasses`'
    Javadoc, `How the classes of the application are told apart from those of the runtime.` → `How the classes of the
    application are told apart from the libraries'.`
  - `CdiPanel.sides`: `" of the runtime"` → `" of the libraries"`.

- [ ] **Step 4: Run the test to verify it passes**

Run: the Step 2 command. Expected: PASS.

- [ ] **Step 5: Update the docs** — in `dev-console.adoc`, section `[#cdi-panel]`:
  - `how many of them are the application's and the runtime's;` → `how many of them are the application's, and how
    many come from a library — every class that is not the application's;`
  - `and an extension's beans, such as the MCP server's, are the runtime's.` → `and every other class — Vidocq's,
    an extension's such as the MCP server's, a third-party jar's — is a library's.`
  - In the three table bullets, `and where it comes from` → `` and where it comes from, `application` or `library` ``.

- [ ] **Step 6: Commit**

```bash
git add vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension \
        docs/en/modules/ROOT/pages/dev-console.adoc
git commit -s -F - <<'EOF'
feat(devconsole): call everything outside the application a library

The CDI panel's "from" column said "runtime" for every class outside
the application, the beans of a third-party jar such as langchain4j-cdi
included. It now says "library", and the boot facts count "the
libraries".

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

### Task 9: The `codegen` and `by reflection` columns

Needs Part A in the local Maven repository: `cd ../vauban && ./mvnw -ntp clean install && cd ../vidocq`.

**Files:**
- Modify: `DC/src/main/java/.../devconsole/CdiInventory.java`
- Modify: `DC/src/main/java/.../devconsole/CdiPanel.java`
- Modify: `DC/src/test/java/.../devconsole/CdiPanelTest.java`
- Modify: `docs/en/modules/ROOT/pages/dev-console.adoc`

**Interfaces:**
- Consumes: `VaubanContainer.codegenCoverage()`, `CodegenCoverage.of(Bean<?>|InterceptorDescriptor|ObserverDescriptor)`,
  `CodegenCoverage.Coverage`, `CodegenCoverage.Verdict` (Part A).
- Produces: `CdiInventory.Coverages` (record of three functions, `NONE`, `of(CodegenCoverage)`),
  `CdiInventory.of(beans, interceptors, observers, application, Coverages)`, record components `beanCodegen`,
  `interceptorCodegen`, `observerCodegen`.

- [ ] **Step 1: Update and add the tests** — in `CdiPanelTest`:

Facts (method `theBootFactsCount…`), replace the key list and add three assertions:

```java
        assertEquals(List.of("beans", "scopes", "interceptors", "decorators", "observers", "application",
                "beans codegen", "interceptors codegen", "observers codegen", "source"), List.copyOf(facts.keySet()));
        assertEquals(List.of("4 n/a"), facts.get("beans codegen"));
        assertEquals(List.of("2 n/a"), facts.get("interceptors codegen"));
        assertEquals(List.of("2 n/a"), facts.get("observers codegen"));
```

Tables: append `"codegen", "by reflection"` to each expected column list, and `"n/a", ""` to each expected row:

```java
        assertEquals(List.of("class", "kind", "scope", "qualifiers", "alternative", "from", "codegen",
                "by reflection"), beans.get("columns"));
        assertEquals(List.of(
                List.of("com.acme.Cart", "other", "request-scoped", "@Default", "no", "application", "n/a", ""),
                List.of("com.acme.Clock", "other", "application-scoped", "@Named(\"clock\")", "yes", "application",
                        "n/a", ""),
                List.of("java.lang.Integer", "other", "application-scoped", "@Default", "no", "library", "n/a", ""),
                List.of("java.lang.String", "other", "dependent", "@Default", "no", "library", "n/a", "")),
                rows(beans));
```

```java
        assertEquals(List.of("interceptor", "bindings", "priority", "from", "codegen", "by reflection"),
                interceptors.get("columns"));
        assertEquals(List.of(
                List.of("com.acme.Timed", "@Audit, @Timing", "10", "application", "n/a", ""),
                List.of("io.vidocq.runtime.Logged", "@Log", "disabled: no @Priority", "library", "n/a", "")),
                rows(interceptors));
```

```java
        assertEquals(List.of("event", "qualifiers", "observer", "mode", "from", "codegen", "by reflection"),
                observers.get("columns"));
        assertEquals(List.of(
                List.of("List<Order>", "@Paid", "com.acme.Orders#placed", "async", "application", "n/a", ""),
                List.of("Startup", "", "io.vidocq.runtime.Boot#started", "sync", "library", "n/a", "")),
                rows(observers));
```

New test (imports `io.vidocq.vauban.core.container.CodegenCoverage`):

```java
    @Test
    void theCodegenColumnsSayWhatCoversEachRowAndTheBootFactsCountThem() {
        Map<Class<?>, CodegenCoverage.Coverage> byClass = Map.of(
                com.acme.Cart.class, new CodegenCoverage.Coverage(CodegenCoverage.Verdict.APT, List.of()),
                com.acme.Clock.class, new CodegenCoverage.Coverage(CodegenCoverage.Verdict.PARTIAL,
                        List.of("field zone", "@PostConstruct start()")),
                String.class, new CodegenCoverage.Coverage(CodegenCoverage.Verdict.REFLECTION, List.of("constructor")),
                Integer.class, new CodegenCoverage.Coverage(CodegenCoverage.Verdict.UNKNOWN, List.of("constructor")));
        CdiInventory.Coverages coverages = new CdiInventory.Coverages(bean -> byClass.get(bean.getBeanClass()),
                CdiInventory.Coverages.NONE.interceptors(), CdiInventory.Coverages.NONE.observers());
        CdiInventory inventory = CdiInventory.of(List.of(
                        bean(String.class, Dependent.class, Default.Literal.INSTANCE),
                        bean(com.acme.Clock.class, ApplicationScoped.class, Default.Literal.INSTANCE),
                        bean(com.acme.Cart.class, RequestScoped.class, Default.Literal.INSTANCE),
                        bean(Integer.class, ApplicationScoped.class, Default.Literal.INSTANCE)),
                List.of(), List.of(), ACME, coverages);
        CdiPanel panel = new CdiPanel(inventory);

        List<List<String>> rows = rows(table(sampled(panel), "beans"));
        assertEquals(List.of(
                List.of("APT", ""),
                List.of("partial", "field zone, @PostConstruct start()"),
                List.of("unknown", "provider predates coverage: constructor"),
                List.of("reflection", "constructor")),
                rows.stream().map(row -> row.subList(6, 8)).toList());
        assertEquals(List.of("1 APT, 1 partial, 1 reflection, 1 unknown"),
                facts(contributed(panel)).get("beans codegen"));
    }
```

(The rows are sorted application first, then by class: `com.acme.Cart`, `com.acme.Clock`, `java.lang.Integer`,
`java.lang.String`.)

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -ntp -pl vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension test -Dtest=CdiPanelTest`
Expected: COMPILATION ERROR — `cannot find symbol: class Coverages`.

- [ ] **Step 3: Change `CdiInventory`** (imports `io.vidocq.vauban.core.container.CodegenCoverage`,
`java.util.EnumMap`, `java.util.function.Function`):

Record header and Javadoc — add three components and their `@param`s:

```java
 * @param applicationRule  how the application's classes were told apart from the libraries'
 * @param beanCodegen      how many beans each code-generation verdict has, such as {@code 41 APT, 3 partial}
 * @param interceptorCodegen the same for the interceptors
 * @param observerCodegen  the same for the observers
 */
record CdiInventory(Map<String, Integer> scopes, int beans, int applicationBeans, List<List<String>> beanRows,
                    int interceptors, int applicationInterceptors, List<List<String>> interceptorRows,
                    int observers, int applicationObservers, List<List<String>> observerRows,
                    String applicationRule, String beanCodegen, String interceptorCodegen, String observerCodegen) {
```

Columns and the `from` indexes, replacing the three column constants and `APPLICATION_FIRST`:

```java
    static final List<String> BEAN_COLUMNS = List.of("class", "kind", "scope", "qualifiers", "alternative", "from",
            "codegen", "by reflection");
    static final List<String> INTERCEPTOR_COLUMNS = List.of("interceptor", "bindings", "priority", "from", "codegen",
            "by reflection");
    static final List<String> OBSERVER_COLUMNS = List.of("event", "qualifiers", "observer", "mode", "from",
            "codegen", "by reflection");
    private static final int BEAN_FROM = BEAN_COLUMNS.indexOf("from");
    private static final int INTERCEPTOR_FROM = INTERCEPTOR_COLUMNS.indexOf("from");
    private static final int OBSERVER_FROM = OBSERVER_COLUMNS.indexOf("from");
```

The coverages record, after `ApplicationClasses`:

```java
    /**
     * How Vauban covers each row with generated code, as three functions so a test can hand rows of its own. Read
     * once with the inventory; only strings are kept.
     *
     * @param beans        the coverage of a bean
     * @param interceptors the coverage of an interceptor
     * @param observers    the coverage of an observer method
     */
    record Coverages(Function<Bean<?>, CodegenCoverage.Coverage> beans,
                     Function<InterceptorDescriptor, CodegenCoverage.Coverage> interceptors,
                     Function<ObserverDescriptor, CodegenCoverage.Coverage> observers) {

        private static final CodegenCoverage.Coverage NOT_EVALUATED =
                new CodegenCoverage.Coverage(CodegenCoverage.Verdict.NOT_APPLICABLE, List.of());

        /** Every row {@code n/a}. */
        static final Coverages NONE = new Coverages(bean -> NOT_EVALUATED, interceptor -> NOT_EVALUATED,
                observer -> NOT_EVALUATED);

        /** What {@code coverage} says of each row. */
        static Coverages of(CodegenCoverage coverage) {
            return new Coverages(coverage::of, coverage::of, coverage::of);
        }
    }
```

`read` passes the container's coverage:

```java
    static CdiInventory read(VaubanContainer container, ApplicationClasses application) {
        return of(container.getBeanManager().getBeans(Object.class, Any.Literal.INSTANCE),
                container.interceptorManager().getInterceptors(), container.eventDispatcher().observers(),
                application, Coverages.of(container.codegenCoverage()));
    }
```

`of` — keep a four-argument overload, and give the full one the coverages (replacing the current `of` body):

```java
    static CdiInventory of(Collection<? extends Bean<?>> beans, List<InterceptorDescriptor> interceptors,
                           List<ObserverDescriptor> observers, ApplicationClasses application) {
        return of(beans, interceptors, observers, application, Coverages.NONE);
    }

    /**
     * The inventory of these beans, interceptors and observers.
     *
     * @param beans        the enabled beans
     * @param interceptors the interceptors
     * @param observers    the observer methods
     * @param application  how to tell the application's classes apart
     * @param coverages    how Vauban covers each row with generated code
     */
    static CdiInventory of(Collection<? extends Bean<?>> beans, List<InterceptorDescriptor> interceptors,
                           List<ObserverDescriptor> observers, ApplicationClasses application, Coverages coverages) {
        Predicate<String> ofApplication = application.isApplication();
        Map<String, Integer> scopes = new TreeMap<>();
        List<List<String>> beanRows = new ArrayList<>();
        List<CodegenCoverage.Coverage> beanCoverages = new ArrayList<>();
        for (Bean<?> bean : beans) {
            String scope = scope(bean.getScope());
            scopes.merge(scope, 1, Integer::sum);
            String name = bean.getBeanClass().getName();
            CodegenCoverage.Coverage coverage = coverages.beans().apply(bean);
            beanCoverages.add(coverage);
            beanRows.add(List.of(name, kind(bean), scope, qualifiers(bean.getQualifiers()),
                    bean.isAlternative() ? "yes" : "no", from(ofApplication, name), codegen(coverage),
                    byReflection(coverage)));
        }
        List<List<String>> interceptorRows = new ArrayList<>();
        List<CodegenCoverage.Coverage> interceptorCoverages = new ArrayList<>();
        for (InterceptorDescriptor interceptor : interceptors) {
            String name = interceptor.interceptorClass().value();
            CodegenCoverage.Coverage coverage = coverages.interceptors().apply(interceptor);
            interceptorCoverages.add(coverage);
            interceptorRows.add(List.of(name, annotations(interceptor.bindings()),
                    interceptor.enabled() ? String.valueOf(interceptor.priority()) : "disabled: no @Priority",
                    from(ofApplication, name), codegen(coverage), byReflection(coverage)));
        }
        List<List<String>> observerRows = new ArrayList<>();
        List<CodegenCoverage.Coverage> observerCoverages = new ArrayList<>();
        for (ObserverDescriptor observer : observers) {
            String name = observer.declaringClass().value();
            List<DotName> qualifiers = observer.qualifiers().stream().map(QualifierInstance::annotationName)
                    .filter(qualifier -> !qualifier.equals(QualifierInstance.ANY_NAME)).toList();
            CodegenCoverage.Coverage coverage = coverages.observers().apply(observer);
            observerCoverages.add(coverage);
            observerRows.add(List.of(type(observer.eventType()), annotations(qualifiers),
                    name + "#" + observer.methodName(), observer.async() ? "async" : "sync",
                    from(ofApplication, name), codegen(coverage), byReflection(coverage)));
        }
        Map<String, Integer> byCount = new LinkedHashMap<>();
        scopes.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(entry -> byCount.put(entry.getKey(), entry.getValue()));
        return new CdiInventory(byCount, beanRows.size(), ofApplication(beanRows, BEAN_FROM),
                firstRows(beanRows, BEAN_FROM),
                interceptorRows.size(), ofApplication(interceptorRows, INTERCEPTOR_FROM),
                firstRows(interceptorRows, INTERCEPTOR_FROM),
                observerRows.size(), ofApplication(observerRows, OBSERVER_FROM),
                firstRows(observerRows, OBSERVER_FROM),
                application.rule(), codegenSummary(beanCoverages), codegenSummary(interceptorCoverages),
                codegenSummary(observerCoverages));
    }
```

The cell writers and the summary, next to `from`:

```java
    /** A verdict as the console writes it: {@code APT}, {@code Class-File}, {@code partial}, {@code n/a}… */
    static String codegen(CodegenCoverage.Coverage coverage) {
        return label(coverage.verdict());
    }

    private static String label(CodegenCoverage.Verdict verdict) {
        return switch (verdict) {
            case APT -> "APT";
            case CLASS_FILE -> "Class-File";
            case APT_AND_CLASS_FILE -> "APT + Class-File";
            case PARTIAL -> "partial";
            case REFLECTION -> "reflection";
            case UNKNOWN -> "unknown";
            case NOT_APPLICABLE -> "n/a";
        };
    }

    /** The operations that fall back, or that a provider predating coverage leaves unknown; empty when none. */
    static String byReflection(CodegenCoverage.Coverage coverage) {
        String operations = String.join(", ", coverage.byReflection());
        return coverage.verdict() == CodegenCoverage.Verdict.UNKNOWN
                ? "provider predates coverage: " + operations : operations;
    }

    /** {@code 41 APT, 3 partial, 20 reflection}: each verdict met, in the order of the verdicts; empty when none. */
    private static String codegenSummary(List<CodegenCoverage.Coverage> coverages) {
        Map<CodegenCoverage.Verdict, Integer> counts = new EnumMap<>(CodegenCoverage.Verdict.class);
        coverages.forEach(coverage -> counts.merge(coverage.verdict(), 1, Integer::sum));
        return counts.entrySet().stream().map(entry -> entry.getValue() + " " + label(entry.getKey()))
                .collect(Collectors.joining(", "));
    }
```

`ofApplication` and `firstRows` take the `from` column:

```java
    private static int ofApplication(List<List<String>> rows, int from) {
        return (int) rows.stream().filter(row -> APPLICATION.equals(row.get(from))).count();
    }

    /** The application's rows first, then by their first cell; the first {@value #MAX_ROWS}. */
    private static List<List<String>> firstRows(List<List<String>> rows, int from) {
        Comparator<List<String>> applicationFirst = Comparator
                .comparing((List<String> row) -> !APPLICATION.equals(row.get(from)))
                .thenComparing(row -> row.getFirst());
        return rows.stream().sorted(applicationFirst).limit(MAX_ROWS).toList();
    }
```

- [ ] **Step 4: Change `CdiPanel`** — in `contribute`, after `.row("application", held.applicationRule());` (end the
chain there with `;` if it was not already) add:

```java
        codegen(section, "beans", held.beanCodegen());
        codegen(section, "interceptors", held.interceptorCodegen());
        codegen(section, "observers", held.observerCodegen());
```

and the helper next to `leftOut`:

```java
    private static void codegen(StartupReportSection section, String table, String summary) {
        if (!summary.isEmpty()) {
            section.row(table + " codegen", summary);
        }
    }
```

In the class Javadoc, add to the boot facts: `{@code beans codegen}, {@code interceptors codegen}, {@code observers
codegen}: how many rows each code-generation verdict has;` and to the tables: `then, for every row, codegen (the
generator that covers it, partial, reflection, unknown or n/a) and by reflection (what falls back)`.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw -ntp -pl vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension test`
Expected: BUILD SUCCESS, `CdiPanelTest` green.

- [ ] **Step 6: Update the docs** — in `dev-console.adoc`, section `[#cdi-panel]`, after the `application` boot fact,
add:

```
** `beans codegen`, `interceptors codegen`, `observers codegen`: how many rows each code-generation verdict has, such as `41 APT, 12 Class-File, 3 partial, 20 reflection`;
```

and after the three table bullets, before `A table with no row reads none.`, add:

```
Every table ends with two columns that say whether a row runs code generated at build time or falls back to reflection, as Vauban reads it from what each generated `_VaubanComponents` provider declares:

* `codegen`: `APT` when the annotation processor generated everything the row needs, `Class-File` when `vauban:generate` or `vidocq:generate` did, `APT + Class-File` for both, `partial` when some of it falls back, `reflection` when all of it does, `unknown` when a provider built before Vauban declared its coverage serves it — rebuild that jar — and `n/a` for synthetic and built-in beans;
* `by reflection`: what falls back, such as `field logger`, `@PostConstruct init()` or `client proxy`. An interceptor's `@AroundInvoke` always does today (https://codefloe.com/Vidocq/vauban/issues/109[vauban#109]), and so does a producer field.
```

- [ ] **Step 7: Commit**

```bash
git add vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension \
        docs/en/modules/ROOT/pages/dev-console.adoc
git commit -s -F - <<'EOF'
feat(devconsole): show which generator covers each CDI row

The beans, interceptors and observers tables gain a "codegen" column
(APT, Class-File, partial, reflection, unknown, n/a) and a "by
reflection" column naming what falls back, from Vauban's
CodegenCoverage. The boot facts count the rows of each verdict.

Spec: vauban docs/superpowers/specs/2026-10-01-codegen-coverage-design.md

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

### Task 10: End-to-end check on the LangChain4j MCP tasks server

No code. The application of the original question: `/Users/yblazart/projects/perso/vidocq-tools/lc4jcdi-on-vidocq/mcp-tasks-server`.

- [ ] **Step 1: Install vidocq** — `cd /Users/yblazart/projects/perso/vidocq/vidocq && sdk env && ./mvnw -ntp clean install -DskipTests`
  (its tests ran in Task 9; this only puts the plugin and the console in the local repository).
- [ ] **Step 2: Start the application** — `cd /Users/yblazart/projects/perso/vidocq-tools/lc4jcdi-on-vidocq/mcp-tasks-server && ./mvnw -ntp clean vidocq:dev`
  (`clean`: a stale `target/` hides codegen changes).
- [ ] **Step 3: Read the CDI tab** of the dev console (URL in the log). Expected:
  - `io.vidocq.tools.lc4jcdi.mcptasks.*`: `from` = `application`, `codegen` = `APT` or `partial` with the reason named;
  - `dev.langchain4j.cdi.mcp.*`: `from` = `library`, `codegen` = `Class-File`, `partial` or `reflection` (what
    `vidocq:generate` covered in that third-party jar);
  - Vidocq bricks not rebuilt since Part A: `unknown`, `provider predates coverage: …`;
  - the summary rows `beans codegen`, `interceptors codegen`, `observers codegen` add up to each table's total.
- [ ] **Step 4: Report** the counts and any row whose verdict looks wrong, with its `by reflection` text, to the
  maintainer. A wrong verdict is a bug: log it with `/log-bug` in `vauban/BUG.md`.
