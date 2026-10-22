# Naming bindings: the `java:` names an integrator declares, in four distinct namespaces

- **Date:** 2026-10-22
- **Status:** design approved, spec under review
- **Repositories:** `vauban` (`vauban-naming`, `vauban-api`, `vauban-core`), then `vidocq`
  (`vidocq-runtime-naming-extension`)
- **Related:** vidocq#297 (the read-only `java:` namespace, merged as vauban#175, vauban#176, vidocq#299)

## Context

`vauban-naming` (vauban#175) answers the JNDI `java:` names from the running CDI container. Two choices of that
first version no longer hold:

1. **A built-in rule.** `DataSourceNames`, a `JndiNameProvider` bean of the module, answers
   `java:comp/env/jdbc/<name>` with the `@Named("<name>")` `DataSource` bean. An application cannot turn it off,
   rename it, or point a name at a bean of another type or with other qualifiers.
2. **One namespace.** `java:module/env` and `java:app/env` are aliases of `java:comp/env`
   (`DefaultNamingService.ENVIRONMENTS`), and `java:global` answers nothing. That is right for one application made
   of one component, and wrong as soon as Vidocq hosts several modules (Jakarta EE profiles, several web modules).

Nothing of `vauban-naming` has been released yet (it is on `0.4.0-SNAPSHOT`), so both can change without a
deprecation path.

## Intent

Vauban provides the mechanism and no policy: an API to declare what each `java:` name is bound to, in the four
namespaces of the Jakarta EE Platform, validated when the container starts. Whoever assembles the application
declares the names: the Vidocq naming extension from `vidocq.properties`, a Java SE application or a library in
code. This keeps every choice in the integrator's hands and lets the namespaces grow (several modules, several
applications) without changing the API.

## Goals

- `java:comp`, `java:module`, `java:app` and `java:global` are four distinct namespaces. A name exists only in the
  namespace it is declared in: no fallback from one to another (Jakarta EE Platform, EE.5.2.2).
- A name is bound to one of two targets:
  - **a bean**: a type and its qualifiers, none meaning `@Default`, resolved in the running container at each
    lookup;
  - **a link**: another `java:` name, looked up in its turn (an alias, chains allowed).
- Declarations are validated when the container starts; a wrong one fails the start, naming the declaration.
- A bean target can be written as text (`com.acme.Orders @com.acme.Region("eu")`), qualifiers with members
  included, so that a configuration file can declare it.
- The Vidocq naming extension declares names from `vidocq.properties`:
  `vidocq.naming.<name without "java:"> = <target>`.
- Documentation: the Vauban page `jndi.adoc` and the Vidocq extension section are rewritten for the new model.

## Non-goals

- More than one application, module or component at a time. The namespaces are distinct in the API and in the
  table; which component is "current" stays a single, fixed one until Vidocq hosts several (see
  [Current component](#current-component)).
- Literal values (`env-entry`: a `java:comp/env/config/timeout` bound to `30`), `@Resource` injection,
  `resource-ref` in descriptors, `@DataSourceDefinition`.
- Listing a context (`list`, `listBindings` stay unsupported).
- Writing through JNDI: the namespace stays read-only.

## Anti-goals

- **No default binding in Vauban.** `DataSourceNames` is removed. Vauban never decides that a name exists.
- **No new reflection.** Qualifier instances built from text reuse the mechanism Vauban already uses to hand
  annotation instances to application code (`AnnotationInstances` in `vauban-core`); the naming module adds no
  `Method.invoke`, no `setAccessible`, no `java.lang.reflect.Proxy` of its own.
- **No `NamingManager.setInitialContextFactoryBuilder`.** Installation stays `JavaNaming.installAsDefault` as today.
- **No silent precedence.** A name declared twice is an error, never "the last one wins".

## Vauban: the API of `vauban-naming`

Package `io.vidocq.vauban.naming`, exported. Names below are the decided shape; signatures are the builder's to
finish.

```java
/** The four namespaces of java:. */
public enum Namespace { COMP, MODULE, APP, GLOBAL }

/** An absolute java: name. */
public record JndiName(Namespace namespace, String path) {
    /** Parses "java:comp/env/jdbc/orders"; InvalidNameException outside the four namespaces. */
    public static JndiName parse(String name) throws InvalidNameException;
    @Override String toString();   // "java:comp/env/jdbc/orders"
}

/** What a name is bound to. */
public sealed interface JndiTarget {
    /** A bean of the running container; no qualifier means @Default. */
    static JndiTarget bean(Class<?> type, Annotation... qualifiers);
    /** The same, written as text: see "Bean declarations as text". */
    static JndiTarget bean(String declaration, ClassLoader loader);
    /** Another java: name. */
    static JndiTarget link(String name);
}

/** A bean declaring names: the application's, a library's, an integrator's. */
public interface JndiBindings {
    void bind(Binder binder);
}

public interface Binder {
    Binder bind(String name, JndiTarget target);
}
```

Usage, in a Java SE application:

```java
@ApplicationScoped
class OrdersNames implements JndiBindings {
    @Override
    public void bind(Binder binder) {
        binder.bind("java:comp/env/jdbc/orders", JndiTarget.bean(DataSource.class, NamedLiteral.of("orders")))
              .bind("java:app/env/jdbc/orders", JndiTarget.link("java:comp/env/jdbc/orders"));
    }
}
```

### The default `NamingService`

`NamingService` stays the one bean every context asks (`CdiContext.naming()`), replaceable with an
`@Alternative @Priority` bean. Its default implementation changes:

- **Table.** The service asks every `JndiBindings` bean to bind its names and builds a table
  `JndiName → JndiTarget`, once per container, at the first of: the first lookup, or the container start (an
  observer of `jakarta.enterprise.event.Startup`). *Designer's call:* building it on the first lookup too means a
  lookup made during startup (Mansart Data's `DataStoreResolver.resolve` creating a repository, from an
  `@Initialized(ApplicationScoped.class)` observer that runs before this one) finds the declared names. A
  `@Dependent` `JndiBindings` bean is destroyed once it has bound its names.
- **Validation, in the `Startup` observer** (a `Startup` observer that throws fails the Vauban start with a
  `DeploymentException`; checked in `VaubanContainer`). The start fails, the message naming the name and, for a
  bean target, its declaration, when:
  - a name is declared twice, by the same bean or by two (the message names both `JndiBindings` classes);
  - a bean target is unsatisfied or ambiguous (`Instance.isResolvable()` false);
  - a bean target's bean is `@Dependent` (a JNDI caller never releases what it looks up; same rule as today);
  - a link names a name the table does not hold, or a chain of links loops.
- **Lookup.** `lookup(name)` parses the name, finds its target, and:
  - for a bean, returns `CDI.current().select(type, qualifiers).get()`, resolved at each call so a context kept
    across a `vidocq:dev` restart answers from the new container;
  - for a link, returns the lookup of the linked name;
  - for a name the table does not hold and that is not a context, throws `NameNotFoundException`.
- **Contexts.** A name is a context when:
  - it is the root of one of the four namespaces (`java:comp`, `java:module`, `java:app`, `java:global`) or one of
    the three environments (`java:comp/env`, `java:module/env`, `java:app/env`), always, so that
    `((Context) ic.lookup("java:comp/env")).lookup("jms/orders")` reaches a provider's name even when nothing is
    declared;
  - a declared name starts with it followed by `/`: declaring `java:comp/env/jdbc/orders` makes
    `java:comp/env/jdbc` a context;
  - a `JndiNameProvider` says so (`isContext(JndiName)`, see below).

  A trailing `/` is ignored, as today. *Designer's call*, after the review.

`CdiContext`, `CdiInitialContextFactory` and `JavaNaming` keep their behaviour: no container held, the system
property set only when the application chose no factory, `ldap://`/`rmi://`/`dns://` left to the JDK.

### `JndiNameProvider`: kept, for names not known in advance

*Decision made by the designer, not discussed:* a library that discovers its resources while running (a messaging
library and its queues) cannot declare them at startup. `JndiNameProvider` stays for it, changed to:

```java
Optional<Object> lookup(JndiName name) throws NamingException;   // absolute, any namespace

default boolean isContext(JndiName name) { return false; }      // e.g. java:comp/env/jms for a messaging library
```

The default `NamingService` asks the table first; only a name the table does not hold goes to the providers. Two
providers answering the same name throw `NamingException` naming both, as today. A provider answering a declared
name is never asked, so there is no conflict to report. The `@Dependent` provider rule (destroyed once it has
answered) is kept.

### Bean declarations as text

`JndiTarget.bean(String declaration, ClassLoader loader)` parses:

```
declaration := type qualifier*
type        := fully qualified class name                   (com.acme.Orders, javax.sql.DataSource)
qualifier   := '@' annotation [ '(' members ')' ]
annotation  := Named | Default | Any                        (jakarta.inject / jakarta.enterprise.inject)
             | fully qualified annotation name
members     := value | name '=' value (',' name '=' value)*
value       := "string" | number | true | false | 'c'
             | fully.qualified.Enum.CONSTANT | fully.qualified.Type.class
```

- No qualifier means `@Default`. `@Named`, `@Default` and `@Any` take their short name; every other qualifier its
  fully qualified name (a properties file has no `import`).
- A single unnamed value is the member `value`, as in Java.
- Arrays and nested annotations are not supported in this version: a parse error says so.
- The type and the annotation types are loaded through `loader` (`Class.forName(name, false, loader)`).
- Errors (unknown class, an annotation that is not a `@Qualifier`, unknown member, a value of the wrong type, a
  member without default left unset) throw `IllegalArgumentException` naming the declaration and the faulty part.
  The default `NamingService` turns them into a failed start.

**Building the qualifier instances.** `NamedLiteral`, `Default.Literal` and `Any.Literal` cover the three short
names. Any other qualifier needs an instance of an arbitrary annotation type: `vauban-api` gets a small service
interface (name left to the builder, for example `AnnotationInstanceFactory`:
`<A extends Annotation> A create(Class<A> type, Map<String, Object> members)`), which `vauban-core` provides
through `ServiceLoader` with `AnnotationInstances.create`. `vauban-naming` already requires `io.vidocq.vauban.api`.
Without a provider (another CDI container), a declaration that needs one fails with a message saying so; the three
short names and `JndiTarget.bean(Class, Annotation...)` keep working.

The `@Nonbinding` members of a qualifier are compared as CDI compares them, since resolution goes through
`Instance.select`: nothing to do in the naming module.

### Current component

The table is keyed by `JndiName` only: one application, one module, one component. The design keeps the place for
more: when Vidocq hosts several modules, a key gains the module (and component) the name belongs to, and the
default `NamingService` learns which one is current at each lookup (for example from a `ScopedValue` the runtime
binds around a component call). Not built now; the API above does not change for it, since `Binder.bind` and
`JndiName` stay as they are and the scope is an addition.

### What changes for an existing user of `vauban-naming`

- `java:comp/env/jdbc/<name>` no longer answers by itself: declare it (or let Vidocq's configuration do it).
- `java:module/env/...` and `java:app/env/...` no longer alias `java:comp/env/...`: declare each, or link them.
- `JndiNameProvider.lookup` takes a `JndiName` instead of a name relative to `java:comp/env`.

## Vidocq: `vidocq-runtime-naming-extension`

### Configuration

```properties
# a bean; no qualifier means @Default
vidocq.naming.comp/env/toto          = com.acme.Toto @Named("Toto")
vidocq.naming.comp/env/jms/audit     = com.acme.AuditQueue
vidocq.naming.comp/env/payment       = com.acme.Payment @com.acme.Primary
vidocq.naming.comp/env/orders/eu     = com.acme.Orders @com.acme.Region("eu") @com.acme.Tier(level = 2)

# a data source, and aliases of it
vidocq.naming.comp/env/jdbc/orders   = javax.sql.DataSource @Named("orders")
vidocq.naming.comp/env/jdbc/legacy   = java:comp/env/jdbc/orders
vidocq.naming.app/env/jdbc/reporting = java:comp/env/jdbc/orders
```

- **Key:** `vidocq.naming.` followed by the name without `java:`, as is. What is in the file copies into a
  `lookup` and back: `vidocq.naming.comp/env/com.acme.Orders/ds` is `java:comp/env/com.acme.Orders/ds`. A
  properties key accepts `/`; only `:` would need escaping, and the key has none.
- **Value:** starting with `java:`, a link (`JndiTarget.link`); otherwise a bean declaration
  (`JndiTarget.bean(value, loader)`), with the application's class loader.
- **Reserved keys:** a key under `vidocq.naming.` whose rest does not start with `comp/`, `module/`, `app/` or
  `global/` fails the start ("unknown naming key"), so that `vidocq.naming.comp.env.toto` (dots for slashes) is
  caught rather than ignored. This leaves room for settings of the extension later under other words.
- **Environment variables** follow MicroProfile Config: `VIDOCQ_NAMING_COMP_ENV_JDBC_ORDERS` overrides a key already
  present in a file, but cannot declare a new name (the `/` cannot be recovered from it). The documentation says so.

### Behaviour

- In `configure`, the extension reads the `vidocq.naming.*` keys (`VidocqConfiguration.propertyNames()`), keeps
  them, and installs the factory as today. In `beforeStart` it adds a `JndiBindings` bean of its own module that
  binds them (the Mansart pool extension's holder pattern, `MansartPoolExtension.java:236`, or
  `VaubanContainerBuilder.addFactory`: builder's choice).
- Validation is Vauban's: a wrong declaration fails the start through the default `NamingService`. The extension
  adds only the key checks (reserved keys).
- **Startup report:** one row per name the extension declared from `vidocq.naming.*`,
  `java:comp/env/jdbc/legacy → java:comp/env/jdbc/orders`, `java:comp/env/toto → com.acme.Toto @Named("Toto")`,
  after the existing "java: names" row. *Designer's call:* only the extension's own names; names declared by other
  `JndiBindings` beans are not listed, since Vauban exposes no listing of its table (`list` stays unsupported).
- **No default data-source names.** With the extension and no `vidocq.naming.*` key, `java:` answers only the names
  other `JndiBindings` beans declare.

## Documentation

- **Vauban `docs/en/modules/ROOT/pages/jndi.adoc`**, rewritten: the four namespaces and the no-fallback rule;
  `JndiBindings`, `Binder`, `JndiTarget` with the Java SE example; the text grammar of bean declarations; what is
  validated at startup and the messages; links; `JndiNameProvider` for names not known in advance; the
  `NamingService` alternative; "What changed" (the three points above); the "Not covered yet" list updated (literal
  values join it; `java:global` leaves it). `whats-new.adoc`, `reference.adoc` and `index.adoc` updated where they
  describe `vauban-naming`.
- **Vidocq `docs/en/modules/ROOT/pages/modules/vidocq-runtime-extensions.adoc#naming`**, rewritten: the key format,
  the value forms, a complete example, reserved keys, environment variables, the startup report rows, the start
  failures; the link to `vauban::jndi.adoc` kept. If Vidocq has a page listing configuration properties, the
  `vidocq.naming.*` keys are added there.

## Testing

TDD in both repositories (failing test first).

- **Vauban, `vauban-naming` tests:** every validation failure; `JndiName.parse` (four namespaces, trailing slash,
  invalid names); the text grammar (each value form, short names, errors); links and chains, a loop; contexts
  derived from declared names; no fallback between namespaces; `java:global`; a provider answering an undeclared
  name and never asked for a declared one; restart (a context kept across two containers). The existing
  `JavaNamingTest`, `JavaNamespaceTest` and `CdiContextTest` are adapted to declare the names they look up.
- **Vauban, `vauban-core`:** the `AnnotationInstanceFactory` provider (members, defaults, `equals` with an annotation
  read from a class).
- **Vidocq:** unit tests of the key reading (reserved keys, link versus bean); the integration test
  `vidocq-runtime-it-naming` (from vidocq#299) declares its data source and an alias through properties and looks
  them up.

## Sequencing

1. Vauban pull request (API, default `NamingService`, `vauban-api` service, `vauban-core` provider, docs).
2. Vauban's `main` deploys the snapshot.
3. Vidocq pull request (extension configuration, integration test, docs).

Between 2 and 3, Vidocq's `main` builds against a `vauban-naming` without the `jdbc/` rule, so its
`vidocq-runtime-it-naming` test fails until 3 is merged. Both pull requests are prepared together, and the Vidocq
one is merged right after the snapshot is out.

## Left to the builder

- Exact signatures, class names of the implementation, the name of the `vauban-api` service.
- Messages of the exceptions (they name the name, the declaration, and the declaring class).
- How the Vidocq extension registers its `JndiBindings` bean (holder or `addFactory`).
- The shape of the `vauban-api` service's arguments: `AnnotationInstances.create` takes an `AnnotationInfo`, so
  the service either takes one or converts a member map into one.
