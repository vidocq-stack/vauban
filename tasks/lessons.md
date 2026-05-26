# Lessons Learned

## 1. No preview features except finalized JDK 25 APIs
**Context**: The user explicitly refused `--enable-preview`.
**Rule**: Use only finalized JDK 25 APIs. `ScopedValue` (JEP 487) is finalized in JDK 25 and must be used instead of `ThreadLocal` for virtual-thread compatibility. No Stable Values (JEP 502), no Structured Concurrency (JEP 505).

## 2. Maven 4 RC is acceptable
**Context**: The user wants Maven 4.0.0-rc-5, not Maven 3.9.
**Rule**: Use Maven 4 features (POM model 4.1.0, automatic sub-project discovery, the new lifecycle). Do not propose a downgrade to Maven 3.

## 3. maven-plugin-plugin incompatible with JDK 25
**Context**: Phase 0 - plugin-plugin 3.15.1 does not support class file version 69 (JDK 25).
**Rule**: The vauban-maven-plugin module stays in `jar` packaging until maven-plugin-tools supports JDK 25. Do not use `<packaging>maven-plugin</packaging>` nor the `@Mojo` annotations.

## 4. Local sealed interfaces/classes are forbidden in Java
**Context**: Phase 0 - compilation error in SmokeTest with a local sealed interface.
**Rule**: Always declare sealed types as class members (nested) or top-level, never local within a method.

## 5. Empty packages with module-info exports
**Context**: Phase 0 - "package is empty or does not exist" error when a module exports a package that contains only package-info.java.
**Rule**: Every package exported in module-info.java must contain at least one concrete class (not just package-info.java).

## 6. Claude Code shell and SDKMAN
**Context**: The Claude Code shell does not automatically load the .sdkmanrc.
**Rule**: Always prefix Maven commands with `export MAVEN_HOME=~/.sdkman/candidates/maven/4.0.0-rc-5 && export PATH="$MAVEN_HOME/bin:$PATH" &&` to guarantee Maven 4.

## 7. AnnotationValue name conflict between model and JDK
**Context**: Phase 1 - `io.vidocq.vauban.indexer.model.AnnotationValue` and `java.lang.classfile.AnnotationValue` have the same simple name.
**Rule**: In `ClassFileScanner`, use FQNs for references to `java.lang.classfile.AnnotationValue` and its subtypes. Do not use a wildcard import for both packages.

## 8. JDK 25 Class-File API: symbol vs raw
**Context**: Phase 1 - `FieldModel.fieldType()` returns `Utf8Entry` (raw), `fieldTypeSymbol()` returns `ClassDesc` (type). Same for `MethodModel`.
**Rule**: Always use the `*Symbol()` methods (`fieldTypeSymbol()`, `methodTypeSymbol()`) to obtain the symbolic types.

## 9. Custom .claude/agents/ agents are not subagent_type
**Context**: Phase 1 - `subagent_type: "tdd-writer"` fails with "Agent type not found".
**Rule**: Custom agents are invoked differently (via @mention or directive). For subagents, use `general-purpose` with the agent's instructions in the prompt.

## 10. CDI lang model vs indexer model name conflicts
**Context**: Phase 2 - The CDI interfaces (`ClassInfo`, `FieldInfo`, `MethodInfo`, `AnnotationInfo`) have the same simple names as our indexer records.
**Rule**: In the lang model implementations, use FQNs or precise imports. Never wildcard-import both packages. Prefix `jakarta.enterprise.lang.model.declarations.ClassInfo` and `io.vidocq.vauban.indexer.model.ClassInfo` explicitly.

## 11. Do not assume the origin of commits
**Context**: The "Missing file to commit" commits were the user's, not the agents'.
**Rule**: Do not make assumptions about who made a commit. Check with the user before consolidating/rebasing.

## 12. CDI DefinitionException vs DeploymentException
**Context**: Phase 10 - The TCK is very strict about the type of exception thrown. Syntax/definition errors are `DefinitionException`, graph resolution problems (unsatisfied, ambiguous) are `DeploymentException`.
**Rule**: Always check the CDI spec (Section 2.8) for the expected exception type. In `VaubanContainer.builder().build()`, filter errors by `ValidationError.Kind` to throw the right Jakarta EE exception.

## 13. Disabled alternatives and bean discovery
**Context**: Phase 10 - `DisabledBeanNotAvailableForInjectionTest` failed because an alternative bean without `@Priority` was still discovered.
**Rule**: CDI 4.1 Section 5.1.1: an alternative is not available for injection if it is not enabled. It is preferable to exclude them as early as the `BeanDiscovery` phase to prevent them from polluting the `BeanResolver`.

## 14. Field injection on a normal scope MUST return the lazy client proxy
**Context**: VAU-INJ-001 (2026-05-07) — `@Inject OperationAudit audit` (or any `@TransactionScoped`/`@RequestScoped` bean) remained `null` after injection, causing an NPE on use.
**Root cause**: `VaubanContainer.getContextualInstance` called `context.get(contextual)` (without a `CreationalContext`, hence the "look up existing" version) BEFORE checking `isNormal()`. For an inactive scope (`@TransactionScoped` outside a TX, `@RequestScoped` outside a request), this `context.get()` calls `checkActive()`, which throws `ContextNotActiveException`. This exception was swallowed by `BeanInjector`'s catch, leaving the field `null`. Second problem: the catch-all in `InterceptorBeanWrapper.getOrCreateProxy` did the same — `ctx.get()` eagerly on an inactive scope.
**Rule**: For `isNormal()` beans, always return the client proxy directly — without ever calling `context.get()` at creation/injection time. The proxy resolves the context *at method-invocation time*, not at boot. The correct pattern: `if (scope.isNormal()) return interceptorWrapper.getOrCreateProxy(bean)` as the first statement of `getContextualInstance`, before any context access. The catch-all of `InterceptorBeanWrapper.getOrCreateProxy` must never degrade to `ctx.get()` for normal-scoped beans.
**Fix applied**: `VaubanContainer.getContextualInstance` (the `isNormal()` check moved above `context.get()`) + `InterceptorBeanWrapper.getOrCreateProxy` catch block (rethrow `DeploymentException` for normal scope instead of `ctx.get()`).
**Misstep (2026-05-07)**: The catch block in `getOrCreateProxy` threw `DeploymentException` for every `isNormal()` bean when the *bytecode proxy creation* failed. But `RuntimeClientProxyGenerator.generateProxyMethod` crashed silently for methods with array-type parameters (e.g. `Song[]`, `int[]`, `String...` varargs) because `Class.describeConstable()` returns `Optional.empty()` for those types, and the fallback `ClassDesc.of(type.getName())` failed because `Song[].class.getName()` returns `"[Lorg...Song;"` (JVM descriptor format, not class-name format). Result: 7 TCK failures on `EventTypesTest`, `MemberLevelInheritanceTest`, `InvokerAssignabilityTest`, `VarargsMethodInvokerTest`.
**Final correction**: Introduce a `classDescOf(Class<?>)` helper in `RuntimeClientProxyGenerator` using `ClassDesc.ofDescriptor(type.descriptorString())` as the fallback — this method accepts JVM descriptors. TCK 774/774 PASS recovered.

## 15. ClassDesc for array types in the Class-File API
**Context**: `RuntimeClientProxyGenerator` (VAU-INJ-001 follow-up, 2026-05-07).
**Cause**: `Class.describeConstable()` can return `Optional.empty()` for array types (e.g. `Song[].class`, `int[].class`). The fallback `ClassDesc.of(type.getName())` fails because `getName()` returns the JVM descriptor (`[Lpackage.Class;` or `[I`), which `ClassDesc.of()` does not recognize — it expects a binary name with `.`.
**Rule**: Always use `ClassDesc.ofDescriptor(type.descriptorString())` as the fallback for `describeConstable()`. `descriptorString()` returns the valid JVM descriptor format for all types (primitives, references, arrays). Correct pattern: `type.describeConstable().orElseGet(() -> ClassDesc.ofDescriptor(type.descriptorString()))`.
