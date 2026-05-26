# Vauban - Work plan

## Completed phases
- **Phase 0**: Project bootstrap (Maven 4 parent POM, 9 JPMS modules) ✅
- **Phase 1**: Class indexer (ClassFileScanner, JarScanner, 64 tests) ✅
- **Phase 2**: CDI Language Model (declarations, types, AssignabilityRules, 33 tests) ✅
- **Phase 3**: Bean discovery and resolution (BeanDiscovery, BeanResolver, DependencyGraph, 25 tests) ✅
- **Phase 4**: Code generation (BeanFactoryGenerator, ClientProxyGenerator via Class-File API, 8 tests) ✅
- **Phase 5**: APT processor (VaubanProcessor, ElementScanner, full pipeline, 3 tests) ✅
- **Phase 6**: Runtime container (VaubanContainer, ApplicationContext, RequestContext, DependentContext, 7 tests) ✅
- **Phase 7**: JUnit 6 integration (@VaubanTest, VaubanExtension, 5 tests) ✅
- **Phase 8**: TCK Runner (TCK SPI, Arquillian adapter, 1826 tests executed) ✅
- **Phase 11**: Module tooling (ModuleAnalyzer, ModuleReport, 7 tests) ✅

## Phase 10: CDI Lite TCK validation 🚧
- [x] Deployment validation: proxyable types, constructors, interceptors
- [x] Maven 4 TCK run: 917 tests, 218 failures, 148 skipped
- [x] AroundConstruct support, interceptor bytecode (Class-File API)
- [x] Deployment exception handling (DefinitionException vs DeploymentException)
- [x] Constructor injection in interceptors, @Priority ordering
- [x] Matching interceptor bindings with @Nonbinding
- [x] Exclusion of disabled alternatives
- [x] Improved type assignability (raw vs parameterized, wildcards)

## Backlog (deferred)
- [ ] `DirectoryScanner` - directory scanning
- [ ] Binary serialization IndexWriter/IndexReader
- [ ] Complete `InterceptorSubclassGenerator`
- [ ] `DecoratorSubclassGenerator`
- [ ] `ObserverInvokerGenerator`
- [ ] Complete BCE pipeline (5 phases @Discovery etc.)
- [ ] `ExtensionLoader` via ServiceLoader
- [ ] Event system (Event<T>, @Observes)
- [ ] Complete `Instance<T>` programmatic lookup
- [ ] Built-in beans (BeanManager, Event, Instance)
- [ ] `@MockBean` for JUnit
- [ ] Maven goals (vauban:index, vauban:module-analyze)
