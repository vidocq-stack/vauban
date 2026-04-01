# Vauban - Plan de travail

## Phase 0: Bootstrap projet ✅
- [x] POM parent Maven 4 multi-module (model 4.1.0, `--release 25`, sans preview)
- [x] Module `vauban-indexer` avec `module-info.java`
- [x] Module `vauban-api` avec `module-info.java`
- [x] Module `vauban-core` avec `module-info.java`
- [x] Module `vauban-processor` avec `module-info.java`
- [x] Module `vauban-maven-plugin` (jar pour l'instant, maven-plugin-plugin incompatible JDK 25)
- [x] Module `vauban-junit` avec `module-info.java`
- [x] Module `vauban-tck-runner`
- [x] Module `vauban-test-suite`
- [x] Test smoke JUnit 6 dans `vauban-indexer` (4 tests: JUnit 6, Class-File API, records, sealed)
- [x] `mvn clean verify` passe (9/9 modules, 1.3s)

## Phase 1: Indexeur de classes ✅
- [x] Modele: `DotName`, `ClassInfo`, `MethodInfo`, `FieldInfo`, `ParameterInfo` (records)
- [x] Modele: `AnnotationInfo`, `AnnotationValue` (sealed 13 variants), `TypeInfo` (sealed 7 variants)
- [x] `ClassFileScanner` - parse .class via `java.lang.classfile` (classes, interfaces, enums, records, annotations)
- [x] `JarScanner` - scan JARs
- [ ] `DirectoryScanner` - scan repertoires (differe, pas bloquant)
- [x] `IndexBuilder` + `VaubanIndex` - construction et requetes
- [ ] Serialisation binaire (`IndexWriter` / `IndexReader`) (differe, pas bloquant)
- [x] Tests TDD complets (64 tests)

## Phase 2: CDI Language Model ✅
- [x] Implementation `jakarta.enterprise.lang.model.declarations.*` (ClassInfo, MethodInfo, FieldInfo, ParameterInfo, PackageInfo)
- [x] Implementation `jakarta.enterprise.lang.model.types.*` (7 types + TypeMapper)
- [x] VaubanAnnotationInfo + VaubanAnnotationMember (bridge annotations)
- [x] IndexLookup (bridge entre VaubanIndex et lang model)
- [x] `AssignabilityRules` (CDI 4.1 Section 2.4 : sous-typage, generiques, wildcards)
- [x] Tests assignabilite CDI + lang model (33 tests)

## Phase 3: Decouverte et resolution de beans ✅
- [x] Modele: BeanDescriptor, BeanId, ScopeInfo, QualifierInstance, InjectionPointInfo (records)
- [x] `BeanDiscovery` : managed beans, producer methods/fields, @Inject, @Vetoed, scopes, qualifiers
- [x] `BeanResolver` : resolution typesafe par type + qualifiers, alternatives @Priority
- [x] `QualifierMatcher` : correspondance avec valeurs de membres
- [x] `DependencyGraph` : DAG, detection cycles illegaux (@Dependent)
- [x] `DeploymentValidator` : non satisfaits, ambigus, cycles
- [x] Tests TDD (25 tests, 122 total)

## Phase 4: Generation de code ✅
- [x] `BeanFactoryGenerator` : factory sans reflexion via Class-File API (new + bridge method)
- [x] `ClientProxyGenerator` : proxy sous-classe avec Supplier delegate, dispatch primitifs/objets
- [x] `GeneratedClass` record, `BeanFactory<T>` interface
- [ ] `InterceptorSubclassGenerator` (differe, pas bloquant pour Phase 6)
- [ ] `DecoratorSubclassGenerator` (differe)
- [ ] `ObserverInvokerGenerator` (differe)
- [x] Tests: generer, charger via ClassLoader, executer (8 tests, 130 total)

## Phase 5: Processeur APT ✅
- [x] `VaubanProcessor` extends AbstractProcessor, pipeline complet
- [x] `ElementScanner` : bridge TypeElement -> ClassInfo (APT -> indexer model)
- [x] Pipeline : scan -> discovery -> validation -> codegen (factories + proxies)
- [x] Enregistrement META-INF/services + provides dans module-info
- [x] Tests avec `javax.tools.JavaCompiler` (compilation in-process, 3 tests)
- [ ] Pipeline BCE complet (5 phases @Discovery etc.) - differe, structure en place
- [ ] `ExtensionLoader` via ServiceLoader - differe

## Phase 6: Runtime conteneur ✅
- [x] `VaubanContainer` avec Builder, select(Class<T>), AutoCloseable
- [x] `ApplicationContext` : ConcurrentHashMap, thread-safe, singleton par bean
- [x] `RequestContext` : ThreadLocal, activate/deactivate, ContextNotActiveException
- [x] `DependentContext` : nouvelle instance a chaque injection
- [x] `ManagedBean<T>` implements Bean<T>, wrape BeanDescriptor + BeanFactory
- [x] `CreationalContextImpl<T>`
- [x] Tests : 7 tests lifecycle/scopes/factory/erreurs, 140 total
- [ ] Systeme d'evenements (Event<T>, @Observes) - differe
- [ ] `Instance<T>` programmatic lookup complet - differe
- [ ] Beans built-in (BeanManager, Event, Instance) - differe

## Phase 7: Integration JUnit 6 ✅
- [x] `@VaubanTest` meta-annotation + `VaubanExtension`
- [x] `@AddBeans` : specifie les beans du test
- [x] Injection `@Inject` dans les champs de test
- [x] Lifecycle : bootstrap avant tests, shutdown apres
- [x] Surefire `useModulePath=false` pour compatibilite JPMS/JUnit
- [x] Tests : 5 tests (injection, conteneur vide, single bean), 145 total
- [ ] `@MockBean` : remplacement par mock - differe

## Phase 8: TCK Runner ✅
- [x] SPI TCK : VaubanBeans, VaubanContexts, VaubanContextuals, VaubanCreationalContexts
- [x] Adaptateur Arquillian : VaubanDeployableContainer (squelette), VaubanContainerConfig, extension
- [x] Configuration : profil -Ptck, cdiCoreMode=true, groupes exclus
- [x] Tests infrastructure SPI (4 tests, 149 total)
- [x] VaubanDeployableContainer fonctionnel : extraction .class ShrinkWrap, ByteArrayClassLoader, bootstrap VaubanContainer
- [x] ContainerHolder : bridge main/test scope pour BeanManager access
- [x] VaubanTestEnricher : injection @Inject BeanManager dans tests TCK
- [x] VaubanContexts.getRequestContext() connecte au conteneur actif
- [x] Profil TCK : surefire-testng provider, dependenciesToScan, testFailureIgnore
- [x] META-INF/cdi-tck.properties + libraryDirectory
- [x] Execution TCK effective : 1826 tests, 91 passes, 1227 failures, 508 skipped (5s)

## Phase 10: Validation TCK CDI Lite 🚧
- [x] Validation de déploiement : types proxiables (pas de primitifs/tableaux pour portée normale)
- [x] Validation de déploiement : constructeur sans argument non-privé pour beans à portée normale
- [x] Validation de déploiement : constructeur sans argument non-privé pour beans interceptés
- [x] Validation de déploiement : constructeur sans argument non-privé pour intercepteurs
- [x] Execution TCK Maven 4 (via SDKMAN) : 917 tests, 218 échecs, 148 sautés
- [ ] Support `AroundConstruct` (interception de l'instanciation) - 🚧 en cours
- [ ] Amélioration de la gestion des exceptions de déploiement (DefinitionException vs DeploymentException)

## Phase 11: Outillage modules ✅
- [x] `ModuleAnalyzer` : analyse JARs (explicit/automatic/unnamed), lit module-info via Class-File API
- [x] `ModuleReport` : rapport lisible [OK]/[WARN]/[ERROR], resume, split packages
- [x] Detection split packages entre JARs
- [x] Derivation noms de modules automatiques (regles JDK)
- [x] Tests : 7 tests, 156 total
- [ ] Goals Maven (`vauban:index`, `vauban:module-analyze`) - differe (maven-plugin-plugin incompatible JDK 25)
- [ ] `vauban:module-fix` - generation module-info pour modules automatiques - differe
