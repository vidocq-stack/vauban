# Vauban - Plan de travail

## Phases completees
- **Phase 0**: Bootstrap projet (POM parent Maven 4, 9 modules JPMS) ✅
- **Phase 1**: Indexeur de classes (ClassFileScanner, JarScanner, 64 tests) ✅
- **Phase 2**: CDI Language Model (declarations, types, AssignabilityRules, 33 tests) ✅
- **Phase 3**: Decouverte et resolution de beans (BeanDiscovery, BeanResolver, DependencyGraph, 25 tests) ✅
- **Phase 4**: Generation de code (BeanFactoryGenerator, ClientProxyGenerator via Class-File API, 8 tests) ✅
- **Phase 5**: Processeur APT (VaubanProcessor, ElementScanner, pipeline complet, 3 tests) ✅
- **Phase 6**: Runtime conteneur (VaubanContainer, ApplicationContext, RequestContext, DependentContext, 7 tests) ✅
- **Phase 7**: Integration JUnit 6 (@VaubanTest, VaubanExtension, 5 tests) ✅
- **Phase 8**: TCK Runner (SPI TCK, Arquillian adapter, 1826 tests executes) ✅
- **Phase 11**: Outillage modules (ModuleAnalyzer, ModuleReport, 7 tests) ✅

## Phase 10: Validation TCK CDI Lite 🚧
- [x] Validation de déploiement : types proxiables, constructeurs, intercepteurs
- [x] Execution TCK Maven 4 : 917 tests, 218 échecs, 148 sautés
- [x] Support AroundConstruct, bytecode intercepteurs (Class-File API)
- [x] Gestion exceptions de déploiement (DefinitionException vs DeploymentException)
- [x] Injection par constructeur dans intercepteurs, @Priority ordering
- [x] Matching bindings d'intercepteurs avec @Nonbinding
- [x] Exclusion alternatives désactivées
- [x] Amélioration assignabilité types (raw vs parameterized, wildcards)

## Backlog (differe)
- [ ] `DirectoryScanner` - scan repertoires
- [ ] Serialisation binaire IndexWriter/IndexReader
- [ ] `InterceptorSubclassGenerator` complet
- [ ] `DecoratorSubclassGenerator`
- [ ] `ObserverInvokerGenerator`
- [ ] Pipeline BCE complet (5 phases @Discovery etc.)
- [ ] `ExtensionLoader` via ServiceLoader
- [ ] Systeme d'evenements (Event<T>, @Observes)
- [ ] `Instance<T>` programmatic lookup complet
- [ ] Beans built-in (BeanManager, Event, Instance)
- [ ] `@MockBean` pour JUnit
- [ ] Goals Maven (vauban:index, vauban:module-analyze)
