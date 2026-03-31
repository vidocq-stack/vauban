## TCK Status: 417/769 (54.2%) — Target: 57%+

### Done this session
- [x] selectByBeanClass (avoid AmbiguousResolutionException)
- [x] getDirectInstance for observer/disposer invocation
- [x] EventMetadata qualifiers
- [x] Observer method hierarchy lookup
- [x] CreationalContext tracking in ApplicationContext/RequestContext
- [x] @Inherited annotation support (stereotypes, qualifiers, scopes)
- [x] Lifecycle events (Startup/Shutdown/Initialized/Destroyed)
- [x] Producer naming via stereotypes
- [x] Explicit ClassLoader for all Class.forName calls

### Failure breakdown (352 failures)
- 70 missing validations (DefinitionException/DeploymentException expected)
- 7 LinkageError (interceptor class name collisions)
- 247 functional failures
- 28 correctly failing deployments (already passing)

### Next targets (functional failures)
- [ ] ResolutionByTypeTest (6) — bean types computation
- [ ] AssignabilityOfRawAndParameterizedTypesTest (6) — type variable bounds
- [ ] DependentContextTest (8) — dependent lifecycle
- [ ] EventMetadataInjectionPointTest (6) — EventMetadata details
- [ ] CheckTypeParametersWhenResolvingObserversTest (5) — observer type param matching
- [ ] ParameterizedEventTest (5) — event type resolution
- [ ] InjectionPointTest (5) — InjectionPoint metadata
- [ ] InterceptorInvocationTest (4) — interceptor invocation
- [ ] MemberLevelInheritanceTest (4) — generic member inheritance
