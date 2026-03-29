# Vauban - Plan de travail

## Phase 0: Bootstrap projet
- [ ] POM parent Maven 4 multi-module (model 4.1.0, `--release 25`, sans preview)
- [ ] Module `vauban-indexer` avec `module-info.java`
- [ ] Module `vauban-api` avec `module-info.java`
- [ ] Module `vauban-core` avec `module-info.java`
- [ ] Module `vauban-processor` avec `module-info.java`
- [ ] Module `vauban-maven-plugin`
- [ ] Module `vauban-junit` avec `module-info.java`
- [ ] Module `vauban-tck-runner`
- [ ] Module `vauban-test-suite`
- [ ] Test smoke JUnit 6 dans `vauban-indexer`
- [ ] `mvn clean verify` passe

## Phase 1: Indexeur de classes
- [ ] Modele: `DotName`, `ClassInfo`, `MethodInfo`, `FieldInfo`, `ParameterInfo` (records)
- [ ] Modele: `AnnotationInfo`, `AnnotationValue` (sealed), `TypeInfo` (sealed), `Modifier`
- [ ] `ClassFileScanner` - parse .class via `java.lang.classfile`
- [ ] `JarScanner` - scan JARs
- [ ] `DirectoryScanner` - scan repertoires
- [ ] `IndexBuilder` + `VaubanIndex` - construction et requetes
- [ ] Serialisation binaire (`IndexWriter` / `IndexReader`)
- [ ] Tests TDD complets

## Phase 2: CDI Language Model
- [ ] Implementation `jakarta.enterprise.lang.model.declarations.*`
- [ ] Implementation `jakarta.enterprise.lang.model.types.*`
- [ ] `TypeResolver` et `AssignabilityRules`
- [ ] Tests assignabilite CDI

## Phase 3: Decouverte et resolution de beans
- [ ] `BeanDiscovery` et finders (bean, producer, interceptor, decorator, observer)
- [ ] `BeanResolver` - resolution typesafe
- [ ] `QualifierMatcher`
- [ ] `DependencyGraph` avec detection cycles
- [ ] `DeploymentValidator`
- [ ] Tests TDD complets

## Phase 4: Generation de code
- [ ] `ClientProxyGenerator` via `java.lang.classfile`
- [ ] `InterceptorSubclassGenerator`
- [ ] `DecoratorSubclassGenerator`
- [ ] `BeanFactoryGenerator`
- [ ] `ObserverInvokerGenerator`
- [ ] Tests: generer, charger, executer

## Phase 5: Processeur APT
- [ ] `VaubanProcessor` - processeur principal
- [ ] Pipeline BCE (5 phases)
- [ ] `ExtensionLoader` via ServiceLoader
- [ ] Tests avec `javax.tools.JavaCompiler`

## Phase 6: Runtime conteneur
- [ ] `VaubanContainer` implements `SeContainer`
- [ ] Contextes: Application, Request, Dependent (impls classiques, sans preview)
- [ ] Systeme d'evenements
- [ ] `Instance<T>` programmatic lookup
- [ ] Beans built-in
- [ ] Bootstrap depuis composants generes

## Phase 7: Integration JUnit 6
- [ ] `@VaubanTest` + `VaubanExtension`
- [ ] `@AddBeans`, `@MockBean`
- [ ] Injection dans tests

## Phase 8: TCK Runner
- [ ] Adaptateur Arquillian
- [ ] SPI TCK
- [ ] Pipeline runtime codegen
- [ ] Execution TCK Lite

## Phase 9: Outillage modules
- [ ] `vauban:index` - indexation dependances
- [ ] `vauban:module-analyze` - analyse JPMS
- [ ] `vauban:module-fix` - generation module-info
