# Vauban — Reproducible bugs

Internal tracker. Minimalist format: short id, date, symptom, repro,
suspected cause, status. Updated on every investigation.

---

## BUG-20261118-49 — An invoker's looked-up argument loses the owner of its type

- **Date**: 2026-11-18
- **Status**: FIXED (2026-11-18, final fixes)
- **Module**: `vauban-core` (`LangModelTypeMapper`, `TypeMapper`, `VaubanParameterizedType`)
- **Symptom**: `Service.owned(Outer<String>.Inner<Integer> inner)` with an invoker that looks its argument up, and the
  producers of `Outer<String>.Inner<Integer>` and of `Outer<Long>.Inner<Integer>`. The processor, the start and the
  freeze refuse the deployment: _Invoker argument lookup ambiguous: 2 beans found for parameter type
  inv.Outer$Inner<java.lang.Integer> at position 0 of method inv.Service.owned_. Past that check, the frozen `G:`
  invoker recorded the looked-up type without its owner (`InvokerShape.notation`), so its lookup at call time matched
  both beans as well.
- **Repro**: `GeneratedInvokerCompileTimeTest`, the four tests (processor, frozen, start, a module that opens nothing),
  method `owned`.
- **Cause** (proved): `LangModelTypeMapper.toIndexType`, which turns a language model type back into an index type,
  built a `ParameterizedType` without owner: the language model has none. `AssignabilityRules.ownersAssignable`
  accepts any owner when one side has none. The invoker's lookup check (`ExtensionMethodValidator`), the notation the
  `G:` record writes (`InvokerShape`) and the injection points `@Enhancement` adds (`EnhancementApplier`) go through it.
- **Fix**: `VaubanParameterizedType`, which the language model gives for an index type, keeps the index owner outside
  its equality (`indexOwner()`), and `LangModelTypeMapper` writes it back. A type an extension builds with
  `Types.parameterized` has no owner, as before.
- **What stays**: `Outer<String>.Plain`, an inner class without type parameters, is a class type in the language model
  (BUG-20261118-47); read back, it has no owner.
- **TCK**: not covered.
- **Verification**: red, `stack-l2d-ff-49.log`, "BUG-49 red" (the four tests, _ambiguous: 2 beans_). Green, same log,
  "BUG-49 green".
- **Found by**: the final whole-branch review of the stack.

## BUG-20261118-54 — The owner of an inherited observer's parameter type keeps the superclass's type variable

- **Date**: 2026-11-18
- **Status**: FIXED (2026-11-18, final fixes)
- **Module**: `vauban-core` (`ObserverInjectionPoints`, `ManagedBean`)
- **Symptom**: `OwnerListenerBase<X>` declares `void on(@Observes Pung, Outer<X>.Inner<String> inner)`, where
  `Inner<I>` is an inner class of `Outer<O>`. With `OwnerListener extends OwnerListenerBase<String>` and a producer of
  `Outer<String>.Inner<String>`, the build accepts the deployment and the start refuses it, run and frozen:
  _unsatisfied dependency for parameter arg1 of type vi.types.Outer<X>$Inner<java.lang.String>_. With
  `OwnerListener<Y> extends OwnerListenerBase<Y>`, the build reports _Unsatisfied dependency: parameter 1 of
  OwnerListener.on()_ itself, where it leaves `Holder<Y>` to the start.
- **Repro**: `VetoedInterceptorCompileTimeTest#theOwnerOfAnInheritedObserversParameterTypeIsTheOneTheBeanClassGives`,
  `#anInheritedObserversParameterWithATypeVariableInItsOwnerIsLeftToTheStart`;
  `ObserverInjectionPointsTest#aTypeVariableLeftInTheOwnerIsNone`.
- **Cause** (proved): `ManagedBean.resolveType` and `substituteTypeVariables` substituted the type arguments of a
  parameterized type and kept its owner as declared, `Outer<X>`. `ObserverInjectionPoints.hasTypeVariable` looked at
  the type arguments only, so an owner left a type variable made an injection point the build resolved.
- **Fix**: both substitutions resolve a parameterized owner too, and `ResolvedParameterizedType.toString()` writes it
  as the JDK does (`Outer<String>$Inner<String>`); `hasTypeVariable` checks the owner. `BeanInjector` and
  `EventDispatcher` go through `resolveType` too; only the observer check is tested.
- **TCK**: not covered.
- **Verification**: red, `stack-l2d-ff-54.log`, "BUG-54 red: unit test" (`owner left`) and "BUG-54 red: processor,
  start, frozen" (the start's _Outer<X>$Inner_ refusal, the build's _Unsatisfied dependency_). Green, same log,
  "BUG-54 green: owner checked, substituted, written".
- **Found by**: the final whole-branch review of the stack.

## BUG-20261118-51 — A bean inherits the static observer methods of its superclass

- **Date**: 2026-11-18
- **Status**: FIXED (2026-11-18, lane L4)
- **Module**: `vauban-core` (`ObserverDisposerDiscovery`, `ObserverInjectionPoints`)
- **Symptom**: `vi.Junior extends vi.Elder`, a `@Dependent` bean; `Elder` declares `static void heard(@Observes Byte)`
  and `void kept(@Observes Byte)`. `Junior` gets both as observers, in the processor, at start and frozen. When the
  superclass is a bean too, its static observer is registered once for it and once more for each bean below it, and
  an event reaches it as many times.
- **Repro**: `VetoedInterceptorCompileTimeTest#aBeanDoesNotInheritAStaticObserverMethodFromTheProcessorAtStartAndFrozen`.
- **Spec**: CDI 4.1, "Inheritance of member-level metadata" (`inheritance.asciidoc`): "injected fields, initializer
  methods, lifecycle callback methods and non-static observer methods are inherited by beans from their
  superclasses", and "If X declares an initializer, non-static observer, `@PostConstruct` or `@PreDestroy` method
  `x()` then Y inherits `x()` if and only if ...".
- **Cause** (proved): the reflective walk at start (`getAllInheritedMethods`) and the index walk of the processor
  (`discoverIndexedInheritedObservers`, BUG-20261118-44) left out a private superclass method only.
  `ObserverInjectionPoints.addInherited`, which follows the reflective walk, did the same for the injection points of
  inherited observers.
- **Fix**: the three leave out a static superclass method too. This departs from Weld 5.1.7
  (`EnhancedAnnotatedTypeImpl`), which registers an inherited static observer on every subclass bean, the superclass
  a bean or not; Vauban follows CDI 4.1, sections 4.2 and 10.4. The migration page says so.
- **TCK**: not covered.
- **Verification**: red, `stack-l4-51-red.log` (`vi.Junior heard, vi.Junior kept` in the three places). Green,
  `stack-l4-51-dev.log`, "Green run 1".
- **Found by**: the L4 review, round 5.

## BUG-20261118-48 — `ClassConfig.methods()` and `fields()` leave out the inherited members, against their Javadoc

- **Date**: 2026-11-18
- **Status**: FIXED
- **Module**: `vauban-core` (`VaubanClassConfig`, `BceProcessor`, `EnhancementPatchSerializer`)
- **Symptom**: in `@Enhancement`, `ClassConfig.methods()` and `fields()` of a class give the members it declares, and
  its direct superinterfaces' methods, where their Javadoc says "as defined by `ClassInfo.methods`" and
  "`ClassInfo.fields`", which include the inherited ones since BUG-20261117-42. An extension cannot qualify, through
  a subclass, an injection point that subclass inherits.
- **Repro**: `VetoedInterceptorCompileTimeTest#aClassConfigConfiguresTheInheritedMembersForThatSubclassOnly` in
  `vauban-processor`: `Configured` and `Sibling` extend `Base`, which injects a `Dep` into a field and an initializer;
  an extension adds `@Named("special")` to both through `Configured`'s `ClassConfig`. `Configured` must get the
  special `Dep`, `Base` and `Sibling` the default one, from the processor, at start and frozen.
  `EnhancementPatchSerializerTest#anInheritedMemberIsKeyedByTheClassDeclaringIt` in `vauban-core`.
- **Cause, proved**: red on b9d9b4db (`stack-l2d-red.log`, "RED BUG-20261118-48 on b9d9b4db (both outcomes
  asserted)"): the extension sees no inherited member in any of the three, and `Configured` gets the default `Dep`;
  "RED BUG-20261118-48 on b9d9b4db: the patch key of an inherited member": one key where a hidden field and the one
  it hides need two. `VaubanClassConfig` built its configs from `VaubanClassInfo.configurableMethods()` and
  `configurableFields()`, the declared members, and `methods()` and `fields()` gave those.
- **Fix**: `VaubanClassConfig` gives a config of its own to every member of `ClassInfo.methods()` and `fields()`,
  the configurable ones first. The configs belong to this class's `ClassConfig`, which `BceProcessor` keys by the
  class configured: the applier applies them to that class's descriptors only (`modifications.get(bean.beanClass())`;
  an inherited observer's descriptor names the subclass), where an inherited injection point keeps the description of
  the class declaring it. `@Enhancement` still calls a `MethodConfig` or `FieldConfig` parameter with the configurable
  members only (`configurableMethods()`, `configurableFields()`), as its Javadoc says. The frozen record, which the
  processor writes too, names an inherited member with the class declaring it, after the key earlier versions wrote:
  `Sub#field#f#p.Base`, `Sub#method#m(desc)#p.Base`, `Sub#parameter#m(desc)#i#p.Base`; a key without it is a member
  the class configures itself, as every earlier record reads. Additive: no earlier key changes.
- **Verification**: both tests green (`stack-l2d-r2.log`, "Round 2 (5)" and "Round 2 (6)"); the reactor in
  `stack-l2d-r2.log`, "Round 2 (7)".
- **Rebased onto a0cddcad (L4's lane)**: `EnhancedClasses`, the index the phases after `@Enhancement` read
  (BUG-20261118-45), keyed the removals of a member by its name only, so a removal on an inherited field, through a
  subclass that declares a field of the same name, emptied the subclass's own field. Red on 5fc788d1
  (`stack-l2d-red.log`, "RED rebase onto a0cddcad, on 5fc788d1: an inherited field's removal in EnhancedClasses"):
  `EnhancementPatchSerializerTest#aRemovalOnAnInheritedFieldLeavesTheFieldOfTheSameNameAlone`, `expected:
  <[jakarta.inject.Inject]> but was: <[]>`. The removals take the key the additions take
  (`EnhancementPatchSerializer.declaring`).
- **What stays**: a binding added on a method counts for the whole bean, as the applier did before, now for an
  inherited method too. The edited index of the later phases holds a member on the class that declares it, for every
  subclass: a change made to an inherited member through a subclass reaches that subclass's beans, not what
  `@Registration` or `@Synthesis` read of the member. Within one `InjectionPointInfo` of such a bean, `qualifiers()`
  comes from the subclass's descriptor and shows the change, while `declaration()` reads the member from the edited
  index (`VaubanBceInjectionPointInfo.declaration`) and does not: the language model has one identity per member,
  that of the class declaring it.
- **Found by**: the re-review of round 1 of batch D, lane L2.

---

## BUG-20261118-47 — A non-generic inner class of a generic class has the raw types above it in its descriptor

- **Date**: 2026-11-18
- **Status**: FIXED
- **Module**: `vauban-indexer` (`TypeInfo.ParameterizedType`, `ClassTypeParameters`), `vauban-processor`
  (`ParameterizedSupertypes`, `ElementScanner`, `AnnotationArtefacts`), `vauban-core` (`BeanDiscovery`,
  `TypeInfoUtils`, `TypeMapper`, `SyntheticComponentRegistrar`)
- **Symptom**: `@Produces Outer<String>.Plain plain()`, where `Outer<O>` declares `class Plain implements Holder<O>`
  and `Plain` declares no type parameter: the producer has the raw `Holder` in the processor, at start and frozen, where
  `Bean.getTypes()` has `Holder<String>`. The shape BUG-20261117-21 left.
- **Repro**: `VetoedInterceptorCompileTimeTest#aProducerOfANonGenericInnerClassHasTheTypesAboveIt` in
  `vauban-processor`; `OwnedTypesAtBuildAndStartTest` (`Items implements Supplier<Box<String>.Plain>`);
  `ClassFileScannerTest$EnclosingTypes#aNonGenericInnerClassHasItsOwner` and
  `#aParameterizedTypeWithoutArgumentsNeedsAnOwner` in `vauban-indexer`.
- **Cause, proved**: red on a278b032 (`stack-l2d-red.log`, "RED round 2 on a278b032 (new tests, main of a278b032)"):
  the three ways see `vi.types.Holder` where the container has `Holder<java.lang.String>`; at start `Supplier` has
  `ClassType[name=p.Box$Plain]`; the scan returns a `ClassType` for `Outer<Number>.Plain`; the record accepts a
  parameterized type without arguments nor owner. The index named a type without type arguments a `ClassType`, which
  has no owner, so `Outer<String>` was lost; reflection gives a `ParameterizedType` without arguments whose owner is
  `Outer<String>`.
- **Fix**: the index names it as reflection does, `ParameterizedType(Outer$Plain, [], Outer<String>)`, without
  touching `ClassType`. The bytecode scan (`ClassTypeParameters`), the processor (`ParameterizedSupertypes`,
  `ElementScanner`) and reflection (`BeanDiscovery`, `TypeInfoUtils`) fold a type without arguments into a
  `ClassType` only where it has no parameterized owner. The compact constructor of `ParameterizedType` refuses empty
  arguments without an owner: that is a class type. The language model has no owner, so `TypeMapper` maps the new
  shape to a `ClassType`, as the extensions saw it before; `AnnotationArtefacts.sourceName` and
  `SyntheticComponentRegistrar.typeName` write no `<>` for it.
- **Audit of the readers that assume arguments** (`git grep` of `typeArguments().get(0)`, `getFirst()`, `size()`,
  `isEmpty()`, `getActualTypeArguments()[0]` and `.length`, and of every `case ParameterizedType` and
  `new ParameterizedType`): the `getActualTypeArguments()[0]` readers (`BeanInjector:290`, `DisposerInvoker:173,486`,
  `VaubanContainer:1276,1298`, `VaubanInjectionPoint:68`, `EventDispatcher:699`, `EventImpl:105`) read `Instance<T>`,
  `Provider<T>`, `Event<T>` or `InjectionPoint`'s type, top-level generic types that always have an argument;
  `IndexClassValidator.typeArguments` compares the count with the class's type parameters, zero for `Plain`;
  `AssignabilityRules` matches a `ClassType` and the new shape by `rawEquivalentArguments`, true on no argument; the
  readers that iterate (`ClassChecksums`, `EventDispatcher.isParameterizedAs`, `ObserverInjectionPoints`,
  `VaubanProcessor.collectClassNames`, `TypeRef`, `ManagedBean.reflectParameterized`) take an empty list as it is;
  the producers from the language model (`LangModelTypeMapper`, `VaubanMethodInfo`, `TypeAnnotationReader.receiver`,
  `BeanDiscovery.beanClassType`) build a parameterized type only with arguments. Five tests of `vauban-core` built a
  parameterized type without arguments as an input; they use a class type or an argument now. Left as they are: the
  `toString` of Vauban's reflective types writes `Outer$Plain<>` (diagnostics only), and
  `ParameterizedSupertypes.walk` walks a supertype `Outer<String>.Plain`, which needs an enclosing instance, as a raw
  class.
- **Verification**: the four tests green (`stack-l2d-r2.log`, "Round 2 (1)"); the whole reactor green ("Round 2
  (3)": indexer 142, core 1782 with 8 skipped, processor 462 with 3 skipped).
- **Found by**: the review of BUG-20261117-21, round 1 of batch D, lane L2.

---

## BUG-20261118-46 — A reflective member type Vauban builds has no owner, where the JDK gives its declaring class

- **Date**: 2026-11-18
- **Status**: FIXED
- **Module**: `vauban-core` (`VaubanObserverMethod`, `VaubanSyntheticObserverBuilder`, `ManagedBean`,
  `BeanDiscovery`)
- **Symptom**: the observed type of an observer of `Map.Entry<String, Integer>`, from its descriptor or from a
  synthetic observer, and a bean type `ManagedBean` rebuilds from the descriptor, were not equal to the JDK's
  `Map.Entry<String, Integer>`: their `getOwnerType()` was `null`, where the JDK gives `Map.class`, and the JDK's
  `equals` compares owners. An inner class of a generic class lost its parameterized owner too.
- **Repro**: `VaubanObserverMethodObservedTypeTest#aStaticMemberTypeHasItsDeclaringClassAsOwner` and
  `#anInnerClassOfAGenericClassHasItsEnclosingTypeAsOwner`, `SyntheticObserverEventShapesTest
  #theReflectiveFormOfAMemberTypeEqualsTheJdks`.
- **Cause, proved**: red on dc80d927 (`stack-l2d-red.log`, "RED round 1 on dc80d927 (new tests, main of
  dc80d927)"): `expected: ParameterizedTypeImpl<java.util.Map$Entry<...>> but was: ResolvedParameterizedType`, and
  `Outer<java.lang.String>$Inner<java.lang.Integer>` against `Outer$Inner<java.lang.Integer>`. Each hard-coded
  `getOwnerType()` to `null` and hashed without it.
- **Fix**: each gives the owner as the JDK does, the parameterized enclosing type the index records, else
  `getDeclaringClass()` (`null` for a top-level class), and hashes with the JDK's formula, as
  `TypeHierarchyResolver` and `ManagedBean.ResolvedParameterizedType` already did. `ManagedBean`'s descriptor
  fallback builds a `ResolvedParameterizedType`, which has `equals`, where it built an anonymous class.
- **What stays**: a type from the language model has no owner, so for `Outer<String>.Inner<Integer>` the fallback
  gives the raw `Outer.class`, the declaring class, where the JDK gives `Outer<String>`. Resolution is not affected:
  `AssignabilityRules` lets a side without an owner agree with any owner (BUG-20261117-21).
- **Verification**: the three tests green (`stack-l2d-r1.log`, "Round 1 (2)"); module tests and the full build in
  `stack-l2d-r1.log`.
- **Found by**: the review of BUG-20261117-21, round 1 of batch D, lane L2.

---

## BUG-20261118-45 — The phases after `@Enhancement` still see the annotations it removed

- **Date**: 2026-11-18
- **Status**: FIXED (2026-11-18, lane L4)
- **Module**: `vauban-core` (`EnhancedClasses`, `BceProcessor.withEnhancements`)
- **Symptom**: an `@Enhancement` method removes `@Deprecated` from `vi.Worn`, its field, its method and its parameter
  (`removeAnnotation` on the class and the method, `removeAllAnnotations` on the field and the parameter). A
  `@Registration` method then reads all four still `@Deprecated`, in the processor and at start. `@Synthesis` and
  `@Validation` read the same language model.
- **Repro**: `VetoedInterceptorCompileTimeTest#registrationDoesNotSeeTheAnnotationsEnhancementRemovedFromTheProcessorAndAtStart`.
- **Cause** (proved): `EnhancedClasses` (BUG-20261118-42) applied the additions of `EnhancementPatchSerializer.additions`
  only. The removals of a `VaubanClassConfig` and of its member configurations (`isAllAnnotationsRemoved`,
  `getRemovePredicates`) reached the container's descriptors (`EnhancementApplier`), never the language model of the
  later phases.
- **Fix**: `EnhancedClasses.of` applies to each class, field, method and parameter the removals of every
  configuration of it, then its additions, as `EnhancementApplier` does: a removal applies to the annotations the
  target had. The rule "add annotations, never remove them" for a frozen deployment is unchanged: a build still refuses
  to freeze a removal (Vidocq/vauban#288), so the extension runs again at each start, and the start sees what the
  processor saw. The test checks that the build refuses the freeze.
- **TCK**: not covered.
- **Verification**: red, `stack-l4-45-red.log`, "Red run (BUG-20261118-45)" (`class true, field true, method true,
  parameter true` in the three runs). Green, `stack-l4-45-dev.log`, "Green run 1" (vauban-core `Bce*`, `*Enhance*`,
  `*Frozen*`; `VetoedInterceptorCompileTimeTest`, `BceCompileTimeTest`).
- **Found by**: the L4 review, round 4.

## BUG-20261118-44 — An `ObserverInfo` of an observer a bean inherits has no method, or none at all

- **Date**: 2026-11-18
- **Status**: FIXED (2026-11-18, lane L4)
- **Module**: `vauban-core` (`ObserverDisposerDiscovery`, `VaubanBceObserverInfo.observed`)
- **Symptom**: `vi.Heir extends vi.Ancestor`, a `@Dependent` bean, inherits `hear(BeanManager, @Observes Short)`. At
  start and frozen, its `ObserverInfo` gives `observerMethod()` and `eventParameter()` `null`, which the
  `ObserverInfo` Javadoc allows for a synthetic observer only. The processor gives `@Registration` no such observer
  at all.
- **Repro**: `VetoedInterceptorCompileTimeTest#anObserverInfoGivesTheObserverMethodABeanInheritsFromTheProcessorAtStartAndFrozen`.
- **Cause** (proved): `VaubanBceObserverInfo.observed` looked the method up in the declaring class, the bean class,
  alone. In the processor, `ObserverDisposerDiscovery.discoverInheritedObservers` found inherited observers by
  reflection only, and a class the compilation compiles does not load there.
- **Fix**: `observed` walks the superclasses, as the language model describes them (`IndexLookup.find`), until one
  declares a matching method; the `MethodInfo` it gives belongs to that superclass, and `declaringClass()` and
  `bean()` stay the bean class, as with Weld. When the bean class does not load, the discovery reads the inherited
  methods from the superclasses `IndexLookup.describe` gives, a method the class or a nearer superclass overrides
  counted once, as the reflective walk does. Round 5 of the review: the walk carries the type arguments each class
  gives the one above it (`IndexClassValidator.typeArguments`, as `ObserverInjectionPoints`), so the event type of
  `on(@Observes T)` in `Base<T>` is `Pong` for `Sub extends Base<Pong>`, as at start, and the override check compares
  the erased parameter types after substitution: `Over`, which overrides it with `on(@Observes Pong)`, had two
  observers in the processor. `parameterTypes` stays the erasure of the declared parameters, and `observed` takes the
  method those name, whatever its event type. Red `stack-l4-44b-red.log` (the processor gave event `T` and a second
  `Over` observer, the start and frozen a `null` method for `Sub`); green `stack-l4-44b-dev.log`
  (`VetoedInterceptorCompileTimeTest#anInheritedObserverOfATypeVariableObservesItsTypeArgumentFromTheProcessorAtStartAndFrozen`).
- **TCK**: not covered.
- **Verification**: red, `stack-l4-44-red.log`, "Red run (BUG-20261118-44)" (the processor gives no observer, start
  and frozen `no method`) and the probe after it. Green, `stack-l4-44-dev.log`, "Green run 2".
- **Found by**: the L4 review, round 4.

## BUG-20261118-43 — At start, `@Registration` gives a `@Typed` bean for the supertypes of its types

- **Date**: 2026-11-18
- **Status**: FIXED (2026-11-18, lane L4)
- **Module**: `vauban-core` (`BceTypeMatcher.matchesRegistrationTypes`)
- **Symptom**: a `@Registration(types = java.util.EventListener.class)` method with a `BeanInfo` parameter is called,
  at start and when the build freezes the extensions, for a bean `@Typed(Talker.class)` whose `Talker` extends
  `EventListener`. Its bean types are `Talker` and `Object` only; the processor does not call it.
- **Repro**: `VetoedInterceptorCompileTimeTest#registrationGivesATypedBeanForItsTypesOnlyFromTheProcessorAtStartAndFrozen`.
- **Cause** (proved): `matchesRegistrationTypes` read a class or parameterized bean type as `matchesTypes` does: it
  loaded the class the type names and asked whether it is a subtype of an expected type. The bean types already hold
  every supertype a bean has; a `@Typed` bean has fewer. The processor cannot load the classes it compiles and matched
  the name only.
- **Fix**: a class or parameterized bean type matches an expected type of the same name, without loading anything,
  in the three places. `@Enhancement`, which matches classes and their subtypes, keeps `matchesTypes`.
- **TCK**: not covered.
- **Verification**: red, `stack-l4-43-red.log`, "Red run 1" (`[vi.Narrow, vi.Wide]` at start and frozen). Green,
  `stack-l4-43-dev.log`, "Dev run 1" (vauban-core, vauban-processor, vauban-maven-plugin).
- **Found by**: the L4 review.

## BUG-20261118-42 — `@Registration` does not see the annotations `@Enhancement` added to members

- **Date**: 2026-11-18
- **Status**: FIXED (2026-11-18, lane L4)
- **Module**: `vauban-core` (`BceProcessor.process`, `withEnhancements`; `VaubanContainerBuilder`)
- **Symptom**: an extension that adds `@Deprecated` in `@Enhancement` to a field, a method, a parameter and a producer
  method (`FieldConfig`, `MethodConfig`, `ParameterConfig`) does not find it on the fields, methods, parameters and
  `producerMethod()` of the `BeanInfo` its `@Registration` method gets, in the processor, at start and frozen. Weld
  applies them on `ProcessAnnotatedType`, so every later view sees them.
- **Repro**: `VetoedInterceptorCompileTimeTest#registrationSeesTheMemberAnnotationsEnhancementAddedFromTheProcessorAtStartAndFrozen`.
- **Cause** (proved): `BceProcessor.withEnhancements` (BUG-20261117-10) and the start's `enrichIndex` add the
  annotations an `@Enhancement` added to a class only; at start, `process` read the index `enrichIndex` gave it, and
  did not know the modifications of the container's own `@Enhancement` pass.
- **Fix**: the phases after `@Enhancement` read an index with every addition, class and members
  (`EnhancedClasses.withAdditions`, keyed as `EnhancementPatchSerializer.additions` names them, an annotation the
  target holds not added twice). The container hands `process` what its pass, the replay and the frozen record added
  (`BceProcessor.Enhanced`, which also carries the extensions of BUG-20261117-08). The index bean discovery reads keeps
  the class additions only: `EnhancementApplier` applies the member ones to the descriptors, and applying them twice
  would double them.
- **TCK**: not covered.
- **Verification**: red, `stack-l4-42-red.log`, "Red run 1" (`false` for the four in the three places). Green,
  `stack-l4-42-dev.log`, "Dev run 1" (vauban-core, vauban-processor, vauban-maven-plugin).
- **Found by**: the L4 review.

## BUG-20261118-41 — An `ObserverInfo` gives two overloads that observe one event type the same method

- **Date**: 2026-11-18
- **Status**: FIXED (2026-11-18, lane L4)
- **Module**: `vauban-core` (`ObserverDescriptor`, `ObserverDisposerDiscovery`, `VaubanBceObserverInfo.observed`)
- **Symptom**: a class with `hear(BeanManager, @Observes Long)` and `hear(@Observes Long)`: the `ObserverInfo` of both
  observers gives the first overload as `observerMethod()`, and its second parameter as `eventParameter()`, in the
  processor, at start and frozen.
- **Repro**: `VetoedInterceptorCompileTimeTest#anObserverInfoTellsApartOverloadsOfTheSameEventTypeFromTheProcessorAtStartAndFrozen`.
- **Cause** (proved): `ObserverDescriptor` names the method and the observed event type only, and
  `VaubanBceObserverInfo.observed` (BUG-20261117-09) took the first method of that name with an observed parameter of
  that type.
- **Fix**: `ObserverDescriptor` carries the erased types of the method's parameters (`parameterTypes`, as
  `BeanDiscovery.erasedParameterTypes` spells them for an indexed method and `Class.getTypeName` for an inherited one
  read by reflection), as a producer's bean id does for an overloaded method (BUG-20261116-11); `EnhancementApplier`
  keeps them; `observed` matches them when they are known.
- **TCK**: not covered.
- **Verification**: red, `stack-l4-41-red.log`, "Red run 1" (the three places give `hear of 2 parameters, event at 1`
  twice). Green, `stack-l4-41-dev.log`, "Dev run 1" (vauban-core, vauban-processor, vauban-maven-plugin).
- **Found by**: the L4 review.

## BUG-20261118-37 — An invoker the processor described is called through reflection at start

- **Date**: 2026-11-18
- **Status**: FIXED (batch 3 of lane L3). It predated batch 2, which moved the invoker's description to the build
  (BUG-20261117-48) but left its call at start as it was.
- **Module**: `vauban-core` (`extensions.VaubanInvoker`, `InvokerTarget`, `SyntheticParamCodec`, new
  `InvokerShape`, `InvokerClassWriter`, `InvokerClasses`; `container.VaubanContainerBuilder` freeze),
  `vauban-processor` (`VaubanProcessor.writeSyntheticMetadata`)
- **Symptom**: an invoker the annotation processor built for a bean class it compiles was recorded as names
  (`InvokerTarget`, the `V:` parameter of `SyntheticParamCodec`). At start, `readInvoker` resolved it with
  `InvokerTarget.resolve` (`Class.getDeclaredMethod`), and `VaubanInvoker` got a `MethodHandle` with
  `MethodHandles.lookup().unreflect(method)`, calling `invokeWithArguments`, or `Method.invoke` when the module gave
  no handle. So did an invoker `vauban:generate` froze. The build knew the method, its parameters and its lookups.
- **Repro**: `BceCompileTimeTest.theInvokerOfABeanTheBuildCompilesIsGeneratedAtBuild`: an extension the processor
  runs builds an invoker of `app.Worker.work()`; the test expects one `Worker$$VaubanInvoker…` class in the output,
  and an invoker read back from the metadata whose `method()` is `null`. Red on 17715ef4: "the invoker class the
  build generated ==> expected: <1> but was: <0>".
- **Cause** (proved by the repro): nothing generated a class for an invoker; the metadata had only the names, so the
  start had nothing to call but the method it resolved by reflection.
- **Fix**: the build generates, with the Class-File API, a class per invoked method of a bean whose class it writes,
  in the package of that class (`<Bean>$$VaubanInvoker$<method>$<hash>`, public, final, synthetic). It implements
  `BiFunction<Object, Object[], Object>`, a type of `java.base` every module reads, and calls the method by name:
  `invokevirtual` on the bean class or the declaring class, `invokeinterface` for an interface default method,
  `invokestatic` for a static method; it casts the instance and each argument, unboxes a primitive argument, boxes a
  primitive return and returns `null` for `void`; an exception of the method is thrown as it is. `InvokerShape`,
  read from the language model when `createInvoker` runs, says how to call the method and whether a class of the
  bean's package may: a private method has no invoker, and a protected or package-private method of a class of
  another package, or a parameter type that package cannot name, keeps the reflective call; so does a method reached
  as a member of the bean's package (not public, or of a class that is not public) when the build does not write
  every class it reaches that way, which a split package of another archive, with another loader, would refuse with
  an `IllegalAccessError`. The metadata names the class with a `G:` parameter, followed by the static flag, the
  declared type and the annotations of each looked-up parameter, then the `V:` names. The start loads the class,
  makes it through a public lookup when its package is exported, through the lookup the generated provider of its
  package grants otherwise (the package needs no `opens`), and calls through it, or calls the method itself when
  neither lookup exists; a value a primitive parameter takes by widening (JLS 5.1.2) is widened first, as a method
  handle did. An exception of the method reaches the caller as it is, an `InvocationTargetException` it throws
  included. The argument lookups use the recorded type and qualifiers, with no `Method`. The processor
  writes the classes through the Filer; the freeze writes them next to the bean classes under the output it records
  into, for the bean classes that output holds. An invoker built at start, and one of a class another archive ships,
  keep the reflective path. `InvokerTarget` no longer reflects (`resolve` moved to `VaubanInvoker`), and leaves
  `ArchitectureRules.REFLECTIVE_FALLBACKS`.
- **Verification**: `stack-l3b-red.log`, header "BUG-20261118-37 red, on 17715ef4: the processor-built invoker
  reflects"; `stack-l3b-green.log`, header "BUG-20261118-37 green, on 17715ef4 + fix: generated invoker classes"
  (core 1770, processor 452, architecture 56, all green). `GeneratedInvokerCompileTimeTest` calls the same invokers
  built in the processor, frozen and at start, and in a named module that exports and opens nothing: a method of a
  superclass, a package-private one, an interface default method, a generic, a varargs and a static method,
  primitive arguments widened and refused when narrowed, a primitive return, `void`, a checked exception, and
  argument lookups of `Holder<String>` and `@Named("x") String` (the CDI Lite TCK `invokers` cases); a private method
  has no invoker; an instance lookup of a `@Dependent` target destroys it after the call. `GeneratedInvokerTest` covers
  the widening, the `G:` record, the split package, the call with no lookup and a class that cannot be made, and the
  message that names the version skew when a newer build wrote a kind of parameter the runtime does not read.
  Review round 1: `stack-l3b-green.log`, header "BUG-20261118-37 round 1 (Sonar + M1-M4), on 1f58c392" (core 1773,
  processor 452, architecture 56).
  Review round 2: `stack-l3b-red.log`, header "BUG-20261118-37 round 2 red, on 192925c1: the generated path unwraps
  an InvocationTargetException the method throws" (processor, frozen and module places red, at start green);
  `stack-l3b-green.log`, header "BUG-20261118-37 round 2 green, on 192925c1 + fix".
- **Found by**: the review of batch 2 (minor M3).

## BUG-20261118-36 — Two intercepted instances created with one creational context share their interceptor instances

- **Date**: 2026-11-18
- **Status**: FIXED on `fix/vauban-bug-stack` (lane `stack/l3-batch-2`)
- **Module**: `vauban-core` (`ManagedBean.create` and `destroy`, `context.CreationalContextImpl`)
- **Symptom**: two intercepted `@Dependent` beans created with one creational context, by a bean constructor that
  injects both or by `BeanManager.getReference(bean, type, sharedContext)`, have one interceptor instance between
  them: the second's calls go through the first's interceptor instance, whose `@Intercepted Bean` names the first.
  CDI 4.1, 6.4.1, makes an interceptor instance a dependent object of the one instance it intercepts.
- **Repro**: `VetoedInterceptorCompileTimeTest#twoInterceptedBeansInjectedIntoOneBeanHaveAnInterceptorEach` in
  `vauban-processor` (`vi.Pair` injects two beans bound to `vi.Watcher` through its constructor; the processor, at
  start and frozen): it answered `Watcher(vi.WatchedService: hello) | Watcher(vi.WatchedService: hello)`.
  `InterceptorInstancePerInterceptedInstanceTest` in `vauban-core`: the reviewer's probe, two beans by `getReference`
  on one context; a constructor that injects both and an `Instance.get()`; each interceptor instance's `@PreDestroy`
  runs once.
- **Cause** (proved by both repros): `CreationalContextImpl` holds the interceptor instances by interceptor class only,
  and an intercepted `ManagedBean` created its instance with the caller's context. Field injection gave an
  intercepted `@Dependent` bean a fresh context (`BeanInjector.beanReference`); a constructor or initializer parameter
  (`VaubanContainer.dependentReference`) and a caller's `getReference` did not. The `@Intercepted Bean` of
  BUG-20261117-47, recorded on that context by the first intercepted bean only, followed the shared instance.
- **Fix**: an intercepted `ManagedBean` creates each instance with a context of its own,
  `CreationalContextImpl.forInterceptedInstanceOf(bean)`, which knows the bean for `@Intercepted Bean`. The caller's
  context keeps it by instance (`adopt`). `destroy` takes it back (`release(instance)`) to run the `@PreDestroy`
  interceptors and release it; releasing the caller's context releases those left. The instance's own dependents go
  in that context too, and are destroyed with it.
- **CDI Lite TCK**: no test creates two intercepted instances with one context; the TCK on aa27d1b0 passed
  (774/774) with the defect.
- **Verification**: red on 183726ec (`stack-l3b-red.log`, "BUG-20261118-36 red, on 183726ec: two intercepted beans a
  constructor injects", at start and frozen, and "... one creational context, two intercepted instances
  (vauban-core)", both tests). Green: `stack-l3b-green.log`, "BUG-20261118-36 fix: a context per intercepted instance,
  core and processor".
- **Found by**: the review of batch 2 (BUG-20261117-47), by a probe.

## BUG-20261118-34 — At start, a compact constructor's parameters lose their type annotations

- **Date**: 2026-11-18
- **Status**: FIXED
- **Module**: `vauban-indexer` (`scanner.ClassFileScanner`); the cause is javac's
- **Symptom**: for `record Compact(java.util.@Marked List<@Marked String> names, @Marked String plain, String
  @Marked [] tags, int size) { Compact { ... } }`, with a `TYPE_USE` annotation, the compact canonical constructor's
  parameters carry no type annotation that has a path (`List<@Marked String>`), sits on a qualified type
  (`java.util.@Marked List`) or on an array (`String @Marked []`), at start and frozen, where the processor shows them.
  A top-level one on a plain type (`@Marked String`) shows, and so does any annotation that also targets `PARAMETER`.
- **Repro**: `VetoedInterceptorCompileTimeTest#aCompactConstructorTypeAnnotations` in `vauban-processor`, three ways;
  red on 922d0028 (`stack-l2c-red.log`, "RED round 3 BUG-20261118-34 on 922d0028"): `Compact
  [java.util.List<java.lang.String> [] [], java.lang.String [vi.types.Marked] [], java.lang.String[] [] [], int []
  []]` at start and frozen. `RecordConstructorParametersTest#aCompactConstructor` in `vauban-indexer`.
- **Cause**: javac 25 writes these annotations into the compact constructor's `RuntimeVisibleTypeAnnotations` as
  copies of the record component's, with a `FIELD` target (`javap`: `FIELD`, `FIELD, location=[TYPE_ARGUMENT(0)]`),
  where the JVMS asks for `METHOD_FORMAL_PARAMETER` with an index: nothing says which parameter they belong to, and
  the index keeps a method's formal parameter targets only. The top-level ones on a plain type it writes right. The
  explicit and the implicit canonical constructors are written right.
- **Fix**: after JLS 8.10.4, a compact canonical constructor's parameters are its record components, in order. For a
  constructor whose `MethodParameters` marks every parameter mandated and whose descriptor is the components', in
  order, `ClassFileScanner` places the type annotations of component *i* (its `RuntimeVisibleTypeAnnotations`, path
  and all) on parameter *i*, instead of the constructor's own parameter entries, which would count the top-level ones
  twice; the constructor's other type annotations stay. Without `MethodParameters`, and for an explicit or an
  implicit canonical constructor, nothing changes. The frozen record's checksum of such a record changes.
- **Verification**: `stack-l2c-green34b.log` (the three-way repro and `aRecordConstructorKeepsItsGenericParameters`,
  green), `stack-l2c-green-idx3.log` (indexer, 138 tests); the full build in the report's "Review round 3".
- **Found by**: the red test of BUG-20261118-32's round 2 (review of batch C); fix after the review's round 3.

---

## BUG-20261118-33 — In the processor, an inner class's constructor lacks its outer instance

- **Date**: 2026-11-18
- **Status**: FIXED
- **Module**: `vauban-processor` (`apt.ElementScanner`, `apt.MirrorTypeAnnotations`)
- **Symptom**: for `class Outer { class Inner { Inner(String name) {} } }`, `MethodInfo.parameters()` of `Inner`'s
  constructor is `[String]` in the processor and `[Outer, String]` at start and frozen; a type annotation on `name` is
  at position 0 in the processor, 1 at start. The language model asks for the parameters "declared or implicitly
  declared": the outer instance of an inner member class's constructor is implicitly declared (JLS 8.8.1), and javac
  marks it mandated.
- **Repro**: `ElementScannerTypeAnnotationsTest#innerConstructorParameters` and `#constructorParameters` in
  `vauban-processor`, red on 814faacf (`stack-l2c-red.log`, "RED BUG-20261118-33 on 814faacf ... (types only)"):
  `[String]` for `[Outer, String]`, `PARAMETER 0` for `PARAMETER 1`.
- **Cause**: `ElementScanner.scanMethod` took the parameters javax.lang.model gives, the declared ones only.
- **Fix**: the processor adds the outer instance first to the constructor of an inner member class, of the erasure of
  the enclosing class, as the descriptor names it, named `arg0`: the source gives it no name, and the language model
  names a parameter it has no name for `argN`. `MirrorTypeAnnotations.parameters` numbers the declared parameters
  after it. The frozen record, computed from the bytecode index, does not change.
- **The name** (review round 2): compiled with `-parameters`, javac writes `this$0` for the outer instance, which the
  start shows; the processor keeps `arg0`. It cannot know whether the class it compiles gets `-parameters`: a
  processor reads the `-A` options only (`ProcessingEnvironment.getOptions()`), not javac's own. Without the option the
  two agree, as for every declared parameter the other way round. Documented in `migration.adoc`.
- **Verification**: `stack-l2c-dev.log`, "BUG-20261118-33 fix: core, processor, maven-plugin tests" (green).
- **Found by**: the review of batch C (round 1, item 4).

---

## BUG-20261118-32 — At start, a local class's constructor shows its captured values, misplaced

- **Date**: 2026-11-18
- **Status**: FIXED
- **Module**: `vauban-indexer` (`scanner.ClassFileScanner`)
- **Symptom**: at start and frozen, the constructor of a local class lists the values the class captures, which
  javac adds after the declared parameters, with the wrong types and annotations: for `Local(@Marked String label)`
  capturing an `int`, `[Maker arg0, String arg1, String arg2 @Marked]`, the `int` shown with the declared `String`
  type and the declared parameter's annotation on it. The language model asks for the parameters "declared or
  implicitly declared".
- **Repro**: `LocalClassConstructorParametersTest` in `vauban-indexer` (javac 25 in an instance and a static method,
  and a class file without `MethodParameters` as javac before 21 writes it) and
  `VetoedInterceptorCompileTimeTest#aLocalClassConstructorHasItsDeclaredParameters` in `vauban-processor`, at start and
  frozen; red on 790e3d38 (`stack-l2c-red.log`, "RED BUG-20261118-32 on 790e3d38" and "RED BUG-20261118-32 three ways
  on 790e3d38").
- **Cause**: `ClassFileScanner` took the parameters the `Signature` names as the last of the descriptor, and, since
  BUG-20261117-58, a shorter `RuntimeVisibleParameterAnnotations` as annotating the last ones: true of an enum's and an
  inner member class's constructor, whose added parameters come first, not of a local class's, whose captured values
  come last. Found by the review of batch C.
- **Fix**: a `ParameterLayout` places the declared parameters among the descriptor's: after the synthetic ones the
  index leaves out (an enum's name and ordinal), and the mandated ones it keeps (the outer instance of an inner or a
  local class's constructor, as javac marks it), before the synthetic ones it leaves out (a local or anonymous
  class's captured values). It reads the `MethodParameters` flags; without them, an enum's two, an inner member's
  outer instance, and for a local class the outer instance when the first parameter is of the class that encloses it
  (`EnclosingMethod`) and the parameters the `Signature` does not name last. A `Signature`, the parameter annotations
  and the type annotations number the declared parameters from there. Without `MethodParameters` nor `Signature`, a
  local class's captured values cannot be told from its parameters, as by reflection: they are read as declared. The
  processor does not reach a local class (it has no canonical name). The frozen record's checksum of a local class
  with captured values changes with its parameters.
- **Mandated is the outer instance only** (review round 2): javac 25 marks other parameters mandated too, which the
  source declares implicitly and the `Signature` names: every parameter of a record's compact canonical constructor,
  the name of an enum's `valueOf`. Counting them as leading implicit took a compact constructor's `List<String>` for
  `List` and dropped its type annotations (`RecordConstructorParametersTest`, red in `stack-l2c-red.log`, "RED round 2
  BUG-20261118-32 records on e137a56c", and three ways, "RED round 2 ... records three ways": `Compact [java.util.List
  [], int []]` at start and frozen). A mandated parameter is now the outer instance only when it is: that of an inner
  member class's constructor, or of a local or anonymous class's whose descriptor names the enclosing class there. That
  is javac's layout: the outer instance is the only mandated parameter javac adds before the declared ones; the others
  it marks mandated are declared ones.
- **A local record has no outer instance** (review round 3): declared in an instance method of `O`, with a first
  component of type `O`, its compact constructor's first parameter, mandated and of the enclosing class, was taken for
  the outer instance, which shifted the `Signature`'s types by one (`LocalClassConstructorParametersTest
  #aLocalRecordOfJavac25`, red in `stack-l2c-red-localrecord2.log`: the `List<String>` component read as `O`). A
  record is implicitly static: a constructor of a class with a `Record` attribute has no outer instance. Without
  `MethodParameters`, the descriptor check stays a guess, documented on `isOuterInstance`: a local class declared in a
  static method, whose first parameter is of the enclosing class, reads as having one; javac writes
  `MethodParameters` for those constructors since Java 21.
- **Verification**: `stack-l2c-dev.log`, "BUG-32 fix: indexer, core, processor, maven-plugin tests" (green), and
  "Round 2 BUG-20261118-32 records fix (3)" with "Round 2 records three ways (reshaped)"; round 3:
  `stack-l2c-green-idx3.log` (indexer, 138 tests).
- **Found by**: the review of batch C (round 1, item 1).

---

## BUG-20261118-31 — At start, a parameter the class file gives flags but no name for is named `null`

- **Date**: 2026-11-18
- **Status**: FIXED
- **Module**: `vauban-indexer` (`scanner.ClassFileScanner`)
- **Symptom**: at start and frozen, `ParameterInfo.name()` is `null` for the parameters of a constructor whose
  `MethodParameters` attribute gives flags without names: javac 25 writes one for an enum's constructor and an inner
  class's, compiled without `-parameters`, to mark the synthetic and mandated parameters. The language model says
  "never `null`": a name it does not know is `argN`, `N` the zero-based position in the method declaration.
- **Repro**: `EnumConstructorParametersTest#unnamedParametersAreArgN` in `vauban-indexer`, red on 31d44610
  (`stack-l2c-red.log`, "RED BUG-20261118-31 on 31d44610"): `[null, null]` for `[arg0, arg1]`.
- **Cause**: `ClassFileScanner.buildParameters` took the attribute's name, absent, as the parameter's, and fell back
  to `argN` only past the attribute's end, `N` the descriptor position.
- **Fix**: a parameter without a name in the class file is `argN`, `N` its position among the parameters the index
  records, after an enum constructor's synthetic ones (BUG-20261117-58). The processor reads the names from the
  source: compiled without `-parameters`, a class read at start keeps `argN` where the processor shows the source's
  names, as before for every method.
- **Verification**: `stack-l2c-dev.log`, "BUG-20261118-31 fix: indexer, core, processor, maven-plugin tests" (green).
- **Found by**: the tests of BUG-20261117-58.

---

## BUG-20261118-30 — The frozen record does not see a change to a class outside the index the extensions read

- **Date**: 2026-11-18
- **Status**: FIXED on `fix/vauban-bug-stack`
- **Module**: `vauban-core` (`langmodel.IndexLookup.find`, `langmodel.ReadClasses`)
- **Symptom**: the known limit of BUG-20261117-31. An extension reads a class outside the index from its class file,
  a bean's superclass in a library say; the record's fingerprint counts the bean classes alone, so a start on
  another version of that library loaded a record its extensions would now write otherwise.
- **Repro**: `FrozenReadClassesTest#aChangedClassOutsideTheIndexRefusesTheRecord` in `vauban-core`: `lib.Holder`
  extends `lib.Base`, outside the index; the extension records the number of `Base`'s fields. Frozen with one field,
  started with two: red on 35c21b77 (`stack-l1b-bug1830.log`, "RED on 35c21b77"), the record stood.
- **Cause**: BUG-20261117-31 left those classes out of the record: fingerprinting them would read their class files
  at start, and which ones the extensions reach is only known by running them.
- **Fix**: the mechanism of BUG-20261118-29. `IndexLookup.find` notes each class it looks up outside the deployment
  while a freeze records (`ReadClasses`), so the set is the one the extensions reached, found or not; the record keeps
  their checksums under `read.<name>`, the JDK's classes left out, and the start compares them. Format, fingerprint
  and older records unchanged.
- **Cost**: a start of a frozen deployment now reads and checksums the class file of each class the record names,
  once: the classes outside the index its extensions read, beyond the JDK's. Not measured; a deployment whose
  extensions walk a large library pays one parse per class they reached.
- **Verification**: `stack-l1b-bug1830.log`, "GREEN on 35c21b77 + BUG-30": `FrozenReadClassesTest` 5 of 5, with the
  frozen-record tests and `BceUnindexedClassTest`, 32 of 32.
- **Found by**: the review of BUG-20261118-29, which asked whether the same mechanism lifts BUG-20261117-31's limit.

## BUG-20261118-29 — The frozen record does not see a change to annotation defaults or to a `package-info`

- **Date**: 2026-11-18
- **Status**: FIXED on `fix/vauban-bug-stack`
- **Module**: `vauban-core` (`langmodel.ReadClasses`, `langmodel.VaubanAnnotationInfo`,
  `langmodel.declarations.VaubanPackageInfo`, `extensions.FrozenExtensions`, `container.VaubanContainerBuilder`)
- **Symptom**: since BUG-20261117-55 and BUG-20261117-57 an extension reads the default values an annotation type
  declares and the annotations of a `package-info`. The fingerprint of the frozen record counts the bean classes
  alone, by their model: a change to a default of an annotation type, or to a `package-info`, left it as it was, and
  the start loaded a record that the extensions would now write otherwise.
- **Repro**: `FrozenReadClassesTest` in `vauban-core`. A library `lib` (`@Ranked` with `rank() default 1`, a
  `package-info` annotated `@Tag("one")`) is written as class files, the bean class `lib.Holder` (`@Ranked`, no rank)
  once; the extension records `rank` and the tag in a synthetic bean. Frozen on that library, then started on one
  whose default is `2`, or whose tag is `two`: red on 6b2403e2 (`stack-l1b-bug1829.log`, "RED (4)"), the record stood
  (`the record is stale: the extensions run ==> expected: <true> but was: <false>`).
- **Cause**: `DeploymentFingerprint` holds the bean classes and the extensions; annotation types and `package-info`
  are neither.
- **Fix**: additive, outside the fingerprint. While a build freezes the extensions, the language model notes the
  classes it reads for them beyond the bean classes (`ReadClasses`, a `ScopedValue` bound around the capture):
  the annotation types whose defaults it merges, and the `package-info` classes whose annotations it gives, found or
  not. The record keeps each one's model checksum (`ClassChecksums.ofModel`, from its class file through the
  deployment's loader, `-` for none) under a new key, `read.<binary name>`; a class of the JDK is left out, as the
  record already stands for the JDK of the build (BUG-20261117-31). A start whose fingerprint matches compares those
  checksums when the record has them, and runs the extensions when one differs, with a note naming the classes.
  `format` stays `1` and `DeploymentFingerprint.value()` does not change: a record written before keeps its
  fingerprint and stays valid (`aRecordWithoutTheClassesReadStaysValid`).
- **Verification**: `stack-l1b-bug1829.log`, "GREEN on 6b2403e2 + BUG-29 fold": `FrozenReadClassesTest` 4 of 4, the
  frozen-record tests 26 of 26; the module tests of the review round.
- **Found by**: the review of BUG-20261117-55 and BUG-20261117-57.

## BUG-20261118-28 — The processor shows class- and source-retained annotations

- **Date**: 2026-11-18
- **Status**: FIXED on `fix/vauban-bug-stack`
- **Module**: `vauban-processor` (`apt.ElementScanner.scanAnnotations`)
- **Symptom**: in the processor, `annotations()` of a class, a field, a method, a parameter or a package also gave
  the annotations of `@Retention(CLASS)` or `@Retention(SOURCE)`, and an `@Enhancement(withAnnotations = …)` of a
  class-retained annotation was called for the classes that carry it. At start and frozen the container reads
  `RuntimeVisibleAnnotations` alone: neither shows them, and the enhancement is called for no class.
- **Repro**: `VetoedInterceptorCompileTimeTest#anExtensionSeesTheRuntimeRetainedAnnotationsAloneInTheProcessorAtStartAndFrozen`,
  red on e85a19cf (`stack-l1b-bug1828.log`, "RED on e85a19cf"): the processor saw
  `class [ClassRetained, Deprecated, SourceRetained]` and `enhanced vi.types.Retained`.
- **Cause**: `scanAnnotations` kept every annotation mirror of the element; the type annotations already kept the
  runtime-retained ones alone (`MirrorTypeAnnotations.isRuntimeRetained`).
- **Fix**: `scanAnnotations` keeps the runtime-retained annotations, and those whose type the compilation does not
  find, whose retention it cannot read (`UnseenClassProcessorTest`).
- **Known limit**: that type is missing only for a class read from the class path, and javac merges the visible and
  invisible annotations of its class file: an unresolved class-retained annotation shows in the processor only, the
  start reading `RuntimeVisibleAnnotations` alone.
- **Verification**: `stack-l1b-bug1828.log`, "GREEN (2) on e85a19cf": vauban-processor 405 tests, 18 skipped.
- **Found by**: the review of BUG-20261117-57.

## BUG-20261118-27 — `@Named("")` on an injection point field keeps the empty name

- **Date**: 2026-11-18
- **Status**: FIXED on `fix/vauban-bug-stack`
- **Module**: `vauban-core` (`bean.discovery.QualifierResolver.resolveNamedDefault`)
- **Symptom**: `@Inject @Named("") Service service` asked for a bean named `""`, and the deployment failed as
  unsatisfied; `@Inject @Named Service service` asks for `service`. `@Named("")` is `@Named` with its default value
  written out. Since BUG-20261117-55, an extension that rebuilds a `@Named` from the members the language model shows
  writes it so.
- **Repro**: `NamedEmptyValueTest` in `vauban-core`, red on 9a79b173 (`stack-l1b-bug1827.log`, "RED on 9a79b173":
  `CDI deployment validation failed`). The bean side, `@Named("") class Gadget`, was already named `gadget`.
- **Cause**: `resolveNamedDefault` gave the default name to a `@Named` without members only.
- **Fix**: it gives it to a `@Named` whose `value` is absent or empty, as the bean name and the names an extension
  adds (`EnhancementApplier.addedName`) already did.
- **Verification**: `stack-l1b-bug1827.log`, "GREEN on 9a79b173 + BUG-18-27 fix"; the module tests of the review
  round.
- **Found by**: the review of BUG-20261117-55.

## BUG-20261118-26 — In the processor, an annotation repeated on a type use shows twice, not in its container

- **Date**: 2026-11-18
- **Status**: FIXED on `fix/vauban-bug-stack`
- **Module**: `vauban-processor` (`apt.MirrorTypeAnnotations`)
- **Symptom**: for `List<@Mark("1") @Mark("2") String>`, the type argument showed `[@Mark("1"), @Mark("2")]` in the
  processor, and `[@Mark.List({...})]` at start and frozen, as the class file records it and as reflection's
  `AnnotatedType.getAnnotations()` gives it. A declaration shows the container the three ways. An extension's
  `annotations()` and `annotation(Mark.List.class)` disagreed between the build and the start.
- **Repro**: `VetoedInterceptorCompileTimeTest#anExtensionSeesTheContainerOfAnAnnotationRepeatedOnATypeUse` in
  `vauban-processor`, red on 7b58d173 in the processor (`stack-l1b-bug1826.log`, "RED on 7b58d173 ... (value rendered
  by kind)": `marks [Mark 1, Mark 2]`).
- **Cause**: javac's `TypeMirror.getAnnotationMirrors()` gives repeated type annotations one by one, where
  `Element.getAnnotationMirrors()` gives the container; `MirrorTypeAnnotations` recorded what it was given.
- **Fix**: `MirrorTypeAnnotations` records an annotation written more than once on a type as the container its
  `@Repeatable` names, holding them in the order written, at the position of the first.
- **Verification**: `stack-l1b-bug1826.log`, "GREEN on 7b58d173 + BUG-18-26 fix" and "BUG-18-26 module tests:
  processor, maven-plugin".
- **Found by**: BUG-20261117-56's test, which read `annotations()` of a type use the three ways.

## BUG-20261118-24 — A normal-scoped producer of a JDK class with package-private methods is refused; Weld deploys it

- **Date**: 2026-11-18
- **Status**: OPEN (a Vauban limitation that departs from Weld; not lifted in the client proxy lane, review round 9)
- **Module**: `vauban-core` (`InterceptorBeanWrapper.createProxy`, `ProducerProxyEligibility`), `vauban-processor`,
  `vauban-maven-plugin`
- **Symptom**: `@Produces @RequestScoped ArrayList<String> names()` injected as `List<String>`: the call throws
  `DeploymentException: No client proxy can be generated for java.util.ArrayList, ... (PACKAGE_PRIVATE_VIRTUALS)`.
  CDI 4.1 §3.10 finds `ArrayList` proxyable (no final non-private instance method, a public no-argument
  constructor), and Weld 5.1.7 deploys it by defining its proxy outside `java.*`.
- **Repro**: `InterfaceClientProxyTest#aNormalScopedProducerOfAJdkClassWithPackagePrivateMethodsInjects`, disabled; red
  when enabled (`stack-bug1824-red.log`, "Red run 1": the `DeploymentException` above, 1 error of 6). Today's answer
  is pinned by `#aNormalScopedProducerOfAJdkClassWithPackagePrivateMethodsFailsWithTheRemedy`.
- **Cause**: Vauban cannot define a class in a package of the JDK, and a proxy in the producer's package cannot
  override the package-private methods of `java.util.ArrayList`: a call to one would run on the proxy's own,
  uninitialised state. Weld accepts that risk. Since BUG-20261117-18 the producer is judged by its raw class; before,
  it got a proxy of `RandomAccess`, which no `List` injection point took.
- **Found by**: the review of the BUG-20261117-18 fix (round 8, I-1).

## BUG-20261118-23 — `@Enhancement` gets `null` for `Messages` and `Types`, `@Synthesis` for `Messages`

- **Date**: 2026-11-18
- **Status**: FIXED on `fix/vauban-bug-stack`
- **Module**: `vauban-core` (`extensions.BceProcessor`: `invokeEnhancement`, `invokeWithArg`, `processSynthesis`)
- **Symptom**: an `@Enhancement` method that declares `Messages` or `Types`, both listed by the Javadoc of
  `@Enhancement`, gets `null` for them, on every path: the full cycle, the enhancement of an unprocessed archive
  and the replay for a pre-processed jar. A `@Synthesis` method gets `null` for `Messages`. The extension fails with a
  `NullPointerException`. `@Registration` and `@Validation` give every type their Javadoc lists.
- **Repro**: `ExtensionPhaseArgumentsTest` in `vauban-core` (unprocessed archive, replay, `@Synthesis` at start);
  `VetoedInterceptorCompileTimeTest#enhancementGetsTypesFromTheProcessorAtStartAndFrozen` and
  `#anErrorEnhancementReportsThroughMessagesFailsTheBuildTheStartAndTheFreeze` in `vauban-processor`. Red without
  the fix (`stack-l1-r5-1823.log`, "BUG-18-23 red": `because "types" is null`, `because "messages" is null`).
- **Cause**: `invokeWithArg` filled the target parameter alone ("Messages and Types can be added here if needed");
  `resolveSynthesisArgs` had no `Messages` branch.
- **Fix**: each `@Enhancement` call gets a `VaubanMessages` and a `VaubanTypes`, the implementation `@Registration`,
  `@Synthesis` and `@Validation` use; `@Synthesis` gets a `VaubanMessages`. The errors reported through them join
  the errors of the phase, of the kind it reports today: deployment problems.
- **Found by**: the review of BUG-20261118-22.

## BUG-20261118-22 — An extension parameter of an unsupported type is `null`

- **Date**: 2026-11-18
- **Status**: FIXED on `fix/vauban-bug-stack`
- **Module**: `vauban-core` (`extensions.BceProcessor.processDiscovery`, `extensions.ExtensionMethodValidator`)
- **Symptom**: a `@Discovery`, `@Synthesis` or `@Validation` method that declares a parameter of a type its phase
  does not support, `Types` in `@Discovery` for one, is called with `null` for it: nothing is reported, and an
  extension that uses it fails with a `NullPointerException`, which BUG-20261118-04 turns into a definition error.
  `@Enhancement` and `@Registration` reported it, without naming the extension, and accepted an extension's own
  subtype of a listed type, which then failed in the call. `Messages`, which `@Discovery` supports, was `null` too.
- **Spec**: the Javadoc of `BuildCompatibleExtension` (cdi-api 4.1.0): a parameter of a type unsupported in the
  phase is a deployment problem. Vauban reports it as a definition error in every phase, as `@Enhancement` and
  `@Registration` already did; the mapping is a question for the maintainer. The Javadoc of `Messages`: an error
  reported through it is a deployment problem.
- **Repro**: `UnsupportedParameterTypesTest` (one test per phase, the subtype, an error through `Messages` in
  `@Discovery`) and `BceProcessorFailureToleranceTest#aDiscoveryParameterOfAnUnsupportedTypeIsReportedBeforeTheCall`
  in `vauban-core`; `VetoedInterceptorCompileTimeTest#aDiscoveryParameterOfAnUnsupportedTypeFailsTheBuildTheStart...`
  and `#anErrorDiscoveryReportsThroughMessagesFailsTheBuildTheStartAndTheFreeze` in `vauban-processor`. Red without
  the fix (`stack-l1-r4-1822.log`, "BUG-18-22 red"; `stack-l1-r6.log`, "R6 red": the start threw nothing for
  `@Synthesis` and `@Validation`, the messages did not name the extension, and `@Discovery`'s `Messages` error was a
  `DefinitionException`; `stack-l1-r7.log`, "R7 red": `BceRuntimeReplayTest$EnhancementFailures` and
  `EnhancementFailureGenerationTest`, nothing thrown on the replay, the unprocessed archive and `vauban:generate`).
- **Cause**: the phases filled the arguments they knew and left the others `null`; `ExtensionMethodValidator`
  checked `@Enhancement` and `@Registration` alone, with `isAssignableFrom`.
- **Fix**: `ExtensionMethodValidator` checks the parameter types of the five phases before any call, on every path
  that runs `@Enhancement` too (the enhancement of an unprocessed archive, at start and under `vauban:generate`, and
  the replay for a pre-processed jar), each against
  the exact types the Javadoc of its annotation lists, and reports the first other one as
  `@<Phase> method <extension>.<method> has invalid parameter type: <type>`; the method is not called. A `Messages`
  parameter of `@Discovery` gets one, and the errors it reports are deployment problems (`DiscoveryResult`
  `deploymentErrors`, a `DeploymentException` in the processor, at start and frozen).
- **Found by**: the review of BUG-20261118-04.

## BUG-20261118-21 — A final normal-scoped bean class is refused even when requested as an interface

- **Date**: 2026-11-18
- **Status**: FIXED on `fix/client-proxy-bridge`
- **Module**: `vauban-core` (`proxy.ForwardedMethods`, `container.InterceptorBeanWrapper`), `vauban-processor`
  (`ClientProxyArtefacts`)
- **Symptom**: `@ApplicationScoped public final class FinalSvc implements Stateful`. Looked up or injected as
  `Stateful`, the container threw `UnproxyableResolutionException` ("is final") when it made the client proxy; the
  processor wrote a source proxy that extends the final class, and the build failed ("cannot inherit from final");
  `vauban:generate` wrote a class file the JVM refuses (`IncompatibleClassChangeError`). CDI 4.1 §3.10 makes a final
  class unproxyable as the requested type only: as an interface it implements, the bean is legal, and Weld proxies
  it, extending `Object` with the interfaces.
- **Repro**: `InheritedFinalMethodTest.finalClassAsItsInterface`, `finalClassInjectedAsItself` and
  `finalClassLookedUpAsItself` in `vauban-core`; `UnproxyableBeanValidationTest.finalBeanClass` and
  `UnnameableMemberBytecodeEmissionTest.finalMethodProxy` (`FinalService`) in `vauban-processor`;
  `VaubanGeneratorTest.proxyOfAFinalClass` in `vauban-maven-plugin`.
- **Cause** (proved by the red runs): the container checked the bean class's finality when it made the proxy, and
  every path made the proxy a subclass of a final class: `ForwardedMethods.proxySuperclass` (BUG-20261118-20) walked
  up past a final method only.
- **Fix**: `ForwardedMethods.proxySuperclass` walks up past a final class too, so the three paths give its proxy the
  shape of BUG-20261118-20: `Object`, or the nearest class above with no final method, and the bean's interfaces. The
  container's check moved to the requested type: a lookup as a final class throws `UnproxyableResolutionException`
  ("is final"), cached per class with the final-method walk; the validator already judged the injection point's type
  ("cannot be a final class"). The processor emits that proxy as bytecode.
- **Verification**: red, `stack-bug21-red.log`, "Red run 1" (on 88c3630a: the container refused the interface lookup,
  the processor's source proxy did not compile, `vauban:generate`'s proxy was refused by the JVM) and "Red run 2" (the
  processor test). Green, `stack-bug21-dev.log`, "Dev run 1" and "Dev run 2".
- **Found by**: BUG-20261117-49 lane, review of BUG-20261118-20.

## BUG-20261118-20 — A proxy looked up as an interface runs a final method that implements it on itself

- **Date**: 2026-11-18
- **Status**: FIXED on `fix/client-proxy-bridge`
- **Module**: `vauban-core` (`proxy.ForwardedMethods`, `proxy.RuntimeClientProxyGenerator`, `proxy.ClientProxyShape`,
  `proxy.ClientProxyEmitter`, `container.InterceptorBeanWrapper`), `vauban-processor` (`ClientProxyArtefacts`,
  `codegen.proxy.ClientProxyShapeFromElements`), `vauban-weaver` (`ProxyLinkWeaver`), `vauban-api`
  (`ClientProxyClass`, review round 6)
- **Symptom**: `@ApplicationScoped class StatefulSvc extends StatefulFinalBase implements Stateful`, where
  `StatefulFinalBase` declares `public final String state()`, which implements `Stateful.state()`. Looked up or
  injected as `Stateful`, which CDI 4.1 §3.10 allows, the bean resolved, and `state()` ran on the client proxy, not on
  the contextual instance: it returned the proxy's field, `null`. A normal-scoped producer of such a class did the
  same.
- **Repro**: `InheritedFinalMethodTest` in `vauban-core` (`anInterfaceMethodTheFinalMethodImplements`,
  `injectedAsTheInterface`, `producedThroughTheInterface`, `objectMethods`, `aSuperclassAboveTheFinalMethod`);
  `UnnameableMemberBytecodeEmissionTest.finalMethodProxy` in `vauban-processor`;
  `VaubanGeneratorTest.proxyBeyondAFinalMethod` in `vauban-maven-plugin`; `ProxyLinkWeaverTest
  .retargetLeavesAProxyBeyondAFinalMethod` in `vauban-weaver`; `ClientProxyInheritanceCrossCheckTest
  .beyondAFinalMethod` pins that the run-time and processor shapes agree.
- **Cause** (proved by the red runs): every path built the client proxy as a subclass of the bean class, whatever type
  the bean was requested as; a subclass cannot override the final method, and the interface call resolved to it, on
  the proxy. `RuntimeClientProxyGeneratorTest.shouldNotOverrideFinalMethods` pinned that shape.
- **Fix**: Weld's rule (`ClientProxyProvider`, weld-core-impl 5.1.7: a bean whose types are not all proxyable gets a
  proxy whose superclass is the requested class, else `Object`, with every bean interface). Vauban keeps one proxy per
  bean, `<Bean>_ClientProxy`: `ForwardedMethods.proxySuperclass` picks the class it extends, the bean class when it
  has no final method, else the nearest class above with none that a proxy can extend, else `Object`. Every class type
  the bean may be requested as (no final method in its walk, BUG-20261118-19) is that class or above it. The proxy
  forwards that class's methods as before, implements the interfaces of the bean class, and forwards each of their
  methods through its interface, so a final method implementing one is reached on the instance; `toString`,
  `hashCode` and `equals` are forwarded through `Object`. The run-time generator, `vauban:generate` (the same
  generator) and the processor build that shape; the processor emits it as bytecode, which Java source could not name
  after the bean. The weaver no longer retargets a proxy that does not extend its bean onto a `(ProxyLink)`
  constructor. A lookup checks the proxy is an instance of the requested class, for a class above the final method
  that no proxy can extend from the bean's package. The final-method walk of a lookup is cached per requested class
  (`ClassValue`). Frozen deployments need no new record: the shape is a function of the bean class, and the provider
  instantiates the class under the same name. An intercepted bean with a final method stays a deployment error.
- **Fix, review round 6**: a proxy that no longer extends its bean class no longer tells it through its superclass,
  which Cassini read (`FilterEntry`, the `@Provider`, `@Priority`, `@PreMatching` and name bindings of a filter,
  CASSINI-066). Vidocq's EL unwrapper (`VaubanProxyUnwrapper`) rightly keeps the superclass: Peano's accessor table
  casts the base to the class the unwrapper names, which such a proxy is no instance of.
  `io.vidocq.vauban.api.ClientProxyClass.beanClassOf(Class)` states it, from what every path already guarantees: the
  proxy is named `<Bean>_ClientProxy`, in the bean's package and loader, frozen or not. It loads that name through the
  proxy's loader, keeps it when it is of the proxy's package and a subclass of what the proxy extends. A producer's
  proxy (`<Produced>$$<hash>_ClientProxy`) answers its superclass when the hash of that class's name matches, nothing
  past a final method of the produced class. Every path generates the proxy final, so a class only named like one
  answers nothing (review round 8). Cached per class (`ClassValue`).
  `ClientProxyShape.PROXY_SUFFIX` is its `SUFFIX`. Two gaps of the shape: `proxySuperclass` jumped to `Object` past the
  first class above the final method that a proxy cannot extend; it now walks on to the nearest one it can (`PublicTop`
  above `PackageMethodMiddle`). And a lookup as an interface skipped the instance check: an interface the proxy cannot
  name, package-private in another package, returned a proxy that is none of it; it now throws
  `UnproxyableResolutionException`, `... which its client proxy <name> is no instance of`, as for a class.
- **Verification, review round 6**: red, `stack-bug20-red.log`, "Red run 5" (on 6686465c: the proxy of `BelowMiddle`
  extended `Object`, the lookup as `PublicTop` threw) and "Red run 6" (a `ClientProxyClass` stub that reads the
  superclass, as the consumers did: empty on the run-time, processor and `vauban:generate` paths and for the API
  cases of a final method or a final class; the lookup as `HiddenFace` threw nothing). Green, `stack-bug20-dev.log`,
  "Dev run 6".
- **Verification**: red, `stack-bug20-red.log`: "Red run 1" (on 47810264: the three interface calls returned `null`,
  the processor's proxy extended `StatefulService`), "Red run 2" (the weaver of 47810264 rewrote the proxy), "Red run
  4" (`vauban:generate` wrote a subclass of the bean; "Red run 3" failed on the test's resource name). Green,
  `stack-bug20-dev.log`, "Dev run 4" and "Dev run 5".
- **Found by**: the check that review round 3 of BUG-20261118-19 asked for first, `stack-bug19-dev.log`, "Dev run 3".

## BUG-20261118-19 — A bean that inherits a final method passes the proxyability checks

- **Date**: 2026-11-18
- **Status**: FIXED on `fix/client-proxy-bridge`
- **Module**: `vauban-core` (`bean.validation.DeploymentValidator`, `container.InterceptorBeanWrapper`,
  `container.DisposerInvoker`, `proxy.ForwardedMethods`), `vauban-processor` (`VaubanProcessor`)
- **Symptom**: `@ApplicationScoped class InheritsFinal extends FinalBase {}`, where `FinalBase` is no bean and declares
  `public final String tag()`. Injected, it started; looked up, it resolved; a call of `tag()` ran on the proxy
  itself. The processor compiled it, whether `FinalBase` was in the compilation or in a library. CDI 4.1 §3.10 makes
  such a class unproxyable: a deployment problem when injected, an `UnproxyableResolutionException` when looked up
  (TCK `NonPrivateNonStaticSuperclassMethodTest`, assertion `bbb`, a superclass that is a bean).
- **Repro**: `InheritedFinalMethodTest` in `vauban-core` (injected, looked up, intercepted, a package-private final
  method of another package, and a private or static final method that leaves the bean proxyable; review round 3: a
  bean `Svc extends FinalBase implements Api` injected and looked up as `Api`, as `Svc`, and a normal-scoped producer
  of such a class); `UnproxyableBeanValidationTest.inheritedFinalMethodBeanIsRejected`,
  `finalMethodOfACompiledSuperclassIsRejected` and `inheritedFinalMethodBeanInjectedAsAnInterfaceCompiles` in
  `vauban-processor`.
- **Cause** (proved by the red run): the validator walks the superclasses through the index and stopped at the first
  one the index does not hold: a class that is no bean, or a library's. At first use, `firstFinalMethod`
  (`InterceptorBeanWrapper`) read the bean class's own methods only. Red: `stack-bug19-red.log`, "Red run 2": the
  injected and looked-up cases and both processor cases. The intercepted case was already rejected at start, by the
  validator's reflective walk of intercepted beans; it is pinned.
- **Fix**: one walk, `ForwardedMethods.firstFinalMethod(TypeView)`: from the class up to `Object`, excluded, a final
  method neither static nor private, bridges and synthetic methods aside. A package-private one of another package
  counts, as CDI 4.1 §3.10 ("public, protected or default visibility") and Weld count it, and as the validator's index
  walk always did. The index walk hands over to it at a superclass the index does not hold: by reflection at start,
  on the compiler's elements in the processor (`DeploymentValidator.finalMethodBeyondTheIndex`). The requested type
  decides, not the bean class: the validator already judged the injection point's type, and a lookup
  (`VaubanContainer.select`, `BeanManager.getReference`, `Instance`) now judges the looked-up type
  (`InterceptorBeanWrapper.getOrCreateProxy(bean, type)`); an interface is always proxyable. Round 2 checked the bean
  class when the proxy was created, which refused a bean looked up as an interface it implements, and a normal-scoped
  producer of a library class: review round 3 moved the check. An intercepted bean is still judged on its class: its
  subclass is the bean class's.
- **Fix, review round 6**: a disposer of a normal-scoped bean whose class inherits a final method never ran.
  `DisposerInvoker` asked `BeanManager.getReference(declaringBean, declaringClass)`, which this fix refuses for such a
  class, and the `UnproxyableResolutionException` was swallowed with every other failure of the disposer. The
  receiver of a disposer of a normal-scoped declaring bean is now its contextual instance
  (`VaubanContainer.selectByBeanClass`), as a producer method's is. CDI 4.1 §6.1 (`Contextual.destroy()`, TCK audit
  "contextual", assertion `ab`) wants what the destruction throws caught, so the catch stays: it now logs a WARNING,
  `Disposer method <class>.<method> failed; the produced instance is destroyed all the same`, with the exception.
  No TCK test throws from a disposer. Red, `stack-bug19-red.log`, "Red run 4" (on 6686465c:
  `disposerOfABeanWithAFinalMethod`, nothing disposed, then in `InheritedFinalMethodTest`; "Red run 5", moved to
  `DisposerOfAFinalMethodBeanTest`). Green, `stack-bug19-dev.log`, "Dev run 4", with `disposerThatThrows`.
- **Verification, review round 3**: red, `stack-bug19-red.log`, "Red run 3" (on 4ac9fce6): the package-private
  final method of another package started, and `Svc` looked up as `Api` and the producer looked up as `Api` threw
  `UnproxyableResolutionException`. `Svc` injected or looked up as `Svc` was already refused, and the processor
  already compiled the interface injection: both are pinned. Green, `stack-bug19-dev.log`, "Dev run 3". The check
  asked first, a bean injected as an interface whose class inherits a final method, found BUG-20261118-20.
- **Verification**: red, `stack-bug19-red.log`, "Red run 2" (on fd1f66c3; "Red run 1" asked a deployment error of a
  bean nothing injects, which CDI does not). Green, `stack-bug19-dev.log`, "Dev run 2": `vauban-core` 1639 tests,
  `vauban-processor` 391, no failure; the full build of the lane tip, `stack-bug49-build.log`.
- **Found by**: the review of BUG-20261117-49, round 2.

## BUG-20261118-18 — The run-time intercepted subclass lets a bridge to a superclass method past its interceptors

- **Date**: 2026-11-18
- **Status**: FIXED on `fix/client-proxy-bridge`
- **Module**: `vauban-core` (`interceptor.InterceptorSubclassGenerator`, `InterceptedEmitter`, `InterceptedShape`),
  `vauban-processor` (`codegen.interceptor.InterceptedShapeFromElements`)
- **Symptom**: an intercepted bean compiled without the processor,
  `@Traced class TracedTaggedBySuperclass extends PlainTagBase implements Tagged<String>`, where `PlainTagBase`
  declares `public String tag(String)` and `Tagged<T>` a default `tag(T)`. A call typed by `Tagged` reaches the
  contextual instance (BUG-20261118-17) but not the interceptor: the interceptor sees one call of two.
- **Repro**: `BridgeMethodClientProxyTest.tagThroughABridgeReachesTheInterceptor` in `vauban-core`;
  `InterceptedShapeFromElementsTest.aBridgeToASuperclassMethodGetsABridgeOfItsOwn` in `vauban-processor` compares the
  bridges of the two front-ends.
- **Cause** (proved by the red run): the bean's bridge `tag(Object)` calls `PlainTagBase.tag(String)` by
  `invokespecial`, and the `$$Intercepted` subclass the container generates overrides `tag(String)` alone: the bridge,
  which it inherits, runs past that override. javac gives a source subclass, the processor's, a bridge `tag(Object)`
  of its own that calls `this.tag(String)`. Red: `stack-bug17-red.log`, "Red run 2" (the repro with its `@Disabled`
  switched off), and `stack-bug18-red.log`, "Red run 1" (the repro enabled, on d2b4468f): expected `[tag, tag]` but
  was `[tag]`.
- **Fix**: `InterceptedShape` carries the bridges the subclass declares, and `InterceptedEmitter` writes each as
  javac does: `ACC_BRIDGE | ACC_SYNTHETIC`, the arguments cast to the intercepted method's parameter types, an
  `invokevirtual` of that method, which reaches the subclass's override. The run-time front-end takes one per bridge of
  the bean that calls an intercepted superclass method of another descriptor by `invokespecial`, read from the class
  bytes (`BridgeCalls`, BUG-20261118-16). The processor's front-end takes one per interface method an intercepted
  class method implements with another erasure, from a class that does not implement the interface: the rule its
  bytecode proxies use (BUG-20261118-17), now one method, `InterceptedShapeFromElements.superCalledBy`. Its bytecode
  subclass (a member signature source cannot name) gets the bridges; its source subclass gets them from javac.
- **Verification, review round 3**: the processor's bytecode subclass, emitted for a bean whose inherited member names
  a type its package cannot name: `UnnameableMemberBytecodeEmissionTest.interceptedSubclassBridge` loads
  `TaggedTaker$$Intercepted` and finds the bridge `tag(Object)`. Red, `stack-bug18-red.log`, "Red run 2" (the
  processor's shape builders of a2550f3b): `NoSuchMethodException`.
- **Verification**: green, `stack-bug18-dev.log`, "Dev run 2": `vauban-core` 1635 tests, `vauban-processor` 389, no
  failure ("Dev run 1": the processor test's expected string was written wrong); the full build of the lane tip,
  `stack-bug49-build.log`.
- **Found by**: the proof of BUG-20261118-17.

## BUG-20261118-17 — A client proxy generated as bytecode misses a bridge to a superclass method

- **Date**: 2026-11-18
- **Status**: FIXED on `fix/client-proxy-bridge`
- **Module**: `vauban-core` (`proxy.RuntimeClientProxyGenerator`), `vauban-processor`
  (`codegen.proxy.ClientProxyShapeFromElements`)
- **Symptom**: a normal-scoped `TaggedBySuperclass extends PlainTagBase implements Tagged<String>`, where
  `PlainTagBase` declares `public String tag(String)` and `Tagged<T>` a default `tag(T)`. A call typed by the bean
  reaches the contextual instance; a call typed by `Tagged<String>` runs on the proxy instance. Seen while proving
  BUG-20261117-49 (`vauban-b49-probe.log`, "tagged typed by Tagged<String>: null x").
- **Repro**: `BridgeMethodClientProxyTest.tagThroughABridgeToASuperclassMethod` in `vauban-core` (the proxy the
  container generates); `IndexProxyInheritedMethodsTest.sourceProxyForwardsABridgeToASuperclassMethod` and
  `bytecodeProxyForwardsABridgeToASuperclassMethod` in `vauban-processor` (the processor's source proxy, and its
  bytecode proxy of a bean with private constructors only); `ClientProxyInheritanceCrossCheckTest
  .oneOverridePerMemberSignature` pins the shapes.
- **Cause** (proved by the red run): javac implements `Tagged<String>.tag(T)` with `PlainTagBase.tag(String)` through
  the bridge `tag(Object)` it puts in `TaggedBySuperclass`, which calls `PlainTagBase.tag(String)` by
  `invokespecial` (`javap`). A proxy inherits that bridge: its own `tag(String)` does not catch a call through it,
  which runs the superclass body on the proxy. Both bytecode paths forwarded `tag(String)` alone: the run-time and
  `vauban:generate` proxies, and the processor's bytecode proxy (a bean with private constructors only, a proxy with
  a `MethodHandle` forward). The processor's source proxy was right: javac gives it a bridge `tag(Object)` that calls
  `this.tag(String)`. Red: `stack-bug17-red.log`, "Red run 1": typed by the supertype `null`, and
  `PrivateCtorTaggedService_ClientProxy` instead of the bean; the source case green.
- **Fix**: a bytecode proxy also forwards such a bridge with its own descriptor, to the bridge of the contextual
  instance. The run-time generator reads the bridges from the class bytes (`BridgeCalls`, BUG-20261118-16): it takes
  one whose `invokespecial` calls a superclass method of another descriptor, that method not final. The first version
  judged by names, a class that declares no method of the bridge's name and arity, and missed the bridge of
  `TaggedWithOverload`, which declares `tag(Integer)` (review round 2); it also forwarded the bridge of `FinalTagged`,
  whose `tag(String)` is final, which the processor does not. The processor takes an interface method a forwarded
  class method implements with another erasure, declared by a class that does not implement the interface: where javac
  writes such a bridge. Both front-ends give the same shape (`ClientProxyInheritanceCrossCheckTest`).
- **Not covered**: the intercepted subclass, BUG-20261118-18 (fixed in the next commit).
- **Verification, review round 2**: red, `stack-bug17-red.log`, "Red run 4" (generators of 8d9974fc; "Red run 3"
  lacked the BUG-49 fixup and did not compile): `tagThroughABridgeNextToAnOverload` and
  `aBridgeToASuperclassMethodIsReadFromTheClassBytes` fail. Green, `stack-bug17-dev.log`, "Dev run 4".
- **Verification**: red, `stack-bug17-red.log`, "Red run 1" (on f14552e3, which the replay that shortened its subject
  made 9ad3129c, same tree). Green, `stack-bug17-dev.log`, "Dev run 3": `vauban-core` 1631 tests (19 skipped, the
  BUG-20261118-18 repro among them), `vauban-processor` 387, no failure ("Dev run 2" ran before the processor's
  co-located shape took the bridges, which the cross-check caught); the full build of the lane tip,
  `stack-bug49-build.log`.
- **Found by**: the proof of BUG-20261117-49.

## BUG-20261118-16 — The run-time intercepted subclass skips a public method of a non-public superclass

- **Date**: 2026-11-18
- **Status**: FIXED on `fix/client-proxy-bridge`
- **Module**: `vauban-core` (`interceptor.InterceptorSubclassGenerator`)
- **Symptom**: a bean compiled without the processor, bound to an interceptor, whose public method comes from a
  superclass that is not public: `public class VisiblyInheritedSupplier extends HiddenSupplierBase`, where the
  package-private `HiddenSupplierBase` declares `public String get()`. The `$$Intercepted` subclass the container
  generates does not override `get()`: a call runs the bean's method without its interceptors. The processor's
  subclass intercepts it. Seen while proving BUG-20261117-49 (`vauban-b49-probe.log`,
  `DependentVisible$$Intercepted get=hidden intercepted=[]`).
- **Repro**: `InterceptedShapeFromElementsTest.aMethodBehindAVisibilityBridgeIsIntercepted` in `vauban-processor`
  compares `InterceptorSubclassGenerator.fromClass` with the processor's shape for a bean that inherits `hidden()`
  through its own visibility bridge and for one that inherits it through a public class between;
  `BridgeMethodClientProxyTest.getInheritedThroughAHiddenSuperclass` in `vauban-core`, its bean now `@Traced`, starts a
  container and counts the interceptor's calls.
- **Cause** (proved by the red run): javac gives a public class a visibility bridge, `ACC_BRIDGE` with the method's
  own descriptor, for each public method of a non-public superclass, and `getMethods()` lists that bridge in the
  method's place. The generator's walk over `getMethods()` refuses a bridge (`shouldIntercept`), and its walk over
  the inherited non-public methods leaves the public ones to the first walk: neither intercepted the method. Red:
  "fromElements only: [hidden()]", and the interceptor saw `[]` (`stack-bug16-red.log`, "Red run 1").
- **Fix**: the walk over `getMethods()` takes a visibility bridge for the method it copies, when it is a public method
  of a class that is not public. `BridgeCalls` reads what a bridge calls from the class bytes of its class, through
  the Class-File API: an `invokespecial` on a superclass method of the bridge's own descriptor makes a visibility
  bridge. Any other bridge stands for itself. A class without bytes on its loader, one defined in memory, is judged by
  names instead: a class that declares a method of the bridge's name and arity calls it, which an overload misleads
  (`BridgeCallsTest` pins both). The first version of the fix judged by names alone, and missed `put(String)` of
  `OverloadingVisibly extends HiddenPutBase`, which declares `put(Integer)` (review round 2). The emitted
  `super.get()` still names the bean, and reaches the method through the bridge. The two front-ends now give the same
  method set (`InterceptedShapeFromElementsTest`, 22 cases).
- **Verification, review round 2**: red, `stack-bug16-red.log`, "Red run 2", and `stack-bug17-red.log`, "Red run
  4": `OverloadingVisibly` loses `put(String)` on the run-time path. Green, `stack-bug17-dev.log`, "Dev run 4":
  `vauban-core` 1635 tests, `vauban-processor` 388, no failure.
- **Verification**: red, `stack-bug16-red.log`, "Red run 1" (on 65ef933f): both tests fail on the defect. Green,
  `stack-bug16-dev.log`, "Dev run 2": `vauban-core` 1629 tests, `vauban-processor` 385, no failure ("Dev run 1"
  intercepted override bridges as well, which the two cross-checks of BUG-20261004-06 caught); the full build of the
  lane tip, `stack-bug49-build.log`.
- **Found by**: the proof of BUG-20261117-49.

## BUG-20261118-12 — The start skips the class-level rules of a class an extension adds through `ScannedClasses`

- **Date**: 2026-11-18
- **Status**: FIXED on `fix/vauban-bug-stack` (lane `stack/l3-container`)
- **Module**: `vauban-core` (`VaubanContainerBuilder.validateClassDefinitions`)
- **Symptom**: `@Dependent ScannedSetter<T> { @Inject void set(T value) }`, which only an extension's
  `@Discovery` adds (`ScannedClasses.add`), starts, though an initializer's parameter of a type variable is a
  definition error (CDI 4.1, 5.2.3); the same class given to `addBeanClass` fails the start.
- **Repro**: `TypeVariableInjectionPointTest#aClassAnExtensionAddsThroughScannedClassesHasItsDefinitionRulesChecked`
  in `vauban-core`.
- **Cause** (proved): `build` validates the class-level rules of `nonBceClasses`, the bean classes it was given,
  before discovery; the classes `addExtensionScannedClasses` loads are indexed and discovered as beans, never in
  that list.
- **Fix**: `validateClassDefinitions` validates the classes an extension added too, the build compatible
  extensions left out as for the bean classes. A class without a bean-defining annotation, which discovery makes a
  bean, is validated as one after the enhancements (BUG-20261117-30's check of the descriptor beans). The frozen
  path reads the classes the record lists through the same method.
- **Who sees the change**: an application whose extension adds a class that breaks a class-level rule now fails the
  start with a `DefinitionException`.
- **Verification**: red on the main code of "build resolves inherited observer params" (`stack-l3-red.log`,
  "BUG-20261118-12 red": the container started). Green: `stack-l3-green.log`, "BUG-20261118-12 fix" (vauban-core
  1655 tests, 13 skipped; vauban-processor 390, 16 skipped), then the whole reactor, `stack-l3-b12-full.log`.
- **Found by**: the review round 1 of the `stack/l3-container` lane.

## BUG-20261118-11 — The build resolves no parameter of an inherited or a static observer

- **Date**: 2026-11-18
- **Status**: FIXED on `fix/vauban-bug-stack` (lane `stack/l3-container`)
- **Module**: `vauban-core` (`BeanDiscovery`, new `ObserverInjectionPoints`, `DeploymentValidator`),
  `vauban-processor` (`VaubanProcessor`)
- **Symptom**: a bean that inherits `on(@Observes Pung pung, Tool tool)` from a superclass, with two beans of type
  `Tool`, or that declares that observer static, compiles without an error, and the start then refuses it
  (`Observer method ...: ambiguous dependency for parameter arg1`). An instance observer the bean class declares fails
  the build, as it should.
- **Repro**: in `vauban-processor`,
  `VetoedInterceptorCompileTimeTest#anInheritedOrStaticObserversUnresolvableParameterFailsTheBuildAndTheStart`.
- **Cause** (proved): the build resolves the observer parameters through the bean's injection points, and
  `BeanDiscovery.addObserverInjectionPoints` reads the instance observers the bean class declares only. The start
  checks every observer the container registers (`VaubanContainer.validateObserverParameters`), a static one and an
  inherited one included (BUG-20261117-26). The review of BUG-20261117-30 read it as "`DeploymentValidator` has no
  observer pass": it has one for the declared instance observers, through the injection points.
- **Fix**: `BeanDiscovery.otherObserverInjectionPoints` (in the new `ObserverInjectionPoints`) gives, for a bean class,
  the parameters of its static observers and of the observers it inherits, the superclass's type variables read as the
  bean class gives them (`IndexClassValidator.typeArguments`, now public). Inherited observers are found by the start's
  rules (`ObserverDisposerDiscovery.getAllInheritedMethods`): a private superclass method is not inherited, and a method
  a class below declares with the same name and erased parameter types overrides it, an observer or not. A parameter
  left a type variable is the definition rules' (CDI 4.1, 5.2.3); `EventMetadata` is no injection point. A superclass
  the processor's index does not hold, one without a bean-defining annotation, is read from the compiler's elements.
  `DeploymentValidator.otherObserverInjectionPoints` resolves them like the bean's own, for its managed bean, and the
  processor adds their types to the dependency types: a type no bean of the compilation declares leaves the start to
  decide with a warning, as for a declared observer.
- **Who sees the change**: an application whose inherited or static observer has a parameter no bean satisfies, or
  more than one does, now fails the build where it failed the start.
- **Verification**: red on the main code of "generic class scope added by an extension" (`stack-l3-red.log`,
  "BUG-20261118-11 red, run 3": the build succeeded). Green: `stack-l3-green.log`, "BUG-20261118-11 fix, run 4"
  (vauban-processor 390 tests, 16 skipped) and "BUG-20261118-11 fix: ObserverInjectionPointsTest"; the whole reactor
  (vauban-core 1654 tests, 13 skipped),
  `stack-l3-b11-full.log`.
- **Review round 2**: the build matched an inherited observer by name against the observers below only, and read a
  private superclass observer; the start skips a private method and overrides on name and erased parameters, observer
  or not, so the build refused what the start accepts. Red: `stack-l3-red.log`, "BUG-20261118-11 review round 2 red"
  and "... red, run 2" (`ObserverInjectionPointsTest#anObserverIsInheritedAsTheStartFindsIt`, and
  `VetoedInterceptorCompileTimeTest#anObserverTheStartDoesNotInheritIsNoErrorAtBuildAtStartAndFrozen`: an ambiguous
  `Tool` at build). Green: `stack-l3-green.log`, "BUG-20261118-11 review round 2 fix, run 2".
- **Review round 3**: an override of a generic superclass's observer, `BridgedToolListener extends
  GenericToolListenerBase<Tool>` declaring `on(Pung, Tool)` over `on(@Observes Pung, T)`, is one at start through
  javac's bridge `on(Pung, Object)`; the compiler's elements have no bridge, and the build resolved `Tool`. The build
  substitutes the type arguments before erasing, as the compiler compares an override. Red: `stack-l3-red.log`,
  "BUG-20261118-11 review round 3 red". Green: `stack-l3-green.log`, "BUG-20261118-11 review round 3 fix".
- **Found by**: the review round 1 of the `stack/l3-container` lane.

## BUG-20261118-06 — The processor misses a type-use-only annotation on a constructor

- **Date**: 2026-11-18
- **Status**: FIXED
- **Module**: `vauban-processor` (`apt.MirrorTypeAnnotations`, `apt.ConstructorTypeUses`)
- **Symptom**: `@A Fixture(int size) {}`, `@A` targeting `TYPE_USE` only: the class file records `@A` on the
  constructor's return type (`METHOD_RETURN`), the bytecode scan indexes it on the constructor's `RETURN` target, the
  processor does not. An annotation that targets `TYPE_USE` and `CONSTRUCTOR` is indexed by both.
- **Repro**: `ElementScannerTypeAnnotationsTest#aTypeUseOnlyAnnotationOnAConstructor` in `vauban-processor`,
  disabled with this id.
- **Cause, proved**: red on 1706478a with the `@Disabled` lifted (`stack-l2d-red.log`, "RED BUG-20261118-06 on
  1706478a (assertions fixed)": `expected: <[RETURN 0 0 [] A{}]> but was: <null>`, and the same for an annotation
  with members). A probe processor on javac 25 printed, for `@A Fixture(int)`, no annotation in
  `getAnnotationMirrors()`, none on `getReturnType()` nor on the `ExecutableType`'s return type, and `@A` in the
  tree's modifiers (`Trees.getTree(constructor).getModifiers()`): javac keeps the type annotation in its symbol's
  type attributes, which no `javax.lang.model` call gives.
- **Fix**: `ConstructorTypeUses` reads the constructor's annotations from its tree (`com.sun.source.util.Trees`,
  `jdk.compiler`, which the processor already requires), keeps those whose `@Target` names `TYPE_USE` and not
  `CONSTRUCTOR`, and evaluates their members as javac does: a literal converted to the member's type, a sign, a
  cast, parentheses, a constant or an enum constant by its field, a class literal, an array written whole or as one
  element, a nested annotation. `MirrorTypeAnnotations.constructor` adds them after the model's, on `RETURN`. The
  scanner reads them through `new ElementScanner(ProcessingEnvironment)`, which the processor and
  `CompilerDeclarations` now use; a compiler without trees, and a class read from the class path, give none.
- **Verification**: `ElementScannerTypeAnnotationsTest`, 12 tests: the repro, an annotation with each kind of member,
  one with each primitive written with a cast, a sign and a character, and `theMethods` comparing every method
  without the removal it carried (`stack-l2d-dev06.log`, "BUG-18-06 fix (5)"); `ConstructorTypeUsesValueTest`;
  processor 444 (14 skipped), maven-plugin 172, architecture 56 (`stack-l2d-dev06.log`, "BUG-18-06 fix (6)" and
  "(8)"). New code covered 100 of 111 lines (JaCoCo, "(8)").
- **Round 1**: a compiler without trees, ECJ say, gets a `NOTE` once, from `VaubanProcessor.init`, that says those
  annotations are not read (`ConstructorTypeUsesNoteTest`, green in `stack-l2d-r1.log`, "Round 1 (3)"); javac gets
  none. No red: the note is new.
- **What stays**: a member written as a constant expression of several operands that names a constant,
  `NAME + "!"`, is not evaluated: the processor leaves out the annotation that holds it
  (`aComputedMemberIsLeftOut`). javac joins literals alone as it parses them.
  The type-use-only annotations come after those that also target `CONSTRUCTOR`: a constructor that writes one of
  the second kind after one of the first has them in another order than the class file's (by construction, not
  tested).
- **Found by**: BUG-20261117-60, lane L2.

---

## BUG-20261118-05 — An exception of `@Enhancement` is swallowed outside the full extension cycle

- **Date**: 2026-11-18
- **Status**: FIXED on `fix/vauban-bug-stack`
- **Module**: `vauban-core` (`extensions.BceProcessor.processEnhancementOnly`, `replayEnhancementForTargets`)
- **Symptom**: an `@Enhancement` method that throws does not fail the deployment on two paths: the enhancement of an
  unprocessed archive at start and under `vauban:generate` (`processEnhancementOnly`), and the replay of
  `META-INF/vauban-bce-runtime.list` for a pre-processed jar (`replayEnhancementForTargets`). The start and the
  generation go on with the archive enhanced as if nothing happened. The full cycle reports the same exception.
- **Repro**: `BceRuntimeReplayTest$EnhancementFailures` in `vauban-core` (replayed for a pre-processed jar; run over
  an unprocessed archive, the extension reached through the replay list alone so that the full cycle does not run
  it) and `EnhancementFailureGenerationTest` in `vauban-maven-plugin`. Red without the fix
  (`stack-l1-r3-1805.log`, "BUG-18-05 red: core start paths (unprocessed isolated, no service file)" and
  "BUG-18-05 red: vauban:generate": nothing was thrown).
- **Cause**: both methods filled error lists, `processEnhancement`'s and the `EnhancementSink`'s, and never read
  them; their own `catch (Exception _)` dropped a constructor that failed.
- **Fix**: both throw the `DeploymentException` of their errors, the first exception as its cause, through
  `extensions.internal.DeploymentFailures.failOnDeploymentErrors` (not exported), the helper the container's later
  phases now share. A constructor that fails is recorded too (`@Enhancement of <extension> failed: <exception>`).
  `vauban:generate` fails with it (`MojoExecutionException` "Vauban generation failed").
- **Found by**: the review of BUG-20261118-04.

## BUG-20261118-04 — An exception thrown in `@Discovery` is swallowed

- **Date**: 2026-11-18
- **Status**: FIXED on `fix/vauban-bug-stack`
- **Module**: `vauban-core` (`extensions.BceProcessor.processDiscovery`, `container.VaubanContainerBuilder`),
  `vauban-processor` (`VaubanProcessor`)
- **Symptom**: a `@Discovery` method that throws does not fail the deployment: the build, the start and the freeze
  go on without what it had not registered, and nothing says why. Every other phase sends its exception to the
  deployment errors (`BceProcessor.runPhase`).
- **Spec**: no text of CDI 4.1 tells a container to ignore it. The Javadoc of `BuildCompatibleExtension`,
  `@Discovery` and `Messages` (cdi-api 4.1.0) names deployment problems and definition errors only; the comment
  "silently ignored per spec" had no source. CDI 4.1 treats an exception of a `BeforeBeanDiscovery` or an
  `AfterTypeDiscovery` observer, the portable events of the same phase, as a definition error.
- **Repro**: `VetoedInterceptorCompileTimeTest#anExceptionOfDiscoveryFailsTheBuildTheStartAndTheFreeze` in
  `vauban-processor`: the build, the start, the freeze and the start after it. Red without the fix
  (`stack-l1-r2-1804.log`, "BUG-18-04 red: processor test, no fix": the start threw nothing).
  `BceProcessorFailureToleranceTest#aDiscoveryMethodThatThrowsIsADefinitionError` and `...FailsTheStart` in
  `vauban-core`.
- **Cause**: `BceProcessor.processDiscovery` caught every exception and dropped it.
- **Fix**: `DiscoveryResult` carries the definition errors and their causes, the exception the method threw
  (`@Discovery of <extension> failed: <exception>`). The start throws them as a `DefinitionException`, the
  exception as its cause, right after `@Discovery`; the processor reports them as compilation errors. The freeze
  runs the start's path: it records nothing, and the start after it runs the extension and fails.
- **Found by**: BUG-20261118-03, whose extension's `NullPointerException` vanished.

## BUG-20261118-03 — `ClassConfig.info()` is `null` for an annotation type outside the index

- **Date**: 2026-11-18
- **Status**: FIXED on `fix/vauban-bug-stack`
- **Module**: `vauban-core` (`extensions.VaubanClassConfig`)
- **Symptom**: in `@Discovery`, `MetaAnnotations.addQualifier(Graded.class).info()` is `null` when the index does not
  hold `Graded`: an extension that reads `info().methods()` records nothing, and the start goes on without a word.
  The language model never gives a `null` `ClassInfo`; for an indexed annotation type, `info()` describes it.
- **Repro**: `AnnotationMembersOutsideTheIndexTest#theMembersOfAnAnnotationOutsideTheIndex` in `vauban-core`.
  Red without the fix (`stack-l1-r2-1803.log`, "BUG-18-03 red: test enabled, no fix": `but was: <[]>`).
- **Cause**: the meta-annotation constructor of `VaubanClassConfig` looks the type up in the index alone
  (`lookup.getClass`) and keeps `null` when it is missing; its method configs fall back to reflection
  (`ReflectionMethodInfo`, BUG-20261118-02). The extension's `NullPointerException` was not reported because
  `BceProcessor` swallowed every `@Discovery` exception: BUG-20261118-04.
- **Fix**: `info()` is `VaubanClassInfo.of(name, lookup)`, the index's class or the class file described as
  BUG-20261117-31 describes a class outside the index; the member configs are its `methods()`, which agree with the
  indexed path. `ReflectionMethodInfo` stays for a type nothing describes (its stub has no member), such as an
  annotation type read outside the extension phases. The processor runs the same `BceProcessor.processDiscovery`.
- **Found by**: the test of BUG-20261118-02.

## BUG-20261118-02 — The members of an annotation type outside the index have no return type

- **Date**: 2026-11-18
- **Status**: FIXED on `fix/vauban-bug-stack`
- **Module**: `vauban-core` (`extensions.ReflectionMethodInfo`, `extensions.VaubanClassConfig`)
- **Symptom**: in `@Discovery`, `MetaAnnotations.addQualifier(Graded.class).methods()`, for an annotation type the
  index does not hold, gives members whose `info().returnType()`, `receiverType()` and `declaringClass()` are `null`,
  and whose `annotations()` are empty though `hasAnnotation(Nonbinding.class)` is true. The language model forbids a
  `null` return type and declaring class, and a member is an instance method, with a receiver type. For an indexed
  annotation type, `VaubanMethodInfo` gives them all.
- **Repro**: `AnnotationMembersOutsideTheIndexTest#theMethodConfigsOfAnAnnotationOutsideTheIndex` in `vauban-core`:
  the members of `Graded` (a `String`, a `@Nonbinding int`, a `Class<?>` and an enum array), indexed and not. Red
  on 16088760 (`stack-l1-r1.log`, "R1 BUG-20261118-02 red": `returns null, receiver null, declared by null`, and no
  `Nonbinding`, outside the index; the same answers as expected when indexed).
- **Cause**: `ReflectionMethodInfo`, the reflective fallback `VaubanClassConfig` uses for an annotation type outside
  the index, answered `null` or empty for everything but the name, the modifiers and `hasAnnotation(Class)`.
- **Fix**: `ReflectionMethodInfo` takes the language model's `IndexLookup` and gives the generic return type through
  `TypeInfoUtils.fromReflectType` and `TypeMapper`, the annotation type as receiver type and declaring class
  (`VaubanClassInfo.of`), and the member's annotations as `VaubanAnnotationInfo`, as `VaubanMethodInfo` does for an
  indexed annotation type. It stays the reflective fallback it was (`REFLECTIVE_FALLBACKS`).
- **Who sees the change**: an extension that reads the members of a qualifier or an interceptor binding it adds in
  `@Discovery`, of a type outside the index, gets their types and annotations.
- **Verification**: green in `stack-l1-r1.log` ("R1 BUG-20261118-02 with the fix", the `ClassConfig` test).
- **Found by**: the review of BUG-20261117-54, lane L1 round 1.

## BUG-20261118-01 — An inner class's constructor has no receiver type

- **Date**: 2026-11-18
- **Status**: FIXED (2026-11-18, review of batch C, round 1, item 3)
- **Module**: `vauban-indexer` (`model.ClassInfo`, `scanner.ClassFileScanner`), `vauban-processor`
  (`apt.ElementScanner`), `vauban-core` (`langmodel.declarations.VaubanMethodInfo`)
- **Symptom**: for `class Enclosing { class Inner { Inner() {} } }`, `MethodInfo.receiverType()` of `Inner`'s
  constructor is `null` in the processor, at start and frozen. The language model asks for the type of the receiver
  parameter it may declare, `Enclosing`: `null` only "if this method is `static` or is a constructor of a top-level
  class or a `static` nested class", as `Constructor.getAnnotatedReceiverType()` gives it. The language model TCK does
  not check it.
- **Repro**: `VetoedInterceptorCompileTimeTest#anExtensionSeesTheReceiverTypeOfAnInnerClassConstructor` in
  `vauban-processor`, `@Disabled` with this id. Red with the `@Disabled` lifted, on the BUG-20261117-53 fix
  (`stack-l1-bug53.log`, "BUG-53 green attempt ... BUG-20261118-01 repro enabled, expected red": `receiver null`
  all three ways).
- **Cause**: the index does not record whether a class is inner, nor the class that encloses it: the class file keeps
  both in its `InnerClasses` attribute (the `static` flag is not in the class's own access flags), which
  `ClassFileScanner` reads for the simple name only; `ElementScanner` reads neither. BUG-20261117-53's
  `receiverType()` answers `null` for every constructor.
- **Second symptom**: a type variable of the enclosing class used in an inner member class, `T` of
  `Enclosing<T extends Number>` in `T value` of `Inner`, is `T extends java.lang.Object` at start and frozen, where
  the processor shows `T extends java.lang.Number`: no type parameter scope holds the enclosing class's
  (`TypeMapper`, BUG-20261117-33's `declaredBounds`).
- **Red** (lane L2, batch C, on c0ad3a9e, `stack-l2c-red.log`, "RED BUG-20261118-01 on c0ad3a9e"):
  `#anExtensionSeesTheReceiverTypeOfAnInnerClassConstructor`, enabled, and
  `#anInnerClassSeesItsEnclosingClassTypeVariables`, both three ways: `receiver null`, `value T extends
  java.lang.Object` at start and frozen.
- **Fix**: an additive slot `ClassInfo.enclosingClass`, read from the class's own `InnerClasses` entry (an outer class
  named, `ACC_STATIC` clear) and from the compiler's `NestingKind.MEMBER` without `static`; the processor's constructor
  of such a class has its outer instance first (BUG-20261118-33). `VaubanClassInfo.typeParameterScope` encloses the
  enclosing class's scope, so its type variables have their bounds; `VaubanMethodInfo.receiverType` gives the
  enclosing class, parameterized by its type variables, for the constructor of an inner member class.
  `ClassChecksums` counts the enclosing class where there is one: any other class keeps its checksum. A local class's
  constructor keeps a `null` receiver type, as reflection gives it.
- **What remains** (review round 2): a local class sees no type variable of the method that declares it, nor of that
  method's class: `T` of `<T extends Number> Object make()` used in a local class is `T extends java.lang.Object` at
  start and frozen. The index records the enclosing class of an inner member class only; a local class needs its
  `EnclosingMethod` (class, name and descriptor) and that method's type parameters in its scope. The processor does
  not reach a local class at all, and the language model TCK does not either.
- **Verification**: `stack-l2c-dev.log`, "BUG-20261118-01 wip: indexer, core, processor, maven-plugin tests" (green)
  and "BUG-20261118-01: ClassChecksumsTest" (`#theEnclosingClassCounts`).
- **Fix idea** (the one done): an additive slot on the index's `ClassInfo`, the enclosing class of an inner member
  class, read from the `InnerClasses` entry (`ACC_STATIC` clear, an outer class named) and from the compiler's
  `NestingKind.MEMBER` without `static`. It changes the index model that lane L2 (type annotations) changes too, and
  goes with the start's synthetic outer-instance parameter of the same constructors, which the processor does not show
  (as BUG-20261117-43 and the enum constructors of the TCK's group 10): left for a commit of its own.
- **Found by**: the review of the API for BUG-20261117-53.

## BUG-20261117-60 — The language model shows no annotation on a type

- **Date**: 2026-11-18
- **Status**: FIXED — in the index, at start, in the processor and frozen; a constructor's type-use-only annotation is
  missing in the processor (BUG-20261118-06)
- **Module**: `vauban-indexer` (`model.TypeAnnotationInfo`, `scanner.ClassFileScanner`,
  `scanner.TypeAnnotationReader`), `vauban-core` (`langmodel.types`, `langmodel.declarations`,
  `container.ClassChecksums`), `vauban-processor` (`apt.ElementScanner`, `apt.MirrorTypeAnnotations`)
- **Symptom**: every `annotations()` of a type of the language model (`jakarta.enterprise.lang.model.types`) is
  empty, in the processor, at start and frozen: `List<@A String>`, `@A String field`, `String @A []`,
  `extends @A Base<@B T>`, `<@A T extends @B Number>`, `throws @A Exception` and an annotated receiver. A build
  compatible extension cannot read a type-use annotation such as a nullness or validation marker on a type argument.
  The probe of the language model TCK (BUG-20261117-40, group 1) counts 14 failing checks with its type annotation
  checks off and 93 more with them on, in `AnnotatedTypes`, `AnnotatedSuperTypes`, `AnnotatedThrowsTypes` and
  `AnnotatedReceiverTypes`.
- **Repro**: `BceTypeAnnotationsTest#theTypesOfAClassShowTheirAnnotations` in `vauban-core`: red on 81fab2cb
  (`stack-l2-red.log`, "RED BUG-20261117-60 at start"), every annotation missing, the types themselves right:
  `typeParameters [T extends Number, U extends T extends Number]` for
  `[@A T extends @B Number, U extends @C T extends @B Number]`, `arrays String [] []` for `@C String @A [] @B []`.
- **Cause**: the bytecode scan reads no `RuntimeVisibleTypeAnnotations` attribute; the index's `TypeInfo` has no
  place for an annotation; every `Vauban*Type` returns `List.of()` from its annotation methods; the processor's
  `ElementScanner` reads no annotation from a `TypeMirror`.
- **Fix (start)**: the index keeps the type annotations beside its types, not in them: `TypeInfo` is what the
  container compares, and an annotation on a type use changes neither a bean type nor an injection point. A
  `TypeAnnotationInfo` holds the annotation, its target (field, return, receiver, parameter, `throws`, type parameter,
  bound, superclass, interface) with its index, and its path (array, wildcard bound, type argument), in terms of the
  index's types: a parameter by its position in the parameters the index records (after the outer instance of an
  inner class's constructor, or the name and ordinal of an enum's), a bound by its position in the bounds the index
  records (an annotated `Object` it leaves out at `0`), a nested type by its innermost name. `ClassInfo`, `MethodInfo`
  and `FieldInfo` gain a `typeAnnotations` component, in a canonical order, every earlier constructor kept.
  `ClassFileScanner` reads them through `TypeAnnotationReader`, which follows a type path along the index's type:
  `Outer.@A Inner` is kept, `@A Outer.Inner`, which the language model has no type for, is left out, by the depth of
  each inner class the `InnerClasses` attribute gives, read only for a class whose types carry annotations, each class
  of a chain of `InnerClasses` entries counted once: entries that name each other as outer classes, which the JVM
  does not check, end the count. An inner member class's constructor without `MethodParameters` (javac before Java
  21) takes its outer instance first. The language model's `TypeMapper` walks a type and its use
  together (`TypeUse`); a `TypeParameterScope` of the method, then of its class, annotates the bounds of a type
  variable where it is used as its type parameter declares them, and gives the class's `typeParameters()` with
  their own annotations. The types share their annotation queries (`AnnotatedTypeUse`); the annotations take no part
  in their equality.
- **Fix (processor)**: `ElementScanner` reads the annotations on types from the compiler's model
  (`MirrorTypeAnnotations`) into the same `TypeAnnotationInfo`s: runtime-retained ones only, a parameter at its
  position in the parameters the processor records (no outer instance, no enum name and ordinal), a bound at its
  position in the bounds it records, a constructor's annotation that applies to a type use on its `RETURN` target.
  `ElementScannerTypeAnnotationsTest` checks that both indexes agree, member by member. JDK-8225377 (the compiler
  gives the types of a class of the class path without their annotations) does not show with the compiler of Java
  25: `TypeAnnotationsProcessorTest#aClassPathClassShowsItsTypeAnnotations` reads a library compiled apart and sees
  every annotation; with the class-file read the brief planned switched off it stays green (`stack-l2-red.log`,
  "PROOF JDK-8225377"), so the processor reads no class file.
- **Fix (frozen)**: `ClassChecksums` counts the type annotations of a class, a field and a method where there are
  any: the record of a build by an earlier Vauban stays valid for a class without them
  (`aClassWithRawSupertypesKeepsItsChecksum` keeps its golden value) and is refused for a class with them, so the
  start runs the extensions again. `TypeAnnotationsGenerationTest` in `vauban-maven-plugin` shows that `vauban:generate`
  runs an `@Enhancement` with the annotations shown.
- **Verification**: `stack-l2-red.log`, "RED BUG-20261117-60 at start" (red on 81fab2cb), "RED BUG-20261117-60 in the
  processor" (red on ad4703f0), "RED BUG-20261117-60 frozen" (`ClassChecksumsTest#typeAnnotationsCount`, red on
  ad4703f0); review round 1, "RED review round 1 on f6d0fbc1" (`ClassFileScannerTypeAnnotationsTest`: the scan of a
  class with cyclic `InnerClasses` entries timed out, a constructor without `MethodParameters` numbered its
  parameter on the outer instance); `stack-l2-e1.log` (reactor green with E1); `stack-l2-e2.log`, "E2 before commit: full reactor install
  with tests" (green).
- **With BUG-20261117-51 to 54**: a method's `typeParameters()`, its `receiverType()`, a generic `throws` type and a
  constructor's `returnType()` read their annotations through `VaubanMethodInfo.typeParameterScope()` and `typeUse`
  (`RECEIVER`, `THROWS`, `RETURN`), at start and in the processor, whose `ElementScanner` records the method's own
  type parameters, so that a method's `<T>` shadows the class's `T` with its own bounds and their annotations.
- **Found by**: the language model TCK probe (BUG-20261117-40, `vauban-tickets/40.md`, group 1).

## BUG-20261117-59 — In the processor, an enum's constants are missing from its fields

- **Date**: 2026-11-18
- **Status**: FIXED
- **Module**: `vauban-processor` (`apt.ElementScanner`)
- **Symptom**: in the processor, `ClassInfo.fields()` of an enum leaves out its constants, which the language model
  counts among its fields ("implicitly declared" `public static final` fields) and the bytecode scan records at
  start and frozen. An extension that looks a constant up by name, or reads its annotations, finds nothing at build.
  The language model TCK fails `EnumMembers:253` and `:244` in the processor ("No declaration of field 'FOO'"; ticket
  40, group 11).
- **Repro**: `ElementScannerTypeAnnotationsTest#enumConstantsAreFields` in `vauban-processor`, red on b6f10a59
  (`stack-l2c-red.log`, "RED BUG-20261117-59 on b6f10a59"): `[]` for `[LOW 16409 [B], HIGH 16409 []]`.
- **Cause**: `ElementScanner.scan` kept the enclosed elements of kind `FIELD` only; javax.lang.model gives an enum
  constant the kind `ENUM_CONSTANT`.
- **Fix**: the scan keeps `ENUM_CONSTANT` elements as fields too, with the class file's `ACC_ENUM` flag beside
  `public static final`, so that both indexes record the same fields, annotations and flags. The frozen record is
  computed from the bytecode index, which had them: it does not change.
- **Verification**: `stack-l2c-dev.log`, "BUG-59 fix: core, processor, maven-plugin tests" (green); at start,
  `BceEnumMembersTest#anEnumsConstantsAreFields` shows the same constants.
- **Found by**: the language model TCK probe (BUG-20261117-40, `vauban-tickets/40.md`, group 11).

---

## BUG-20261117-58 — At start, an enum's constructor keeps the name and the ordinal javac adds

- **Date**: 2026-11-18
- **Status**: FIXED
- **Module**: `vauban-indexer` (`scanner.ClassFileScanner`)
- **Symptom**: at start and frozen, the constructor `Level(String label, boolean flag)` of an enum has four
  `parameters()`: `(String, int, String, boolean)`, the name and the ordinal of its constant first, which its source
  does not declare; the processor gives two. The annotations of the declared parameters land on the first ones: an
  annotation of `label` is on the synthetic `name`. A type annotation of `label` was numbered `2`. The language model
  TCK fails `EnumMembers:246` at start (`assertConstructor(clazz, BOOLEAN, ...)` reads the first parameter, a
  `String`: "Not a primitive"; ticket 40, group 10).
- **Repro**: `EnumConstructorParametersTest` in `vauban-indexer` (an enum javac 25 compiles, and one without
  `Signature` nor `MethodParameters` on its constructor, as javac before Java 21 writes it), and
  `BceEnumMembersTest#anEnumConstructorHasItsDeclaredParameters` in `vauban-core`, red on 7af75cc1
  (`stack-l2c-red.log`, "RED BUG-20261117-58 on 7af75cc1" and "RED BUG-20261117-58 at start on 7af75cc1").
- **Cause**: `ClassFileScanner.buildParameters` recorded every parameter of the descriptor, and read the
  `RuntimeVisibleParameterAnnotations` attribute by descriptor position, where javac writes it for the parameters
  the source declares only.
- **Fix**: an enum's constructor drops its leading parameters the source does not declare: the difference between
  its descriptor and its `Signature`, those its `MethodParameters` marks synthetic, or, with neither attribute, the
  two of an enum (`ACC_ENUM`). A `RuntimeVisibleParameterAnnotations` attribute that counts fewer parameters than the
  descriptor annotates the declared ones (the last ones here; placed by BUG-20261118-32 since). A type annotation numbers the parameters the index records, as the processor
  does. The frozen record's checksum of an enum changes with its parameters: a record an earlier Vauban wrote is
  refused for it, and the start runs the extensions again.
- **Verification**: `stack-l2c-dev.log`, "BUG-58 fix: indexer, core, processor, maven-plugin tests" (green) and
  "BUG-58: processor agreement test" (`ElementScannerTypeAnnotationsTest#enumConstructorParameters`: the processor
  and the bytecode scan record the same parameters).
- **Found by**: the language model TCK probe (BUG-20261117-40, `vauban-tickets/40.md`, group 10).

---

## BUG-20261117-57 — A package of the language model has no annotation

- **Date**: 2026-11-18
- **Status**: FIXED on `fix/vauban-bug-stack`
- **Module**: `vauban-core` (`langmodel.declarations.VaubanPackageInfo`), `vauban-processor`
  (`CompilerDeclarations`)
- **Symptom**: `ClassInfo.packageInfo().annotations()` was always empty, and `hasAnnotation`, `annotation` and
  `repeatableAnnotation` answered as for a package without annotation, in the processor, at start and frozen, for a
  package whose `package-info.java` declares some. The official language model TCK fails 6 checks on it
  (vauban#336, group 13, `LangModelVerifier` lines 133-138).
- **Repro**: `VetoedInterceptorCompileTimeTest#anExtensionSeesTheAnnotationsOfAPackageInTheProcessorAtStartAndFrozen`
  in `vauban-processor`, red on 0a9ca0e3 the three ways (`stack-l1b-bug57.log`, "RED on 0a9ca0e3": `vi.types has
  @Labelled false`); `VaubanPackageInfoTest` in `vauban-core`.
- **Cause**: `VaubanPackageInfo` held the package name alone and returned no annotation ("packages have no
  annotations in our model").
- **Fix**: a package shows the annotations of its `package-info` class, as the language model describes a class
  (`IndexLookup.find`): from the index, which holds a vetoed package's, else from its class file at start and frozen,
  and in the processor from the compiler's package element, which `CompilerDeclarations` now describes as the
  bytecode scan describes a `package-info` (`ElementScanner.scan(PackageElement)`). A package without one has no
  annotation. Nothing of the index changes, and a `package-info` stays out of the bean discovery.
- **Verification**: `stack-l1b-bug57.log`, "GREEN on 0a9ca0e3 + BUG-57 fix", "GREEN: BUG-57 core unit test" and
  "BUG-57 module tests: core, processor, maven-plugin".

## BUG-20261117-56 — `repeatableAnnotation` does not unwrap the container

- **Date**: 2026-11-18
- **Status**: FIXED on `fix/vauban-bug-stack`
- **Module**: `vauban-core` (`repeatableAnnotation` of `langmodel.declarations.VaubanClassInfo`, `VaubanMethodInfo`,
  `VaubanParameterInfo`, `VaubanFieldInfo`, `langmodel.types.AnnotatedTypeUse`, `extensions.ReflectionMethodInfo`)
- **Symptom**: `AnnotationTarget.repeatableAnnotation(Tag.class)` gave the annotation when it is written once, and
  nothing when it is repeated, `@Tag("a") @Tag("b")`: the compiler wraps the repeated ones in their container, which
  the method did not look into. With `@Tag("x") @Tag.List({@Tag("y"), @Tag("z")})` it gave `x` alone. In the
  processor, at start and frozen; on a type use the processor gave the first of the repeated ones (see
  BUG-20261118-26). The official language model TCK fails 8 checks on it (vauban#336, group 9,
  `RepeatableAnnotations` lines 203-209 and 267-274).
- **Spec**: the Javadoc of `AnnotationTarget.repeatableAnnotation` (lang-model 4.1.0): annotations in the `value`
  member of a container annotation are considered present on the target that carries the container.
- **Repro**: `VetoedInterceptorCompileTimeTest#anExtensionSeesTheRepeatedAnnotationsInTheProcessorAtStartAndFrozen`
  in `vauban-processor`, red on fffe7852 the three ways (`stack-l1b-bug56.log`, "RED on fffe7852": `class []`,
  `method mixed [x]`); `RepeatableAnnotationsTest` in `vauban-core`.
- **Cause**: each implementation returned `annotation(annotationType)` as a one-element list.
- **Fix**: `langmodel.RepeatableAnnotations` gives, from the annotations the target shows, those of the type and
  those each of its containers holds, as `getAnnotationsByType` does. A container is the annotation type the
  repeatable type names in its `@Repeatable`, and a type that is not repeatable has none; when nothing describes the
  repeatable type, an annotation whose `value` is an array of it is taken as its container. Nothing of the index
  changes.
- **Verification**: `stack-l1b-bug56.log`, "GREEN on fffe7852 + BUG-56 fix", "GREEN: BUG-56 core unit test" and
  "BUG-56 module tests: core, processor, maven-plugin".

## BUG-20261117-55 — An annotation of the language model lacks its default values

- **Date**: 2026-11-18
- **Status**: FIXED on `fix/vauban-bug-stack`
- **Module**: `vauban-core` (`langmodel.VaubanAnnotationInfo`)
- **Symptom**: `AnnotationInfo.members()`, `member(name)` and `hasMember(name)` showed the members an annotation
  writes alone, in the processor, at start and frozen: for `@Labelled("x")` with `int rank() default 3`,
  `hasMember("rank")` was `false` and `member("rank")` `null`, where reflection gives 3. A nested annotation too. The
  official language model TCK fails 135 checks on it (vauban#336, group 7, `AnnotationInstances`). Vidocq's bricks
  re-apply each default themselves (Kerckhoffs, Noether, Mansart, Foy).
- **Repro**: `VetoedInterceptorCompileTimeTest#anExtensionSeesTheDefaultValuesOfAnAnnotationInTheProcessorAtStart...`
  in `vauban-processor`, red on 7af75cc1 the three ways (`stack-l1b-bug55.log`, "RED on 7af75cc1": `@Labelled rank
  false: value=x`); `VaubanAnnotationInfoTest` in `vauban-core`.
- **Cause**: `VaubanAnnotationInfo` read the members the index records for the annotation, the written ones. The
  defaults are in the index too, on the methods of the annotation type (`MethodInfo.defaultValue()`, from the
  `AnnotationDefault` attribute at start, from `ExecutableElement.getDefaultValue()` in the processor), and nothing
  read them.
- **Fix**: `VaubanAnnotationInfo` shows each member of the annotation type that the annotation does not write, with
  its declared default, read once from the type as the language model describes it (`IndexLookup.find`). A type
  nothing describes gives the written members alone, as before. The index form of the annotation, which the
  container compares, stays as written (`indexAnnotation()`, `LangModelAnnotations.toIndex`); the index and the
  frozen record do not change.
- **Verification**: `stack-l1b-bug55.log`, "GREEN on 7af75cc1 + BUG-55 fix" and "BUG-55 module tests: core,
  processor, maven-plugin" (core 1650, processor 401, plugin 168, green).

## BUG-20261117-54 — A constructor's `name()` is `<init>` and its `returnType()` is `void`

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/vauban-bug-stack`
- **Module**: `vauban-core` (`langmodel.declarations.VaubanMethodInfo`, `extensions.EnhancementPatchSerializer`,
  `extensions.VaubanInvokerFactory`)
- **Symptom**: in the processor, at start and frozen, a constructor's `MethodInfo.name()` is `<init>` and its
  `returnType()` the void type. The language model asks for "the binary name of the declaring class" and "the type of
  the declaring class". The official language model TCK fails 5 checks on it (vauban#336, ticket 40, group 6:
  `PlainClassMembers`, `ctor.name().equals(clazz.name())`, `ctor.returnType().asClass().declaration()`).
- **Repro**:
  `VetoedInterceptorCompileTimeTest#anExtensionSeesAConstructorNamedAndTypedAfterItsClassInTheProcessorAtStartAndFrozen`
  in `vauban-processor`: the constructors of `vi.types.Members` and `vi.types.Simple`. Red on 5ef42670
  (`stack-l1-bug54.log`, "BUG-54 red": `name <init> returns VOID void`, all three ways).
- **Cause**: `VaubanMethodInfo` gave the index's method name and return type, which are the class file's: `<init>`
  and `V`.
- **Fix**: `name()` gives the binary name of the declaring class for a constructor, and `returnType()` its class type
  (a class type for a generic class too, as the TCK reads it with `asClass()`). The index and the fingerprint of
  the frozen record do not change. Two places inside Vauban read `<init>` there:
  - the frozen record's `@Enhancement` patch keys a constructor `<init>(...)` still, so a record an earlier Vauban
    wrote stays valid: `EnhancementPatchSerializerTest#aConstructorEntryKeyedInitStillTargetsTheConstructor`, green
    before and after the fix ("BUG-54 guard");
  - the invoker factory refuses a constructor by `isConstructor()`:
    `VaubanInvokerLookupTest#theInvokerFactoryRefusesAConstructor`, red with the fix but the factory's old test of
    `name()` against `<init>` (`stack-l1-r1.log`, "R1 item 2 red": the factory looked for a method named after the
    class and failed with "Method ... not found", instead of "Cannot create invoker for constructor of: ...").
- **Who sees the change**: an extension that compared a constructor's `name()` to `<init>` no longer matches: it reads
  `isConstructor()`, as the language model intends.
- **Verification**: green in `stack-l1-bug54.log` ("BUG-54 module tests with the fix") and in `stack-l1-r1.log`
  ("R1 item 2 green").
- **Found by**: the language model TCK probe (vauban#336).

## BUG-20261117-53 — `MethodInfo.receiverType()` is always `null`

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/vauban-bug-stack`
- **Module**: `vauban-core` (`langmodel.declarations.VaubanMethodInfo`)
- **Symptom**: `MethodInfo.receiverType()` is `null` for every method, in the processor, at start and frozen. The
  language model asks for the type of the receiver parameter an instance method may declare, whether it declares it
  or not, and `null` only for a static method and a constructor of a top-level or static nested class. The official
  language model TCK fails 10 checks on it (vauban#336, ticket 40, group 5: `AnnotatedReceiverTypes`).
- **Repro**: `VetoedInterceptorCompileTimeTest#anExtensionSeesTheReceiverTypeOfAMethodInTheProcessorAtStartAndFrozen`
  in `vauban-processor`: the instance methods, static method and constructors of the generic `vi.types.Members` and
  of `vi.types.Simple`, one of which declares its receiver parameter. Red on 2f9ba2cc (`stack-l1-bug53.log`,
  "BUG-53 red": `receiver null` for every method, all three ways).
- **Cause**: `VaubanMethodInfo.receiverType()` returned `null`.
- **Fix**: `receiverType()` gives the type of the declaring class for an instance method: a class type, or for a
  generic class a parameterized type of its own type variables (`Members<C, E>`), as reflection gives it since JDK 17
  and as the TCK accepts. `null` for a static method and a constructor. An inner class's constructor, which may declare
  a receiver of its enclosing class, is `null` too: BUG-20261118-01. Nothing of the index changes, nor the fingerprint
  of the frozen record. The receiver type carries no type annotation (ticket 40, group 1).
- **Who sees the change**: an extension gets a receiver type for an instance method, where it got `null`.
- **Verification**: green in `stack-l1-bug53.log` ("BUG-53 green attempt", "BUG-53 module tests with the fix").
- **Found by**: the language model TCK probe (vauban#336).

## BUG-20261117-52 — At start and frozen, a method's generic `throws` types are erased

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/vauban-bug-stack`
- **Module**: `vauban-indexer` (`scanner.ClassFileScanner`, `scanner.ClassTypeParameters`)
- **Symptom**: for `<F extends Throwable> void throwing() throws IOException, E, F` in a class
  `Members<..., E extends Exception>`, `MethodInfo.throwsTypes()` gives the classes `java.lang.Exception` and
  `java.lang.Throwable` at start and frozen, where the processor gives the type variables `E` and `F`. The official
  language model TCK fails 4 checks on it at start (vauban#336, ticket 40, group 4: `AnnotatedThrowsTypes`).
- **Repro**:
  `VetoedInterceptorCompileTimeTest#anExtensionSeesTheGenericThrowsTypesOfAMethodInTheProcessorAtStartAndFrozen`
  in `vauban-processor`, and `ClassFileScannerTest$ThrownTypes#typeVariables` in `vauban-indexer`. Red on 6d5338ef
  (`stack-l1-bug52.log`, "BUG-52 red": `CLASS java.lang.Exception, CLASS java.lang.Throwable` at start and frozen,
  the processor right; and the scan's `ClassType[name=java.lang.Exception]`).
- **Cause**: `ClassFileScanner.extractExceptionTypes` read the method's `Exceptions` attribute, which names the
  erasure of each thrown type (JVMS 4.7.5). The thrown types as declared are in the method's `Signature`, after `^`,
  which javac writes when one of them is a type variable; `ClassTypeParameters.methodTypes` did not read them.
- **Fix**: `ClassTypeParameters.methodTypes` reads the thrown types of the signature (`throwableSignatures()`), their
  type variables resolved like the parameters', and `ClassFileScanner` takes them when the signature names any; the
  `Exceptions` attribute otherwise, as before.
- **Who sees the change**: an extension the container runs at start, or that the build freezes, gets a type variable
  for a thrown type variable, as an extension the processor runs did. The fingerprint of the frozen record changes
  for a class with such a method only, as what the extensions see of it changed; any other class keeps its checksum.
- **Verification**: green in `stack-l1-bug52.log` ("BUG-52 module tests with the fix").
- **Found by**: the language model TCK probe (vauban#336).

## BUG-20261117-51 — `MethodInfo.typeParameters()` is always empty

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/vauban-bug-stack`
- **Module**: `vauban-core` (`langmodel.declarations.VaubanMethodInfo`), `vauban-processor` (`apt.ElementScanner`)
- **Symptom**: an extension reads no type parameter on a generic method, `<T, U extends C> void typed()`, in the
  processor, at start and frozen. The language model asks for "a list of type parameters declared on this method".
  The official language model TCK (vauban#336, ticket 40, group 2) fails 56 checks on it, in `AnnotatedTypes`
  (`typeVariableMethod`) and the members of the other fixtures. The method-level sibling of VAU-BCE-007.
- **Repro**: `VetoedInterceptorCompileTimeTest#anExtensionSeesTheTypeParametersOfAMethodInTheProcessorAtStartAndFrozen`
  in `vauban-processor`: `vi.types.Members<C extends Number, E extends Exception>` declares `typed()`, `sorted(S)`
  and `throwing()`, generic, and `plain()`; an extension records each method's `typeParameters()` in `@Synthesis`.
  Red on 81fab2cb (`stack-l1-bug51.log`, "BUG-51 red"): `sorted<>`, `throwing<>`, `typed<>` all three ways.
- **Cause**: `VaubanMethodInfo.typeParameters()` returned `List.of()`. At start and frozen, the index held them
  (`ClassFileScanner` reads them from the method's `Signature` through `ClassTypeParameters.methodTypes`); the
  processor's `ElementScanner.scanMethod` did not record them.
- **Fix**: `VaubanMethodInfo.typeParameters()` maps the index's, each with `java.lang.Object` when it declares no
  bound, as `ClassInfo.typeParameters()` does (`TypeParameterScope.typeParameters`, since BUG-20261117-60).
  `ElementScanner` records a method's type parameters through its `TypeParameterReader`, now built for a method too:
  a bound naming a type parameter of the method or of its class carries that parameter's bounds, as the bytecode scan
  reads them. The fingerprint of the
  frozen record does not change: it is computed from the bytecode index, which held them already.
- **Who sees the change**: an extension gets the type parameters of a generic method. The processor now reports a
  generic initializer method (`@Inject <T> void init(T t)`) as the container does at start, as a warning.
- **Verification**: green in `stack-l1-bug51.log` ("BUG-51 green attempt", and "BUG-51 module tests").
- **Found by**: the language model TCK probe (vauban#336).

## BUG-20261117-50 — The processor reads a type another processor generates in round 1 as `java.lang.Object`

- **Date**: 2026-11-17
- **Status**: FIXED
- **Module**: `vauban-processor` (`apt.ElementScanner`, `VaubanProcessor`)
- **Symptom**: a class of the compilation that names a type another annotation processor generates in the first
  round is read with that type as `java.lang.Object`, or, for a supertype, by its simple name. What that does, shape
  by shape (`GeneratedTypeProcessorTest`; `ShapesProbe` in `vauban-b50-shapes.log`, which also starts the
  application where it compiles):
  - an injection point of the generated type, `@Inject Baz_ baz`, fails the build: "Ambiguous dependency: field
    User.baz of type ClassType[name=java.lang.Object]", which every bean matches;
  - an observer of it, `@Observes Baz_`, fails the build: the observer method the processor generates in
    `_VaubanComponents` passes it an `Object` ("incompatible types: java.lang.Object cannot be converted to p.Baz_");
  - a producer method of it builds and starts, and a bean injects what it produces; an extension reads the
    producer's types as `[java.lang.Object]`;
  - a bean whose superclass and interface are generated builds and starts, and a bean injects it by that interface;
    an extension reads them as `Base_` and `[Baz_]`, which name no class, and the bean's types as
    `[Base_, Baz_, java.lang.Object, p.Foo]`. Before BUG-20261117-31 it read `java.lang.Object`, `[java.lang.Object]`
    and `[java.lang.Object, p.Foo]`;
  - a class the other processor generates is scanned in a later round, when the types it names exist: a generated
    bean and the injection points of a generated class are read right.

  MapStruct's pattern works: an `@ApplicationScoped` `CarMapperImpl` generated in the first round, injected by the
  hand-written `CarMapper`, builds and starts. Immutables' pattern fails when an injection point names the generated
  class: `ImmutableCar` produced and injected by that name fails the build ("Ambiguous dependency: field Garage.car
  of type ClassType[name=java.lang.Object]"); produced by that name and injected as the hand-written `Car`, it builds
  and starts. The companion processor of the repros generates classes of those shapes; the libraries themselves were
  not run.
- **Repro**: `GeneratedTypeProcessorTest` in `vauban-processor`, five tests `@Disabled` with this id: an injection
  point, an observer, an Immutables value produced and injected by its generated name, and what an extension sees
  of a producer's types and of a bean's supertypes. A companion processor that runs before Vauban's generates
  `p.Baz_` and the others in the first round. Red with the `@Disabled` lifted on deb2aa98 (`vauban-b31-red.log`,
  "Round 2: BUG-50 repro"). `ShapesProbe` gives the same results on Vauban main 455067f5, on BUG-20261117-31's base
  b63cc6cb and on deb2aa98, but for the supertypes' names, which BUG-20261117-31 changed; MapStruct's pattern and
  the Immutables value injected as `Car` work on all three.
- **Cause**: in the first round, the generated type does not exist yet: javac gives an error type. The scan
  (`ElementScanner.typeMirrorToTypeInfo`) has no case for an error type and gives `java.lang.Object`; a supertype
  goes through `ElementScanner.unresolved`, which gives the binary name javac gives the error type, its simple name.
  `VaubanProcessor.indexOnce` (~531) scans a class once, in the round where it first appears, and never again.
- **Cause, proved**: red on e6013800 with the five `@Disabled` lifted (`stack-l2d-red.log`, "RED BUG-20261117-50 on
  e6013800"): "Ambiguous dependency: field User.baz of type ClassType[name=java.lang.Object]", the same for
  `Garage.car`, the observer that does not compile, a producer's types `[java.lang.Object]`, and the supertypes
  `Base_` and `[Baz_]`.
- **Fix**: `ElementScanner` counts the error types its scans meet (`errorTypesMet()`), in `typeMirrorToTypeInfo` and
  in `unresolved`. `VaubanProcessor.indexOnce` notes a class whose scan met one, and the round the pipeline runs in
  scans each again from its fresh element (`Elements.getTypeElement`), replacing its first scan in the index. A type
  still missing reads as before.
- **Verification**: `GeneratedTypeProcessorTest`, 5 of 5 green (`stack-l2d-dev50.log`, "BUG-50 fix (1)"); processor
  444 (8 skipped), maven-plugin 172, architecture 56 (`stack-l2d-dev50.log`, "BUG-50 fix (2)").
- **What stays**: a type another processor generates in the very round the pipeline runs in does not exist yet there
  and still reads as before (by construction, not tested).
- **Found by**: the review of BUG-20261117-31 (62e80eaf), with its `GeneratedSupertypeProbe`.

## BUG-20261117-49 — A client proxy generated from a class file skips a method that has a compiler bridge

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/client-proxy-bridge`
- **Module**: `vauban-core` (`codegen.ClassTypeView`, `proxy.ForwardedMethods`, `proxy.ProducerProxyEligibility`), and
  through them `vauban-maven-plugin` (`vauban:generate`, the dependency enhancer)
- **Symptom**: a normal-scoped bean whose client proxy is generated from its class file, at run time or by
  `vauban:generate`: a call to a method javac pairs with a bridge runs on the proxy itself, neither on the contextual
  instance nor through its interceptors. The proxy instance has run no constructor body and no `@PostConstruct`, so
  the call reads `null` fields. Three shapes: `String get()` of a bean that implements `Supplier<String>`, declared or
  inherited; a covariant override, `String name()` over `CharSequence name()`; a public method a public class inherits
  from a package-private one. Typed by the bean or by its supertype alike. Found by Dirac's DRC-011 probe:
  `get() through Supplier: ran on app.Svc_ClientProxy; count=0` (`dirac-drc011-dev.log:3927-3928`).
- **Repro**: `BridgeMethodClientProxyTest` in `vauban-core`, a real container start whose bean classes are added with
  `addBeanClass` and compiled without the processor, so the test checks that each proxy is announced as generated at
  run time. Five `@ApplicationScoped` beans, state set in `@PostConstruct`, each called through the bean type and the
  supertype: `OwnSupplier`, `InheritedSupplier`, `VisiblyInheritedSupplier` (through a package-private superclass),
  `CovariantName`, and `CovariantValue` (`Integer value()` over `Number value()`), the control. Red on 455067f5: four of
  five, the control passes. `ClientProxyInheritanceCrossCheckTest.aMethodNextToItsBridgeIsForwarded` in
  `vauban-processor`: the source and co-located shapes forward `get()` and `name()`, the run-time shape nothing;
  `aFinalMethodNextToItsBridgeIsJudged`: `ProducerProxyEligibility` of `public final String get()` next to its bridge
  says `ELIGIBLE` from the class file, `FINAL_VIRTUALS` from the source.
- **Cause** (proved by the red runs): `ForwardedMethods.select` (`ForwardedMethods.java:93` on 455067f5) recorded a
  method's override key, its name and erased parameter types, before it asked whether the method can be proxied, which
  refuses a bridge. `ClassTypeView` lists a class's methods in `BeanMembers.STABLE_ORDER`: name, parameter types,
  return type descriptor, declaring class. The bridge `Object get()` sorts before `String get()`
  (`Ljava/lang/Object;` before `Ljava/lang/String;`), `CharSequence name()` before `String name()`: the bridge took the
  key, was refused, and the method it stands for, met next, counted as already met, as did every declaration above
  it. The proxy forwarded no `get()`, and the call ran the inherited body on the proxy instance. When the real method
  sorts first, `Integer value()` before its bridge `Number value()`, the proxy was right. A visibility bridge, javac's
  copy of an inherited public method in the public subclass, takes the key whatever the order, as the walk meets the
  subclass first. The red diagnostic names it: "the run-time walk meets [bridge java.lang.Object OwnSupplier.get(),
  java.lang.String OwnSupplier.get()], the proxy … forwards [void init(), boolean equals(), int hashCode(),
  java.lang.String toString()] ==> expected: <instance> but was: <null>". The processor walks the language model,
  which has no bridge: the build-time proxy was right. `ProducerProxyEligibility.methodsVerdict`
  (`ProducerProxyEligibility.java:145` on 455067f5) had the same flaw.
- **Fix**: the walks over a class file follow the processor's rules. `ClassTypeView` keys a method by its signature as
  a member of the class the walk starts from, as the processor's `memberSignatureKey` does through `Types.asMemberOf`:
  the type variables of each generic superclass take the arguments the subclass gives them, enclosing types included;
  a superclass extended raw is seen erased (JLS 4.8); a generic signature reflection cannot read falls back to the
  erased key. `ForwardedMethods.select` and `ProducerProxyEligibility` pass a bridge by, as the language model has
  none. The bridges used to tell the walk that `echo(T)` of `Base<T>` is overridden by `echo(String)` in
  `Child extends Base<String>`; the member key now does. The two paths agree because both walks take the same
  declarations (no bridge), most derived first, under the same key; `ClientProxyInheritanceCrossCheckTest` compares
  the source, co-located and run-time shapes of every fixture, and `ClassTypeViewOverrideKeyTest` pins the keys.
- **Deployments on the run-time path** (read on Vauban 455067f5 and Vidocq 053f4e8d). The container first asks the
  generated component providers for a proxy (`InterceptorBeanWrapper.java:462-463`); without one, it generates it
  (`:474-476`, `RuntimeClientProxyGenerator.java:98` walks with `ForwardedMethods.select`). A bean whose proxied type
  is an interface goes to `interfaceProxy` instead (`:303-304`, `:339-366`): the build-time interface proxy of
  `@ClientProxies`, else a `java.lang.reflect.Proxy`, which dispatches each interface method and walks no class: not
  affected.
  - **Class-based beans compiled without the processor**: a jar on the class path, a class added at start. Every
    normal-scoped one.
  - **Synthetic beans not in `@ClientProxies`**: the bean class is the implementation class the extension names
    (`SyntheticBeanConverter.java:80-82`, `ManagedBean.java:89`). An interface takes `java.lang.reflect.Proxy`, not
    affected; a class is generated at run time, affected, unless that class already has a processor proxy under the
    same name (the container looks the proxy up by its class name, `InterceptorBeanWrapper.java:462-463`).
    `@ClientProxies` takes interfaces only.
  - **Build compatible extensions run at start**: no proxy of their own; a class an extension adds or makes
    normal-scoped at start, where the processor did not see it so, has no build-time proxy. Class-based beans, and
    synthetic beans as above.
  - **`vidocq:dev`**: not by default. It compiles with an out-of-process `mvn process-classes`
    (`RecompileRunner.java:71`), processors included, and recompiles an IDE build that skipped them
    (`VidocqDevMojo.java:396-397`): the application's beans have build-time proxies. It takes the run-time path for a
    dependency compiled without the processor while `vidocq:generate`'s stamp is stale
    (`VidocqBootstrap.java:337-339`), and for synthetic beans of a class. `vidocq:generate` runs the processor
    (`DependencyCodegen`): not affected.
  - **Arquillian runners**: every one builds the container from the archive's classes. A class the processor never
    compiled, every class of a TCK jar among them, takes the run-time path, class-based or synthetic; a class of an
    application test compiled with `vauban-processor` gets its build-time proxy from its `_VaubanComponents`, found
    through `ServiceLoader` (`ComponentProviders.java:60-66`). `vauban-tck-runner`
    (`VaubanDeployableContainer.java:105-115`, `addBeanClass`), and its copy in `mansart-data-tck`
    (`VaubanDeployableContainer.java:105-111`); `vidocq-runtime-arquillian` (`VidocqEmbeddedContainer.java:117-189`,
    then `VidocqBootstrap.java:353-363`), used by the runtime's `vidocq-runtime-tck-*` runners; the brick runners that
    build a `VaubanContainer` themselves: Cyrano and Heisenberg (`VaubanTckBootstrap.java:92-94`, `:89-92`), Dirac
    (`VaubanDiracTckBootstrap.java:63-66`), Cervantes (`CervantesJwtDeployableContainer.java:151-160`), Humboldt
    (`HumboldtDeployableContainer.java:124-143`), Kerckhoffs (`KerckhoffsDeployableContainer.java:268-272`), and Grimm
    through `VidocqBootstrap` (`GrimmDeployableContainer.java:111`). Cassini, Knock, Foy and Ibarra boot through
    their test harnesses, not read further.
  - **`SeContainerInitializer`**: `VaubanSeContainerInitializer.java:133-157` scans packages or bean archives and adds
    the classes given to it; a class compiled without the processor takes the run-time path. Class-based beans, and
    synthetic beans as above.
  - **Tests that add bean classes**: `VaubanContainerBuilder.addBeanClass` (`VaubanContainerBuilder.java:181`).
    vauban-core's own tests are compiled without the processor: every proxy there is generated at run time.
  - **At build, through the same generator**: `vauban:generate` writes these proxies into the build output
    (`VaubanGenerator.java:406`, `:597`, `:883`, eligibility at `:578`), and so does the dependency enhancer
    (`DependencyEnhancer.java:87`, eligibility at `:136`). Such an archive keeps the faulty proxies until it is
    processed again.
- **Not covered**: two other bridge shapes, seen while proving this one, have entries of their own:
  BUG-20261118-16 (the run-time intercepted subclass) and BUG-20261118-17 (a bridge that calls a superclass method
  of another descriptor).
- **Observable change**: `ProducerProxyEligibility` now judges a `final` method next to its bridge, which is not
  final, as the processor does: `FINAL_VIRTUALS`. `vauban:generate` and the dependency enhancer warn and write no
  producer proxy for such a type (`VaubanGenerator.java:578`, `DependencyEnhancer.java:136`), where they wrote one
  that could not override the method. At start, a normal-scoped producer of such a type whose package Vauban cannot
  define into fails the deployment with a `DeploymentException` (`InterceptorBeanWrapper.java:506-508`), where the
  start succeeded and the first call either threw `UnproxyableResolutionException` or ran on the proxy. Pinned by
  `ProducerProxyAndProviderGenerationTest.aFinalMethodNextToItsBridgeGetsNoProducerProxy` and
  `ClientProxyInheritanceCrossCheckTest.aFinalMethodNextToItsBridgeIsJudged`.
- **Verification**: logs in `session-swap/`. Red: `vauban-b49-red.log`, "Red run 1" (`BridgeMethodClientProxyTest`,
  4 of 5 red), "Red run 2" and "Red run 3" (`ClientProxyInheritanceCrossCheckTest`), and "Red run 6", the final tests on
  the main code of 455067f5 with the fix set aside: `BridgeMethodClientProxyTest` 4 of 5, `ClassTypeViewOverrideKeyTest`
  3 of 5, `ClientProxyInheritanceCrossCheckTest` 2 of 35. Green: "Green run 5" in the same log, 89 tests of
  `vauban-core` and 38 of `vauban-processor`; `vauban-b49-dev.log`, "Dev run 2", the key test with a generic signature
  that names a missing class (6 of 6); the full build of the commit, `vauban-b49-build.log`, "Full build of the commit
  tree, run 5", `-Pquality clean install`, 2600 tests, 0 failures, 14 skipped; the CDI Lite core TCK, 774 of 774, and
  the AtInject TCK, 1 of 1, `vauban-b49-tck.log`, "CDI Lite core TCK and AtInject TCK again", vauban-core resolved
  from the private repository the full build installed (`dependency:build-classpath` in the same log).
- **Verification, review round 1** (rebased on 81fab2cb): `stack-bug49-red.log`, "Red run S2", the new cases on the
  main code of 81fab2cb with the fix set aside: the bridge cases with a package-private method of another package
  and an interface default with a covariant return, the producer shapes, both placements of the walk, and the
  plugin's `FINAL_VIRTUALS` warning all red; the multi-level generic and generic interface default cases pass there,
  as the bridges' keys did that work before. Green: "Red run S1" in the same log, which ran with the fix (its
  set-aside step failed), and the module and full builds in `stack-bug49-dev.log` and `stack-bug49-build.log`.
- **Found by**: Dirac's DRC-011 probe (`dirac-drc011-dev.log`).

## BUG-20261117-48 — The processor cannot build an invoker for a bean class compiled in the same build

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/vauban-bug-stack` (lane `stack/l3-batch-2`)
- **Module**: `vauban-core` (`VaubanInvokerFactory`, `VaubanInvokerBuilder`, new `InvokerTarget`,
  `SyntheticParamCodec`, `ExtensionMethodValidator.validateInvokerLookups`), run by `vauban-processor`
- **Symptom**: an extension that builds, in `@Registration`, an invoker of a method of a bean the compilation
  compiles, `@Dependent public class Worker { public String work() }`, fails the compilation with
  `[Vauban BCE] Cannot load bean class: app.Worker`. CDI 4.1 lets an extension build an invoker of any method of an
  enabled managed bean (TCK audit `building_invoker`, a): a valid extension on a valid application fails the build.
- **Repro**: `BceCompileTimeTest#anExtensionBuildsAnInvokerForABeanTheBuildCompiles`,
  `#theBuildChecksTheLookupsOfAnInvokerOfABeanItCompiles` and `#theBuildRefusesAnInvokerOfAMethodAnotherClassDeclares`
  in `vauban-processor`.
- **Cause** (proved): `createInvoker` loaded the bean class with the processor's class loader to find the `Method`;
  under javac a class of the compilation has no class file yet. The check of the argument lookups
  (BUG-20261117-27) read the `Method` too, so the build only checked the lookups of the classes of libraries.
- **Fix**: an invoker's method is described by names, from the language model (`InvokerTarget`: the bean class, the
  declaring class, the name and the erased parameter types, as `Class.getName()` writes them). `createInvoker` checks a
  private method, a method of `Object`, and a method the bean class neither declares nor inherits on the language model
  (that last one was checked on a class it loads only, so the build accepted it for a class it compiles), and loads the
  classes only when they have a class file: a class the build compiles gives an invoker described by names
  (`InvokerTarget.Described`), which `SyntheticParamCodec` writes as the invoker of a loaded class, and which the start
  reads back as a `VaubanInvoker` (the method found by its exact erased parameters, where `findMethod` matched the name
  and the count). The argument lookups are checked on the language model's parameters, their types and qualifiers, so
  the build checks those of a bean it compiles as of one it loads, and the start the same.
- **CDI Lite TCK**: does not cover it. The runner runs the extensions at start, where every class loads; its
  invoker tests (`org.jboss.cdi.tck.tests.invokers`) pass before and after.
- **Verification**: red on 7e831a64 (`stack-l3b-red.log`, "BUG-20261117-48 red, on 7e831a64": `Cannot load bean
  class: app.Worker`; "BUG-20261117-48 red, run 2": both final tests; "BUG-20261117-48 review: an invoker of another
  class's method, on 37f4198f": the build accepted it). Green: `stack-l3b-dev.log`, runs 2 to 5
  (`InvokerTargetTest`, `SyntheticParamCodecTest#describedInvoker`), and `stack-l3b-green.log`,
  "BUG-20261117-48 fix: vauban-core and vauban-processor, all tests", and "BUG-20261117-48 review: the method's class
  checked on the language model".
- **Found by**: the review of fix round 2 of BUG-20261117-27, from its build-side test, whose beans are precompiled
  for that reason.
## BUG-20261117-47 — The container provides none of the bean metadata beans of CDI 4.1 §5.5.8

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/vauban-bug-stack` (lane `stack/l3-batch-2`)
- **Module**: `vauban-core` (the container's built-in beans, `VaubanBeanManager`; `InterceptorBeanWrapper`)
- **Symptom**: CDI 4.1, 5.5.8 "Bean metadata", in Part I.A, CDI Lite: "The container must provide beans allowing a
  bean instance to obtain a `Bean` or `Interceptor` instance containing its metadata: a bean with scope `@Dependent`,
  qualifier `@Default` and type `Bean` which can be injected into any bean instance; a bean with scope `@Dependent`,
  qualifier `@Default` and type `Interceptor` which can be injected into any interceptor instance. Additionally, the
  container must provide beans allowing interceptors to obtain information about the beans they intercept: a bean
  with scope `@Dependent`, qualifier `@Intercepted` and type `Bean` which can be injected into any interceptor
  instance." Only decorator metadata is CDI Full (16.4.2: `Decorator`, `@Decorated Bean`). Vauban provided none of
  the three: a bean that injects `Bean<Itself>` failed when the container created it, with
  `UnsatisfiedResolutionException: No bean found for type: jakarta.enterprise.inject.spi.Bean<…>`; an interceptor
  that injects `Interceptor<Itself>` or `@Intercepted Bean<?>` failed with `CreationException: Failed to create
  interceptor`, caused by the same for `Interceptor` or `Bean`.
- **Repro**: in `vauban-processor`, `VetoedInterceptorCompileTimeTest`, each checking the processor, then a container
  started on classes compiled with the processor's validation off, its extensions run at start, then frozen, and
  calling the bean:
  - `aBeanGetsItsOwnBeanMetadata`: `vi.SelfAware` injects `Bean<SelfAware>` and answers its bean class;
  - `anInterceptorGetsItsOwnInterceptorMetadata`: `vi.Introspector` injects `Interceptor<Introspector>` and wraps what
    it intercepts in its own bean class;
  - `anInterceptorGetsTheMetadataOfTheBeanItIntercepts`, for `?` and `? extends Object`: `vi.Watcher` injects
    `@Intercepted Bean<…>` and wraps what it intercepts in the class of that bean.

  In `vauban-core`, `BeanMetadataTest`, on classes no processor compiled: `Bean<Itself>` at a constructor, a field and
  an initializer; an interceptor with `Interceptor<Itself>` at its constructor and `@Intercepted Bean<?>` in a field,
  bound to two beans, a `@Dependent` and an `@ApplicationScoped` one, each seen as itself; the `Interceptor` and
  `@Intercepted Bean` beans give `null` outside the creation of an interceptor.
- **Cause** (proved, "BUG-20261117-47 red, on 80e3cff7"): no built-in bean of type `Bean` or `Interceptor` was
  registered. The container's built-in beans were `BeanManager`, `Event`, `Instance`, `InjectionPoint` and
  `RequestContextController` (`VaubanBeanManager`), so the resolution of a field or a parameter of type `Bean` or
  `Interceptor` found no bean. The validator counts both types as built-in (`DeploymentValidator.isBuiltInType`), so
  nothing failed before the creation.
- **Fix**: `BeanMetadata` adds the three beans to the container's built-in ones, each `@Dependent`:
  - `Bean`, `@Default`: the bean of the injection point being injected, as `InjectionPoint` is served;
  - `Interceptor`, `@Default`, and `Bean`, `@Intercepted`: two `ScopedValue`s, bound while
    `InterceptorBeanWrapper` creates an interceptor instance, to its `Interceptor` and to the bean whose instance it
    intercepts. That bean is the one the interceptor's creational context creates: an intercepted `ManagedBean`
    records itself on the `CreationalContextImpl` it creates an instance with, unless one is already recorded.
  `BuiltInBean` takes qualifiers other than `@Default`. No reflection is added: the beans read what the container
  holds.
- **Limit**, fixed by BUG-20261118-36: an intercepted `@Dependent` bean created with the creational context of another
  intercepted bean shared its interceptor instances, and so their `@Intercepted Bean`.
- **CDI Lite TCK**: not covered. `tests.full.implementation.builtin.metadata.BuiltinMetadataBeanTest`, which injects
  these beans, is in the CDI Full part the runner leaves out, as it checks the decorator metadata too (`@Decorated`);
  the Lite tests of `builtin.metadata.broken` expect a definition error, and the validation that gives it is
  unchanged.
- **Verification**: red on 80e3cff7, BUG-20261117-41, the fix set aside (`stack-l3b-red.log`, "BUG-20261117-47 red,
  on 80e3cff7": the three repros, at start and frozen, `No bean found for type` `Bean<vi.SelfAware>`,
  `Interceptor<vi.Introspector>`, `Bean<?>`). Green: `stack-l3b-green.log`, "BUG-20261117-47 fix, run 3" (the
  processor repros), "run 4" (`BeanMetadataTest`), and "BUG-20261117-47 fix: the whole reactor".
- **Found by**: the review of BUG-20261117-41 (BUG-20261117-22, round 4), against CDI 4.1, 5.5.8; the failure was first
  seen in "Fix round 2 red run 3".
## BUG-20261117-46 — An error an extension phase throws without a message is reported as "null"

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/vauban-bug-stack`
- **Module**: `vauban-core` (`extensions.BceProcessor`)
- **Symptom**: an `@Enhancement` method that throws a `Throwable` without a message, such as the `AssertionError` of a
  failed `assert`, fails the start with
  `DeploymentException: CDI deployment validation failed: - @Enhancement error: null`. The line names neither the
  extension class, nor its method, nor the error; only the stack of the cause tells where. The CDI language model
  TCK checks with plain `assert` statements, so each of its failures reads `null`.
- **Repro**: `PhaseErrorReportTest#theStartFailureNamesTheExtensionItsMethodAndTheError` in `vauban-core`,
  `@Disabled` with this id: red with the `@Disabled` lifted (`vauban-b31-red.log`, "Fix round 1, red run 11"). The
  BUG-20261117-40 probe showed it first (`vauban-b40-probe.log`,
  `LangModelTckProbeTest#anAssertionErrorInAPhaseFailsTheStart` on the local branch `probe/langmodel-tck`).
- **Cause**: `BceProcessor` (~604-608) unwraps the phase's `InvocationTargetException`, keeps the cause, and adds
  `"@Enhancement error: " + unwrapped.getMessage()` to the errors. `@Registration` (~756-789), `@Synthesis`
  (~842-844), `@Validation` (~875-877) and the extension's own failure (~295) build their lines the same way.
- **Expected**: when the message is `null`, the line names the extension class, the method and the exception type,
  `@Enhancement error in ...Asserting.verify: java.lang.AssertionError` say.
- **Found by**: the BUG-20261117-40 probe of the CDI language model TCK.
- **Fix**: `BceProcessor.phaseError` writes the line of each phase that calls the extension per class or per bean,
  `@Enhancement`, `@Registration`, `@Synthesis` and `@Validation`: `<phase> error: <message>`, as before, or, for a
  failure without a message, `<phase> error in <extension class>.<method>: <failure type>`. `@Registration` keeps an
  `IllegalStateException` the call throws as its message alone, the invoker factory's own errors. The line of an
  extension that fails outside a method call names its failure type when it has no message.
- **Verification**: `PhaseErrorReportTest`, `@Disabled` lifted and widened to the four phases: red on e6bca57c
  with `BceProcessor` unfixed (`stack-l1b-bug46.log`, "RED on e6bca57c (BceProcessor unfixed, test extended)": the
  four `error: null`), green with the fix ("GREEN on e6bca57c + BUG-46 fix: PhaseErrorReportTest, four phases");
  "BUG-46 module tests: core, processor, maven-plugin". Checked on the stack tip 7af75cc1 first: BUG-20261118-05 had
  not changed the message.

## BUG-20261117-45 — The start checks invoker lookups without the synthetic beans of frozen or compiled extensions

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/vauban-bug-stack` (lane `stack/l3-batch-2`)
- **Module**: `vauban-core` (`VaubanContainerBuilder`, `BceProcessor.process`)
- **Symptom**: at start, an extension that runs then builds an invoker whose argument lookup only a synthetic bean
  of another extension satisfies, an extension frozen at build (`vidocq:generate`) or run by the annotation
  processor of an archive: the start fails with `Invoker argument lookup unsatisfied: no bean found for parameter
  type …`, though the call finds the bean.
- **Repro**: `FrozenExtensionsTest#anExtensionThatRunsAtStartMayLookUpTheSyntheticBeanOfAFrozenOne` in
  `vauban-core` (one extension frozen, the other named to run at start);
  `BceCompileTimeTest#theStartChecksALookupAmongTheSyntheticBeansTheArchiveRecords` in `vauban-processor` (an
  archive the processor processed records the synthetic bean, an unprocessed bean makes the extensions run at start).
- **Cause** (proved by both repros): `BceProcessor.process` checked the lookups among the beans discovered and the
  synthetic beans of the extensions it ran (BUG-20261117-39). `VaubanContainerBuilder` registers the synthetic beans
  of the frozen extensions (`registerSyntheticMetadata`) and of the archives processed at compile
  (`loadSyntheticMetadataFromApt`) after it.
- **Fix**: `BceProcessor.process` takes `deferLookups`; the start sets it, and gets the invokers back in
  `Result.invokerLookups()`, which it checks once every synthetic bean is registered, among all the descriptors. The
  annotation processor and the build that freezes the extensions run every extension of what they see, so they check
  in `process` as before.
- **CDI Lite TCK**: no; the TCK runs every extension in one cycle, it neither freezes nor processes archives at
  compile. `invokers.lookup.LookupUnsatisfiedTest` covers the unsatisfied lookup the fix keeps.
- **Verification**: red on 4685e708, BUG-20261117-44 (`stack-l3b-red.log`, "BUG-20261117-45 red, run 2" for the
  processed archive, "BUG-20261117-45 red, run 3" for the frozen extension, the fix set aside). Green:
  `stack-l3b-green.log`, "BUG-20261117-45 fix: core and processor, with the frozen repro".
- **Found by**: the review of BUG-20261117-27, checking the "every bean" of BUG-20261117-39.
## BUG-20261117-44 — The start refuses an invoker's lookup of a built-in bean the call serves

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/vauban-bug-stack` (lane `stack/l3-batch-2`)
- **Module**: `vauban-core` (`ExtensionMethodValidator.validateArgumentLookup`, `DeploymentValidator`)
- **Symptom**: an extension builds an invoker of `boolean hasContainer(BeanContainer container)` with
  `withArgumentLookup(0)`: the start fails with `Invoker argument lookup unsatisfied: no bean found for parameter
  type jakarta.enterprise.inject.spi.BeanContainer at position 0`, though the call gives the `BeanContainer`. The
  same for `Provider<X>`, `RequestContextController` and `InjectionPoint`, which the call gives too.
- **Repro**: `VaubanInvokerLookupTest#theStartAcceptsALookupOfTheBeanContainer`,
  `#theStartAcceptsTheLookupsOfTheOtherBuiltInBeans` and `#theArgumentLookupsOfTheOtherBuiltInBeansGiveThem` (the
  call, green before: the start refused what it serves) in `vauban-core`;
  `BceCompileTimeTest#theBuildAcceptsALookupOfTheBeanContainer` in `vauban-processor`, the same check at build.
- **Cause** (proved): the check left to the container the built-in beans its own list held, `Instance`, `Event` and
  `BeanManager`, and looked the others up among the deployment's beans, which do not hold the built-in ones. It was
  so before BUG-20261117-27, which counted classes.
- **Fix**: the check and the deployment validation share one list of the built-in beans,
  `DeploymentValidator.isBuiltInBeanType`: `Event`, `Instance`, `Provider`, `BeanManager`, `BeanContainer`,
  `InjectionPoint` and `RequestContextController`, raw or parameterized. A lookup of one of them is left to the
  container, as an injection point of it is. The bean metadata types (`Bean`, `Interceptor`, `EventMetadata`) stay
  out of it: CDI 4.1, 5.5.8, gives them to a bean instance, and a lookup of one is checked as any other.
- **CDI Lite TCK**: `invokers.lookup.ArgumentLookupBeanManagerTest`, `ArgumentLookupEventTest` and
  `ArgumentLookupInstanceTest` cover the three types the old list held; none looks up the other four.
- **Verification**: red on c4addfe4, BUG-20261117-38 (`stack-l3b-red.log`, "BUG-20261117-44 red, on c4addfe4": the
  start refused the `BeanContainer`, the `Provider`, the `RequestContextController` and the `InjectionPoint`).
  Green: `stack-l3b-green.log`, "BUG-20261117-44 fix: lookups of every built-in bean the call serves" and
  "BUG-20261117-44 fix: vauban-core and vauban-processor, all tests".
- **Found by**: the review of BUG-20261117-27.
## BUG-20261117-43 — At start and frozen, the language model shows the synthetic methods of a class file

- **Date**: 2026-11-17
- **Status**: FIXED
- **Module**: `vauban-indexer` (`scanner.ClassFileScanner`), `vauban-core` (`langmodel.declarations.VaubanClassInfo`)
- **Symptom**: for a class with a lambda and a bridge method, `ClassInfo.methods()` gives `[get]` in the processor and
  `[get, get, lambda$get$0]` at start and frozen: the bridge `Object get()` the compiler adds for
  `Supplier<String>.get()`, and the body of the lambda. The same for a bean of the index and, since
  BUG-20261117-31, for a class outside it described from its class file, a class of the JDK included. The review's
  probe on a class of the class path (`SyntheticMembers`): `[<init>, get]` from the compiler,
  `[<init>, get, get, lambda$get$0]` from the bytecode scan.
- **Spec**: `ClassInfo.methods()` returns the methods "declared or implicitly declared in this class and all its
  superclasses up to and excluding `java.lang.Object`, as well as all direct and indirect superinterfaces". A bridge
  method or a lambda's body is neither: the compiler marks synthetic "a construct emitted by a Java compiler [that]
  does not correspond to a construct declared explicitly or implicitly in source code" (JLS 13.1). The language
  model's Javadoc does not name synthetic members, but its TCK checks them: `BridgeMethods` ("bridge method
  `void genericMethod(Object)` will be generated") expects the class's `genericMethod(String)` and its superclass's
  `genericMethod(T)`, and no bridge; `EnumMembers`: "enum constructors often have synthetic parameters, but the
  language model demands that these are _not_ presented".
- **Evidence**: in the BUG-20261117-40 probe (`vauban-b40-probe.log`, group 12), this bug shows in `BridgeMethods` by
  masking BUG-20261117-42. Each class there overrides a method of its superclass, and the TCK expects two methods,
  the class's and the superclass's, and no bridge. At start, the bridge stands in for the inherited method that
  BUG-20261117-42 leaves out: `Number numberMethod()` for the superclass's `Number numberMethod()`,
  `genericMethod(Object)` for its `genericMethod(T)`. So lines 56, 58 and 72 (two methods, one `numberMethod`
  returning `Number`) pass at start, and fail in the processor, which shows no bridge. Line 74 fails at start for
  BUG-20261117-42 alone: it counts a `genericMethod` whose parameter is a type variable, and the bridge's parameter
  is the class `Object`.
- **Fix order**: with BUG-20261117-42, or before it. Fixed alone, BUG-20261117-42 adds the inherited methods next
  to the bridges, and `BridgeMethods` lines 56 and 72 turn red at start (three methods where the TCK counts two),
  and, by reading its check, line 58 with them (two `numberMethod` returning `Number`).
- **Repro**: `VetoedInterceptorCompileTimeTest#anExtensionSeesNoSyntheticMethodInTheProcessorAtStartAndFrozen` in
  `vauban-processor`, `@Disabled` with this id: red with the `@Disabled` lifted on b7af3df3 (`vauban-b31-red.log`,
  "Fix round 1, red run 9"): `[get]` in the processor, `[get, get, lambda$get$0]` at start and frozen, for the class
  outside the index (`Teller`) and for the bean (`Counter`).
- **Cause**: `ClassFileScanner` (~72) records every method of the class file, `ACC_SYNTHETIC` and `ACC_BRIDGE` ones
  included, and `VaubanClassInfo.methods()` shows them all; `ElementScanner` reads the compiler's elements, which have
  none.
- **Cause, proved**: red on 8a2d7033 with the `@Disabled` lifted (`stack-l2d-red.log`, "RED BUG-20261117-43 on
  8a2d7033"): `[get, get, lambda$get$0]` at start and frozen for `Teller` and `Counter`, `[get]` in the processor.
- **Fix**: `VaubanClassInfo` leaves out of `methods()`, `fields()` and `constructors()` the members whose class file
  flags carry `ACC_SYNTHETIC` or `ACC_BRIDGE`: a bridge method, a lambda's body, an enum's `$values()` and `$VALUES`,
  an inner class's `this$0`. The index keeps them: the generators that read it (client proxies, subclasses, the
  bridge calls) need them. The processor's index has none, so the three ways agree. The synthetic leading parameters
  of an enum's constructor were fixed apart (BUG-20261117-58).
- **Verification**: the repro, green (`stack-l2d-dev43.log`, "BUG-43 fix: processor repro"); core 1733 (12 skipped),
  processor 434 (16 skipped), maven-plugin 172 (`stack-l2d-dev43.log`, "BUG-43 fix: core, processor, maven-plugin
  tests").
- **Found by**: the review of BUG-20261117-31.

## BUG-20261117-42 — The language model shows no inherited members and no `@Inherited` annotations

- **Date**: 2026-11-17
- **Status**: FIXED (vauban#336)
- **Module**: `vauban-core` (`langmodel.declarations.VaubanClassInfo`)
- **Symptom**: `ClassInfo.methods()` lists the class's own methods, plus those of its direct superinterfaces the index
  holds; the methods of its superclasses, of its indirect superinterfaces and of an interface outside the index are
  missing. `fields()` lists the class's own fields. `annotations()`, `annotation()` and `hasAnnotation()` leave out
  the `@Inherited` annotations of the superclasses. The same in the processor, at start and frozen.
- **Spec**: `ClassInfo.methods()`: "Returns a collection of methods declared or implicitly declared in this class and
  all its superclasses up to and excluding `java.lang.Object`, as well as all direct and indirect superinterfaces. If
  this class is `java.lang.Object`, its methods are not excluded. If this class is an interface, only superinterfaces
  are considered. Implicitly declared methods corresponding to `java.lang.Object` methods in interfaces are omitted.
  If the collection of methods described above contains multiple methods with the same signature, all such methods
  are returned." `ClassInfo.fields()`: "Returns a collection of fields declared or implicitly declared in this class
  and all its superclasses up to and excluding `java.lang.Object`, as well as all direct and indirect
  superinterfaces." `AnnotationTarget`, which `DeclarationInfo` extends, defines the annotations it shows as those
  "present on this annotation target", which "means: either the annotation is declared or implicitly declared
  directly on this annotation target, or this annotation target is a class declaration and the annotation is
  inherited from a superclass".
- **Evidence**: the BUG-20261117-40 probe of the CDI language model TCK (`vauban-b40-probe.log`, group 8): 61 checks
  fail at start for this cause, in `InheritedMethods` (25), `InheritedFields` (4), `InheritedAnnotations` (3),
  `RepeatableAnnotations` (27, the inherited ones), `PlainClassMembers` (1, line 211) and `BridgeMethods` (1, line
  74); in the processor, `EnumMembers` line 294 and `BridgeMethods` lines 56, 58 and 72 too. At start, those three
  lines pass only because BUG-20261117-43's bridge methods stand in for the inherited methods this bug leaves out;
  line 74 fails at start for this bug alone (BUG-20261117-43, "Evidence").
- **Fix order**: BUG-20261117-43 is fixed with this one, or before it. Fixed alone, this one adds the inherited
  methods next to the bridges, and `BridgeMethods` lines 56 and 72 turn red at start, and line 58 with them.
- **Cause**: `VaubanClassInfo.methods()` (~222) reads the class's index entry and the direct superinterfaces
  `IndexLookup.getClass` finds, `fields()` (~248) and the annotation methods (~262-295) the class's entry alone.
- **Impact**: Dirac's `namesAMetric` misses a metric declared on an inherited method; its companion DRC-011, on
  Dirac's `fix/langmodel-unindexed-classes`, works around it. Any extension that iterates `methods()`, `fields()` or
  the annotations sees fewer than the spec says. A fix changes that for every consumer at once, so it needs an audit
  of the consumers first.
- **Repro**: `VetoedInterceptorCompileTimeTest#anExtensionSeesInheritedMembersAndAnnotations` in
  `vauban-processor`: `Child extends Parent extends Grand`, with `Middle extends Top` and `Grand implements Top`, an
  `@Inherited` `@Kept` on `Grand` and `Parent`, a plain `@Own` on `Grand`. Red on eec568d9 (`stack-l2d-red.log`, "RED
  BUG-20261117-42 on eec568d9 (null-safe recorder)"): the processor, the start and frozen all gave `methods
  [Child.middle]`, `fields [Child.child]`, `annotations [jakarta.enterprise.context.Dependent]` and `Kept none`.
- **Fix**: `VaubanClassInfo.methods()` and `fields()` give the class's own members, those of its superclasses up to and
  excluding `java.lang.Object`, and those of every superinterface, direct or not, each interface once, each member
  with the class that declares it; a supertype nothing describes is left out with what is above it. `annotations()`,
  `annotation()` and `hasAnnotation()` add the `@Inherited` annotations its superclass has, whose type the class does
  not declare, and `repeatableAnnotation()` reads the superclass's when the class declares none, as
  `Class.getAnnotations` and `getAnnotationsByType` do; an interface inherits none. Whether a type is `@Inherited` is
  read from its declaration (`IndexLookup.find`). The three ways share the code. An annotation type's `methods()` now
  has `java.lang.annotation.Annotation`'s four, as the TCK's `AnnotationMembers` says.
- **What stays**: `@Enhancement` configures and visits the members a class declares, and its direct superinterfaces'
  methods the index holds, as before (`VaubanClassInfo.configurableMethods()`, `configurableFields()`): a
  `MethodConfig` changes the class it configures, and an inherited method belongs to its own class. `ClassConfig
  .methods()` of a `MetaAnnotations` annotation type is its members, not `Annotation`'s. Since BUG-20261118-48,
  `ClassConfig.methods()` and `fields()` of a class give its inherited members too, configured for that class alone;
  the calls `@Enhancement` makes are unchanged.
- **Verification**: the repro, green, with BUG-20261117-43's test now `[get, get]` (`Supplier.get`), three ways
  (`stack-l2d-dev42.log`, "BUG-42 fix (1)" and "BUG-42 fix (4)"); core 1752 (11 skipped), processor 440 (16
  skipped), maven-plugin 172 (`stack-l2d-dev42.log`, "BUG-42 fix (3)"), after `AnnotationMembersOutsideTheIndexTest`
  kept the members `Graded` declares and `BceUnindexedClassTest` expected `Annotation`'s methods. The probe and the
  brick audit are in the batch D report.
- **Found by**: the BUG-20261117-40 probe of the CDI language model TCK.

## BUG-20261117-41 — An interceptor that injects `@Intercepted Bean<?>` is refused

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/vauban-bug-stack` (lane `stack/l3-batch-2`), before BUG-20261117-47: each is testable
  alone, this one by a deployment that calls no intercepted bean.
- **Module**: `vauban-core` (`bean.validation.DeploymentValidator`, which the processor runs too)
- **Symptom**: an interceptor that injects the bean it intercepts, `@Inject @Intercepted Bean<?> intercepted`, or the
  same type written `Bean<? extends Object>`, is refused as a definition error, everywhere: "Illegal injection of
  built-in metadata type with type variable or raw type: field Watcher.intercepted of type
  ParameterizedType[rawType=jakarta.enterprise.inject.spi.Bean, typeArguments=[WildcardType[upperBound=null,
  lowerBound=null]]]". The container refuses it at start and frozen (`DefinitionException`); the processor reports it
  as an error, and the compilation fails. The processor refuses `Interceptor<Itself>` in an interceptor too. CDI 4.1,
  5.5.8, in CDI Lite, lets any interceptor inject both.
- **Repro**: in `vauban-processor`, `VetoedInterceptorCompileTimeTest`:
  - `#anInterceptorThatInjectsTheBeanItInterceptsDeploysInTheProcessorAtStartAndFrozen`, for `?` and for
    `? extends Object`;
  - `#anInterceptorThatInjectsItsOwnMetadataDeploysInTheProcessorAtStartAndFrozen`, `Interceptor<Introspector>`.

  Each checks what the processor reports, and that a container started on classes compiled with the processor's
  validation off, its extensions run at start, then frozen, starts. No call creates the interceptor: providing the
  metadata is BUG-20261117-47, whose repros call the bean. In `vauban-core`, the row of a wildcard without bounds in
  `DeploymentValidatorReflectionChecksTest#interceptedBeanMetadata`.
- **Cause** (proved, "BUG-20261117-41/47 red" and "BUG-20261117-41 red" below): two.
  - In `DeploymentValidator.isIllegalMetadataInjection`, the wildcard of `@Intercepted Bean<X>` was legal with an
    upper bound `Object` only (`lowerBound != null || !isObjectType(upperBound)`, and `isObjectType(null)` is false).
    The index records `?` and `? extends Object` without bounds: the bytecode scan and the reflective reading always
    did, and the compiler's elements do since BUG-20261117-34. So the container never deployed such an interceptor.
  - `@Intercepted Bean<X>` and `Interceptor<X>` are legal in an interceptor only, and the validator told an
    interceptor by loading its class (`DeploymentClasses.load`) to read `@Interceptor`. The processor cannot load a
    class it compiles: it took every interceptor for a plain bean, and refused both whatever `X`.
- **Fix**: from the parked branch `fix/intercepted-bean-metadata` (3dac4ef4), read and ported, not cherry-picked. A
  wildcard with no lower bound, and no upper bound or `Object`, is `?`. The validator reads `@Interceptor` from the
  index first, which holds the classes the processor compiles; when the index does not say so, it loads the class as
  before (`@Interceptor` is not `@Inherited`, so both give the same answer for a class the index holds).
- **CDI Lite TCK**: the Lite tests of `implementation.builtin.metadata.broken` (`typeparam.interceptor.*`,
  `injection.intercepted.*`, `injection.BuiltinInterceptorInjectionTest`) expect a definition error for the illegal
  forms, which the fix keeps; none injects the legal ones.
  `full.implementation.builtin.metadata.BuiltinMetadataBeanTest` does, in the CDI Full part the runner leaves out.
- **Other readers of a wildcard's bounds**, outside the language model, checked for the same assumption (that `?`
  has the upper bound `Object`): none has it. They read a missing bound as `Object` or as no constraint, test the kind
  only, or read another type:
  - `AssignabilityRules`: `isUnbounded` takes both forms; `wildcardAdmits` and `isAssignableToWildcard` read a missing
    bound as no constraint, and a bean's wildcard argument as `Object` (a legal bean type has none anyway);
  - `EventDispatcher` (`withinWildcard` skips a missing or `Object` upper bound, `resolveObservedType` reads a missing
    one as `Object`), `VaubanObserverMethod` and `VaubanSyntheticObserverBuilder` (a missing upper bound is
    `Object`);
  - `InjectionPointInfo`, `IndexCreationPlans`, `TypeRef` and the indexer's `ComponentCollector`: descriptors and
    erasures, a wildcard is `Object` or its upper bound;
  - `ClassValidator`, `IndexClassValidator` and `BeanDiscovery`: the kind only, or both bounds walked null-safe for a
    type variable; `BeanDiscovery.asReflected` keeps the bounds as they are, and `DeploymentValidator`'s own
    `typeArgMatchesBeanClass` (`Bean<X>`, `Interceptor<X>`) matches a class type only;
  - `ClassChecksums` writes the bounds as they are: every index producer gives the same form, so the build's and the
    start's checksums agree;
  - `SyntheticComponentRegistrar.wildcardName` and the processor's `AnnotationArtefacts.sourceName`: names, `?` for a
    wildcard without bounds (BUG-20261117-34);
  - `EnhancementApplier` reads no bound: it goes through `LangModelTypeMapper`. The processor's
    `ParameterizedSupertypes` gives `(null, null)` for both forms, as `BeanDiscovery` does for the reflective types;
  - `TypeHierarchyResolver`, `ReflectionValidator`, `ManagedBean`, `VaubanBeanManager`, `EventImpl` and
    `DisposerInvoker` read `java.lang.reflect.WildcardType`, whose `?` always has the upper bound `Object`.
- **Verification**: red on 92622b2d, BUG-20261117-45 (`stack-l3b-red.log`, "BUG-20261117-41/47 red, on 92622b2d"
  with the four serving repros, "BUG-20261117-41 red, on 92622b2d" with the deploy-only ones: the processor refused
  all three types, the start and frozen refused both wildcards). Green: `stack-l3b-green.log`, "BUG-20261117-41 fix:
  core and processor". The earlier runs, in `vauban-b22-red.log`, are those of the parked attempt ("Fix round 2 red
  run 1" to 5, "Round 4: repro with the parked fix").
- **Found by**: the review of 63cce2fc (BUG-20261117-22), by reading.
## BUG-20261117-39 — The start checks an invoker's argument lookups before the synthetic beans exist

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/invoker-argument-lookup`
- **Module**: `vauban-core` (`BceProcessor.processRegistration`, `ExtensionMethodValidator.validateInvokerLookups`)
- **Symptom**: an extension builds, in `@Registration`, an invoker of `String useGadget(Gadget gadget)` with
  `withArgumentLookup(0)`, and adds, in `@Synthesis`, a synthetic bean of `Gadget`: the start, and the build, fail
  with `Invoker argument lookup unsatisfied: no bean found for parameter type …Gadget at position 0 of method
  …GadgetUser.useGadget`, though the call would find that bean.
- **Repro**: `VaubanInvokerLookupTest#theStartAcceptsALookupASyntheticBeanSatisfies` in `vauban-core`. Red on the
  main code of 131fb3e6, fix round 4 of BUG-20261117-16 (`vauban-b27-red.log`, "Red run 3").
- **Why it goes with BUG-20261117-27**: the base refused a lookup only a synthetic bean satisfies, but it counted
  the classes of the parameter's type, qualifiers left out, so a class bean of that type with a qualifier was
  enough. BUG-20261117-27's check, run in `@Registration`, refuses what the base accepted: a managed
  `@Fast FastDevice implements Device` and a synthetic `@Default Device`, looked up as `Device`.
  `#theStartAcceptsALookupASyntheticBeanSatisfiesBesideAQualifiedClassBean` is green on the main code of 131fb3e6
  ("Red run 6"), red with BUG-20261117-27's check run in `@Registration` ("Red run 5"), and green with this fix.
- **Cause**: `invokeRegistrationMethod` checked the lookups of the invokers each `@Registration` call built, before
  `@Synthesis` ran, among the beans discovered. CDI 4.1, "Configuring lookups", asks for the check during deployment
  validation, among every bean of the deployment.
- **Fix**: `processRegistration` collects the invoker factories; `BceProcessor.process` checks their lookups once
  `@Synthesis` is over, among the beans and the descriptors of the synthetic beans of the extensions it ran
  (`withSyntheticBeans`). The build (`VaubanProcessor`) and the start (`VaubanContainerBuilder`) both run
  `BceProcessor.process`, so both agree. The synthetic beans of frozen extensions and of archives processed at
  compile come later at start: BUG-20261117-45.
- **Verification**: the tests green (`vauban-b27-dev.log`, "Dev run 10"). Whole build and TCKs under
  BUG-20261117-27.
- **Found by**: the work on BUG-20261117-27, reading where the start checks invoker lookups.

## BUG-20261117-38 — An invoker's instance lookup misses a target bean that has a qualifier

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/vauban-bug-stack` (lane `stack/l3-batch-2`), from the parked branch
  `fix/invoker-instance-lookup` (cc0cec19), with its review's defect fixed
- **Module**: `vauban-core` (`VaubanInvoker.lookupTarget`)
- **Symptom**: an invoker of `FastWorker.work()` with `withInstanceLookup()`, for a bean `@Dependent @Fast class
  FastWorker`: the call throws `UnsatisfiedResolutionException: No bean found for type: …FastWorker`. The same for a
  bean whose `@Typed` leaves its class out of its bean types.
- **Repro**: `VaubanInvokerLookupTest#anInstanceLookupFindsATargetBeanThatHasAQualifier` and
  `#anInstanceLookupFindsATargetBeanWhoseClassTypedLeavesOut` in `vauban-core`; with the parked branch's
  `#anInstanceLookupFindsTheManagedBeanNotAProducer`,
  `#anInstanceLookupOfAMethodAGenericSuperclassDeclaresFindsTheTargetBean` and
  `#anInstanceLookupOfAClassThatIsNoBeanIsUnsatisfied`, green before and after, which keep what the fix must not
  break.
- **Cause** (proved): `lookupBean` called `cdi.select(beanClass)`, which requires `@Default` and the class as a bean
  type. CDI 4.1, "Configuring lookups", asks for a contextual reference for the target bean and the bean type that
  declares the target method.
- **Fix**: `lookupTarget` takes the managed bean of the target class among the beans of that class with `@Any`, or
  among those of `Object` when `@Typed` leaves the class out, not a producer the class declares, and asks
  `getReference` for the bean type that declares the method, as the bean has it (`HolderService<String>`), or
  `Object`. The parked fix released the creational context after every call, which destroyed the `@Dependent`
  objects of a `@Singleton` target the lookup created: the context is released for a `@Dependent` target only, as
  `VaubanInvokerLookupTest#aSingletonTargetTheLookupCreatesKeepsItsDependents` requires.
- **CDI Lite TCK**: `invokers.lookup.InstanceLookupTest` and `InstanceLookupDependentTest` look a `@Default` target up,
  and passed before; none has a qualified or `@Typed` target.
- **Verification**: red on ed64b4f6, BUG-20261117-48 (`stack-l3b-red.log`, "BUG-20261117-38 red, on ed64b4f6": both
  repros, `UnsatisfiedResolution`). Green: `stack-l3b-green.log`, "BUG-20261117-38 fix: the target bean by its class,
  any qualifier, its declaring bean type" (33 invoker tests) and "BUG-20261117-38 fix: vauban-core, all tests".
- **Found by**: the work on BUG-20261117-27, testing that the qualifiers still apply.
## BUG-20261117-37 — At start, `ClassInfo.modifiers()` gives the class file's flags

- **Date**: 2026-11-17
- **Status**: FIXED
- **Module**: `vauban-indexer` (`scanner.ClassFileScanner`), `vauban-core` (`langmodel.declarations.VaubanClassInfo`)
- **Symptom**: `ClassInfo.modifiers()` should read as `java.lang.reflect.Modifier` reads a class's modifiers. At
  start and frozen, `Modifier.toString` gives `public synchronized` for `public class Plain`, and `public synchronized`
  for its `public static class Inner`; the processor gives `public` and `public static`. The same extension sees two
  answers for one class, and `Modifier.isStatic` is `false` at start for a static nested class.
- **Repro**: `VetoedInterceptorCompileTimeTest#anExtensionSeesTheModifiersOfAClassInTheProcessorAtStartAndFrozen` in
  `vauban-processor`, `@Disabled` with this id: red with the `@Disabled` lifted on 63cce2fc plus BUG-20261117-31's
  fix, which does not touch `modifiers()` (`vauban-b31-red.log`, "red run 7"): green in the processor, `public
  synchronized` for both classes at start and frozen.
- **Cause**: `modifiers()` returns the index's `accessFlags`, which `ClassFileScanner` takes from the class file's
  `access_flags`: `ACC_SUPER` (0x20), which javac sets on every class, is `Modifier.SYNCHRONIZED`, and a nested
  class's `static`, `private` and `protected` live in its own `InnerClasses` entry, which the scan reads for the
  simple name only. `ElementScanner` builds the flags from the compiler's modifiers. Every class at start is concerned,
  and since BUG-20261117-31 a class outside the index described from its class file too.
- **Fix idea**: give the language model the flags reflection gives, `Class.getModifiers()`'s rule: the class's own
  `InnerClasses` flags when it has an entry, without `ACC_SUPER`. Kept out of BUG-20261117-31: the index's
  `accessFlags` feed the bean discovery and the class checks, so the change belongs to the scan or to a new field of
  the index, and must be shown not to move a bean.
- **Cause, proved**: red on 9152f813 with the `@Disabled` lifted (`stack-l2d-red.log`, "RED BUG-20261117-37 on
  9152f813"): `public synchronized` for `Plain` and for `Plain$Inner` at start and frozen, `public` and `public static`
  in the processor.
- **Fix**: the index's `ClassInfo` gains `modifiers`, the class's modifiers as `Class.getModifiers()` gives them:
  `ClassFileScanner` reads them from the class's own `InnerClasses` entry when it has one, otherwise from its access
  flags without `ACC_SUPER`; the processor's flags already are its modifiers. `accessFlags` stays the class file's,
  which the bean discovery and the class checks read, so no bean moves. `ClassInfo.modifiers()` gives them, keeping
  the bits `java.lang.reflect.Modifier` gives a class or an interface. The frozen record's checksum of a class whose
  modifiers differ from its access flags but `ACC_SUPER`, a nested class, changes.
- **Verification**: the repro, widened to a `private static final`, a `protected abstract` inner and a member
  interface, green the three ways (`stack-l2d-dev37.log`, "BUG-37 fix (1): processor repro"); indexer, core,
  processor and maven-plugin tests: indexer 138; core 1753 (11 skipped), processor 440 (15 skipped), maven-plugin 172
  (`stack-l2d-dev37.log`, "BUG-37 fix (3)"), after `ClassChecksumsTest` kept the scanned nested class's modifiers
  and gained `theModifiersCount`.
- **Found by**: BUG-20261117-31's comparison of the processor and the bytecode scan.

## BUG-20261117-36 — The processor's scan overflows the stack on a member of a type variable bounded by itself

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/langmodel-unindexed-classes`, with BUG-20261117-31
- **Module**: `vauban-processor` (`apt.ElementScanner`)
- **Symptom**: a compilation fails with `java.lang.RuntimeException: java.lang.StackOverflowError` when Vauban's
  processor scans a class with a field, a parameter or a return typed by a type variable bounded by itself: the bean
  `@Dependent public class Box<T extends Comparable<T>> { private T value; ... }`, or a class of a library such as
  `Store<E extends Comparable<E>>` with a field of `List<? extends E>`.
- **Repro**: `ElementScannerSelfBoundedMembersTest#aBeanWithASelfBoundedMemberCompiles` in `vauban-processor`, red on
  the main code of 0360a032 (`vauban-b31-red.log`, "red run 3", where the bug was still numbered 34) and of 63cce2fc
  ("red run 6"); `ElementScannerOutsideClassesTest`, whose library has `Store`, failed in its `@BeforeAll` the same
  way in both runs.
- **Cause**: `ElementScanner.typeVariableWithUpperBound` mapped the bound of a member's type variable with
  `typeMirrorToTypeInfo`, which maps each type variable it meets with `typeVariableWithUpperBound` again: `T`,
  `Comparable<T>`, `T`, ... without end. The type parameters of a class are read with a guard
  (`TypeParameterReader`); the types of its members were not.
- **Fix**: a member's type is read with `MemberTypeVariables`, which remembers the type variables whose bound it is
  reading: a variable met again inside its own bound has no bound, as the bytecode scan records it
  (`ClassTypeParameters`). `T` in `<T extends Comparable<T>>` reads `T extends Comparable<T>`, the inner `T` without
  bound, the same model both ways (`ElementScannerOutsideClassesTest#theSameTypeForASelfBoundedField`).
- **Verification**: green in `vauban-b31-dev.log` from "dev run 2" on, and in the full build of the commit
  (`vauban-b31-build.log`).
- **Why with BUG-20261117-31**: before BUG-20261117-31, the processor's scan read the members of the classes of the
  compilation and of its class path only; a JDK class was a stub whose members nobody read. BUG-20261117-31 has it
  describe every class an extension reaches outside the index, the JDK's included, with all their members, and
  members typed by a type variable bounded by itself are common there: `Enum.compareTo(E)` in
  `Enum<E extends Enum<E>>`, `Comparator.naturalOrder()` and `Map.Entry.comparingByKey()`, whose type variables are
  bounded by a `Comparable` of themselves. Without this fix, a compilation would overflow where it got a stub before.
  In the red runs, the class that overflowed was `lib.Store<E extends Comparable<E>>`, with its field
  `List<? extends E>`, of `ElementScannerOutsideClassesTest`'s library, read before the test's JDK classes, and the
  bean `Box<T extends Comparable<T>>`.
- **Found by**: BUG-20261117-31's comparison of the processor and the bytecode scan on a class of a library.

## BUG-20261117-35 — A normal scope an extension adds to a generic class is no definition error

- **Date**: 2026-11-17 (entry written 2026-11-18)
- **Status**: FIXED on `fix/vauban-bug-stack` (lane `stack/l3-container`); Cassini companion CASSINI-065 on
  Cassini's `fix/vauban-bug-stack`
- **Module**: `vauban-core` (`bean.validation.GenericBeanScope`, `VaubanContainerBuilder`, `IndexClassValidator`,
  `ReflectionValidator`), `vauban-processor` (`VaubanProcessor.reportDefinitionErrors`)
- **Symptom**: `@Resource public static class GenericResource<T>`, where `@Resource` is no scope, and a build
  compatible extension `@Enhancement(types = Object.class, withAnnotations = Resource.class)` that adds
  `@RequestScoped`: the container starts, and `GenericResource` is a request-scoped bean. CDI 4.1, 3.1: "If the
  managed bean class is a generic type, it must have scope `@Dependent`. If a managed bean with a parameterized bean
  class declares any scope other than `@Dependent`, the container automatically detects the problem and treats it as
  a definition error." Cassini's `CassiniScopeExtension` does exactly this to a scope-less generic `@Path` class,
  and Cassini carried a transitional path for the request-scoped generic resource (CASSINI-061). A class that
  declares the scope itself was refused, with a message that did not name the scope.
- **Repro**: `GenericBeanScopeTest` (vauban-core): `aNormalScopeAnExtensionAddsToAGenericClassIsADefinitionError`
  started, `aScopeAGenericClassDeclaresIsNamed` lacked the scope; and
  `VetoedInterceptorCompileTimeTest#aNormalScopeAnExtensionAddsToAGenericClassIsADefinitionErrorAtBuildAtStartAndFrozen`
  (vauban-processor): no warning.
- **Does an extension's scope count?** Yes. Weld's `ManagedBean.checkType()` throws WELD-000071
  (`managedBeanWithParameterizedBeanClassMustBeDependent`) when `!isDependent()` and the enhanced annotated type is
  parameterized, and reads the scope from the annotated type the extensions modified, so a scope added in
  `ProcessAnnotatedType` counts. In CDI Lite, `@Enhancement` changes the annotations the container uses to discover
  the bean: the bean is request-scoped, so it is a bean with a parameterized class and another scope. The CDI TCK
  `org.jboss.cdi.tck.tests.definition.bean.genericbroken.GenericManagedBeanTest` (no `CDI_FULL` group, so in the
  Lite part, not excluded by Vauban's runner) asserts the declared case, `@RequestScoped FooBroken<T>`,
  `@ShouldThrowException(DefinitionException.class)`.
- **Cause** (proved): the rule ran in the class-level validators only, `IndexClassValidator.validateGenericBeanScope`
  and `ReflectionValidator.validateGenericBeanScope`, from `validateClassDefinitions`, before the enhancements: they
  read the annotations the class declares. Nothing checked the bean descriptors once the extensions had run; the red
  run shows the container starting.
- **Fix**: `GenericBeanScope.errors(beans, index)` checks the bean descriptors: a managed bean whose class has type
  parameters and whose scope is not `@Dependent` is a definition error, `Managed bean <class> has type parameters
  and is not @Dependent: @<scope>`. `VaubanContainerBuilder.build` runs it right after the enhancements apply, at
  every start, the extensions run or frozen. The processor adds its errors to the warnings of `reportDefinitionErrors`
  ("— the container rejects this deployment at start."), deduplicated with the class-level ones; a warning, as every
  definition error the processor reports, because an extension at start may still veto the class. The class-level
  messages name the scope the class declares, through the same `GenericBeanScope.error`.
- **Who sees the change**: an extension that gives a generic class a normal scope or `@Singleton` now fails the
  start, where the bean worked with that scope. Cassini's `CassiniScopeExtension` gave a scope-less generic `@Path`
  class `@RequestScoped`: CASSINI-065 gives it `@Dependent` and removes the transitional path. The message of a
  declared scope gains `: @<scope>`.
- **Verification**: red with the tests alone on the main code of b10193ee (`stack-l3-red.log`, "BUG-20261117-35 red:
  GenericBeanScopeTest, tests only, main code of b10193ee": 3 tests, 2 failures; "BUG-20261117-35 red:
  vauban-processor test, processor main code and vauban-core of b10193ee": no warning). The first version of the
  processor test started what plain javac compiled with the extension
  registered, which the processor's freeze of the enhancement for the deployment it compiled does not match
  (`stack-l3-green.log`, "BUG-20261117-35 fix", 1 failure; `stack-l3-dev.log`, "processor test starts what the
  processor compiled", 1 failure): the test now starts what the processor compiled, whose frozen enhancement applies
  `@RequestScoped`; `GenericBeanScopeTest` covers the extension run at start. Green: the whole reactor,
  `stack-l3-b35-full.log`, "BUG-20261117-35 fix: full reactor install with tests" (vauban-core 1637 tests, 13
  skipped; vauban-processor 386, 16 skipped). Cassini: `stack-l3-cassini.log` and `stack-l3-cassini-full.log`.
- **After review round 1** (2026-11-18): BUG-35 approved. Re-picked onto the amended BUG-20261117-30, -28 and -29;
  the start runs this check with the descriptor-bean check of BUG-20261117-30 in one list, after the enhancements.
  Green: the whole reactor, `stack-l3-r1-b35-full.log`, "BUG-20261117-35 after review round 1 re-pick" (vauban-core
  1651 tests, 13 skipped; vauban-processor 389, 16 skipped).
- **Other extensions that add a scope**: a grep of the main code of every `*/main` checkout of the workspace for
  `addAnnotation(` (2026-11-18) finds one extension that adds a normal scope or `@Singleton` to a class, Cassini's
  `CassiniScopeExtension` (`@RequestScoped`, `@ApplicationScoped`). Foy's `FoyWebExtension` adds `@Dependent`; the
  others add bindings, `@Priority`, `@Inject`, qualifiers or `@Vetoed` (Heisenberg, Humboldt, Noether, Mansart,
  Ravel).
- **Found by**: the review of CASSINI-061.

## BUG-20261117-34 — The language model gives `?` no bound, and the processor gives `? extends Object` one

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/langmodel-generic-supertypes`, with BUG-20261117-22
- **Module**: `vauban-processor` (`apt.ElementScanner`), `vauban-core` (`langmodel.types.TypeMapper`,
  `langmodel.types.VaubanWildcardType`, `extensions.LangModelTypeMapper`, `extensions.ObservedTypeNotation`)
- **Symptom**: the language model (`jakarta.enterprise.lang-model` 4.1.0, `WildcardType`) says: "`?`: unbounded, has
  an implicit upper bound of `java.lang.Object`. Note that `?` is equivalent to `? extends Object` and is represented
  as such. Therefore, either `upperBound()` or `lowerBound()` always returns non-`null`." For `class Wild implements
  Holder<List<?>>, Labelled<List<? extends Object>>`, with fields, a parameter and a return type of the same two
  shapes, an extension saw, through `types.ofClass("vi.types.Wild").declaration()`:
  - at start and frozen, `upperBound=null, lowerBound=null` for all six: no bound at all;
  - in the processor, the same for `?`, and `upperBound=CLASS java.lang.Object` for `? extends Object`: the build and
    the start disagree, on the supertypes as on the fields, the parameters and the return types.

  Neither `?` equalled `Types.wildcardUnbounded()`, which builds `? extends Object`.
- **Repro**:
  - `VetoedInterceptorCompileTimeTest#anExtensionSeesAnUnboundedWildcardAsExtendingObjectInTheProcessorAtStartAndFrozen`
    in `vauban-processor`, three ways, the `Wild` above;
  - `ElementScannerTypeParametersTest#extendsObjectIsTheUnboundedWildcard`, and `Generic.Wild` in `#genericSupertypes`:
    the processor's index against the bytecode scan's, supertypes and member types;
  - `LangModelTypeValueSemanticsTest#mapsTheUnboundedWildcardAsExtendingObject`,
    `ExtensionBuildersEdgeCasesTest#theUnboundedWildcardMapsBackToAWildcardWithoutBounds` and
    `SyntheticMetadataWriteTest#anUnboundedWildcardReadsBackWithoutBounds` in `vauban-core`.

  Red on 0360a032 (the first commit of BUG-20261117-22, which brought the supertypes into the language model):
  `vauban-b22-red.log`, "Fix round 1 red run 6", every one of them but the notation test, with the outcome above;
  "Fix round 1 red run 7", the notation test, on the main code of 0360a032 (the round's main changes set aside):
  `java.util.List<?>` read back as `WildcardType[upperBound=ClassType[name=java.lang.Object]]`.
- **Cause**: the index records `?` without bounds, and `? extends Object` the same: the bytecode scan drops the
  `Object` bound (`ClassTypeParameters`), and so does the reflective reading (`TypeInfoUtils`). `ElementScanner` kept
  the `Object` the compiler gives `? extends Object` as an upper bound. `TypeMapper` mapped the wildcard without bounds
  to a language model wildcard without bounds. Back the other way, `LangModelTypeMapper` kept `Object` as an upper
  bound, and the metadata reader of the processor's synthetic observers (`ObservedTypeNotation`) read `?` with it.
- **Fix**: the index keeps one form, without bounds; the language model shows it as the spec says.
  - `ElementScanner` drops an `Object` upper bound, as the bytecode scan does.
  - `TypeMapper` gives a wildcard without bounds `java.lang.Object` as its upper bound.
    `VaubanWildcardType.toString()` names `? extends Object` `?`, as reflection does.
  - `LangModelTypeMapper` maps `? extends Object` back to the wildcard without bounds.
  - `ObservedTypeNotation` reads `?`, and `? extends java.lang.Object`, as that wildcard.
- **Who sees the change**:
  - every type the language model maps from the index: the arguments of the supertypes, the types of the fields, the
    parameters and the return types, the bounds of the type parameters, the bean types of `BeanInfo.types()`, the types
    of the injection points, the observed event types. Their `?` has `upperBound()` `java.lang.Object`, where it was
    `null`, and equals `Types.wildcardUnbounded()`; its `toString()` stays `?`. `Types.wildcardUnbounded()` itself now
    names `?`, where it named `? extends java.lang.Object`;
  - the processor's index, wherever the source writes `? extends Object`: a field, a parameter, a return type, a
    supertype, a producer's type, an injection point. It is now the start's;
  - a type an extension hands back from the language model, a synthetic bean's or observer's type, or a type an
    enhancement reads back from a field or a parameter: `? extends Object` is the index's wildcard without bounds,
    where it kept `Object`; a synthetic observer of `List<?>` reads back the same from the processor's metadata;
  - the built-in metadata check gives the same verdicts (BUG-20261117-41, open);
  - `SyntheticComponentRegistrar.wildcardName` writes `?` for the `Types.wildcardUnbounded()` of a type an extension
    builds, as reflection's `getTypeName()` does, where it wrote `? extends java.lang.Object`;
  - `AnnotationArtefacts.sourceName` writes `?` for `? extends java.lang.Object` in the sources the processor
    generates.

  The assignability rules treat both forms alike (`AssignabilityRules.isUnbounded`, `wildcardAdmits`), so no
  resolution changes. That no test of the build and no TCK outcome changed holds for the tests only: a verdict that
  reads the form could change. The check of built-in metadata reads it, and gives the same verdicts; BUG-20261117-41
  lists the other readers of a wildcard's bounds, none of which reads the form.
- **Not covered**: the language model TCK (`cdi-tck-lang-model` 4.1.0, `AnnotatedTypes.wildcardMethod`) checks
  `upperBound()` `java.lang.Object` for `List<?>` and `List<? extends Object>`. `vauban-tck-runner` declares the
  artifact, but none of the 774 tests it runs calls `LangModelVerifier`, so the TCK did not catch this.
- **Verification**: green in `vauban-b22-dev.log`, "Fix round 1 dev run 5". The full build and the TCKs are those of
  BUG-20261117-22: `vauban-b22-build.log`, "Fix round 4 full build", and `vauban-b22-tck.log`, "Fix round 3 TCK".
- **Found by**: the review of 0360a032 (BUG-20261117-22), by reading.

## BUG-20261117-33 — A type variable the language model reaches through a type has no bound

- **Date**: 2026-11-17
- **Status**: FIXED (2026-11-18)
- **Module**: `vauban-core` (`langmodel.types.TypeMapper`), as it maps the index's `TypeInfo.TypeVariable`
- **Symptom**: `TypeVariable.bounds()` is empty for a type variable declared without a bound, when the language model
  reaches it through a type: the argument of a supertype (`Supplier<T>` above `Supplying<T>`), the type of a field, a
  parameter or a return, a bean type. The API says "never `null` or empty"; the same `T` has
  `[java.lang.Object]` through `ClassInfo.typeParameters()`.
- **Repro**: `BceClassSupertypesTest#aTypeVariableOfASupertypeHasItsBound` in `vauban-core`, disabled; red when
  enabled (`vauban-b22-red.log`, "BUG-33 red run 5", on 455067f5 with the fix of BUG-20261117-22, which this bug is
  not part of): `typeParameters() [java.lang.Object]`, `superInterfaces() []`.
- **Cause**: the index leaves out the implicit `java.lang.Object` bound of every type variable, and gives a type
  variable met again inside its own bounds, or one of an enclosing class or method, no bound at all, so that the model
  stays finite. `VaubanClassInfo.typeParameters()` puts `Object` back for a type parameter that declares no bound;
  `TypeMapper` does not.
- **Impact on Mansart**: `VaubanTypeVariable.equals` compares the bounds, so the `T` of `typeParameters()`
  (`[java.lang.Object]`) does not equal the `T` of a supertype's argument (`[]`). A substitution keyed on the
  `TypeVariable` objects, from the type parameters of a repository's supertype to the arguments it gets, misses; one
  keyed on `name()` works.
- **More repros** (batch C, on 8a01240d, `stack-l2c-red.log`): "RED BUG-20261117-33 on 8a01240d",
  `BceTypeVariableBoundsTest` at start: `B extends A extends ` for `<A, B extends A>` (the language model TCK's
  `AnnotatedTypes` 720-723), `T extends ` for a method's `<T> T method(T)`, and the `S` inside `Comparable<S>` of
  `<S extends Comparable<S>>` without bounds; "RED BUG-20261117-33 in the processor on 8a01240d",
  `VetoedInterceptorCompileTimeTest#aTypeVariableReachedThroughATypeHasItsBoundsThreeWays`, the same in the processor,
  at start and frozen.
- **Fix**: `TypeMapper` gives a type variable the bounds the index records for it, or else those its type parameter
  declares in the scope of the type's use (`TypeParameterScope.declaredBounds`, method then class), or else
  `java.lang.Object`. A variable met again inside its own bounds, which the index records without bounds so that the
  model stays finite (BUG-20261117-36), is a `VaubanTypeVariableReference`: the same type variable, whose bounds are
  mapped when asked, equal to it, and shown by its name. A type variable hashes by its name, which equal ones share.
  `TypeParameterScope.typeParameters` maps its parameters through `TypeMapper` too, and `VaubanMethodInfo.receiverType`
  loses its local `Object` patch. `LangModelTypeMapper`, which turns an extension's types back into the index's, drops
  the `Object` bound and the bounds of a variable met inside its own, so the index and the frozen record keep their
  conventions: neither the index nor the frozen checksum change.
- **Observable**: see `migration.adoc`; an extension that walks bounds recursively must stop at a variable it is
  already reading, as with reflection (the processor's `BeanTypeRecorder` test helper had to).
- **Verification**: `stack-l2c-dev.log`, "BUG-33 fix: indexer, core, processor, maven-plugin tests" and "BUG-33:
  three-way processor test and receiver patch removed" (green);
  `BceClassSupertypesTest#aTypeVariableOfASupertypeHasItsBound` enabled and green.
- **Found by**: the tests of BUG-20261117-22.

## BUG-20261117-32 — An interface's `ClassInfo.superClass()` is `Object` at start and `null` in the processor

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/langmodel-generic-supertypes`
- **Module**: `vauban-core` (`langmodel.declarations.VaubanClassInfo`)
- **Symptom**: for an interface, `superClass()` answers `CLASS java.lang.Object` at start and frozen, and `null` in
  the processor: the same extension sees two answers for one class. The language model says `null`: "Returns `null`
  if this class does not have a superclass; that is, if this class is `java.lang.Object` or an interface".
- **Repro**: `BceClassSupertypesTest#anInterfaceHasNoSuperclass` in `vauban-core`, an interface and an annotation type,
  at start: red on the main code of 455067f5 (`vauban-b22-red.log`, "red run 4": `superClass=CLASS
  java.lang.Object` for both). Three ways, `vi.types.Probe` and `vi.types.Archive` of
  `VetoedInterceptorCompileTimeTest#anExtensionSeesTheGenericSupertypesOfAClassInTheProcessorAtStartAndFrozen`:
  `superClass=null` in the processor, `superClass=CLASS java.lang.Object` at start and frozen (red runs 3 and 4).
- **Cause**: the class file of an interface names `java/lang/Object` as its superclass (JVMS 4.1), which
  `ClassFileScanner` records as `superName`; the compiler gives an interface no superclass (`TypeKind.NONE`), which
  `ElementScanner` records as `null`; `superClass()` mapped `superName`.
- **Fix**: `VaubanClassInfo.superClass()` answers `null` for an interface and an annotation type. The index keeps
  `superName` as each scan reads it, for the bean discovery and the rest. `superClassDeclaration()` was `null` both
  ways already (the index holds no `java.lang.Object`).
- **Who sees the change**: an extension the container runs at start, or that the build freezes, now gets `null` for an
  interface, as an extension the processor runs already did.
- **Verification**: green in `vauban-b22-dev.log` ("dev run 1", "dev run 2"), and as BUG-20261117-22.
- **Found by**: the three-way test of BUG-20261117-22.

## BUG-20261117-31 — The language model gives a class outside the index an empty stub

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/langmodel-unindexed-classes`
- **Module**: `vauban-core` (`langmodel.IndexLookup`, `langmodel.ClassFileDeclarations`,
  `langmodel.declarations.VaubanClassInfo`, `langmodel.types.VaubanClassType`, `langmodel.VaubanAnnotationInfo`,
  `langmodel.VaubanAnnotationMember`, `container.VaubanContainerBuilder`), `vauban-processor`
  (`CompilerDeclarations`, `VaubanProcessor`, `apt.ElementScanner`), `vauban-maven-plugin` (`generate.VaubanGenerator`)
- **Symptom**: an extension that follows a type to a class the index does not hold gets a stub: superclass `Object`,
  no interface, no type parameter, no member. That class may be one of a dependency jar (Jakarta Data's
  `CrudRepository`), of the JDK (`Supplier`), or one of the application the deployment did not add. Mansart walks
  `RoutedNoteArchive` → `CrudRepository<ArchivedNote, Long>` → `BasicRepository<ArchivedNote, Long>` to substitute
  `T` and `K`, and its probe on 0360a032 stopped at the stub:

  ```
  jakarta.data.repository.CrudRepository typeParameters=0 superInterfaces=[]
  jakarta.data.repository.BasicRepository typeParameters=0 superInterfaces=[]
  ```

  The three places did not agree. In the processor, a class the compilation sees, on its class path too, was
  described (`unindexedResolver`, VAU-BCE-008), but a JDK class was a stub. At start and frozen, every class outside
  the index was a stub. In all three, `superClassDeclaration()` and `superInterfacesDeclarations()` read the index
  alone: `null` and a list without the class, while `superClass().asParameterizedType().genericClass().declaration()`
  gave the processor's description. At start and frozen, `AnnotationInfo.declaration()` and an enum member's
  `asEnumClass()` threw `IllegalArgumentException: Class not found in index` for a class outside the index.
  **Mansart carries a documented reflective fallback until this fix (MANSART-068, mansart#109).** This fix, with
  BUG-20261117-22, which gives the supertypes their type arguments, is what lets it go.
- **Repro**: three ways, in `vauban-processor`,
  `VetoedInterceptorCompileTimeTest#anExtensionSeesAClassOutsideTheIndexDescribedInTheProcessorAtStartAndFrozen`:
  `interface Archive extends Crud<Note, Long>`, `interface Crud<T, K> extends Basic<T, K>`,
  `interface Basic<T, K>`, `Crud` and `Basic` not in the index; an `@Synthesis` method records the declarations it
  reaches, `Supplier`'s and `Plain`'s superclass declaration. In the processor `Crud` and `Basic` were described but
  `superInterfacesDeclarations()` was `[]`, `Supplier` a class without type parameters and `Plain`'s superclass
  declaration `null`; at start and frozen, `Crud` was a class with neither type parameters nor superinterfaces.
  `BceUnindexedClassTest` in `vauban-core`, at start: Mansart's shape, `Supplier` (`[T]`), a class of the class path
  never added (`Elsewhere`, with its fields, methods, constructor and annotation), an annotation type and an enum class
  outside the index, a class nowhere (the stub, its name kept), and a guard that the beans and their types do not
  change. `ElementScannerOutsideClassesTest`: the processor and the bytecode scan give a class of a library and of the
  JDK the same declaration (name, kind, type parameters, supertypes, wildcards). Fix round 1:
  `BceUnindexedClassTest#aClassNoClassFileDescribesKeepsItsOldAnswers` at start and `UnseenClassProcessorTest` in
  the processor, a class file `Kept` whose superclass, one interface, two annotation types and an enum class exist
  nowhere; `ClassFileDeclarationsTest#aWalkUpTheSuperclassesEnds` and `#objectHasNoSuperclass`; and
  `OutsideClassGenerationTest` in `vauban-maven-plugin`, an `@Enhancement` that `vauban:generate` runs.
- **Cause**: `VaubanClassType.declaration()` described a class only through `IndexLookup.describe`, which is the
  index, plus the processor's resolver; anything else became VAU-BCE-001's stub. The container had no resolver, the
  processor's left out the JDK, and the declaration methods (`superClassDeclaration()`, `superInterfacesDeclarations()`,
  `AnnotationInfo.declaration()`, `asEnumClass()`) read the index alone.
- **Fix**: every path to a class goes through `VaubanClassInfo.of(name, lookup)`, which describes it with
  `IndexLookup.declaration`, the first time more than its name is asked: the index, else the language model's own
  resolver, else the stub. The resolver is a `ScopedValue` binding, separate from the bean discovery's: at start and
  frozen (`VaubanContainerBuilder.build`, around the whole extension cycle) and in `vauban:generate`, the class file
  the deployment's class loader finds as a resource (`ClassFileDeclarations`, `ClassFileScanner`: nothing is loaded);
  in the processor, the compiler's model, the JDK included (`CompilerDeclarations`, `ElementScanner`), which still
  leaves the JDK out for the bean discovery. Each place caches its descriptions per container build or compilation,
  one per class asked, in a `ConcurrentHashMap`: concurrent askers may read one class file twice and all get the
  first description. `superClassDeclaration()` is now `java.lang.Object`'s declaration for a class that names no other
  superclass, as the language model asks, and `null` for an interface, as `superClass()`. Nothing outside the
  language model changes: the described class joins neither the index nor the bean discovery, the bean types or the
  fingerprint.
- **Fix round 1** (review of b7af3df3):
  - a class no class file and no element describes keeps the answer each path gave, the coordinator's rule after the
    Dirac audit, so that a consumer keeps its "I cannot see this class" path: a type's `declaration()` its stub,
    `superClassDeclaration()` `null`, `superInterfacesDeclarations()` without it, and an annotation's `declaration()`
    and `asEnumClass()` the `IllegalArgumentException: Class not found in index: <name>` they threw
    (`IndexLookup.find` and `requireDeclaration`). The first commit gave a stub on every path.
  - `java.lang.Object` has no superclass, wherever its description comes from: its stub names none, and
    `VaubanClassInfo` answers `null` for it. The first commit's stub of `Object` named `Object` as its superclass, so a
    walk up `superClassDeclaration()` outside the extension phases, or from a class with no lookup, never ended.
  - in the processor, a supertype the compiler cannot find, named by a class file of the class path, is named as the
    class file names it (`ElementScanner.unresolved`), as the bytecode scan names it: it was `java.lang.Object`, so the
    language model gave `Object`'s declaration instead of `null`, and an interface `Object` instead of leaving it out.
- **Persistence and fingerprint**: the frozen record does not fingerprint the classes described this way. What an
  extension reads of them, type parameters, supertypes, members and annotations, comes from the deployment's own
  classes in the wide sense the freeze rule allows: the dependency jars the application is built and packaged with,
  and the JDK the build runs on: the freeze reads the class files of the JVM that runs the build, not those of the
  `--release` it compiles for. A changed dependency between build and start is a different application; the
  fingerprint has never covered the classes the build reads beyond the bean classes (the classes VAU-BCE-008
  describes, a bean's superclass in a jar). Fingerprinting them would read at start
  the very class files the freeze spares, and the set is only known by running the extensions. If an application
  ever starts on another version of a jar its extensions read, the frozen record is stale for it, as it already is for
  those classes. **Lifted by BUG-20261118-30** (2026-11-18): the freeze notes each class it describes this way, the
  set the extensions actually reached, and the start compares their checksums, the JDK's classes left out.
- **Who sees the change**: an extension at start or frozen, and in the processor for a JDK class, now reads type
  parameters, supertypes, members and annotations where it read a stub, for every class a class file or the
  compilation describes; for one nothing describes, every path keeps its old answer.
  - **Dirac**: `namesAMetric` walks `superClassDeclaration()` to `null`. It now reads the superclasses a jar holds,
    `java.lang.Object` last, where it deferred to its reflective scan at the first one outside the index, and it still
    defers where nothing describes the class. Its companion DRC-011 covers it.
  - **Foy** (`FoyWebExtension.hasBeanDefiningAnnotation`, ~216): a servlet, filter or listener that carries a library
    annotation, neither a scope nor a stereotype, made `declaration()` throw at start, and Foy left the class alone.
    When the annotation's class file is found, Foy now reads it and gives the class `@Dependent` at start, as the
    processor already did.
  - **Champollion** (`JsonbCdiExtension`): its `@Validation` asks `injects()` of the class a JSON-B annotation names,
    which `collect` (~88) reached through `asClass().declaration()`, an adapter say. At start such a class outside the
    index was a stub without members, so `injects()` read nothing; an adapter with `@Inject` members that is no bean
    now fails the start, as it already failed the build.
  - **The processor, for a supertype another processor generates in the first round**: `ElementScanner.unresolved`
    names it as javac names the error type, by its simple name, `Bar_`, where the processor's index had
    `java.lang.Object`; at start it is `p.Bar_`, from the class file. `VaubanProcessor.indexOnce` (~531) never scans
    the class again, so in the processor an extension's `superClass()` names `Bar_`, and the bean's types gain a type
    `Bar_` that names no class (BUG-20261117-50's repro of a bean's supertypes). The generated code does not change
    (the review compared it). The processor and the start disagreed before this commit; only the shape of the
    disagreement is new. BUG-20261117-50's fix, a new scan of such classes, covers both.
- **Verification**: red on the main code of 0360a032 (`vauban-b31-red.log`, "red run 1", "red run 2", "red run 4")
  and of 63cce2fc, BUG-20261117-22's commit amended, which is this commit's base ("red run 5", "red run 6");
  `ClassFileDeclarationsTest` calls the new `IndexLookup.classFiles` and `stub`, does not compile on the base, and
  its behaviour is covered red by `BceUnindexedClassTest`. Green in `vauban-b31-dev.log`; the full build of the commit
  (`vauban-b31-build.log`), the CDI Lite TCK and the AtInject TCK (`vauban-b31-tck.log`), each under the header that
  names the commit. Fix round 1: red on b7af3df3 (`vauban-b31-red.log`, "Fix round 1, red run 8" at start and for
  `java.lang.Object`, "red run 9" in the processor); `OutsideClassGenerationTest` red with `VaubanGenerator` put back
  to 63cce2fc's ("red run 10"); green in `vauban-b31-dev.log` from "Fix round 1, dev run 5" on, and in the full build
  and the TCKs under the "Fix round 1" headers. Rebased onto b63cc6cb: the full build and the TCKs under the "Rebase
  on b63cc6cb" headers. Fix round 2 changes this file and adds BUG-20261117-50's disabled repro, and no `src/main`
  file: the full build under the "Fix round 2" header; the TCKs were not run again. Fix round 3 answers the local
  SonarQube analysis of 7fa7d214: each cache keeps a lookup, a miss included, in a small private class that holds
  the `Optional` every asker gets, instead of an `Optional` it compared with `null`, with the same behaviour; the full
  build and the TCKs under the "Fix round 3" headers.
- **Found by**: Mansart's probe on BUG-20261117-22's commit (MANSART-068, mansart#109).

## BUG-20261117-30 — The start refuses an inherited initializer's parameter of a type variable

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/vauban-bug-stack` (lane `stack/l3-container`)
- **Module**: `vauban-core` (`ClassValidator.validateInjectionParameters`, `IndexClassValidator`, `ReflectionValidator`)
- **Symptom**: `AbstractGadgetSetter<T>`, abstract, declares `@Inject void set(T gadget)`, and the bean
  `GadgetSetterOfAbstract extends AbstractGadgetSetter<Gadget>` inherits it with `T` given a class; so does
  `GadgetSetterOfVetoed` from the `@Vetoed` `VetoedGadgetSetter<T>`. The start refuses the deployment:
  `DefinitionException: CDI definition validation failed: - Injection point has type variable type:
  ...$AbstractGadgetSetter.set - Injection point has type variable type: ...$VetoedGadgetSetter.set`. The class that
  declares the initializer is no bean, and its subclass gives `T` a class, as for an inherited observer (CDI TCK,
  `MemberLevelInheritanceTest`). The build does not warn about it, by reading: the processor runs
  `IndexClassValidator`, which checks no initializer for a type variable, and `ClassValidator` runs at start only.
- **Repro**: `AssignabilityRulesReproductionTest#anInheritedInitializersParameterOfATypeVariableItsBeanClassGivesAClassIsLegal`
  in `vauban-core`, disabled; red when enabled, on a3d8c032, fix round 2b of BUG-20261117-16 (`vauban-b16-red.log`,
  "Red run 16").
- **Cause** (proved): `ClassValidator.validateInjectionParameters` checked the parameters of every `@Inject` method
  and constructor of every class in the index, a bean or not, for a bare type variable. The red run shows the start
  refusing `AbstractGadgetSetter.set` and `VetoedGadgetSetter.set`; the processor's `IndexClassValidator` and the
  start's `ReflectionValidator`, which have the "may be a bean" scope of BUG-20261117-16, checked no initializer or
  constructor parameter for one, so the build did not warn of `GadgetSetter<T>`'s own `@Inject void set(T)`.
- **Fix**: the check moves out of `ClassValidator` into the rules of a class that may be a bean (neither abstract nor
  `@Vetoed`, nor in a vetoed package), in `IndexClassValidator` (the processor, and the start when the index has the
  generic members) and `ReflectionValidator` (the start otherwise), beside the observer, disposer and producer checks:
  `Injection point has type variable type T: <class>.<method> initializer param <i>`, or
  `<class> constructor param <i>` for a bean constructor. The injection already resolved `T` by the bean class.
- **Who sees the change**: a bean that inherits an initializer of a type variable from an abstract or vetoed class
  now starts; the build now warns of a bean's own initializer or constructor of a type variable, and the start's
  message names the variable and the parameter. A generic initializer, `@Inject <U> void init(U u)`, gets the message
  for its `U` beside `cannot declare type parameters`.
- **Review round 1** (2026-11-18): two gaps. (1) The "may be a bean" scope did not require a bean-defining
  annotation: a concrete `SetterBase<T>` without one, no bean under annotated discovery, with `@Inject void set(T)`,
  was refused at start when the bean `SetterSub extends SetterBase<Gadget>` inherits it, and the build said nothing.
  (2) An initializer a bean inherits and leaves a type variable of its own, `GenericSetterOfVetoed<T> extends
  VetoedSetter<T>`, was not checked (`getDeclaredMethods` only): the container started, then failed with "Failed to
  call initializer method: set" at the first lookup. Fix: `mayBeABean` requires a bean-defining annotation or a bean
  descriptor, as bean discovery decides, in `IndexClassValidator` and `ReflectionValidator`; the start validates
  again, after the extensions ran, the managed beans whose class has no bean-defining annotation
  (`VaubanContainerBuilder.descriptorBeanErrors`), and the processor treats every managed bean descriptor's class as
  a bean (`IndexClassValidator.withBeanClasses`). Both validators walk the superclasses that are no bean for the
  initializers a bean inherits, read through the bean class's type arguments (an initializer a class below
  overrides is none), and the processor's validator describes a superclass it did not index, a vetoed one, from the
  compiler's elements. The message prefix is one constant, `IndexClassValidator.TYPE_VARIABLE_INJECTION_POINT`.
  Verification: `stack-l3-red.log`, "BUG-20261117-30 review round 1 red: tests only, main code of 'type-variable
  initializer check on beans'": `TypeVariableInjectionPointTest` 2 failures (the legal deployment refused, the
  inherited type variable started), `IndexClassValidatorTest` 4 failures, the processor's
  `anInheritedInitializersParameterLeftATypeVariableIsADefinitionErrorAtBuildAtStartAndFrozen` not warned; the
  extension-made bean and the processor's legal inherited initializer were green before too, and guard the new rule.
  Green: `stack-l3-green.log`, "BUG-20261117-30 review round 1 fix, run 2" (vauban-core 1633 tests, 17 skipped;
  vauban-processor 385, 16 skipped), then the whole reactor (`stack-l3-r1-b30-full.log`).
- **Verification**: red on the stack base, "fix(core): a class outside the index is described", with the tests alone
  (`stack-l3-red.log`, "BUG-20261117-30 red":
  `AssignabilityRulesReproductionTest#anInheritedInitializersParameterOfATypeVariableItsBeanClassGivesAClassIsLegal`
  refused, `IndexClassValidatorTest#typeVariableInitializerAndConstructorParameters` and two `sameErrorsAsReflection`
  cases empty; "BUG-20261117-30 red: vauban-processor":
  `VetoedInterceptorCompileTimeTest#anInitializersParameterOfATypeVariableIsADefinitionErrorOnAClassThatMayBeABean`
  not warned). Green: `stack-l3-green.log`, "BUG-20261117-30
  fix, round 2" (vauban-core 1623 tests, 17 skipped; vauban-processor 383, 16 skipped), and the whole reactor,
  `stack-l3-b30-full.log`. The processor test of the inherited initializer, at build, at start and frozen, was green
  on the base too: a scanned class path does not index the abstract class.
- **Found by**: the review of fix round 2b of BUG-20261117-16.

## BUG-20261117-29 — An `@Inject` field of a type variable is not a definition error

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/vauban-bug-stack` (lane `stack/l3-container`)
- **Module**: `vauban-core` (`ClassValidator.validateFields`, `IndexClassValidator` and `ReflectionValidator`,
  `validateInjectFields`, `DeploymentValidator`)
- **Symptom**: the bean `@Dependent GadgetField<T> { @Inject T gadget; }` has an injection point of a type variable,
  a definition error (CDI 4.1, 5.2.3; TCK audit `legal_injection_point_types` c). The start reports a deployment
  problem instead, the field looked up by its erasure: `DeploymentException: CDI deployment validation failed: -
  Ambiguous dependency: field AssignabilityRulesReproductionTest$GadgetField.gadget of type TypeVariable[name=T,
  bounds=[]]. Matching beans: [...$Gadget, ...$GadgetField]`.
- **Repro**: `AssignabilityRulesReproductionTest#anInjectFieldOfATypeVariableIsADefinitionError` in `vauban-core`,
  disabled; red when enabled, on a3d8c032, fix round 2b of BUG-20261117-16 (`vauban-b16-red.log`, "Red run 16").
- **Cause** (proved): no validator checks a field for a bare type variable: `ClassValidator.validateFields`
  checks `@Produces` with `@Inject` and `final`, and `validateInjectFields` of the index and of the reflection
  validators check for a raw `Event` or `Instance` only. A fix needs the "may be a bean" scope of
  BUG-20261117-16: the CDI TCK's vetoed `inheritance.generics.Bar<T1, T2>` has `@Inject T1 t1`, which its bean
  subclass `Foo extends Bar<String, Qux>` gives a class.
- **Fix**: `validateInjectFields` of `IndexClassValidator` and `ReflectionValidator` reports an `@Inject` field of a
  type variable on a class that may be a bean, `Injection point has type variable type T: <class>.<field>`, and one
  inherited from superclasses that are none, read as the class gives their type variables: `Foo extends
  Bar<String, Qux>` gives `T1` a class, no error; `GenericFieldOfVetoed<T> extends VetoedGadgetField<T>` leaves it
  `T`, an error, through one superclass or more. The processor's validator describes a superclass it did not index,
  a vetoed one, from the compiler's elements: it skipped every class that extends one. `DeploymentValidator` no
  longer looks an injection point of a type variable up by its erasure, which reported it ambiguous or unsatisfied:
  the build failed on that error before the definition warning.
- **Who sees the change**: the start throws a `DefinitionException` where it threw a `DeploymentException`, the
  build warns of the definition error where it failed with `Ambiguous dependency` or `Unsatisfied dependency`, and a
  bean that leaves an inherited `@Inject` field a type variable no longer starts. The processor now validates a bean
  class whose superclass it did not index, and may warn of its other definition errors at build.
- **Review round 1** (2026-11-18): the check refused a legal deployment. A concrete `FieldBase<T> { @Inject T got; }`
  without a bean-defining annotation is no bean under annotated discovery, and the bean `FieldSub extends
  FieldBase<Gadget>` gives the field its class; the start threw `DefinitionException ... FieldBase.got`, while the
  processor, which validates bean classes only, said nothing. Fix: the "may be a bean" rule of BUG-20261117-30's
  round 1 (a bean-defining annotation or a bean descriptor, as bean discovery decides), and the field walk joins the
  initializer walk of the superclasses that are no bean (`validateInheritedInjectionPoints` in both validators); a
  static field is no injection point. Verification: `stack-l3-red.log`, "BUG-20261117-29 review round 1 red: the new
  field tests, main code of the first 'field of a type variable' commit": the legal `FieldSub` refused at start
  (`TypeVariableInjectionPointTest#anInjectFieldOfAClassWithoutABeanDefiningAnnotationIsItsSubclasss`), and the index
  reporting `NoBeanTypeVariableInjectField`; the processor's
  `anInjectFieldOfAClassWithoutABeanDefiningAnnotationIsInheritedAtBuildAtStartAndFrozen` was green before too (a
  scanned class path). Green: `stack-l3-green.log`, "BUG-20261117-29 review round 1 fix" (vauban-core 1648 tests, 13
  skipped; vauban-processor 388, 16 skipped), then `stack-l3-r1-b29-full.log`.
- **Verification**: red on the main code of "no erased-class fallback for an injection" (`stack-l3-red.log`,
  "BUG-20261117-29 red": a
  `DeploymentException` for `GadgetField`, nothing for `GenericFieldOfVetoed`, no error from the index; "red:
  vauban-processor": the build failed with `Ambiguous dependency`). Green: `stack-l3-green.log`, "BUG-20261117-29
  fix, round 2" (vauban-core 1634 tests, 13 skipped; vauban-processor 385, 16 skipped), and the whole reactor,
  `stack-l3-b29-full.log`.
- **Found by**: the review of fix round 2b of BUG-20261117-16.

## BUG-20261117-28 — A non-contextual injection point falls back on its erased class

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/vauban-bug-stack` (lane `stack/l3-container`)
- **Module**: `vauban-core` (`BeanInjector.injectBeanField`, `VaubanContainer.resolveParameter`,
  `DisposerInvoker.disposerArgument`, `EventDispatcher.beanArgument`)
- **Symptom**: a deployment with a producer of a raw `Foo`, `@Produces Foo foo()` with `Foo<T>` generic. A class
  that is no bean, `FooOfIntegerClient { @Inject Foo<Integer> foo; }`, injected through
  `BeanManager.getInjectionTargetFactory(...).createInjectionTarget(null).inject(...)`, gets the raw producer's
  `Foo`, and so does the initializer `@Inject void init(Foo<Integer> foo)` of a class `Unmanaged` creates. The raw
  `Foo` satisfies `Foo<Object>` only (CDI 4.1, 5.2.4), and the start refuses the same injection point on a bean.
- **Repro**: `AssignabilityRulesReproductionTest#aNonContextualInjectionPointIsNotSatisfiedByABeanTheStrictRuleRefuses`
  and `#aNonContextualInitializerParameterIsNotSatisfiedByABeanTheStrictRuleRefuses` in `vauban-core`, disabled; red
  when enabled, on b42eb9a6 (`vauban-b16-red.log`, "Red run 10", the field) and on fix round 2 of BUG-20261117-16
  ("Red run 14", both).
- **Cause** (proved): when `getBeans` of the parameterized type finds no bean, `BeanInjector.injectBeanField`
  falls back on `container.select(field.getType())` and `VaubanContainer.resolveParameter` on `select(paramType)`:
  the erased class, which the raw producer satisfies. The start's validation refuses such an injection point on a
  bean before the fallback can run; a class that is no bean is not validated at start, and creating its
  `InjectionTarget` does not refuse it either. `DisposerInvoker.disposerArgument` (~471) keeps the same fallback,
  `container.select(paramType)`, for a disposer's parameter; there the start's check of a disposer's parameters
  (BUG-20261117-25) refuses what `getBeans` does not satisfy before a disposer is called. The same family as
  BUG-20261117-25 and BUG-20261117-27.
- **Fix**: the three fallbacks are gone: an injection point `getBeans` finds no bean for, with its type and its
  qualifiers, throws `VaubanContainer.unsatisfied(required)`, `UnsatisfiedResolutionException: No bean found for type:
  ...Foo<java.lang.Integer>` (CDI 4.1, 5.2). The first version kept `select(erased)` for a required type that is a
  class; review round 1 showed it ignores the qualifiers, `@Inject @Red Knob` getting the `@Default Knob`, and looks
  `Object` up for a field of a type variable the class gives `Missing`, which no bean has. No brick of the workspace
  uses `Unmanaged` or an `InjectionTarget` (the reviewer's grep of every `*/main`). An
  observer's argument that `getBeans` misses goes to `resolveParameter` with the type the bean class gives it,
  `Holder<String>` for an inherited `Holder<X>`, no longer the declared one.
- **Who sees the change**: an `InjectionTarget` or `Unmanaged` instance whose injection point of a parameterized type
  no bean satisfies by the rules of CDI 4.1, 5.2.4 now fails when it is injected, with an
  `UnsatisfiedResolutionException` (wrapped in a `ContainerOperationException` for an initializer, as any failure of
  its call), where it got a bean of the erased class, a qualified injection point included. A bean's injection points
  are checked at start, as before.
- **Verification, review round 1**: `stack-l3-red.log`, "BUG-20261117-28 review round 1 red, run 3: the new test in
  assertAll": `AssignabilityRulesReproductionTest#aNonContextualInjectionPointNoBeanSatisfiesIsUnsatisfied`, nothing
  thrown for the `@Red` field, the field of `Missing` and the `@Red` initializer parameter (runs 1 and 2: a name
  clash, then the first assertion alone). Green: `stack-l3-green.log`, "BUG-20261117-28 review round 1 fix"
  (vauban-core 1635 tests, 14 skipped; vauban-processor 386, 16 skipped), then `stack-l3-r1-b28-full.log`.
- **Verification**: red on the main code of "start checks inherited observer params" (`stack-l3-red.log`,
  "BUG-20261117-28 red" with the tests as
  logged, "BUG-20261117-28 red, round 2" with `assertThrows`: nothing thrown, the raw producer's `Foo` injected).
  Green: the whole reactor, `stack-l3-b28-full.log`, "BUG-20261117-28 fix, round 2" (vauban-core 1624 tests, 14
  skipped; vauban-processor 384, 16 skipped).
- **Found by**: the review of fix round 1 of BUG-20261117-16.

## BUG-20261117-27 — An invoker's argument lookup resolves the erased class of its parameter

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/invoker-argument-lookup`
- **Module**: `vauban-core` (`VaubanInvoker.lookupBean`, `ExtensionMethodValidator.validateArgumentLookup`)
- **Symptom**: an invoker of `String read(Holder<String> holder)` with `withArgumentLookup(0)`, and a bean of
  `TextHolder implements Holder<String>`: the call throws `UnsatisfiedResolutionException: No bean found for type:
  …Holder`.
  The start checked the lookup otherwise again: it counted the beans whose class is assignable to the erased class,
  with no qualifier and no producer, so it refused a `@Fast Part` parameter with two `Part` beans as ambiguous, a
  `Holder<Integer>` parameter a producer satisfies as unsatisfied, and accepted a `Holder<Integer>` parameter with
  only a `Holder<String>` bean, which the call then failed, or with a producer and a class bean of
  `Holder<Integer>`, which the call found ambiguous.
- **Repro**: `VaubanInvokerLookupTest#anArgumentLookupOfAParameterizedTypeResolvesThatType` in `vauban-core`,
  disabled; red when enabled, on 3ce0b832 (`vauban-b16-red.log`, "Red run 8") and on fix round 1 of
  BUG-20261117-16 ("Red run 9"). Enabled, with the wildcard, qualifier and start tests of this fix, red on the main
  code of 131fb3e6, fix round 4 of BUG-20261117-16 (`vauban-b27-red.log`, "Red run 1", and again in "Red run 2b").
  Fix round 1: the ambiguous start test now holds a producer and a class bean, which the base accepted; its first
  form, two class beans of `Holder<String>`, the base refused too, by another message. Red on the main code of
  131fb3e6 ("Red run 6"); the qualifier, `@Singleton` and type variable tests red on the main code of 35035df4, the
  fix as first reviewed ("Red run 4b"). Fix round 2:
  `#twoInterceptedDependentArgumentsHaveAnInterceptorInstanceEach` red on the main code of aa3dc804, fix round 1
  ("Red run 7"), green on the main code of 131fb3e6 ("Base run 8"); the build side of the registered qualifiers,
  `BceCompileTimeTest#anInvokerLookupOfARegisteredQualifierPassesTheBuild` in `vauban-processor`, green on aa3dc804
  already ("Base run 10") and red on 35035df4 ("Red run 9").
- **Cause** (by reading): `lookupArgument` hands `lookupBean` the parameter's class, and `lookupBean` calls
  `cdi.select(type)`: a raw required type, which a parameterized bean type satisfies only with `Object` or unbounded
  type variables for arguments (CDI 4.1, 5.2.4). The generic type is at hand (`genParamTypes`), and the `Instance`
  and `Event` lookups already read it. The same family as BUG-20261117-25.
- **Fix**: an argument lookup is resolved as an injection point (CDI 4.1, "Configuring lookups"): `lookupArgument`
  hands `BeanManager.getInjectableReference` a `VaubanInjectionPoint` of the parameter, its declared type and the
  annotations `isQualifier` accepts, so `Instance`, `Event` and `BeanManager` are the built-in beans an injection
  gets. Each lookup obtains its bean with a creational context of its own, as an injection does, so two intercepted
  `@Dependent` arguments have an interceptor instance each (Jakarta Interceptors 2.1, 2.2; fix round 2: one context
  for the whole call cached one interceptor instance for both). The context of a `@Dependent` bean is released once
  the call is over, which destroys the `@Dependent` instances created for the lookups; that of any other bean is left
  alone, so a `@Singleton` the lookup creates keeps its `@Dependent` objects (fix round 1: the first fix gave every
  bean the call's context, and released it). Each lookup resolves its bean twice per call, for its context and in
  `getInjectableReference`, which takes no bean and gives the built-in beans and the `InjectionPoint` metadata as an
  injection does. The instance lookup is unchanged (BUG-20261117-38). The start and the build check each lookup with
  `BeanResolver.resolveInjectionPoint` on the same type and qualifiers (`@Default` when none): the qualifiers are the
  annotations `@Qualifier` declares and those the extensions registered, compared without the members they made
  non-binding, as the container does (fix round 1: the first fix read `@Qualifier` alone, so it refused a lookup of a
  registered qualifier the call served). The messages name the parameter by its declared type,
  `…Holder<java.lang.Integer>`. A lookup no bean satisfies throws `UnsatisfiedResolutionException` at the call, and is
  a `DeploymentException` at the start.
- **A changed verdict**: a parameter of a type variable, `take(T part)` with `T extends Part`, is now refused at
  build and at start, `Invoker argument lookup of a type variable: parameter type T at position 0 of method …`. The
  base counted the beans of its erasure, `Part`, accepted the deployment when one bean was one, and the call looked
  that erasure up. The required type is the declared type of the parameter (CDI 4.1, "Configuring lookups"), and a
  type variable is no legal required type (5.2.2); Weld refuses it at validation. The call, for an invoker built
  without the check, throws the `IllegalArgumentException` of `getBeans`.
- **Verification**: `VaubanInvokerLookupTest`, 22 tests, 3 skipped (BUG-20261117-38, BUG-20261117-44), green, the
  extension tests of `vauban-core` and the build-side test (`vauban-b27-dev.log`, "Dev run 10"). Whole build and
  TCKs: `vauban-b27-build.log`, `vauban-b27-tck.log`, "Fix round 2". Fix round 3 changed tests and `BUG.md` alone:
  the whole build again ("Fix round 3"), the TCKs not rerun, no `src/main` change.
- **Found by**: the review of fix round 1 of BUG-20261117-16.

## BUG-20261117-26 — The start does not check an inherited observer's parameters

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/vauban-bug-stack` (lane `stack/l3-container`)
- **Module**: `vauban-core` (`VaubanContainer.validateObserverParameters`, `observerMethodNamed`)
- **Symptom**: `IntegerHolderListenerBase` declares `void on(@Observes Pung pung, Holder<Integer> holder)`, and the
  bean `InheritedIntegerHolderListener` extends it; no bean is a `Holder<Integer>`. The start passes; the call fails
  at the first event.
- **Repro**: `AssignabilityRulesReproductionTest#anInheritedObserversUnsatisfiedParameterFailsTheStart` in
  `vauban-core`, disabled; red when enabled, on fix round 1 of BUG-20261117-16 (`vauban-b16-red.log`, "Red run 9":
  nothing thrown).
- **Cause** (proved): `observerMethodNamed` reads the methods the bean class declares, `getDeclaredMethods()`; an
  inherited observer is not there, and its parameters are not checked. When they are, the check has to resolve the
  type variables the subclass gives, `Holder<X>` as `Holder<String>`, as the call does
  (`EventDispatcher.resolveObserverParamType`): `requiredTypeOf` does not
  (`#anInheritedObserversParameterOfItsTypeVariableResolvesAsTheSubclassGivesIt` passes today because the check
  does not run). Unchecked as well: `Sub<T> extends VetoedBase<T>`, where `VetoedBase<T>` declares
  `void on(@Observes E e, T t)`. The inherited observer keeps `T`, an injection point of a type variable on a class
  that may be a bean (CDI 4.1, 5.2.3), but the definition validation reads the class that declares the observer,
  which is none (BUG-20261117-16 scopes the check so), and this check does not see the inherited observer.
- **Fix**: `VaubanContainer.validateObserverParameters` finds the observer the bean class declares or, failing that,
  the one its nearest superclass declares, and reads each parameter as the bean class gives its superclasses' type
  variables (`ManagedBean.buildTypeVariableMapping` and `resolveType`, what the call reads). A parameter the bean class
  leaves a type variable is a definition error, `Injection point has type variable type T:
  <bean class>.on observer param 1`; any other is resolved as an injection point, and the messages name the resolved
  type, `Holder<java.lang.Integer>`.
- **Who sees the change**: a deployment whose bean inherits an observer with an unsatisfied or ambiguous parameter
  now fails at start with a `DeploymentException`, extensions run or frozen, where the first event failed; one whose
  bean passes its own type variable on to an inherited observer's parameter fails with a `DefinitionException`. The
  processor checks no observer parameter for a bean, inherited or not, and does not warn of either.
- **Verification**: red on the main code of 833e994e (`stack-l3-red.log`, "BUG-20261117-26 red":
  `#anInheritedObserversUnsatisfiedParameterFailsTheStart` and
  `#anInheritedObserversParameterItsBeanClassLeavesATypeVariableIsADefinitionError` threw nothing; "BUG-20261117-26
  red: vauban-processor":
  `VetoedInterceptorCompileTimeTest#anInheritedObserversUnsatisfiedParameterFailsTheStartRunAndFrozen` started). Green:
  `stack-l3-green.log`, "BUG-20261117-26 fix" (vauban-core 1624 tests, 16 skipped;
  vauban-processor 384, 16 skipped), `#anInheritedObserversParameterOfItsTypeVariableResolvesAsTheSubclassGivesIt`
  still green now that the check runs, and the whole reactor, `stack-l3-b26-full.log`.
- **Found by**: the review of fix round 1 of BUG-20261117-16, which suspected `requiredTypeOf`; the repro showed the
  check never reaches it.

## BUG-20261117-25 — An observer's or a disposer's parameter is resolved by its erased class

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/raw-type-assignability`
- **Module**: `vauban-core` (`VaubanContainer.checkResolvable`, `DisposerInvoker.validateDisposerParameter` and
  `disposerArgument`, `EventDispatcher.beanArgument`)
- **Symptom**: `void on(@Observes Ping ping, Holder<String> holder)` with a bean of `TextHolder implements
  Holder<String>`: the start fails, `Observer method …ParameterizedListener.on(): unsatisfied dependency for parameter
  arg2 of type …Holder`. Under the rule of BUG-20261117-16, a parameter `Counter<Integer>` with a bean of
  `Counter<T extends Number>` fails the start the same way, firing the event throws `IllegalArgumentException: Type
  class …Counter is not a bean type of class …Counter`, and a disposer `dispose(@Disposes Ticket t, Counter<Integer>
  c)` is not called, without a word: `callDisposer` suppresses what its arguments throw.
- **Repro**: `AssignabilityRulesReproductionTest#anObserversAndADisposersParametersOfAParameterizedTypeResolveAsInjectionPoints`
  in `vauban-core`. Red on the main code of 154c46fc (`vauban-b16-red.log`, "Red run 6": the observer's `Holder`), on
  the strict rule ("Red run 5", base 6e2f2a1b: the observer's `Counter`), and, the check and the observer fixed, on the
  disposer ("Red run 7", base af47d553: the disposer received nothing).
- **Cause**: the observer check and the disposer check fall back on the parameter's erased class,
  `param.getType()`, for the required type; the observer call and the disposer call find the bean by the
  parameterized type, or the class, and ask `getReference` with the class, which a bean of `Counter<T extends
  Number>` or `Holder<String>` does not have among its types.
- **Fix**: `VaubanContainer.requiredTypeOf(Parameter)` gives a parameterized parameter its parameterized type, and
  any other its class, as before; both checks use it, and name the parameter by that type when it is unsatisfied
  or ambiguous, `…Holder<java.lang.Integer>`. The observer call asks `getReference` with the type it found the bean
  by; the disposer call finds the bean by the parameter's type as declared and asks with it. A parameter of a type
  variable of the class, `T extends Gadget`, made `getBeans` throw, which `callDisposer` suppressed: it is an
  injection point of a type variable, a definition error the start reports since fix round 2 of BUG-20261117-16
  (CDI 4.1, 5.2.3; fix round 1 looked it up by its erased class, which the specification does not allow).
- **Verification**: the test green (`vauban-b16-dev.log`, "Dev run 5"). Fix round 1: the messages
  (`AssignabilityRulesReproductionTest#anUnsatisfiedObserverParameterIsNamedByItsParameterizedType`,
  `DisposerMatchingTest#undescribedParameterizedParameter`) red on 3ce0b832 (`vauban-b16-red.log`, "Red run 8").
  Fix round 2: the ambiguous messages (`#anAmbiguousObserverParameterIsNamedByItsParameterizedType`,
  `#anAmbiguousDisposerParameterIsNamedByItsParameterizedType`) red on the main code of 904a8b94 ("Red run 12"),
  and the type variable under BUG-20261117-16. Whole build and TCKs under BUG-20261117-16.
- **Found by**: the work on BUG-20261117-16, listing what each caller of `BeanResolver` does under the strict rule;
  the type variable and the messages by the review of its fix round 1.

## BUG-20261117-24 — The resolution of an injection point reads a bean's type variable otherwise than `getBeans`

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/raw-type-assignability`
- **Module**: `vauban-core` (`AssignabilityRules.wildcardAdmits`, `actualTypeAdmits`, `isRawEquivalentArgument`)
- **Symptom**: for a bean type with a type variable among its type arguments, the resolution of an injection point,
  at build and at start, answered otherwise than `BeanManager.getBeans` and CDI 4.1, 5.2.4:
  - `GenericDao<T> implements Dao<T>` did not satisfy `@Inject Dao<? extends Integer>`: the upper bound of `T`,
    `Object`, is assignable from the wildcard's (rule db), which the resolution did not check, and a type variable
    with no bound failed against any upper bound of a wildcard, `? extends Object` included;
  - `Sorted<T extends Comparable<T>> implements Holder<T>` did not satisfy `@Inject Holder<Integer>` (rule e);
  - the same bean satisfied a raw `@Inject Holder`, which `getBeans(Holder.class)` does not list: a type variable
    bounded by a parameterized type counted as unbounded (rule a).
  A bean class itself was named raw (BUG-20261117-16), so these showed through the types above it only. Typed with
  its type variables, the class itself meets them: the CDI TCK's
  `ParameterizedTypesInjectionToParameterizedWithWildcardTest` injects `Dao<? extends Integer, ?>` into a bean of
  `Dao<T1, T2>`.
- **Repro**: `AssignabilityRulesReproductionTest#aBeanTypeVariableSatisfiesAWildcardWhoseUpperBoundIsBelowItsOwn`,
  `#aBeanTypeVariableWithAParameterizedBoundSatisfiesAnActualTypeWithinIt` and
  `#aBeanTypeVariableWithAParameterizedBoundDoesNotSatisfyTheRawType` in `vauban-core`, each checking `getBeans` too,
  and the cases of `AssignabilityRulesEdgeCasesTest` marked BUG-20261117-24. Red on the main code of 154c46fc
  (`vauban-b16-red.log`, "Red run 1b"); the first two and six edge cases still red on the strict rule alone of
  BUG-20261117-16 ("Red run 2", base e727c02c), whose predicate fixed the third.
- **Cause**: `wildcardAdmits` checked a bound of the type variable assignable to the wildcard's upper bound only, and
  fell through to `isAssignable(T, upper)`, always false, for a type variable with no bound (the index leaves out the
  implicit `Object`). `actualTypeAdmits` asked `isAssignable(Integer, Comparable<T>)`, which compared the class names.
  `isRawEquivalentArgument` refused a class bound only.
- **Fix**: `wildcardAdmitsTypeVariable` checks the upper bound of the type variable assignable to or from the
  wildcard's upper bound, and assignable from its lower bound; `upperBounds` gives a type variable with no bound
  `Object`, and a type variable bound its own bounds, as Weld's `getUppermostTypeVariableBounds` does.
  `isAssignableToBound` takes a class within a parameterized bound, and a parameterized type within a class bound, by
  their classes, as `getBeans`'s `isTypeAssignableTo` did. `isObjectOrUnboundedTypeVariable` (BUG-20261117-16)
  counts any bound but `Object` a bound, since fix round 1 of BUG-20261117-16. One case of
  `AssignabilityRulesEdgeCasesTest`
  asserted the old answer: `List<T>` against `List<? extends Number>`, now assignable (rule db).
- **Verification**: the tests above green with the fix (`vauban-b16-dev.log`, "Dev run 1"), and the whole build and
  the TCKs under BUG-20261117-16.
- **Found by**: the work on BUG-20261117-16, which needed them before typing a generic bean class with its type
  variables.

## BUG-20261117-23 — The processor in a layer above vauban-core's cannot use the packages exported to it alone

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/processor-bean-supertypes`
- **Module**: `vauban-core` (the qualified exports of `io.vidocq.vauban.core.codegen` and `io.vidocq.vauban.core.proxy`
  to `io.vidocq.vauban.processor`), `vauban-processor`
- **Symptom**: javac given `vauban-processor` on `--processor-module-path`, in a JVM whose module path holds
  `vauban-core`: the processor's first use of either package throws `IllegalAccessError` ("module io.vidocq.vauban.core
  does not export io.vidocq.vauban.core.codegen to module io.vidocq.vauban.processor"). On main (528c42dd), a
  compilation with a normal-scoped bean failed (`ElementTypeView` implements `core.codegen.TypeView`), and one with an
  intercepted method that carries an annotation (`BeanMembers`); a `@Dependent` bean compiled. With BUG-20261117-03,
  every compilation failed (`CompiledSupertypes`, bound in `VaubanProcessor.process`): Foy's
  `FoyWebExtensionTest.processorModulePathDiscoversTheExtension`, in the `pr-validate` of vauban#325 (downstream
  `Vidocq/foy`).
- **Repro**: `ProcessorAboveCoreLayerTest` in `vauban-processor`, enabled in every build: it defines the processor's
  module in a layer above the boot layer, which holds `vauban-core`, as javac does, and compiles a `@Dependent` bean, a
  normal-scoped bean and an intercepted method. Red on 154c46fc: the three, on `CompiledSupertypes`
  (`vauban-b03-red.log`, "Fix round 5 red run 24"); on the main code of 528c42dd: the normal-scoped bean (`TypeView`)
  and the intercepted method (`BeanMembers`), the `@Dependent` bean compiles (run 26; run 25, the binding on the
  class, reached no `BeanMembers`).
- **Cause**: a qualified export reaches the modules of the exporting module's layer, and of the layers below it, that
  the layer holds when it is defined. javac defines the modules of `--processor-module-path` in a new layer above the
  boot layer, and the processor's module reads `vauban-core` from the boot layer: no qualified export reaches it.
- **The processor's uses** of the two packages, by path:
  - every compilation (this branch only): `CompiledSupertypes` (`VaubanProcessor`, `ParameterizedSupertypes`);
  - a client proxy, of a normal-scoped bean or producer: `ClientProxyEmitter`, `ClientProxyShape`,
    `ProducerProxyEligibility` (`ClientProxyArtefacts`); `TypeView`, `ClientProxyShape` (`ElementTypeView`);
    `ClientProxyShape`, `ProxyMethodShape`, `ForwardedMethods` (`ClientProxyShapeFromElements`,
    `ClientProxySourceRenderer`, `InterfaceProxySourceRenderer`);
  - an intercepted bean: `BeanMembers` (a method that carries an annotation) and `TypeMembers` (a default method),
    `InterceptedShapeFromElements`.
- **Who hits it**: a JVM whose module path holds `vauban-core` and that runs javac in process with `vauban-processor`
  on `--processor-module-path`: Foy's tests (`TestApps.compile`). No application build, read on Vidocq 4a78d777:
  applications compile with `annotationProcessorPaths` (`vidocq-runtime-parent/pom.xml:82-89`), a `-processorpath` in
  Maven's JVM, whose boot layer holds no Vauban module; `vidocq:dev` recompiles with an out-of-process
  `mvn process-classes` (`RecompileRunner.java:70-71`); `DependencyCodegen` runs javac in process
  (`DependencyCodegen.java:124`, `:273-275`) with processors of a `URLClassLoader` over the platform loader
  (`AttributedProcessors.java:85`, `:98`), where `vauban-core` and the processor share one unnamed module;
  `WarJspPages` runs javac without a processor (`WarJspPages.java:116`, `:334`). The only `--processor-module-path`
  there is a test that forks `javac` for Peano's processor (`PeanoAccessorRegistrationTest.java:64`).
- **Fix**: `io.vidocq.vauban.core.ProcessorAccess.grantTo(Module)`, for `vauban-processor` only: from `vauban-core`,
  which alone may add its exports (`Module.addExports`), it exports to the named module `io.vidocq.vauban.processor`
  each package vauban-core's module descriptor exports to it by name, both packages today, so a later
  `exports ... to io.vidocq.vauban.processor` needs no other change; it refuses a named module of another name, and
  changes nothing for an unnamed one. `VaubanProcessor.init` calls it before any use. The qualified exports stay,
  and the module descriptor names the hook. One public class in an exported package, on the precedent of
  `IndexLookup.describe` (BUG-20261117-02).
- **Not covered**: a processor on a class path whose loader takes `vauban-core` from a layer where it is a named module
  (`-processorpath` in a JVM whose module path holds `vauban-core` and not `vauban-processor`): the processor is in an
  unnamed module, which no qualified export names and the hook leaves alone. By reading, not run, it fails as above,
  on every compilation since BUG-20261117-03. No consumer known: Foy isolates the loader of its class-path case for
  that reason (`TestApps.isolatedLoader`), and Maven's JVM holds no Vauban module. The other qualified export,
  `io.vidocq.vauban.core.access` to `io.vidocq.heisenberg.cdi.vauban`, has the same limit, which the Vidocq runtime
  never reaches (read on Vidocq 4a78d777): its layer rule keeps every `io.vidocq.` module, Heisenberg's among them,
  with `vauban-core` in the parent layer (`LayerSplit.java:61`, `:207`, `:310`); only an explicit
  `-Dvidocq.app.modules` naming it moves it alone (`VidocqAppLayer.java:256`, `:281`), and Heisenberg then catches the
  `LinkageError` (`VaubanLookupSource.java:48`).
- **Verification**: green, `ProcessorAboveCoreLayerTest` 3/3 in `vauban-processor` and `ProcessorAccessTest` 3/3 in
  `vauban-core` (`vauban-b03-dev.log`, "Fix round 5 dev run 19"; the final `ProcessorAccessTest` in dev run 21, beside
  the tests of `core.codegen` and `core.proxy`, after the probe of dev run 20, and with the packages read from the
  descriptor in "Fix round 6 dev run 22"), and in the full build of the commit (`vauban-b03-build.log`);
  Foy's `FoyWebExtensionTest`, 11/11, on Foy's origin/main 412ca8b and on its checkout 6c873b1, with Vauban installed
  from the commit (`vauban-b03-build.log`, "Foy FoyWebExtensionTest on").
- **Found by**: `pr-validate` of vauban#325, downstream `Vidocq/foy`.

## BUG-20261117-22 — The language model gives a class's supertypes raw

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/langmodel-generic-supertypes`
- **Module**: `vauban-indexer` (`model.ClassInfo`, `scanner.ClassFileScanner`, `scanner.ClassTypeParameters`),
  `vauban-processor` (`apt.ElementScanner`), `vauban-core` (`langmodel.declarations.VaubanClassInfo`,
  `container.ClassChecksums`)
- **Symptom**: `ClassInfo.superClass()` and `superInterfaces()` answer a `CLASS`, raw, for every supertype, in the
  processor, at start and frozen. What Mansart saw, through `types.ofClass(name).declaration()` in an `@Synthesis`
  method, on snapshot 455067f5:
  - `RoutedNoteRepository extends BasicRepository<RoutedNote, Long>` gives `superInterfaces=[CLASS:…BasicRepository]`
    where CDI expects `PARAMETERIZED_TYPE` with `[RoutedNote, Long]`;
  - `RoutedNoteArchive extends CrudRepository<ArchivedNote, Long>, Labelled<List<String>>` gives two `CLASS` entries.

  The language model (`jakarta.enterprise.lang-model` 4.1.0, `ClassInfo`) asks for types, which carry their type
  arguments: `superClass()` "Returns the direct superclass type of this class. Returns `null` if this class does not
  have a superclass; that is, if this class is `java.lang.Object` or an interface"; `superInterfaces()` "Returns a list
  of direct superinterface types of this class. Returns an empty list if this class has no direct superinterface."
  Mansart's extension gives the synthetic bean of each repository interface the parameterized supertypes of the
  interface, so that `@Inject BasicRepository<RoutedNote, Long>` resolves: since BUG-20261117-16 a raw bean type no
  longer satisfies a parameterized injection point (CDI 4.1, 5.2.4). **Mansart carries a documented reflective
  fallback until this fix (MANSART-068, mansart#109).**
- **Repro**: in Vauban alone, `interface Probe extends Supplier<String>`, and an `@Synthesis` method that records
  `types.ofClass("…Probe").declaration().superInterfaces()`: one `CLASS java.util.function.Supplier`, where the
  language model wants one `PARAMETERIZED_TYPE`, `Supplier<String>`. The tests:
  - `BceClassSupertypesTest#supertypesKeepTheirTypeArguments` in `vauban-core`, at start: `Probe`, a superclass with
    arguments (`Extending extends Base<List<String>>`), Mansart's shape with local types (`Archive extends
    Crud<Note, Long>, Labelled<List<String>>`, in that order), a type variable (`Supplying<T> implements Supplier<T>`,
    a `TYPE_VARIABLE` named `T`), wildcards inside an argument (`Holder<Map<? extends Number, ? super Integer>>`:
    Java allows no wildcard as an argument of a supertype itself, JLS 8.1.4), an array argument
    (`Labelled<int[][]>`), a raw supertype (`Raw extends ArrayList`, a `CLASS`), `Object` (`Plain`), an enum
    (`Enum<Level>`), and `Supplying` again through `ParameterizedType.genericClass().declaration()`;
  - `VetoedInterceptorCompileTimeTest#anExtensionSeesTheGenericSupertypesOfAClassInTheProcessorAtStartAndFrozen` in
    `vauban-processor`, the same cases three ways (the processor, the container at start, the build that freezes the
    extensions), through `seenThreeWays`; the interfaces and the enum come in through `ScannedClasses`, as no
    annotation brings them into the index;
  - `ClassFileScannerTest$GenericSupertypes`, `ClassInfoTest` (`vauban-indexer`), the generic supertypes of
    `ElementScannerTypeParametersTest` (the processor's scan equals the bytecode scan) and `ClassChecksumsTest`.

  Red on 455067f5: `vauban-b22-red.log`, "red run 3" (the first expectations) and "red run 4" (the final ones, on the
  main code of 455067f5 with the fix set aside): every supertype is a `CLASS` in the core test and in the processor, at
  start and frozen. Runs 1 and 2 of that log did not reach the tests (a `test` phase the reactor cannot build, then a
  test that did not compile). The other tests use the new model and do not compile on 455067f5. After the review, on
  0360a032 ("Fix round 1 red run 6"): `ClassInfoTest#aGenericSuperclassWithoutASuperclassIsRefused` is red, nothing
  thrown. `ClassFileScannerTest$GenericSupertypes#noSuperclassWithASignature` and
  `ClassChecksumsTest#aClassWithRawSupertypesKeepsItsChecksum` pass there (red runs 7 and 6 of the round): they pin
  what does not change, they are not red.
- **Cause**: the index model kept the names of a class's supertypes only. `ClassFileScanner` read the class's
  `Signature` attribute for its type parameters only (VAU-BCE-007), `ElementScanner` the compiler's
  `TypeElement.getSuperclass()` and `getInterfaces()` for their names only, and `VaubanClassInfo.superClass()` and
  `superInterfaces()` wrapped each name in a `ClassType`.
- **Fix**: `indexer.model.ClassInfo` gains two components, `genericSuperclass` and `genericInterfaces`, the supertypes
  as the class declares them, in the index's types: the interfaces as `Class.getGenericInterfaces()` gives them, the
  superclass that `superName` names (for an interface, the `java.lang.Object` its class file names, where
  `Class.getGenericSuperclass()` gives `null`). Additive: every earlier constructor stays and gives the raw
  supertypes, `ClassType`s of `superName` and `interfaces`, which is also what `null` gives to the new canonical
  constructor. Each new component says how the class declares a supertype the class file names, so a list of generic
  interfaces that does not line up with `interfaces` is refused, and so is a generic superclass without a
  `superName`. `ClassFileScanner` reads them from the class signature through the
  Class-File API (`ClassTypeParameters.supertypes`), its type variables resolved against the class's type parameters
  as for the type parameters and the members, the signature parsed once for all three; no signature, one that does
  not parse, one that names another number of interfaces than the class file, or a class file without a superclass
  leaves them raw. `ElementScanner` reads them from `getSuperclass()` and
  `getInterfaces()`, its type variables through the same `TypeParameterReader` as the type parameters, so the
  processor and the bytecode scan give the same model (`ElementScannerTypeParametersTest`). `VaubanClassInfo`
  maps them: `superClass()` and `superInterfaces()`. `ClassType.declaration()` and
  `ParameterizedType.genericClass().declaration()` already went through the same code (`VaubanClassType.declaration()`,
  then `VaubanClassInfo`), and so do `BeanInfo.declaringClass()` and `ClassConfig.info()`.
  `superClassDeclaration()` and `superInterfacesDeclarations()` do not change, nor do the bean types, the resolution
  or the assignability: nothing but the language model and the fingerprint reads the new components.
- **Persistence**: the index is not persisted. The container indexes the class files of the bean classes at each
  start (`ClassFileScanner`), the processor indexes the compiler's elements at each compilation, `vauban:generate`
  the class files; no jar, frozen record or generated class holds a `ClassInfo`. What the frozen record keeps is the
  fingerprint of the deployment, a checksum of each bean class's model (`ClassChecksums`,
  `META-INF/vauban-frozen-extensions.properties`). The generic supertypes count there only where they carry type
  arguments: a class whose supertypes are raw keeps the checksum it had, so the record a build by an earlier Vauban
  wrote stays valid for it (`ClassChecksumsTest#aClassWithRawSupertypesKeepsItsChecksum` pins `374142584L`, what
  `ClassChecksums.ofModel` gave its model on 455067f5: `vauban-b22-dev.log`, "Fix round 1, golden checksum"); a class
  with a parameterized supertype gets another checksum, the start refuses that
  record as written for another deployment and runs the extensions, as for any refused record (a debug note), and they
  see the generic supertypes. An application built with an earlier Vauban starts on this one either way.
- **Not covered**: a class the index does not hold, as jakarta.data's `CrudRepository` in a dependency jar, or
  `java.util.function.Supplier` itself, gets an empty stub from `VaubanClassType.declaration()` (VAU-BCE-001):
  superclass `Object`, no interfaces, no type parameters. The processor describes such a class already when the
  compilation sees it, on its class path too (`VaubanProcessor.unindexedResolver`, VAU-BCE-008), now with its generic
  supertypes: for a class such as `CrudRepository`, the processor and the start already disagree. That is
  BUG-20261117-31's to fix, separate. An interface's `superClass()` was `Object` at start and `null` in the
  processor: BUG-20261117-32, fixed with this one. A wildcard had no bound at start and frozen, and `? extends Object`
  had `Object` in the processor: BUG-20261117-34, fixed with this one. An interceptor's `@Intercepted Bean<?>` is
  refused, before this fix as after: BUG-20261117-41, open; past that check, the container provides no bean metadata
  bean: BUG-20261117-47, open. A type variable reached through a supertype has no bound: BUG-20261117-33, open.
- **Verification**: red as above; green, the same tests (`vauban-b22-dev.log`, "dev run 1" for the core and the
  three-way tests, "dev run 2" and "dev run 3" for the others; "Fix round 1 dev run 5" after the review). The full
  build of the commit, `vauban-b22-build.log`, "Fix round 4 full build"; the CDI Lite TCK and the AtInject TCK,
  `vauban-b22-tck.log`, "Fix round 3 TCK", on the same main code (round 4 changed tests and text only).
- **Found by**: Mansart's probe of its repositories' supertypes (MANSART-068, mansart#109).

## BUG-20261117-21 — A producer of an inner class of a generic class has the raw types above it in its descriptor

- **Date**: 2026-11-17
- **Status**: FIXED
- **Module**: `vauban-indexer` (`TypeInfo.ParameterizedType`), as `vauban-core` (`BeanDiscovery.computeProducerTypes`)
  and `vauban-processor` (`ParameterizedSupertypes`) read it
- **Symptom**: `@Produces Outer<String>.Inner<Integer> inner()`, where `Outer<O>` declares
  `class Inner<I> implements Holder<O>`, all compiled by the same compilation: the producer's descriptor, which the
  resolution of an injection point and an extension's `BeanInfo.types()` read, holds the raw `Holder`, at build, at
  start and frozen. `Bean.getTypes()`, read from the producer method, holds `Holder<String>`. A raw bean type satisfied
  an injection point of any parameterization: `@Inject Holder<String>` resolved to it, and so did
  `@Inject Holder<Integer>`. Since BUG-20261117-16 neither does, and `@Inject Holder<String>` is unsatisfied at build
  and at start, where `getBeans` finds the producer.
- **Repro**: `VetoedInterceptorCompileTimeTest#aProducerOfAnInnerClassOfAGenericClassHasTheTypesAboveIt` in
  `vauban-processor`, disabled; red when enabled: the extension sees the raw `Holder` in the processor, at start and
  frozen, and `Bean.getTypes()` has `Holder<String>` (`vauban-b03-red.log`, "Fix round 3 red run 18", on the round 3
  tree). `#aProducerOfAnInnerClassOfAGenericClassHasTheSameTypesAtBuildAsAtStart` checks that the build and the start
  agree.
- **Cause**: the index names the produced type `Outer$Inner<Integer>`, without `Outer<String>`, the type that encloses
  it. At build, the compiler's `Types.getDeclaredType` refuses a member class of a generic class without its enclosing
  type (`IllegalArgumentException`, caught in `ParameterizedSupertypes.above`), and the bean discovery falls back to
  the raw supertypes the index names. At start, reflection on `Outer$Inner<Integer>` without an owner type leaves the
  `O` of `Holder<O>` unresolved. Until fix round 3 the start kept `Holder<O>`, a third answer, at start and frozen
  (`vauban-b03-red.log`, "Fix round 4 red run 22", on the main code of 03db99af); a type variable of a class above
  the produced type now gives the raw class (BUG-20261117-14), so the build and the start agree.
- **Same cause, two more shapes** (by reading, the review of f8dc8292; not tested): neither the descriptor nor
  `Bean.getTypes()` reads the type that encloses the produced type, `getOwnerType()` in reflection,
  `getEnclosingType()` in the compiler.
  - `@Produces <F> Outer<F>.Inner<Integer>`, with `Inner<I> implements Holder<O>`: `ManagedBean.typesOfProducedType`
    collects the producer's type variables from the type arguments of the produced type only (`typeVariablesOf`),
    so it misses the `F` in the enclosing `Outer<F>`, and `filterIllegalBeanTypes` makes `Holder<F>` raw. The
    descriptor has the raw `Holder` too (`BeanDiscovery.hasForeignTypeVariable`, `ParameterizedSupertypes
    .hasForeignTypeVariable`). Both give the raw `Holder`, where CDI 4.1, 3.3 wants `Holder<F>`.
  - `@Produces Outer<String>.Inner<Integer>`, with `Inner<I> implements Holder<List<? extends O>>`:
    `Bean.getTypes()` resolves `Holder<List<? extends String>>` and drops it, as a type with a wildcard type argument
    should be. The descriptor, without the enclosing type, has the raw `Holder`, at build, at start and frozen, which
    satisfied an injection point of any `Holder<X>` until BUG-20261117-16, and now satisfies `Holder<Object>` only.
- **Cause, proved**: red on 67964972 with the `@Disabled` lifted (`stack-l2d-red.log`, "RED BUG-20261117-21 on
  67964972"): the raw `Holder` in the processor, at start and frozen, where `Bean.getTypes()` has `Holder<String>`.
- **Fix**: `TypeInfo.ParameterizedType` gains `owner`, the parameterized type that encloses it, `null` otherwise; a
  two-argument constructor keeps the old calls. The owner is part of the type, the record's own `equals` and
  `hashCode`, as reflection compares it (round 1: it was left out of equality at first). Every reader of a type gives
  the owner of a member type, so one type is one value: the processor's `ParameterizedSupertypes.declaredTypeInfo`
  reads `getEnclosingType()`, `ObserverInjectionPoints.given` substitutes into the owner, `TypeInfoUtils` reads
  `getOwnerType()`. `AssignabilityRules` compares the owners where both sides have one, and ignores them where one
  side has none, a type from the language model, which has no owner. The producer's type variables include those of
  the owner, `F` of `<F> Outer<F>.Inner<Integer>`, in the descriptor (`BeanDiscovery.addTypeVariableNames`) and in
  `Bean.getTypes()` (`ManagedBean.addTypeVariables`). The bytecode scan reads the owner from
  the signature's outer type (`ClassTypeParameters`), the processor from `DeclaredType.getEnclosingType()`, and
  `BeanDiscovery.reflectTypeToTypeInfo` from `getOwnerType()`, each where the enclosing type is parameterized. At
  start, the produced type rebuilt for `TypeHierarchyResolver` gives the owner, which maps `O` to `String`; at build,
  `ParameterizedSupertypes` builds the compiler's type within the enclosing one
  (`Types.getDeclaredType(containing, ...)`), and a type variable an enclosing argument stands for is no foreign one.
  `ClassChecksums` counts the owner where there is one. Every record pattern on the type names three components.
- **Verification**: the repro green the three ways and with the container's `Bean.getTypes()`; the earlier
  `...HasTheSameTypesAtBuildAsAtStart`, which pinned the raw `Holder` both ways, now checks the wildcard shape, which
  has no `Holder` at all, as `Bean.getTypes()` says (`stack-l2d-dev21.log`, "BUG-21 (7)"); `ClassFileScannerTest
  .EnclosingTypes` and `ClassChecksumsTest.theEnclosingTypeOfAParameterizedTypeCounts`; the whole reactor, indexer
  140, core 1754 (11 skipped), processor 444 (13 skipped), maven-plugin 172, architecture 56 (`stack-l2d-dev21.log`,
  "BUG-21 (9)").
- **Round 1 red**: on dc80d927 with the owner in `TypeInfo`'s equality alone (`stack-l2d-red.log`, "RED round 1 on
  dc80d927 with the owner in TypeInfo equality alone"): `OwnedTypesAtBuildAndStartTest`, the bean types of
  `Items extends ArrayList<Box<String>.Item<Integer>>`, has `Box<String>.Item<Integer>` at start and `Box$Item<Integer>`
  without owner at build. On dc80d927 itself ("RED round 1 on dc80d927 (new tests, main of dc80d927)"): the owner
  takes no part in equality (`ClassFileScannerTest.theOwnerIsPartOfTheType`), `Outer<String>.Inner<Integer>` is
  assignable to `Outer<Long>.Inner<Integer>` (`AssignabilityRulesTest.theOwnersMustAgree`), and the producer of
  `<F> Outer<F>.Inner<Integer>` has the raw `Holder` the three ways and in `Bean.getTypes()`
  (`aProducerOfAnInnerClassKeepsItsTypeVariableFromTheEnclosingType`).
- **Round 1 verification**: those tests green (`stack-l2d-r1.log`, "Round 1 (2)" and "(3)"); the full build and the
  CDI TCK in `stack-l2d-r1.log`.
- **What stays**: an inner class that declares no type parameter, `Outer<String>.Plain`, was a `ClassType`, which has
  no owner, until BUG-20261118-47. Vidocq's dev console (`CdiInventory.type`) names two components in its record pattern
  and needs `, _` to compile against this Vauban (a one-line companion). The language model has no owner:
  `LangModelTypeMapper` and `VaubanMethodInfo`'s receiver give none, and `TypeAnnotationReader.receiver` gives the
  receiver of an inner class's methods without its enclosing type, which only the language model reads.
  `ObservedTypeNotation` dropped the owner silently: its types came from the language model, but `write` takes any
  index type. Since round 2 it writes the owner as `Type.getTypeName()` does, `p.Outer<java.lang.String>$Inner<I>`,
  and reads it back, and it refuses an owner that does not enclose the type. The notation is additive: earlier
  versions never wrote a `$` after a `>`, and every earlier record reads the same.
- **Round 3**: `AnnotationArtefacts.sourceName`, the source the processor writes for an annotation member's type,
  dropped the owner, `p.Outer.Inner<Integer>`, which javac rejects; it writes `p.Outer<String>.Inner<Integer>`.
  `AnnotationLiteralRenderingTest#anInnerClassOfAGenericClassIsWrittenAfterItsOwner`, red (`stack-l2d-red.log`, "RED
  round 3 on 8c3f1926 (+fixups)": `Class<? extends ...OwnedClassMember.Outer.Inner<java.lang.Integer>>`), green in
  `stack-l2d-r3.log`, "Round 3 (1)". The fix is in the commit of BUG-20261118-47, which last changed that line.
- **Round 2 red**: `SyntheticMetadataWriteTest#aMemberTypeKeepsItsOwner` and
  `#anOwnerThatDoesNotEncloseTheTypeHasNoNotation`, red on a278b032 (`stack-l2d-red.log`, "RED round 2 on a278b032
  (new tests, main of a278b032)"); green with the fix (`stack-l2d-r2.log`, "Round 2 (1)", and the reactor in "Round 2
  (3)").

## BUG-20261117-20 — Under a Turkish default locale, a primitive type no longer matches the class named after it

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/processor-bean-supertypes`
- **Module**: `vauban-core` (`AssignabilityRules.namesPrimitive` and `primitiveName`, `VaubanPrimitiveType.name`)
- **Symptom**: with a Turkish default locale, `PrimitiveType(INT)` and `ClassType("int")` are not assignable either
  way, nor an array of `int` to an array of `ClassType("int")`, and `VaubanPrimitiveType.name()`, the name an
  extension reads, is `ınt`, with a dotless i. Reflection names the component of an array of primitives as a class,
  where the index and the compiler give a primitive: the bean types of BUG-20261117-14 match across both forms.
- **Repro**: `AssignabilityRulesEdgeCasesTest#aPrimitiveTypeMatchesTheClassTypeNamedAfterItUnderATurkishDefaultLocale`
  in `vauban-core`, which sets `Locale.forLanguageTag("tr")` and restores the default after it: red on 03db99af, four
  failures (`vauban-b03-red.log`, "Fix round 3 red run 17").
- **Cause**: `String.toLowerCase()` lowers in the default locale, and the Turkish one lowers `I` to `ı`.
- **Fix**: `toLowerCase(Locale.ROOT)` at the three places. `ParameterizedSupertypes` and `TypeRef.sourceName` already
  lowered that way. The other case conversions without a locale in `vauban-core` and `vauban-processor`: one,
  `TypeRef.toString` (`TypeRef.java:333`), which names a type for a message, on no matching path; left as it is.
- **Verification**: green with the fix (`vauban-b03-dev.log`, "Fix round 3 dev run 13", the whole `vauban-core`
  suite).
- **Found by**: the review of fix round 3 of BUG-20261117-14 (U2).

## BUG-20261117-19 — The descriptor of a `@Typed` producer names its types raw

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/raw-type-assignability`
- **Module**: `vauban-core` (`BeanDiscovery.computeProducerTypesWithTyped`)
- **Symptom**: `@Produces @Typed(Repo.class) Box<String> strings()` and `@Produces @Typed(Repo.class) Box<Integer>
  integers()`, where `Box<T> implements Repo<T>`: an `@Inject Repo<String>` is ambiguous at build (`Ambiguous
  dependency: field Reader.injected … Matching beans: [vi.types.BoxFactory, vi.types.BoxFactory]`) and, by the review,
  at start. `Bean.getTypes()` holds `Repo<String>` and `Repo<Integer>`; an extension's `BeanInfo.types()` the raw
  `Repo`.
- **Repro**: `VetoedInterceptorCompileTimeTest#anInjectionPointResolvesToTheTypedProducerOfItsParameterization` in
  `vauban-processor`, disabled; red when enabled, at build (`vauban-b03-red.log`, "Fix round 3 red run 18", on the
  round 3 tree). Enabled: ambiguous at build on the main code of 154c46fc (`vauban-b16-red.log`, "Red run 4"), and
  unsatisfied at start under the strict rule of BUG-20261117-16 ("Red run 3", base 492688ae), where a single such
  producer no longer satisfied `@Inject Repo<String>` either: the strict rule made this bug a regression.
- **Cause** (confirmed): `computeProducerTypesWithTyped` gives the class each `@Typed` value names, raw, and
  `Object`, without the type arguments the produced type gives it. A raw bean type satisfied an injection point of
  any parameterization (BUG-20261117-16), so both producers did. A bean class's `@Typed` takes the parameterized form
  (`typedBeanTypes`), and `ManagedBean.getTypes()` does for both.
- **Fix**: `computeProducerTypesWithTyped` takes each named class from the types of the produced type
  (`computeProducerTypes`), in its parameterized form when they hold one, as `typedBeanTypes` does for a bean class;
  both go through `typedForms`. Fixed with BUG-20261117-16: small, in the same code, and the strict rule would
  otherwise have broken `@Inject Repo<String>` on a single `@Typed(Repo.class)` producer of `Box<String>`.
- **Verification**: the test above green (`vauban-b16-dev.log`, "Dev run 3"); whole build and TCKs under
  BUG-20261117-16.
- **Found by**: the review of fix round 3 of BUG-20261117-14 (U1).

## BUG-20261117-18 — The client proxy of a normal-scoped producer of a parameterized type is not one of that type

- **Date**: 2026-11-17
- **Status**: FIXED (2026-11-18, the BUG-20261117-18 commit of the client proxy lane): the three paths read a
  parameterized bean type raw
- **Module**: `vauban-core` (`InterceptorBeanWrapper.resolveProxyTargetClass`), `vauban-processor`
  (`ClientProxyArtefacts`)
- **Symptom**: `@Produces @RequestScoped Supplier<String> greeting()` in an `@ApplicationScoped GreetingProducer`, and
  an `@Inject Supplier<String>` in another bean: calling the injected `Supplier` throws `ClassCastException: Cannot
  cast …InterfaceClientProxyTest$GreetingProducer_ClientProxy to java.util.function.Supplier`. By the review of fix
  round 3: `@Produces @RequestScoped ArrayList<String>` injected as a `List<String>` gets a proxy of `RandomAccess`,
  which is no `List`; a `@Typed` producer gets a proxy of the class that declares it, while the processor generates
  one of the `@Typed` interface.
- **Repro**: `InterfaceClientProxyTest#aNormalScopedProducerOfAParameterizedInterfaceInjectsAsAProxyOfIt` in
  `vauban-core`, disabled; red when enabled (`vauban-b03-red.log`, "Fix round 2 red run 7").
- **Cause** (by reading, the repro agrees for the first case): `resolveProxyTargetClass` takes, for a producer, the
  first bean type that is a `Class` and not `Object`, a class first, then an interface. A parameterized produced type,
  and every parameterized type above it, is a `ParameterizedType`, not a `Class`. With none left, it returns
  `bean.getBeanClass()`, the class that declares the producer, and the container proxies that class. With one left
  above it, a class or an interface that is not generic, it proxies that one: `RandomAccess` for `ArrayList<String>`.
  The injection point takes neither. The processor reads the descriptor's types that name a class without type
  arguments (`ClientProxyArtefacts.producedTypeCandidates`) in the container's order. Where both read the same types,
  it follows the container: it generates no proxy when there is no candidate, and the proxy of `RandomAccess` for
  `ArrayList<String>`, which the container then uses, with the same failure. For a `@Typed` producer they differ: the
  descriptor names the `@Typed` types raw (BUG-20261117-19), so the processor generates the proxy of the `@Typed`
  interface, `Repo` for `@Typed(Repo.class) Box<String>`, while `Bean.getTypes()` holds `Repo<String>` only and the
  container proxies the class that declares the producer.
- **Found by**: the review of BUG-20261117-15 (fix round 2), which asked to confirm or rule it out; the two other
  cases by the review of fix round 3 (M-5).
- **Red runs** (2026-11-18, `stack-bug1718-red.log`): run 1, the container on `Supplier<String>`, `ArrayList<String>`
  and `@Typed(Repo.class) Box<String>`: 2 errors of 4, `UnproxyableResolutionException` (the proxy of the declaring
  class, then a `java.lang.reflect.Proxy` of `RandomAccess`, is no `Supplier`, no `List`); run 2, `Box<Integer>` in place
  of `ArrayList<String>`: 2 errors of 4; run 3, the processor: no proxy of `Box` nor of `Repo`, 2 failures of 3; run 4,
  `vauban:generate`: no proxy of `Box`, 1 failure of 11.
- **Fix**: `InterceptorBeanWrapper.resolveProxyTargetClass` (and the interfaces of its `java.lang.reflect.Proxy`
  fallback), `ClientProxyArtefacts.producedTypeCandidates` and `VaubanGenerator.resolveProducedClass` read a
  `ParameterizedType` as its raw class, so the three pick the produced class, raw, else the first interface, in the
  same order as before. A `@Typed(Repo.class)` producer of `Box<String>` gets the proxy of `Repo` on every path. The
  processor generated it when the entry was written, from the raw `@Typed` types; on the base of this fix it
  generated none (red run 3). A producer of `ArrayList<String>` is now proxied as
  `ArrayList`, which Vauban refuses (`PACKAGE_PRIVATE_VIRTUALS`): it cannot define a class in `java.util`, and a proxy
  in the producer's package cannot override `ArrayList`'s package-private methods. The failure names the remedy;
  before, its proxy of `RandomAccess` was no `List`. CDI 4.1 §3.10 allows such a producer and Weld deploys it: a
  Vauban limitation that departs from Weld, tracked as BUG-20261118-24 and kept in this stack (review round 9).
- **Tests**: `InterfaceClientProxyTest#aNormalScopedProducerOfAParameterizedInterfaceInjectsAsAProxyOfIt` (enabled)
  and `#aNormalScopedProducerOfAParameterizedClassOrTypedInterfaceInjectsAsItsType`,
  `ProducedTypeProxyTest#aProducerOfAParameterizedTypeGetsTheProxyOfItsRawType`,
  `ProducerProxyAndProviderGenerationTest#aProducerOfAParameterizedTypeGetsTheProxyOfItsClass`.

## BUG-20261117-17 — A type argument of a produced type that is not a class is lost in the types above it

- **Date**: 2026-11-17
- **Status**: FIXED (the BUG-20261117-03 commit, fix round 2b)
- **Module**: `vauban-core` (`BeanDiscovery.parameterizedReflectType`), `vauban-processor` (`ParameterizedSupertypes`)
- **Symptom**: `@Produces Box<List<String>>`, where `Box<T> implements Repo<T>`: at start the producer's bean types
  are `Box<List<String>>`, `Box<Object>`, `Repo<Object>` and `Object`, and an `@Inject Repo<List<String>>` is
  unsatisfied. In an application compiled with the annotation processor, the build resolves that injection point:
  since BUG-20261117-14 the processor reads `Repo<List<String>>` from the compilation. The start then refuses it. The
  same for an array argument (`Box<String[]>`, `Box<List<String>[]>`, `Box<int[][]>`) and a type variable
  (`<T> Box<T>`, `<N extends Number> Box<List<N>>`): `Box<Object>` and `Repo<Object>` at start. The processor got the
  arrays of classes right, and gave the raw `Repo` to `Box<T>`, `Box<int[][]>` and `Box<List<N>>`.
  `Bean.getTypes()`, which reflection reads from the producer method itself, was right in each case.
- **Repro**: `VetoedInterceptorCompileTimeTest#aProducerOfATypeWithAParameterizedTypeArgumentHasTheTypesAboveItAtStartAsAtBuild`
  and `#aProducerOfATypeWithATypeArgumentOfEveryShapeHasTheTypesAboveItAtStartAsAtBuild` in `vauban-processor`,
  `BeanTypesAtStartTest#aTypeArgumentOfAProducedTypeKeepsItsShapeInTheTypesAboveIt`,
  `#aProducerOfATypeWithAParameterizedTypeArgumentSatisfiesThatParameterizationAboveIt` and
  `#…SatisfiesNoOtherParameterizationAboveIt` in `vauban-core`. Red on b8302835: the compilation passes and the start
  fails with `Unsatisfied dependency: field Reader.injected` (`vauban-b03-red.log`, "Fix round 2b red run 11"); each
  place's types and the core cases (run 12); an `@Inject Holder<List<String>>` unsatisfied and an `@Inject
  Holder<Object>` satisfied by a producer of `Base<List<String>>` (run 14); the producers of `Crate<C> implements
  Repo<C[]>`, `Crate<String[]>` and `<T> Crate<T>`, in the every-shape test, and a class argument, `Base<String>`, in
  the core one (run 15, on the main code of b8302835 with the tests of "Fix round 2b dev run 12").
- **Cause**: `computeProducerTypes` rebuilds the produced type for `TypeHierarchyResolver`, and
  `parameterizedReflectType` turned each type argument that is not a plain class into `Object`. In the processor,
  `ParameterizedSupertypes` built the produced type in the compiler's model, which has no type variable for a
  `TypeInfo` that names one (a type alone does not declare it) and had no mirror for the index's primitive type: it
  gave nothing, and the bean discovery fell back to the index's raw supertypes.
- **Fix**: on both sides, a type argument that is not a class is carried as the index wrote it to where the type
  variable it stands for appears above the produced type. At start `parameterizedReflectType` wraps it in an
  `IndexedType`, a `java.lang.reflect.Type` that `TypeHierarchyResolver` places without reading it (no type variable
  of the classes above can occur in it) and `reflectTypeToTypeInfo` gives back. In the processor the compiler gets the
  type variable itself in that place, and the conversion to the index's types replaces that type variable, by its
  element, with the argument; an array of such an argument is one array of both dimensions, as reflection gives it. A
  class argument is still the class at start and the compiler's type at build. So the argument is the same in
  `Box<X>` and in every type above it, at build, at start and frozen.
- **What the start resolves differently**: such a producer no longer has `Box<Object>` and `Repo<Object>`, so an
  injection point of those types that it satisfied, or made ambiguous with another bean, no longer finds it (tested
  with `Holder<Object>` and a producer of `Base<List<String>>`), and an injection point of the type above it with the
  argument resolves to it (tested with `Repo<List<String>>` and `Holder<List<String>>`; for the arrays and the type
  variables the tests check the types, by reading the resolution follows). A producer of `Box<T>` now has
  `Repo<T>`, a legal type for a producer (CDI 4.1, 2.2.1). A wildcard argument gives types that are not legal, and
  dropped with their raw classes; such a producer is a definition error anyway (CDI 4.1, 3.2). At build, the
  producers of `Box<T>`,
  `Box<int[][]>` and `Box<List<N>>` no longer have the raw `Repo`, which satisfied an injection point of any `Repo`
  (BUG-20261117-16). `Bean.getTypes()` does not change.
- **Verification**: the five tests above, green with the fix (`vauban-b03-dev.log`, "Fix round 2b dev run 10" and
  "dev run 11"), and with the `Crate` producers and the class argument ("dev run 12"); full build and TCKs under
  BUG-20261117-03.
- **Found by**: the review of BUG-20261117-14 (fix round 2), which asked to confirm or rule it out; fixed in this
  pull request on review (fix round 2b), as the build resolved what the start refused.

## BUG-20261117-16 — A raw bean type satisfies an injection point of any parameterization

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/raw-type-assignability`
- **Module**: `vauban-core` (`AssignabilityRules.isAssignableToParameterized`, `VaubanBeanManager.typesMatch`,
  `BeanDiscovery.computeBeanTypes`)
- **Symptom**: a producer of a raw `Foo`, `@Produces Foo foo()` with `Foo<T>` generic, satisfies an `@Inject
  Foo<Integer>`: the container starts. `BeanManager.getBeans` of `Foo<Integer>` returns no bean.
- **Repro**: `AssignabilityRulesReproductionTest#aRawBeanTypeDoesNotSatisfyAnotherParameterization` in `vauban-core`,
  disabled; when enabled, `getBeans` is empty and the container starts (`vauban-b03-red.log`, "Fix round 2 red run
  7").
- **Cause** (confirmed): the resolution of an injection point (`BeanResolver` → `AssignabilityRules.beanTypeMatches`
  → `isAssignableToParameterized`) takes a raw bean type for any parameterization of its class. CDI 4.1, 5.2.4 allows
  it only when each type argument of the required type is `Object` or an unbounded type variable. `getBeans` took a
  wildcard as well.
- **Fix**: one rule for the resolution, at build and at start, and for `getBeans`: "A raw bean type is considered
  assignable to a parameterized required type if the raw types are identical and all type parameters of the required
  type are either unbounded type variables or java.lang.Object" (CDI 4.1, 5.2.4), and a parameterized bean type to a
  raw required type on the same condition on its own arguments. A type variable is unbounded when it declares no
  bound but `Object`, as Weld's `isUnboundedTypeVariable`: `T extends U` is bounded, `U` unbounded or not, a
  variable declared with a bound being bounded (JLS 4.4). The first round took it for unbounded; the review of fix
  round 1 reversed it.
  - **One rule set** (fix round 1): `BeanManager.getBeans`, `getReference` and `isMatchingBean` call
    `AssignabilityRules.beanTypeMatches`, the rules of the resolution of an injection point at build and at start,
    through `VaubanBeanManager.typesMatch`, which converts the reflected types to the index model
    (`TypeInfoUtils.fromReflectType`, now finite on `T extends Comparable<T>`) and, for the container, keeps the
    conversions. `getBeans` kept a copy of its own that read the declared bounds of a type variable where the
    resolution read the uppermost ones: `Chained<U extends Number, T extends U> implements Holder<T>` passed the
    validation for `@Inject Holder<Integer>`, then the injection, which reads `getBeans` and falls back on
    `select(Holder.class)`, threw `UnsatisfiedResolutionException`. The copy is gone; the reflective helpers left
    serve the type arguments of an event only (`eventTypesMatch`, CDI 4.1, 10.3.1).
  - **The rules, as Weld's `BeanTypeAssignabilityRules`**: the bound checks, rule e and the wildcard rules, read the
    uppermost bounds (`getUppermostTypeVariableBounds`); rule f too, with `Object` for no bound on either side, so a
    synthetic bean's `(T extends java.lang.Object)` meets `T`; rule c takes the wildcard's bounds as Java does, a
    `List<String>` within `? extends List`; rule b compares a parameterized argument by these same rules; a required
    type that is neither a class, a parameterized type nor an array, and an array of a type variable, match nothing
    but themselves. Settled with it, from the review: `T extends Number & Comparable<T>` against `? extends
    Serializable`, which `getBeans` refused bound by bound and the resolver and Weld take; `boundsCovered` against a
    synthetic `(T extends java.lang.Object)`; and `rawBeanTypeMatchesParameterized`, which compared the classes by
    identity, removed.
  - **Fix round 2**: an observer's parameter besides the event parameter, and a disposer's besides the disposed one, of
    a type variable is a definition error (CDI 4.1, 5.2.3; TCK audit, disposer methods h and
    `legal_injection_point_types` c), as the parameter of an `@Inject` method already was: `IndexClassValidator`, at
    build and at start, and `ReflectionValidator` report `Injection point has type variable type T:
    vi.types.WidgetFactory.dispose disposer param 1`, for a class that may be a bean, neither abstract nor vetoed. A
    bean that inherits the observer of a class that is none gives the type variable its class: the CDI TCK's
    `MemberLevelInheritanceTest` (`Foo extends Bar<String, Qux>`, `Bar` vetoed), which a first cut refused. Fix round 1
    looked a disposer's up by its erased class, which `DisposerInvoker` no longer does. The rules read a class the index
    does not hold through the container's class loader, not the caller's context loader, which a thread of the
    application may set to a loader that does not see the deployment: `getBeans` then found no bean, where the
    reflective rules compared the classes. Only the bean types of the deployment are kept converted; a caller's type is
    converted at each call. The rules of a type variable in the event matching are gone: `isMatchingEvent` refuses a
    type variable before they could run.
    Fix round 2b: a producer method's parameter too, `… producer param 0` ("Red run 15", base 0151202a).
    Fix round 3: `getReference`, on the path of every injection, matches a bean of the container through the
    conversions `getBeans` keeps, and a `Bean` its caller makes as before, kept nowhere ("Red run 16", base
    a3d8c032).
  - **Wildcards**: a raw `Foo` satisfies neither `Foo<?>` nor `Foo<? extends X>`. A wildcard is neither an unbounded
    type variable nor `Object`; Weld 5.1 refuses it too; the CDI TCK's `RawBeanTypeParameterizedRequiredTypeTest`
    (audit 5.2.4, `assignable_parameters`) has no wildcard case. `getBeans`, which took it, no longer does
    (`TypesMatchCharacterizationTest#rawBeanType`). The array exception stays: `Class[]` satisfies `Class<?>[]`
    (VAU-BCE-004, MicroProfile Config through Ravel).
  - **A generic bean class** is typed with its own type variables, `Repo<T>` for `class Repo<T>`, as
    `Bean.getTypes()` has it: in the descriptor at start and at build (`computeBeanTypes`), with `@Typed`
    (`typedBeanTypes`), and in what an extension sees. Raw, it satisfied `@Inject Repo<String>` through the old rule
    only; with its type variable it does by rule e. That needed the type-variable rules of BUG-20261117-24, or the CDI
    TCK's `Dao<? extends Integer, ?>` on a `Dao<T1, T2>` bean would have failed. A `@Typed` producer needed
    BUG-20261117-19, and an observer's or a disposer's parameter BUG-20261117-25.
  - **Where the bean types travel**: the bean types of a class or a producer are not frozen: the descriptor is
    rebuilt at start from the index, whose type parameters the class file gives, and `_VaubanComponents` and
    `vauban-beans.list` hold no bean types. A synthetic bean's types go through `ObservedTypeNotation`, which writes a
    type variable `(T extends java.lang.Object)` and reads it back with an explicit `Object` bound, which the rule
    takes for no bound.
- **What changes for an application**: `@Inject Foo<Integer>` on a producer of a raw `Foo` fails the build when `Foo`
  is a class (`ERROR: [Vauban] Unsatisfied dependency: field Reader.injected of type
  ParameterizedType[rawType=vi.types.Foo, typeArguments=[ClassType[name=java.lang.Integer]]] with qualifiers
  [...]`), warns at build and fails the start when
  it is an interface (`CDI deployment validation failed:` and the same line, a `DeploymentException`), at start and
  from the frozen record alike. A raw `@Inject Repo` no longer resolves to a bean of `Repo<T extends Number>` (rule
  a, as `getBeans` and Weld), nor does `VaubanContainer.select(Repo.class)`, as `getBeans(Repo.class)` already did
  not: a bounded generic `@Dependent` Cassini resource or provider fails at request time with `Not managed by Vauban`
  (`VaubanBeanProvider.getBean`). A producer of `Outer<String>.Inner<Integer>` injected as `Holder<String>`
  resolved through the old rule and is unsatisfied until BUG-20261117-21 is fixed. Docs: `whats-new.adoc`,
  `migration.adoc` (Upgrading).
- **Verification**: red on the main code of 154c46fc with the tests of 95e240cf (`vauban-b16-red.log`, "Red run 1b":
  core 25 failures and 2 errors, processor 6 failures); on the strict rule alone, base e727c02c, the bean-class tests
  stay red ("Red run 2": test 2a `aBeanOfAGenericClassIsInjectableAsAParameterizationOfIt…`, which the old rule kept
  green on the base, test 2b and `Counter<Integer>`); the raw `@Inject Counter` test red on the base ("Red run 4").
  Green with the fix (`vauban-b16-dev.log`, "Dev run 1" to "Dev run 5"). Fix round 1: red on 3ce0b832 with the
  tests of a2acfa60 ("Red run 8": `Chained` in core and through the processor's start, `T extends U` bounded, two
  bounds against `? extends Serializable`, six edge cases). Fix round 2: red on b42eb9a6 ("Red run 10": the
  type-variable parameters in core, in both validators and through the processor; "Red run 11": `getBeans` from a
  thread whose context loader sees no deployment class), on the main code of 904a8b94 ("Red run 12":
  `isMatchingBean` took a raw `Counter` for `Counter<T extends Number>`), and on the first cut of the round ("Red run
  13": the TCK's vetoed `Bar`, abstract or vetoed classes in both validators). Green: the full build
  (`vauban-b16-build.log`, "Full build, fix round 4") and the TCKs (`vauban-b16-tck.log`, "TCKs, fix round 3";
  fix round 4 changed tests only).
- **Consumers**: no shipping consumer relies on the lenient rule. The parameterized injection points found are
  Cervantes' `@Claim ClaimValue<String>` and Dirac's `Gauge<Long>` (tests only), both satisfied by parameterized bean
  types. Mansart, Noether, Cassini, the Vidocq runtime extensions, Cyrano, Ravel, Kerckhoffs, Foy and Grimm do not
  rely on it. The external TCKs cannot be judged from the sources: the consumer jobs show them.
- **Found by**: the review of BUG-20261117-14 (fix round 2).

## BUG-20261117-15 — The processor generates the client proxy of a class or an interface above a producer's type

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/processor-bean-supertypes`
- **Module**: `vauban-processor` (`ClientProxyArtefacts.resolveProducedTypeElement`, `resolveProducedInterfaceElement`)
- **Symptom**: for a normal-scoped producer of a class that extends another class, or of an interface that extends
  another interface, the processor could generate the client proxy of a class or an interface above the produced
  type, under that type's key: `app.c.A2_ClientProxy` for a producer of `app.c.A8`. The container looks the proxy up
  by the produced type at start, finds none, and generates one at run time. Which proxy the processor generated
  changed from one JVM to the next.
- **Repro**: `ProducedTypeProxyTest` in `vauban-processor` (producers of `app.c.A8` and `app.c.B8`, each above seven
  classes, and of `app.i.J8` and `app.i.K8`, each extending seven interfaces): the keys of `A2`, `B7`, `J5` and `K1`
  (`vauban-b03-red.log`, "BUG-15 red run 2"; run 1, with the interfaces named `J0` and `K0`, got `A7` and `B1`).
  `PlacedProxyEmissionTest#placedProxyOverridesInheritedPackagePrivateMembers` failed in one run on that code
  (`vauban-b03-dev.log`, "dev run 3") and passed in another ("BUG-15 red run").
- **Cause** (confirmed by the repro): both methods returned the first class, or the first public interface, among
  the producer's bean types. A `BeanDescriptor` keeps them in a `Set.copyOf`, whose order changes from one JVM to the
  next. They held the classes above a produced class of the compilation only when the index held them, a superclass
  with a bean-defining annotation say; since BUG-20261117-03 they hold all of them. A producer of a JDK class whose
  superclass is not `Object` (`java.security.SecureRandom`) could already get its superclass's proxy. The container
  takes the first class of its own bean types, where the produced type comes first
  (`InterceptorBeanWrapper.resolveProxyTargetClass`).
- **Fix**: `ClientProxyArtefacts` takes the candidate the container proxies. It reads the produced type from the
  producer method or field, walks the classes and interfaces above it in the container's order (`TypeHierarchyResolver`:
  a class, its superclass, then its interfaces as they are declared), and takes the first candidate it meets, as
  `resolveProxyTargetClass` takes the first class of its bean types. A producer `@Typed({T1.class, …, T8.class})` of
  `Made implements T8, …, T1`, eight unrelated interfaces, gets the proxy of `T8`, the one the container uses; the
  first fix took the first candidate when none is a subtype of all the others, and that order changes from one JVM to
  the next. One of overloaded producer methods is found by the erased parameter types its id carries
  (`Class#method(T1,T2)`); a producer whose member is not found gets no proxy, and the container generates one at run
  time. A non-public interface the container proxies now gets no proxy, rather than the proxy of a public interface
  above it, which the container does not look up.
- **Verification**: red on d9144099 plus the BUG-20261117-03 fix (`vauban-b03-red.log`, "BUG-15 red run" and "BUG-15
  red run 2"), green with the fix (`vauban-b03-dev.log`, "BUG-15 green check"). The `@Typed` case:
  `ProducedTypeProxyTest#aProducerTypedWithUnrelatedInterfacesGetsTheProxyTheContainerLooksUp`, red on 438d3a80 (the
  processor generated `T1`; `vauban-b03-red.log`, "Fix round 2 red run 5"); the same choice code generated `T8` in
  "Fix round 2 red run 9", with the overloaded `twin()` added: it changes from one run to the next. Green with fix
  round 2, the container using the generated proxies of `T8` and `T2` rather than a `java.lang.reflect.Proxy`. Then
  the build and the TCK of BUG-20261117-03. The two departures, tested in fix round 3 by `ProducerProxyChoiceTest`,
  which hands `ClientProxyArtefacts.generateForProducer` a descriptor: a producer of a package-private `Hidden extends
  Shown` gets no proxy, not that of `Shown`, and a producer whose member is not found gets none; a producer of `Shown`
  gets the proxy of `Shown`. They pin the code of 03db99af and pass on it (`vauban-b03-red.log`, "Fix round 3 red run
  17"): a member that is not found does not occur in a compilation, where the processor writes the id itself.
- **Found by**: the run of the whole `vauban-processor` suite before the commit of BUG-20261117-03.

## BUG-20261117-14 — The processor's bean types are raw, and a raw bean type matches every parameterization

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/processor-bean-supertypes`
- **Module**: `vauban-core` (`BeanDiscovery.computeBeanTypes`, `typedBeanTypes`, `computeProducerTypes`), as
  `vauban-processor` runs them; at start, `TypeHierarchyResolver` and `ManagedBean.getTypes()`
- **Symptom**: in an application compiled with the annotation processor, `vi.types.Text implements Callable<String>`
  and `vi.types.Count implements Callable<Integer>`, both `@Dependent`, make an `@Inject Callable<String>` ambiguous at
  build (`Ambiguous dependency: … Matching beans: [vi.types.Text, vi.types.Count]`), and the compilation fails. The
  container resolves it to `Text` at start. The same holds through interfaces the compilation compiles: with
  `TextsImpl implements Texts`, `Texts extends Repo<String>`, and `CountsImpl` through `Counts extends Repo<Integer>`,
  an `@Inject Repo<String>` is ambiguous at build, `[vi.types.TextsImpl, vi.types.CountsImpl]`, and resolves to
  `TextsImpl` at start.
- **Repro**: `VetoedInterceptorCompileTimeTest#anInjectionPointOfAParameterizedTypeResolvesAtBuildAsAtStart` in
  `vauban-processor`: both injection points ambiguous at build (`vauban-b03-red.log`, "BUG-14 red run", and "Fix round
  1 (BUG-14) red run 1"). The resolution at start: the probes of the same two shapes, with the build's validation off
  (`vauban-b03-probe.log`, `PROBE-GENERIC`).
- **Cause** (confirmed by the tests below): the processor's bean types named a class's supertypes raw. The index keeps
  the superclass and the interfaces of a class as names, without their type arguments. The container adds the
  parameterized ones by reflection on the bean class (`TypeHierarchyResolver.resolveAllSupertypes`), and a class the
  compilation compiles does not load in the processor, so the processor kept the raw ones.
  `AssignabilityRules.isAssignableToParameterized` lets a raw bean type match any parameterization of the required
  type, so every bean that has the raw `Callable` or `Repo` matched. The direct form, `Text implements Callable<String>`
  with `Text` in the index, has the same cause, the reflection on `vi.types.Text`, and failed before BUG-20261117-03 as
  well (`vauban-b03-red.log`, "BUG-14 red run on the code before BUG-03": only `Readers.text` is ambiguous). Before
  that fix the bean types of `TextsImpl` stopped at `Texts`: the `Repo<String>` injection point had no bean at build,
  the processor deferred it to the container with a warning, and the compilation passed; that fix made it an error.
- **Fix**: `BeanDiscovery` takes the parameterized classes and interfaces above a bean class, and above a producer's
  type, from the processor while it runs (`CompiledSupertypes`, a `ScopedValue` in `io.vidocq.vauban.core.codegen`, a
  package exported to the processor only, which `io.vidocq.vauban.core.ProcessorAccess` gives it where javac defines it
  in a layer above vauban-core's, BUG-20261117-23), and by reflection otherwise. The processor's reading comes
  first even for a class that loads where it runs: `vidocq:generate` runs javac in process, and a copy of a class in
  the plugin's class loader is not the one the compilation compiles. A producer's type may be parameterized,
  `Box<String>`, or have a type argument the compilation compiles, `List<Foo>`. The processor (`ParameterizedSupertypes`,
  bound in `VaubanProcessor.process` around the deployment it processes) reads them from the compiler's model:
  `Types.directSupertypes` gives each supertype with its type arguments substituted, inside a wildcard, an array and
  the type that encloses an inner class too (JLS 4.10.2), so a type variable resolves along the chain
  (`Texts extends Base<String>`, `Base<B> extends Repo<B>` gives `Repo<String>`); a raw supertype is walked from its
  declaration, its type variables unresolved; the type itself and `Object` are left out; wildcards, arrays, primitives
  and type variables with their bounds are converted as `BeanDiscovery.reflectTypeToTypeInfo` converts them. No class
  is loaded and no reflection is added. The container binds nothing, at start and when `vauban:generate` freezes the
  extensions. Both then keep the legal bean types (CDI 4.1, 2.2.1), as `ManagedBean.getTypes()` keeps them: a
  parameterized type with a type variable that is neither the bean class's own nor one of the type a producer
  produces is replaced by its raw class, as Java erases the types above a raw type (JLS 4.8, fix round 3); a
  parameterized type with a wildcard type argument is dropped, and its raw class with it, as the CDI TCK's
  `BeanTypesWithIllegalTypeTest` requires (fix round 3b). A type variable of a class above the bean class, or above
  the produced type, which only a supertype named raw leaves unresolved, gives the raw class where the supertypes are
  read, by the declaration of the variable, not its name, inside a wildcard's bounds too: on both sides, the
  compiler's `TypeParameterElement.getGenericElement()` and reflection's `TypeVariable.getGenericDeclaration()`.
- **What the start does differently** (fix round 2, each red first): `TypeHierarchyResolver` substituted a type
  variable at the top of a type argument and inside a parameterized one only, and never mapped the type variables of
  an enclosing class; it now substitutes inside a wildcard, an array and an enclosing type too, and maps them, as the
  compiler does. `ManagedBean.getTypes()` read the classes and interfaces above a bean class with a walker of its own,
  which substituted a top-level type argument only; it now reads them through `TypeHierarchyResolver`. The descriptor
  types, which the resolution of an injection point and an extension's `BeanInfo.types()` read, are filtered as
  `Bean.getTypes()` is. So: `ArrayLibrary extends ArrayShelf<String>`, with `ArrayShelf<S> implements Holder<S[]>`, is
  a `Holder<String[]>`, where it was a `Holder<S[]>`, and an `@Inject Holder<String[]>` that the start refused
  resolves; `InnerUser extends Outer<String>.Inner<Integer>`, with `Inner<I> implements Holder<O>`, is a
  `Holder<String>`, where its descriptor had `Holder<O>` and `Bean.getTypes()` no `Holder`; `Library`, through
  `Shelf<S> implements Holder<List<S>>`, has `Holder<List<String>>` in `Bean.getTypes()` too, where only its
  descriptor had it; `Holder<List<? extends S>>`, `Holder<Map<?, int[][]>>`, `Pair<List<? extends Number>, …>` and
  the `Holder<B>` above a class that implements `Base` raw are no longer in the descriptor nor in what an extension
  sees, and were never in `Bean.getTypes()`; the raw `Holder` above the class that implements `Base` raw is in all
  three (fix round 3), the raw classes of the types with a wildcard in none.
  The types of an event go through `TypeHierarchyResolver` too
  (`EventDispatcher`): an event of an `ArrayLibrary` already reached an observer of `Holder<String[]>` before
  (`vauban-b03-red.log`, "Fix round 2 red run 8" and the note after it).
- **What fix round 3 changes** (each red first): the rule on legal bean types dropped an illegal parameterized type
  after the raw form of its class had been dropped for it, so nothing was left of that class. A bean of `LegacyNames
  extends ArrayList`, raw, was no `List`, `Collection` or `Iterable`, a bean of `extends HashMap` no `Map`, and an
  `@Inject List`, `List<Object>` or `Map` that resolved on d9144099 and on 438d3a80, by the review, was unsatisfied at
  build and at start; `@Typed(Holder.class) class TypedRawUser implements Base` had `Object` only. They now have the
  raw types, in the descriptor, in what an extension sees and in `Bean.getTypes()`, whose `addRawForRemoved` promised
  it and never did it. `Legacy<E> extends ArrayList` kept `ArrayList`'s `List<E>` in its descriptor, its `E` taken for
  its own by name; it now has the raw `List`, as in `Bean.getTypes()`. At start, `Bean.getTypes()` of a producer keeps
  a parameterized type with a type variable only when that variable is in the produced type: a producer of
  `LegacyNames` had `SequencedCollection<E>` and the like, and has the raw classes now (red run 17), and a producer
  of a generic class named raw, `Ranked`, had `Holder<T>` there and in its descriptor, and has `Holder` (red run
  22). A producer whose descriptor kept `Holder<O>` above `Outer<String>.Inner<Integer>`, at start and frozen, now
  has the raw `Holder`, as at build (red run 22, BUG-20261117-21). Fix round 3 first replaced a type with a
  wildcard type argument by its raw class too, and the CDI TCK failed on it (`vauban-b03-tck.log`, "TCK of
  b5f950c8"): `BeanTypesWithIllegalTypeTest`, three tests, wants `Eagle<T> extends Bird<T>`, where
  `Bird<T> extends AnimalHolder<Animal<? extends T>>`, to have exactly `Eagle<T>`, `Bird<T>` and `Object`, as a
  bean, as a producer field and as a producer method. Such a type is dropped
  with its raw class again (fix round 3b). A raw supertype still erases the types above it, wildcards and all:
  `RawEyrie<P> extends Perch`, raw, where `Perch<P> implements Holder<List<? extends P>>`, is a `Holder`; the
  source reads a wildcard's bounds for the type variables of another class, so that its own `P` is not taken for
  `Perch`'s.
- **Agreement, and what is left**: for the shapes the tests cover, the processor's descriptor types equal the
  container's at start and frozen, and `Bean.getTypes()` equals them but for the type of a generic bean class itself,
  raw in the descriptor (`Ranked`) and with its type variables in `Bean.getTypes()`
  (`Ranked<T extends Number & Comparable<T>>`). A type argument of a produced type that is not a class, which the
  start turned into `Object` above it, is carried as the index wrote it at build and at start (BUG-20261117-17,
  FIXED). Still different: a raw bean type satisfies an injection point of any
  parameterization, at build as at start (BUG-20261117-16, OPEN); the client proxy of a normal-scoped producer of a
  parameterized type is not one of that type (BUG-20261117-18, OPEN); the descriptor of a `@Typed` producer names its
  types raw (BUG-20261117-19, OPEN); the descriptor of a producer of an inner class of a generic class has the raw
  types above it, where `Bean.getTypes()` has them parameterized (BUG-20261117-21, OPEN). Since BUG-20261117-16 the
  descriptor types a generic bean class with its type variables, `Ranked<T extends Number & Comparable<T>>`, as
  `Bean.getTypes()` does, a raw bean type satisfies the parameterizations CDI allows only, and BUG-20261117-19 is
  fixed with it.
- **Mansart**: the class Mansart's processor generates for a repository, `<Repository>Impl`
  (`mansart-data-processor`, `RepositoryWriter.java:152`), is a `@Singleton` that `implements` the application's
  repository interface (`RepositoryWriter.java:228` and `:232`), which the application compiles and which extends
  `BasicRepository<Book, Long>` (`mansart-data-tests`, `BookRepository.java:34`). In an application compiled with both
  processors, two repositories made an `@Inject BasicRepository<Book, Long>`, or `DataRepository<Book, Long>`,
  ambiguous at build since BUG-20261117-03 (both have the raw `BasicRepository`), and unsatisfied with a warning
  before it. An injection point of the repository interface itself, `@Inject BookRepository`, names a class without
  type arguments and was never affected. With the fix the generated class is a `BasicRepository<Book, Long>`, as at
  start: the shape of `aParameterizedTypeABeanHasThroughAnInterfaceItCompilesResolvesAtBuildAsAtStart`. No Mansart test
  or example covers it: none injects a parameterized repository type, and `mansart-data-tests` compiles its
  repositories with Mansart's processor only (`pom.xml`, `annotationProcessorPaths`), the Vauban container reading
  them at start (read only, Mansart `main` at a873403f).
- **Verification**: red first, on 6462110e (the BUG-20261117-03 commit) with the new tests (`vauban-b03-red.log`, "Fix
  round 1 (BUG-14) red run 1"), each test run in the processor, at start and frozen: the repro, enabled, and
  `aParameterizedTypeABeanHasThroughAnInterfaceItCompilesResolvesAtBuildAsAtStart` (`Repo<String>` through `Texts`),
  `aTypeVariableResolvesAlongTheInterfacesTheCompilationCompilesAtBuildAsAtStart` (`Base<B> extends Repo<B>`) and
  `aParameterizedTypeABeanDeclaresResolvesAtBuildAsAtStart` (`Callable<String>`), ambiguous at build;
  `theProcessorsBeanTypesEqualTheContainersForEveryShapeOfTypeArgument` (wildcards, arrays of primitives, a nested
  class, an intersection bound that refers to itself, a raw supertype, a chain of superclasses, `ArrayList<String>`,
  a producer), raw types in the processor. Then on `BeanDiscovery.java` of 6462110e with the new core tests
  (`vauban-b03-red.log`, "Fix round 1 (BUG-14) red run 2"): `BeanDiscoveryReflectionFallbackTest#beanTypesCarryTheTypeArgumentsTheProcessorReads`,
  `#typedKeepsTheTypeArgumentsTheProcessorReads` and `#producerTypesCarryTheTypeArgumentsTheProcessorReads`, raw
  `NoSuchRepo`. `aSupertypeTheCompilationDoesNotFindLeavesJavacsError`, a guard for a superclass, an interface or a
  type argument javac does not find, passed on the code before it too: javac reports the missing classes and the
  processor runs its deployment (`vauban-b03-red.log`, "red run 3" and "4", and the note after them;
  `vauban-b03-probe.log`, `PROBE-ERRONEOUS`); it now asserts the processor's note "Generated
  vi.types._VaubanComponents" too. Fix round 2, red on 438d3a80 with the new tests (`vauban-b03-red.log`, "Fix round
  2 red run 5" and "6"), the processor tests each in the processor, at start and frozen, with `Bean.getTypes()` at
  start and frozen: `aTypeVariableResolvesInsideAWildcardAnArrayAndAnEnclosingTypeAtStartAsAtBuild` (`Holder<S[]>`,
  `Holder<O>` and `Holder<List<? extends S>>` at start), `anInjectionPointOfATypeArgumentArrayResolvesAtStartAsAtBuild`
  (unsatisfied at start), `aProducerOfAParameterizedTypeHasTheTypesAboveItAtBuildAsAtStart` (`Box<String>` and
  `List<Foo>` alone in the processor), `aProducerOfAParameterizedTypeAndABeanOfATypeAboveItAreAmbiguousAtBuildAsAtStart`
  (the build resolved it), `theProcessorsBeanTypesEqualTheContainersForEveryShapeOfTypeArgument` (illegal types in the
  descriptor, `Holder<List<String>>` missing from `Library`'s `Bean.getTypes()`); in `vauban-core`,
  `BeanTypesAtStartTest`, six of its tests (`aBeanKeepsTheTypesItsOwnTypeVariablesAreIn`, a guard, passed once its
  helper was fixed in run 6; `aSubstitutedWildcardAndArrayEqualTheJdks`, added later, is red on the resolver of
  438d3a80, "Fix round 2 red run 10"), and
  `BeanDiscoveryReflectionFallbackTest#theCompilationsReadingComesBeforeAClassThatLoads` (reflection first). Green: the build and the TCK of BUG-20261117-03 (`vauban-b03-build.log`, `vauban-b03-tck.log`),
  and the whole `vauban-core` and `vauban-processor` suites before the commit (`vauban-b03-dev.log`, "Fix round 1
  (BUG-14) dev run 2", and "Fix round 2 dev run 8").
  Fix round 3, red on 03db99af (`vauban-b03-red.log`, "Fix round 3 red run 17"; run 16 ran the first test alone):
  `BeanTypesAtStartTest#aClassThatExtendsAGenericClassRawHasTheRawTypesAboveIt`, eight failures (`LegacyNames`,
  `Legacy` and a producer of `LegacyNames`, without `List` and the raw types above it, in the descriptor and in
  `Bean.getTypes()`, `Legacy` with `List<E>` in its descriptor, `TypedRawUser` with `Object` only); in the processor,
  `aBeanOfAClassThatExtendsAGenericClassRawHasTheRawTypesAboveItAtBuildAsAtStart`, in the processor, at start, frozen
  and in `Bean.getTypes()`, and `anInjectionPointOfARawSupertypeResolvesToABeanThatExtendsAGenericClassRaw`, an
  `@Inject List`, `Collection`, `Iterable`, `List<Object>` and `Map` each unsatisfied at build (run 19, on the main
  code of 03db99af). Green with the fix, the tests that asserted the loss changed (`vauban-b03-dev.log`, "Fix round 3
  dev run 13" and "dev run 14"). Fix round 3b, red on the main code of b5f950c8 (`vauban-b03-red.log`, "Fix round 3b
  red run 20" for `vauban-core`, "red run 21" for `vauban-processor`), after the CDI TCK of b5f950c8 failed:
  `BeanTypesAtStartTest#aTypeWithAWildcardTypeArgumentGoesWithItsRawClassUnlessARawSupertypeErasedIt`, four
  failures (`Eyrie` and a producer of `Eyrie<F>`, with the raw `Holder`, in the descriptor and in `Bean.getTypes()`),
  and the wildcard expectations of fix round 2 restored, `WildLibrary`, `Wild` and `Base<? extends Number>` with the
  raw `Holder`; in the processor, the same test, at build, at start, frozen and in `Bean.getTypes()`,
  `aTypeVariableResolvesInsideAWildcardAnArrayAndAnEnclosingTypeAtStartAsAtBuild` and
  `theProcessorsBeanTypesEqualTheContainersForEveryShapeOfTypeArgument`, with the raw `Holder` and `Pair`. The
  `RawEyrie` cases passed there and pin the order of the two rules. Green with the fix (`vauban-b03-dev.log`, "Fix
  round 3b dev run 17"), and the CDI TCK 774/774 on the working tree (`vauban-b03-tck.log`, "Dev TCK of the round 3b
  working tree"). Two tests of fix round 3 first ran green only, on code that already had the change; fix round 4
  ran them on the main code of 03db99af (`vauban-b03-red.log`, "Fix round 4 red run 22" and the note after it), red:
  `BeanTypesAtStartTest#aProducerKeepsTheTypeVariablesOfTheTypeItProduces`, two failures, `Holder<T>` in the
  descriptor and in `Bean.getTypes()` of a producer of `Ranked` (its `Base<N>`, `Base<List<String>[]>` and `T` cases
  pass there and pin the code), and
  `VetoedInterceptorCompileTimeTest#aProducerOfAnInnerClassOfAGenericClassHasTheSameTypesAtBuildAsAtStart`, two
  failures, `Holder<O>` at start and frozen where the processor gives the raw `Holder`.
- **Found by**: the bricks audit of BUG-20261117-03, which asked what a raw supertype changes in the resolution.

## BUG-20261117-13 — The `BeanInfo` of a producer bean has no producer method and no producer field

- **Date**: 2026-11-17
- **Status**: FIXED (2026-11-18, lane L4)
- **Module**: `vauban-core` (`VaubanBceBeanInfo`)
- **Symptom**: a `@Registration` method gets, for a producer bean, a `BeanInfo` whose `isProducerMethod()` or
  `isProducerField()` answers `true` and whose `producerMethod()` and `producerField()` answer `null`. `disposer()`
  answers `null` and `stereotypes()` an empty list for every bean. CDI 4.1 gives the producer method or field, the
  disposer method and the stereotypes. An extension that reads the producer's member throws `NullPointerException`,
  and the deployment fails.
- **Repro**: `BceRegistrationProducerInfoTest#theBeanInfoOfAProducerGivesItsMethodOrField` in `vauban-core`, disabled
  until the fix: `@Registration error: Cannot invoke "…MethodInfo.name()" because the return value of
  "…BeanInfo.producerMethod()" is null`, and the same for `producerField()` (`vauban-b03-red.log`, "BUG-13 red run").
- **Cause**: `VaubanBceBeanInfo.producerMethod()`, `producerField()` and `disposer()` return `null`, and
  `stereotypes()` returns `List.of()`.
- **Next**: find the member through the index from the producer's descriptor (its declaring class, its name and, for
  a method, its erased parameter types, as the producer ids of BUG-20261116-11 keep them), and the disposer and the
  stereotypes the same way. No brick of the workspace reads these four.
- **Found by**: the repro of BUG-20261117-11, which names a producer bean by its types because its `BeanInfo` gives
  no member.
- **Cause** (proved): `VaubanBceBeanInfo` returned `null` from `producerMethod()`, `producerField()` and
  `disposer()`, and `List.of()` from `stereotypes()`, on every path: the processor's, the container's at start and the
  build's that freezes the extensions all hand `@Registration` that class.
- **Fix**: `producerMethod()` finds the method in the index by the name the bean id carries after the declaring class
  and, for one of overloaded methods, by its erased parameter types (`BeanDiscovery.erasedName`, now public, as
  `BeanId.ofOverloadedProducerMethod` spells them); `producerField()` by its name. `disposer()` gives the method of the
  declaring class whose `@Disposes` parameter has the type of one of the bean's types, as the container pairs them
  (`DisposerDescriptor.disposes`, which `DisposerInvoker` now calls too), and qualifiers the bean has, `@Default` for
  none (`VaubanBceDisposerInfo`). A first version matched the erasure, and gave producers of `List<String>` and
  `List<Integer>` the same disposer; the L4 review found it, red `stack-l4-44-red.log` "Red run 1" (the three places),
  green `stack-l4-13b-dev.log` "Dev run 1"
  (`VetoedInterceptorCompileTimeTest#aBeanInfoGivesAProducerOfAParameterizedTypeItsOwnDisposerFromTheProcessorAtStartAndFrozen`). `stereotypes()` gives the annotations of the class, or of the producer
  member, whose type is a `@Stereotype`, each described by the language model (`VaubanBceStereotypeInfo`: the scope it
  declares, `@Alternative`, `@Priority`, `@Named` and its interceptor bindings).
- **TCK**: not covered; the CDI Lite TCK's `RegistrationTest` reads `qualifiers()` and `isInterceptor()` only.
- **Verification**: red, `stack-l4-13-red.log`: "Red run 1" (at start, the `NullPointerException` of
  `producerMethod()` and `producerField()`) and "Red run 2" (the processor, on b546df6e's `VaubanBceBeanInfo`: the same
  `NullPointerException` fails the compilation). Green, `stack-l4-13-dev.log`: "Dev run 2" (the `BceRegistration*`
  tests), "Dev run 3" (`VetoedInterceptorCompileTimeTest#aBeanInfoGivesItsProducerMemberDisposerAndStereotypesFromTheProcessorAtStartAndFrozen`),
  "Dev run 4" (vauban-core, vauban-processor, vauban-maven-plugin).

## BUG-20261117-12 — `vauban-synthetic-metadata.properties` changes at every compile (its date line)

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/synthetic-metadata-no-date`
- **Module**: `vauban-core` (`SyntheticMetadataSerializer.write`)
- **Symptom**: found by a Vidocq probe of the `vidocq:dev` loop. On the Bistro example (Foy), changing one method
  body gives `Hot reload signalled after … ms (layer swap: META-INF/vauban-synthetic-metadata.properties changed)`;
  the same change on the REST example, whose build writes no such file, gives `class redefinition: 1 class`. An
  application whose build writes synthetic metadata (4 of the 8 Vidocq examples: cyrano-rest-client, foy-servlet,
  mansart-h2, petstore) never gets a redefinition in place, so each save is a layer swap and loses the state of its
  application-scoped beans. The jar was not reproducible either.
- **Repro**: compile an application with an extension that registers a synthetic bean twice, a second apart, and diff
  `META-INF/vauban-synthetic-metadata.properties`: one line differs, `#Tue Nov 17 10:03:20 CET 2026`.
  `BceCompileTimeTest#syntheticMetadataIsTheSameAtEveryCompilation` compiles twice and compares.
- **Cause** (confirmed by the two red runs): `SyntheticMetadataSerializer.write` ended with
  `Properties.store(Writer, comment)`, which writes the current date as a comment line and iterates in hash order (a
  JDK detail, not a contract).
- **Fix**: the file is written without the date and with its keys sorted, escaped as `Properties.store` escapes. The
  pattern "store one entry, drop the `#` lines" existed twice (`FrozenExtensions.sorted`,
  `EnhancementPatchSerializer.line`): it is now one package-private helper, `PropertiesText` (`entry`, `sorted`), used by
  the three writers. The reader is untouched: a file of an earlier version, with the date and in hash order, reads as
  before. The helper no longer `strip()`s a line, which dropped the trailing spaces of a value in the two older copies.
- **Verification**: `SyntheticMetadataStableTest` (no date line, same bytes, sorted keys, an earlier file reads the same,
  escapes kept) and the processor test above. Red on 85203ee4 (`theFileHasNoDateLine`, and the processor test: both
  `expected: <[]> but was: <[#Tue Nov 17 10:12:42 CET 2026]>`), green after the fix. See the logs named in the PR.
- **Audit** (what was checked, no more): no other `.store(`, `now()` or `new Date()` in the main code of `vauban-core`,
  `vauban-processor` and `vauban-maven-plugin`, nor in `vidocq/main`, where `Instant.now()` appears only in the CLI
  notice, the runtime and the temp cleanup. Traced to "membership or lookup only, never iterated into an output":
  `ClientProxyArtefacts:62` (a `computeIfAbsent` cache), `ComponentProviderArtefacts:86` (`add` and `removeAll`),
  `InterfaceProxySourceRenderer:101` (`seen.add` dedup) and `VaubanProcessor:139,160,162` (`add` and `contains` only).
  **Not traced end to end**: `ClientProxyShapeFromElements:108,151,220,339,516` (they read as `add` dedup sets, but
  were not followed through every caller).

## BUG-20261117-11 — `@Registration` over beans ignores primitive and array bean types

- **Date**: 2026-11-17
- **Status**: FIXED (2026-11-18, lane L4)
- **Module**: `vauban-core` (`BceTypeMatcher.matchesTypes`, `rawTypeName`)
- **Symptom**: a `@Registration` method with a `BeanInfo` parameter is not called for a producer of `int` with
  `types = Integer.class` or `Serializable.class`, nor for a producer of `String[]` with `types = Serializable.class`.
  CDI 4.1 treats a primitive type as its wrapper class, and an array is `Serializable`; the `ObserverInfo` matcher
  reads them so since BUG-20261117-05. The processor, the start and the frozen deployment agree.
- **Repro**: `VetoedInterceptorCompileTimeTest#registrationGetsTheProducersOfPrimitiveAndArrayTypesFromTheProcessorAtStartAndFrozen`
  in `vauban-processor`, disabled until the fix: the three places see `[serializable vi.Logger]` instead of
  `[integer producer of int, serializable producer of int, serializable producer of java.lang.String[], serializable
  vi.Logger]` (`vauban-b03-red.log`, "BUG-11 red run").
- **Cause**: `matchesTypes` loads the class each bean type names (`rawTypeName`). A primitive or an array type names
  none, and a class type named after a primitive, which the container reads by reflection, does not load: only the
  bean type `Object` of such a producer matches.
- **Next**: read a bean type as `isAssignableToAny` reads an observed event type: a primitive as its wrapper class, an
  array through its component. The bricks' `@Registration` methods name `Object`, `Config`, `Servlet`, `Filter` and
  `EventListener`: none of them gains a bean.
- **Found by**: the review of BUG-20261117-02.
- **Cause** (proved): `BceTypeMatcher.matchesTypes` loaded the class each bean type names (`rawTypeName`), and a
  primitive or an array type names none: only the bean type `Object` of such a producer matched, on the three paths.
- **Fix**: `@Registration` matches beans with `BceTypeMatcher.matchesRegistrationTypes`: the bean types of a producer
  of a primitive or an array type are that type and `Object` (CDI 4.1, 2.2.1), so a primitive type, or a class type
  named after one, matches its primitive class and its wrapper, and an array its own array class; a class or
  parameterized type as before. `@Enhancement`, which matches classes, keeps `matchesTypes`. A first version read
  them by assignability (`int` matched `Number` and `Serializable`, `String[]` matched `Serializable` and
  `CharSequence[]`); the L4 review found it, red `stack-l4-11b-red.log` "Red run 1", green `stack-l4-11b-dev.log`
  "Dev run 1".
- **TCK**: not covered.
- **Verification**: red, `stack-l4-11-red.log`, "Red run 1" (the repro enabled on the code of the BUG-20261117-09
  commit: `[serializable vi.Logger]` in the three places). Green, `stack-l4-11-dev.log`: "Dev run 1" (the repro, the
  `BceTypeMatcher*` and `BceRegistration*` tests), "Dev run 2" (`BceTypeMatcherMembersTest#aRegistrationMatchesAPrimitiveOrArrayBeanTypeByTheTypeItself`,
  and vauban-core, vauban-processor, vauban-maven-plugin).

## BUG-20261117-10 — In the processor, `@Registration` does not see the annotations `@Enhancement` added

- **Date**: 2026-11-17
- **Status**: FIXED (2026-11-18, lane L4)
- **Module**: `vauban-processor` (`VaubanProcessor.runRemainingBcePhases`), `vauban-core` (`VaubanBceBeanInfo.declaringClass`)
- **Symptom**: an extension that adds `@Deprecated` to a class in `@Enhancement` and reads the class of the bean back
  in `@Registration` (`bean.declaringClass().annotations(...)`) finds it once at start and when the build freezes the
  extensions, and not at all while the processor runs it.
- **Repro**: `VetoedInterceptorCompileTimeTest#registrationSeesTheAnnotationsEnhancementAddedFromTheProcessorAtStartAndFrozen`
  in `vauban-processor`, disabled until the fix: the processor sees `[vi.Impl @Deprecated x0, vi.Other @Deprecated
  x0]`, start and frozen `[vi.Impl @Deprecated x1, vi.Other @Deprecated x1]` (`vauban-b10-red.log`).
- **Cause** (hypothesis, from the code): `VaubanBceBeanInfo.declaringClass()` reads the class from the index. At
  start, the index is the one the enhancement-only pass of BUG-20261117-08 enriched with the annotations it added
  (`VaubanContainerBuilder.collectEnhancements`, `enrichIndex`); the full run's `@Enhancement` modifications go to the
  bean descriptors only. The processor runs no such pass and hands `@Registration` the index as the compilation wrote
  it. Fixing BUG-20261117-08 by dropping that pass would make start lose the annotation too.
- **Next**: give `@Registration` a class view with the modifications of the `@Enhancement` phase that ran, in the
  processor and at start alike, before or with BUG-20261117-08. At start it misses some of them too, checked in the
  code (`VaubanContainerBuilder`) and, for the second case, by a probe. `@Discovery` runs first (~936); the first
  `@Enhancement` pass (`collectEnhancements` ~968, ~1280-1284) then runs on fresh instances of the extensions,
  without the state their `@Discovery` built, over the archive classes listed before `@Discovery` ran (~930), and its
  modifications reach the index `@Registration` reads (`enrichIndex` ~977). A type `@Discovery` adds is enhanced by the
  second pass only (`BceProcessor.process` ~997), and so is a modification that depends on that state; the second
  pass's modifications go to the bean descriptors only (`applyEnhancements` ~1022). An extension that adds
  `@Deprecated` only once its `@Discovery` ran finds it `x0` in the processor, at start and frozen; one that adds it
  unconditionally, `x0` in the processor and `x1` at start and frozen (`vauban-b03-red.log` and
  `vauban-b03-dev.log`, the `PROBE-B10` lines).
- **Found by**: the probe of the review of BUG-20261117-08 (does an unguarded `addAnnotation` land twice?),
  `vauban-b02-r2-probe.log`.
- **Cause** (proved): `BceProcessor.process` built one `IndexLookup` over the index it was given and kept it for
  the phases after `@Enhancement`. At start the container gave it an index it had already enriched with what the
  enhancement pass added (`VaubanContainerBuilder.enrichIndex`); the processor gave it the compiled index.
- **Fix**: after the `@Enhancement` phase, `process` reads `@Registration`, `@Synthesis`, `@Validation` and the check
  of the invokers' lookups through an index with the annotations that phase added (`withEnhancements`), as the
  container's `enrichIndex` does. It needed BUG-20261117-08: while the container called `@Enhancement` twice, it
  would have added each annotation twice at start.
- **TCK**: not covered; the TCK's `changeBeanQualifier` and `changeInterceptorBinding` read the bean metadata, not the
  `ClassInfo` of `@Registration`.
- **Verification**: red, `stack-l4-10-red.log`: "Red run 1" (the repro enabled: `@Deprecated x0` in the processor)
  and "Red run 2" (the same on the BceProcessor of the BUG-20261117-08 commit). Green, `stack-l4-10-dev.log`:
  "Dev run 1" (the repro and the BUG-20261117-08 test), "Dev run 2" (vauban-core, vauban-processor,
  vauban-maven-plugin).
- **Left**: like `enrichIndex`, the enriched index carries the annotations added to a class, not those added to its
  members.

## BUG-20261117-09 — An `ObserverInfo` has no observer method, no event parameter, no bean and no qualifier

- **Date**: 2026-11-17
- **Status**: FIXED (2026-11-18, lane L4)
- **Module**: `vauban-core` (`VaubanBceObserverInfo`)
- **Symptom**: the `ObserverInfo` a `@Registration` method gets answers `null` from `observerMethod()`,
  `eventParameter()` and `bean()`, and an empty `qualifiers()`, for an observer method of a bean. CDI 4.1 gives the
  method, its event parameter, the bean that declares it and the observed event qualifiers. An extension that reads
  them throws `NullPointerException`, and the deployment fails.
- **Repro**: `BceRegistrationObserverInfoTest#anObserverInfoGivesItsMethodItsEventParameterAndItsBean` in
  `vauban-core`, disabled until the fix: `@Registration error: Cannot invoke "…MethodInfo.name()" because the return
  value of "…ObserverInfo.observerMethod()" is null`.
- **Cause**: `VaubanBceObserverInfo` returns `null` or `List.of()` for these four. `ObserverDescriptor` holds the
  declaring class, the method name and the qualifiers, but no parameter types, so the method of an overloaded name
  cannot be told apart, and no link to the declaring bean.
- **Next**: record the erased parameter types in `ObserverDescriptor` (as producer methods' ids do, BUG-20261116-11),
  find the `MethodInfo` through the index, and give the declaring class's managed bean as `bean()`. No brick of the
  workspace declares an `ObserverInfo` `@Registration`.
- **Found by**: the review of BUG-20261117-05.
- **Cause** (proved): `VaubanBceObserverInfo` returned `null` from `observerMethod()`, `eventParameter()` and `bean()`,
  and `List.of()` from `qualifiers()`, on the three paths; `BceProcessor` built it from the `ObserverDescriptor`
  alone.
- **Fix**: `VaubanBceObserverInfo` finds, in the index's declaring class, the method of the descriptor's name with an
  `@Observes` or `@ObservesAsync` parameter whose type is the observed event type, its erasure when no type is equal:
  an overload of another event type is told apart without a new component in `ObserverDescriptor`. The event
  parameter is that parameter, the qualifiers are those it declares (the descriptor's names when the method is not
  found), and `BceProcessor` hands it the managed bean of the declaring class (`declaringBean`). `isSynthetic()` reads
  the descriptor. Two overloads of one name that observe the same event type were not told apart: BUG-20261118-41.
- **TCK**: `RegistrationTest` reads `declaringClass()` of an `ObserverInfo` only.
- **Verification**: red, `stack-l4-09-red.log`: "Red run 1" (at start, the `NullPointerException` of
  `observerMethod()`) and "Red run 2" (`VetoedInterceptorCompileTimeTest#anObserverInfoGivesItsMethodParameterBeanAndQualifiersFromTheProcessorAtStartAndFrozen`
  on the code of the BUG-20261117-04 commit: the processor fails the compilation with that exception). Green,
  `stack-l4-09-dev.log`: "Dev run 1", "Dev run 2", "Dev run 3" (vauban-core, vauban-processor, vauban-maven-plugin).

## BUG-20261117-08 — The container calls an `@Enhancement` method twice for each type, at start and when the build freezes

- **Date**: 2026-11-17
- **Status**: FIXED (2026-11-18, lane L4)
- **Module**: `vauban-core` (`VaubanContainerBuilder.collectEnhancements`, `BceProcessor.processEnhancementOnly`,
  `BceProcessor.process`)
- **Symptom**: CDI 4.1 calls an `@Enhancement` method once for each type it matches. When the container runs the
  extensions of an archive no processor ran them for, at start or when `vauban:generate` freezes them, it calls the
  method twice for each: an extension that records the classes in a static list sees `[vi.Impl, vi.Impl, vi.Other,
  vi.Other]`. The processor calls it once.
- **Repro**: `VetoedInterceptorCompileTimeTest#enhancementIsCalledOnceForEachTypeFromTheProcessorAtStartAndFrozen`
  in `vauban-processor`, disabled until the fix: at start and frozen, `[vi.Impl, vi.Impl, vi.Other, vi.Other]`
  instead of `[vi.Impl, vi.Other]`.
- **Cause** (hypothesis, from the code): `collectEnhancements` runs `processEnhancementOnly` over the classes of the
  unprocessed archives, with a new instance of each extension, to learn the annotations `@Enhancement` adds before
  discovery; `BceProcessor.process` then runs every phase, `@Enhancement` again, with the instances of the Discovery
  phase. An extension that keeps its state in its instance sees each class once per instance, and one that writes
  elsewhere, a static field or a file, sees it twice.
- **Impact today**: low. The bricks keep their state on the extension instance, and each instance sees each class
  once; Mansart's `vetoRoutedRepositoryImpls` is stateless on purpose, as its Javadoc says ("the container may run
  `@Enhancement` on a fresh extension instance").
- **Next**: run `@Enhancement` once and hand its modifications to both, or let `process` skip the pass done before;
  then audit the bricks' `@Enhancement` methods. It changes when every extension's `@Enhancement` runs, so it gets
  its own change. Two risks to weigh:
  - the first pass's modifications reach the index before the second pass runs (`enrichIndex` adds them without
    deduplicating), and the second pass adds its own to the bean descriptors. An unguarded `addAnnotation` does not
    land twice in what an extension reads: a probe that adds `@Deprecated` in `@Enhancement` and counts it back in
    `@Registration` finds it once at start and frozen (`vauban-b02-r2-probe.log`), and a descriptor's qualifiers and
    interceptor bindings are sets (`EnhancementApplier`). The same probe found that the processor shows none
    (BUG-20261117-10): the class `@Registration` reads at start carries the first pass's annotations, so dropping that
    pass changes what it reads;
  - the first pass runs on a fresh instance, without the state the `@Discovery` phase built: Dirac's
    `DiracExtension`, Mansart's `MansartDataExtension` and `MansartTransactionsExtension` declare both phases.
- **Found by**: the test of BUG-20261117-07, which records what `@Enhancement` sees in the three places.
- **Cause** (proved): at start, `VaubanContainerBuilder.collectEnhancements` ran the `@Enhancement` methods of the
  extensions over the classes no processor saw (`BceProcessor.processEnhancementOnly`, each extension on a new
  instance), to enrich the index before discovery; then `BceProcessor.process` ran its `@Enhancement` phase again
  over the beans, on the `@Discovery` instance. The capture build that freezes the extensions takes the same path.
- **Fix**: `processEnhancementOnly` takes the instances of `@Discovery` and calls each extension on its own;
  `collectEnhancements` enhances the classes no processor saw and those `@Discovery` added (`Enhancing`); and
  `BceProcessor.process` takes the set of extensions already enhanced and does not call their `@Enhancement`
  methods again. When that pass did not run (every source processed), `process` runs the phase as before.
- **TCK**: not covered; the CDI Lite TCK extensions do not count their `@Enhancement` calls.
- **Bricks**: Dirac, Mansart Data and Mansart Transactions declare `@Enhancement` and later phases: their
  `@Enhancement` method now runs once, on the instance their later phases run on.
- **Verification**: red, `stack-l4-10-red.log`, "Red run 1" (`VetoedInterceptorCompileTimeTest#enhancementIsCalledOnceForEachTypeFromTheProcessorAtStartAndFrozen`
  enabled on 3d208491, a lane commit since taken out, whose BceProcessor did not touch this path:
  `[vi.Impl, vi.Impl, vi.Other, vi.Other]` at start and frozen). `BcePhaseOrderTest` guards that an extension's
  phases run in order on its one instance; it was green before the fix too.
  Green, `stack-l4-08-dev.log`: "Dev run 1" (that test and `enhancementGetsTheSubtypesOfItsTypesFromTheProcessorAtStartAndFrozen`),
  "Dev run 2" (vauban-core, vauban-processor, vauban-maven-plugin).

## BUG-20261117-07 — An extension the processor runs misses an `@Enhancement` subtype whose type comes through a class or an interface above it

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/processor-sees-interceptors`
- **Module**: `vauban-core` (`BceTypeMatcher.matchesClassByIndex`), as `vauban-processor` runs it
- **Symptom**: while the processor runs an extension, `@Enhancement(types = EventListener.class, withSubtypes = true)`
  was called neither for `vi.Impl extends vi.Base`, where `Base implements EventListener`, nor for
  `vi.Other implements vi.Sub`, where `Sub extends EventListener`. The container calls it for both, at start and when
  the build freezes the extensions.
- **Repro**: `VetoedInterceptorCompileTimeTest#enhancementGetsTheSubtypesOfItsTypesFromTheProcessorAtStartAndFrozen`
  in `vauban-processor` (with `withAnnotations = Dependent.class`, `Impl` and `Other` `@Dependent`, `Base` and `Sub`
  without annotations): the processor saw `[]`. `BceTypeMatcherMembersTest#anIndexedClassMatchesTheInterfacesOfItsSuperclassesAndOfItsInterfaces`
  in `vauban-core`.
- **Cause** (confirmed by the tests above): the processor matches a class that does not load on the index
  (`processEnhancement`'s third path). `isSubtypeByIndex` walked its superclasses through `lookup.getClass`, the
  index only, which stops at `vi.Base` (no annotation, so not indexed), and compared the expected type with the
  interfaces the class declares itself, never those of a superclass nor of an interface. The bean path, which loads a
  bean's types, did not catch them either: their processor bean types stop at the unindexed class or interface
  (BUG-20261117-03).
- **Fix**: with subtypes, `matchesClassByIndex` walks every class and interface above the class as the interceptor
  and observer matchers do (`isSubtypeOfAny`): through `IndexLookup.describe`, so the processor's own description of
  the compilation too, loading only a class nothing describes. Without subtypes it matches the class itself, as before.
- **Verification**: red first on 5c784778 plus a 5-argument `matchesClassByIndex` that delegated to the 4-argument
  matcher of 5c784778, so the new unit test compiled (`vauban-b02-r1-red.log`, then `vauban-b02-r1-red2.log` with the
  three places reported at once): the unit test (`Impl` not matched) and the processor test (`[]` in the processor).
  Green with the fix (`vauban-b02-r1-green.log`), the duplicate calls at start aside (BUG-20261117-08).
- **Unannotated classes**: the repro filters on `withAnnotations = Dependent.class`. A probe without the filter, with
  the abstract `vi.Base`, the interface `vi.Sub`, and the concrete `vi.Loose implements EventListener` and
  `vi.LooseChild extends Base`, none of them annotated, finds the same `[vi.Impl, vi.Other]` in the processor, at
  start and frozen (start twice each, BUG-20261117-08): neither place gives a class without an annotation, so the
  filter hides no gap here (`vauban-b02-r2-probe.log`). The run of that probe in `vauban-b02-r2-green.log` failed at
  `seenThreeWays:678`, the `assertFalse` on `extensionsAtStart().frozen()`, most likely because its second
  `seenThreeWays` call in one `@TempDir` found the first call's freeze record; the evidence is its re-run in
  `vauban-b02-r2-probe.log`.
- **Found by**: the review of BUG-20261117-02, which questioned "as `@Enhancement` already did".

## BUG-20261117-06 — At start, a `@Registration` method with a `BeanInfo` parameter gets classes that are no beans

- **Date**: 2026-11-17
- **Status**: FIXED (2026-11-18, lane L4)
- **Module**: `vauban-core` (`BceProcessor.registrationTargets`, `archiveTargets`), `vauban-processor`
  (`VaubanProcessor.loadArchiveClasses`)
- **Symptom**: when no bean and no interceptor has one of a `@Registration` method's `types`, the container calls
  its `BeanInfo` method for each class of the archive that has one, bean or not: with `types = Marker.class`, the
  abstract `MarkedPart implements Marker`, which is no bean, with `isClassBean()` true. CDI 4.1 calls it for beans
  only. The processor makes no such call, so the same extension sees a class at start that it does not see at build.
- **Repro**: `BceRegistrationInterceptorInfoTest#aBeanInfoParameterIsGivenNoClassThatIsNoBean` in `vauban-core`,
  disabled until the fix: `[bean …$MarkedPart]` instead of `[]`.
  `anInterceptorInfoParameterIsGivenNoClassOfTheDeploymentThatIsNoInterceptor` pins today's behaviour at start.
- **Cause**: `registrationTargets` falls back to `archiveTargets` when no bean or interceptor matched and the method
  takes no `InvokerFactory`: each archive class assignable to an expected type (by reflection) that the index holds
  gets a `VaubanBceBeanInfo` over `BeanDescriptor.minimal`, a `MANAGED` descriptor. In the processor,
  `loadArchiveClasses` loads the index's classes through the processor's class loader, which does not load a class
  the compilation compiles: the list holds none of them. With `types = Object.class` there is always a bean, so the
  fallback never runs.
- **Foy**: `FoyWebExtension.collect` (`@Registration(types = {Servlet, Filter, EventListener})`, `BeanInfo`,
  `isClassBean()`, then a `@WebServlet`, `@WebFilter` or `@WebListener`) does not rely on the fallback. The
  processor, where Foy's extension runs in an application build and in every `FoyWebExtensionTest` case, never makes
  the call, and those tests pass. At start, `defaultScope` (`@Enhancement(withAnnotations = {WebServlet, WebFilter,
  WebListener})`) gives a web component without a scope `@Dependent` and records every concrete one itself, before
  `@Registration` runs; the fallback runs only when no bean at all has `Servlet`, `Filter` or `EventListener`, and
  then gives classes that are no beans, an abstract `@WebServlet` class say, which Foy's discovery skips as it skips
  any class without a bean. Removing the fallback changes nothing Foy serves.
- **Next**: drop the fallback, or keep the classes `@Enhancement` made beans only (`beansAfterEnhancement` already
  adds them), and check the CDI Lite TCK and the bricks' `@Registration` methods again. It changes what extensions
  see at start, so it gets its own change.
- **CDI Lite TCK** (2026-11-18, lane L4): dropping the fallback failed
  `org.jboss.cdi.tck.tests.build.compatible.extensions.priority.PriorityTest` ("Incorrect invocation order of extension
  methods"). Its deployment holds no bean and no interceptor for Vauban (`PriorityTest` and the extension, under an
  empty `beans.xml`), yet its `@Registration(types = Object.class)` method with a `BeanInfo` parameter must be called:
  the fallback gave it `PriorityTest`, a class that is no bean. Debug runs, `<scratchpad>/session-swap/stack-l4-08b-debug.log`,
  "Debug run 1" and "Debug run 2" (`beans=[]`, `interceptors=[]`, no target); the TCK failure, `stack-l4-08b-red.log`,
  "Red run 2". The implementations the TCK runs on give a `BeanInfo` to their built-in beans: Vauban has no
  `BeanDescriptor` for them. The fallback stays until it does; the fix of lane L4 was taken out of the stack.
- **Next (updated)**: describe the built-in beans (`BeanManager`, `Event`, `Instance`, `InjectionPoint`, ...) as beans
  that `@Registration` is given, in the processor, at start and frozen alike, then drop the fallback; the CDI Lite TCK
  `PriorityTest` is the check.
- **Found by**: the review of BUG-20261117-02.
- **Cause** (proved): `BceProcessor.registrationTargets` gave a `BeanInfo` parameter the beans and interceptors the
  container discovered, and, when none matched and the method took no `InvokerFactory`, the classes of the archive
  that were subtypes of an expected type (`archiveTargets`), each as `BeanDescriptor.minimal`. Vauban had no
  description of its built-in beans: the fallback was what fed `PriorityTest` its call. The processor passes no
  loadable class of the compilation, so it made neither call.
- **Fix**: the built-in beans are described as beans (`BuiltInBeans`: `BeanManager`, `Instance`, `Event`,
  `InjectionPoint`, `Bean`, `Interceptor`, `RequestContextController` and the `@Intercepted Bean`, `@Dependent`, their interfaces and `Object` as
  bean types, `@Default` and `@Any`, `@Intercepted` for the last) and `@Registration` gets those that match, in the
  processor, at start and frozen; their `declaringClass()` is their interface, described from the class file. The
  archive fallback is gone, with `archiveTargets`, `usesInvokerFactory` and the archive classes of
  `processRegistration`. Two processor tests that record every bean of `@Registration(types = Object.class)` now
  leave the built-in beans out. Round 4 of the review: `RequestContextController` joins the list (CDI 4.1, 6.5.8;
  Vauban provides it as `VaubanRequestContextController`), and `InvokerFactory.createInvoker` throws a
  `DeploymentException` for a bean that is no managed bean, as Weld does: it accepted a built-in bean, such as the
  `BeanManager`. The producer check it replaces threw an `IllegalStateException`. Round 5: every other refusal of
  `createInvoker` (interceptor, constructor, private method, `Object` method, method of another class) throws a
  `DeploymentException` too, as Weld does (`InvokerFactoryImpl`); red `stack-l4-06d-red.log`, green
  `stack-l4-06d-dev.log`. Red, `stack-l4-06b-red.log` and
  `stack-l4-06c-red.log`; green, `stack-l4-06b-dev.log` and `stack-l4-06c-dev.log`
  (`VetoedInterceptorCompileTimeTest#theInvokerFactoryRefusesABeanThatIsNoManagedBeanFromTheProcessorAtStartAndFrozen`).
- **TCK**: `PriorityTest` (one `@Registration(types = Object.class)` call with no bean of the deployment) and
  `RegistrationTest` pass: `stack-l4-06-dev.log`, "Dev run 5".
- **Verification**: red, `stack-l4-06-red.log`, "Red run 3" (`BceRegistrationInterceptorInfoTest#aBeanInfoParameterIsGivenNoClassThatIsNoBean`
  gives `MarkedPart`; `VetoedInterceptorCompileTimeTest#registrationGetsTheBuiltInBeansFromTheProcessorAtStartAndFrozen`
  gets no built-in bean in the three places). Green, `stack-l4-06-dev.log`: "Dev run 2" and "Dev run 3"
  (vauban-core, vauban-processor, vauban-maven-plugin), "Dev run 5" (the TCK tests).

## BUG-20261117-05 — `@Registration` over observers matches the class that declares them, not the observed event type

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/processor-sees-interceptors`
- **Module**: `vauban-core` (`BceTypeMatcher.matchesObserverTypes`, `BceProcessor.processRegistration`)
- **Symptom**: CDI 4.1 calls a `@Registration` method with an `ObserverInfo` parameter once for each observer whose
  observed event type is assignable to an expected type. The container called it for each observer whose declaring
  class is a subtype of one: with `types = Serializable.class`, an observer of `CharSequence` declared by a
  `Serializable` bean, and not an observer of a `Serializable` event declared by a bean that is not. While the
  annotation processor ran the extensions, it called such a method for none of the observers the compilation compiles,
  even with `types = Object.class`.
- **Repro**: `BceRegistrationObserverInfoTest` in `vauban-core`: `[…$Ping, java.lang.CharSequence]` instead of
  `[…$Ping]`. `VetoedInterceptorCompileTimeTest#registrationGetsTheObserversOfTheEventsOfItsTypesFromTheProcessorAtStartAndFrozen`
  in `vauban-processor`: the processor sees `[]`.
- **Cause**: `BceTypeMatcher.matchesObserverTypes` loaded `observer.declaringClass()` and asked whether an expected
  type is assignable from it. The processor's class loader does not load a class the compilation compiles, the
  cause of BUG-20261117-02.
- **Fix**: `matchesObserverTypes` reads `observer.eventType()` (`BceTypeMatcher.isAssignableToAny`): a class or a
  parameterized type through its class and the classes and interfaces above it, as `matchesInterceptorTypes` reads
  them (BUG-20261117-02), whatever its type arguments; a primitive type as its wrapper class; an array as assignable
  to `Object`, `Cloneable` and `Serializable`, and to an array of one dimension less of a type it is assignable to, an
  array of primitives to an array of the same primitives only; a type variable through its bounds, one without a bound
  to `Object` only; any other type to `Object` only. A class type named after a primitive, `int` say, which the
  container gives the event type of an observer it loads by reflection (`BeanDiscovery.reflectTypeToTypeInfo`),
  where the processor reads the primitive type, is read as that primitive, as the component of an array too
  (`asPrimitives`): `@Observes int` matched `Number`, `Integer` and `Serializable` in the processor and only `Object`
  at start and frozen (379aa55e). That class type shows nowhere else in what an extension sees: `TypeMapper` already
  gives the language model a primitive type for it, and a bean's types are matched by loading them, which neither
  form passes. The first version of the fix (5c784778) matched a class or a
  parameterized type only, and gave an array or a type variable `Object` only: `@Observes String[]` was no
  `Serializable` and no `Object[]`, `@Observes T` with `T extends Number` no `Number`. No brick of the workspace
  declares an `ObserverInfo` `@Registration`; the CDI Lite TCK uses `types = Object.class`, which matches every
  observer either way.
- **Verification**: the red runs of the first version did not run on 85203ee4. They ran on the BUG-20261117-02 fix
  (the interceptor matcher and `registrationTargets` in), before the observer matcher changed, when
  `matchesObserverTypes` still loaded the declaring class: `BceRegistrationObserverInfoTest` at start
  (`[…$Ping, java.lang.CharSequence]`, `vauban-b05-red2.log`), the processor test in the processor (`[]`,
  `vauban-b05-red.log`). `BceTypeMatcherMembersTest#anObserverMatchesTheTypesOfTheEventItObserves` was written
  after the fix and not run red then; it was shown red later on 5c784778 with `matchesObserverTypes` mutated to match
  `observer.declaringClass()` (`Collection` not matched for a `List` event, `vauban-b02-r1-item3.log`). The arrays,
  type variables and primitives: red on 5c784778 (`anArrayEventMatchesTheTypesOfEveryArrayAndTheArraysOfTheSupertypesOfItsComponent`,
  `aTypeVariableEventMatchesTheTypesOfItsBoundsAndAPrimitiveThoseOfItsWrapper`, and the processor test with
  `vi.Listener.onTexts(@Observes String[])` and `vi.Gauge<T extends Number>.onReading(@Observes T)`, which no
  place matched, `vauban-b02-r1-red.log`), green after. The primitives at start: red on 379aa55e
  (`aClassTypeNamedAfterAPrimitiveMatchesAsThatPrimitive`; the processor test with `vi.Counter.onCount(@Observes int)`
  and `onCounts(@Observes int[])`, where start and frozen missed `int` for `Number`, `Integer` and `Serializable`,
  `vauban-b02-r2-red.log`), green after (`vauban-b02-r2-green.log`). At start `int[]` already matched `int[]` and
  not `Object[]` in that test; the unit test covers an array of the reflected `int`. Builds and TCK as BUG-20261117-02.
- **Found by**: BUG-20261117-02, which reads the observer matcher beside the interceptor one.

## BUG-20261117-04 — An `InterceptorInfo` has no bean type and no injection point

- **Date**: 2026-11-17
- **Status**: FIXED (2026-11-18, lane L4)
- **Module**: `vauban-core` (`VaubanBceInterceptorInfo`)
- **Symptom**: the `InterceptorInfo` a `@Registration` method gets answers an empty `types()`, `qualifiers()` and
  `injectionPoints()`, at start, frozen and in the processor. CDI 4.1 gives an interceptor's `BeanInfo` its bean types
  and its injection points, as a bean's. An extension that synthesizes a bean for each injection point it sees misses
  those of an interceptor: Ravel's `ConfigCdiExtension` for an `@Inject @ConfigProperty` field of an interceptor,
  Cervantes' `CervantesClaimExtension` for an `@Inject @Claim` one, unless a bean injects the same type.
- **Repro**: `BceRegistrationInterceptorInfoTest#anInterceptorInfoGivesTheBeanTypesAndInjectionPoints` in
  `vauban-core`, disabled until the fix: the extension sees `[]`.
- **Cause**: `VaubanBceInterceptorInfo.types()`, `qualifiers()` and `injectionPoints()` return `List.of()`;
  `InterceptorDescriptor` holds neither the bean types nor the injection points. The stubs of `VaubanBceObserverInfo`
  are BUG-20261117-09.
- **Next**: describe the interceptor as the managed bean of its class (`BceProcessor.process`'s `managedBean`), and
  audit the bricks again: Ravel, Cervantes, Champollion and Mansart read `types()` or `injectionPoints()` in
  `@Registration(types = Object.class)`. It changes what every brick's extensions see, so it gets its own change.
- **Found by**: the audit of the bricks' `@Registration` methods for BUG-20261117-02.
- **Cause** (proved): `VaubanBceInterceptorInfo.types()`, `qualifiers()` and `injectionPoints()` returned `List.of()`,
  and `BceProcessor` built it from the `InterceptorDescriptor` alone, which holds neither, on the three paths.
- **Fix**: `BceProcessor.process` describes each interceptor class with the `managedBean` function the processor and
  the container already pass for the classes `@Enhancement` makes beans (`interceptorsAsBeans`), and
  `VaubanBceInterceptorInfo` takes its bean types, qualifiers and injection points from that descriptor.
- **TCK**: `RegistrationTest` matches the interceptor `MyInterceptor` by its type and checks `isInterceptor()`; it reads
  neither the types, the qualifiers nor the injection points.
- **Verification**: red, `stack-l4-04-red.log`: "Red run 1" (at start: no type, qualifier or injection point) and
  "Red run 2" (`VetoedInterceptorCompileTimeTest#anInterceptorInfoGivesItsTypesQualifiersAndInjectionPointsFromTheProcessorAtStartAndFrozen`
  on the code of the BUG-20261117-13 commit: 3 failures, the processor, start and frozen). Green,
  `stack-l4-04-dev.log`: "Dev run 1", "Dev run 2", "Dev run 3" (vauban-core, vauban-processor, vauban-maven-plugin).
- **Bricks**: Ravel, Cervantes, Champollion and Mansart read `types()` or `injectionPoints()` in
  `@Registration(types = Object.class)`: they now see an interceptor's, as CDI 4.1 gives them.

## BUG-20261117-03 — The processor's bean types stop at a superclass the compilation compiles

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/processor-bean-supertypes`
- **Module**: `vauban-core` (`BeanDiscovery.collectBeanTypes`, `collectUnindexedSupertypes`), `vauban-processor`
  (`VaubanProcessor.process`)
- **Symptom**: in an application compiled with the annotation processor, a bean that extends a class the compilation
  compiles and the index does not hold (an abstract class without annotations, say) lacks the types that class
  brings: `vi.SerialService extends vi.Wrapping`, where `Wrapping implements Serializable`, is no `Serializable` to an
  extension the processor runs, `@Registration(types = Serializable.class)` is not called for it, and the processor
  resolves the injection points at build without that type. The container sees it at start.
- **Repro**: `VetoedInterceptorCompileTimeTest#anExtensionRunByTheProcessorSeesTheTypesABeanHasThroughASuperclassItCompiles`
  in `vauban-processor`: the start sees `serializable vi.SerialService`, the processor
  `[interceptor vi.Logger, serializable vi.Logger]`.
- **Cause** (confirmed in the code and by the tests below): `collectBeanTypes` reads a supertype the index does not
  hold by loading it (`collectUnindexedSupertypes`, through `DeploymentClasses.load`), and the processor's class
  loader does not load a class the compilation compiles: the walk stops at `vi.Wrapping`. The processor's description
  of the compilation (`IndexLookup.whereUnindexedDescribedBy`, VAU-BCE-008) was bound around the extension phases
  only: the bean discovery (`discoverBeans`, `beansAfterEnhancement`, and the discoveries again of
  `resolveFromBeanArchiveModules` and `addDependencyBeans`) ran outside it.
- **Fix**: `collectBeanTypes` reads each class through `IndexLookup.describe`: the index, then, while the processor
  runs the deployment, its description of a class the index does not hold; a class nothing describes, one of the JDK
  say, is loaded as before. The processor binds that description once around the whole deployment it processes
  (`process`, around `processDeployment`): the extension phases and the bean discovery. The container binds none, at
  start and when `vauban:generate` freezes the extensions, so there `describe` is the index and nothing changes. No
  class of the compilation is loaded, and no reflection, `ThreadLocal` or run-time generation is added. The processor
  hands its reading of the parameterized supertypes to the bean discovery through `CompiledSupertypes`, in
  `io.vidocq.vauban.core.codegen`, a package exported to the processor alone: `VaubanProcessor.init` gets it, and
  `io.vidocq.vauban.core.proxy`, through `io.vidocq.vauban.core.ProcessorAccess` where a qualified export does not
  reach the processor, javac's `--processor-module-path` in a JVM whose module path holds `vauban-core`
  (BUG-20261117-23).
- **What the build does differently**: a bean, an interceptor or a producer whose type has a class or an interface
  above it that the compilation compiles, and the index does not hold, has the types that class or interface brings,
  as at start. An injection point only such a type satisfies resolves at build, where the build failed with
  "Unsatisfied dependency"; two beans of such a type make an injection point ambiguous at build, where the build passed
  and the start refused it; `@Registration(types = Serializable.class)` gets `vi.SerialService`, the normal-scoped
  `vi.SerialStore` and the producer of `vi.Payload extends Wrapping`. The generated sources and classes name no bean
  type: for `SerialService`, `SerialStore` and the `Payload` producer, `_VaubanComponents`, the client proxies and
  `vauban-beans.list` are byte for byte those of d9144099 (`vauban-b03-probe.log`). The producer's proxy matched by
  luck there: the processor took any class of the producer's types for its proxy, which the new types made a real
  risk (BUG-20261117-15, fixed with this). Those types carry their type arguments, as at start: an injection point of
  `Repo<String>` that two beans reach through such interfaces, one a `Repo<String>` and the other a `Repo<Integer>`,
  resolves at build; raw, they made it ambiguous there (BUG-20261117-14, fixed with this, which also changes the start;
  the cases still left are BUG-20261117-16, fixed since, and -18).
- **Bricks**: a source-level audit of the workspace checkouts (`b03-audit.py`, its output in `vauban-b03-probe.log`)
  finds seven beans that gain types: Heisenberg's `DiracFtMetricsRecorder` and `OtelFtMetricsRecorder` gain
  `FtMetricsRecorder` through `DelegatingFtMetricsRecorder`, which only two `@Any Instance<FtMetricsRecorder>` fields
  inject, never validated at build; Mansart's test repository `ShelfNotes_` gains `BasicRepository` through
  `ShelfNotes`; four beans of `vauban-module-it`. Beans that extend a class of another archive, Foy's and Vidocq's
  servlets and filters, have the classes and interfaces above it as the compiler describes them when the processor
  does not load it. No injection point of the workspace names a type these beans gain, `Serializable`, a servlet or
  filter type, `EventListener` or a Jakarta Data repository, so no resolution changes. The audit does not see the
  repository implementations Mansart generates in an application build; they gain `BasicRepository<Book, Long>` and
  the interfaces above it, with their type arguments (BUG-20261117-14).
- **Verification**: red first, each on its assertion, on the main code of d9144099 with the new tests
  (`vauban-b03-red.log`, "red run 1"): `BeanDiscoveryReflectionFallbackTest#beanTypesFollowTheSupertypesTheProcessorDescribes`
  and `#producerTypesFollowTheSupertypesTheProcessorDescribes` (no `java.io.Serializable` from the processor's
  description); the repro, enabled (`[interceptor vi.Logger, serializable vi.Logger]` in the processor, right at
  start and frozen); `anInjectionPointOfATypeABeanHasThroughASuperclassItCompilesResolvesAtBuildAsAtStart`
  (`Unsatisfied dependency: field SerialClient.serial`); `twoBeansOfATypeOneHasThroughASuperclassItCompilesAreAmbiguousAtBuildAsAtStart`
  (the build resolved the injection point). The processor tests run each case in the processor, at start and frozen.
  Green: `-o -Pquality clean install` of the commit, 2587 tests, 0 failures, 0 errors, 14 skipped (`vauban-core` 1494
  with 9 skipped, the repros of BUG-20261117-04, -06, -09, -13, -16 and -18 among them; `vauban-processor` 346 with 5
  skipped, the repros of BUG-20261117-08, -10, -11, -19 and -21), `vauban-b03-build.log`, the last run; CDI Lite TCK
  774/774 and AtInject 1/1 (`-Ptck`, `vauban-b03-tck.log`, the last run). The same runs verify BUG-20261117-14, -15,
  -17 and -20. Each change fix rounds 3 and 3b make to the types at start was red first on the code before it
  (`vauban-b03-red.log`, red runs 17, 19, 20, 21 and 22; run 22, on the main code of 03db99af, for a producer of a
  generic class named raw and of an inner class of a generic class; BUG-20261117-14). Binding `CompiledSupertypes` on
  every compilation made the processor fail on every compilation where javac defines it in a layer above
  vauban-core's: red in `ProcessorAboveCoreLayerTest` on 154c46fc (`vauban-b03-red.log`, "Fix round 5 red run 24"),
  and in Foy's `FoyWebExtensionTest` in the `pr-validate` of vauban#325 (BUG-20261117-23, fixed with this).
- **Found by**: the tests of BUG-20261117-02, with a bean beside the interceptor that extends the same class.

## BUG-20261117-02 — An extension the annotation processor runs sees no interceptor the compilation compiles

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/processor-sees-interceptors`
- **Module**: `vauban-core` (`BceTypeMatcher.matchesInterceptorTypes`, `BceProcessor.processRegistration`), as
  `vauban-processor` runs them
- **Symptom**: while the annotation processor runs the build compatible extensions of a compilation, a
  `@Registration(types = Object.class)` method with a `BeanInfo` parameter is called for the beans the compilation
  compiles, and for none of its interceptors. The container calls it for the interceptors too, whether the
  extensions run at start or `vauban:generate` froze them. An extension that acts on an interceptor in
  `@Registration` does nothing for it at compile time. A method with an `InterceptorInfo` parameter is handed every
  bean as well as the interceptors, at start, frozen and in the processor, and the deployment, or the compilation,
  fails with `@Registration error: argument type mismatch`.
- **Repro**: `VetoedInterceptorCompileTimeTest#anExtensionRunByTheProcessorSeesTheInterceptorsTheCompilationCompiles`
  in `vauban-processor`: `@Registration` sees `[vi.LoggedService]`, not `[vi.LoggedService, vi.Logger]`.
  `BceRegistrationInterceptorInfoTest` in `vauban-core`: `CDI deployment validation failed: @Registration error:
  argument type mismatch`. `BceTypeMatcherMembersTest#anInterceptorThatDoesNotLoadMatchesTheTypesTheIndexAndTheProcessorDescribe`:
  an interceptor whose class does not load matches not even `Object`.
- **Cause** (confirmed by the tests above): `BceTypeMatcher.matchesInterceptorTypes` loaded the interceptor's class
  through the class loader `BceProcessor.process` gets, the processor's own (`VaubanProcessor.class.getClassLoader()`)
  when the processor runs it. A class the compilation compiles is not on it: `ClassNotFoundException`, caught, and the
  interceptor matched no type, `Object` included. `processRegistration` leaves the interceptors out of the bean loop,
  so nothing else reported them. A bean matches through its bean types, `java.lang.Object` among them, which load.
  Separately, `processRegistration` handed a method of any parameter the beans it matched, and
  `resolveRegistrationArgs` gives a `BeanInfo` to an `InterceptorInfo` parameter, which `Method.invoke` refuses.
- **Fix**: `matchesInterceptorTypes` reads the interceptor's class and the classes and interfaces above it through
  `IndexLookup.describe`: the index, and, while the processor runs the extension phases, its own description of the
  classes the compilation compiles (the `unindexedResolver` of VAU-BCE-008). It matches by name, and loads only a class
  nothing describes, one of the JDK say, as before; no class of the compilation is loaded. A method with an
  `InterceptorInfo` parameter is called for the interceptors only, never for a bean nor for an archive class
  (`BceProcessor.registrationTargets`). The processor, the container at start and `vauban:generate` that freezes the
  extensions now call a method for the same interceptors and observers. They still differ on the classes that are no
  beans a `BeanInfo` method gets at start when no bean has one of its types (BUG-20261117-06). They differed on a
  second point, the processor's bean types of a bean that extends a class the compilation compiles, until
  BUG-20261117-03 was fixed on `fix/processor-bean-supertypes`.
- **Bricks**: five extensions of the workspace declare a `@Registration`, all with a `BeanInfo` parameter. At compile
  time they are now called for the interceptors the compilation compiles too, with an `InterceptorInfo` whose `types()`
  and `injectionPoints()` are empty (BUG-20261117-04). Ravel's `ConfigCdiExtension` (`Object`, `Config`), Cervantes'
  `CervantesClaimExtension` and Champollion's `JsonbCdiExtension` (`Object`) read only those, and do nothing new.
  Mansart's `MansartDataExtension` (`Object`) adds the interceptor's class to `managedBeanClasses`, which it reads for a
  repository implementation only. Foy's `FoyWebExtension` (`Servlet`, `Filter`, `EventListener`) is called for an
  interceptor of those types only, and records it only with a `@WebServlet`, `@WebFilter` or `@WebListener`. None
  throws, registers twice or synthesizes a bean twice; the container already made these calls at start.
- **Verification**: red first, each on its assertion, on the main code of 85203ee4 plus one method that did nothing
  new: a 4-argument `matchesInterceptorTypes(types, interceptor, lookup, loader)` that delegated to the 2-argument
  matcher of 85203ee4, so the new unit tests compiled. The `vauban-core` runs used that code; the `vauban-processor`
  runs used it installed in the separate local repository (`vauban-b02-red.log`: the unlabelled runs at its top,
  `vauban-core` then `vauban-processor`, and "red run 5"; "red run 4" is a compilation failure and does not count).
  `anExtensionRunByTheProcessorSeesTheInterceptorsTheCompilationCompiles`, enabled: `[vi.LoggedService]`;
  `anExtensionRunByTheProcessorSeesNothingOfAVetoedPackage`, filled with the live interceptor: `[vi.LoggedService]`;
  `registrationGetsTheSameInterceptorsFromTheProcessorAtStartAndFrozen` (an `InterceptorInfo` method over `Object`,
  a `BeanInfo` method over `Serializable`, which the interceptor and its vetoed sibling have through `vi.Wrapping`,
  an abstract class the compilation compiles and the index does not hold): `argument type mismatch` in the processor;
  `BceRegistrationInterceptorInfoTest`: `argument type mismatch` at start; the two `BceTypeMatcherMembersTest`
  interceptor cases: no match. Not on 85203ee4: `anInterceptorInfoParameterIsGivenNoClassOfTheDeploymentThatIsNoInterceptor`,
  on the fix with the archive guard removed: `argument type mismatch` ("red run 6"); the interceptor binding case
  (`@Registration(types = Traced.class)` with an `InterceptorInfo` parameter, no call), at start and in the three
  places, on the fix with `matchesInterceptorTypes` mutated to match every type (`vauban-b02-r1-item9.log`). The processor test runs each extension in the processor, at start
  and frozen (`vauban:generate`'s `freezeExtensions`), then starts the frozen deployment, where it does not run. Green:
  `-o -Pquality clean install`, 2524 tests, 0 failures, 0 errors, 9 skipped (`vauban-core` 1461 with 6 skipped, the
  repros of BUG-20261117-04, -06 and -09 among them; `vauban-processor` 316 with 3 skipped, the repros of
  BUG-20261117-03, -08 and -10); CDI Lite TCK 774/774 and AtInject 1/1 (`-Ptck`).
- **Found by**: BUG-20261117-01, whose processor test records what `@Registration` sees.

## BUG-20261117-01 — A package-level `@Vetoed` is ignored in a compiled application

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/vetoed-package`
- **Module**: `vauban-processor` (`VaubanProcessor`, `ElementScanner`), `vauban-core` (`VaubanContainerBuilder`,
  `BceProcessor`, `PackageInfos`)
- **Symptom**: in an application compiled with the annotation processor, the classes of a package whose
  `package-info.java` carries `@Vetoed` are beans, and an interceptor among them intercepts. CDI 4.1 ignores them
  altogether. So are the classes of such a package that the container scans, and those an extension adds through
  `ScannedClasses`. The build of an application resolves an injection point with a bean of a vetoed package of
  another module, which the start then finds unsatisfied. A deployment that hands the container the `package-info`
  class itself, as the CDI TCK runner does, skips them.
- **Repro**: `VetoedInterceptorCompileTimeTest#aVetoedPackageHoldsNoBeanAndNoInterceptor` in `vauban-processor`:
  `vi.quiet.QuietBean` is a bean, and `vi.quiet.QuietLogger` intercepts `vi.LoggedService`
  (`Logger(QuietLogger(hello))`). `VetoedInterceptorTest` in `vauban-core`, for a package `scanPackage` scans, a
  directory and a jar with a `beans.xml` no Vauban build step processed, the classes an extension adds through
  `ScannedClasses` from a jar with no `beans.xml`, and a `vauban-beans.list` that names them:
  `overseer(package(call))`. `BeanArchiveModuleInjectionTest#aProducerOfAVetoedPackageOfAnotherModuleSatisfiesNothing`
  in `vauban-processor`: `Resolved io.repro.api.Exchange with io.repro.quiet.ExchangeBeans from a bean archive
  module`.
- **Cause**: `BeanDiscovery.isVetoed` reads a package's `@Vetoed` on the `package-info` class of the index, and no
  path but the one that hands the container the `package-info` class put it there. The processor indexes types only,
  never a package, so it lists the classes of a vetoed package in `vauban-beans.list`, and the container of a
  compiled application indexes what that list names. The class path scanner leaves `package-info` out, as an
  interface and, in a jar, by name (`ContainerScanner.tryAddArchiveClass`, `scanJarEntries`, `tryAddBeanClass`). A
  class an extension adds through `ScannedClasses` is indexed alone, by the container
  (`VaubanContainerBuilder.addExtensionScannedClass`) and by the processor (`VaubanProcessor.runDiscoveryPhase`), and
  so are the types of another module the processor resolves an injection point with
  (`VaubanProcessor.scanBeanArchiveModules`): those of a package with a `_VaubanComponents`, which the current
  processor writes for a vetoed package that declares an annotation type or holds a produced-type proxy, and those an
  earlier processor listed in `vauban-beans.list`.
- **Fix**: the index holds the `package-info` of every vetoed package a type of the deployment belongs to.
  - The processor indexes the `package-info` of a type's package when the package carries `@Vetoed`
    (`VaubanProcessor.indexVetoedPackage`), for each type it indexes from its annotations and for each type an
    injection point brings in. `ElementScanner.scan(PackageElement)` builds it as the bytecode scan reads
    `package-info.class`: a synthetic interface named `<package>.package-info` with the package's annotations. For a
    class of another archive, one an extension adds through `ScannedClasses` or one of another module that the
    processor resolves an injection point with (`scanBeanArchiveModules`), it reads the package's `@Vetoed` through
    `Elements`, which sees a `package-info` the compilation compiles or finds on its class path or module path, and
    else the `package-info.class` on the processor path (`PackageInfos.vetoed`). Discovery then skips the package's
    classes on the processor as in the container: the processor lists none of them in `vauban-beans.list`, generates
    no client proxy, no `$$Intercepted` and no `_VaubanComponents` for them, its extensions' `@Registration` sees
    none of them, an injection point that needs one is unsatisfied at build, as at start, and no bean of a vetoed
    package of another module satisfies one. The processor does not list the `package-info` itself.
  - The container looks up the `package-info.class` of the package of each class it indexes, through the class's
    loader, once per package, and indexes it when it carries `@Vetoed` (`VaubanContainerBuilder.indexVetoedPackages`,
    `PackageInfos.vetoed`), whatever way the class came: `addBeanClass`, `scanPackage`, a `beans.xml` archive, a
    `vauban-beans.list` (one from a processor without this fix lists the classes of a vetoed package), or
    `ScannedClasses` (`addExtensionScannedClass`). The fingerprint of a frozen deployment counts it as it counts the
    class that brought it: the `package-info` of a package only the runtime's own beans (`addHostBean`) belong to
    stays out, as they do, and the veto holds on them all the same. `addBeanClass` given a `package-info` keeps it
    apart from the bean classes, and looks it up the same way. The class path scanner is unchanged.
  - A `package-info` is no type: no extension phase sees one. `BceProcessor` leaves the `package-info` classes out of
    the archive classes `@Enhancement` and `@Registration` are given, and out of the index classes `@Enhancement`
    matches (`typesOf`, `knownTypes`). `vauban:generate` indexes every class file but `module-info`, so on main an
    `@Enhancement(types = Object.class)` it runs is given the `package-info` of every annotated package
    (`@XmlSchema`, `@NullMarked`). The container never indexes one that does not veto its package, and counts none
    among the classes of an archive.
- **Verification**: red before the fix, green after:
  - in `vauban-processor`, `VetoedInterceptorCompileTimeTest`'s `aVetoedPackageHoldsNoBeanAndNoInterceptor`,
    `theExtensionsOfACompiledApplicationSeeNothingOfAVetoedPackage` (run at start and frozen at build),
    `anExtensionRunByTheProcessorSeesNothingOfAVetoedPackage`, `aTypeOfAVetoedPackageAnInjectionPointNeedsIsNoBean`,
    `aClassOfAVetoedPackageAnExtensionScansIsNoBean` (the processor listed `ScannedQuietBean`) and
    `aClassAnExtensionScansIsNoBeanWhenTheCompilationVetoesItsPackage` (the compilation compiles the `package-info`,
    which the processor path does not hold); `BeanArchiveModuleInjectionTest`'s
    `aProducerOfAVetoedPackageOfAnotherModuleSatisfiesNothing`, for a module the application reads, whose vetoed
    package has a `_VaubanComponents`, and for one on the processor path whose `vauban-beans.list` names the vetoed
    producer (the build resolved the injection point with it; it now stays deferred to the container);
  - in `vauban-core`, `VetoedInterceptorTest` for `scanPackage`, a directory and a jar archive, `ScannedClasses` and a
    hand-written `vauban-beans.list`, with the extensions run at start and frozen at build (ten cases; the two where
    the deployment is handed the `package-info` passed before), and
    `VetoedInterceptorTest#aHostBeanOfAVetoedPackageLeavesTheRecordValid` (the start refused the record:
    `io.vidocq.vauban.core.container.vetoed.package-info (added)`);
  - in `vauban-maven-plugin`, `VetoedPackageGenerationTest#anEnhancementIsGivenNoPackageInfo`;
    `VetoedPackageGenerationTest#aVetoedPackageHoldsNoBean` passes before and after, and fails when the
    `package-info` is left out of the classes it scans.

  `PackageInfoIsNoTypeTest` in `vauban-core`, run at start and frozen at build, guards against a regression the first
  version of this fix brought, not a bug of main: that version added every `package-info` of a scanned archive to the
  deployment, and `@Enhancement` was given that of an annotated and of a vetoed package. On main the scanner leaves
  `package-info` out.

  `ElementScannerPackageTest` checks the processor's `package-info` against the bytecode scan's, `PackageInfosTest`
  the lookup. The reactor `-o -Pquality install` (2505 tests, 4 skipped), the CDI Lite TCK (774/774) and the AtInject
  TCK (1/1) green on the branch.
- **Found by**: BUG-20261116-12, whose tests cover a class of a vetoed package.

## BUG-20261116-12 — A `@Vetoed` interceptor still intercepts

- **Date**: 2026-11-17
- **Status**: FIXED in vauban#321
- **Module**: `vauban-core` (`InterceptorDiscovery`)
- **Symptom**: an interceptor class annotated `@Vetoed`, or a class of a package annotated `@Vetoed`, is no bean, as
  it should be, but it still intercepts the beans its binding names, and an extension's `@Registration` sees it as an
  interceptor. In a deployment whose classes the container indexes itself: classes added with `addBeanClass`, a
  `beans.xml` archive no Vauban build step handled, a class an extension scans; with its extensions run at start or
  frozen at build.
- **Repro**: `BuiltInInjectableReferenceTest#aVetoedInterceptorInterceptsNothing` (`VetoedAnnouncer` fires its `Ping`
  on `Shouter.shout()`), and `VetoedInterceptorTest` (a vetoed class and a class of a vetoed package, extensions run
  at start and frozen at build), in `vauban-core`.
- **Cause**: `BeanDiscovery` skips a vetoed class or a class of a vetoed package, `InterceptorDiscovery` did not:
  it made an interceptor of any class annotated `@Interceptor`. CDI 4.1 ignores a vetoed type altogether, an
  interceptor included. The first reading also blamed the annotation processor, thought to discover interceptors on
  its own to weave the `$$Intercepted` subclasses. It does not: the processor and `vauban:generate` weave a bean by
  its bindings, and the subclass calls the chain the container resolves at run time. The processor discovers
  interceptors only for its extensions' `@Registration`, through `BeanDiscovery`, as the freezing of the extensions
  does; `vauban:generate` discovers none. A compiled application was not affected: the processor lists no vetoed
  class in `vauban-beans.list`, the classes its container indexes.
- **Fix**: `InterceptorDiscovery.discoverInterceptors` skips what `BeanDiscovery.isVetoed` skips. The build tools
  need no change of their own. The container still creates an interceptor by itself, outside the bean path, when its
  creation as a bean fails, and so creates the `@ActivateRequestContext` interceptor, which has no bean: an
  instrumented run of the core tests and of the CDI Lite TCK found no other case. The test of BUG-20261116-07 for an
  interceptor's `@Loud Event<Ping>` constructor parameter moved from the vetoed interceptor onto one whose creation
  as a bean fails once (`anInterceptorCreatedByTheContainerKeepsItsEventQualifiers`), which fails if that parameter
  loses its qualifier.
- **Verification**: the three tests red before (`[shouted]`, and `overseer(vetoed(package(call)))` with the extensions
  run at start and frozen at build), green after. `VetoedInterceptorCompileTimeTest#theSubclassOfABoundBeanCallsNoVetoedInterceptor`
  in `vauban-processor` passes before and after. The reactor `-o -Pquality install`, the CDI Lite TCK (774/774) and
  the AtInject TCK green on the branch.
- **Found by**: the review of BUG-20261116-07, which looked for an application interceptor with no bean: a vetoed one
  is the only one.

## BUG-20261116-11 — Overloaded producer methods collide: the deployment fails

- **Date**: 2026-11-17
- **Status**: FIXED in vauban#320
- **Module**: `vauban-core` (`BeanId`, `BeanDiscovery.buildProducerMethodBean`, and the producer method lookups of
  `VaubanContainer`, `ManagedBean`, `DisposerInvoker`, `CodegenCoverage`)
- **Symptom**: a class with two producer methods of one name, `@Produces Spoon make()` and
  `@Produces Fork make(Spoon spoon)`, fails the deployment: `Illegal circular dependency involving @Dependent bean`,
  naming `Kitchen#make` twice. On `main` too.
- **Repro**: `OverloadedProducerTest` in `vauban-core`.
- **Cause**: a producer method's id is `Class#method`, so overloaded producer methods share one: in the registry, a
  `Map` keyed by id, one overwrote the other, and the validator saw one bean depend on itself. Each lookup of the
  producer's `Method`, its factory, its return type, its annotations for the disposer, its codegen coverage, found
  it by name alone, so it took the first overload.
- **Fix**: one of overloaded producer methods has the id `Class#method(T1,T2)`, its erased parameter types as
  `Class.getTypeName()` spells them (`BeanId.ofOverloadedProducerMethod`); a producer method of a name of its own
  keeps `Class#method`. `VaubanContainer.isProducerMethod` finds the method an id names, by its name and, for an
  overload, its parameter types, wherever the container looks it up. The ids of other producers, and the frozen and
  generated records, do not change.
- **Verification**: `OverloadedProducerTest` (2) red before, green after; the reactor `-o -Pquality install`, the CDI
  Lite TCK (774/774) and the AtInject TCK green on the branch.

## BUG-20261116-10 — A `Provider` injection point has no bean for `getInjectableReference`

- **Date**: 2026-11-17
- **Status**: FIXED in vauban#320
- **Module**: `vauban-core` (`VaubanBeanManager.getBuiltInBeans`)
- **Symptom**: `BeanManager.getInjectableReference` for a `Provider<Shelf>` injection point throws
  `UnsatisfiedResolutionException: No bean for injection point`, and `getBeans` of a `Provider<X>` type finds no
  bean. Field, constructor and initializer injection of a `Provider` worked: they build the object from the
  injection point and never resolve a bean, which is why the AtInject TCK passes.
- **Repro**: `BuiltInInjectableReferenceTest#aProviderFieldKeepsItsType` in `vauban-core`.
- **Cause**: the built-in `Instance` bean's types were `{Instance}`. CDI 4.1 §5.6.2 ("The built-in Instance") makes
  it eligible for any injection point of type `Instance<X>` or `Provider<X>`.
- **Fix**: the built-in `Instance` bean has the types `{Instance, Provider}`.
- **Verification**: `aProviderFieldKeepsItsType` red before, green after; the reactor `-o -Pquality install`, the CDI
  Lite TCK (774/774) and the AtInject TCK green on the branch.

## BUG-20261116-09 — `Instance.select` drops a parameterized type

- **Date**: 2026-11-16
- **Status**: FIXED in vauban#320
- **Module**: `vauban-core` (`InstanceImpl.select(TypeLiteral, Annotation...)`, `InstanceImpl.select(Annotation...)`)
- **Symptom**: `BeanManager.createInstance().select(new TypeLiteral<Supplier<String>>() { })`, or the same `select` on
  an injected `Instance`, throws `ClassCastException: ParameterizedTypeImpl cannot be cast to Class`, though
  `TypeLiteral` exists to select a parameterized type. Once past that, `select(qualifiers)` on an
  `Instance<Supplier<String>>`, injected or selected by a `TypeLiteral`, resolves the raw `Supplier`: no bean.
- **Repro**: `InstanceImplTest#shouldSelectAParameterizedType` and
  `#shouldKeepAParameterizedTypeThroughSelectQualifiers` in `vauban-core`.
- **Cause**: `select(TypeLiteral)` cast `subtype.getType()` to `Class`, which holds for a class literal only;
  `select(Annotation...)` built its child with the constructor that takes the class alone, which drops the whole type.
- **Fix**: the child `Instance` takes `subtype.getRawType()` as its class and `subtype.getType()` as the type it
  resolves its beans with, as an injected `Instance<Supplier<String>>` already did, and its injection point takes the
  whole type too; `select(Annotation...)` passes the whole type on.
- **Verification**: the two tests red before (`ClassCastException`, then `UnsatisfiedResolutionException` for
  `java.util.function.Supplier`), green after; the reactor `-o -Pquality install`, the CDI Lite TCK (774/774) and the
  AtInject TCK green on the branch.

## BUG-20261116-08 — The injection points of a class that declares a producer can belong to the producer

- **Date**: 2026-11-16
- **Status**: FIXED in vauban#320
- **Module**: `vauban-core` (`VaubanContainer.findBeanForInstance`)
- **Symptom**: when the producer method, producer field or synthetic bean a class declares comes before the class's
  own managed bean in the container's registry, the injection of an instance of that class takes the producer as the
  owner of its injection points: `InjectionPoint.getBean()` names the producer, and an `Event` or an `Instance` it
  injects writes the producer's passivation id instead of the class's, so the importing container looks its injection
  point up on the producer.
- **Repro**: `BuiltInInjectableReferenceTest#theOwnerIsTheManagedBeanWhenItsProducerComesFirst` in `vauban-core`
  moves the managed bean of `Workshop`, which declares a producer method, after that producer in the registry.
- **Cause**: `findBeanForInstance` returned the first bean of the registry whose bean class is the instance's class,
  whatever its kind, and a producer's bean class is the class that declares it. The registry follows the order the
  descriptors were registered in, which a build compatible extension or another registration pass can change.
- **Fix**: `findBeanForInstance` prefers the class's managed bean, wherever it comes; a class with no managed bean
  keeps the first bean it declares, as before.
- **Verification**: `theOwnerIsTheManagedBeanWhenItsProducerComesFirst` red before (the owner was `Workshop#tool`),
  green after, with `aClassWithNoManagedBeanIsOwnedByABeanItDeclares` for the old behaviour; the reactor
  `-o -Pquality install`, the CDI Lite TCK (774/774) and the AtInject TCK green on the branch.

## BUG-20261116-07 — `getInjectableReference` drops the type and the qualifiers of a built-in `Event` or `Instance`

- **Date**: 2026-11-16
- **Status**: FIXED in vauban#320
- **Module**: `vauban-core` (`VaubanBeanManager.getInjectableReference` and `getReference`,
  `VaubanContainer.injectedBuiltIn` and `lookedUpBuiltIn`, `InstanceImpl.get`, `EventDispatcher.observerArguments`,
  `InterceptorBeanWrapper.interceptorConstructorArg`)
- **Symptom**: `BeanManager.getInjectableReference` for an `Instance<Shelf>` injection point gives an
  `Instance<Object>`, whose `get()` throws `AmbiguousResolutionException`; for a `@Loud Event<Ping>` injection point,
  an `Event` that fires with `@Default` only, so the `@Loud` observers never see it. `getReference` of the built-in
  `Instance` bean for `Instance<Shelf>` gives an `Instance<Object>` too. A `@Loud Event<Pong>` parameter of an
  observer method fires with `@Default` as well, and so do the `Event` a `@Loud Instance<Event<Ping>>` gives and a
  `@Loud Event<Ping>` constructor parameter of an interceptor the container creates with no bean.
- **Repro**: `BuiltInInjectableReferenceTest` in `vauban-core`: an `Instance` field and constructor parameter, a
  qualified `Event` field, `getReference` for `Instance<Shelf>`, an observer method's qualified `Event` parameter, a
  `@Loud Instance<Event<Ping>>`, an interceptor with no bean.
- **Cause**: the built-in `Event` and `Instance` beans create their object with `BeanManager.getEvent()` and
  `createInstance()`, which know neither the injection point's type nor its qualifiers, and `getReference`, the
  observer's arguments, `Instance.get()` and an interceptor's constructor went through those beans. Field,
  constructor and initializer injection never did: they build the object from the injection point.
- **Fix**: `getInjectableReference` of a built-in `Event`, `Instance` or `Provider` returns what an injection at its
  injection point gives (`VaubanContainer.injectedBuiltIn`). `getReference` takes a bean, a bean type and a creational
  context, no injection point (CDI 4.1 §13.3.2, "Obtaining a contextual reference for a bean"): it returns one of the
  requested type with `@Default` (`VaubanContainer.lookedUpBuiltIn`), a programmatic lookup, whose `Event` fires with
  no injection point in its metadata. `Instance.get()` gives one with the `Instance`'s qualifiers. A first version of
  the fix read the injection point being injected when it had the requested type, so a `getReference` during an
  unrelated injection took that injection point's qualifiers, and the `get()` of an injected `Instance<Event<Ping>>`
  returned the `Instance` of the field instead of an `Event`. An observer method's `Event` parameter is built from the
  parameter's qualifiers, and fires with `@Default` when it has none; it has no injection point, so it cannot move
  with a session and says why. An interceptor's `Event`, `Instance` or `Provider` constructor parameter is built from
  the parameter, as a bean's is.
- **Verification**: `BuiltInInjectableReferenceTest` red before for each case (the type, the qualifiers, the
  injection point of a looked-up `Event`, the `Instance<Event<Ping>>` regression), green after; the reactor
  `-o -Pquality install`, the CDI Lite TCK (774/774) and the AtInject TCK green on the branch.

## BUG-20261116-03 — `vauban:enhance-dependencies` leaves the versioned descriptor of a multi-release jar as it is

- **Date**: 2026-11-16
- **Status**: FIXED on `fix/enhance-versioned-descriptor`
- **Module**: `vauban-maven-plugin` (`DependencyEnhancer.copyEntry`)
- **Symptom**: the enhanced copy of a multi-release jar whose module descriptor is only under
  `META-INF/versions/<n>/` (a library that still runs on Java 8) holds the generated `<Type>_ClientProxy` and
  `_VaubanComponents`, but its module neither provides `VaubanComponentProvider` nor requires `io.vidocq.vauban.api`.
  On the module path no `ServiceLoader` finds the provider, so the container falls back to generating the proxy at
  run time and asks for the `opens` the goal was run to avoid. Found by reading the code while Vidocq's
  `vidocq:jlink` started to call the enhancer (Vidocq BUG-20261116-06), not reported by a user.
- **Repro**: `DependencyEnhancerTest.enhancesTheVersionedDescriptorOfAMultiReleaseJar`: a jar with
  `Multi-Release: true`, `META-INF/versions/9/module-info.class` and no root descriptor. Before the fix,
  `ModuleFinder` reads from the enhanced copy a module that requires `java.base` only.
- **Cause**: `copyEntry` rewrites the entry named `module-info.class` and copies every other entry as it is. The
  module system reads the versioned descriptor of a multi-release jar.
- **Fix**: `copyEntry` rewrites every module descriptor of the jar, the root one and the versioned ones
  (`isModuleDescriptor`). `internals.adoc` says so.

## BUG-20261116-02 — A `@Decorator` is never applied: decorators (CDI Full) are not implemented

- **Date**: 2026-11-16
- **Status**: OPEN — waits for the CDI Full work (stage F4 of
  `docs/superpowers/plans/2026-10-19-cdi-4.1-full-study.md`, Vidocq/vauban#38); decided 2026-11-16, no early slice
- **Module**: `vauban-core` (`VaubanBeanManager.resolveDecorators`, `VaubanSeContainerInitializer.enableDecorators`,
  `DeploymentValidator`), `vauban-processor` (no generation for decorators)
- **Symptom**: a class annotated `@Decorator` with an `@Inject @Delegate` injection point decorates nothing: calls
  reach the bean directly, with no error and no warning. Found by the Jakarta Security TCK application
  `app-mem-basic-decorate`, which decorates `HttpAuthenticationMechanism`, a synthetic bean of Kerckhoffs: 1 test of
  2 fails (KERCK-20261116-01, Vidocq/kerckhoffs#29).
- **Minimal repro**: the Security TCK application above. No Vauban test yet: the CDI TCK runs with
  `excludedGroups=integration,javaee-full,se,cdi-full`, so no decorator test runs.
- **Cause**: not implemented. `resolveDecorators` returns an empty list, `enableDecorators` does nothing, the
  processor generates nothing for a decorator, and the `<decorators>` section of `beans.xml` is not read.
  `BeanDiscovery` only knows `jakarta.decorator.Decorator` as a bean defining annotation. Not checked yet: what the
  container does with the decorator class itself (an ordinary bean of the decorated type would be a latent
  ambiguity).
- **Investigations**:
  - 2026-11-16: read-only study for Kerckhoffs. Feasible at build without a run-time proxy: a generated subclass
    in the shape of `$$Intercepted` for a managed bean of a class type; a chain of decorator instances for a bean
    exposing interfaces only. Open contract question: CDI requires decoration of managed and built-in beans, not of
    synthetic ones, so decorating a synthetic bean (opt-in on the builder, or general) is Vauban's decision.
    `app-mem-basic-decorate` is a ready acceptance test for F4. The documentation said "supported in Lite"
    (`migration.adoc`) and left a TODO (`reference.adoc`); both now say unsupported.

## BUG-20261116-01 — The "extension not on the processor path" check reads one class-path service file, in a directory only

- **Date**: 2026-11-16
- **Status**: LIMIT EXPLICIT when a warning or hint is printed, on `fix/processor-all-bce-service-files`; the check stays partial (dependency jars, and the other entries of a class path whose first entry is the module's own output and holds a service file, are never checked; no supported API lists the class path). In practice the vauban#29 class-path warning fires only for directories, and never for a dependency jar.
- **Module**: `vauban-processor` (`VaubanProcessor.unloadableBcesOnCompilePath`, vauban#29)
- **Symptom**: with several entries on the compile class path that declare build compatible extensions, only the first
  one's are reported as unable to run; the others never run at build, with no warning. Worse, an extension declared in
  a **jar** of the class path is never seen at all. And when the module's own output, which Maven puts first on the class
  path, has a service file (a module that provides an extension), it hides every other entry: `isBuildOutput` leaves it
  out (BUG-20261115-05), so nothing is reported and nothing is said.
- **Repro**: `BceProcessorPathDiagnosticTest.onlyTheFirstServiceFileOfTheClassPathIsVisible` (two directories) and
  `aJarsServiceFileIsNotVisible` (one jar) and `ownOutputFirstHidesTheOtherEntries` (own output first); same result with a bare `javac -processor` probe.
- **Cause**: the class-path half of the check calls `Filer.getResource(CLASS_PATH, "", BCE_SERVICE_PATH)`, which returns
  the first match among the directories of the class path and never one in a jar (`FileNotFoundException`, also for
  `META-INF/MANIFEST.MF`). The module-path half (`provides` directives) is complete.
- **Fix**: no supported API lists class-path resources (`Elements.getFileObjectOf` gives the jar of a type javac has
  already looked at, not of one nobody references). When the probe finds a service file and a warning or a hint is printed anyway, they
  say that only that file is visible, name its entry (and that it is the module's own
  output, when it is), and tell to use
  `<annotationProcessorPaths>` or the module path. Documented in `concepts.adoc`. Nothing is printed for a module whose
  own output comes first, with a service file, and nothing else to report: that case stays silent on purpose, a
  standalone warning would fire in every module that provides an extension, and its other class-path entries stay
  unchecked.

## BUG-20261115-09 — A modularized library that looks up a type its callers give it fails in an explicit module

- **Date**: 2026-11-15
- **Status**: FIXED on `fix/modularize-service-loader-uses`
- **Module**: `vauban-maven-plugin` (`modularize`: `Modularizer`, new `ServiceLoaderCalls`, `ConstantPoolPatch`)
- **Symptom**: the `vidocq:jlink` image of a Vaadin application serves its page, but the view never renders:
  `ServiceConfigurationError: io.vidocq.tools.vaadin.MainView: module flow.server does not declare uses`, from
  `com.vaadin.flow.di.LookupInitializer$LookupImpl.lookup`. Found by the Vaadin session (Vidocq/vidocq#598).
- **Repro**: `ServiceLoaderCallsTest`: a library whose `Lookup.find(Class)` calls `ServiceLoader.load(type)`, patched,
  and an application module that requires it and passes its own class.
- **Cause**: an explicit module may only load the services it declares. The type here is the application's, in a
  module that depends on the library: a `uses` of it would need a `requires` back, so `UsesLegality` drops it (or the
  scan never sees it, the type being a parameter). Vidocq's jlink image makes such jars explicit (`forceExplicit`,
  Vidocq/vidocq#580).
- **Fix**: each patched jar has its `ServiceLoader.load` / `loadInstalled` method references redirected, in the
  constant pool, to a generated `VaubanServiceLoader` class of the same package, which calls `Module.addUses` from the
  module itself, then the original method. The code and its stack maps are left as they were: rewriting the code
  made the Class-File API recompute stack maps, which needs the hierarchy of the library's types.

## BUG-20261115-08 — An encrypted jar in a directory whose name holds a space or a non-ASCII character is not found

- **Date**: 2026-11-15
- **Status**: FIXED on `fix/bce-processed-source-location`
- **Module**: `vauban-core` (`ContainerScanner.scanEncryptedJarsOnClasspath`)
- **Symptom**: an encrypted jar (`META-INF/vauban.header`) on the class path under a directory such as `with space été/`
  gives no bean. The start logs `Failed to load encrypted JAR secret.jar` with
  `NoSuchFileException: …/with%20space%20%C3%A9t%C3%A9/secret.jar`, and goes on without its beans. Found while fixing
  BUG-20261115-07.
- **Repro**: `ContainerScannerResourcesTest.encryptedJarUnderAnEscapedPath`: a `URLClassLoader` over
  `with space été/secret.jar` (header and a `bean-discovery-mode="all"` `beans.xml`), a plugin that opens it,
  `scanClasspath()`: the fixture was not among the bean classes.
- **Cause**: the scanner took the jar's path as the text of the header's URL between `jar:file:` and `!`, without
  decoding it: `Path.of("/…/with%20space%20%C3%A9t%C3%A9/secret.jar")` names a file that does not exist.
- **Fix**: `ProcessedArchives.localJarOf` gives the local jar of a `jar:file:` resource with its path decoded, as the
  location of BUG-20261115-07 is; a resource of a directory or of a nested jar gives none, as before.
- **Verification**: `encryptedJarUnderAnEscapedPath` red before, green now; `ProcessedArchivesTest.localJarOf` on the
  escaped, `file:///`, directory and nested spellings. With BUG-20261115-07 on the same branch: `vauban-core` 1354
  tests, 0 failures, 3 skipped; the reactor's `-Pquality,errorprone clean install` 2315 tests, 0 failures.

## BUG-20261115-07 — Under the Vauban class loader or on the module path, a jar processed at compile is not recognised

- **Date**: 2026-11-15
- **Status**: FIXED on `fix/bce-processed-source-location`
- **Module**: `vauban-core` (`ContainerScanner.scanClasspath`, `VaubanContainerBuilder.planExtensions`)
- **Symptom**: a packaged Vidocq application whose bean archives were all processed by `vauban-processor` at compile
  (each carries `META-INF/vauban-bce-processed`) still runs its build compatible extensions at every start; under
  `vidocq.startup.require-frozen-extensions=true` it refuses to start (VIDOCQ-GEN-003). A servlet-only application,
  Foy's `FoyWebExtension` processed at compile and nothing else, is one. Found by a Vidocq integration test (Vidocq
  BUG-20261115-05).
- **Repro**: `ProcessedArchiveLocationTest`: a jar with a bean, its index, an extension's service file and the
  marker, loaded through `VaubanClassLoader.of(...)`: `extensionsAtStart().ranAtStart()` named the extension. So did
  the same jar as module `m.app` in a module layer. The same jar under a `URLClassLoader`, a directory of classes, or
  an exploded module, ran none.
- **Cause**: `scanClasspath` recorded each marker's archive as the text of its URL, the `jar:` prefix and the entry
  cut off, and `isClassFromBceProcessedSource` looked a class's code source up in that set by its text. The Vauban
  class loader writes a jar's entry `jar:file:///a/app.jar!/META-INF/vauban-bce-processed` (`Path.toUri()`) and the
  code source of its classes `file:/a/app.jar` (`URI.toURL()`): one file, two spellings, never equal. A
  `URLClassLoader` writes both from the URL it was given, so they matched. The module path writes them as the Vauban
  class loader does: a jar module's entry `jar:file:///…/m.app.jar!/…`, its classes' code source `file:/…/m.app.jar`;
  an exploded module matched. Every class looked unprocessed, and `planExtensions` ran every extension. The mismatch
  was noted when the loader got its code sources (BUG-20261013-01, with the `vauban:` URLs of that time) and left for
  a separate change.
- **Fix**: `ProcessedArchives` records and compares locations, not strings: the archive of a `jar:` or `vauban:` URL
  is the URL inside it, a `file:` URL is the normalised `Path` it names (escaped or raw, a space or a non-ASCII
  character included), and what names no local file (a `jrt:` module, a nested jar, a file URL with a host) is its
  normalised URI without a trailing slash. A jar nested in another stays apart from the outer jar.
- **Verification**: `ProcessedArchiveLocationTest` (9: the Vauban class loader with a jar, a directory, a path with a
  space and `été`; a `URLClassLoader` with an escaped URL, and a raw one as a no-regression case; a jar module and an
  exploded module in a module layer; an unprocessed jar and a processed jar beside an unprocessed one still run the
  extension): 3 red before (the Vauban class loader's jar, the same under a path with a space and `été`, the jar
  module), all green now. `ProcessedArchivesTest` (16) on the spellings and on `holds`.
- **Message**: the build of such a deployment said `the deployment has no build compatible extension`
  (`FrozenExtensions.NO_EXTENSION`), which it has. It now says `every build compatible extension of the deployment ran
  at compile` (`FrozenExtensions.ALL_AT_COMPILE`), and `ExtensionFreezer.report` writes `nothing to freeze: <reason>`
  for both, at info level (`Outcome.nothingToFreeze()` covers both). `NO_EXTENSION` keeps its text and meaning: Vidocq's
  `VidocqGenerateMojo` and `VidocqGenerateFreezeTest` read them. Covered by
  `ProcessedArchiveLocationTest.freezingAProcessedDeployment` (red before) and `freezingADeploymentWithoutAnExtension`,
  and `ExtensionFreezerTest.theReportWhenNothingIsToFreeze`.

## BUG-20261115-06 — A module whose only processor is Vauban gets "created in the last round" warnings again

- **Date**: 2026-11-15
- **Status**: FIXED on `fix/processor-build-noise`
- **Module**: `vauban-processor` (`VaubanProcessor.process`, `NextRound`)
- **Symptom**: `foy-cdi-vauban`'s build with vauban-processor `0.4.0-20261115.150122-50` prints six `File for type
  '…' created in the last round will not be subject to annotation processing.`: `ServletRequestHolder_ClientProxy`,
  `ServletContextHolder_ClientProxy`, `HttpServletRequest$$4717b253_ClientProxy`, `HttpSession$$1fde0ed_ClientProxy`,
  `ServletContext$$ec34198f_ClientProxy`, `_VaubanComponents`; a sample application compiled with Vauban alone
  prints one for its own `_VaubanComponents`. VAU-APT-007 had removed them from the tutorial's builds. Found by the
  Foy session (Servlet built-in beans, Vidocq/foy#107). Vidocq's `vidocq-runtime-it-foy-servlet`, with Foy's,
  Cassini's and Champollion's processors, prints six too, for its client proxies and `_VaubanComponents`.
- **Repro**: `GenerationRoundTest.aModuleWithNoOtherProcessorWritesBeforeTheLastRound` (foy-cdi-vauban's shape:
  normal-scoped producers of interface types, their holders, a build compatible extension, Vauban alone), and
  `aBeanACompanionGeneratesWithAForeignAnnotationIsWaitedFor`, which now also asserts that no warning is printed.
- **Cause**: not a regression, a case VAU-APT-007 kept on purpose: "kept in the last round when there is no second
  round". javac starts a round only for the source and class files the previous one created
  (`JavacProcessingEnvironment.moreToDo` is `filer.newFiles()`). Vauban waits in the first round for the companion
  processors, so in a module where none writes a class there, as foy-cdi-vauban (vauban-processor and
  vauban-webcontexts' extension only), the second round is javac's last, and every class written there warns. The
  same held when a companion's bean that carries a foreign annotation made Vauban wait in the second round, and
  nothing was written there. In Vidocq's IT, `AccountServlet` carries `@ServletSecurity`, which is `@Inherited`:
  Vauban took it for an annotation that could start another processor on its subclasses and kept the last round.
- **Fix**: a round Vauban waits in, when it has something to write and may write before the last round (no
  `-Avauban.generationRound=last`, no foreign `@Inherited` annotation indexed), writes `<package>._VaubanRound<n>`, a
  compilation unit that declares no type and compiles to nothing: javac starts the next round, where Vauban writes
  its classes. foy-cdi-vauban's jar holds the same entries as before; its build prints no warning, a rebuild without
  `clean` either. A compilation with nothing for Vauban writes nothing (`aModuleWithoutBeansAsksForNoExtraRound`).
  `jakarta.servlet.annotation` joins the packages whose `@Inherited` annotations start no processor, as JAX-RS's:
  Foy's processor starts from `@WebServlet`, `@WebFilter`, `@WebListener`, none inherited, or from a class's type
  (`aSecuredServletIsWrittenBeforeTheLastRound`); the Vidocq IT's build then prints no warning.

## BUG-20261115-05 — A module's own build compatible extension is reported as "NOT on the annotation-processor path"

- **Date**: 2026-11-15
- **Status**: FIXED on `fix/processor-build-noise`
- **Module**: `vauban-processor` (`VaubanProcessor.unloadableBcesOnCompilePath`)
- **Symptom**: `foy-cdi-vauban`'s build warns `[Vauban] 1 Build Compatible Extension(s) present on the
  compile/module path are NOT on the annotation-processor path and will not run:
  [io.vidocq.foy.cdi.vauban.FoyWebExtension]. Maven's <annotationProcessorPaths> narrows javac's -processorpath …`
  about its own extension, which runs at the build of the applications, with advice that cannot be followed. Found by
  the Foy session (Servlet built-in beans, Vidocq/foy#107); Foy's pom carries a comment explaining the warning away.
- **Repro**: `BceProcessorPathDiagnosticTest.aModulesOwnBceIsNotReported`: a module `repro.ext` provides
  `OwnExtension` and `OwnExtension$Nested`, its output directory, with the service file, on the class path, and
  requires `repro.other`, which provides `OtherExtension`; then its test compilation, `test-classes` next to
  `classes`, which is on the class path (the task review's repro: the main compile clean, the test compile still
  warned about `OwnExtension`).
- **Cause**: `unloadableBcesOnCompilePath` skips the modules under compilation when it reads the `provides`
  directives (a), but reads `META-INF/services/…BuildCompatibleExtension` from `StandardLocation.CLASS_PATH` (b),
  where Maven puts the module's own `target/classes`, into which it copied the resources before compiling.
- **Fix**: a declared extension that is a type this compilation compiles (a root type of a round, or a member of
  one) is never reported, whichever way it was declared; nor is what the service file of the build's own outputs
  declares, the class output and, for a test compilation, the main output (`-Avauban.mainOutput`, Maven's and
  Gradle's layouts), compared as real paths (javac gives the class path as written and the output resolved: `/var`
  and `/private/var` on macOS). `OtherExtension` still is. Left: a service file outside those outputs, as in Gradle's
  separate resources directory, still counts.

## BUG-20261115-04 — An injection point a bean module satisfies warns when the compilation does not read that module

- **Date**: 2026-11-15
- **Status**: FIXED on `fix/processor-build-noise`
- **Module**: `vauban-processor` (`VaubanProcessor.scanBeanArchiveModules`, `ProcessorPathArchives`)
- **Symptom**: an application that injects `HttpServletRequest`, `HttpSession` or `ServletContext`, produced since
  Vidocq/foy#107 by `ServletBuiltInBeans` of `foy-cdi-vauban`, warns at every build: `[Vauban] Unsatisfied
  dependency: field WhoAmIServlet.request of type …HttpServletRequest … — the required type is not declared by this
  compilation unit; resolution deferred to the runtime container.` On the module path and on the class path alike;
  `--add-modules io.vidocq.foy.cdi.vauban` silences it. Found by the Foy session (Servlet built-in beans). Vidocq's
  `vidocq-runtime-it-foy-servlet` and the applications `vidocq create` writes print it four times: there
  `foy-cdi-vauban` is a runtime dependency, on the processor path only, through the Foy extension's `-codegen` bundle.
- **Repro**: `BeanArchiveModuleInjectionTest.aBeanModuleTheApplicationDoesNotReadResolvesFromTheProcessorPath` and
  `aBeanArchiveOnTheClassPathResolvesFromTheProcessorPath`: a module `repro.beans` produces `repro.api`'s `Exchange`,
  the application requires `repro.api` only, `repro.beans` sits on the processor path, and on the module path (or the
  class path) or not.
- **Cause**: VAU-APT-006 reads the bean archive modules through `Elements.getAllModuleElements()`, which javac fills
  with the modules of the compilation's module graph only (`Modules.getObservableModule` checks `allModules()`).
  No module of a Vidocq application requires `io.vidocq.foy.cdi.vauban`, the Foy runtime extension included, so it
  is never resolved; on the class path, VAU-APT-006 reads nothing. javac gives a processor no way to list the module
  path: the Filer reads a module-oriented location only for a module it names.
- **Fix**: for the types still deferred, the processor also reads the bean archives on the processor path, its own
  class loader, where Vidocq's `-codegen` bundles put each brick's CDI jar: every `META-INF/vauban-beans.list`, and
  the class files of the beans it lists (resources that end with `.class` are never encapsulated, in a named module
  of a processor module path either), scanned with `ClassFileScanner`; nothing is loaded or run. VAU-APT-006's rules
  hold: those beans join resolution only, never validated, generated or listed by the application, an ambiguity among
  them stays a warning, and a class this compilation compiles is never taken from a path. A bean module neither read
  nor on the processor path stays deferred, with the warning
  (`aBeanModuleNeitherReadNorOnTheProcessorPathStaysDeferred`): another build adds it with `--add-modules`.

## BUG-20261115-03 — A modularized library that looks services up by a parameter misses their `uses`

- **Date**: 2026-11-15
- **Status**: FIXED on `fix/modularize-shipped-services`
- **Module**: `vauban-maven-plugin` (`modularize`, `ServiceUsesScanner`)
- **Symptom**: a Vidocq application with Liquibase starts with `vidocq:run` and its distribution, and fails in a
  jlink image: `ServiceConfigurationError: liquibase.logging.LogService: module liquibase.core does not declare
  `uses``. The descriptor `modularize` writes for `liquibase-core-4.33.0.jar` has 38 `provides` and 24 `uses`: 16
  services it provides are not used. Found by the Vidocq tutorial session.
- **Repro**: `ServiceUsesShippedServicesTest`; by hand, `Modularizer.synthesizeOne` on `liquibase-core-4.33.0.jar`
  over the jars of the application's `lib/`.
- **Cause**: the scanner finds a service by the class constant written before the lookup. Liquibase's
  `StandardServiceLocator.findInstances(Class)` hands `ServiceLoader` a type held in a parameter, and its factories
  hold the type in a field: no call site names it.
- **Fix**: a jar with a method that takes the service type as a parameter and reaches `ServiceLoader` also uses
  every service its `META-INF/services` declares for a type it defines itself (a type it does not define may be in
  no module it reads). On `liquibase-core`: 41 `uses`, every provided service used.

## BUG-20261115-02 — A build that can decrypt an encrypted jar takes all its classes as beans, and the start does not

- **Date**: 2026-11-15
- **Status**: FIXED on `fix/sjar-bean-archive`
- **Module**: `vauban-core` (`ContainerScanner.scanSjar`)
- **Symptom**: a Vidocq application depends on an encrypted jar that is not a bean archive (no `beans.xml`, no
  `META-INF/vauban-beans.list`). Built with `VAUBAN_SJAR_KEY` in the environment, the record of the frozen
  extensions lists the jar's classes (`com.acme.secretlib.Secret`, `com.acme.secretlib.internal.Vault`) as bean
  classes; every start with the key then runs the extensions again and warns `VIDOCQ-GEN-003 … 2 bean classes differ
  from the build's: … (removed)`. `vidocq:run`, which builds and starts with the same environment, warns at each
  start. Built without the key, the record matches. Found by the Vidocq tutorial session.
- **Repro**: `ContainerScannerResourcesTest.encryptedJarFollowsTheBeanArchiveRules`: an encrypted jar with only its
  header gave its class as a bean.
- **Cause**: `generate` scans the class path. There, `scanEncryptedJarsOnClasspath` decrypts every jar with a
  `META-INF/vauban.header` and `scanSjar` added each of its classes as a bean class, whatever the jar declared. The
  start reads the jar as a module of the layer and applies the bean archive rules, so it found none.
- **Fix**: `scanSjar` reads the jar's clear `META-INF` as for any jar: the classes its `vauban-beans.list` lists,
  else the classes of a bean archive (`beans.xml`, by its discovery mode), else none.
- **Validation**: the new test, red before the fix; `ExampleAppEndToEndTest` (an encrypted jar indexed by
  `vauban-beans.list`) still gives its beans.

## BUG-20261115-01 — In a module layer, a class of an encrypted jar's internal package cannot be called by its module

- **Date**: 2026-11-15
- **Status**: FIXED on `fix/sjar-in-layer`
- **Module**: `vauban-classloader` (`VaubanLayerFactory`, `VaubanClassLoader`)
- **Symptom**: an application launched in a Vauban layer (Vidocq: `vidocq:run`, the `vidocq:package`
  distribution) depends on an encrypted jar. It starts, and the first call from an exported class of that jar into a
  class of one of its internal packages fails: `IllegalAccessError: class com.acme.secretlib.Secret (in module
  com.acme.secretlib) cannot access class com.acme.secretlib.internal.Vault (in unnamed module @…) because module
  com.acme.secretlib does not read unnamed module`. Found by the Vidocq tutorial session, which saw a
  `ClassNotFoundException` for the same class.
- **Repro**: a module that exports `com.acme.secretlib` and holds `com.acme.secretlib.internal.Vault`, packaged by
  Maven's jar plugin, encrypted with `vauban:encrypt`; `VaubanLayerFactory.createAppLayer` on it, then
  `Secret.reveal()`, which calls `Vault.value()`. `EncryptedModuleInLayerTest`.
- **Cause**: the module system finds the packages of a module by the names of the entries of its jar, unless the
  descriptor lists them in a `ModulePackages` attribute, which only the `jar` tool writes. An encrypted jar stores
  its internal classes under opaque names (`META-INF/vauban/<uuid>`), so the layer was defined with the exported
  packages only. The loader then defined an internal class in a package no module of the layer owns: the class
  went to the loader's unnamed module, which the named module does not read. A jar made with the `jar` tool hides
  the defect, and so did the existing encrypted-archive tests, which load through the loader without a layer.
- **Fix**: `VaubanLayerFactory.withPluginPackages` wraps the module finder of the layer: a module read through a
  source plugin declares every package its classes are in, as the plugin lists them
  (`VaubanClassLoader.pluginPackages`). A plain jar or a directory is left as the module system describes it.
- **Validation**: `EncryptedModuleInLayerTest`, red before the fix. By hand, the distribution of a Vidocq
  application that depends on such a jar, the jar zipped as Maven does and encrypted with a known key: `GET /secret`
  answers 500 with the class loader of `main`, and the decrypted value with the fixed one.

## BUG-20261114-05 — Every module of a Vauban layer reads the first archive's copy of a resource outside any package

- **Date**: 2026-11-14
- **Status**: FIXED on `fix/find-resource-module-name`
- **Module**: `vauban-classloader` (`VaubanClassLoader`, `VaubanLayerFactory`)
- **Symptom**: found in the Vidocq runtime. Its `ExtensionLoader` reads `META-INF/vidocq/extension.properties` from
  each module of the application layer with `Module.getResourceAsStream`, and read 71 identical descriptors: every
  module answered with the copy of the first archive that had the file. Vidocq worked around it by reading each
  module through its `ModuleReader`.
- **Minimal repro**: two modules in a layer of `VaubanLayerFactory.createAppLayer`, `com.example.alpha` (a jar) and
  `com.example.beta` (a directory), both shipping `META-INF/vidocq/extension.properties` with a content of their
  own, and `META-INF/alpha-only.txt` in alpha only: `beta.getResourceAsStream(SHARED)` gave alpha's content, and
  `beta.getResourceAsStream("META-INF/alpha-only.txt")` alpha's file instead of `null`.
- **Cause**: `findResource(String moduleName, String name)`, which `Module.getResourceAsStream`, and
  `Class.getResource` on a class of a named module, reach, ignored `moduleName` and searched every archive of the
  loader in order. The JDK contract is the resource of that module, `null` when it does not hold it or when no module
  of that name is defined to the loader, and the class path of the unnamed module for a `null` name. A name inside a
  package hid the flaw, since a package belongs to one module; a name outside any package (`META-INF/…`, a root file)
  did not. `findClass(String moduleName, String name)` ignored the module the same way: `Class.forName(Module, name)`
  still returned `null` for another module's class, because the JDK checks the module of the result, but only after
  the loader had defined it.
- **Fix**: `VaubanLayerFactory` hands the loader each module's name with its archive (`forLayer(Map<String, Path>)`).
  `findResource(moduleName, name)` looks in that module's archive only, `null` for an unknown module; with a `null`
  name it searches the class-path archives, all of them for a loader of `VaubanClassLoader.of`, none for a layer
  loader. `findClass(moduleName, name)` defines a class only when its archive, or its produced type's for a placed
  proxy, is that module's. The name-based `getResource`, `getResources` and `getResourceAsStream` still search every
  archive.
- **Tests**: `ModuleScopedLookupTest` (five tests, all red before):
  `eachModuleReadsItsOwnCopy` (`expected: <id=beta> but was: <id=alpha>`),
  `aModuleThatLacksAResourceFindsNothing` (beta got alpha's stream instead of `null`),
  `theUnnamedModuleOfALayerLoaderHoldsNoResource` (a URL instead of `null`),
  `aModuleFindsItsOwnClassesOnly` (`findClass(BETA, "com.example.alpha.Alpha")` returned the class),
  `theUnnamedModuleOfAClassPathLoaderHoldsTheArchives` (`findResource(ALPHA, …)` on a class-path loader returned a
  URL). `PlacedProxyTest.placedProxyBelongsToTheProducedTypesModule` guards the placed-proxy branch.
- **Consumers**: the Vidocq runtime (the only consumer of `vauban-classloader`), read on `origin/main`:
  `ExtensionLoader.descriptors` gets each module's own descriptor; `GenerateStamp` now reads the stamp, the bean index
  and the processor markers of the application module itself, where it could read another module's copy. Foy's
  `Module.getResourceAsStream` / `Class.getResourceAsStream` reads all name the class file of the type asked, which
  lives in that type's module: unchanged. No consumer reads through the layer loader's unnamed module.

## BUG-20261114-04 — In a Vauban class loader, each Vidocq start says twice that it will weave the same beans

- **Date**: 2026-11-14
- **Status**: FIXED on `fix/dependent-holder-proxy`
- **Module**: `vauban-core` (`LoadTimeWeaving`)
- **Symptom**: found while checking the Vidocq tutorial. One start of the application, from an IDE build in a Vauban
  layer, prints the INFO line `Unwoven normal-scoped bean(s) [com.example.library.book.BookResource] will be woven by
  the Vauban class loader` twice.
- **Minimal repro**: a loader under a `VaubanClassLoader` whose `META-INF/vauban-beans.list` declares one
  `@ApplicationScoped` class with a `String` constructor only; `LoadTimeWeaving.prepare(loader)` twice: the line is
  logged at each call.
- **Cause**: `prepare` runs twice per Vidocq start, by design: in `VidocqBootstrap`, before any extension can load an
  application class, then in `VaubanContainerBuilder.build()`, the backstop of a plain Vauban start. Both calls must
  return the planned beans, for deployment validation. The agent branch says what it attaches only for the beans no
  earlier call planned (`plannedSoFar`), but the Vauban class loader branch logged on every call.
- **Fix**: that branch names only the beans it has not announced yet in this JVM (`announcedSoFar`, apart from
  `plannedSoFar`, so that an agent is still attached for those beans under a loader without a Vauban layer). The
  returned `Result` is unchanged: both calls report the bean planned.
- **Tests**: `LoadTimeWeavingCandidateSelectionTest.aVaubanLoaderAnnouncesEachBeanOnceAcrossRepeatedPreparations`
  (two calls, the bean planned on both, its name logged once), red before: `expected: <1> but was: <2>`.
- **Not covered**: the announcement is once per JVM and per class name. A second Vauban class loader in the same JVM
  that weaves the same bean again, after a restart in development mode, says nothing more.

## BUG-20261114-03 — `vauban:generate` writes a client proxy for the `@Dependent` holder of a normal-scoped producer

- **Date**: 2026-11-14
- **Status**: FIXED on `fix/dependent-holder-proxy`
- **Module**: `vauban-maven-plugin` (`VaubanGenerator.pregenerate`)
- **Symptom**: found while fixing BUG-20261114-02. `vauban:generate` over a module holding
  `@Dependent public class Instruments { @Produces @ApplicationScoped Clock clock() { … } }` writes
  `Instruments_ClientProxy.class` next to `Instruments` and lists it in `GenerationResult.generatedProxies()`, the
  "generated N proxies" count of the goal. Nothing ever loads it: the container does not proxy a `@Dependent` bean, it
  proxies a producer through the produced class, and the provider names the proxies of normal-scoped managed beans
  only. Dead generated code in the archive, and a wrong count.
- **Minimal repro**: `VaubanGenerator.generate` over the package of the `abstractproduced.Instruments` fixture (two
  normal-scoped producers, of `Clock` and of `Meter`): `generatedProxies()` was
  `[io.vidocq.vauban.maven.generate.abstractproduced.Instruments_ClientProxy]`.
- **Cause**: `pregenerate` wrote `<bean class>_ClientProxy` for every descriptor of a normal scope, whatever its kind.
  A producer method or field descriptor carries the producer's scope, and its holder as bean class; the proxied type
  is the produced one, whose proxy `collectProducerProxies` writes under `ClientProxyShape.producerProxyName`. A
  normal-scoped holder got its proxy from its own managed descriptor anyway, so only a holder of another scope was
  affected. The plugin's other proxy steps (`ProxyLinkWeaving`, the providers) already required a managed bean, and
  the processor sends a producer to `ClientProxyArtefacts.generateForProducer` only: it has no such flaw.
- **Fix**: `pregenerate` writes a bean-class client proxy for a normal-scoped managed bean only. The producer proxies,
  and the proxy of a normal-scoped holder, are unchanged.
- **Tests**: `ProducerProxyAndProviderGenerationTest.aDependentHolderOfNormalScopedProducersGetsNoProxyOfItsOwn`
  (plugin, red before: `expected: <[]> but was: <[…Instruments_ClientProxy]>`); `eachProducerGetsTheProxyItsTypeAllows`
  still checks that the `@ApplicationScoped` holder `Workshop` keeps its `Workshop_ClientProxy`.
  `ProducerAndProviderScenariosTest.aDependentHolderOfNormalScopedProducersGetsNoProxyOfItsOwn` (processor) guards
  that the processor writes none for the `@Dependent` holders `app.Producers` and `DefaultProducer`; it passed before
  the fix, as the processor never had the flaw.

## BUG-20261114-01 — A no-arg bean class recompiled without Vauban under its woven client proxy is not woven at load, and its first client proxy throws `NoSuchMethodError`

- **Date**: 2026-11-14
- **Status**: FIXED on `fix/unwoven-bean-weaving`
- **Module**: `vauban-weaver` (`BeanWeavingAnalysis`, `ProxyLinkWeaver`), `vauban-core` (`LoadTimeWeaving`,
  `DeploymentValidator`, `VaubanContainerBuilder`), `vauban-classloader` (`CdiProxifierTransformer`)
- **Symptom**: found while checking the Vidocq tutorial. In a packaged Vidocq application, `BookResource.class` (an
  `@ApplicationScoped`/`@RequestScoped` REST resource with an implicit public no-arg constructor) was replaced by a
  class compiled with plain `javac -proc:none -g -parameters`, which is also what an IDE that recompiles
  `target/classes` without Maven writes. The container starts, the startup report shows load-time weaving `none`, and
  the first request fails with `NoSuchMethodError: 'void com.example.library.book.BookResource.<init>(io.vidocq.vauban.api.ProxyLink)'`
  from `BookResource_ClientProxy.<init>` ← `_VaubanComponents.createClientProxy` ←
  `ComponentProviders.createClientProxy` ← `InterceptorBeanWrapper.subclassProxy`.
- **Minimal repro**: `ReplacedBeanClassModulePathTest` (vauban-module-it) takes the build output of `Collaborator`
  (`@ApplicationScoped`, implicit no-arg constructor), drops the woven `<init>(ProxyLink)`, puts it next to the
  shipped `Collaborator_ClientProxy` and a `vauban-beans.list`, runs `LoadTimeWeaving.prepare` and creates the proxy:
  `NoSuchMethodError: 'void io.vidocq.vauban.moduleit.Collaborator.<init>(io.vidocq.vauban.api.ProxyLink)'`.
- **Cause**: at build, the javac plugin (`VaubanProcessor.publishWeavePlan`, `WeavePlan`) and the Maven plugin
  (`ProxyLinkWeaving`) weave the marker into every top-level normal-scoped managed bean, no-arg constructor or not, and
  retarget its `<Bean>_ClientProxy` onto it. At start, `LoadTimeWeaving` and the class loader's
  `CdiProxifierTransformer` planned a class only when `BeanWeavingAnalysis.needsMarker(bytes)` held: no marker and no
  non-private no-arg constructor. A no-arg class was never planned, even when the proxy next to it calls
  `<init>(ProxyLink)`, and `DeploymentValidator` checks proxyability only through injection points, never which
  constructor the proxy calls. When weaving could not be installed, nothing failed the start either.
- **Fix**: one rule, `BeanWeavingAnalysis.needsMarker(name, bytes, resolver)`, for both load-time tiers: a class
  without the marker needs it when it has no non-private no-arg constructor, or when `<name>_ClientProxy` next to it
  refers to its `<init>(Lio/vidocq/vauban/api/ProxyLink;)V` (`ProxyLinkWeaver.refersToMarkerConstructor`, constant
  pool only). The proxy is read only for a class without the marker that has a no-arg constructor: a woven build
  reads nothing more. Such a class is planned and woven like any other unwoven bean. When nothing can weave it (the
  attach fails, or `-Dvauban.weaving.loadtime=disabled`, which now detects without attaching), `LoadTimeWeaving.Result`
  names it in `unwoven()`, and `DeploymentValidator.loadTimeUnwoven` fails the start on each such managed bean,
  injected or not, with the weaving failure as the reason (the message Vidocq reports as VAUBAN-009).
- **Tests**: `BeanWeavingAnalysisTest` (vauban-weaver: the rule, and that a woven class or one without a no-arg
  constructor reads no proxy); `LoadTimeWeavingTest` (the agent weaves the replaced class, a proxy chaining the no-arg
  constructor needs nothing, the opt-out names what it leaves unwoven); `VaubanClassLoaderTest` (the class loader
  weaves it); `ProxyableBeanValidationTest` and `UnwovenBeanStartFailureTest` (the start fails, once per bean, with
  the reason); `ReplacedBeanClassModulePathTest` (vauban-module-it, on the real build output). Each failed first.
- **Not covered**: a woven subclass whose marker chains `super((ProxyLink) null)` to a superclass recompiled without
  the marker (checking it would read the superclass of every woven bean at start); a class path without
  `vauban-weaver`, where `LoadTimeWeaving` cannot analyse anything.

## BUG-20261114-02 — A normal-scoped producer of an abstract class fails, `java.time.Clock` with a `NullPointerException`

- **Date**: 2026-11-14
- **Status**: FIXED on fix/abstract-produced-type-proxy
- **Module**: `vauban-core` (`ProducerProxyEligibility`, `ForwardedMethods`, `RuntimeClientProxyGenerator`,
  `InterceptorBeanWrapper`), `vauban-processor`, `vauban-maven-plugin`
- **Symptom**: an application on Vidocq 0.4.0-SNAPSHOT started in-process (`VidocqBootstrap.create().configure()
  .start()`) with `@ApplicationScoped class ClockProducer { @Produces @ApplicationScoped Clock clock() { return
  Clock.systemUTC(); } }`: `CDI.current().select(Clock.class).get()` throws `DeploymentException: Failed to create
  client proxy for normal-scoped bean …ClockProducer`, caused by `ContainerOperationException: Failed to define proxy
  class for class java.time.Clock`, caused by `NullPointerException: Cannot invoke "ClassLoader.loadClass(String)"
  because the return value of "Class.getClassLoader()" is null`. The reporter worked around it with `@Singleton`.
- **Minimal repro**: the producer above in a `VaubanContainer`, then `container.select(Clock.class)`
  (`AbstractProducedTypeProxyTest`); on the module path with the processor, `AbstractProducedTypeTest` of
  `vauban-producer-module-it`.
- **Cause**: `ProducerProxyEligibility` answered `ABSTRACT_CLASS` for any abstract produced type, with a Javadoc
  claiming CDI 4.1 §3.10 makes it unproxyable. §3.10 lists classes without a non-private no-arg constructor, final
  classes, classes with non-static final methods, primitives and arrays: not abstract classes. So the processor
  shipped no proxy (a NOTE), `vauban:generate` warned that the run time would generate it, and the run-time fallback
  generated `java.time.Clock_ClientProxy` in `java.time`: `loadOrDefineClassRobustly` called
  `getClassLoader().loadClass` on the boot loader's `null`, and could not have defined a class in `java.time` anyway.
  Every proxy generator also skipped abstract methods (`ForwardedMethods.isProxyable`, the interface-default walks),
  so a proxy of an abstract class, wherever it came from, threw `AbstractMethodError` on such a call.
- **Fix**: an abstract class is eligible like any class; an abstract method is judged by its access, as the others
  (a protected one keeps the proxy in the type's own package). The proxy shape forwards abstract methods, those an
  interface leaves to the class included, to the contextual instance, never through `super`. The processor and
  `vauban:generate` then generate the proxy in the producer's package (`ClientProxyShape.producerProxyName`, now the
  one naming rule of the three paths); `vauban:enhance-dependencies` no longer refuses an abstract type. The run-time
  fallback defines the proxy next to the produced type when the build left it there or Vauban can define a class in
  that package; otherwise, for a producer, in the producer's package through the producer's lookup, as the build
  would have; a type that fits neither is a `DeploymentException` naming the type, its producer and scope, and
  suggesting `@Singleton` or `@Dependent`. `Class.forName` replaces `getClassLoader().loadClass`.
- **Tests**: `ProducerProxyEligibilityVerdictTest` (abstract, `Clock`, protected, package-private and final abstract
  cases), `ProducerProxyBytecodeTest` (a call reaches the instance through a proxy of `Clock` and of an abstract
  class), `AbstractProducedTypeProxyTest` (run time: `Clock` in the producer's package, an abstract class of another
  package, `java.util.Random` refused with a `DeploymentException`), `ClientProxyInheritanceCrossCheckTest` and
  `AbstractProducedTypeProxyEmissionTest` (processor), `ProducerProxyAndProviderGenerationTest` and
  `DependencyEnhancerProxyabilityTest` (plugin), `AbstractProducedTypeTest` (module path, zero opens). All red before.

## BUG-20261113-19 — A synthetic bean of an interface type always takes the run-time client proxy, with advice that does not apply

- **Date**: 2026-11-13
- **Status**: FIXED (branch `fix/synthetic-interface-proxies`)
- **Module**: `vauban-core` (`RuntimeGeneration`, `InterceptorBeanWrapper.interfaceProxy`), `vauban-processor`,
  `vauban-api`
- **Symptom**: every Noether application on Vidocq starts with the framed warning _VAUBAN — RUNTIME CODE GENERATION
  FALLBACK_, listing `interface client proxy (java.lang.reflect.Proxy) jakarta.persistence.EntityManagerFactory` and
  `… jakarta.persistence.EntityManager`: the `@ApplicationScoped` and `@TransactionScoped` synthetic beans
  `noether-jpa-cdi` registers for each unit. The warning says the beans "were compiled without the Vauban annotation
  processor" and advises adding it, which the application already has: no processor sees a synthetic bean.
- **Minimal repro**: Vidocq `vidocq-runtime-it-noether-units-per-module`, `units-run.log`, even with the extensions
  frozen at build ("Vauban: froze 2 build compatible extension(s)").
- **Cause**: the processor generates the client proxy of a bean it sees, a producer of an interface included (a class
  `implements` it, #42). A synthetic bean has no class it sees, so `InterceptorBeanWrapper.interfaceProxy` found no
  provider owning the interface's key and fell back to `java.lang.reflect.Proxy`, every call through
  `Method.invoke`. The warning counted it as a bean compiled without the processor.
- **Fix**: `io.vidocq.vauban.api.ClientProxies` lists the interfaces of an extension's synthetic beans, on any class of
  a module compiled with `vauban-processor`. The processor generates for each the class `implements` it that a
  producer of the interface gets, in the package of the annotated class, registered under the interface's key in the
  package's `_VaubanComponents`, written even for a module without a bean; once per interface and package, a producer
  of the same interface sharing it. A listed type that is not a public interface fails the compilation. The boot
  warning lists a synthetic bean's proxy apart (`SYNTHETIC_INTERFACE_PROXY`) with the `@ClientProxies` advice, and
  the advice to add `vauban-processor` only when another kind of class is listed.
- **Tests**: `ClientProxiesDeclarationTest` (vauban-processor): the provider creates the proxy and covers its key, a
  default method goes to the contextual instance, a producer of the same interface shares one proxy, a class is
  refused. `RuntimeGenerationTest`: the boot predicts the synthetic kind, and its warning gives the advice that applies.
- **Investigations**:
  - 2026-11-13: found by the Vidocq IT of BUG-20261113-18.
  - 2026-11-14: the extensions frozen at build do not help, as they freeze descriptors, not proxies. Generating the
    proxies in `vauban:generate` after the freeze was set aside: it would miss every deployment that runs its
    extensions at start (tests, an IDE build). Noether's case is closed by Vidocq's Noether extension listing
    `EntityManager` and `EntityManagerFactory`, which keeps `noether-jpa-cdi` free of Vauban.
  - 2026-11-14: the first downstream Vidocq build of the fix failed in `vidocq-runtime-dirac-metrics-extension`,
    "Provider io.vidocq.vauban.processor.VaubanProcessor could not be instantiated": the processor read the
    annotation's name from `ClientProxies.class`, and that module's processor path held the published `vauban-api`,
    without it, through a codegen bundle. The processor now names the type as the other `vauban-api` types it looks
    for; `OlderApiOnProcessorPathTest` loads it without `ClientProxies`, red before.

## BUG-20261113-18 — A `Startup` observer cannot reach a bean of a scope an extension registered

- **Date**: 2026-11-13
- **Status**: FIXED on `fix/contexts-before-startup`
- **Module**: `vauban-core` (`VaubanContainer`, `VaubanContainerBuilder.build`)
- **Symptom**: an observer of `Startup` or `@Initialized(ApplicationScoped.class)` that calls a bean of a scope a
  build compatible extension registers (`MetaAnnotations.addContext`) fails the start with
  `ContextNotActiveException: No context registered for scope …`. Found by a Vidocq IT: a `Startup` observer
  persisting through `@Inject @Archived EntityManager`, a `@TransactionScoped` bean of `noether-jpa-cdi`, inside a
  `@Transactional` method, under `vidocq:run`.
- **Minimal repro**: `ExtensionContextsAtStartupTest`: an extension registers the normal scope `@Ledgered`, and an
  `@ApplicationScoped` bean reads a `@Ledgered` bean from its startup observers.
- **Cause**: the `VaubanContainer` constructor fired the startup events, and the builder installed the extensions'
  contexts on the container it returned (`installContexts`), so after them. The factories an extension supplied
  (`suppliedFactories`) and the module lookups to withdraw on close were set after them too.
- **Fix**: the constructor no longer fires the events; the builder calls `VaubanContainer.start()` last, once the
  contexts, the supplied factories, the extensions' report and the module lookups are set. A start that fails closes
  the module lookups twice, which `ModuleLookups.Registration.close` allows.

---

## BUG-20261113-17 — A jlink image finds its record of the frozen extensions twice and refuses it

- **Date**: 2026-11-13
- **Status**: FIXED (`fix/frozen-record-found-twice`, then `fix/fingerprint-strip-debug` for the stripped classes)
- **Module**: `vauban-classloader` (`VaubanClassLoader.findResources`), `vauban-core` (`FrozenExtensions.find`,
  `DeploymentFingerprint`)
- **Surfaced by**: Vidocq's `CreateThenBuildIT`, extended to start what a created application packages. The zip
  distribution starts on the record (`cdi extensions  2 extensions frozen at build`); the image of `vidocq:jlink`
  reports `2 extensions run at start (the record of the build is not this deployment's)`, also with
  `-Dvidocq.stripDebug=false`, whose classes are byte for byte those of the build.
- **Repro**: `FrozenExtensionsRecordTest.oneRecordSeenThroughTwoLoaders` (a loader and its parent over the same
  directory: "2 records of frozen extensions on the path") and
  `StandardResourceUrlsTest.aJarTheParentAlsoHoldsGivesEachResourceOnce` (the same jar as `jar:file:/…` from the
  parent and `jar:file:///…` from this loader).
- **Cause**: the fingerprint was right: the same settings, the same four classes, the same value at build and in the
  image. In an image, Vidocq finds the application module in the boot layer and layers it again in
  `vauban-app-layer` (boot-layer detection). `getResources` gives the parent's resources and then the loader's own,
  so `VaubanClassLoader` returned `jrt:/com.acme.demo.app/META-INF/vauban-frozen-extensions.properties` twice, and
  `FrozenExtensions.find` took two results for two records, none of them this deployment's. `CompositeClassLoader`
  already left out what its parent gives; `VaubanClassLoader` did not.
- **Fix**: `VaubanClassLoader.findResources` leaves out what its parent already gives, with `file:///x` and `file:/x`
  taken for the same file; `FrozenExtensions.find` counts distinct files.
- **Second cause**: with jlink's default `--strip-debug` the image's class bytes differ from the build's, and the
  fingerprint, a CRC32C of the bytes, differed too. jlink removes `SourceFile`, `LineNumberTable`,
  `LocalVariableTable`, `LocalVariableTypeTable` and `MethodParameters`, and computes some `StackMapTable`s again. It
  keeps the constant pool and the bytecode: over the 322 classes of `vauban-core`, the pool, the code arrays,
  `max_stack`, `max_locals` and the exception tables are identical in the image, and 202 stack map tables differ.
- **Second fix**: a bean class counts by what the extensions see of it, its model without the parameter names
  (`ClassChecksums.ofModel`); an extension counts by its code as well, the constant pool and each method's bytecode
  and exception table (`ClassChecksums.ofCode`). A method body of a bean no longer invalidates the record. Repro:
  `DeploymentFingerprintTest.debugInformationDoesNotCount` (red on the model as its `toString` gives it, which holds
  the parameter names), `ClassChecksumsTest.realClassesKeepTheirChecksumsOnceStripped`.

---

## BUG-20261113-16 — A host bean the deployment also holds changes its fingerprint: the frozen record is refused

- **Date**: 2026-11-13
- **Status**: FIXED (`fix/host-bean-also-scanned`)
- **Module**: `vauban-core` (`VaubanContainerBuilder.addHostBean`, `indexBeanClasses`)
- **Surfaced by**: the tutorial replay of the frozen extensions (Vidocq). An application with `mansart-pool` starts
  frozen without a pool, and with `VIDOCQ_POOL_URL` set reports _N extensions run at start (the record of the build
  is not this deployment's)_; the log gives two fingerprints. The same with `mansart-data` or Flyway next to the pool.
- **Repro**: `FrozenExtensionsTest.aHostBeanTheDeploymentAlsoHoldsKeepsTheRecordValid`: a deployment holding
  `HostHolder` is frozen, then started with `addHostBean(HostHolder.class, …)` as well: "the record … has the
  fingerprint 64cd4ea4…, this deployment bdefdc9e…".
- **Cause**: `addHostBean` left its class out of the fingerprint, whether or not the deployment held it. Vidocq's
  `MansartPoolHolder` is a `@Singleton` bean the pool extension's `vauban-beans.list` names: the build counts it, and
  a start counts it too unless a pool is configured, when `MansartPoolExtension.beforeStart` also adds it with
  `addHostBean`.
- **Fix**: the builder keeps the classes of the deployment (`addBeanClass`, a scan included) apart from those only
  `addHostBean` adds; a host bean leaves the fingerprint only when it is not the deployment's, in either order of the
  calls.

---

## BUG-20261113-15 — An alternative or an interceptor whose `@Priority` an `@Enhancement` removes stays enabled

- **Date**: 2026-11-13
- **Status**: FIXED (`fix/alternative-priority-zero`), found while testing BUG-20261113-14
- **Module**: `vauban-core` (`EnhancementApplier.applyClassConfigs`, `EnhancementApplier.withEnhancedPriority`)
- **Symptom**: an extension that removes `@Priority` with `ClassConfig.removeAnnotation` changes nothing:
  - from an `@Alternative` class, the alternative stays selected: lookups and injection points still get it;
  - from an `@Interceptor` class, the interceptor stays enabled and still runs (CDI 4.1, 9.4: an interceptor
    without `@Priority` and not listed in a `beans.xml` is not enabled).

  Adding a priority the same way works, as expected.
- **Repro**:
  - `AlternativePriorityZeroTest.anAlternativeWhosePriorityAnExtensionRemovesIsNotSelected`, "expected: <plain>
    but was: <zero>";
  - `DeclaredPriorityTest.anInterceptorWhosePriorityAnExtensionRemovesIsNotEnabled`, "expected: <[fixed]> but
    was: <[moved, fixed]>".
- **Cause**: the enriched index that discovery reads carries the annotations an `@Enhancement` adds, never the ones
  it removes. `EnhancementApplier` applies the removals to the qualifiers and the interceptor bindings of the
  descriptors only: it copied the priority of a bean unchanged, and for an interceptor it read the added
  `@Priority` alone (BUG-20261113-12). Before BUG-20261113-14 an alternative at `@Priority(0)` looked unselected
  anyway, which hid the case the alternative test uses.
- **Fix**: `applyClassConfigs` (beans) and `withEnhancedPriority` (interceptors) run the configs over the priority
  in their order: a removal predicate that matches the `@Priority` (or `removeAllAnnotations`) takes it away,
  which unselects the alternative or disables the interceptor, and an added `@Priority` in any form gives its value.
  A frozen deployment is not concerned: the freeze already refuses an extension that removes annotations, which is
  replayed at start. A priority a bean takes from a stereotype is not on the class: a predicate removing
  `@Priority` from the class leaves it (`AlternativePriorityZeroTest.removingThePriorityOfTheClassLeavesTheOneOfItsStereotype`),
  and only `removeAllAnnotations`, which takes the stereotype as well, removes it.

---

## BUG-20261113-14 — An alternative enabled with `@Priority(0)` is taken for one without a priority

- **Date**: 2026-11-13
- **Status**: FIXED (`fix/alternative-priority-zero`), found by reading while fixing BUG-20261113-11
- **Module**: `vauban-core` (`BeanDescriptor`, `BeanDiscovery`, `StereotypeResolver`, `BeanResolver`,
  `VaubanBeanManager`, `DeploymentValidator`, the synthetic bean builder and its metadata)
- **Symptom**: `@Alternative @Priority(0)` is not selected over the bean it replaces: a lookup and an injection point
  both get the default bean. The same for an alternative stereotype declaring `@Priority(0)`, a producer declared an
  alternative at priority 0, and a synthetic bean given `alternative(true).priority(0)`, run at start or frozen at
  build.
- **Repro**: `AlternativePriorityZeroTest`, 4 of the first 5 tests red before the fix ("expected: <zero> but was:
  <plain>"), then the synthetic one ("expected: <synthetic> but was: <plain>").
- **Cause**: a bean descriptor held `priority` as an `int` where 0 meant "not set". `VaubanBeanManager.getBeans` and
  `BeanResolver` took an alternative at `priority() <= 0` for a disabled one; the producer and stereotype fallbacks
  of `BeanDiscovery` and `StereotypeResolver` went on looking while the value was 0; the synthetic bean builder did
  not know whether `priority(int)` was called, and its metadata wrote 0 for none.
- **Fix**: `BeanDescriptor.priorityDeclared` and `SyntheticBeanDescriptor.priorityDeclared` say whether a priority was
  found, as `InterceptorDescriptor.enabled` does for interceptors. Discovery works on `OptionalInt` from the
  annotation to the descriptor; the resolution, the name validation and `BeanInfo.priority()` read the flag
  (`InterceptorInfo.priority()` reads `enabled`). The synthetic metadata writes `bean.N.priorityDeclared`; metadata of
  an earlier version, without it, reads a priority of 0 as none, as it meant. The constructors without the flag
  still take 0 for none, so a caller that is not updated behaves as before.

---

## BUG-20261113-13 — No `$$Intercepted` subclass is generated at build for a binding an `@Enhancement` adds

- **Date**: 2026-11-13
- **Status**: FIXED (`fix/enhanced-binding-intercepted`)
- **Module**: `vauban-processor` (`InterceptedArtefacts.isInterceptedTarget`), `vauban-maven-plugin`
  (`VaubanGenerator.pregenerate`)
- **Surfaced by**: Vidocq/vidocq#546. With Humboldt's extension on the processor path, the build freezes the
  `@SpanBinding` it adds to a `@WithSpan` method (`META-INF/vauban-enhancements.properties` names it), yet the compiled
  output holds `TracedGreeter_ClientProxy.class` and no `TracedGreeter$$Intercepted.class`.
- **Repro**: `BceCompileTimeTest.anInterceptorBindingAnExtensionAddsGetsItsSubclassAtBuild` (a binding added to a
  method, then to a class: no `$$Intercepted.class`) and
  `VaubanGeneratorTest.anInterceptorBindingAnExtensionAddsGetsItsSubclass` ("interceptors: []").
- **Cause**: both build front-ends decide the intercepted targets from what the class carries: the processor from
  javac's model of the source, the plugin by reflection on the compiled class. Neither holds what an `@Enhancement`
  adds, and the plugin's enriched index holds the class-level additions only. The container then generated the
  subclass at start with the Class-File API, and announced it: the application worked, but not from generated code
  alone.
- **Fix**: `BceProcessor.boundByEnhancement` lists the classes the `@Enhancement` modifications give an interceptor
  binding, on the class, a method or a constructor, whichever `addAnnotation` added it. The processor and the plugin
  generate the subclass of such a class when the container can wrap it (non-final, not an `@Interceptor`,
  `InterceptionTargets.isWrappable`), as for a binding the source declares.

---

## BUG-20261113-12 — A `@Priority` an `@Enhancement` adds as an annotation instance does not move the interceptor

- **Date**: 2026-11-13
- **Status**: FIXED (branch `fix/priority-zero-and-enhanced`)
- **Module**: `vauban-core` (`EnhancementApplier.withAddedPriority`)
- **Surfaced by**: Vidocq/heisenberg#66. `mp.fault.tolerance.interceptor.priority=5000` left
  `FaultToleranceInterceptor` at 4010 in the container: Heisenberg's extension removes `@Priority` and adds
  `new PriorityLiteral(5000)`. Under Weld SE the same jars apply the priority.
- **Symptom**: `ClassConfig.addAnnotation(Annotation)` with a `@Priority` on an interceptor class changes nothing;
  `addAnnotation(AnnotationInfo)` with the same value moves it.
- **Minimal reproduction**:
  ```
  ./mvnw -ntp -pl vauban-core -am package -Dsurefire.failIfNoSpecifiedTests=false -Dtest=DeclaredPriorityTest
  ```
  `aPriorityAddedAsAnAnnotationMovesTheInterceptor`: `expected: <[fixed, moved]> but was: <[moved, fixed]>`.
- **Root cause**: `withAddedPriority` read `VaubanClassConfig.getAddedAnnotationInfos()`, the language-model form
  only. An annotation instance is kept apart (`addedAnnotationInstances`); `getAddedAnnotationsIndexed()` gives the
  three forms. The enriched index could not make up for it: it appends the added annotation after the declared one,
  the removal is not applied to it, and `BeanDiscovery.extractPriority` returns the first `@Priority`.
- **Fix**: `withAddedPriority` reads `getAddedAnnotationsIndexed()`.
- **Left open**: a removal is still not applied to the enriched index, so a class-level annotation an enhancement
  replaces is read from the declared one wherever only the index is read. The frozen patch records additions only
  (see `concepts.adoc`).

---

## BUG-20261113-11 — An observer declared at `@Priority(0)` is notified as if it declared no priority

- **Date**: 2026-11-13
- **Status**: FIXED (branch `fix/priority-zero-and-enhanced`)
- **Module**: `vauban-core` (`ObserverDisposerDiscovery`, `BeanDiscovery.declaredPriority`)
- **Surfaced by**: Vidocq/cervantes#52. A `Startup` observer at `Interceptor.Priority.PLATFORM_BEFORE`, which is 0,
  ran after an observer at `LIBRARY_BEFORE`.
- **Symptom**: three observers of one event at 0, at 100 and without `@Priority` are notified in the order 100,
  default, 0: the one at 0 gets the default priority, 2500.
- **Minimal reproduction**: `DeclaredPriorityTest.anObserverAtPriorityZeroIsNotifiedFirst`:
  `expected: <[zero, hundred, undeclared]> but was: <[hundred, undeclared, zero]>`.
- **Root cause**: `BeanDiscovery.extractPriority` returns 0 when no `@Priority` is there, and the observer discovery
  took 0 for "none" and fell back to the method's annotations, then to the default.
- **Fix**: `BeanDiscovery.declaredPriority` returns an `OptionalInt`; the observer discovery falls back only when
  nothing is declared. The same convention on bean descriptors is BUG-20261113-14.

---

## BUG-20261113-01 — A synthetic bean in a scope of an extension becomes `@Dependent`

- **Date**: 2026-11-13
- **Status**: FIXED (branch `feat/cdi-qualifier-scope`)
- **Module**: `vauban-core` (`SyntheticBeanConverter.scopeOf`, `BceProcessor.scopeOf`)
- **Surfaced by**: Vidocq/noether#72. Jakarta EE 11 Platform §13.12.1 puts a persistence unit's `EntityManager` bean
  in `jakarta.transaction.TransactionScoped`, a scope that `mansart-transactions-cdi` registers with
  `MetaAnnotations.addContext`.
- **Symptom**: `components.addBean(EntityManager.class).scope(TransactionScoped.class)` gives a bean whose
  `getScope()` is `Dependent`. It has no client proxy, each lookup creates a new instance, and a call outside a
  transaction does not throw `ContextNotActiveException`. In `noether-jpa-cdi-module-it`:
  `expected: jakarta.transaction.TransactionScoped but was: jakarta.enterprise.context.Dependent`.
- **Minimal reproduction**:
  ```
  ./mvnw -ntp -pl vauban-core -am install -Dsurefire.failIfNoSpecifiedTests=false \
    -Dtest='SyntheticBeanConverterScopeTest,SyntheticBeanExtensionScopeTest'
  ```
- **Root cause**: `SyntheticBeanConverter.scopeOf` compared the scope with the five built-in scopes and
  `@Singleton`, and returned `DEPENDENT` for every other one. The run time and the processor both convert synthetic
  beans through it. A class bean was not affected: `BeanDiscovery.mapScope` reads the `@NormalScope` or `@Scope`
  meta-annotation.
- **Fix**: `BceProcessor.scopeOf` reads a scope annotation an extension hands over, with the meta-annotation check
  that `enhancedScope` already made. `enhancedScope` and `SyntheticBeanConverter.scopeOf` share it. A scope of an
  extension keeps its name and its normality, and a class that is not a scope still gives `@Dependent`.
  `BceProcessor` is already a documented reflective fallback of the Build Compatible Extensions API.

## BUG-20261113-02 — The interface client proxy wraps what the contextual instance throws

- **Date**: 2026-11-13
- **Status**: FIXED (branch `feat/cdi-qualifier-scope`)
- **Module**: `vauban-core` (`InterceptorBeanWrapper.interfaceProxy`)
- **Surfaced by**: Vidocq/noether#72. `close()` on an `@ApplicationScoped` `EntityManagerFactory` bean must throw
  `IllegalStateException`; the caller got `java.lang.reflect.UndeclaredThrowableException`.
- **Symptom**: a normal-scoped bean whose type is an interface, with no proxy generated at build time, gets a
  `java.lang.reflect.Proxy`. Any exception its contextual instance throws, checked or not, reaches the caller as an
  `UndeclaredThrowableException` that wraps an `InvocationTargetException`.
- **Minimal reproduction**:
  ```
  ./mvnw -ntp -pl vauban-core -am install -Dsurefire.failIfNoSpecifiedTests=false \
    -Dtest='InterfaceClientProxyTest#anExceptionOfTheContextualInstanceReachesTheCallerUnwrapped'
  ```
- **Root cause**: the invocation handler returned `method.invoke(target, args)` and let `InvocationTargetException`
  escape. `Proxy` wraps a checked exception that the interface method does not declare.
- **Investigations**:
  - 2026-11-13: red on `origin/main` `aeb44388`: `expected: <java.lang.IllegalStateException> but was:
    <java.lang.reflect.UndeclaredThrowableException>`.
- **Fix**: the handler rethrows the cause of the `InvocationTargetException`.

## BUG-20261111-01 — A synthetic bean or observer registered at build time loses the member values of its qualifiers

- **Date**: 2026-11-11
- **Status**: FIXED 816d2c8f (branch `fix/build-time-synthetic-qualifier-members`)
- **Module**: `vauban-core` (`SyntheticMetadataSerializer`, `SyntheticBeanDescriptor`, `SyntheticObserverDescriptor`,
  `SyntheticComponentRegistrar`, `SyntheticParamCodec`)
- **Surfaced by**: the Noether audit, Vidocq/noether#45 finding F9 (`noether-jpa-cdi` on the processor path).
- **Symptom**: an extension run by the processor registers synthetic beans qualified `@PersistenceBinding("unit:explicit")`,
  `@PersistenceBinding("unit:container")`, ... The application compiles, and the boot fails:
  `Unsatisfied dependency: field Repository.factory of type ...EntityManagerFactory with qualifiers
  [...PersistenceBinding, members={value=StringVal[value=unit:container]}]`. A synthetic observer qualified the same way
  receives no event. The build wrote the qualifier's type name alone:
  ```
  bean.0.qualifiers=io.vidocq.noether.jpa.cdi.PersistenceBinding
  ```
- **Minimal reproduction**:
  ```
  ./mvnw -ntp -pl vauban-processor -am package -Dsurefire.failIfNoSpecifiedTests=false \
    -Dtest='SyntheticMetadataSerializerTest*,BceCompileTimeTest#buildTimeSyntheticQualifierKeepsItsMembers'
  ```
- **Root cause**: the build-time counterpart of BUG-20260914-15, which fixed the builders and the run-time path only.
  `SyntheticMetadataSerializer.writeBeanBuilder` and `writeObserverBuilder` wrote `q.annotationType().getName()`;
  `readBeans` and `readObservers` read a list of names; `SyntheticComponentRegistrar` rebuilt each qualifier from its
  type, every member at its default. The injection points keep their members (the index, and the frozen patch since
  BUG-20261008-05), so no synthetic bean matched.
- **Investigations**:
  - 2026-11-11: red on `origin/main` `07cd8206`: `SyntheticMetadataSerializerTest.shouldRoundTripBeanQualifierMembers`
    and `shouldRoundTripObserverQualifierMembers` read `members={}`; `BceCompileTimeTest.buildTimeSyntheticQualifierKeepsItsMembers`
    (the extension run by the processor, then a boot from the processed archive) fails with the two
    `Unsatisfied dependency` of the symptom.
- **Fix**: the qualifiers are written with their members in the form the frozen patch already uses
  (`SyntheticParamCodec.encodeAnnotations`, one frame of `encodeAnnotation` each, now shared by both files). The
  descriptors carry them in index form (`qualifierAnnotations()`, next to the names), and the registrar builds each
  qualifier instance from it. The comma-separated names of an archive built by an earlier version still read, members
  empty (`shouldReadTheQualifierNamesOfAnEarlierVersion`). Not covered: a qualifier type the compilation is still
  producing cannot be loaded by the builders at build time and is still skipped (`SyntheticQualifiers.instanceOf`).

## BUG-20261111-02 — A frozen member patch of a class outside the deployment fails the boot

- **Date**: 2026-11-11
- **Status**: FIXED 816d2c8f (branch `fix/build-time-synthetic-qualifier-members`)
- **Module**: `vauban-core` (`EnhancementPatchSerializer.memberConfigurations`)
- **Surfaced by**: Vidocq/noether#45, while reproducing BUG-20261111-01 in `noether-jpa-cdi-module-it`.
- **Symptom**: with an extension on the processor path that adds annotations to members, a container built from some
  of the classes of the processed archive fails:
  `IllegalStateException: Enhanced class is missing from the index: ...SetterRepository`.
  Seen with `VaubanContainer.builder().addBeanClass(Services.class).addBeanClass(Repository.class).build()`, and with
  `scanLocal()` from a test class, which deploys the caller's package only.
- **Minimal reproduction**:
  ```
  ./mvnw -ntp -pl vauban-processor -am package -Dsurefire.failIfNoSpecifiedTests=false \
    -Dtest='EnhancementPatchSerializerTest*,BceCompileTimeTest#frozenMemberPatchOfAClassOutsideTheDeploymentIsIgnored'
  ```
- **Root cause**: `VaubanContainerBuilder.loadEnhancementPatch` merges `META-INF/vauban-enhancements.properties` of
  every archive the class loader sees, while the index holds the classes of the deployment. `enrichIndex` applies the
  class entries to the indexed classes and passes over the others; `memberConfigurations`, added with the member
  entries (BUG-20261009-03, `e7daa793`), threw for an owner the index does not hold. No test or note asked for that
  failure: the check guards against a patch that no longer matches its class, which the two member checks
  (`Enhanced field is missing`, `Enhanced method is missing`) do for a deployed class.
- **Investigations**:
  - 2026-11-11: red on `origin/main` `07cd8206`: `EnhancementPatchSerializerTest.memberAdditionsOfAClassOutsideTheIndexAreLeftOut`
    and `BceCompileTimeTest.frozenMemberPatchOfAClassOutsideTheDeploymentIsIgnored` fail with the exception of the symptom.
- **Fix**: a member entry whose owner is not in the index is left out, as its class entries are. A field or a method
  the patch names and a deployed class does not have still fails (`aMissingMemberOfADeployedClassStillFails`).

## VAU-APT-009 — A nested bean with an observer, a producer or a lifecycle method no longer compiles
- **Date**: 2026-11-05 — **Status**: FIXED (fix/nested-bean-invoke-cast)
- **Severity**: high (the build of the application fails; the only way out is to make the bean a top-level class)

### Symptom
A static nested bean class with a non-private `@Observes`, `@Produces`, `@Disposes`, `@PostConstruct` or `@PreDestroy`
method, or an `@Inject` initializer, made the generated `_VaubanComponents` fail to compile: `cannot find symbol` on
`app.Outer$Inner`. `invoke()` cast the target, and called a static method, through the binary name of the class, which
is not a name the Java source can use. `create()` and `injectField()` already named the class canonically. Found while
writing a vidocq test with nested observers (Vidocq/vidocq#496).

### Repro
```java
public class Holder {
    @ApplicationScoped
    public static class Observers {
        void seen(@Observes String event) { }
    }
}
```
compiled with the processor: `ComponentProviderCompileTimeTest#nestedBeanMethodsAreCalledThroughItsCanonicalName`.

### Fix
`MethodInvoke` carries the canonical name of the declaring class next to its binary name, from
`ProvidedClass.sourceFqn` through `ComponentCollector.collectMethods`. `invoke()` keeps the binary name as the key the
container asks with, and casts and calls through the canonical name. A caller with no separate source name (the
bytecode provider of the Maven plugin, which writes internal names) keeps the six-argument constructor. Tests:
`ComponentProviderCompileTimeTest#nestedBeanMethodsAreCalledThroughItsCanonicalName`,
`ComponentProviderGeneratorTest#invokeCallsANestedClassByItsSourceName`.

---

## VAU-PERF-009 — A request-scoped or dependent bean is read by reflection again at each request
- **Date**: 2026-11-02 — **Status**: FIXED (perf/per-request-interceptors)
- **Severity**: medium (steady-state cost: ~60 reflective calls per request on the tutorial application, linear in the number of requests)

### Symptom
The tutorial session (step 11, `vidocq build` dist, `rtrace` agent) counted 59.6 reflective calls per request, 36.6 of
them from Vauban, nothing amortising. They came from creating a request-scoped resource and its dependents at every
request, which the creation plan of VAU-PERF-003 does not cover: the interceptor bindings of the class
(`getAnnotations`, `isAnnotationPresent` on each binding and meta-binding), the annotations of every declared method
(`eagerCreateInterceptors`, `hasMethodOrClassInterceptors`), whether an instance is itself an interceptor, an
interceptor's `@PostConstruct`, the constructors of the bean and of its `$$Intercepted` subclass, the `$$init` handle of
that subclass (`Class.forName` while resolving the method type), the generic type of an injected field, the producer
method (`getDeclaredMethods`, `@TransientReference` per parameter), `isQualifier`, and `loadClass`.

### Repro
`tmp` application of the audit with a `@RequestScoped @Logged` resource injecting an intercepted `@Dependent` helper and a
`@Dependent` producer with a parameter; rtrace phase 2 over 2,500 requests: 20.0 reflective calls per request (80 for the
request-scoped resource alone) before, 0 from Vauban after.

### Fix
`ClassMetadataCache` keeps what depends on a declaration alone, once per class (`ClassValue`: a class a hot reload
replaces gets its own): class, method and constructor annotations, declared interceptor-binding and qualifier types,
`@Interceptor` classes, declared constructors, field generic types, an interceptor's `@PostConstruct`. What an extension
can change later (the custom interceptor binding types) is asked each time, not kept. The `$$init` handle is found once
per intercepted class by the container; the constructor of an intercepted factory once per argument count; the producer
method and its `@TransientReference` parameters once per factory; `loadClass` once per name. The reflective reading is
unchanged: it is what runs the first time and when nothing describes the class. Tests: `ClassMetadataCacheTest`,
`ContainerParameterResolutionTest#producerMethodServesRepeatedCalls`.

---

## VAU-COV-001 — The codegen coverage reports a bean built by a supplied factory as reflection
- **Date**: 2026-11-02 — **Status**: FIXED (fix/coverage-supplied-factories)
- **Severity**: low (dev console: wrong verdict, no runtime effect)

### Symptom
A bean an extension registers with `VaubanContainerBuilder.addFactory(Class, BeanFactory)` is built by that factory,
without reflection, but `CodegenCoverage` counted its instantiation as a constructor call the container makes, so the
dev console listed it as `reflection` (`MansartPoolHolder`, `KnockHolder`, `HumboldtHolder` once Vidocq registers them
that way).

### Fix
The builder records the classes given a factory; their instantiation is a `FACTORY` operation, covered with no
generator. A bean with nothing else to cover gets the new `FACTORY` verdict; next to generated operations it counts
as covered (`APT`, `CLASS_FILE`…). A managed bean with an `@Inject` constructor ignores a supplied factory, as the
container does. `CodegenCoverageTest` (`suppliedFactory*`, `withoutSuppliedFactoryIsReflection`).
**Downstream**: `CdiInventory.label` in vidocq switches exhaustively over `Verdict` and needs a `FACTORY` case
(`"factory"`) before this reaches a Vidocq build.

---

## VAU-PERF-008 — Every boot validates each bean class by reflecting on all its members
- **Date**: 2026-11-02 — **Status**: FIXED (perf/index-validation)
- **Severity**: low (boot time: `ReflectionValidator` read every field, method, constructor and parameter of every bean class)

### Symptom
After `ClassValidator` checked what the index could describe, `ReflectionValidator` loaded each bean class and read
its declared members, generic types and annotations by reflection to check the remaining definition rules (raw
`Event`/`Instance`, producer types with wildcards or type variables, generic initializers, `@Named` without value,
public fields of normal-scoped beans, `@Typed` values, stereotype names/scopes/priorities, conflicting interceptor
binding values).

### Fix
`IndexClassValidator` checks the same rules on the index, with the same messages: the generic member types
(VAU-PERF-007), the method type parameters now read from the `Signature`, the annotations a class inherits through
its indexed superclasses (in `Class.getAnnotations()` order), and `AnnotationTypes` for the annotation types — the
index when it holds them, otherwise the annotation type itself by reflection, once. A class it cannot decide (erased
member types, a superclass or a `@Typed` supertype outside the index) is validated by reflection, unchanged.
Verification: over the whole CDI Lite TCK and the module ITs, a temporary shadow run compared both validators on
16,557 bean classes: 15,818 decided by the index, identical errors (39 with errors; 2 differed only in the order of
a message, aligned), 737 left to reflection. At build, `vauban-processor` checks the same rules on the compiler's elements and reports a broken rule as a warning ("the container rejects this deployment at start"): a warning, not an error, because a class an extension vetoes at run time never becomes a bean, which the compilation cannot tell. Tests: `IndexClassValidatorTest`, `ClassFileScannerTest`, `ComponentProviderCompileTimeTest#definitionErrorIsReportedAtBuild`.

---

## VAU-PERF-007 — Discovery reads every producer and injection point by reflection to get its generic type
- **Date**: 2026-11-02 — **Status**: FIXED (perf/index-signatures)
- **Severity**: low (boot time: `getDeclaredField`/`getDeclaredMethods`/`getGeneric*` for every injection point and producer)

### Symptom
The class-file index typed fields, parameters and return types from their descriptors, so `List<String>` was
`List`. `BeanDiscovery` therefore loaded each bean class and read the member again by reflection
(`resolveGenericTypeForField`, `resolveGenericTypeForMethodParameter`, `reflectedProducerFieldType`,
`reflectedProducerMethodType`) to recover the type arguments. It also picked a producer method by name alone, so with
an overload the return type could come from the wrong method.

### Fix
`ClassFileScanner` reads the `Signature` attribute of fields and methods: type arguments, wildcards, type variables
with their bounds (the method's own, then the class's), and the leading synthetic parameters of an inner-class or
enum constructor. `ClassInfo.genericMembers()` tells discovery that the member types are the generic ones, and it
then takes them from the index, normalized to the form reflection gives (`BeanDiscovery.asReflected`). A signature
that does not parse leaves the erased type, as the reflective path did when reflection failed on it. An index with
erased types (`genericMembers` false) is still read by reflection, unchanged. Tests: `ClassFileScannerTest`
(`GenericMemberTypes`), `IndexSignatureEquivalenceTest` (index = reflection for every shape and for the public
methods of `ConcurrentHashMap`; index first; reflective fallback).

---

## VAU-PERF-006 — A private `@Inject` field is written by reflection at each creation of its bean
- **Date**: 2026-11-02 — **Status**: FIXED (perf/private-inject-lookups)
- **Severity**: low (reflection on the request path for `@RequestScoped` / `@Dependent` beans with private fields)

### Symptom
The generated `_VaubanComponents` assigned only non-private `@Inject` fields. For a private one the processor
warned "still needs opens" and the container wrote it with `VaubanLookup.setField` — `privateLookupIn` and
`unreflectSetter` on the `Field` — at every creation of the bean.

### Fix
The provider writes a private field through a `VarHandle` it finds once with its own lookup:
`privateLookupIn(owner, lookup()).findVarHandle(...)`. The provider is in the owner's module, so no `opens` is
needed. The generated source keeps the handles in one lazy holder per class; the bytecode generator loads each as a
dynamic constant. A field the provider cannot find is declined, and the container's reflective path takes over, as
before. A private field whose type is nested stays with the container in the generated source (its binary name is
not its source name). Without a generated provider (Weld, Open Liberty, a module built without the processor)
nothing changes: `PrivateFieldFallbackTest`. Tests: `ComponentCollectorMemberSelectionTest`,
`ComponentProviderClassGeneratorTest`, `ComponentProviderCompileTimeTest`, `ProducerAndProviderScenariosTest`.

---

## VAU-PERF-005 — Every boot replays the whole BCE lifecycle because no brick archive carries the BCE-processed marker
- **Date**: 2026-11-02 — **Status**: FIXED on `perf/frozen-extensions` (vauban#288): a build step that sees the whole
  deployment freezes what the extensions did, and the start of that deployment loads it instead of running them. See
  "Fix" below; the measurements are BENCH-20261113-01.
- **Severity**: medium (≈ 940 `Method.invoke` and a full discovery at every boot of a Vidocq application)

### Symptom
The reflection audit traced `BceProcessor.invokeWithArg`/`invokeRegistrationMethod` running ≈ 940 reflective calls at
boot. `VaubanContainerBuilder` skips the runtime BCE lifecycle only when every bean archive comes from a
"BCE-processed" source (`allSourcesProcessed`: no class outside a source with `META-INF/vauban-bce-processed`). In the
audit application (11 extensions), only the application jar carries the marker; none of the 18 brick bean archives
does (cassini-core, cervantes-cdi-vauban, champollion-jsonb-cdi, dirac-cdi-vauban, grimm-cdi-vauban,
heisenberg-cdi-vauban, mansart-data-cdi, ravel-cdi-vauban, …), so the full lifecycle runs again at runtime.

### Analysis
`VaubanProcessor` writes the marker only in the module whose compilation ran the BCEs. A brick's archive cannot carry
it on its own: the BCEs of *other* bricks, present only in the final application, may enhance its classes. The
application's build sees every BCE (codegen bundles) and, since VAU-APT-006, the dependency bean archives — so it is
the application build that could record, per dependency archive, that the BCE effects on it are baked in (e.g. extend
the replay list / frozen enhancement patch to dependency classes, or let `vidocq:generate`'s enriched copies carry a
marker scoped to the application's BCE set).

### Next
Decide where the "BCE effects baked in" fact lives for dependency archives (application build output vs enriched
copies), then make `allSourcesProcessed` true for a Vidocq application built with the codegen bundles, keeping the
runtime replay as the fallback for archives that were not processed. TCK 774/774 and the BUG-20261030-27 double-run
lesson apply.
Options and recommendation: `docs/design/vau-perf-005-bce-processed-dependencies.md`.

### What skipping the cycle as it was would have lost (2026-11-13)
Measured before any change, on an application with eleven extensions, the cycle forced off:
- a `@Counted` method answered 500, "Counter metric was removed from registry": the processor had frozen Dirac's
  synthetic bean with an empty class list, the application classes not being loadable inside javac;
- a missing required `@ConfigProperty` and an invalid JWT key no longer stopped the start (500 at each request): Ravel
  and Cervantes check them in their phases, guarded by `ExtensionPhase.isBuildTime()`;
- Humboldt's extension was not on the processor path at all (vidocq#546): nothing of it was frozen.
So "mark the archives" was not enough: what is frozen must be what the start computes, on the same deployment.

### Fix
Neither option A nor B of the design note as written. No brick jar has an enriched copy to mark (the test
distribution had none: sixteen bean archives used as published), and a per-archive answer cannot say what an extension
of another archive did. The record is the deployment's:
- `VaubanContainerBuilder.freezeExtensions(output)` runs the extension cycle the way `build()` does, at build time, and
  writes `META-INF/vauban-frozen-extensions.properties` (fingerprint, extensions, those that run at start, what
  `@Discovery` registered), `vauban-frozen-enhancements.properties` and `vauban-frozen-synthetic.properties`;
  `vauban:generate` calls it with `freezeExtensions` (`ExtensionFreezer`), on the runtime class path alone.
- `build()` loads a record whose fingerprint is the deployment's (`DeploymentFingerprint`: bean class names, the
  checksum of their class files, the discovery settings) instead of running the extensions; anything else is the
  former path, untouched, which is the one every TCK deployment takes.
- An extension that asked `ExtensionPhase.isBuildTime()` runs again at start (`ExtensionPhase.tellWhenAsked`,
  `ExtensionAsks`); the build can name others. Its results are left out of the record.
- No record when an extension fails, removes annotations, or registers what the metadata cannot write.
- `addHostBean` keeps the beans of the embedding runtime out of the fingerprint.
- A synthetic bean keeps a parameterized type in the metadata (`bean.N.genericType.I`), which the replay at start
  used to hide (the rest of BUG-20261008-01).
- A `Startup` observer that throws a `DeploymentException` or a `DefinitionException` stops the start with it: the
  place for a check of the start environment once the extensions are frozen.
Tests: `FrozenExtensionsTest` (each case boots with and without the record; two mutations checked: record never
used, asks ignored), `FrozenExtensionsRecordTest`, `DeploymentFingerprintTest`, `ExtensionFreezerTest`,
`LifecycleObserverFailureTest`, `ExtensionPhaseTest`.

---

## VAU-PERF-003 — Every creation of a bean reads its class again by reflection
- **Date**: 2026-11-02 — **Status**: FIXED (perf/creation-plan-cache)
- **Severity**: medium (reflection on the request path: a `@RequestScoped` or `@Dependent` bean is created on every request)

### Symptom
The reflection audit's tracer counted 1,532 reflective calls from vauban-core while serving requests in an
application with eleven extensions: at each creation of a bean, `BeanInjector.injectionOrder` walked the hierarchy
with `getDeclaredFields`/`getDeclaredMethods` and `isAnnotationPresent(@Inject)` on every member,
`BeanLifecycle`/`ManagedBean` collected the `@PostConstruct`/`@PreDestroy` methods the same way,
`buildTypeVariableMapping` read the generic superclasses, the `@Inject` constructor and its parameters
(`getDeclaredConstructors`, `getParameters`, qualifiers, `@TransientReference`) were looked up again, and the
`@AroundConstruct` interceptors of an intercepted bean were matched from the class's and the constructor's
annotations.

### Fix
What these read depends on the class alone (and the bean's descriptor), so it is worked out once and reused: the
injection order per class and descriptor, the lifecycle callbacks per class and kind, the type-variable mapping per
class (all in `ClassValue`s, so a reloaded class gets its own), the constructor plan per bean factory, and the
`@AroundConstruct` interceptors per class, constructor and bindings (`InterceptorManager`, cleared like the method
chains when an `@Enhancement` adds a binding). The reflective computation itself is unchanged; it runs once.
`CreationPlanCacheTest`. Step 2 (perf/creation-plan-build): the injection order and the lifecycle callbacks of a class come from the index (`IndexCreationPlans`, identical to the reflective computation over the CDI Lite TCK), and each planned member is looked up directly instead of scanning the hierarchy (`IndexPlans`); a class the index does not cover keeps the step-1 computation. `IndexCreationPlansTest`. Design and follow-up (members made only when `getMember()` asks): `docs/design/vau-perf-003-step2-build-time-plan.md`. Traced again: 6 reflective calls from vauban-core while serving the same requests
(`ModuleLookups$Deployment.privateLookupIn`).

---

## VAU-PERF-004 — `InvocationContext.getInterceptorBindings()` reads the annotations on every intercepted call
- **Date**: 2026-11-02 — **Status**: FIXED (perf/creation-plan-cache)
- **Severity**: low (reflection on every call of an interceptor that asks for its bindings, e.g. Mansart's `@Transactional`)

### Symptom
For a business method, `VaubanInvocationContext.getInterceptorBindings()` derived the bindings from
`beanClass.getAnnotations()`, `method.getAnnotations()` and the meta-bindings on each invocation.

### Fix
The derived set is computed once per class, method, container and set of custom binding types (a BCE may declare
one after a first derivation) and shared, unmodifiable. `InterceptorBindingsCacheTest`;
`CustomInterceptorBindingRegressionTest` still green.

---

## VAU-PERF-001 — The request context's lifecycle events reach `AnnotationLiteral` by reflection
- **Date**: 2026-11-02 — **Status**: FIXED (fix/no-reflection-per-request)
- **Severity**: low (reflection on the request path, against the build-time-first rule)

### Symptom
The ecosystem reflection audit (an agent tracing reflective calls in an application with eleven extensions) saw
`VaubanContainer.requestContextEvents` call `AnnotationLiteral.hashCode()`, which reads the annotation's members with
`getDeclaredMethods` and `Method.invoke`: the observed-or-not cache was a `ConcurrentHashMap` keyed by the qualifier.
`AnnotationLiteral` caches its hash, so in practice it ran on the first request; but the request path depended on
reflection and on that cache.

### Fix
`ObservedQualifiers` matches the qualifiers by identity (the request context always passes the same three
`Literal.REQUEST` constants), resolves each one's observers once, and fires only when observed. No `hashCode` or
`equals` is called on the qualifier (`ObservedQualifiersTest`). Traced again: no reflective call left from this site.

---

## VAU-PERF-002 — A primitive `@Inject` field is written with `Field.set`
- **Date**: 2026-11-02 — **Status**: FIXED (fix/no-reflection-per-request)
- **Severity**: low (reflection at every creation of the bean)

### Symptom
`@Inject @ConfigProperty int times` in a REST resource was missing from the generated provider's `injectField`:
`ComponentCollector.collectFields` dropped primitive fields, so the container wrote them through
`VaubanLookup.setField` (unreflectSetter) at each creation of the bean, and the dev console's codegen coverage showed
the field as *partial*.

### Fix
A primitive field is kept under its keyword type (`int`). The generated source assigns `b.times = (int) value`, and
the bytecode generator unboxes the wrapper; a `null` value is declined (`false`), leaving the container's path to
report it as before. Tests: `ComponentCollectorMemberSelectionTest`, `ComponentProviderClassGeneratorTest`
(`injectFieldUnboxesIntoPrimitiveFields`), `ComponentProviderCompileTimeTest` (`providerAssignsPrimitiveInjectField`,
red without the change). Traced again: no `VaubanLookup.setField` call left.

---

## VAU-DISC-003 — An `@Inject` constructor is typed from another constructor of the same arity
- **Date**: 2026-10-30 — **Status**: FIXED (fix/overloaded-inject-constructor-type)
- **Severity**: high (a valid deployment fails as ambiguous or unsatisfied, or an injection point gets the wrong type)

### Symptom
Grimm's `GrimmModelCache` declares `@Inject GrimmModelCache(GrimmConfig, Instance<ScannedTypes>)` and
`GrimmModelCache(GrimmConfig, ScannedTypes)`. With two `ScannedTypes` beans (one frozen at build time, one registered
at run time), the deployment failed: _Ambiguous dependency: parameter 1 of GrimmModelCache() (…GrimmConfig;…Instance;)V
of type ClassType[name=io.vidocq.grimm.cdi.ScannedTypes]_. An `Instance` injection point is never validated (CDI 4.1
"Unsatisfied and ambiguous dependencies").

### Repro
`OverloadedInjectConstructorTest`: a bean with `Holder(Config, Part)` declared before
`@Inject Holder(Config, Instance<Part>)`, and two `Part` beans.

### Cause
`BeanDiscovery.resolveGenericTypeForMethodParameter` looked for the reflected constructor (or method) with the same
number of parameters as the indexed one, and took the first: the overload `(Config, Part)` typed the second parameter.

### Fix
The reflected executable must have the same erased parameter types as the indexed one (`sameErasedParameters`), for
constructors and methods alike.
## VAU-APT-008 — An application with Fault Tolerance and mansart-transactions no longer compiles
- **Date**: 2026-10-31 — **Status**: FIXED (fix/apt-no-class-init)
- **Severity**: high (the build fails; regression of VAU-APT-006)

### Symptom
With the Vidocq SNAPSHOT 0.4.0-20261031.015020-10, `./mvnw compile` of an application with
`heisenberg-fault-tolerance` and `mansart-transactions` fails: `Fatal error compiling: java.lang.NoClassDefFoundError:
io/opentelemetry/api/common/AttributeKey` at `io.vidocq.heisenberg.cdi.internal.OtelFtMetrics.<clinit>`, called from
`QualifierResolver.loadWithFallback` ← `getInheritedAnnotations` ← `StereotypeResolver.isAlternativeWithStereotypes` ←
`BeanDiscovery.discoverBeans` ← `VaubanProcessor.resolveDependencyBeans`. Reported by the vidocq-tutorial session
(steps 06 to 11).

### Repro
`vidocq create --name m --group-id com.example -x cassini-rest`, `vidocq extension add heisenberg-fault-tolerance
mansart-transactions`, `./mvnw compile`. Unit: `QualifierResolverLoadingTest`.

### Cause
Since VAU-APT-006 the processor discovers the beans of the dependencies' bean archives. `loadWithFallback` loads a
class through the context class loader without initialising it, but its fallback, `Class.forName(name)`, initialises
it. Under javac the context loader does not see the dependencies, so the fallback ran `OtelFtMetrics`' static
initialiser inside the compiler, where the OpenTelemetry API is not on the processor path.

Once that was fixed, the same path failed on `NoClassDefFoundError: org/eclipse/microprofile/metrics/annotation/RegistryScope`:
a dependency's class whose supertypes or annotations are not on the processor path cannot even be linked under javac,
and `getInheritedAnnotations` only caught `ClassNotFoundException`.

### Fix
The fallback loads through `QualifierResolver`'s loader with `initialize=false`; reading annotations does not need an
initialised class. A class that cannot be loaded or linked (`LinkageError`, `TypeNotPresentException`) has no inherited
annotations beyond those the index read; the runtime container discovers it again with its full module graph.
`QualifierResolverLoadingTest` (both cases, red then green); the tutorial's repro (`cassini-rest`,
`heisenberg-fault-tolerance`, `mansart-transactions`) compiles.

### Follow-up (fix/apt-linkage-errors)
The same hazard sat on the other loads discovery and the processor make under javac:
- `DeploymentClasses.load`, used by about twenty discovery reads (`BeanDiscovery`, `InterceptorDiscovery`,
  `QualifierResolver.isQualifier`, `DeploymentValidator`), let a `LinkageError` through its callers, which only catch
  `ClassNotFoundException`; and without a context class loader it fell back on `Class.forName(name)`, which
  initialises. It now loads through its own loader with `initialize=false`, and reports a class that cannot be linked
  as a `ClassNotFoundException` whose cause is the `LinkageError`, so every caller keeps what the index read.
- `VaubanProcessor.loadArchiveClasses`, which hands the archive's classes to the Build Compatible Extensions (reached
  whenever a codegen bundle puts a BCE on the processor path), skips a class it cannot link instead of failing.
`DeploymentClassesTest` (two tests) and `LoadArchiveClassesTest`, red then green.

---

## VAU-APT-006 — An injection point a dependency's producer satisfies warns at every build
- **Date**: 2026-10-30 — **Status**: FIXED (feat/build-time-dependency-beans)
- **Severity**: low (a false warning at every build; the injection works at start-up)

### Symptom
An application that injects a type produced by a bean of another module warned at each compilation:
`[Vauban] Unsatisfied dependency: field LoanResource.caller of type …JsonWebToken … — the required type is not declared
by this compilation unit; resolution deferred to the runtime container.` The Vidocq tutorial hit it with
`@Inject JsonWebToken`, produced by `JsonWebTokenProducer` in cervantes-cdi-vauban.

### Repro
`BeanArchiveModuleInjectionTest`: a module `repro.core` with an interface `TokenService` and, in a non-exported
package, an `@ApplicationScoped` bean producing it; an application module injecting `TokenService`, compiled on the
module path: the warning above.

### Cause
`resolveDependencyBeans` (vauban#23) indexes the required type itself through `Elements`: a dependency type that is a
bean resolves, an interface whose producer or implementation is another class of its module stays deferred, since the
processor never looked at the rest of that module.

### Fix
For the types still deferred, the processor reads the bean archive modules on the module path: the named modules that
`provides io.vidocq.vauban.api.VaubanComponentProvider`, one implementation per package of beans. It scans those
packages through `Elements` (class files, nothing loaded or run), replays discovery, and adds the beans whose types match
a deferred type to the resolver only: never validated, generated or listed by the application. An ambiguity among
them is left to the container as a warning. A module on the class path, a bean a BCE adds at start-up only, and a type
nobody provides stay deferred, with the same warning.

---
## VAU-APT-007 — Every generated class costs javac's "created in the last round" warning; BCE logs print in JUL's format
- **Date**: 2026-10-30 — **Status**: FIXED (fix/build-noise-26)
- **Severity**: low (build noise: 17 warnings and raw two-line logs at every build of the vidocq tutorial)

### Symptom
Every build of an application printed `[WARNING] File for type '…_ClientProxy' / '…$$Intercepted' /
'…._VaubanComponents' created in the last round will not be subject to annotation processing.`, one per generated
class, and the Build Compatible Extensions run at build time logged through java.util.logging's console handler:
two lines each, date and level in the machine's language (`oct. 30, 2026 … CassiniScopeExtension …` / `INFOS: …`).
Reported by the vidocq tutorial (vidocq BUG-20261030-26).

### Repro
`GenerationRoundTest` (a companion processor writing a class in round 1, as Cassini does) and `BceLoggingTest`.

### Cause
The pipeline ran in javac's last round, on purpose: companion processors write their classes in the first round,
and they surface in the second. javac warns, without any `-Xlint` switch (`JavacFiler`), for every file created in
the last round. The BCEs ran with the JDK's default logging configuration.

### Fix
- The pipeline runs in the first round, from the second on, whose new classes brought no bean, before the last one.
  Kept in the last round:
  - when there is no second round;
  - when an indexed class carries an `@Inherited` annotation that is not CDI's, JAX-RS's or MicroProfile OpenAPI's:
    Vauban's subclasses inherit it, and the next round's processors would take them for their own (Dirac's
    `@Counted`, Heisenberg's `@Retry`);
  - with `-Avauban.generationRound=last`.

  A bean another processor generates after the pipeline ran fails the build by name, instead of being missing from
  `_VaubanComponents`.
- While the BCEs run, java.util.logging goes to javac's messages: INFO a note, WARNING and SEVERE a warning, one line
  each, prefixed `[Vauban BCE]`, parameters formatted in `Locale.ROOT` (`BceLogBridge`).

## VAU-INT-010 — `Interceptor.intercept` of the SPI wraps exceptions in `InvocationTargetException`
- **Date**: 2026-10-28 — **Status**: FIXED (fix/interceptor-inject-constructor)
- **Severity**: medium (a caller of the SPI sees the wrong exception type: exception mappers, `@Retry(retryOn)`,
  `@Fallback(applyOn)`, rollback rules and plain `catch` blocks all miss it)

### Symptom
Through the `Interceptor` bean `BeanManager.resolveInterceptors` returns, `intercept(AROUND_INVOKE, instance, ctx)`
threw a `java.lang.reflect.InvocationTargetException` whenever the interceptor method, or the chain it proceeds to,
threw: a checked business exception, a `RuntimeException` or an `Error` never reached the caller as itself. Jakarta
Interceptors 2.2 "Exceptions": interceptor methods throw runtime exceptions or the checked exceptions of the target
method, which the caller must see; Weld and OpenWebBeans unwrap the reflective call.

### Repro
`VaubanInterceptorExceptionTest`: resolve a proceeding interceptor, `create` it and `intercept` with a context whose
`proceed` throws a `BusinessException`, an `IllegalStateException` or an `AssertionError`: each came back as an
`InvocationTargetException`.

### Cause
`VaubanInterceptor.intercept` called the `@AroundInvoke` method with `Method.invoke` and let its
`InvocationTargetException` escape. The container's own interception path (`InterceptorBeanWrapper`) already unwrapped it.

### Fix
`intercept` rethrows the cause of the `InvocationTargetException`: an `Exception` or an `Error` as itself; only a bare
`Throwable`, which `intercept` cannot declare, is wrapped in an `UndeclaredThrowableException`.


### Re-check (2026-10-31, tutorial finding S4) — REOPENED, then FIXED (fix/apt-write-before-last-round)
A first re-check, without a Jakarta Data repository, found no warning and wrongly blamed an older SNAPSHOT. The tutorial
session reproduced it on the current SNAPSHOT (vauban-processor 0.4.0-20261031.025825-140): with `mansart-data` and a
`@Repository`, `mvn compile` prints 3 × `created in the last round` (`HelloResource_ClientProxy` and both
`_VaubanComponents`), 8 with the tutorial's step-03 code.

**Cause**: the pipeline waited for a round whose new classes brought no bean. Mansart Data's processor writes
`BookRepositoryImpl` (a `@Singleton` bean), `_Book` and `Book_` in the first round; they reach Vauban in the second,
which therefore was not quiet, and nothing came after it: the next round was javac's last.

**Fix**: a round whose newly indexed classes only carry annotations no companion processor acts on counts as quiet:
`java.lang`, `javax.annotation.processing` (`@Generated`), `jakarta.annotation`, `jakarta.inject`,
`jakarta.enterprise.context` / `.inject`, and the static metamodel markers (`jakarta.persistence.metamodel`,
`jakarta.data.metamodel`). Such a class cannot make another processor generate more; a generated class with any
other annotation still makes Vauban wait, and a bean generated after the pipeline ran still fails the build by name.
`GenerationRoundTest.aBeanACompanionGeneratesWithInertAnnotationsIsListedBeforeTheLastRound` (red, then green) and
`aBeanACompanionGeneratesWithAForeignAnnotationIsWaitedFor`; the tutorial's repro and step-03 code compile without
the warning, `BookRepositoryImpl` still listed.
---

## VAU-INT-009 — An interceptor with an `@Inject` constructor and no no-arg constructor fails deployment
- **Date**: 2026-10-28 — **Status**: FIXED (fix/interceptor-inject-constructor)
- **Severity**: medium (a valid CDI interceptor is refused at boot; the workaround is an extra no-arg constructor)

### Symptom
An interceptor declaring only an `@Inject` bean constructor, e.g.
`@Inject GreetingInterceptor(@Formal Salutation salutation)`, failed the boot with a `DefinitionException`:
`Interceptor … must have a non-private no-arg constructor`. CDI treats an interceptor as a managed bean, so its bean
constructor may be an `@Inject` one (CDI 4.1 "Declaring a bean constructor"); the CDI TCK uses such interceptors
(`builtin.metadata.broken.typeparam.interceptor.InterceptorConstructor`, `InterceptedBeanConstructor`). Besides, the
`Interceptor` bean `BeanManager.resolveInterceptors` returns created its instance with `getDeclaredConstructor()`, so
its `create` failed for such an interceptor, and skipped the injection and `@PostConstruct` the container performs.

### Repro
`InterceptorInjectConstructorTest`: boot a container with an interceptor whose only constructor is
`@Inject GreetingInterceptor(@Formal Salutation)`, call an intercepted method, and create the interceptor through
`resolveInterceptors(AROUND_INVOKE, …).getFirst().create(ctx)`. `DeploymentValidatorReflectionChecksTest
.interceptorInjectConstructors` checks the rule on descriptors.

### Cause
`DeploymentValidator.validateInterceptorConstructors` required a non-private no-arg constructor and ignored
`@Inject` constructors. The interception path (`InterceptorBeanWrapper.findConstructor`, the generated
`_VaubanComponents` arm) already picked the `@Inject` constructor and resolved its parameters; `VaubanInterceptor`
had its own reflective, no-arg creation.

### Fix
An interceptor needs exactly one `@Inject` constructor, or else a non-private no-arg constructor: two `@Inject`
constructors, or neither, is a definition error. `VaubanInterceptor.create` goes through the container's own
interceptor creation (`InterceptorManager.getOrCreateInstance`), with a creational context of its own when given
none; the reflective no-arg creation stays only for an instance built outside a container.

---

## VAU-APT-005 — Built by Eclipse's compiler (Eclipse, VS Code), an application loses its beans without a word
- **Date**: 2026-10-25 — **Status**: FIXED (fix/eclipse-apt-file-object)
- **Severity**: high (the application starts and answers 404 on its own REST resources; nothing says why)

### Symptom
A Vidocq application imported in VS Code (Java extension, jdt.ls 1.61: Eclipse JDT with m2e) or Eclipse, built by
the IDE, had no `META-INF/vauban-beans.list`, no `META-INF/services/io.vidocq.vauban.api.VaubanComponentProvider` and
no `META-INF/vauban-bce-processed` in `target/classes`. Started on that output, it booted (`anomalies none`) with one
REST resource class instead of two, and `GET /hello` answered 404. The IDE's log had `Exception thrown by Java
annotation processor io.vidocq.vauban.processor.VaubanProcessor` with `java.nio.file.FileSystemNotFoundException` at
`ElementsImpl9.getFileObjectForType`. Found while checking Vidocq/vidocq#339 (evolution 2) in VS Code.

### Repro
`EclipseCompilerFileObjectTest`: the processor runs behind an `Elements` that throws `FileSystemNotFoundException`
from `getFileObjectOf` for a type read from the class path, as Eclipse's does; its output must be javac's. By hand:
jdt.ls headless on an application created by `vidocq create -x cassini-rest` (Vidocq/vidocq scratch probe), then the
runtime on the IDE's `target/classes`.

### Cause
Eclipse's `Elements.getFileObjectOf` throws `FileSystemNotFoundException` for a type read from a jar: it builds a
`Path` from a `jar:` URI without the zip file system. `compiledHere` (which annotation types are this compilation's)
and `isTestRound` called it unguarded, so the last round stopped before writing the bean list, the provider service
and the BCE marker.

### Fix
`fileObjectOf` catches the compiler's failure and answers "no file": a type whose file the compiler cannot give is on
the path, not a source of this compilation. Checked in jdt.ls: no processor exception, the IDE's output has the same
files as Maven's, and the application answers 200. Upstream: the exception itself is Eclipse JDT's.

---

## VAU-APT-004 — The processor tells every Vidocq application to declare a `provides` it does not need
- **Date**: 2026-10-24 — **Status**: FIXED (fix/provides-hint, Vidocq/vidocq#336)
- **Severity**: low (a note on every build; the application works without the change it asks for)

### Symptom
Every build of an application created by `vidocq create` prints `[Vauban] Generated <pkg>._VaubanComponents (…). On
the module path, add to module-info: provides io.vidocq.vauban.api.VaubanComponentProvider with
<pkg>._VaubanComponents;`. The application has no such `provides`, and works under `vidocq dev`, `vidocq start` and
from a jlink image (checked 2026-10-24, Vidocq/vidocq BUG-20261024-10). A user who follows the advice edits a file
for nothing; one who does not wonders whether the build is broken.

### Repro
`ProvidesHintTest`: a module with one `@ApplicationScoped` bean; the note gives the advice, and there was no way to
leave it out.

### Cause
The advice is right for Vauban alone: in a named module `ServiceLoader` reads `provides` only, not
`META-INF/services`. Vidocq's application layer turns the `META-INF/services` entries the processor writes into
`provides` when it defines the module, so a Vidocq application needs nothing. The processor cannot tell the two
apart.

### Fix
`-Avauban.providesHint=false` (a supported option, so javac does not report it as unrecognized) keeps the note and
drops the advice; the Vidocq runtime parent sets it. Default unchanged. Test `ProvidesHintTest`.

## VAU-BCE-007 — `VaubanClassInfo.typeParameters()` always returns an empty list
- **Date**: 2026-10-24 — **Status**: FIXED (fix/vauban-tickets, vauban#207, Vidocq/vidocq#285)
- **Severity**: low (a build compatible extension cannot read the type parameters of a generic class, nor get a
  type variable from it; the CDI TCK does not cover it)

### Symptom
`ClassInfo.typeParameters()`, in the lang model a build compatible extension sees, returned an empty list for every
class, generic or not, in `@Enhancement` as in `@Registration`. The lang model contract is the type parameters the
class declares, as `TypeVariable`s with their bounds. Found while fixing vauban#188 (Vidocq/vidocq#285).

### Repro
`BceClassTypeParametersTest` (vauban-core): a `@Dependent` bean `Holder<T, N extends Number & Comparable<N>,
L extends List<N>>`, read by an `@Enhancement(types = Holder.class)` and a `@Registration` method; both saw `""`
instead of `T extends java.lang.Object; N extends java.lang.Number & java.lang.Comparable<N>; L extends
java.util.List<N>`.

### Cause
`VaubanClassInfo.typeParameters()` was a stub returning `List.of()`, and the index had nothing to back it: the
bytecode scan (`ClassFileScanner`) read descriptors only, never the class `Signature` attribute, and the processor's
`ElementScanner` did not record the type parameters of the `TypeElement`.

### Fix
The index `ClassInfo` gains a `typeParameters` component (`List<TypeInfo.TypeVariable>`; the former constructors
remain and record none). The bytecode scan reads it from the class `Signature` attribute with the Class-File API
(new `ClassTypeParameters`), the processor from `TypeElement.getTypeParameters()`, with the same rules: declaration
order, the implicit `java.lang.Object` bound left out as everywhere in the index, a bound naming another type
parameter of the class carries that parameter's bounds, and a type variable met again while its own bounds are read
(`T` in `<T extends Comparable<T>>`) or one of an enclosing class is recorded without bounds, so the model stays
finite. A class signature that does not parse records no type parameter instead of failing the scan.
`VaubanClassInfo.typeParameters()` maps them to `VaubanTypeVariable`s, giving a type parameter that declares no
bound `java.lang.Object` as its single bound, as the lang model requires. No reflection: both paths read the
index. A class outside the index (a JDK type reached through `ClassType.declaration()`) still has none. Tests:
`BceClassTypeParametersTest.typeParametersAreExposedWithTheirBounds` (vauban-core),
`ClassFileScannerTest$TypeParameters` and `ClassInfoTest` (vauban-indexer), `ElementScannerTypeParametersTest` (vauban-processor: the
processor and the bytecode scan agree).
## VAU-LIFE-003 — `Bean#destroy(instance, null)` of an intercepted bean silently skips `@PreDestroy`
- **Date**: 2026-10-24 — **Status**: FIXED (fix/vauban-tickets, vauban#203, Vidocq/vidocq#285)
- **Severity**: low (only a direct `Bean#destroy` with no creational context; the container always passes one)

### Symptom
`Bean#destroy(instance, null)` on a bean bound to a `@PreDestroy` interceptor returned without error, but neither the
interceptor's `@PreDestroy` method nor the bean's own `@PreDestroy` method ran. Found while fixing vauban#200.

### Repro
`InterceptedBeanDestroyWithoutCreationalContextTest` (vauban-core): a bean whose interceptor and class both declare a
`@PreDestroy` method, destroyed with `Bean#destroy(instance, null)`; no callback ran before the fix (`expected:
<[pre-destroy, target-pre-destroy]> but was: <[]>`).

### Cause
`ManagedBean.destroy` handed the null context on to its `@PreDestroy` step. With no context,
`InterceptorManager.resolveLifecycleChain` cannot resolve the interceptor instances and puts an
`InterceptorInvocation(null, null)` placeholder in the chain. The invocation context failed on it before reaching any
callback, and the `catch` meant for exceptions thrown by `@PreDestroy` callbacks swallowed that failure too.

### Fix
`ManagedBean.destroy` of an intercepted bean now creates a creational context of the bean's own when the caller passes
none, as `ManagedBean.create` does since VAU-LIFE-002. The `@PreDestroy` interceptors are resolved from it, the
callbacks run through them (CDI 4.1 §7.3.1 and §5.5.3, Jakarta Interceptors 2.2), and the context is released
afterwards with the interceptor instances it holds. An exception thrown by a `@PreDestroy` callback is still caught and
not reported, as CDI 4.1 §6.1 requires ("If an exception occurs while destroying an instance, the exception must be
caught by the destroy() method"; Jakarta Annotations 3.0 `@PreDestroy`: "it is ignored by the container"). Tests:
`InterceptedBeanDestroyWithoutCreationalContextTest.preDestroyRunsThroughItsInterceptor` and
`.failingPreDestroyIsIgnored` (vauban-core).

---

## VAU-LIFE-002 — `Bean#create(null)` of an intercepted bean fails in its `@PostConstruct` step
- **Date**: 2026-10-24 — **Status**: FIXED (fix/vauban-tickets, vauban#200, Vidocq/vidocq#285)
- **Severity**: low (only a direct `Bean#create` with no creational context; the container always passes one)

### Symptom
`Bean#create(null)` on a bean bound to a lifecycle interceptor (`@PostConstruct`), once vauban#192 let it past the
factory, failed with `NullPointerException: Cannot invoke "java.lang.reflect.AccessibleObject.trySetAccessible()"
because "member" is null`, from `VaubanInvocationContext.makeAccessibleSafe`, in `BeanLifecycle.callInterceptedPostConstruct`.

### Repro
`InterceptedBeanCreateWithoutCreationalContextTest` (vauban-core): `Bean#create(null)` on a bean whose interceptor
has `@AroundConstruct`, `@PostConstruct` and `@AroundInvoke` methods, and on a bean bound to a `@PostConstruct`
interceptor only; both failed before the fix.

### Cause
`ManagedBean.create` handed the null context on to the injector, which runs the `@PostConstruct` step. With no context,
`InterceptorManager.resolveLifecycleChain` cannot resolve the interceptor instances and puts an
`InterceptorInvocation(null, null)` placeholder in the chain, which the invocation context dereferences. The
creational context the intercepted factory made of its own (VAU-INT-008) stays inside the factory.

### Fix
`ManagedBean.create` of an intercepted bean (the only beans with an interceptor manager) now creates a creational
context of the bean's own when the caller passes none, and hands it to both the factory and the injector. The
`@PostConstruct` interceptors are resolved from it, and one interceptor instance per interceptor class, a dependent
object of the bean instance (CDI 4.1 §6.4.1), serves its construction, its `@PostConstruct` and its methods (Jakarta
Interceptors 2.2 §2.2). Beans without interceptors still get the caller's context, null included. Tests:
`InterceptedBeanCreateWithoutCreationalContextTest.aroundConstructPostConstructAndAroundInvoke` and
`.postConstructOnly` (vauban-core).
## VAU-APT-002 — `VaubanProcessor`: a broken extension service entry fails the compilation instead of being skipped
- **Date**: 2026-10-24 — **Status**: FIXED (fix/vauban-tickets, vauban#199, Vidocq/vidocq#285)
- **Severity**: low (a malformed `META-INF/services` entry for `BuildCompatibleExtension` on the processor path)

### Symptom
A `META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension` entry on the annotation
processor path that names a class which cannot be loaded or instantiated fails the whole compilation with
`java.util.ServiceConfigurationError: … Provider com.missing.AbsentExtension not found`, raised from the processor's
`init`. The extensions listed next to it never run. Found while fixing VAU-MVN-002 (vauban#189), the same defect in
`vauban:generate`.

### Repro
Put a service file listing a missing class and a working extension on the processor path; compile any bean with the
processor.

### Cause
`VaubanProcessor.discoverBceClasses` ran `ServiceLoader.forEach` inside a `catch (Exception)`. `ServiceLoader` reports
a broken provider with `ServiceConfigurationError`, an `Error`, which that catch does not handle.

### Fix
The discovery walks the `ServiceLoader` iterator itself, as `vauban:generate` does since VAU-MVN-002: a provider that
fails with `ServiceConfigurationError` or a `LinkageError` is skipped with a compiler warning naming the service file
and the provider, and the extensions after it still run. The same error twice in a row ends the discovery, since
`ServiceLoader` cannot move past it (the service files themselves cannot be listed). Skipping with a warning matches
`vauban:generate` and the processor's warning for build compatible extensions it cannot load (vauban#29); CDI 4.1
§12.1 only defines a build compatible extension as a service provider declared in `META-INF/services`. Test:
`BceCompileTimeTest.aBrokenExtensionServiceEntryIsSkippedWithAWarningAndTheOthersStillRun` (vauban-processor).
## VAU-APT-003 — A processor-run extension cannot add a synthetic observer of a class compiled in the same build
- **Date**: 2026-10-24 — **Status**: FIXED (fix/vauban-tickets, vauban#210, Vidocq/vidocq#285)
- **Severity**: medium (a valid extension fails the build; the container runs it fine)

### Symptom
When the annotation processor runs a build compatible extension that adds, in `@Synthesis`, a synthetic observer of a
class compiled in the same build (`addObserver(types.ofClass(info))`, the `ClassInfo` taken in `@Registration`, or
that class as a type argument), the build fails with `[Vauban BCE] @Synthesis error: Cannot resolve type: <class>`.
Found while fixing vauban#206.

### Repro
`BceCompileTimeTest.syntheticObserverOfAClassOfTheSameBuild` (vauban-processor): an extension observes `Ping` and
`List<Ping>`, both compiled with it; the compilation failed
(`compilation failed: [ERROR: [Vauban BCE] @Synthesis error: Cannot resolve type: Ping]`).

### Cause
`VaubanSyntheticObserverBuilder(Type)` built the reflective form of the observed type up front, and loaded each class
it names with `Class.forName`. In the processor, a class the compilation is still producing has no class file yet, so
no class loader can load it. The processor never uses that reflective form: since VAU-OBS-005 it records the index
form, and the boot loads its classes by name.

### Fix
At build time (`ExtensionPhase.isBuildTime()`, which the processor sets around the extensions it runs), the builder
keeps the reflective form by name only, in the notation of the metadata, as the boot already does for a recorded
generic type (VAU-OBS-005); the index form is unchanged, so the metadata the processor writes is the same as before.
When the container runs the extension, the observed type is still loaded in the builder, and a type it cannot load
still fails the `@Synthesis` method with "Cannot resolve type". Tests:
`BceCompileTimeTest.syntheticObserverOfAClassOfTheSameBuild` (vauban-processor: compiled with the processor, booted,
`Ping` and `List<Ping>` events received) and `SyntheticObserverBuildTimeTypeTest` (vauban-core: by name at build
time, "Cannot resolve type" at container start).

---

## VAU-OBS-005 — On the processor path, a synthetic observer of a parameterized type is silently skipped at boot
- **Date**: 2026-10-24 — **Status**: FIXED (fix/vauban-tickets, vauban#206, Vidocq/vidocq#285)
- **Severity**: medium (the build succeeds, and the observer never receives an event, without a word)

### Symptom
When the annotation processor runs a build compatible extension that adds, in `@Synthesis`, a synthetic observer of
a parameterized type such as `List<String>`, the application boots, and the observer never receives a `List<String>`
event. Nothing is logged. Since VAU-OBS-003, an observer of a type with a wildcard, a generic array or a type variable
fails the build instead (the metadata could not carry it). Found while fixing vauban#188.

### Repro
`SyntheticComponentRegistrationTest.recordedParameterizedObserver` (vauban-core): metadata recording
`observer.0.eventType=java.util.List<java.lang.String>`, as the processor writes it; a `List<String>` event fired at
boot reached no observer (`expected: <[list:[a]]> but was: <[]>`).

### Cause
`SyntheticMetadataSerializer` wrote the observed type by its type name (`java.util.List<java.lang.String>`), and
`SyntheticComponentRegistrar.registerAptObserver` loaded that name with `Class.forName`. Loading a type name fails, and
the `ClassNotFoundException` is the one that skips an observer whose class is missing, without a word.

### Fix
The metadata writes the index form of the observed type (`ObservedTypeNotation`) in a notation the boot reads back:
a class by its name, as before; a parameterized type as before (`java.util.List<java.lang.String>`); wildcards
(`?`, `? extends U`, `? super L`); arrays (`java.lang.String[]`, `int[]`); a type variable with its bounds
(`(N extends java.lang.Number)[]`). A class and a parameterized type of classes are written as the previous version
wrote them, so its metadata reads the same. At boot, a class is loaded as before; any other type is registered with
its index form, whose classes the observer resolution loads by name; a value the boot cannot read fails the
deployment (`synthetic observer of …: not an observed type…`). The refusal VAU-OBS-003 added to the serializer is
lifted: every shape is recorded. `addObserver(String[].class)` already received `String[]` events on both paths (the
class name `[Ljava.lang.String;` is loaded at boot); tests now pin it. Tests:
`SyntheticComponentRegistrationTest` (`recordedParameterizedObserver`, `recordedObserversOfEveryShape`,
`unreadableRecordedObservedTypeFailsTheDeployment`, `classArrayObserver`), `SyntheticMetadataWriteTest`
(`everyShapeOfObservedTypeIsWrittenAndReadBack`, `anObservedTypeWrittenByThePreviousVersionReadsTheSame`,
`aMalformedObservedTypeIsRefused`) and `BceCompileTimeTest.recordedGenericSyntheticObserversReceiveTheirEvents`
(vauban-processor: compiled with the processor, booted, events received).

---

## VAU-OBS-004 — An observer or disposer method with overloads can fail deployment, depending on method order
- **Date**: 2026-10-23 — **Status**: FIXED (`test/coverage`, vauban#185, Vidocq/vidocq#285)
- **Severity**: medium (a valid application fails to deploy, or deploys, depending on the JVM's method order)

### Symptom
A bean whose observer method shares its name with an overload that observes nothing (`on(@Observes String)` next to
`on(int)`) could fail deployment with `DeploymentException: Observer method …on(): unsatisfied dependency for
parameter arg0 of type int`. The same held for a disposer method next to an overload without `@Disposes`. It passed
on one machine and failed on another: found when a new coverage test passed locally and failed in CI.

### Repro
`ObserverOverloadValidationTest` (vauban-core): beans declaring their overloads in different orders, so that a
first-match lookup hits a non-observing overload whatever the order; both tests failed before the fix.

### Cause
The deployment validation of observer and disposer parameters looked the method up by name only and took the first
match of `getDeclaredMethods()`, whose order is unspecified, then checked the parameters of that overload.

### Fix
The lookup keeps only the overload that has an `@Observes`/`@ObservesAsync` parameter (observers) or a `@Disposes`
parameter (disposers).

---

## VAU-CL-002 — Reading a `jar:` resource after its loader is closed throws `NullPointerException`
- **Date**: 2026-10-23 — **Status**: FIXED (`docs/log-coverage-defects`, Vidocq/vidocq#285)
- **Severity**: low (a connection opened before the loader closed, read after; the caller gets an NPE instead of an
  `IOException`)

### Symptom
`BuiltInReaders.JarReader.Connection.getInputStream()` is meant to report a closed loader as
`IOException("The loader of … is closed")`. When the connection was already connected before the loader closed, it
throws `NullPointerException` instead. Found while covering the code refactored by vauban#182.

### Repro
Get a resource URL of a plain jar from a `VaubanClassLoader`, open its connection and call `connect()`, close the
loader, then call `getInputStream()`.

### Cause
`entry()` catches the `IllegalStateException` of the closed `JarFile` and returns `null`, so
`jar.getInputStream(null)` throws the NPE and the `catch (IllegalStateException closed)` that builds the intended
`IOException` never runs.

### Fix
`getInputStream()` reads the entry from the jar file itself instead of going through `entry()`, inside the `try`
that catches `IllegalStateException`: a closed jar file now reaches that `catch` and comes out as
`IOException("The loader of … is closed")`, with the jar file's exception as its cause. `connect()` and
`getContentLengthLong()` keep using `entry()` (a closed loader is still `-1` for the length). Test:
`StandardResourceUrlsTest.connectedConnectionReadAfterTheLoaderClosesThrowsIOException` (vauban-classloader).

---

## VAU-INT-007 — Fallback intercepted factory: an `@AroundConstruct` that calls `proceed()` fails with an NPE
- **Date**: 2026-10-23 — **Status**: FIXED (`docs/log-coverage-defects`, Vidocq/vidocq#285)
- **Severity**: medium (only on the runtime fallback, when the pre-generated `$$Intercepted` subclass cannot be
  defined; the bean cannot be created)

### Symptom
Creating a bean bound to an `@AroundConstruct` interceptor that calls `proceed()` fails with a
`NullPointerException` in `InterceptorManager.resolveChainAroundConstruct` when the container uses the fallback
intercepted factory. `factory().create()` with no creational context fails the same way.

### Repro
A bean with an `@AroundConstruct` interceptor that calls `proceed()`, and a `classDefiner` that refuses the first
definition of the `$$Intercepted` class, so that `InterceptorBeanWrapper` takes its fallback path.

### Cause
`InterceptorBeanWrapper.FallbackInterceptedFactory` does not override `create(VaubanInvocationContext,
CreationalContext)`. Its `createThroughAroundConstruct` lambda calls it; the default delegates to `create()`, then
`create(null)`, which resolves the construct chain again with a null context.

### Fix
`FallbackInterceptedFactory` now overrides `create(VaubanInvocationContext, CreationalContext)` as the regular
`InterceptedFactory` does: without a construct context it resolves the `@AroundConstruct` chain and, when there is
one, runs it; the chain's end calls back with its context and the creational context, and that call instantiates
and wires the subclass without resolving the chain again. `create(CreationalContext)` and
`create(VaubanInvocationContext)` delegate to it. The instance is wired once, by the call that creates it (the old
`create(CreationalContext)` wired it again after the chain). Test:
`FallbackAroundConstructTest.selectedBeanIsCreatedThroughAroundConstruct` (vauban-core).

The `factory().create()` symptom has a second, separate cause that both factories share: with no creational
context, the interceptor instance resolves to `null`. It stays open as VAU-INT-008.

---

## VAU-INT-008 — An intercepted bean's factory, called without a creational context, NPEs on an `@AroundConstruct`
- **Date**: 2026-10-23 — **Status**: FIXED (fix/vauban-tickets, vauban#192, Vidocq/vidocq#285)
- **Severity**: low (only a direct `ManagedBean.factory().create()` with no creational context; the container
  always passes one)

### Symptom
`factory().create()` on an intercepted bean bound to an `@AroundConstruct` interceptor throws
`NullPointerException: Cannot invoke "Object.getClass()" because "instance" is null`, in
`InterceptorManager.resolveAroundConstructChain` for the regular `InterceptedFactory` and in
`resolveChainAroundConstruct` for the fallback one. Found while fixing VAU-INT-007.

### Repro
A `@Dependent` bean bound to an `@AroundConstruct` interceptor; resolve its `ManagedBean` through the
`BeanManager` and call `factory().create()`.

### Cause
`InterceptorBeanWrapper.getOrCreateInterceptorInstance` first asks `interceptorInstanceAsBean`, which calls
`BeanManager.getReference(bean, type, null)`; the dependent context returns `null` for a null creational context,
and that `null` is returned as the interceptor instance instead of falling back on creating it. The chain
resolution then dereferences it. Treating a `null` reference as "no bean" would change the eager interceptor
creation of `InterceptorBeanWrapper` (around line 509), which ignores the result today, so it needs its own look.

### Fix
`InterceptedFactory` and `FallbackInterceptedFactory` now create the bean with a creational context of its own when
the caller passes none (`InterceptorBeanWrapper.contextOfItsOwnIfNone`), as `VaubanBeanManager.createCreationalContext`
would give one. Its interceptor instances are dependent objects of the bean instance (CDI 4.1 §6.4.1): with a
context, the dependent context returns them, and the `@AroundConstruct` chain and the business method chains of that
bean instance share one instance per interceptor class (Jakarta Interceptors 2.2 §2.2). The eager interceptor
creation of `InterceptorBeanWrapper` is unchanged: a direct `factory().create()` does not reach it, and it still
receives the caller's context. Tests: `InterceptedFactoryWithoutCreationalContextTest.regularFactory` and
`.fallbackFactory` (vauban-core).

`Bean#create(null)` on an intercepted bean with an `@AroundConstruct` interceptor now gets past the factory, then
fails in its `@PostConstruct` step with a separate cause: `ManagedBean.create` hands the null context on to the
injector, and `InterceptorManager.resolveLifecycleChain` puts an `InterceptorInvocation(null, null)` placeholder in
the chain when it has no context, which `VaubanInvocationContext.makeAccessibleSafe` dereferences. Tracked and
fixed as VAU-LIFE-002 (vauban#200).

---

## VAU-BCE-006 — An `@Inject` that a build compatible extension adds on a constructor is ignored
- **Date**: 2026-10-23 — **Status**: FIXED (fix/vauban-tickets, vauban#187, Vidocq/vidocq#285)
- **Severity**: medium (a CDI Lite conformance gap; the TCK does not cover it)

### Symptom
A bean with a no-arg constructor and a one-argument constructor that an `@Enhancement` makes `@Inject`: the
container still instantiates it through the no-arg constructor, and what the injected constructor would set stays
null.

### Repro
`@Enhancement(types = Bean.class)` adding `@Inject` to the one-argument constructor of a bean that also declares a
no-arg constructor; look the bean up and check the value the injected constructor sets.

### Cause
CDI 4.1 §13.5.1 has the container alter its metadata with what the `@Enhancement` phase did before bean discovery,
and §3.5.1 makes the `@Inject` constructor the bean constructor, all its parameters injection points: it is a bug.
`EnhancementApplier` created the injection points of an added `@Inject` for fields and initializer methods only,
constructors being excluded, so the descriptor recorded no constructor parameter and the container took the
pre-registered no-arg factory. Constructor selection (`VaubanContainer`) and `ManagedBean.getInjectionPoints()` also
read `@Inject` on the class only.

### Fix
`EnhancementApplier` treats a constructor that an enhancement marks `@Inject` like an initializer: each parameter
becomes a constructor injection point, so it is validated and resolved. `VaubanContainer` creates the bean through
the constructor whose parameters the descriptor describes (exact overload, parameter types included) before falling
back on the parameter count, and `ManagedBean.getInjectionPoints()` reports that constructor's parameters
(`QualifierHelper.describesConstructor`). Tests: `BceInjectionEnhancementTest.constructorAnEnhancementMarksInjectIsTheBeanConstructor`
(with a same-arity `String` overload) and `unsatisfiedParameterOfConstructorAnEnhancementMarksInjectFailsDeployment`.

---

## VAU-OBS-003 — Synthetic observers reject wildcard-parameterized and generic-array event types
- **Date**: 2026-10-23 — **Status**: FIXED (fix/vauban-tickets, vauban#188, Vidocq/vidocq#285)
- **Severity**: low (a limitation: `@Synthesis` refuses such observers with "Unsupported type for synthetic
  observer")

### Symptom
Registering a synthetic observer for an event type such as `List<? extends Number>` or `T[]` at `@Synthesis` fails
with "Unsupported type for synthetic observer", while CDI allows such observed types (CDI 4.1, section 10.1: any Java
type may be an observed event type; section 10.3.1 for wildcards and type variables).

### Repro
A build compatible extension that registers, in `@Synthesis`, a synthetic observer whose observed type is a
wildcard-parameterized type or a generic array type.

### Cause
`VaubanSyntheticObserverBuilder(Type)` turned the lang model type into a reflective type and knew only class types and
parameterized types: a wildcard argument, any array (even `String[]`), a primitive component or a type variable threw
"Unsupported type for synthetic observer", and the `@Synthesis` method failed. Behind it,
`SyntheticComponentRegistrar.eventTypeInfo` turned the reflective type into the index form the observer resolution
matches events against, and lost every argument that was not a class (`List<? extends Number>` would have observed
`List<Object>`, and never received a `List<Integer>`).

### Fix
The builder keeps the index form of the observed type, converted from the lang model type with
`LangModelTypeMapper.toIndexType`, as the bean types of a synthetic bean already were (VAU-BCE-001), and the registrar
uses it instead of rebuilding it from the reflective type. The reflective form now covers wildcards, arrays (generic
or not), primitive components and type variables (by name). An observer added with a `Class` keeps the same index form
as before. When the processor runs the extension, the metadata names the observed type and the boot loads it by that
name, which a wildcard, a generic array or a type variable cannot survive: `SyntheticMetadataSerializer` refuses such
an observed type, and the build fails with "Cannot record a synthetic component: synthetic observer of …", as for a
param the build cannot record (BUG-20261008-02), instead of an observer silently lost at boot. Tests:
`SyntheticObserverEventShapesTest` (vauban-core: `List<? extends Number>`, `List<String>[]`, `String[]`, `N[]` with
`N extends Number`), `SyntheticMetadataWriteTest.anObservedTypeWithAWildcardAGenericArrayOrATypeVariableFailsTheBuild`
and `BceCompileTimeTest.wildcardSyntheticObserverFailsTheBuild` (vauban-processor). The build-time refusal was lifted
by VAU-OBS-005, which records every shape in the metadata (those two tests were replaced).

---

## VAU-MVN-002 — `VaubanGenerator`: a broken extension service file fails the build instead of being skipped
- **Date**: 2026-10-23 — **Status**: FIXED (fix/vauban-tickets, vauban#189, Vidocq/vidocq#285)
- **Severity**: low (a malformed `META-INF/services` entry for `BuildCompatibleExtension` on the build class path)

### Symptom
The comment around the discovery of build compatible extensions in `VaubanGenerator` says a provider that fails to
load is ignored, but a broken service file fails the generation with a `ServiceConfigurationError`.

### Repro
A jar on the generator's class path whose `META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension`
names a class that does not exist; run the `generate` goal.

### Cause
`ServiceLoader` reports a broken provider with `ServiceConfigurationError`, an `Error`, and the surrounding
`catch (Exception)` does not catch it.

### Fix
The discovery walks the `ServiceLoader` iterator itself: a provider that fails with `ServiceConfigurationError` or a
`LinkageError` is skipped with a generation warning naming the service file and the provider, and the extensions after
it still run. Skipping with a warning matches the rest of `vauban:generate` (a dependency or a class it cannot read is
a warning) and the processor's warning for build compatible extensions it cannot load (vauban#29); CDI 4.1 §12.1 only
defines a build compatible extension as a service provider declared in `META-INF/services`. Test:
`GeneratorInputScanningTest.aBrokenExtensionServiceEntryIsSkippedWithAWarningAndTheOthersStillRun` (vauban-maven-plugin).

---

## VAU-TEST-002 — `VaubanExtension` leaves `@Inject` fields null with `@TestInstance(PER_CLASS)`
- **Date**: 2026-10-23 — **Status**: FIXED (fix/vauban-tickets, vauban#190, Vidocq/vidocq#285)
- **Severity**: low (a limitation of the JUnit extension; the default per-method lifecycle works)

### Symptom
In a test class annotated `@TestInstance(TestInstance.Lifecycle.PER_CLASS)`, the `@Inject` fields stay null.

### Repro
A `@VaubanTest` class with `@TestInstance(PER_CLASS)` and an `@Inject` field; any test reads it as null.

### Cause
With the per-class lifecycle, JUnit post-processes the test instance before `beforeAll`, where `VaubanExtension`
starts the container, so the container is not in the store yet when the fields are injected.

### Fix
`beforeAll` and `postProcessTestInstance` both get the container from the class-level store with `computeIfAbsent`,
keyed by the test class, so whichever runs first starts it and the class keeps a single container, closed by
`afterAll`. `VaubanExtensionTest.PerClassLifecycleTest` (failed before the fix: `expected: not <null>`) and
`VaubanExtensionTest.PerMethodLifecycleTest` cover both lifecycles, each checking that one container boots.

---

## VAU-VAL-001 — `ClassValidator.validateStereotypeName` never runs
- **Date**: 2026-10-23 — **Status**: FIXED (`fix/vauban-tickets`, vauban#191, Vidocq/vidocq#285)
- **Severity**: low (the same rule is enforced at run time by `ReflectionValidator`)

### Symptom
The build-time check that a stereotype does not declare `@Named` with a value never reports anything.

### Repro
A stereotype annotated `@Named("x")`: `ClassValidator` does not report it (the run-time validation does).
`ClassValidatorTest.StereotypeNamed.rejectsNonEmptyNamed` (vauban-core).

### Cause
`validateClass` returns early for annotation types, so the `@Stereotype` branch that calls
`validateStereotypeName` is unreachable.

### Fix
The check is made reachable rather than removed: CDI 4.1, 2.8.1.3 makes a stereotype that declares a non-empty
`@Named` a definition error on its own, while `ReflectionValidator` sees it only through a bean that carries the
stereotype. `validateClass` now hands an annotation type to `validateAnnotationType`, which runs
`validateStereotypeName` for a stereotype and none of the bean rules, and the unreachable branch is gone. The
message names the value, as the run-time one does: `Stereotype X has @Named with non-empty value 'x'`. A stereotype
the container indexes (passed to `addBeanClasses`, added by an extension) now fails the boot with a
`DefinitionException` even when no bean carries it. Tests: `ClassValidatorTest.StereotypeNamed`
(`rejectsNonEmptyNamed`, `acceptsEmptyNamed`).

---

## VAU-BCE-009 — A synthetic bean recorded at build and synthesized again at boot is registered twice

- **Date**: 2026-11-02 — **Status**: FIXED (fix/synthetic-signature-canonical-types)
- **Severity**: high (the application does not start: every injection point of that bean is ambiguous)

### Symptom
A Vidocq application with `@ConfigProperties(prefix = "app") class AppSettings` injected as
`@Inject @ConfigProperties AppSettings settings` fails at boot: `Ambiguous dependency: field HelloResource.settings
of type ClassType[name=…AppSettings]. Matching beans: [java.lang.Object, java.lang.Object]`. Found by the reflection
audit of 2026-11-02 (MicroProfile Config through Ravel's `ConfigCdiExtension`).

### Repro
`vidocq create -x cassini-rest -x ravel-config`, the class and the injection above, `app.greeting=…` in
`vidocq.properties`, package and start. Unit: `SyntheticSignatureTest`.

### Cause
The build records the synthetic bean in `META-INF/vauban-synthetic-metadata.properties`; at boot its types are read
back as classes (`io.example.AppSettings`). The extension runs again at boot (the application's dependencies are not
all processed) and names the same type through the language model, kept as an index type whose `toString()` is
`ClassType[name=io.example.AppSettings]`. The VAU-BCE-002 deduplication compares signatures built from those strings,
so the two registrations of one bean did not collapse.

### Fix
`SyntheticComponentRegistrar.syntheticSignature` writes an index type the way `java.lang.reflect.Type#getTypeName()`
writes the same type (class name, `Raw<Arg, …>`, `[]`, wildcards). Tested red then green (`SyntheticSignatureTest`);
the application above starts and answers with its configured values. Still open, unrelated: the synthetic metadata
has no notation for a parameterized type, so such a type is left out of the recorded bean.

---

## VAU-BCE-008 — At build, the `ClassInfo` of a class named only in an annotation value has no members

- **Date**: 2026-11-01 — **Status**: FIXED (fix/vau-bce-008)
- **Severity**: medium (a build compatible extension that inspects such a class at build sees an empty class, and
  draws wrong conclusions without any error)

### Symptom
Champollion's extension takes the class `@JsonbTypeAdapter(AmountAdapter.class)` names
(`annotation.value().asType().asClass().declaration()`) and checks whether it has `@Inject` members. At boot the
answer is right; at build (Vauban's processor) `fields()`, `methods()` and `constructors()` are empty, so an adapter
that injects without being a bean passed the validation. Found on JSON-B step C4 (Vidocq/champollion, CDI adapters).

### Repro
An application with `record Price(@JsonbTypeAdapter(AmountAdapter.class) BigDecimal amount)` and
`class AmountAdapter implements JsonbAdapter<BigDecimal, String> { @Inject Rates rates; … }` (no bean-defining
annotation), a BCE with `@Enhancement(types = Object.class, withSubtypes = true, withAnnotations = JsonbTypeAdapter.class)`
that reads `value().asType().asClass().declaration().fields()` for `Price`'s annotation: empty at build
(`VaubanClassInfo`, 0 fields), `[rates]` at boot.

### Cause
`VaubanClassType.declaration()` looks the class up in the index (`IndexLookup.getClass`) and, when it is absent,
returns `syntheticClassInfo(name)`, a stub with no members (VAU-BCE-001). At build the index holds the beans and the
classes carrying a trigger annotation; `AmountAdapter` carries none, so it is a stub, although the processor has its
`TypeElement` in the very compilation.

### Fix
While the processor runs the extension phases, `IndexLookup.whereUnindexedDescribedBy` binds a resolver (a
`ScopedValue`, unbound at run time) that describes a class the index lacks from its `TypeElement`, through
`ElementScanner`, lazily and once per class. `VaubanClassType.declaration()` asks `IndexLookup.describe`, so the
language model shows the class's members; nothing else reads the resolver, so the class never becomes a bean. The
JDK's classes stay stubs, as at boot. `AnnotationValueClassInfoTest`: a BCE reads the fields, methods and the
`@Inject` of a class named only in an annotation value (red before the fix: `injects false`, no member).

### Workaround
`champollion-jsonb-cdi` also records, through `@Enhancement(withAnnotations = Inject.class)`, the classes that inject:
a trigger annotation puts them in the index.

## VAU-Q5-001 — `InterceptorManager.$$callIntercepted` wraps an `Error` in a `RuntimeException`
- **Date**: 2026-10-23 — **Status**: FIXED (`refactor/zero-issues-5`, Vidocq/vidocq#285)
- **Severity**: low (no caller in Vauban or the ecosystem today; the method is public in an exported package)

### Symptom
An `Error` thrown by the action given to `$$callIntercepted` (an `AssertionError`, a `StackOverflowError`) came out
as a `RuntimeException` whose cause is the error, while its sibling `$$runIntercepted`, and
`VaubanLookup.invokeMethod`, let an `Error` propagate unchanged. Found while cleaning up SonarQube findings
(`java:S1181`, `java:S112`).

### Repro
`CallInterceptedTest.propagatesAnErrorUnchanged` (vauban-core): the action throws an `Error`, the call must throw
that same error.

### Cause
The method caught `Throwable` after `Exception` and wrapped what remained, which for a `CallableOp<R, Exception>`
can only be an `Error`, in a new `RuntimeException`.

### Fix
The method returns what `ScopedValue.Carrier.call` returns and lets its exceptions through: checked exceptions,
runtime exceptions and errors all come out unchanged.

---

## VAU-CL-001 — Resources of plain jars and directories have `vauban:` URLs that libraries cannot read as folders
- **Date**: 2026-10-22 — **Status**: FIXED (`pr/ybl/standard-resource-urls`, vauban#177)
- **Severity**: medium (Vaadin's development mode logs errors and pins no npm versions; other libraries that read
  a resource URL as a jar or a file fail the same way)

### Symptom
On Vidocq, Vaadin's development mode logged `Unable to read pinned npm versions from 'vauban:file:///…!/…'` from
`PinnedNpmVersions`, then `Couldn't find any versions file in META-INF/VAADIN/versions/`, and
`BundleValidationUtil` logged an exception while deciding whether to rebuild the bundle. Found on
threadlocalrandom/VaadinCreate2026 (Vidocq/vidocq#256).

### Repro
`StandardResourceUrlsTest` (vauban-classloader): a resource of a plain jar, a jar folder listed through
`JarURLConnection`, a name with a space, the file and folder of an exploded directory, and an archive read by a
`ByteSourcePlugin`. `MultiReleaseJarTest` reads a versioned resource through its URL.

### Cause
`VaubanClassLoader.resourceUrl` gave every resource a `vauban:<archive>!/<name>` URL with a private handler. A
library can open its stream, but code that turns a resource URL into a `JarFile` or a `Path`, as these Vaadin
classes do, knows only `jar:` and `file:`. A directory archive found no folder at all, and a name with a space
threw `IllegalArgumentException`.

### Fix
Plain jars give the `jar:` URL the JDK's loaders give, with `#runtime` for a multi-release jar. Its connection is a
`JarURLConnection` that reads the loader's own jar file, so that nothing lands in the JDK's global jar cache, which
would keep the jar open after the loader closes; a caching connection's `getJarFile()` closes with the loader.
Exploded directories give a `file:` URL, for a file or a folder; a name outside the directory (`../x`, an absolute
path) finds nothing, through a URL, a stream or a class. Archives read by a `ByteSourcePlugin`, such as an sjar,
keep `vauban:` URLs, now with the name encoded. A jar folder is found only through its directory entry,
as with the JDK: the enriched copies of `vidocq:generate` had none, fixed by Vidocq BUG-20261022-01.

## VAU-LIFE-001 — A throwing lifecycle observer is swallowed; with reflection forbidden, the observers are never called
- **Date**: 2026-10-18 — **Status**: FIXED (`fix/97-lifecycle-observer-failures`, vauban#97)
- **Severity**: high (an application starts half-initialized with no trace; under `forbid`, lifecycle observers never run)

### Symptom
`VaubanContainer` fired `@Initialized(ApplicationScoped.class)`, `Startup`, `Shutdown`, `@BeforeDestroyed` and
`@Destroyed(ApplicationScoped.class)` inside `catch (Exception e) { /* suppress */ }`. An observer that threw stopped
the event (CDI 4.1 §9.5), the exception was discarded, nothing was logged, and `build()` returned a half-initialized
container. An `Error` propagated but left `VaubanContainer.current()` on the half-built container.

The same `catch` hid a second defect: with `vauban.annotations.reflection=forbid`, computing the key of the
`@Initialized(ApplicationScoped.class)` literal needed reflection and threw, so those observers were never notified,
silently.

### Repro
`LifecycleObserverFailureTest` (vauban-core): a throwing `@Initialized` observer, an `Error`, a throwing `Shutdown`
observer, the qualifier keys under `forbid`; `GeneratedAnnotationProviderTest` boots under `forbid`.

### Fix
`EventDispatcher.fireLifecycle` names the observer that threw (`LifecycleObserverFailure`). At startup: one
`WARNING` line with the stack trace, the boot undone (`running`, `current()`, contexts), and a `DeploymentException`
with the observer's exception as cause (maintainer's choice: fail rather than log and go on). At shutdown: one
`WARNING` line per event, and `close()` completes. `AnnotationTypes.key` reads `@Initialized`, `@BeforeDestroyed`
and `@Destroyed` through their `value()`, without reflection.

## VAU-TEST-001 — A bean in the test sources hides the main beans
- **Date**: 2026-10-18 — **Status**: FIXED (`fix/101-test-component-provider`, vauban#101)
- **Severity**: medium (any module with a test-only bean next to its own beans)

### Symptom
A `@ApplicationScoped` bean under `src/test/java`, in the package of the main beans, broke the deployment with
`Unsatisfied dependency` on a main bean (Vidocq's `vidocq-runtime-mansart-h2-example`: `ProductRepository`).

### Repro
`TestRoundProviderTest` (vauban-processor), `TestCompanionProviderTest` (vauban-core). End to end: a test bean
injecting `ProductRepository` in the mansart example fails with `Unsatisfied dependency` before the fix, passes after.

### Cause
Maven runs the processor on the main sources, then on the tests. The test round emitted a second
`<package>._VaubanComponents` and a second `META-INF/vauban-beans.list` (and contexts list) from the test sources
alone; when the tests patch the module, these hide the main ones. The provider alone was not the whole cause: with
only a renamed provider, the hidden beans list still lost `ProductRepository`.

### Fix
The test round names its provider `_VaubanComponentsTests` and writes no services entry; `ComponentProviders.load`
finds it through the lookup the main provider grants. The test round adds the main round's lines to its line-based
indexes (`vauban-beans.list`, `required-opens.list`, `vauban-bce-runtime.list`, contexts), and warns when it writes a
binary index next to a main one. Cassini's `$$CassiniAdapter` / `$$CassiniRoutes` are not covered here.

---

## VAU-MOD-005 — `vauban:modularize` misses `uses` when the helper is called through a supertype method
- **Date**: 2026-10-15 — **Status**: FIXED (`fix/uses-through-supertype`, vauban#167)
- **Severity**: high (an explicit module fails its own lookup at boot)
- **Surfaced by**: the Vaadin 25 jlink image, after Vidocq/foy#50.

### Symptom
`ServiceConfigurationError: com.vaadin.flow.di.InstantiatorFactory: module flow.server does not declare uses`,
from `LookupInitializer$LookupImpl.lookupAll`, called by `VaadinService.loadInstantiators`.

### Repro
`ModularizerTest.emitsUsesWhenTheHelperIsCalledThroughAnInterface`,
`emitsUsesWhenTheHelperIsCalledThroughASuperclass`.

### Cause
`ServiceUsesScanner` matched forwarding helpers by exact `owner.name(descriptor)`. `VaadinService` calls
`Lookup.lookupAll` (the interface), while the method reaching `ServiceLoader` is `LookupImpl.lookupAll`.

### Fix
A supertype method is a lookup when one of its implementations in the closure is, and the forwarding chain
reaches the implementations. On Vaadin this shows the helper has no legal explicit form: `collaboration-engine`
and `flow-lit-template` pass their own types to `Lookup.lookupAll`, so `flow.server` would need a `uses` back
into modules that require it, a cycle. `flow-server` stays automatic; `vauban.modularize.forceExplicit` patches
it anyway for an application that never reaches those lookups.

---

## VAU-MOD-004 — `vauban:modularize` leaves resource-only directories out of the packages, jlink refuses the module
- **Date**: 2026-10-14 — **Status**: FIXED (`fix/modularize-resource-packages`, vauban#163), except a directory
  two jars share (still refused by jlink, reported)
- **Severity**: medium (a modularized closure cannot be linked)
- **Surfaced by**: `vidocq:jlink` of the Vaadin 25 spike after vauban#160, #162 and vidocq#254.

### Symptom
`Module flow.server's descriptor indicates the set of packages is : [...], but module contains packages: [...]`,
the difference being `com.vaadin.flow.server.frontend`, `...frontend.dependencies.{tailwindcss,vite,workbox}`:
directories holding resources only.

### Repro
`ModularizerTest.aResourceOnlyDirectoryIsAPackageOfTheModule`,
`aResourceDirectorySharedWithAnotherJarStaysOut`.

### Cause
`ModuleDescriptorSynthesizer` counted a package only where a class sits. jlink counts every directory holding
a resource with a legal package name; `ModuleFinder` does not check, so only jlink failed.

### Fix
Resource-only directories are packages, `META-INF` and illegal names aside. One another jar of the closure
also holds stays out (two modules holding one package fail at boot), with one loud warning block per directory
naming the jars, and a `shared resource directory:` line in `report.txt`. Vaadin's `flow-server` and
`flow-react` share `com/vaadin/flow/server/frontend/`, so that pair still blocks jlink.

---

## VAU-MOD-003 — `vauban:modularize` keeps `provides` the module cannot read
- **Date**: 2026-10-14 — **Status**: FIXED (`fix/modularize-provides-legality`, vauban#162)
- **Severity**: high (the whole module graph is rejected at boot)
- **Surfaced by**: Vaadin 25 spike fully modularized (vauban#160), under `vidocq.app.layer=classes`.

### Symptom
`ResolutionException: Module reactor.core provides io.micrometer.context.ContextAccessor but does not
read a module that exports io.micrometer.context to reactor.core`.

### Repro
`ModularizerTest.dropsProvidesForAServiceOutsideTheClosure`,
`requiresTheModuleOfAProvidedServiceInTheClosure`.

### Cause
`ModuleDescriptorSynthesizer` turned every service file into a `provides` after checking only that the
provider is in the jar. A service type outside the closure (an optional dependency) cannot be read, and a
service type from the closure was required only when the provider's bytecode referenced it.

### Fix
A `provides` whose service package no module of the closure or the JDK owns is dropped with a
`WARN dropped provides:` line; one owned by another module adds a `requires` on it. At run time,
`VaubanLayerFactory` promoted the same services file back to a `provides`: it now promotes only a
service type the module reads (`VaubanLayerFactoryTest.servicesFilesForAnUnreadableTypeAreNotPromoted`).

---

## VAU-MOD-002 — `vauban:modularize` fails the build on a jar without classes
- **Date**: 2026-10-14 — **Status**: FIXED (`fix/modularize-jar-without-classes`, vauban#160)
- **Severity**: medium (a Vaadin closure cannot be modularized, so it cannot be linked with jlink)
- **Surfaced by**: Vaadin 25 spike, while verifying Vidocq/vidocq#251.

### Symptom
`vauban:modularize failed: cannot modularize vaadin-core-25.3.2.jar: it holds no class in any package`.
`vaadin-core` is a Maven aggregator jar: `META-INF/MANIFEST.MF` and `META-INF/maven/**` only.

### Repro
`ModularizerTest.aJarWithoutClassesGetsAnEmptyExplicitModule`, `synthesizeOneGivesAJarWithoutClassesAnEmptyModule`.

### Cause
`ModuleDescriptorSynthesizer.synthesize` refused any jar with no package, although a module without
packages is legal.

### Fix
A jar with no class gets an empty module. A jar whose classes are all in the unnamed package still
fails (the JDK already refuses to derive its automatic module, naming the jar).

---

## VAU-MOD-001 — `vauban:modularize` misses `uses` when the ClassLoader comes from a computed `Class`
- **Date**: 2026-10-14 — **Status**: FIXED (`fix/uses-receiver-not-constant`, vauban#158)
- **Severity**: high (a modularized jar fails its own service lookup at boot)
- **Surfaced by**: Vaadin 25 on Foy (vidocq#246), packaged through `vauban:modularize`.

### Symptom
The descriptor synthesized for Vaadin's `flow-server` provides
`com.vaadin.experimental.FeatureFlagProvider` but has no `uses` for it; boot fails with
`ServiceConfigurationError: … module flow.server does not declare uses`.

### Repro
`ModularizerTest.emitsUsesWhenTheClassLoaderComesFromAComputedClass`:
`ServiceLoader.load(Spi.class, getClass().getClassLoader())` and
`ServiceLoader.load(Local.class, anchor.getClassLoader())` with `anchor` a `Class<?>` parameter.

### Cause
`ServiceUsesScanner.consume` removed one candidate class constant for the receiver of any
instance call on `java.lang.Class`, assuming the receiver was the constant just pushed
(`Util.class.getClassLoader()`). With `getClass().getClassLoader()` or a `Class` variable the
receiver is not a constant, so the service type itself was popped and never recorded.

### Fix
The receiver is consumed only when the instruction right before the call pushed a class
constant and the call takes no `Class` argument (`anchor.asSubclass(Other.class)` consumes its
argument, not a second candidate). Remaining trade-off: a receiver constant separated from its
call (`Foo.class.getResourceAsStream("x")`) stays pending and may add a superfluous, inert `uses`.

---

## VAU-PROXY-001 — client proxy replays the bean constructor (side effects doubled, NPE on `@Inject` ctor)
- **Date**: 2026-08-10 — **Status**: FIXED, phases 0+1+2 (`pr/ybl/vauban-24-proxy-link`) — phase 2 weaves the marker automatically at `process-classes` (no app-code change required)
- **Severity**: high (constructor injection unusable on normal-scoped beans; silent double side effects)
- **Surfaced by**: Sébastien Blanc, Vidocq/vauban#24, while building Rossignol on Vidocq.

### Symptom
Creating the `_ClientProxy` of a normal-scoped bean chains `super(<defaults>)` into a business
constructor: construction side effects run once per proxy on top of the contextual instance
(reporter's case, 2 constructions for 1 instance), and a bean whose only constructor is an
`@Inject` one that dereferences a parameter throws `NullPointerException` at proxy creation
(the proxy passes null for every parameter).

### Repro
`ProxyLinkConstructorTest` (vauban-core) and `UnproxyableBeanValidationTest` (vauban-processor).

### Cause
Two independent defects. (1) The JVM forces the proxy's `<init>` to chain to a bean
constructor, and every generator picked the "simplest" business constructor with default
arguments. (2) Such beans are *unproxyable* per CDI 4.1 and should have been reported as a
deployment problem — but `DeploymentValidator`'s proxyability checks used `Class.forName`
over the TCCL with a silent `catch (ClassNotFoundException)`: inert at annotation-processing
time and on the module path (the Vidocq runtime deployment), so nothing ever fired.

### Fix
Phase 0: proxyability checks (final class, non-private final methods, missing no-arg ctor)
are now index-based with reflection as fallback — final members fail the build; the
missing-constructor case is a compile-time warning (weavable) and a container-start error.
Phase 1: opt-in `ProxyLink` marker constructor (`vauban-api`); all three proxy front-ends
chain to it when declared, and the container never selects it for injection. Phase 2
(the actual no-app-change fix): `vauban-maven-plugin` weaves a synthetic `(ProxyLink)`
constructor into the compiled bean at `process-classes` (`ProxyLinkWeaver` +
`ProxyLinkWeaving`) and retargets APT-emitted proxies onto it — the application source
never mentions `ProxyLink`. Full study: `tasks/vauban-24-client-proxy-constructor.md`.
Verified: reactor 526 tests + CDI Lite TCK 774/774 green, weaving exercised for real by
the examples modules during the reactor build.

---

## VAU-APT-001 — `@Inject` of a bean from another module rejected at compile time
- **Date**: 2026-08-09 — **Status**: FIXED (`pr/ybl/vauban-23-cross-module-index`)
- **Severity**: high (viral at every module boundary; no workaround for third-party beans)
- **Surfaced by**: Sébastien Blanc, Vidocq/vauban#23, while building Rossignol on Vidocq.

### Symptom
```
[ERROR] [Vauban] Unsatisfied dependency: field HelloResource.greeter of type
        ClassType[name=io.repro.core.Greeter] with qualifiers [... Any ..., ... Default ...]
```
`Greeter` is an `@ApplicationScoped` bean of a **different** Maven / Java module. The same
injection point wrapped in `Instance<Greeter>` compiled and resolved correctly at runtime,
against the same bean and the same module graph — so the build-time rejection was a false
negative, not a real deployment error.

### Repro
Two modules: `core` exports an `@ApplicationScoped Greeter`; `app` requires `core` and injects it
directly. `mvn install` fails. Reproduced as `CrossModuleInjectionTest` in `vauban-processor`.

### Cause
`VaubanProcessor` built its index solely from the types of the current compilation round
(`accumulatedIndex`), while the dependency scan lived only in `vauban-maven-plugin`
(`VaubanGenerator`, which reads each dependency's `META-INF/vauban-beans.list`). The validator
therefore judged the deployment on a partial index. The reporter's two checks confirmed the
runtime index was fine: `Instance<T>` worked end to end, and adding a local `@Produces` bridge
produced *Ambiguous dependency* — an ambiguity impossible to report without knowing the other
module's bean.

### Fix
No jar scanning was needed: `Elements` already sees the whole compile classpath. A required type
that is missing from the index is now looked up there, scanned in, and bean discovery is replayed
to obtain its descriptor (`resolveDependencyBeans`). Such a type goes into `externalClassNames`,
so no `_Factory` / `_ClientProxy` is emitted for it here — its own module ships those, and
emitting them again would split the package.

What still cannot be resolved is classified rather than rejected: an **interface or abstract
class from outside the Java platform** may be implemented by a bean this compilation cannot see,
so it is reported as a warning and deferred to the container (which re-validates on start). A
concrete type that was indexed and did not become a bean carries no scope, and a platform type is
nobody's bean — both keep failing the build. The in-module check therefore stays strict.

### Impact on the reported workarounds
`@Inject Instance<JwtValidator>` (cervantes) is no longer necessary, and neither is the
"library modules carry no CDI beans" restructuring the reporter had to adopt.

## VAU-INT-001 — overloaded intercepted methods collide on the `$$ti$<name>` glue
- **Date**: 2026-06-07 — **Status**: FIXED (unique per-overload `$$ti$` names in both renderers)
- **Severity**: medium (any bean with two intercepted methods of the same name fails to deploy)
- **Surfaced by**: MicroProfile Metrics 5.1 TCK `OverloadedTimedMethodBeanTest` (dirac), via the
  runtime `InterceptedEmitter`. Invisible to the vauban golden tests (no overloaded fixture).

### Symptom
```
Cannot create interceptor subclass: Duplicate method name "$$ti$overloadedTimedMethod"
with signature "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;"
in class file …/OverloadedTimedMethodBean$$Intercepted
```
A bean with two `@Timed`/`@Audited` methods of the same name (overloads) fails at subclass
generation/compilation.

### Cause
The `TargetInvoker` glue `$$ti$<name>` is named by method **name only** and erases to
`(Object, Object[]) Object`. Two overloads → two `$$ti$<name>` methods with identical name **and**
descriptor → invalid class file (bytecode) / duplicate method (source). The `$$super$<name>` bridges
do **not** collide (they keep the methods' distinct parameter descriptors). Naming dates back to the
B2 `invokedynamic`/`LambdaMetafactory` target-invoker work; the bug is pre-existing, shared by the
runtime/plugin bytecode emitter (`InterceptedEmitter`) and the APT source renderer
(`InterceptedSourceRenderer`).

### Fix
`targetInvokerNames(List<MethodShape>)` in both `InterceptedEmitter` and `InterceptedSourceRenderer`:
a non-overloaded name stays `$$ti$<name>` (golden snapshots byte-for-byte stable); each overload of a
repeated name gets a `$<occurrence>` suffix (`$$ti$run$0`, `$$ti$run$1`, …). The override's
`invokedynamic`/method-reference and the glue definition use the same unique name. Verified: dirac MP
Metrics TCK 127/127 (was 126+1 error) and a new compile-time test
`generatesDistinctGlueForOverloadedInterceptedMethods` (source path).

---

## VAU-INT-002 — primitive-array params/return mishandled in intercepted methods (`TypeRef` ignores array dims)
- **Date**: 2026-06-07 — **Status**: FIXED (`TypeRef.isPrimitive()`/`isCategory2()` now require `dims == 0`)
- **Severity**: medium (any intercepted method with an `int[]`/`long[]`/… param or return fails to load)
- **Surfaced by**: MicroProfile Fault Tolerance 4.1 TCK CircuitBreaker tests (heisenberg) — beans with
  `serviceA(int[])`. 458/463, 4 failures + 1 deployment error. Invisible to the vauban golden tests.

### Symptom
```
java.lang.VerifyError: Bad local variable type … in CircuitBreaker…$$Intercepted.serviceA([I)…
@39: iload_1  Reason: Type '[I' (locals[1]) is not assignable to int
```
The override loads an `int[]` parameter with `iload` (scalar int) instead of `aload` (reference).

### Cause
`TypeRef` stores a primitive-array as `primitive=INT, dims=1`, but `isPrimitive()` returned
`primitive != null && primitive != VOID` — **ignoring `dims`**. So `int[]` was treated as a scalar
`int`: `boxAndLoad`/`castOrUnboxParam` emitted `iload`/`Integer.valueOf`, and the slot arithmetic
(`isCategory2()` likewise ignoring dims) under/over-counted for `long[]`/`double[]`. An array of a
primitive is a **reference** type (one slot, `aload`/`areturn`, never boxed). Pre-existing since the
Phase 15 `TypeRef` model; shared by `InterceptedEmitter` (bytecode) and `InterceptedSourceRenderer`.

### Fix
`isPrimitive()` and `isCategory2()` now also require `dims == 0`. `InterceptedSourceRenderer.sourceName`
branches on `primitiveKind() != null` (non-null for primitive arrays, whose `binaryName` is null) so
`int[]` renders as `int[]` rather than NPEing. Verified: heisenberg FT TCK 463/463 (was 458 + 5) and a
new compile-time test `generatesValidGlueForPrimitiveArrayInterceptedMethods` (source path).

---

## VAU-INT-003 — source-rendered `$$Intercepted` of a method declaring `throws` fails to compile
- **Date**: 2026-06-07 — **Status**: FIXED (`InterceptedSourceRenderer`: pre-init guard moved inside the try)
- **Severity**: medium (any APT-generated subclass of a bean whose intercepted method declares a checked
  exception fails `javac` — `unreported exception java.lang.Exception; must be caught or declared`)
- **Surfaced by**: the new `mansart-transactions-cdi-module-it` module-path vehicle — `TxService.required(Block)`
  declares `throws Exception`. Invisible to the golden/structural tests (no checked-throws fixture) and to
  the bytecode emitter (the JVM does not enforce checked exceptions on `super.<m>()`), so only the SOURCE
  renderer (APT) is affected, and only when a bean's intercepted method declares a checked exception.

### Cause
The generated override drops the original `throws` clause (a `MethodShape` carries no exceptions — by
design, the intercepted path rethrows via `sneaky(...)`). But the **pre-init guard** that delegates to
`super.<m>(...)` before `$$init` ran was emitted *outside* the `try { … } catch (Throwable) { throw
sneaky(t); }` block, so `javac` saw an uncaught checked exception from the bare `super` call.

### Fix
The pre-init guard is now emitted as the first statement *inside* the `try`, so the checked exception from
`super.<m>(...)` propagates through `sneaky(...)` (which rethrows the very same throwable — behaviour
identical to the bytecode emitter). The vehicle's `TxModulePathTest` (a `@Transactional` bean whose
methods `throws Exception`) is the live regression: it generates, compiles and runs the build-time
subclass on the module path. (A vauban-local compile-time test mirroring it is a follow-up.)

---

## VAU-INT-004 — `@Interceptor` beans are not emitted into `_VaubanComponents` (module-path needs an opens)
- **Date**: 2026-06-07 — **Status**: FIXED (APT emits interceptors as components; runtime routes their instantiation through the provider)
- **Severity**: medium (a wrapper that declares `@Interceptor` beans cannot reach zero-opens on a strict
  module path: the interceptor instances fall back to reflective instantiation)
- **Surfaced by**: `mansart-transactions-cdi-module-it` — `container.select(TxService.class)` on the module
  path throws `Cannot reflectively access …TransactionalInterceptorRequiresNew … provide a generated
  VaubanComponentProvider … or open the package`. Invisible to every class-path TCK (dirac Metrics,
  heisenberg FT, etc. run on the class path, where reflection is unrestricted).

### Cause
`VaubanProcessor.CDI_ANNOTATIONS` (and the plugin's `VaubanGenerator`) select scope-annotated beans and
`@Produces` only — **`jakarta.interceptor.Interceptor` is excluded**. So the APT discovers interceptors
for binding resolution (`discoverInterceptors()`) and emits their `$$Intercepted` subclasses for target
beans, but never emits the interceptor classes themselves into `_VaubanComponents.create()`/`_Factory`.
On the module path the container therefore instantiates them reflectively, which needs
`opens … to io.vidocq.vauban.core`.

### Fix
Two parts. **(1) APT** — `jakarta.interceptor.Interceptor` is added to `VaubanProcessor.CDI_ANNOTATIONS`, so
interceptor classes enter the APT round/index and `BeanDiscovery` (which already lists `@Interceptor` as a
bean-defining annotation) returns them as `@Dependent` managed beans. They then flow through the normal
component pipeline: a `_Factory`, a `_VaubanComponents.create()` arm and `injectField` coverage (their
`@Inject` fields are package-private; inherited fields are keyed on the declaring class by `BeanInjector`,
so the empty per-TxType subclasses are covered too). `isInterceptedTarget` now returns `false` for
`@Interceptor` classes so no bogus `Interceptor$$Intercepted` is generated. **(2) Runtime** —
`InterceptorBeanWrapper` routed both interceptor-instantiation sites through `instantiatePreferProvider(...)`
(renamed from `instantiateIntercepted`): in-module provider → public Lookup → reflection. The interceptor's
public `@AroundInvoke` is already reachable without opens (F3 public-member guard).

Proven by `mansart-transactions-cdi-module-it` with **zero opens** (REQUIRED on the base interceptor and
REQUIRES_NEW on an empty subclass with an inherited `@Inject` field). Non-regression: dirac Metrics 5.1
127/127, heisenberg FT 4.1 463/463, vauban full suite green. The plugin (`VaubanGenerator`) is unchanged —
all first-party interceptor wrappers use the APT; mirroring it for external jars is a follow-up.

---

## VAU-INT-005 — APT forces `@InterceptorBinding` completion, breaking module-path interception of marker bindings
- **Date**: 2026-06-07 — **Status**: FIXED (name-based meta detection, no symbol completion)
- **Severity**: medium (a wrapper whose interceptor binding is a `@InterceptorBinding`-meta marker —
  e.g. heisenberg's `@FaultToleranceBinding` stamped on beans by a BCE `@Enhancement` — cannot have its
  targets' `$$Intercepted` generated by the APT for a downstream module on a strict module path)
- **Surfaced by**: `heisenberg-cdi-vauban-module-it` — the `@Retry` fixture's `default-compile` fails with
  `cannot access jakarta.interceptor.InterceptorBinding / class file … not found`
  (`com.sun.tools.javac.code.Symbol$CompletionFailure`). Invisible to every class-path TCK (heisenberg FT
  runs `useModulePath=false`); also distinct from dirac's `@Counted`, which is registered as a custom
  binding via `meta.addInterceptorBinding(...)` and is not itself `@InterceptorBinding`-meta.

### Cause
`VaubanProcessor.isInterceptorBinding(am)` decided "is this annotation an interceptor binding?" with
`am.getAnnotationType().asElement().getAnnotation(jakarta.interceptor.InterceptorBinding.class) != null`.
The class-literal form forces javac to **complete** the `jakarta.interceptor.InterceptorBinding` symbol.
When the binding marker (`@FaultToleranceBinding`) is declared in a dependency module that only
`requires static jakarta.interceptor`, that completion fails for a downstream module-path compile: a
`requires static` read edge is absent from a consumer's module graph, so the marker module cannot read
`jakarta.interceptor` during the consumer's annotation-processing round. The unchecked `CompletionFailure`
aborts the APT. Neither `requires transitive jakarta.interceptor` on the marker module nor
`--add-reads` on the consumer fixes it — the failure is in symbol completion during the APT round.

### Fix
Detect the meta-`@InterceptorBinding` by **name** over the annotation type's own meta-mirrors instead of by
class literal — the same pattern already used for the `@Interceptor` early-return in `isInterceptedTarget`.
Reading a meta-mirror's `getAnnotationType().toString()` only touches the constant pool, so it never
triggers completion. Behaviour is identical for every resolvable case (custom non-meta bindings like
`@Counted` still return `false`; meta-bindings still return `true`), but a marker behind `requires static`
no longer aborts the build.

Proven by `heisenberg-cdi-vauban-module-it` (`@Retry` intercepted 4× on the module path, **zero opens**,
`FaultToleranceService$$Intercepted` generated at build time). Non-regression: dirac Metrics 5.1 127/127,
heisenberg FT 4.1 463/463, vauban full suite green. The `requires transitive jakarta.interceptor`
work-around attempted before this fix was reverted (unnecessary once the APT stops forcing completion).

---

## VAU-DISC-002 — non-bean archive over-discovers an annotated, non-scanned class (trade-off vs VAU-DISC-001)
- **Date**: 2026-06-07 — **Status**: FIXED (commit `80e953c` — explicit bean-discovery mode)
- **Severity**: low (1 CDI TCK failure)
- **Surfaced by**: CDI TCK `CustomStereotypeTest` (`build.compatible.extensions.customStereotype`).

### Symptom
`CustomStereotypeTest.test:49` fails with `expected [true] but found [false]`. The failing line is
`assertTrue(getBeans(NotDiscoveredBean).isEmpty())` — i.e. **`NotDiscoveredBean` IS discovered when
it must not be**. (The earlier two assertions pass, so the custom-stereotype *scope* — `addStereotype`
+ `@ApplicationScoped` on `MyService` — actually works; the initial "MetaAnnotations gap" reading was
wrong.) The archive has no `beans.xml` (`beanArchive(false)`), the BCE `@Discovery` adds only
`MyService` via `ScannedClasses.add`, and `NotDiscoveredBean` is `@Dependent` but neither scanned nor
forced — yet it surfaces as a bean.

### Cause
`BeanDiscovery.isAllowedByScannedClassesFilter` admits any bean-defining-annotated class even in a
non-bean archive (the VAU-DISC-001 fix). That is exactly what the Mansart Data TCK relies on: its
`@Singleton @Produces DataSource` lives in a `beanArchive(false)` archive, is not scanned, and must
still be discovered (else every `@Repository` is unsatisfied → `EntityTests.setup` NPEs). So the two
TCKs demand opposite answers for the *same* Vauban inputs — `beanArchive(false)` + BCE
`ScannedClasses.add` + an annotated, non-scanned class. Verified: replacing the annotation bypass
with `forcedBeanClasses.contains(...)` makes the CDI TCK 774/774 **but** regresses Mansart Data to
73 errors ("DataSource bean not found"). Reverted.

### Fix
Made the discovery mode explicit instead of guessing from `beanArchive(boolean)`:
`BeanDiscovery.setStrictScannedDiscovery` / builder `strictScannedDiscovery(boolean)`.
`false` (default, "annotated") keeps the VAU-DISC-001 bypass → Mansart's non-scanned `@Produces`
stays discovered; `true` ("none" / CDI-Lite synthetic) admits only `forcedBeanClasses` (BCE
`ScannedClasses` + class-path bean-archive scan). The CDI TCK runner
(`vauban-tck-runner` `VaubanDeployableContainer`) sets `strictScannedDiscovery(!hasBeansXml)`, so its
`withoutBeansXml()` BCE archives use "none" mode; Mansart's runner leaves the default. Verified BOTH
ways: **CDI TCK 774/774** (CustomStereotypeTest passes) and **Mansart Data TCK 73/73** (unchanged).

---

## VAU-EVT-001 — checked exception from a synchronous observer wrapped as CreationException
- **Date**: 2026-06-07 — **Status**: FIXED (commit on `pr/ybl/wip-bce-static-metadata`)
- **Severity**: medium (CDI TCK `CheckedExceptionWrappedTest`)

### Symptom
A synchronous observer throwing a **checked** exception surfaced as `CreationException` instead of
the CDI-required `ObserverException`.

### Cause
When observer dispatch was rerouted through `VaubanLookup.invokeMethod` (in-module provider first,
reflective fallback), `invokeMethod` wraps a non-runtime throwable in `CreationException` — correct
for producers, wrong for observers. `CreationException extends RuntimeException`, so the dispatch's
`catch (RuntimeException) rethrow` branch re-threw it verbatim and the
`catch (Exception) -> ObserverException` branch became unreachable.

### Fix
`EventDispatcher` now wraps each observer-method invocation: a `CreationException` from
`invokeMethod` (which always wraps the observer's own checked exception — runtime exceptions/errors
are propagated unwrapped) is unwrapped and rethrown as `ObserverException`. CDI TCK: 2 → 1 failure.

---

## VAU-DISC-001 — non-bean archive + BCE `ScannedClasses` suppressed annotated beans (incl. `@Produces`)
- **Date**: 2026-06-02 — **Status**: FIXED
- **Severity**: high (any non-bean archive whose Build Compatible Extension calls
  `ScannedClasses.add(...)` — every legitimately-annotated bean outside the scanned set silently vanished)
- **Surfaced by**: Mansart Jakarta Data 1.0 TCK harness — the whole official `EntityTests` suite
  (73/73) errored in `@BeforeEach` with `assertNotNull` on injected repositories. The misleading
  proximate symptom was a `null` injection; the real cause was upstream in Vauban.

### Symptom
A container built with `beanArchive(false)` whose BCE adds classes via `ScannedClasses.add(...)`
discovered **only** the scanned classes. Any other class in the archive — even one carrying a
bean-defining annotation — was dropped, including its `@Produces` members. In the TCK this meant the
test-supplied `@Singleton @Produces DataSource` (`H2DataSourceProducer`) was never registered, so
`MansartRuntimeProducer` saw an unsatisfied `Instance<DataSource>` and threw "No @Default DataSource
bean found", which left every `@Repository`'s `RepositoryRuntime` unsatisfied → `@Inject` repo = null.

### Cause
`VaubanContainerBuilder.build()` calls `discovery.setScannedClassesFilter(scannedDotNames)` when
`!isBeanArchive` and the BCE scanned classes (lines ~818). In `BeanDiscovery.discoverBeans()` the
filter (`isAllowedByScannedClassesFilter`) ran **before** the bean-defining-annotation check and
turned the scanned set into an exclusive whitelist — contrary to CDI "annotated" discovery, where a
bean-defining annotation is always a discovery trigger and `ScannedClasses.add` only *augments* the
set with otherwise-unannotated classes. (Observers/disposers/interceptors never used this filter,
which is why only managed beans + producers were affected.)

### Fix
`isAllowedByScannedClassesFilter` now also returns `true` for any class with a bean-defining
annotation (index or reflection): the scanned-classes filter governs only *non-annotated* forced
classes and can no longer hide annotated beans. Regression:
`BeanDiscoveryTest$ScannedClassesFilter.annotatedProducerSurvivesScannedClassesFilter`. Full vauban
reactor green; downstream Mansart Jakarta Data TCK now 74/74 (EntityTests 73 + SignatureTests 1).

## VAU-PRX-003 — APT client proxy emits an invalid descriptor for a method whose return/param type is a NESTED class
- **Date**: 2026-06-01 — **Status**: FIXED
- **Severity**: high (any `@ApplicationScoped`/normal-scoped bean with a method returning or taking a
  nested class; the bean becomes uninstantiable at runtime once injected)
- **Surfaced by**: Arago Phase 1 — `AttendeeTokens.verify()` returning the nested record
  `AragoJwt.Claims`. Injecting `AttendeeTokens` into a resource threw, at request time:
  `NoClassDefFoundError: io/vidocq/tools/arago/auth/AragoJwt/Claims` (note the `/` before `Claims`).

### Symptom
The compile-time `*_ClientProxy` for such a bean has a method whose bytecode descriptor uses `/`
where `$` is required for a nested class (e.g. `Lio/.../AragoJwt/Claims;` instead of
`Lio/.../AragoJwt$Claims;`). Loading the proxy and resolving its methods (e.g. `getMethod(...)` during
interceptor-proxy setup) raises `NoClassDefFoundError` / `ClassNotFoundException` with a dotted name.

### Cause
APT path only. `ElementScanner.typeMirrorToTypeInfo` built the `DotName` for a `DeclaredType` from
`TypeElement.getQualifiedName()` — the **canonical** name (`Outer.Nested`, dot-separated). Fed to
`ClassDesc.of(...)`, the dot becomes a `/`, yielding `Outer/Nested`. (The runtime
`RuntimeClientProxyGenerator` was correct — it uses `Class.describeConstable()`/`descriptorString()`.)

### Fix
`ElementScanner` now uses `Elements.getBinaryName(element)` (the binary name, `Outer$Nested`) for the
`ClassType`/`ParameterizedType` raw name. Top-level types are unaffected (binary == qualified).
Regression: `BceCompileTimeTest.clientProxyHandlesNestedReturnType` (compiles a bean returning a
nested record, loads the generated proxy, asserts the method resolves with the `$` binary name).

## VAU-PROXY-INTERFACE — no client proxy for an interface-typed normal-scoped bean; `Instance`/`Provider` and nested generics lose their type arguments

**Date**: 2026-05-26
**Status**: `FIXED` — 2026-05-26 (branch `pr/ybl/cdi-interface-proxy-generics`)
**Severity**: high — blocks any normal-scoped bean whose bean type is an **interface** (the common case of a `@RequestScoped @Produces` method returning an interface), and any `Instance<T>`/`Provider<T>` whose `T` is parameterized. Concrete case: MicroProfile JWT's `@Produces @RequestScoped JsonWebToken` and `@Inject Provider<Optional<String>>` / `ClaimValue<Set<String>>` — surfaced by the Cervantes MP JWT 2.1 TCK.

**Symptom (1) — interface client proxy**: injecting a normal-scoped bean whose bean type is an interface into a wider-scoped consumer leaves the field `null` (the subclass-based `RuntimeClientProxyGenerator` cannot subclass an interface, the proxy creation fails and is swallowed). Repro: `vauban-core` `InterfaceClientProxyTest` — a `@RequestScoped @Produces Identity` (interface) injected into an `@ApplicationScoped` consumer; the field must be a non-null proxy that throws `ContextNotActiveException` out-of-scope and resolves the per-request instance in-scope. Red before the fix (field null).

**Symptom (2) — parameterized lookup**: `@Inject Instance<Optional<String>>` / `Provider<Set<String>>` resolves against the raw class (`Optional`, `Set`) instead of the full parameterized type, so it cannot match a synthetic bean registered with the exact `Optional<String>` type; nested generic arguments (`ClaimValue<Set<String>>`) are flattened to `Object`.

**Fix** (`vauban-core`, CDI container only — no public-API change):
- `InterceptorBeanWrapper` — when the proxy target type is an interface, build a `java.lang.reflect.Proxy` (implementing all interface bean types) that lazily delegates each call to the contextual instance of the active scope, instead of failing on the subclass generator.
- `InstanceImpl` + `BeanInjector` — carry the full `java.lang.reflect.Type` (possibly a `ParameterizedType`) as the resolution type for `getBeans()`/`getReference()`, alongside the raw class used for generic bounds (new full constructor).
- `ManagedBean` — reconstruct parameterized bean types **recursively** so nested type arguments (`ClaimValue<Set<String>>`) are preserved.

**Validation**: full `vauban-core` suite green (282 tests with the new `InterfaceClientProxyTest`); end-to-end via the Cervantes MicroProfile JWT 2.1 TCK (206/206). Symptoms (2) are exercised by the TCK's `@Claim` injection matrix.

---

## VAU-INJ-PRIM — primitive injection point exposed to BCE lang model as `ClassType[name=boolean]` instead of `PrimitiveType`

**Date**: 2026-05-26
**Status**: `FIXED` — 2026-05-26 (branch `pr/ybl/jwt-needs`)
**Severity**: medium — breaks any Build Compatible Extension that boxes primitive injection points by detecting `type instanceof PrimitiveType` (the spec-correct pattern, used by Ravel's `ConfigCdiExtension` and Cervantes' `CervantesClaimExtension`). Concrete case: `@Inject @Claim boolean emailVerified` (MicroProfile JWT) — the field was silently left at its default with no deployment error.

**Symptom**: a `@Dependent` consumer with an `@Inject` field of a *primitive* type qualified toward a wrapper-typed synthetic bean is never injected (field stays at the Java default; the wrapper-typed sibling field works). No error surfaces — `BeanInjector` resolves the field via `getBeans(boolean, quals)` (empty, because the synthetic bean got registered under a bogus `ClassType[boolean]`), falls back to `select(boolean)` which looks up `@Default` and throws `UnsatisfiedResolutionException`, which is swallowed.

**Repro**: `vauban-core` `PrimitiveQualifiedInjectionTest.primitiveInjectionPointIsPrimitiveTypeAndBoxes` — a BCE collects `@Tag` injection-point types in `@Registration` and boxes primitives (`instanceof PrimitiveType`) in `@Synthesis`; a primitive `@Tag boolean`/`int` field must be injected.

**Cause**: `BeanInfo.injectionPoints().get(i).type()` is built by `VaubanBceInjectionPointInfo.type()` → `TypeMapper.map(ip.requiredType(), …)`. The indexer encodes a primitive field's `requiredType()` as `TypeInfo.ClassType` whose `name` is a reserved primitive (`"boolean"`, `"int"`, …), not a `TypeInfo.PrimitiveType`. `TypeMapper` forwarded it verbatim as `VaubanClassType[boolean]`, so `instanceof PrimitiveType` was false in the extension → no boxing → synthetic bean registered under the bogus class type → resolution miss.

**Fix**: `TypeMapper.map` now maps a `TypeInfo.ClassType` whose name is a reserved primitive to `VaubanPrimitiveType` (CDI Lite lang model contract). Targeted at the BCE boundary; the indexer's internal `TypeInfo` is untouched. Full `vauban-core` suite green (281 tests); verified end-to-end against Cervantes (`@Claim boolean` now injects).

---

## VAU-BCE-001 — BCE pipeline: `@Registration` sees zero IP, `@Synthesis` loses parameterized types and qualifier members

**Date**: 2026-05-09
**Status**: `FIXED` — 2026-05-09 (branch `fix/vau-bce-001-bce-not-invoked`)
**Severity**: high — blocks any Build Compatible Extension that (a) traverses `BeanInfo.injectionPoints()` in `@Registration`, (b) declares a `SyntheticBean` with a parameterized type (`Optional<T>`, `List<T>`, `Provider<T>`, …), or (c) attaches a qualifier whose members drive resolution. Concrete case: Ravel's `ConfigCdiExtension` (MicroProfile Config 3.1) — blocks M6 (Vidocq ecosystem integration) as long as the BCE is not recognized.

### Symptom

The BCE pipeline is *entered* (the `@Registration` / `@Synthesis` methods are correctly dispatched by `BceProcessor`), but on arrival the deployment fails with:

```
jakarta.enterprise.inject.spi.DeploymentException:
CDI deployment validation failed:
  - Unsatisfied dependency: field <Bean>.<field>
      of type ClassType[name=java.lang.String]
      with qualifiers […, @ConfigProperty(name=app.greeting, defaultValue=Hello)]
```

or, earlier in the pipeline, an `IllegalArgumentException`:

```
@Registration error: Class not found in index: org.eclipse.microprofile.config.inject.ConfigProperty
@Registration error: Class not found in index: java.lang.String
```

or even a silent `NullPointerException` surfaced as `@Synthesis error: null` when the extension builds a `ParameterizedType` via `Types.parameterized(Optional.class, types.of(String.class))`.

### Minimal repro

```java
@jakarta.inject.Qualifier
@Retention(RUNTIME) @Target({FIELD, PARAMETER, METHOD, TYPE})
public @interface Tagged { String value() default ""; }

@Dependent
public static class TargetBean {
    @Inject @Tagged("scalar") public String scalar;
    @Inject @Tagged("optional") public Optional<String> opt;
}

public class TestBce implements BuildCompatibleExtension {
    @Registration(types = Object.class)
    public void registrar(BeanInfo bean) { /* observe bean.injectionPoints() */ }

    @Synthesis
    public void synth(SyntheticComponents components, Types types) {
        components.addBean(String.class)
                .type(String.class).qualifier(taggedLiteral("scalar"))
                .scope(Dependent.class).createWith(ScalarCreator.class);
        components.addBean(Object.class)
                .type(types.parameterized(Optional.class, types.of(String.class)))
                .qualifier(taggedLiteral("optional"))
                .scope(Dependent.class).createWith(OptionalCreator.class);
    }
}

SeContainerInitializer.newInstance()
        .addBeanClasses(TargetBean.class, TestBce.class)
        .initialize();
// → DeploymentException: Unsatisfied dependency on @Tagged("scalar") + @Tagged("optional")
```

The test covers exactly the API surface used by `ravel-cdi-vauban/.../ConfigCdiExtension`, which passes **349/349** on the MicroProfile Config 3.1 TCK under Weld 6.0.2 — so the Ravel code is spec-compliant; the bug is entirely on the Vauban side.

### Root cause — six cumulative defects on the same path

| # | Site | Problem |
|---|---|---|
| 1 | `VaubanBceBeanInfo.injectionPoints()` | Stub `return List.of();` — the extension receives zero IP, its `@Registration` collects nothing, `@Synthesis` synthesizes nothing. |
| 2 | `VaubanAnnotationInfo` | No override of `name()`. The CDI Lite 4.1 API default (`return declaration().name();`) goes through `lookup.requireClass(<annotation FQN>)` which crashes for annotations outside the index (typical: `@ConfigProperty`, which lives in `microprofile-config-api`). |
| 3 | `VaubanClassType.declaration()` | `lookup.requireClass(name)` throws `IllegalArgumentException("Class not found in index: java.lang.String")` for any JDK or third-party type absent from the scan. The spec contract requires `ClassType.declaration()` to succeed for any type in the model. |
| 4 | `VaubanSyntheticBeanBuilder.type(Type)` | No-op (`return this; // simplified`) — any `addBean(...).type(<lang-model Type>)` is silently lost, and the synthetic bean ends up exposing only `Object`. Systematic hit on `Optional<T>`, `List<T>`, `Provider<T>`, etc. |
| 5 | `VaubanTypes.ofClass(String name)` | Returns `null` if the class is not in the index. Causes an NPE when the extension builds `types.parameterized(Optional.class, types.of(String.class))` → `List.of(null, …)`. |
| 6 | `BceProcessor.toBeanDescriptor` | `qualifiers.add(new QualifierInstance(qName, Map.of()));` — the qualifier members are lost. Consequence: `@Tagged("scalar")` on the synthetic bean side vs `@Tagged("scalar")` on the IP side no longer resolve, because the bean qualifier has `members={}` whereas the IP qualifier has `members={value=StringVal[scalar]}`. The resolver considers them distinct. |

### Fix

Seven files touched in `vauban-core`, all on the BCE / lang-model pipeline:

1. `extensions/VaubanBceBeanInfo.injectionPoints()` — delegates to a new wrapper `VaubanBceInjectionPointInfo` that adapts each `BeanDescriptor.InjectionPointInfo` (internal model) to `jakarta.enterprise.inject.build.compatible.spi.InjectionPointInfo` with the type, the qualifiers (members preserved via `VaubanAnnotationInfo`) and the declaration (`FieldInfo` when parsable from the description, otherwise `ClassInfo` fallback).
2. `langmodel/VaubanAnnotationInfo` — override of `name()` returns `indexAnnotation.name().value()` directly without going through `declaration()`.
3. `langmodel/types/VaubanClassType.declaration()` — synthetic fallback (`io.vidocq.vauban.indexer.model.ClassInfo` with just the `name`) when the type is not indexed.
4. `extensions/VaubanSyntheticBeanBuilder.type(Type)` — converts the lang-model `Type` to a `TypeInfo` via the new helper `LangModelTypeMapper`, stored in a new `Set<TypeInfo> indexTypes`. Accessible via `getIndexTypes()`.
5. `extensions/LangModelTypeMapper` (NEW) — inverse of `langmodel.types.TypeMapper`: `jakarta…Type → indexer TypeInfo` (recursive over primitives, classes, parameterized, arrays, wildcards, type-variables).
6. `extensions/VaubanTypes.ofClass(String)` — always returns a `VaubanClassType`, no more `null` even if the type is not in the index (the fallback from #3 takes over).
7. `extensions/BceProcessor.toBeanDescriptor` — merges `synBean.getIndexTypes()` into the type set, and calls a new helper `extractAnnotationMembers(Annotation)` (reflection: each method of the annotation type → corresponding `AnnotationValue`, support for primitives / String / Class / Enum / nested Annotation / arrays).

No public signature modified. No impact on the pre-processed APT path (the `vauban-bce-runtime.list` / `vauban-bce-processed` markers go through other call-sites).

### Regression test

`vauban-core/src/test/java/io/vidocq/vauban/core/extensions/BceRegistrationSynthesisTest.java` (3 tests):

- `registrationExposesInjectionPoints` — verifies that `BeanInfo.injectionPoints()` exposes the 2 IPs with their type AND the `value` member of the `@Tagged` qualifier (covers #1, #2, #6 on the read path).
- `syntheticScalarBeanResolves` — `@Synthesis` registers a `SyntheticBean<String>` with qualifier `@Tagged("scalar")`, the IP `@Inject @Tagged("scalar") String` resolves (covers #6 on the resolution side).
- `syntheticParameterizedBeanResolves` — `@Synthesis` with `type(types.parameterized(Optional.class, types.of(String.class)))`, the IP `Optional<String>` resolves (covers #3, #4, #5).

`vauban-core`: 269 tests, 0 failure (266 baseline + 3 new). `ravel-cdi-vauban`: 21 tests, 0 failure; the test `VaubanContainerIntegrationTest.resolves_config_property_injection_through_bce_pipeline` (until now `@Disabled` for residual M6) now passes locally — it can be re-enabled on the Ravel side once a Vauban snapshot containing this fix is published.

### Follow-up

- `VaubanBceInterceptorInfo.injectionPoints()` remains a stub — `InterceptorDescriptor` does not track IPs yet; not critical for M6, to be opened separately (VAU-BCE-002 if needed).
- The other call sites of `new QualifierInstance(qName, Map.of())` (BceProcessor lines 1010, 1071, 1141; VaubanContainerBuilder line 1172) follow the same member-loss pattern; to be investigated if a non-synthetic case requires it.

---

## VAU-MVN-001 — `VaubanGenerator` propagates `NoClassDefFoundError` instead of skipping the class

**Date**: 2026-05-09
**Status**: `FIXED` — 2026-05-09 (branch `fix/vau-mvn-001-classpath-noclassdef`)
**Severity**: medium — blocks any build that consumes an artifact whose "optional" transitive dependency is missing from the classpath provided to the plugin (concrete case: `microprofile-config-api:3.1.1`, which references `jakarta.activation`).

### Symptom

During the `process-classes` phase of `vauban-maven-plugin`, the JVM raises an uncaught linkage error and the build fails:

```
java.lang.NoClassDefFoundError: jakarta/activation/DataSource
    at java.base/java.lang.Class.getDeclaredFields0(Native Method)
    at io.vidocq.vauban.maven.generate.VaubanGenerator.loadArchiveClasses(VaubanGenerator.java:352)
    at io.vidocq.vauban.maven.generate.VaubanGenerator.generate(VaubanGenerator.java:141)
    at io.vidocq.vauban.maven.generate.GenerateMojo.execute(GenerateMojo.java:62)
```

No `META-INF/vauban-beans.list` is produced for the affected module, and all subsequent Maven-dependent modules fail in cascade.

### Minimal repro

A Maven project that:
1. declares `org.eclipse.microprofile.config:microprofile-config-api:3.1.1` in `compile`,
2. runs `vauban:generate` (`process-classes` phase).

The scenario is exactly that of `ravel-cdi-vauban` packaged via Vauban (see `ravel/CLAUDE.md`).

### Root cause

Two call sites of `Class.forName(name, false, cl)` in `VaubanGenerator`:

| Line | Context | Original catch |
|---|---|---|
| ~211 | proxy / interceptor generation (in `generate`) | `ClassNotFoundException` |
| ~352 | `loadArchiveClasses` (reflective loading of indexed classes) | `ClassNotFoundException` |

`Class.forName(name, false, cl)` does not trigger static init but does trigger **linkage**: the JVM must resolve the superclass, the interfaces, and the field/method types. Any referenced class missing from the classloader passed as a parameter throws `NoClassDefFoundError` (a subclass of `LinkageError`, hence *not* a `ClassNotFoundException`).

The plugin builds its classloader via `GenerateMojo.buildClassLoader(...)` from `project.getArtifacts()`. Dependencies marked `optional=true` on the scanned artifact (typically `microprofile-config-api` → `jakarta.activation`) do not propagate up to the plugin classpath → the first call site that touches a field/method of the imported class blows up with `NoClassDefFoundError`.

### Fix

`vauban-maven-plugin/src/main/java/io/vidocq/vauban/maven/generate/VaubanGenerator.java`, **both** call sites widen their catch to `NoClassDefFoundError`:

- `loadArchiveClasses` (~352): silently ignored (the class will not be loaded for the rest of the BCE / proxy / interceptor pipeline, but remains available on the bytecode indexer side).
- proxy generation (~211): adds the existing warning ("Cannot load class for generation: <fqn>").

No semantic change with respect to the normal contract: we simply replace a hard crash with a graceful degradation aligned with the behavior already in place for `ClassNotFoundException`.

### How to avoid the regression

`VaubanGeneratorTest` still passes (9/9). To add as follow-up: `shouldSkipClassWithMissingTransitive`, which generates via Class-File API a class annotated `@ApplicationScoped` extending a non-existent `com.missing.Parent`, embeds it in a scanned JAR, and verifies that `generate()` returns without throwing.

### Possible widening

`Class.forName` can also throw other `LinkageError`s (`ClassFormatError`, `IncompatibleClassChangeError`, `UnsupportedClassVersionError`, `VerifyError`). If we observe one of these cases in production, widen the catch to `LinkageError` (common parent). For now the fix stays targeted at the observed symptom.

---

## VAU-PRX-002 — Drift between `ClientProxyGenerator` (compile-time) and `RuntimeClientProxyGenerator` (runtime)

**Date**: 2026-05-07
**Status**: `FIXED` — 2026-05-07
**Severity**: high (makes any `@ApplicationScoped` bean injected via `cassini-cdi-vauban` unusable on the JAX-RS side when the `_ClientProxy.class` is pre-generated by APT).

### Symptom

`InterceptorBeanWrapper.getOrCreateProxy` throws a `DeploymentException("Failed to create client proxy for normal-scoped bean ...")`. Cassini then falls back to `cls.getDeclaredConstructor().newInstance()`, which returns a raw, non-injected instance → NPE on invocation (`this.dataSourceInstance is null`, `this.products is null`, etc.).

```
Caused by: java.lang.NoSuchMethodException: …DatabaseInspectorResource_ClientProxy.<init>()
    at java.lang.Class.getDeclaredConstructor(Class.java:2491)
    at io.vidocq.vauban.core.container.InterceptorBeanWrapper.lambda$getOrCreateProxy$0(InterceptorBeanWrapper.java:235)
```

### Root cause

Two client proxy generators coexisted with incompatible contracts:

- `vauban-processor/.../ClientProxyGenerator.java` (compile-time, APT) emitted: final `delegate` field + `(Supplier)` ctor + putfield in the constructor.
- `vauban-core/.../RuntimeClientProxyGenerator.java` (runtime fallback) emits: non-final `$$delegate` field + no-arg ctor + `$$setDelegate(Supplier)` setter.

`InterceptorBeanWrapper.getOrCreateProxy` (lines 235-249) expects exclusively the second format. `loadOrDefineClassRobustly` loads the APT-pre-generated `_ClientProxy.class` in priority, so the old format always shadows the runtime one.

### Why `vidocq-runtime-cassini-rest-example` worked anyway

Its `target/classes/.../TodoResource_ClientProxy.class` came from an earlier compilation done with a version of `ClientProxyGenerator` that already produced the modern format — it simply had not been regenerated since the drift.

### Fix

`vauban/vauban-processor/src/main/java/io/vidocq/vauban/processor/codegen/proxy/ClientProxyGenerator.java`: aligned with the `RuntimeClientProxyGenerator` format:
- `$$delegate` field (non-final).
- Public no-arg constructor calling `super()`.
- `$$setDelegate(Supplier)` method.
- All `getfield "delegate"` → `getfield "$$delegate"`.

`ClientProxyGeneratorTest` tests updated to use the new contract (no-arg ctor + setter).

### How to avoid the regression

`ClientProxyGeneratorTest.shouldDelegateMethodCalls` / `shouldDelegateVoidMethods` now invoke the proxy via `getDeclaredConstructor().newInstance()` then `getMethod("$$setDelegate", Supplier.class)` — exactly the code that `InterceptorBeanWrapper.getOrCreateProxy` does. Any future unilateral drift of either generator immediately breaks these tests.

---

## VAU-INJ-001 — Field injection eagerly resolves normal-scope beans (loses the client proxy)

**Date**: 2026-05-07
**Status**: `FIXED` — 2026-05-07
**Severity**: high (makes `@TransactionScoped` / `@RequestScoped` unusable as a direct `@Inject` field).

### Symptom

When an `@ApplicationScoped` bean (or another managed bean) declares an `@Inject T`
where `T` is a **normal-scope** bean (`@TransactionScoped`, `@RequestScoped`, …),
field injection tries to resolve the context at boot/instance-creation time —
hence *out-of-scope* — and throws a
`ContextNotActiveException`. The field stays `null`; any subsequent invocation
causes an NPE.

```
INJECTION FAILED FOR audit ON class …ProductResource$$Intercepted :
  @TransactionScoped accessed outside an active transaction
jakarta.enterprise.context.ContextNotActiveException
    at io.vidocq.mansart.transactions.cdi.TransactionScopedContext.requireActiveTx
    at io.vidocq.mansart.transactions.cdi.TransactionScopedContext.get
    at io.vidocq.vauban.core.container.InterceptorBeanWrapper.lambda$getOrCreateProxy$0
    at io.vidocq.vauban.core.container.InterceptorBeanWrapper.getOrCreateProxy
    at io.vidocq.vauban.core.container.VaubanContainer.getOrCreateProxyForBean
    at io.vidocq.vauban.core.container.VaubanBeanManager.getReference        ← here
    at io.vidocq.vauban.core.container.BeanInjector.lambda$injectFieldsByReflection$0  ← calls getReference at boot
```

### Minimal repro

The `vidocq-runtime-mansart-h2-example` project reproduces this on the module-path:

```java
@ApplicationScoped
@Path("/products")
public class ProductResource {
    @Inject OperationAudit audit;        // @TransactionScoped → null after injection

    @POST @Transactional
    public Response create(Product input) {
        audit.record("…");                // NPE this.audit is null
    }
}

@TransactionScoped
public class OperationAudit implements Serializable { … }
```

### Suspected cause

`BeanInjector.injectFieldsByReflection` (vauban-core, line ~90):

```java
value = bm.getReference(resolved, fieldType, ctx);
```

For a normal-scope bean, `getReference` must return a **lazy client
proxy**: a wrapper object that resolves the context *on each method
invocation* (not at creation time). Today `getReference` →
`getOrCreateProxy` → `ApplicationContext.get` → `ManagedBean.create` → which
tries to create the instance immediately. Out-of-scope, the `OperationAudit`
cannot be created and the returned proxy is null.

The `OperationAudit_ClientProxy` client proxy does in fact exist (generated by
`ClientProxyGenerator`), with a `(Supplier<OperationAudit>)` constructor —
that is exactly the correct pattern. But `BeanInjector` does not instantiate
it; it calls `getReference`, which resolves the instance, losing the
lazy-resolution offered by the proxy.

### Documented workaround

Inject via `Provider<T>` or `Instance<T>`: `BeanInjector` has a specific
branch (lines 38-53) for these types that correctly creates a lazy
wrapper.

```java
@Inject Provider<OperationAudit> audit;   // OK
…
audit.get().record("…");                  // resolves in the active scope
```

### Applied fix (2026-05-07)

**Actual root cause**: `VaubanContainer.getContextualInstance` called
`context.get(contextual)` (without `CreationalContext`, i.e. "look up existing")
BEFORE checking `isNormal()`. For a scope inactive at boot
(`@TransactionScoped` outside a TX, `@RequestScoped` outside a request), this `context.get()`
calls `checkActive()` → `ContextNotActiveException`. This exception was
swallowed by the `BeanInjector` catch, leaving the field at `null`.

Second vector: the catch-all in `InterceptorBeanWrapper.getOrCreateProxy`
degraded to an eager `ctx.get()` for any scope when an exception occurred
during proxy creation.

**Fix 1 — `VaubanContainer.getContextualInstance`**: move the
`isNormal()` check to the first statement, before any call to `context.get()`.
The proxy is returned immediately without ever touching the context.

**Fix 2 — `InterceptorBeanWrapper.getOrCreateProxy` catch block**: for
`isNormal()` beans, throw `DeploymentException` instead of trying `ctx.get()`
eagerly.

**TDD test added**: `NormalScopeFieldInjectionTest` (4 cases) in
`vauban-core/src/test/java/io/vidocq/vauban/core/container/`.

262 vauban-core tests — 0 failures, 0 errors after the fix.

**TCK regression discovered and fixed (2026-05-07)**: Fix 2 caused 7 CDI TCK failures
(`EventTypesTest`, `MemberLevelInheritanceTest`, `InvokerAssignabilityTest`,
`VarargsMethodInvokerTest`). Actual cause: `RuntimeClientProxyGenerator.generateProxyMethod`
crashed for methods having array-type parameters (`Song[]`, `int[]`,
`String...` varargs) because `Class.describeConstable()` returns `Optional.empty()` for these
types and the fallback `ClassDesc.of(type.getName())` used the JVM descriptor format
(e.g. `"[Lorg...Song;"`) that `ClassDesc.of()` rejects. The `DeploymentException` from Fix 2
exposed this generation error that was previously silently ignored.

**Fix 3 — `RuntimeClientProxyGenerator.classDescOf(Class<?>)`**: helper that uses
`ClassDesc.ofDescriptor(type.descriptorString())` as a fallback — a format accepted for
all types (arrays, primitives, references). All
`describeConstable().orElse(ClassDesc.of(...))` calls replaced by `classDescOf()`.

CDI TCK **774/774 PASS** — 0 failures after Fix 3.

---

## VAU-TYP-001 — three private ParameterizedType/GenericArrayType copies with divergent equals/hashCode

- **Date**: 2026-06-10 — **Status**: FIXED (uncommitted — Phase 16a modernization batch)

**Symptom**: found by code review during the Java-modernization pass, no failing test
observed yet. `vauban-core` carried **three** independent synthetic implementations of
`java.lang.reflect.ParameterizedType` — `ManagedBean.ResolvedParameterizedType`,
`BeanDiscovery.ResolvedParamType` and an anonymous class in
`TypeHierarchyResolver.substitute` (plus a second anonymous one in
`ManagedBean.substituteTypeVariables`) — each with a **different** `equals`/`hashCode`
contract:

| Copy | equals checks owner? | hashCode includes owner? |
|---|---|---|
| `ManagedBean.ResolvedParameterizedType` | yes | **no** |
| `BeanDiscovery.ResolvedParamType` | yes | yes |
| `TypeHierarchyResolver` anonymous | **no** | no |
| JDK `ParameterizedTypeImpl` (reference) | yes | yes |

**Risk**: bean types live in hash-based sets (`LinkedHashSet<Type>`) mixing JDK-built
types and Vauban-built ones. Two equal types with different hash codes silently land in
different buckets → duplicate bean types, missed assignability matches. Only observable
when `ownerType != null` (nested generic classes) or when the laxer
`TypeHierarchyResolver` equals deduplicated types it should not.

Same family: `ManagedBean.ResolvedGenericArrayType` (record) kept the record-generated
`equals`, which only matches its own record type — `jdkGat.equals(resolvedGat)` was true
while `resolvedGat.equals(jdkGat)` was false (asymmetric), and the anonymous
`GenericArrayType` in `substituteTypeVariables` had **identity** equals/hashCode.

**Fix (2026-06-10)**:
- `BeanDiscovery.resolveReflectType` now delegates to `ManagedBean.resolveType` /
  `buildTypeVariableMapping`; the private copies (`resolveReflectTypeWithMapping`,
  `ResolvedParamType`, `ResolvedGenArrayType`, ~80 lines) are deleted.
- `ManagedBean.ResolvedParameterizedType.hashCode` aligned on the JDK formula
  (`Arrays.hashCode(args) ^ Objects.hashCode(owner) ^ Objects.hashCode(raw)`).
- `ManagedBean.ResolvedGenericArrayType` given explicit `equals`/`hashCode` matching
  `GenericArrayTypeImpl` semantics (any `GenericArrayType` with equal component).
- The two anonymous classes replaced by the records
  (`ManagedBean.substituteTypeVariables` → `ResolvedParameterizedType` /
  `ResolvedGenericArrayType`; `TypeHierarchyResolver.substitute` → new private
  `SubstitutedParameterizedType` record with JDK-aligned contract).
- Bonus dedup: the three near-identical `collectTypesFromSupers` /
  `collectTypesWithMapping` / `collectTypesWithMappingSkipSelf` walkers merged into a
  single `collectSupertypes(..., boolean addSelf)`.

**Validation**: full reactor `clean install` green + CDI 4.1 Lite TCK **774/774 PASS**.

## BUG-20260712-01 — vauban-api ships a hardcoded VERSION constant

- **Date** : 2026-07-12
- **Statut** : FIXED (branch fix/build-derived-version — ships with the next release)
- **Module touché** : vauban-api / Vauban.java
- **Symptôme** : the artifact published on Maven Central as 0.2.0 reports
  `Vauban.VERSION = "0.1.0-SNAPSHOT"` — the constant is maintained by hand and was
  never updated by the release train. Same class as vidocq BUG-20260704-01 (CLI banner).
- **Reproduction minimale** :
  ```
  jshell --class-path vauban-api-0.2.0.jar -q \
    -s <(echo 'System.out.println(io.vidocq.vauban.api.Vauban.VERSION)')
  ```
- **Hypothèse de cause** : compile-time constant, no build filtering.
- **Investigations** :
  - 2026-07-12 : found by grepping for stale version strings after the issue #3
    follow-up. Fixed: `version.properties` filtered by Maven next to the class,
    constant loaded at class init (same-module Java Modules resource, no opens needed).
    No runtime consumers existed; the constant is no longer compile-time-inlineable,
    which also protects future consumers from the javac inlining trap.

## VAU-BCE-004 — Synthetic bean types given as runtime array classes never resolve

- **Date** : 2026-07-13
- **Statut** : FIXED (2026-07-13, branch pr/ybl/synthetic-array-bean-types)
- **Module touché** : vauban-core (SyntheticBeanConverter, AssignabilityRules, ManagedBean, VaubanContainer, VaubanBeanManager)
- **Symptôme** : a BCE registering `addBean(Boolean[].class).type(Boolean[].class)`
  (MP Config's ConfigCdiExtension does, for `@ConfigProperty` array injection points)
  yields "Unsatisfied dependency" for every `ArrayType` injection point: the bean type
  was recorded as a flat `ClassType(DotName("[Ljava.lang.Boolean;"))`.
- **Reproduction minimale** : `SyntheticArrayBeanTypeTest` (boxed, primitive, `Class<?>[]`).
- **Hypothèse de cause** : `SyntheticBeanConverter.toBeanDescriptor` mapped every runtime
  `Class` to `ClassType(cls.getName())`; array matching, runtime bean-type resolution
  (`resolveArrayClass` on primitive-named components), `select(Class)` and
  `typesMatch(GenericArrayType)` each had the same blind spot.
- **Investigations** :
  - 2026-07-13 : found by migrating the MP Config TCK runner to the assembled Vidocq
    runtime (ravel BUG-20260713-01 family 1). Fixed end to end; array element matching
    now follows CDI 4.1 §5.2.4 (identical element types, no boxing, raw ↔ unbounded
    parameterized equivalence).

## VAU-LKP-001 — Programmatic lookup drops qualifiers and hides the injection point

- **Date** : 2026-07-13
- **Statut** : FIXED (2026-07-13, branch pr/ybl/synthetic-array-bean-types)
- **Module touché** : vauban-core (VaubanCDI, InstanceImpl)
- **Symptôme** : `CDI.current().select(type, qualifiers...)` ignored the given
  qualifiers (resolved `@Default` or failed `UnsatisfiedResolution`); synthetic bean
  creators invoked through a programmatic lookup saw an EMPTY `InjectionPoint`
  (type `Object`) instead of the selected type/qualifiers, breaking MP Config's
  `@ConfigProperties` programmatic lookups (`ClassCastException: Object`).
- **Reproduction minimale** : `VaubanCDISelectQualifierTest`.
- **Investigations** :
  - 2026-07-13 : ravel BUG-20260713-01 family 2 (programmatic side). select now
    forwards qualifiers (CDI 4.1 §11.1) and `Instance.get()` without an underlying
    injection point installs a synthetic `InjectionPoint` carrying the selected
    type and qualifiers; an enclosing injection point stays visible to a creator's
    internal lookups.

## VAU-DSC-002 — Build-time composite discovery loader hides source-loader resources

- **Date** : 2026-07-13
- **Statut** : FIXED (2026-07-13, branch pr/ybl/synthetic-array-bean-types)
- **Module touché** : vauban-core (VaubanContainerBuilder.buildCompositeClassLoader)
- **Symptôme** : during the build (BCE phases included) the TCCL is a composite
  loader that only delegated classes and `getResourceAsStream`; `getResources`
  (plural) fell through to the parent, so a deployment's
  `META-INF/microprofile-config.properties` and ServiceLoader `ConfigSource`s were
  invisible to MP Config during deployment validation. An embedded deployment
  contributing no class of its own (parent-first loader over classes that also
  exist on the application classpath) lost its resources entirely.
- **Reproduction minimale** : `CompositeLoaderResourceTest` (multi-loader + entry-TCCL).
- **Investigations** :
  - 2026-07-13 : ravel BUG-20260713-01 family 3. `findResource`/`findResources` now
    aggregate every source loader, and the caller-installed context loader joins the
    composite.

## VAU-OBS-002 — Observer method non-event parameters are not injection points

- **Date** : 2026-07-13
- **Statut** : FIXED (2026-07-13, branch pr/ybl/synthetic-array-bean-types)
- **Module touché** : vauban-core (BeanDiscovery)
- **Symptôme** : the non-event parameters of observer methods (CDI 4.1 §10.4.3) were
  absent from the bean descriptor: deployment validation skipped any parameter with a
  custom qualifier (ad-hoc check) and BCEs never saw them through
  `BeanInfo.injectionPoints()` — MP Config could neither validate
  `@ConfigProperty` observer parameters nor synthesize the beans satisfying them
  (TCK `MissingValueOnObserverMethodInjectionTest`).
- **Reproduction minimale** : `ObserverParamValidationTest`.
- **Investigations** :
  - 2026-07-13 : ravel BUG-20260713-01 family 4 (observer side). Observer non-event
    parameters are now collected as `METHOD_PARAMETER` injection points
    (EventMetadata excluded); the typed-resolution `AmbiguousResolutionException`
    message now lists the matching beans.

## VAU-BCE-005 — @Vetoed added at @Enhancement ignored for index-discovered beans

- **Date** : 2026-07-14
- **Statut** : FIXED (2026-07-14)
- **Module touché** : vauban-core (EnhancementApplier)
- **Symptôme** : a BCE adding `@Vetoed` to a bean class in its `@Enhancement` phase
  (the CDI Lite idiom for replacing a discovered managed bean with a synthetic one)
  only takes effect when the class goes through the pre-discovery archive pass. For a
  bean already discovered — the normal case for pre-indexed application classes on the
  module path — `EnhancementApplier.applyEnhancements` applied qualifier/scope changes
  but silently ignored the added `@Vetoed`, so the managed bean stayed registered and
  every injection point of its interface became `AmbiguousResolution` once the
  replacing synthetic bean was added.
- **Reproduction minimale** : mansart MANSART-005 — `AuditEntryRepositoryImpl`
  (managed `@Singleton`, vetoed by `MansartDataExtension`) + the routing synthetic
  bean; boot `vidocq-runtime-mansart-h2-example` on the module path.
- **Hypothèse de cause** : `applyEnhancements` only rewrote descriptors; it never
  dropped one. Bean discovery must see enhancement-modified annotations, so an added
  `@Vetoed` has to exclude the class exactly like a source-level one.
- **Investigations** :
  - 2026-07-14 : fixed — `applyEnhancements` drops any descriptor whose enhancement
    configs add `@Vetoed` (class-level, `getAddedAnnotations` + `getAddedAnnotationInfos`).
    Regression test `BceVetoedEnhancementTest`; full reactor green; CDI Lite TCK re-run
    774/774.

## VAU-CTX-001 — BCE-registered custom contexts silently dropped on the module path

- **Date** : 2026-07-14
- **Statut** : FIXED (2026-07-14)
- **Module touché** : vauban-core (VaubanContainerBuilder, InterceptorBeanWrapper)
- **Symptôme** : a context registered through `MetaAnnotations.addContext(scope, isNormal,
  contextClass)` was never installed when the app runs on the strict module path: the
  builder instantiated the context class via `privateLookupIn` (needs `opens`), caught the
  failure and **silently skipped** the context. Every normal-scoped bean of that scope then
  hit the client-proxy delegate's **silent `@Dependent` fallback** — a fresh instance per
  proxy call, so state written through the proxy vanished on the next call. Seen as
  mansart's `@TransactionScoped` bean always being empty in
  `vidocq-runtime-mansart-h2-example` ("TX audit: []").
- **Reproduction minimale** : `TxModulePathTest.transaction_scoped_context_works_on_the_module_path`
  (mansart-transactions-cdi-jpms-it) — red before the fix (`expected: <2> but was: <0>`).
- **Hypothèse de cause** : two compounding silent fallbacks. (1) The custom-context
  registration only knew the provider path and the private-lookup path; a public context
  class in an exported package (the normal case, and what the BCE API implies) needs
  neither. (2) The proxy delegate treated "no context registered for this scope" as
  "@Dependent", violating CDI 4.1 §6.5.1.
- **Investigations** :
  - 2026-07-14 : fixed — context instantiation now tries the module's
    `VaubanComponentProvider`, then the PUBLIC no-arg constructor via
    `MethodHandles.publicLookup()` (no opens needed for an exported package), then the
    private lookup; an uninstallable declared context is now a `DeploymentException`
    instead of a silent skip. The proxy delegate throws `ContextNotActiveException` when
    no context is registered for a normal scope instead of handing out per-call
    `@Dependent` instances. CDI Lite TCK re-run 774/774.

## BUG-20260824-01 — BCE on the compile path but absent from the processor path is silently ignored

- **Date** : 2026-08-24
- **Statut** : FIXED 726dc38
- **Module touché** : `vauban-processor` (`VaubanProcessor.init()` / `discoverBceClasses`)
- **Symptôme** : in a standalone Vauban + Mansart app, declaring Maven
  `<annotationProcessorPaths>` (e.g. for `mansart-data-processor`) narrows javac's
  `-processorpath` to those entries only. `VaubanProcessor` discovers
  `BuildCompatibleExtension` implementations via
  `ServiceLoader.load(BuildCompatibleExtension.class, VaubanProcessor.class.getClassLoader())`
  (`VaubanProcessor.java:146-147`, `:647-661`), i.e. on the processor path — so a BCE
  living in a regular dependency jar (`mansart-data-cdi`'s `MansartDataExtension`) never
  runs, its `ScannedClasses.add(...)` contributions are missing, and the user gets a
  confusing hard error much later:
  `[Vauban] Unsatisfied dependency: parameter 0 of <Repo>Impl() of type ClassType[name=io.vidocq.mansart.data.core.RepositoryRuntime] ...`
  (concrete final type → `mayBeSatisfiedElsewhere()` returns false → not deferred).
  Reported by an external user following the Mansart README/getting-started.
- **Reproduction minimale** :
  ```
  App with mansart-data-{core,cdi,dialect-postgresql} as dependencies, one @Repository
  interface + entity, and:
    <annotationProcessorPaths>
      <path>io.vidocq.mansart:mansart-data-processor</path>
    </annotationProcessorPaths>
  mvn compile → [Vauban] Unsatisfied dependency ... RepositoryRuntime
  Workaround/fix: add io.vidocq.mansart:mansart-data-cdi as an extra <path> (reference:
  mansart-transactions/mansart-transactions-cdi-module-it/pom.xml:88-110, plus
  -Avauban.validation=false for cross-module bean resolution the APT cannot see).
  ```
- **Hypothèse de cause** : not a resolution bug — a DX gap. The APT cannot load BCEs from
  the compile/module path (javac gives it no classloader over it), but it *can* detect the
  mismatch: dependency jars are visible as `-classpath`/`--module-path` entries and a scan
  for `META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension`
  (or `provides` in `module-info.class`) would identify BCEs its own ServiceLoader cannot
  see. Proposed fix: emit a `Diagnostic.Kind.WARNING` (and enrich the unsatisfied-dependency
  error) naming the jar and suggesting the `<annotationProcessorPaths>` addition.
- **Investigations** :
  - 2026-08-24 : fix implemented (warning + error hint; detection via module `provides`
    directives and the classpath services resource). Javac trap found on the way: reading
    the directives of a module STILL BEING COMPILED completes it prematurely and freezes
    `provides` resolution errors before last-round generated classes exist (broke
    vauban-module-it) — the scan now skips the modules owning this compilation's root
    elements. Covered by BceProcessorPathDiagnosticTest (3 tests); full reactor green.
  - 2026-08-24 : tracked publicly as codefloe issue vauban#29 (companion: mansart#9,
    the original support case + doc fixes).
  - 2026-08-24 : root cause traced end to end (processor-path ServiceLoader at
    `VaubanProcessor.java:650`; `GenerateMojo` runs BCEs later but `processEnhancementOnly`,
    no `@Discovery`/validation, so it cannot compensate). Docs fixed on the Mansart side
    (getting-started now wires `mansart-data-cdi` on the processor path); this entry tracks
    the diagnostic improvement in the APT itself.

---

## VAU-INT-006 — `@AroundInvoke` interceptors fire on `@PostConstruct` / `@PreDestroy` lifecycle callbacks
- **Date**: 2026-09-11 — **Status**: FIXED (vauban#114, branch `pr/ybl/lifecycle-callbacks-not-business`)
- **Severity**: medium (spec violation; concrete effect: a class-level `@Transactional` — or any
  business-method interceptor such as `@Retry`, `@Timed`, `@Logged` — wraps the bean's lifecycle
  callbacks, which neither Weld nor ArC do; a transaction is opened around `@PostConstruct` and
  `@PreDestroy`, and metrics/logging see phantom "business" invocations named `init`/`dispose`)
- **Surfaced by**: a request-scope probe on `vauban-module-it` (APT path, module path, zero opens)
  while checking that normal scopes compose with `$$Intercepted`. Scope behaviour itself is correct
  (one `$$Intercepted` per request, `ContextNotActiveException` outside a request, callbacks on the
  right instance). No existing test covers the `@AroundInvoke` × lifecycle-callback boundary.

### Symptom
For a `@RequestScoped @Audited` bean with package-private `@PostConstruct void init()` and
`@PreDestroy void dispose()`, the `@Audited` interceptor's `@AroundInvoke` records:
```
CALLS = [init/0, id/0, id/0, thisClass/0, outer/0, inner/0, viaCollaborator/0, dispose/0]
```
`init/0` and `dispose/0` must not be there. Jakarta Interceptors 2.2 §2.2/§5: `@AroundInvoke` applies
to **business method** invocations only; lifecycle callbacks are intercepted solely by the interceptor's
own `@PostConstruct`/`@PreDestroy` methods, and `InvocationContext.getMethod()` is `null` for them. Here
`getMethod()` returned the callback `Method` and the chain ran as a business invocation.

### Repro
```java
@RequestScoped @Audited
public class RequestAuditedService {
    @PostConstruct void init() {}
    @PreDestroy  void dispose() {}
    public int id() { return 1; }
}
// boot VaubanContainer with AuditInterceptor (@AroundInvoke records ctx.getMethod().getName())
container.requestContext().runInScope(() -> service.id());
// observed: CALLS contains "init" and "dispose"; expected: only "id"
```
Same outcome on the APT path (`vauban-module-it`, source-rendered `$$Intercepted`) and, by
construction, on the runtime/weaver fallback — both share the same override filter.

### Cause
Both override filters exclude `@Inject` initializer methods and target-class `@AroundInvoke` methods,
but **not lifecycle callbacks**:
- APT: `InterceptedShapeFromElements.shouldIntercept` (vauban-processor) — checks `INJECT`, `AROUND_INVOKE`.
- Runtime: `InterceptorSubclassGenerator.shouldIntercept` (vauban-core) — same two checks.

So a non-private, non-final `@PostConstruct`/`@PreDestroy` method is overridden in `<Bean>$$Intercepted`
like any business method (confirmed in the generated source: `@Override public void init()` /
`dispose()` build a `VaubanInvocationContext` and call `proceed()`). `BeanLifecycle` then invokes the
callback by **virtual dispatch on the `$$Intercepted` instance** (`vaubanLookup.invokeMethod(instance,
pcMethod)`, with `pcMethod` resolved on the superclass) → the override runs → the `@AroundInvoke`
chain fires. The dedicated lifecycle chain (`resolveLifecycleChain(..., PostConstruct.class, ...)`)
is built correctly on top of that — the defect is only the extra business-chain wrap.

### Proposed fix
Exclude every Jakarta lifecycle-callback annotation from both `shouldIntercept` filters (single
authority — `InterceptedShape`/shared IR — so APT, runtime emitter and weaver stay in lockstep):
`jakarta.annotation.PostConstruct`, `jakarta.annotation.PreDestroy`, `jakarta.interceptor.AroundConstruct`,
`jakarta.interceptor.AroundTimeout`, `jakarta.ejb.PostActivate` / `PrePassivate` (name-based, no symbol
completion — cf. VAU-INT-005). Add a regression test to `vauban-module-it` (APT, module path) and to
`vauban-core` asserting that an `@AroundInvoke` interceptor never sees a lifecycle callback and that
`InvocationContext.getMethod()` is `null` inside lifecycle interceptor methods.

### Why the CDI TCK did not catch it
The TCK has the exact fixture (`Goat` + `AnimalInterceptor` in `LifecycleCallbackInterceptorTest`) but
only asserts the positive path (lifecycle interceptor methods *are* called); its `@AroundInvoke` records
nothing and no test asserts it was *not* invoked for a callback. The only `assertFalse(methodIntercepted)`
cover `Object` methods and `@Inject` initializers — the two cases the filter already excludes. Details
and a proposed upstream assertion: `CDI_TCK_PROPOSALS.md` → `TCK-GAP-001`.

### Investigations
- 2026-09-11 : reproduced on `vauban-module-it` (0.4.0-SNAPSHOT, APT path). Root cause traced to the two
  `shouldIntercept` filters above; generated `RequestAuditedService$$Intercepted` confirmed to override
  `init()`/`dispose()`. Probe fixtures removed after the run (not committed).
- 2026-09-11 : TCK 4.1.0 coverage analysed (`cdi-tck-core-impl` sources) — gap documented as `TCK-GAP-001`.
- 2026-10-07 : fixed. `BeanMembers.NON_BUSINESS_METHOD_ANNOTATIONS` (vauban-core, `core.codegen`, exported to the
  processor) lists, by name, the annotations that make a method something other than a business method: `@Inject`,
  `@AroundInvoke`, `@AroundConstruct`, `@AroundTimeout`, `@PostConstruct`, `@PreDestroy`, `@PostActivate`,
  `@PrePassivate`. `InterceptorSubclassGenerator.shouldIntercept` and `InterceptedShapeFromElements.shouldIntercept`
  both read it, so the callbacks are no longer overridden in `$$Intercepted` and `BeanLifecycle`'s virtual call runs
  the bean's own method under the lifecycle chain only. Tests: `LifecycleCallbackInterceptionTest` (vauban-core,
  run-time path: a normal-scoped and a `@Dependent` bean, `getMethod()` is `null` in the interceptor's lifecycle
  methods) and `LifecycleCallbackModulePathTest` (vauban-module-it, processor path, module path), both failing
  first. heisenberg, dirac, cyrano and mansart rebuilt against it, their tests green.

## BUG-20260911-01 — Build-time Class-File bytecode takes the build JDK's class-file version, not the release

- **Date**: 2026-09-11
- **Status**: FIXED f9a2f4d
- **Module**: `vauban-processor` (`BeanFactoryGenerator`, and `ClientProxyEmitter` when the processor calls it: build-time `_ClientProxy` classes and the placed-proxy resources of #42 Stage 4); `vauban-maven-plugin` (`ComponentProviderClassGenerator` from `VaubanGenerator` and `DependencyEnhancer`).
- **Symptom**: a module compiled with `--release 25` on a JDK 26 ships its Class-File-generated classes at class file 70 while javac's own output is 69. A Java 25 runtime then rejects them: `UnsupportedClassVersionError: …JsonWebTokenContext_Factory has been compiled by a more recent version of the Java Runtime (class file version 70.0)` from `cassini-maven-plugin:generate`, and `jlink` fails with `Unsupported class file version: 70`.
- **Minimal reproduction** (observed, not yet rebuilt deliberately): eleven `io.vidocq` 0.4.0-SNAPSHOT jars in the local Maven repository, built on 2026-09-10, mix class files 69 and 70; only the `*_Factory` classes are 70.
  ```
  # with JDK 26 running Maven, a brick whose pom sets release 25:
  ./mvnw -o install -DskipTests -pl knock-cdi-vauban
  # class major of a generated factory: 70 (javac's classes: 69)
  # then, on Java 25, jlink over that jar: "Unsupported class file version: 70"
  ```
- **Suspected cause**: every build-time site calls `ClassFile.of().build(desc, …)` without `withVersion(...)`, so the class takes `ClassFile.latestMajorVersion()` of the JVM running javac or Maven. The version should follow the compilation target: `processingEnv.getSourceVersion()` in the processor, the project's release (or the major version of the classes being processed) in the plugin. Runtime-only sites (`InterceptedEmitter`, runtime proxies) are unaffected, since the same JVM loads what it defines.
- **Investigations**:
  - 2026-09-12: fixed in f9a2f4d. Every generator builds through `GeneratedClassFile.build`, which pins the class file version to Java 25. Proven by running the new tests on a JDK 26: red (70 vs 69) before, green after, green on Java 25 in both cases. The other bricks' Class-File sites (cassini, cyrano, mansart) generate at runtime only, where the running JVM defines what it writes, so they are unaffected.
  - 2026-09-11: found while gating vidocq#77 offline. The JWT and Knock examples fail on those local jars; CI, which builds on Java 25, does not see it. Site list from `grep "ClassFile.of().build"`: `BeanFactoryGenerator:69`, `ClientProxyEmitter:83`, `ComponentProviderClassGenerator:152` (build time), `InterceptedEmitter:106` (runtime). No site sets a version.

## BUG-20260912-01 — A nested bean's generated classes take its canonical name, so nothing can load them

- **Date**: 2026-09-12
- **Status**: FIXED (this branch)
- **Module**: `vauban-processor` (`apt/ElementScanner#scan`, hence `BeanFactoryGenerator` and `ClientProxyGenerator`)
- **Symptom**: for a nested normal-scoped bean `app.Holder.Counter`, the annotation processor emits `app/Holder/Counter_ClientProxy.class` and `app/Holder/Counter_Factory.class` — a class-as-package directory. The bytes name the class `app.Holder.Counter_ClientProxy` and give it the superclass `app/Holder/Counter`, which does not exist (the bean is `app/Holder$Counter`). The runtime looks up `app.Holder$Counter_ClientProxy`, finds nothing, and the two emitted classes are unloadable anyway: defining either one raises `NoClassDefFoundError: app/Holder/Counter`. A normal-scoped nested bean therefore has no usable client proxy at all.
- **Minimal reproduction**:
  ```java
  // app/Holder.java, compiled with the Vauban processor
  package app;
  public class Holder {
      @jakarta.enterprise.context.ApplicationScoped
      public static class Counter { public String hit() { return "hit"; } }
  }
  // javac -proc:full … → target/classes/app/Holder/Counter_ClientProxy.class
  //   javap: public class app.Holder.Counter_ClientProxy extends app.Holder.Counter
  ```
- **Suspected cause**: `ElementScanner#scan` built the `ClassInfo` name from `typeElement.getQualifiedName()` (canonical: `app.Holder.Counter`) instead of `Elements#getBinaryName` (`app.Holder$Counter`). The same trap had already been fixed for method return/parameter types in `typeMirrorToTypeInfo` (the `$`-vs-`.` comment there), but not for the scanned class's own name, and every artifact derived from it inherited the wrong name.
- **Investigations**:
  - 2026-09-12: found by writing the missing coverage for the APT's bytecode-proxy fallback (`NestedBeanProxyEmissionTest`) — the branch at `VaubanProcessor:480` had no end-to-end test, so the defect was invisible. Fixed by scanning the binary name. The fix is a no-op for top-level types (binary name == qualified name); it only changes nested ones, which were broken. `ComponentProviderCompileTimeTest#nestedBeanIsSkipped` still holds: the generated provider skips nested beans, so the proxy is resolved by name at runtime — which is exactly what the wrong name prevented.

## BUG-20260912-02 — The Vauban layer cannot be created inside a jlink image

- **Date**: 2026-09-12
- **Status**: FIXED (this branch)
- **Module**: `vauban-classloader` (`VaubanLayerFactory#applicationPaths`, `Launch#run`)
- **Symptom**: an application whose `main` starts with `Launch.run(...)` does not start at all from a `jlink` image. It fails with a message that names the wrong cause:
  ```
  Exception in thread "main" java.lang.IllegalStateException: No application module to re-layer.
  With -m, the launcher is the only root module: add --add-modules ALL-MODULE-PATH to the java
  command line. …
  ```
  Adding `--add-modules` changes nothing: there is no module path to add anything from. Consequently **every proxying case that depends on the layer is unavailable under `jlink`** — the in-package proxies the build ships under `META-INF/vauban/placed/` are never placed, and so is anything else the layer's loader does (sjar decryption, load-time weaving).
- **Minimal reproduction** (verified 2026-09-12 on the cdi#1015 example, which runs correctly on a plain module path):
  ```
  jlink --module-path <deps> --add-modules io.vidocq.vauban.example.cdi1015.app --output /tmp/img
  /tmp/img/bin/java -m io.vidocq.vauban.example.cdi1015.app/…Main
  # → IllegalStateException: No application module to re-layer
  ```
- **Suspected cause**: `applicationPaths` collects the archives to re-layer from each resolved module's `reference().location()`, keeping only URIs whose scheme is `file:` (`VaubanLayerFactory.java:94` and `:164`). In a runtime image every module's location is `jrt:/<module>`, so the filter drops all of them and the resulting path list is empty. The same filter is what makes the loader index the archives it owns, so simply lifting it is not enough: a `jrt:` module has no archive to read bytes from, and the layer's loader would have to read its classes through the `jrt` file system (`FileSystems.getFileSystem(URI.create("jrt:/"))`) instead.
- **Investigations**:
  - 2026-09-12: found while answering whether the loader-based placement survives `jlink`. It does not. `jpackage` is affected whenever it wraps a jlink runtime image; `jpackage` over a plain module path is not (same shape as the working `java -p` run). Until this is fixed, the build-time route — `vauban:enhance-dependencies`, which rewrites the dependency jar — is the only one that works under `jlink`, as it already is for GraalVM native images.
  - 2026-09-13: **fixed**. `applicationPaths` and `createAppLayer` no longer keep only `file:` locations; both go through `archiveOf`, which also maps a `jrt:` module to `/modules/<name>` of the `jrt` file system. Nothing else had to change: a module in a runtime image is an exploded directory, and `BuiltInReaders` already walks directories, so the layer's loader reads it exactly as it reads a `target/classes`. Verified end to end — the cdi#1015 example now runs from a jlink image with its layer, its placed proxies (`FraudScreen_ClientProxy`, `ReceiptPrinter_ClientProxy` defined inside the library's package) and its interceptor. A probe written first confirmed the JDK allows it: `ModuleFinder.of(jrtPath)` finds the module and a child layer resolves from it, with its own loader and its own class identity. The unit test needs no jlink run: every JVM has `jrt:/java.base`.

## BUG-20260914-01 — Boot validation ignores member defaults, so `@Q` and `@Q("default")` never match

- **Date**: 2026-09-14
- **Status**: FIXED 358fc16
- **Module**: `vauban-core` (`QualifierMatcher#qualifierEquals`); neither `ClassFileScanner` nor `ElementScanner` records member defaults.
- **Symptom**: a valid deployment fails with `DeploymentException: Unsatisfied dependency` when an injection point and a bean spell the same qualifier differently, one relying on a member's default and the other writing it out. The same lookup done programmatically resolves, because the run-time path reads members through the annotation, defaults included.
- **Minimal reproduction** (`QualifierMemberResolutionTest$DefaultedMember`):
  ```java
  @Qualifier @Retention(RUNTIME) @interface Graded { String value() default "standard"; }
  @Graded @Dependent class GradedImplicit implements Service {}
  @Dependent class GradedField { @Inject @Graded("standard") Service service; }
  // VaubanContainer.builder().addBeanClass(GradedImplicit.class).addBeanClass(GradedField.class).build()
  // → DeploymentException: Unsatisfied dependency: field GradedField.service
  ```
- **Suspected cause**: the index keeps explicit member values only, and `qualifierEquals` compares the two member maps as they are, so a member written on one side and defaulted on the other counts as a mismatch. `java.lang.annotation.Annotation#equals` treats both spellings as the same annotation.
- **Investigations**:
  - 2026-09-14: found by the vauban#70 safety net; the tests are disabled with this id.
  - 2026-09-14: **fixed** by vauban#70 PR 2. Both scanners now record the default of each annotation member (`MethodInfo#defaultValue`), and `QualifierMatcher` compares `AnnotationKey`s, in which every member the type declares takes its written value or its default. Proven by mutation: with defaults left out of the key, both `$DefaultedMember` tests fail again with the same `DeploymentException`, and so do the default tests of `AnnotationTypesTest` and `QualifierMatcherTest`.

## BUG-20260914-02 — A field injection point drops enum, Class, char, byte, short, array and nested-annotation member values

- **Date**: 2026-09-14
- **Status**: FIXED b71b31a
- **Module**: `vauban-core` (`QualifierHelper#annotationValueToObject`, reached from `BeanInjector#injectSingleField`)
- **Symptom**: boot validation accepts the injection point, then the field is injected wrongly at creation, without any error: it stays `null`, or receives the `@Default` bean when there is one. A constructor parameter or a programmatic lookup with the same qualifier resolves correctly.
- **Minimal reproduction** (`QualifierMemberResolutionTest$EnumMember#field`, `$NoDefaultFallback#enumField`):
  ```java
  @Qualifier @Retention(RUNTIME) @interface Colored { Hue value(); }
  @Colored(Hue.RED) @Dependent class ColoredOne implements Service {}
  @Colored(Hue.BLUE) @Dependent class ColoredTwo implements Service {}
  @Dependent class PlainService implements Service {}
  @Dependent class ColoredField { @Inject @Colored(Hue.BLUE) Service service; }
  // container.select(ColoredField.class).service → PlainService; null without PlainService
  ```
- **Suspected cause**: the field's qualifiers are rebuilt from the index as `QualifierHelper` proxies, and `annotationValueToObject` converts only String, boolean, int, long, float and double members; any other member reads as `null`. `getBeans` then compares `null` with the bean's value, finds no match, and the injection falls back on a `@Default` lookup. The same proxy also returns `hashCode` 0 and compares only the members it was given.
- **Investigations**:
  - 2026-09-14: found by the vauban#70 safety net for every listed kind (`$ClassMember#field`, `$NarrowPrimitiveMembers#field`, `$ObjectArrayMember#field`, `$PrimitiveArrayMember#field`, `$NestedAnnotationMember#field`); the tests are disabled with this id.
  - 2026-09-14: **fixed** by vauban#70 PR 3a, which was aimed at BUG-20260914-03: field injection rebuilds its qualifiers through the same conversion as `Bean#getQualifiers()`, and `AnnotationInstances` keeps every member kind, so `getBeans` compares the values it was given. The seven tests are enabled here, once the change proved it. The field path still loads the qualifier type through the thread context class loader (BUG-20260914-05) and still rebuilds an instance per injection; PR 3b resolves it on keys instead.

## BUG-20260914-03 — Container-built qualifier instances misreport array and nested members and leave `@Nonbinding` out of `equals` and `hashCode`

- **Date**: 2026-09-14
- **Status**: FIXED b71b31a
- **Module**: `vauban-core` (`QualifierUtils#createAnnotationInstance`, `#convertAnnotationValue`, `#membersEqual`, `#computeAnnotationHashCode`)
- **Symptom**: one proxy, two visible effects.
  - Resolution: a qualifier with an `int[]` or a nested-annotation member never matches at run time; constructor injection and programmatic lookups throw `UnsatisfiedResolutionException`. A `String[]` member happens to match (see the cause).
  - `Bean#getQualifiers()` breaks the `java.lang.annotation.Annotation` contract: reading an `int[]` member throws `ClassCastException: [Ljava.lang.Object; cannot be cast to [I`, a nested member returns `null`, and `equals`/`hashCode` skip `@Nonbinding` members, so `@Noted(value = "v", note = "n")` equals `@Noted(value = "v", note = "other")` and its hash code differs from the JDK's.
- **Minimal reproduction** (`BeanQualifiersContractTest`, `QualifierMemberResolutionTest$PrimitiveArrayMember`, `$NestedAnnotationMember`):
  ```java
  @Qualifier @Retention(RUNTIME) @interface Codes { int[] value(); }
  @Codes({1, 2}) @Dependent class Specimen implements Shape {}
  Annotation built = beanManager.resolve(beanManager.getBeans(Shape.class, Any.Literal.INSTANCE))
          .getQualifiers().stream().filter(q -> q.annotationType() == Codes.class).findFirst().orElseThrow();
  ((Codes) built).value();                                  // ClassCastException
  Specimen.class.getAnnotation(Codes.class).equals(built);  // false
  ```
- **Suspected cause**: `convertAnnotationValue` turns every array into a fresh `Object[]` and every nested annotation into `null`, so an `int[]` member can never equal an `int[]`, while a `String[]` passes `Objects.deepEquals` by luck. The handler's `equals` and `hashCode` apply CDI's `@Nonbinding` rule, which belongs to resolution, not to `Annotation#equals`. `getQualifiers()` also rebuilds these proxies on every call.
- **Investigations**:
  - 2026-09-14: found by the vauban#70 safety net, with the JDK's own annotation instance as the expected value; the tests are disabled with this id. The CDI TCK tests of this contract (`QualifierEquivalenceTest`, `InterceptorBindingEquivalenceTest`) belong to `cdi-full` and are excluded from the Lite run.
  - 2026-09-14: **fixed** by vauban#70 PR 3a. `AnnotationInstances` replaces the five proxy handlers with one instance built from index data: each member returns its declared type — an `int[]` as an `int[]`, a nested annotation as that annotation — and `equals`, `hashCode` and `toString` follow the `Annotation` specification, `@Nonbinding` members included, since non-binding is a rule of CDI resolution and not of this contract. `Bean#getQualifiers()` builds the set once per bean instead of rebuilding proxies on every call. Proven by mutation: leaving `@Nonbinding` members out of `equals` and `hashCode` fails the two contract tests again, and retyping array members to `Object[]` fails the `int[]` and nested-annotation ones with the original `ClassCastException`.

## BUG-20260914-04 — Boot validation loses `@Nonbinding` on a qualifier type vauban-core's own class loader cannot see

- **Date**: 2026-09-14
- **Status**: FIXED 358fc16
- **Module**: `vauban-core` (`QualifierMatcher#qualifierEquals`)
- **Symptom**: when the application's classes live in their own class loader, as with the TCK runner or a layer created by `Launch`/`Vidocq.run`, an injection point whose `@Nonbinding` member differs from the bean's fails the deployment with `Unsatisfied dependency`. The same fixture deploys when every member value is equal.
- **Minimal reproduction** (`QualifierMemberResolutionTest$IsolatedClassLoader#nonbindingMember`; the fixtures are generated with the Class-File API into a `URLClassLoader`, set as context class loader):
  ```java
  // iso.Marked:      @Qualifier @interface Marked { String value(); @Nonbinding String note(); }
  // iso.MarkedOne:   @Marked(value = "a", note = "one") @Dependent class MarkedOne implements Supplier
  // iso.MarkedLoose: @Inject @Marked(value = "a", note = "two") public Supplier supplier;
  VaubanContainer.builder().classLoader(loader).addBeanClass(…).build();
  // → DeploymentException: Unsatisfied dependency: field MarkedLoose.supplier
  ```
- **Suspected cause**: `qualifierEquals` looks the `@Nonbinding` members up with the one-argument `Class.forName`, which uses vauban-core's defining loader. The `ClassNotFoundException` is swallowed as "no non-binding member", so the differing note is compared.
- **Investigations**:
  - 2026-09-14: found by the vauban#70 safety net; the test is disabled with this id.
  - 2026-09-14: **fixed** by vauban#70 PR 2. The container builds one `AnnotationTypes` for its lifetime. It describes a qualifier type from the index first, then from the type's class file or its declaration, read through the discovery class loader, the context class loader and vauban-core's own loader, in that order, so a `@Nonbinding` member is known whichever loader defines the type. Proven by mutation: with vauban-core's loader alone, and the index kept, `#nonbindingMember` fails again with the same `Unsatisfied dependency`, since `iso.Marked` is not indexed; it fails too when `@Nonbinding` members are kept in the key.

## BUG-20260914-05 — Field injection loads qualifier types through the thread context class loader and silently drops those it cannot load

- **Date**: 2026-09-14
- **Status**: FIXED a0110d4
- **Module**: `vauban-core` (`QualifierHelper#qualifierInstancesToAnnotations`)
- **Symptom**: with the application's classes in their own class loader and the thread context class loader left as it is, a qualified field stays `null` after injection, without any error, although boot validation accepted it.
- **Minimal reproduction** (`QualifierMemberResolutionTest$IsolatedClassLoader#withoutContextClassLoader`): the fixtures of BUG-20260914-04 with equal member values, built without setting the context class loader; `supplier` is `null`.
- **Suspected cause**: the qualifier type is loaded with `Thread.currentThread().getContextClassLoader().loadClass(name)`. A `ClassNotFoundException` skips the qualifier, the injection point is then resolved with `@Default` alone, and nothing matches.
- **Investigations**:
  - 2026-09-14: found by the vauban#70 safety net; the test is disabled with this id.
  - 2026-09-14: **fixed** by vauban#70 PR 3b. Field injection resolves the keys of the injection point the index built, so it builds no qualifier instance and loads no qualifier type. Proven by mutation: rebuilding the qualifiers as instances through the context class loader fails the test again.

## BUG-20260914-06 — Asynchronous observers ignore qualifier member values

- **Date**: 2026-09-14
- **Status**: FIXED a0110d4
- **Module**: `vauban-core` (`EventDispatcher#fireAsync`)
- **Symptom**: an event fired asynchronously with `@Channel("alpha")` reaches both `@ObservesAsync @Channel("alpha")` and `@ObservesAsync @Channel("beta")`. A synchronous `fire` delivers it to the first observer only.
- **Minimal reproduction** (`QualifierMemberEventTest#asynchronousMemberValue`):
  ```java
  public void alpha(@ObservesAsync @Channel("alpha") Ping ping) { … }
  public void beta(@ObservesAsync @Channel("beta") Ping ping) { … }
  event.select(channelAlpha).fireAsync(new Ping("3")).toCompletableFuture().get();
  // received: [async-alpha:3, async-beta:3]
  ```
- **Suspected cause**: `fireAsync` calls the three-argument `findMatchingObservers`, which compares qualifier names only, whereas `fire` passes the annotations on to `observerQualifiersMatchFull`.
- **Investigations**:
  - 2026-09-14: found by the vauban#70 safety net; the test is disabled with this id.
  - 2026-09-14: **fixed** by vauban#70 PR 3b. There is one matching method left, on `AnnotationKey`s, and both paths call it; the event's qualifiers are converted on the calling thread, before the task. Proven by mutation: matching on the qualifier type alone on the asynchronous path fails the test again, with the original two observers notified.

## BUG-20260914-07 — Observers ignore members an extension made non-binding

- **Date**: 2026-09-14
- **Status**: FIXED a0110d4
- **Module**: `vauban-core` (`EventDispatcher#qualifierMembersMatch`)
- **Symptom**: for a qualifier registered with `MetaAnnotations.addQualifier` whose `value` member the extension marks `@Nonbinding`, observer resolution still compares `value`, so an event with another `value` never reaches the observer. Injection and programmatic lookups honour the rule, and the observer does receive the event when every member is equal.
- **Minimal reproduction** (`QualifierMemberEventTest#extensionNonbindingMember`):
  ```java
  meta.addQualifier(Stream.class).methods().stream()
          .filter(m -> m.info().name().equals("value")).forEach(m -> m.addAnnotation(Nonbinding.class));
  public void grouped(@Observes @Stream(value = "observer", group = "g") Ping ping) { … }
  event.select(streamEvent).fire(new Ping("4"));   // @Stream(value = "event", group = "g")
  // received: []
  ```
- **Suspected cause**: `qualifierMembersMatch` skips only members annotated `@Nonbinding` in source; unlike `QualifierMatcher` and `VaubanBeanManager#qualifierEquals`, it never consults the extension-declared set.
- **Investigations**:
  - 2026-09-14: found by the vauban#70 safety net; the test is disabled with this id.
  - 2026-09-14: **fixed** by vauban#70 PR 3b. Observers match on keys, which the container's `AnnotationTypes` builds — and it holds what the extensions declared non-binding, so every path applies the same rule. Proven by mutation: building that metadata without the extension-declared members fails the observer test and the two injection ones.

## BUG-20260914-08 — Interceptor bindings declared by an extension ignore their member values

- **Date**: 2026-09-14
- **Status**: FIXED bbb3c0b
- **Module**: `vauban-core` (interceptor resolution: `InterceptorDiscovery`, `InterceptorManager#bindingMembersMatchWherePresent`)
- **Symptom**: a binding registered with `MetaAnnotations.addInterceptorBinding` binds its interceptor whatever the member values: a method annotated `@Metered(value = "method", group = "other")` is intercepted by an interceptor declared `@Metered(value = "interceptor", group = "g")`, although `group` is binding.
- **Minimal reproduction** (`InterceptorBindingMemberTest#extensionBindingMemberValue`):
  ```java
  meta.addInterceptorBinding(Metered.class).methods()…   // value made @Nonbinding, group stays binding
  @Metered(value = "interceptor", group = "g") @Interceptor @Priority(APPLICATION) class MeteredInterceptor { … }
  @Metered(value = "method", group = "other") public String metered() { … }
  // intercepted; expected not
  ```
- **Suspected cause**: interceptor discovery keeps only annotation types meta-annotated `@InterceptorBinding`, while the bean side also accepts bindings registered by extensions; the interceptor's binding list is then empty and `bindingMembersMatchWherePresent` has nothing to compare.
- **Investigations**:
  - 2026-09-14: found by the vauban#70 safety net. `#extensionNonbindingMember` passed first; its control showed that it passes only because no member is compared at all. `#extensionBindingMemberValue` is disabled with this id.
  - 2026-09-14 (fix, bbb3c0b): two causes, both about values that never reached the comparison. `InterceptorDiscovery` collected an interceptor's binding *annotations* by asking each annotation type for a physical `@InterceptorBinding`, which a type registered by an extension does not carry, so the descriptor's list stayed empty — and `bindingMembersMatchWherePresent` returns `true` on an empty list; it now uses the set of binding names beside it, which already went through the predicate that knows about extensions. And `VaubanMetaAnnotations` reported the members an extension had made `@Nonbinding` for its qualifiers but not for its interceptor bindings, so `value` would have started binding as soon as the first cause was fixed. The comparison itself moved onto `AnnotationKey`, which applies member defaults and drops non-binding members from both sources at once. `#extensionBindingMemberValue` is enabled.

## BUG-20260914-09 — An `@Inherited` qualifier loses its long, float, double, byte, short, char, array and nested members

- **Date**: 2026-09-14
- **Status**: FIXED 358fc16
- **Module**: `vauban-core` (`QualifierResolver#toAnnotationInfo`)
- **Symptom**: a bean inheriting `@Leveled(1L)` from its superclass can neither be injected nor looked up with `@Leveled(1L)`: unsatisfied at boot validation and on lookup. The same scenario with a `String` member resolves on both paths.
- **Minimal reproduction** (`QualifierMemberResolutionTest$InheritedQualifier#longField`, `#longProgrammatic`):
  ```java
  @Inherited @Qualifier @Retention(RUNTIME) @interface Leveled { long value(); }
  @Leveled(1L) abstract class LeveledBase {}
  @Dependent class LeveledChild extends LeveledBase implements Service {}
  @Dependent class LeveledField { @Inject @Leveled(1L) Service service; }
  // → DeploymentException: Unsatisfied dependency: field LeveledField.service
  ```
- **Suspected cause**: inherited annotations are read reflectively and converted by a switch that keeps only `String`, `Boolean`, `Integer`, `Class` and enum values; any other member is dropped from the qualifier, which then cannot equal the required one.
- **Investigations**:
  - 2026-09-14: found by the vauban#70 safety net; the tests are disabled with this id.
  - 2026-09-14: **fixed** by vauban#70 PR 2. `QualifierResolver#toAnnotationInfo` delegates to `AnnotationValues#toAnnotationInfo`, which converts every member kind the way the bytecode scan records it; `AnnotationValuesTest` checks each kind against `ClassFileScanner`. A member that cannot be read is still left out, as before, since the inherited annotation need not be a qualifier. Proven by mutation: keeping only the five former kinds makes `#longField` and `#longProgrammatic` fail again.

## BUG-20260914-10 — A qualifier added by an `@Enhancement` turns enum, Class, array and nested members into strings

- **Date**: 2026-09-14
- **Status**: FIXED b71b31a
- **Module**: `vauban-core` (`EnhancementApplier#annotationMemberToValue`)
- **Symptom**: an extension adding `@Colored(Hue.BLUE)` to a bean class leaves a `@Colored(Hue.BLUE)` injection point unsatisfied at boot validation. Adding a `String`-valued qualifier the same way works.
- **Minimal reproduction** (`QualifierMemberResolutionTest$EnhancementAddedQualifier#enumMember`):
  ```java
  @Enhancement(types = PaintedBlue.class)
  public void paint(ClassConfig config) {
      config.addAnnotation(AnnotationBuilder.of(Colored.class).member("value", Hue.BLUE).build());
  }
  // @Inject @Colored(Hue.BLUE) Service service; → DeploymentException: Unsatisfied dependency
  ```
- **Suspected cause**: the member conversion handles strings and primitives, and maps every other kind to `StringVal(member.toString())`, which cannot equal the injection point's `EnumVal`.
- **Investigations**:
  - 2026-09-14: found by the vauban#70 safety net; the test is disabled with this id.
  - 2026-09-14: **fixed** by vauban#70 PR 3a. `LangModelAnnotations` converts an annotation an extension writes into the index model without losing anything: an enum constant keeps its enum type, a class literal stays a class, nested annotations and arrays are converted element by element. Proven by mutation: stringifying the enum member again fails `$EnhancementAddedQualifier#enumMember` and the lang-model comparison.

## BUG-20260914-11 — The default name of a nested bean class keeps its enclosing class

- **Date**: 2026-09-14
- **Status**: FIXED (vauban#116, branch `pr/ybl/nested-bean-default-name`)
- **Module**: `vauban-core` (`StereotypeResolver` and `BeanDiscovery#decapitalize`), through `DotName#simpleName`
- **Symptom**: `@Named` on the static nested class `Outer.ReportService` names the bean `outer$ReportService` instead of `reportService`, so `@Named("reportService")` is unsatisfied at boot validation and on lookup.
- **Minimal reproduction** (`QualifierMemberResolutionTest$NamedQualifier#field`, `#programmatic`):
  ```java
  class Outer { @Named @Dependent public static class ReportService implements Service {} }
  CDI.current().select(Service.class, NamedLiteral.of("reportService")).get();   // UnsatisfiedResolutionException
  ```
- **Suspected cause**: `DotName#simpleName` cuts the binary name at its last `.`, which leaves `Outer$ReportService` for a nested class; CDI 4.1 §3.1.5 takes the unqualified class name, `ReportService`.
- **Investigations**:
  - 2026-09-14: found by the vauban#70 safety net, whose fixtures are nested classes; the tests are disabled with this id. Top-level classes are not affected. Not part of the vauban#70 rework.
- **Fix**: `ClassInfo` gains a `simpleName` component, the unqualified name as `Class#getSimpleName()` gives it. The
  bytecode scan reads it from the class's own entry of the `InnerClasses` attribute (`""` for an anonymous class,
  the binary simple name for a top-level class, which has no entry); the processor's `ElementScanner` takes
  `TypeElement#getSimpleName()`. `StereotypeResolver` builds the default name from it, and the two enrichment
  copies (`IndexEnricher`, `VaubanGenerator`) keep it through `ClassInfo#withAnnotations`. The eight-argument
  constructor stays and derives the name from the binary name, as before. Covered by the two re-enabled
  `QualifierMemberResolutionTest$NamedQualifier` tests, `ClassFileScannerTest` (nested, top-level and local
  classes) and `ElementScannerMemberValueTest#nestedSimpleName` (both scanners agree).

## BUG-20260914-12 — The processor names nested types canonically in member values, and loses primitive and array class literals

- **Date**: 2026-09-14
- **Status**: FIXED 358fc16
- **Module**: `vauban-processor` (`ElementScanner#scanAnnotations`, `#convertAnnotationValue`, `#typeMirrorToDotName`)
- **Symptom**: the processor's index disagrees with the run-time bytecode scan of the same class. For `@Probe` declared in `app.Holder`, the processor records the annotation `app.Holder.Probe`, an enum value of type `app.Holder.Hue`, a nested `@app.Holder.Inner` and the class literal `app.Holder.Hue`, where the class file says `app.Holder$Probe`, `app.Holder$Hue` and `app.Holder$Inner`. `int.class` and `String[].class` both become `java.lang.Object`.
- **Minimal reproduction** (`ElementScannerMemberValueTest`): compile the fixture with a processor that runs `ElementScanner#scan` on `app.Holder.Target`, then compare with `ClassFileScanner#scan` of `app/Holder$Target.class`.
- **Suspected cause**: annotation, enum and class-literal names come from `getQualifiedName()`, which BUG-20260912-01 replaced by `Elements#getBinaryName` for the scanned class and its method types but not for member values; `typeMirrorToDotName` falls back to `java.lang.Object` for anything that is not a declared type.
- **Investigations**:
  - 2026-09-14: found by the vauban#70 safety net; the tests are disabled with this id. vauban#70 keys qualifier matching on these values, so fixing it comes before the build-time metadata.
  - 2026-09-14: **fixed** by vauban#70 PR 2. Annotation, enum and nested-annotation names go through `Elements#getBinaryName`. A class literal is named the way `DotName#fromDescriptor` reads the class file: the keyword for a primitive, the descriptor for an array. The superclass and interface names, which were still canonical, follow the same rule (`#nestedSupertypes`), and the processor now records member defaults too, compared with the bytecode scan by `#memberDefaults`. Proven by mutation: with canonical names and the `java.lang.Object` fallback put back, the eight comparisons fail again, while the bytecode-side guard and the String member stay green.

## BUG-20260914-13 — Compile-time validation does not know qualifiers declared in the module being compiled

- **Date**: 2026-09-14
- **Status**: FIXED be435eb
- **Module**: `vauban-processor` (`VaubanProcessor#getSupportedAnnotationTypes`, `#resolveDependencyBeans`), through `vauban-core`'s `QualifierResolver#isQualifierAnnotation`
- **Symptom**: a module that declares its own qualifier and two beans of one type does not compile with the Vauban processor: `[Vauban] Ambiguous dependency: field Checkout.card of type … Matching beans: [CardPayment, WirePayment]`, although `@Channel("card")` selects a single bean. The container resolves the same deployment at run time.
- **Minimal reproduction** (`SameModuleQualifierValidationTest#sameModuleQualifier`):
  ```java
  @Qualifier @Retention(RUNTIME) @interface Channel { String value(); }
  interface Payment {}
  @Channel("card") @Dependent class CardPayment implements Payment {}
  @Channel("wire") @Dependent class WirePayment implements Payment {}
  @Dependent public class Checkout { @Inject @Channel("card") Payment card; }
  // javac -proc:full with VaubanProcessor → error: Ambiguous dependency: field Checkout.card
  ```
- **Suspected cause**: the processor indexes the types carrying its supported annotations (the bean-defining ones and the extension triggers) and the injection points' required types; a qualifier annotation type is neither. `isQualifierAnnotation` misses it in the index and falls back on `Class.forName` through the thread context class loader, which cannot load a type still being compiled. The qualifier is then ignored on both sides and every bean of the type matches. A qualifier from a dependency jar is probably affected the same way, since the context class loader of javac does not see the compile classpath.
- **Investigations**:
  - 2026-09-14: found while compiling the vauban#70 benchmarks, which work around it with `-Avauban.validation=false` (the container validates again at boot); the test is disabled with this id.
  - 2026-09-14: **fixed** by vauban#70 PR 4a. Before discovery runs, the processor adds to its index every annotation type the indexed classes name, and then the types those name in turn, resolving each one through `Elements` — which sees the module being compiled and the compile classpath, where a class loader sees neither. Discovery therefore reads `@Qualifier`, `@Stereotype` and `@InterceptorBinding` from the index and never falls back on `Class.forName`. Compile-time matching goes through an `AnnotationTypes` built on that index, so a member default counts as a written value and a `@Nonbinding` member takes no part, as at run time. `vauban-bench` compiles with validation on again: its `-Avauban.validation=false` is gone. Proven by mutation: without the indexing step the two-bean fixture is ambiguous again, and with the previous matcher the defaulted injection point is unsatisfied.

## BUG-20260914-14 — The CDI invoker wraps the trailing array of a varargs method in another array

- **Date**: 2026-09-14
- **Status**: FIXED 3e05bdf
- **Module**: `vauban-core` (`VaubanInvoker#unreflect`, `#invoke`)
- **Symptom**: calling a varargs method through a CDI `Invoker` throws `ClassCastException: Cannot cast [Ljava.lang.String; to java.lang.String` at `VaubanInvoker.java:144`. The CDI TCK's `VarargsMethodInvokerTest` fails: 773/774 on 2026-09-14.
- **Minimal reproduction** (`VaubanInvokerTest#varargsMethod`):
  ```java
  public String join(String head, String... tail) { … }
  new VaubanInvoker(Calculator.class.getMethod("join", String.class, String[].class), Calculator.class, false, Set.of())
          .invoke(new Calculator(), new Object[] {"a", new String[] {"b", "c"}});   // ClassCastException
  ```
- **Suspected cause**: a regression from b9b7ab1 (vauban#71). `MethodHandles.Lookup#unreflect` returns a variable-arity handle for a varargs method, and `invokeWithArguments` then collects the trailing arguments into a fresh array, so the `String[]` the caller passes ends up inside a new one. The reflective call it replaced passed the array through.
- **Investigations**:
  - 2026-09-14: found by the CDI TCK run of the vauban#70 safety-net PR. It went unnoticed because the pull-request workflow runs no TCK and the main workflow does not read the TCK reports (`testFailureIgnore=true`).
  - 2026-09-14: fixed. `unreflect` returns the handle with `asFixedArity()`, so `invokeWithArguments` passes the caller's array through, as `Method#invoke` did. `VaubanInvokerTest#varargsMethod` was written first and failed with the TCK's exact `ClassCastException`; it passes with the fix. vauban-core 356 tests green, CDI TCK 774/774 and AtInject green, counted from the surefire reports.

## BUG-20260914-15 — A synthetic bean or observer loses the member values of its qualifiers

- **Date**: 2026-09-14
- **Status**: FIXED b71b31a
- **Module**: `vauban-core` (`VaubanSyntheticBeanBuilder#qualifier(AnnotationInfo)`, `VaubanSyntheticObserverBuilder#qualifier(AnnotationInfo)`, `SyntheticComponentRegistrar#toObserverDescriptor`)
- **Symptom**: a synthetic bean qualified `@Channel("alpha")` answers no lookup at all — neither `@Channel("alpha")` nor any other value — and a synthetic observer qualified the same way receives no event. `AnnotationBuilder` is the only way an extension can write an annotation, so this is every qualified synthetic component whose qualifier has a member.
- **Minimal reproduction** (`SyntheticQualifierMemberTest`):
  ```java
  @Synthesis
  public void synthesise(SyntheticComponents components) {
      components.addBean(String.class).type(String.class)
              .qualifier(AnnotationBuilder.of(Channel.class).member("value", "alpha").build())
              .createWith(AlphaCreator.class);
  }
  // CDI.current().select(String.class, channelAlpha).get() → UnsatisfiedResolutionException
  ```
- **Suspected cause**: `qualifier(AnnotationInfo)` keeps the annotation's name and drops its members: it loads the type and delegates to `qualifier(Class)`, which builds a proxy answering every member with its default — `null` for a member that has none. The observer path loses them a second time, in the registrar: `new QualifierInstance(qName, Map.of())`.
- **Investigations**:
  - 2026-09-14: found while writing the vauban#70 PR 3a tests, which cover the lossless path from an extension-written annotation to resolution.
  - 2026-09-14: **fixed** by vauban#70 PR 3a. Both builders keep the annotation an extension wrote, member values included, as an `AnnotationInstances` instance; the registrar converts an observer's qualifiers through `AnnotationValues#infoOf`, which reads the index data such an instance carries. Proven by mutation: keeping only the qualifier's type on either path fails the matching test again.

## BUG-20260914-16 — `AnnotationBuilder.member(name, Foo.class)` records the class name as a string

- **Date**: 2026-09-14
- **Status**: FIXED b71b31a
- **Module**: `vauban-core` (`VaubanAnnotationBuilder#member(String, Class)`, `#member(String, Class[])`, `#member(String, ClassInfo)`, `#member(String, ClassInfo[])`)
- **Symptom**: a qualifier an extension adds with a `Class` member never matches an injection point that declares the same qualifier in source, so the deployment fails with `Unsatisfied dependency`. Reading the member back through the lang model fails too: `asType()` throws `IllegalStateException: Not a CLASS value, but STRING`, where CDI 4.1 §BCE says `member(String, Class)` writes a class-typed member.
- **Minimal reproduction** (`QualifierMemberResolutionTest$EnhancementAddedQualifier#classMember`):
  ```java
  @Enhancement(types = PaintedKind.class)
  public void paint(ClassConfig config) {
      config.addAnnotation(AnnotationBuilder.of(OfKind.class).member("value", Integer.class).build());
  }
  // @Inject @OfKind(Integer.class) Service service; → DeploymentException: Unsatisfied dependency
  ```
- **Suspected cause**: the four overloads store `BuiltAnnotationMember.ofString(value.getName())`, so the member reads as a `String` where the class file records a class. The `Type` overloads do record a class (`ofClass`), which is why only the `Class`/`ClassInfo` ones are affected.
- **Investigations**:
  - 2026-09-14: found while writing the vauban#70 PR 3a tests. `VaubanParameters#convertMemberValue` carries a workaround for the same shape — it reads a `Class`-typed member back from a string.
  - 2026-09-14: **fixed** by vauban#70 PR 3a. The four overloads record a class member, whose lang-model type names a primitive by its keyword and an array by its descriptor, as the class file does. The workaround is gone with `convertMemberValue`, but a string written for a `Class` member is still read as one, so an annotation built before this fix keeps working. Proven by mutation: recording the class name again fails the end-to-end `@Enhancement` test and both lang-model conversions.

---

## BUG-20260914-17 — An injection failure is swallowed, and the field is left null

- **Date**: 2026-09-14
- **Status**: FIXED (see the investigation of 2026-09-14 below)
- **Module**: `vauban-core` (`BeanInjector#injectSingleField`, `ManagedBean#getInjectionPoints`)
- **Symptom**: when injecting a field throws anything other than `IllegalProductException` or `UnproxyableResolutionException`, the exception is logged and swallowed; the field keeps its default value and the bean is handed out looking fine. The failure surfaces later as a `NullPointerException` in application code, with a stack trace that names neither the field nor the cause. `ManagedBean#getInjectionPoints` does the same with an empty `catch` and returns an empty set, so `Bean#getInjectionPoints()` silently reports that a bean has no injection points at all.
- **Minimal reproduction**: make any field injection throw — for instance run a module under `-Dvauban.annotations.reflection=forbid` before vauban#70 PR 4d, which turns a reflective read into an exception:
  ```
  java.lang.NullPointerException: Cannot invoke "…Payment.id()" because "this.viaField" is null
      at …Checkout.fromField(Checkout.java:55)
  ```
  The real cause — `reading the qualifiers off field …Checkout.viaField needs reflection` — appears only in a log line.
- **Suspected cause**: `catch (Exception e) { LOG.log(ERROR, …); }` in `injectSingleField`, and `catch (Exception e) { // Fallback to empty set }` in `getInjectionPoints`. CDI 4.1 §5.1.2 makes an unsatisfiable injection point a deployment problem, not a null field.
- **Investigations**:
  - 2026-09-14: found while writing the `forbid` integration test of vauban#70 PR 4d, where it hid three genuine failures behind NPEs. That PR only makes `AnnotationReflection.ForbiddenException` pass through both catches — a diagnostic mode that a `catch` can silence is worthless — and leaves the rest of the behaviour alone: turning the swallow into a propagation changes what happens to every failing injection in the container, which wants its own change and its own TCK run.
  - 2026-09-14 (fix): both catches propagate. `injectSingleField` rethrows a `RuntimeException` with its type intact — a caller catching `UnsatisfiedResolutionException` or `IllegalProductException` must still see it — and wraps a checked exception in `CreationException`, naming the field. `getInjectionPoints` throws rather than reporting that a bean has half its injection points, or none: the caller cannot tell a wrong answer from the truth. **The swallow turned out to protect nothing**: 434 unit tests, both surefire run orders, the CDI Lite TCK 774/774 and AtInject are green without it, and no test relied on a failed injection leaving a field null. Covered by `InjectionFailurePropagatesTest`; the `getInjectionPoints` half has no test of its own — nothing in the reactor makes that method fail — and rests on those suites staying green.

---

## BUG-20260914-18 — `Bean#destroy` runs a producer's disposer twice

- **Date**: 2026-09-14
- **Status**: FIXED (vauban#115, branch `pr/ybl/disposer-runs-once`)
- **Module**: `vauban-core` (`ManagedBean#destroy`, the dependent-context release)
- **Symptom**: destroying a produced `@Dependent` instance calls its `@Disposes` method **twice**. `ManagedBean#destroy` invokes the destroyer, then releases the `CreationalContext`, and the instance is destroyed a second time along the way — the `removeIf(dep -> dep.instance() == instance)` guard just above does not cover it, so the instance must also be registered as a dependent of another context.
- **Minimal reproduction** (`DisposerParameterTest#anUnqualifiedParameterIsUnaffected`, which pins the count at 2 on purpose so a fix fails there and gets noticed):
  ```java
  var bean = beanManager.resolve(beanManager.getBeans(Ledger.class));
  var context = beanManager.createCreationalContext(bean);
  var ledger = beanManager.getReference(bean, Ledger.class, context);
  bean.destroy(ledger, context);
  // the disposer ran twice
  ```
- **Suspected cause**: two paths destroy the same instance — the explicit `destroyer.accept(...)` and the dependent released by `cc.release()`. Whichever registration the guard misses is the one to find.
- **Investigations**:
  - 2026-09-14: found while fixing vauban#89. It is older than that change and independent of it: the count is 2 whether the disposer's other parameter is qualified or not, which is why the test pins both cases. It was invisible until #89 made the disposer's body actually run — before, its parameters resolved to nothing and the call failed inside a `catch (Exception) { /* Best effort */ }`.
  - CDI 4.1 §5.5.3: a disposer runs once per destroyed instance. Note `ManagedBean#destroy` suppresses any exception a disposer throws, which the specification does require — that swallow is not this bug.
  - 2026-10-07: the second call came from `DisposerInvoker.callDisposer` itself. It resolved the disposer's declaring
    instance and other parameters in the produced instance's `CreationalContext`, then released that context when
    the call completed — and that context also holds the produced instance (registered by `getReference`), so the
    release ran `Bean#destroy` again, before the `removeIf` guard of `ManagedBean#destroy` was reached.
- **Fix**: the disposer call gets its own `CreationalContext`; the `@Dependent` objects created to receive it are
  destroyed when it completes (CDI 4.1 §6.4.2), and the produced instance's context is released once, by
  `ManagedBean#destroy`. `DisposerParameterTest` now asserts one call in both cases (it pinned two), and that the
  `@Dependent` parameter created for the call is still destroyed after it.

## BUG-20261001-01 — A synthetic bean built in the processor is listed as a managed bean, and the boot fails

- **Date**: 2026-10-01
- **Status**: FIXED (`fix/extension-build-time-signal`)
- **Module**: `vauban-processor` (`VaubanProcessor#writeBeansList`)
- **Surfaced by**: Vidocq/ravel#21 (Sébastien Blanc), once Ravel's extension ran in the compiler.
- **Symptom**: an application compiled with a Build Compatible Extension on the processor path does not start. A synthetic bean the extension adds in `@Synthesis` (Ravel: one typed `java.lang.String`, qualified `@ConfigProperty`) is written to `META-INF/vauban-beans.list` as well as to the synthetic metadata. The container scans every class the list names: `java.lang.String` comes from the bootstrap loader, `getClassLoader()` is `null`, and `VaubanContainerBuilder#build` throws a `NullPointerException`.
- **Minimal reproduction**: `BceCompileTimeTest#syntheticBeanClassStaysOutOfTheBeanList` — a BCE that calls `components.addBean(String.class)`; the bean list held `[RealBean, java.lang.String]`.
- **Cause**: the synthetic descriptors are appended to the discovered beans so that validation sees them, and `writeBeansList` listed every descriptor, synthetic ones included.
- **Fix**: `writeBeansList` skips `BeanKind.SYNTHETIC`; the synthetic metadata already carries those beans to the container.

## BUG-20261001-02 — An extension cannot tell the processor from the container start

- **Date**: 2026-10-01
- **Status**: FIXED (`fix/extension-build-time-signal`)
- **Module**: `vauban-api` (new `ExtensionPhase`), `vauban-processor`, `vauban-maven-plugin`
- **Surfaced by**: Vidocq/ravel#21.
- **Symptom**: Ravel's extension, run in the processor, checked configuration values against the build machine (`Missing required config property 'shop.name'`) although the value comes from the deployment. CDI Lite gives an extension no way to know where it runs.
- **Fix**: `ExtensionPhase.isBuildTime()`, a `ScopedValue` the processor and the Maven plugin bind around every extension phase they run (`ExtensionPhase.atBuildTime`). Pinned by `ExtensionPhaseTest`, `BceCompileTimeTest#extensionKnowsItRunsAtBuildTime` and `VaubanGeneratorTest#extensionsRunByThePluginKnowTheyRunAtBuildTime`.

## BUG-20261001-03 — An `@Inject` constructor next to a no-arg one falls back to reflection (grimm#15)

- **Date**: 2026-10-01
- **Status**: FIXED (`fix/inject-constructor-over-no-arg`)
- **Module**: `vauban-indexer` (`ComponentCollector#instantiableCtorParams`)
- **Symptom**: once Grimm shipped named modules, `GrimmModelCache` (an `@Inject` constructor taking `GrimmConfig, ScannedTypes` next to a public no-arg one) could not be built: `Cannot reflectively access io.vidocq.grimm.cdi.GrimmModelCache on the module path … does not open io.vidocq.grimm.cdi to module io.vidocq.vauban.core`.
- **Minimal reproduction**: `ComponentProviderCompileTimeTest#injectConstructorWinsOverNoArgConstructor`.
- **Cause**: the collector chose the no-arg constructor whenever one existed. The container picks the `@Inject` one (CDI 4.1 §3.1.1) and asks the provider for `create(name, args)`. The generated provider only had `create(name)`, so the default `create(name, args)` answered `null` and the container fell back to reflection. On an automatic module, which is open, this went unnoticed.
- **Fix**: the `@Inject` constructor comes first. A no-arg constructor is chosen only when there is no `@Inject` one.

## BUG-20261001-04 — A bean class that cannot be loaded is skipped without a word (grimm#15)

- **Date**: 2026-10-01
- **Status**: FIXED (`fix/inject-constructor-over-no-arg`)
- **Module**: `vauban-core` (`ContainerScanner#tryAddBeanClass`)
- **Symptom**: every Grimm bean vanished from a modular application (its package split into the application module), and nothing in the log said so.
- **Fix**: a class listed in a `META-INF/vauban-beans.list` that cannot be loaded is still skipped, but with a warning that names it and the likely causes: a split package, or a missing module. `ScanClasspathTest#shouldReportUnloadableListedClass`. Not a single one shows in the CDI TCK run.

## BUG-20261004-01 — `InvocationContext.getMethod()` returns the generated `$$super$` bridge for a method the bean class does not declare

- **Date**: 2026-10-04
- **Status**: FIXED 2026-10-04 (`2d85326`; resolution moved to `BusinessMethods` in `1165eb8`, private declarations skipped in `7202b86`, branch `pr/ybl/inherited-interceptor-method`)
- **Module**: `vauban-core` (`VaubanInvocationContext#getMethod`)
- **Surfaced by**: a review of the MicroProfile 7.2 upgrade (Humboldt names spans and sets `code.function.name` from `getMethod()`).
- **Symptom**: an interceptor bound to a bean sees `ctx.getMethod()` as `<Bean>$$Intercepted.$$super$<name>` instead of the bean's method whenever the method is inherited: declared on the direct superclass, on any class above it, or as an interface default method. Every interceptor gets the wrong `Method` (wrong declaring class, `$$super$` name, no annotations), so `getInterceptorBindings()` also loses the method-level bindings of the inherited method. Both front-ends are affected: the run-time Class-File subclass and the processor's source subclass. Only a method the bean class declares itself was resolved.
- **Minimal reproduction**: `InheritedInterceptedMethodTest` (vauban-core, run-time subclass) and `InheritedMethodModulePathTest` (vauban-module-it, processor subclass on the module path): `C extends B extends A`, interceptor recording `ctx.getMethod()`. Before the fix, the vauban-core run had 8 tests, the six kept plus two temporary probes of an inherited protected and package-private method (since removed, see BUG-20261004-03): 6/8 failed, the four inherited cases with `getMethod() leaked the generated bridge` and the two probes with no interception at all. The vauban-module-it run failed 6/8 with `getMethod() leaked the generated bridge`.
- **Cause**: the generated subclass hands the context its `$$super$<name>` bridge, and `getMethod()` looked the original up with `getSuperclass().getDeclaredMethod(…)` on the bean class only, falling back to the bridge on `NoSuchMethodException`. `InterceptorManager` already walked the whole superclass chain to collect the method's bindings, so the chain was right and only the method reported to interceptors was wrong.
- **Fix**: `getMethod()` resolves the bridge up the whole superclass chain (most derived declaration first, any access), then to the interface default method, once per bridge: the result is kept per generated subclass in a `ClassValue`. Resolving at run time rather than capturing the declaring class in the generated code keeps every already-compiled `$$Intercepted` correct, and does not name a class that may be inaccessible from the bean's package or module. The walk now lives in `BusinessMethods`, which `InterceptorManager` shares, so the method reported and the bindings applied cannot diverge (BUG-20261004-04).

## BUG-20261004-02 — A client proxy does not forward an interface default method

- **Date**: 2026-10-04
- **Status**: FIXED 2026-10-04 (`1165eb8`, branch `pr/ybl/inherited-interceptor-method`)
- **Module**: `vauban-core` (`RuntimeClientProxyGenerator#shapeOf`), `vauban-processor` (`ClientProxyShapeFromElements#from`, `#fromColocated`)
- **Symptom**: calling an interface default method that a normal-scoped bean does not override, through its client proxy, runs the default body on the proxy instance: the contextual instance is bypassed and the interceptors bound to the bean do not fire. The bean's own methods are forwarded and intercepted as expected.
- **Minimal reproduction**: an `@ApplicationScoped @Audited` bean `implements Greeting` (default `greet`), no override; `select(…).greet("y")` returns `"hi y"` and the interceptor records nothing. Seen on both proxies while working on BUG-20261004-01 (run-time proxy in vauban-core, processor proxy in vauban-module-it); the regression tests of BUG-20261004-01 use a `@Dependent` bean to reach the generated subclass directly.
- **Cause**: all three shapes walk the superclass chain only; methods inherited from interfaces are never listed.
- **Fix**: the three shapes also forward the interface default methods no class of the bean overrides: `Class#getMethods()` filtered on `isDefault()` in `shapeOf`, `Elements#getAllMembers` filtered on `DEFAULT` in both processor shapes (as `InterfaceProxySourceRenderer` already did for an interface-typed proxy). The producer proxies built from the same shapes forward them too. Tests: `DefaultMethodInterceptionTest#defaultMethodThroughTheClientProxy` (vauban-core), `InheritedBindingModulePathTest#defaultMethodThroughTheClientProxy` (vauban-module-it), `ClientProxyInheritanceCrossCheckTest#defaultMethodsAreForwarded` (vauban-processor: all three shapes, an overridden default and a default re-declared by a sub-interface). The indexer-based `ClientProxyGenerator` fallback (beans with no non-private constructor, unproxyable per CDI 4.1 §3.10) still overrides declared methods only.
- **Regression of this fix, found by the round-4 re-review, fixed in `d6023f5` (never on `main`)**: a normal-scoped bean inheriting a default whose member signature names a type its package cannot — `label(Hidden)` from `implements Labeled<Hidden>` in a superclass of package `q`, `Hidden` package-private there, no shadow — got a rendered `_ClientProxy` that does not compile (`Hidden is not public in q`), where `main`, which forwarded no default, compiled it. The rendered proxy now leaves such a method out (`ClientProxyShapeFromElements`, `InterceptedShapeFromElements#memberSignatureNameableFrom`), which is `main`'s outcome — the default runs on the proxy instance — and the processor reports it as a warning on the bean; the bytecode shapes keep forwarding it by descriptor (pinned as a documented divergence). A rendered subclass with the same member is BUG-20261004-09. Tests: `ClientProxyInheritanceCrossCheckTest#nonShadowedDefaultWithAnInaccessibleMemberType` (RED `fv2r4-r5red-processor.log`), `InaccessibleMemberTypeModulePathTest` (RED `fv2r4-r5red-moduleit.log`: vauban-module-it did not compile).

## BUG-20261004-03 — The run-time intercepted subclass skips an inherited protected or package-private method

- **Date**: 2026-10-04
- **Status**: FIXED 2026-10-04 (`1165eb8`; cross-package behaviour test `9fe86da`, branch `pr/ybl/inherited-interceptor-method`)
- **Module**: `vauban-core` (`InterceptorSubclassGenerator#fromClass`, also used by the Maven plugin's `VaubanGenerator`)
- **Symptom**: a protected or package-private business method that the bean inherits from a superclass is not intercepted when the `$$Intercepted` subclass comes from the run-time generator (class-path fallback, Maven plugin). The processor's subclass intercepts it (`InterceptedShapeFromElements` lists every member through `Elements#getAllMembers`), so the two front-ends do not produce the same override set, although both are documented to.
- **Minimal reproduction**: a `@Dependent @Traced` bean `extends Parent extends Grandparent`, where `Grandparent` declares `protected String inheritedProtected()` and `String inheritedPackagePrivate()`; calling either from the same package reaches no interceptor, while the public inherited method is intercepted. Seen while working on BUG-20261004-01.
- **Cause**: `fromClass` takes the bean's declared methods, then only the inherited *public* ones (`Class#getMethods()`).
- **Fix**: `fromClass` then walks the superclasses for the inherited protected and package-private methods, with the rules `Elements#getAllMembers` applies (JLS 8.4.8): the most derived declaration of a signature decides, so a `final` or `private` one hides the rest, and a package-private method counts only when every class from the bean up to its declaring class shares that class's runtime package — no subclass elsewhere can override it. They are appended after the existing passes, so the shape of a bean without such methods is unchanged (`GoldenBytecodeTest`). Tests: `InheritedInterceptedMethodTest#inheritedProtected` and `#inheritedPackagePrivate` (vauban-core, through the run-time client proxy and subclass), `#inheritedProtectedFromAnotherPackage` (added by the review: a protected method of a superclass in another package, through the run-time subclass; RED with the new pass disabled, `fv2r1-red-foreign-protected.log`), `InterceptedShapeFromElementsTest#inheritedNonPublicMethodsAgree` (vauban-processor: both front-ends select the same set, including a protected and a package-private method of a superclass in another package and a `final` override).

## BUG-20261004-04 — An interceptor bound to an interface default method never runs, while `getInterceptorBindings()` lists its binding

- **Date**: 2026-10-04
- **Status**: FIXED 2026-10-04 (`1165eb8`; review follow-ups `7202b86` (private declarations), `2f56400` (business methods only), branch `pr/ybl/inherited-interceptor-method`)
- **Module**: `vauban-core` (`InterceptorManager#matchingForMethod`, `#resolveInterceptorDescriptorsForMethod`, `InterceptorBeanWrapper#wrapInterceptedBeans`)
- **Surfaced by**: the review of the BUG-20261004-01 fix, which made `getMethod()` — and with it `getInterceptorBindings()` — answer the interface default method.
- **Symptom**: an interceptor binding declared on an interface default method the bean does not override is listed by `getInterceptorBindings()`, but the interceptor bound to it does not run: the chain ignores it. A bean whose only binding sits on such a method is not intercepted at all.
- **Minimal reproduction**: `interface Greeter { @Marked default String markedGreet(String w) {…} }`, a `@Traced @Dependent` bean implementing it, a `@Marked` interceptor: `markedGreet("z")` runs the `@Traced` interceptor, which sees `@Marked` in its bindings, but not the `@Marked` one. `DefaultMethodInterceptionTest#defaultMethodBindingApplies` and `#beanBoundOnlyByADefaultMethod` (vauban-core) failed before the fix.
- **Decision**: the binding applies. CDI 4.1 does not mention default methods; it gives the bindings of inherited methods for superclasses (§4.2: inherited unless a class below overrides the method) and never inherits type-level metadata from interfaces (§4). A default method the bean does not override is a member of the bean class (JLS 8.4.8) exactly as a non-overridden superclass method is, so its own bindings apply by the same rule, and stop applying once a class of the bean overrides it. Weld does the same: its annotated type lists the interface default methods next to the class hierarchy's methods (`BackedAnnotatedType`), and the interception model reads each business method's own bindings (`InterceptionModelInitializer`). The class-level bindings of an interface still never apply.
- **Cause**: `InterceptorManager` resolved the method's declaration on the superclass chain only, while `getMethod()` also fell back to the interface default method; the bean wrapper looked for method-level bindings on the superclass chain only, and resolved each method from its declaring class instead of the bean class.
- **Fix**: one helper, `BusinessMethods#declarationOf`, gives the declaration a call runs on an instance of the bean class — the most derived one up the superclass chain, else the inherited default method — and the method-level bindings are read off it. `VaubanInvocationContext#getMethod()` (hence `getInterceptorBindings()`), the chain and the wrapper's "is this bean intercepted" check all use it; the wrapper now also checks the inherited default methods. Tests: `DefaultMethodInterceptionTest` (vauban-core, five cases including an overridden default method that drops its binding), `InheritedInterceptedMethodTest#overriddenMethodDropsTheSuperclassBinding` and `#overloadOnTheGrandparent`/`#overloadOnTheParent`, `InheritedBindingModulePathTest` (vauban-module-it).
- **Review follow-up, same day**: `declarationOf` took the most derived declaration "whatever its access", so a private superclass method with the signature of an inherited default method stood for it: `getMethod()` answered the private method and the bindings were read from it, while the bean's member is the default method (JLS 8.4.8). It now skips private and static declarations. Test: `DefaultMethodInterceptionTest#privateSuperclassMethodDoesNotShadowTheDefault` (RED `fv2r1-red-private-shadow-2.log`: `getMethod()` was `private PrivateHidden.hidden`). The call itself failed in that shape until BUG-20261004-08 was fixed. Since `54e5c9c` it skips every declaration that is not a business method of the bean (`isBusinessMethodOf`): a package-private one of another package too.

## BUG-20261004-05 — On the module path, a bean bound only through an inherited method cannot be deployed

- **Date**: 2026-10-04
- **Status**: FIXED 2026-10-04 (`1165eb8`; review follow-up `2f56400`, branch `pr/ybl/inherited-interceptor-method`)
- **Module**: `vauban-processor` (`VaubanProcessor#isInterceptedTarget`)
- **Surfaced by**: the tests of BUG-20261004-04.
- **Symptom**: a bean with no class-level binding whose only binding sits on a method it inherits — a superclass method, or (since BUG-20261004-04) an interface default method — gets no `$$Intercepted` subclass from the processor. On the strict module path the container then has to define the subclass itself and cannot (no `opens`): `DeploymentException: Could not define interceptor subclass`. On the class path the run-time fallback hid it.
- **Minimal reproduction**: `@Dependent class InheritedBindingService extends BoundBase`, `BoundBase` declaring `@Audited public String inheritedBound()`; booting a container with it in vauban-module-it throws the exception above. `InheritedBindingModulePathTest#inheritedMethodBindingApplies` and `#defaultMethodBindingApplies` failed before the fix.
- **Cause**: `isInterceptedTarget` looked for method-level bindings on the methods the bean class declares only.
- **Fix**: it also looks at the methods the bean inherits, as `Elements#getAllMembers` lists them: a method a class of the bean overrides is left out, so its binding does not count (CDI 4.1 §4.2). A supertype the compiler cannot complete leaves the bean to the run-time fallback, with a note, as before. A bean bound through a *generic* superclass method also needed BUG-20261004-06.
- **Review follow-up, same day — the reverse mismatch**: the container's "is this bean intercepted" check still counted a binding on a method that is not a business method of the bean — private, static, or package-private in a superclass of another package, which the bean does not inherit — while the processor (`getAllMembers`, `hasMethodLevelBinding`) does not. On the module path such a bean was wrapped without a pre-generated subclass: `DeploymentException: Could not define interceptor subclass` again. `BusinessMethods#isBusinessMethodOf` (non-static, non-private, inherited per JLS 8.4.8) now gates the method-level bindings in `InterceptorManager`, so the check, the chain and `InterceptorSubclassGenerator` count the same methods as the processor. Tests: `NonBusinessMethodBindingTest` (vauban-core, RED `fv2r1-red-business.log`: the foreign package-private case was wrapped) and `NonBusinessMethodBindingModulePathTest` (vauban-module-it, RED `fv2r1-red-business-moduleit.log`: the `DeploymentException`); the private and static cases already passed once BUG-20261004-04's follow-up made `declarationOf` skip private and static declarations, and are pinned.

## BUG-20261004-06 — The processor's generated sources do not compile when the bean binds a type variable of an inherited method

- **Date**: 2026-10-04
- **Status**: FIXED 2026-10-04 (`1165eb8`; review follow-ups `982bfa1` (one override per member signature), `ae477c2` (generic bean extended raw), branch `pr/ybl/inherited-interceptor-method`)
- **Module**: `vauban-processor` (`InterceptedShapeFromElements`, `InterceptedSourceRenderer`, `ClientProxyShapeFromElements#from`), `vauban-core` (`MethodShape`)
- **Surfaced by**: a probe written while fixing BUG-20261004-02 and -05, which would otherwise have widened it (`fv2-probe-generic.log`, probe files deleted).
- **Symptom**: a bean that inherits a method whose parameter or return type is a type variable its supertype binds — `echo(T)` of `class Bean extends Base<String>`, or a default `label(T)` of `implements Labeled<String>` — breaks the build: the `$$Intercepted` source the processor renders for an intercepted bean, and the `_ClientProxy` source it renders for a normal-scoped bean, declare `echo(java.lang.Object)`, which javac rejects (`name clash: echo(Object) … and echo(String) … have the same erasure, yet neither overrides the other`, then `incompatible types`). Pre-existing for generic superclass methods on both sources and for generic default methods on the `$$Intercepted` source; BUG-20261004-02 would have added the default methods of the client proxy, and BUG-20261004-05 every bean bound through a generic base-class method (a repository bound through its base class), which until then fell back to the run-time generator and worked on the class path.
- **Minimal reproduction**: `GenericInheritanceModulePathTest` with `GenericScopedService` (`@ApplicationScoped @Audited extends GenericBase<String> implements Labeled<String>`) and `GenericBoundService` (`@Dependent extends GenericBase<String>`, bound only through `@Audited store(T)`): vauban-module-it did not compile (`fv2-red-generic-moduleit.log`).
- **Cause**: the source front-ends render each method with the erasure of its declaration, which is the right descriptor for a class file (the bytecode emitters override by descriptor) but not an override Java source accepts when the bean sees the type variable as a concrete type.
- **Fix**: `MethodShape` also carries the method's signature as a member of the bean (`Types#asMemberOf`, erased), identical to the declaration unless a type variable is bound. `InterceptedSourceRenderer` declares the override with it (javac adds the bridge for the descriptor) and keeps the `$$super$` bridge on the erased declaration — the run-time front-end's descriptor, which `getMethod()` resolves to the declaration — casting each argument to its member type for `super.<name>(…)`. `ClientProxyShapeFromElements#from`, rendered as source only, uses the member signature; `#fromColocated` and the run-time shapes, emitted as bytecode, keep the descriptor. Tests: `GenericInheritanceModulePathTest` (four cases: a generic superclass method and a generic default method through the build-time client proxy, a generic return type, a bean bound only through a generic superclass method; the interceptor sees the erased declaration), `ClientProxyInheritanceCrossCheckTest#genericMethodsUseTheMemberSignatureInSource`.
- **Reopened by the review, same day**: the source front-ends still keyed their walks on the erased *declaration*, then rendered the *member* signature, so two declarations that are one member of the bean were rendered twice: `method echo(String) is already defined` in the client proxy of `class OverridingBean extends GenericBase<String> { @Override echo(String) }` (`echo(T)` keyed as `echo(Object)`), and `method label(String) is already defined` in the `$$Intercepted` of `class X extends PlainBase implements Labeled<String>` whose `PlainBase.label(String)` implements `label(T)` (`getAllMembers` lists both). The second shape was already a name clash before. Reproduced by `GenericInheritanceModulePathTest#overriddenGenericMethodThroughTheClientProxy` and `#interfaceMethodImplementedBySuperclass` (vauban-module-it did not compile, `fv2r1-red-dedupe-moduleit.log`), `ClientProxyInheritanceCrossCheckTest#oneOverridePerMemberSignature` and `InterceptedShapeFromElementsTest#oneMethodPerMemberSignature`.
- **Fix, second part**: every APT walk — `InterceptedShapeFromElements#from`, `ClientProxyShapeFromElements#from`, `#fromColocated` and the default methods — is keyed on the erased *member* signature (`InterceptedShapeFromElements#memberSignatureKey`), the most derived declaration first, class methods before interface default methods, a non-interceptable or non-proxyable declaration still hiding the ones it overrides. The cross-check helpers now fail on a signature listed twice.
- **Regression of the first fix on generic beans, found by the branch review, fixed** (`ae477c2`, never on `main`): the member signature was computed against the parameterised bean, while the rendered subclass and proxy extend a generic bean *raw*, where every inherited member is erased (JLS 4.8). `@Dependent @Audited class GenericNoShadow<X> implements Labeled<String>` got an override `label(String)` that "does not override or implement a method from a supertype"; `main` rendered `label(Object)` and compiled. `InterceptedShapeFromElements#memberType` now takes the member as of the raw type when the bean is generic, which restores `main`'s output for every generic bean and keeps the bound member for a non-generic one. Tests: `InterceptedShapeFromElementsTest#genericBeanIsExtendedRaw` (member signatures), `ClientProxyInheritanceCrossCheckTest#genericBeanIsExtendedRaw`, `GenericInheritanceModulePathTest#genericBeanIsExtendedRaw` and `ShadowedDefaultModulePathTest#genericBeanWithAShadowedDefault` (vauban-module-it did not compile: `fv2r4-red-moduleit.log`).

## BUG-20261004-07 — An unproxyable intercepted bean is not reported as a deployment problem, nor told why it is intercepted

- **Date**: 2026-10-04
- **Status**: FIXED 2026-10-04 (`6c1952b`, branch `pr/ybl/inherited-interceptor-method`)
- **Module**: `vauban-core` (`InterceptorBeanWrapper#wrapInterceptedBeans`)
- **Surfaced by**: the review of the FV2 fixes, which make more beans intercepted (bound through an inherited or default method).
- **Symptom**: a final intercepted bean class failed with a `DefinitionException`, while CDI 4.1 treats an unproxyable intercepted bean as a deployment problem (§3.10, §8.3) and the final-method case already threw a `DeploymentException`. None of the three proxyability errors (final class, final method, private no-arg constructor) said why the bean is intercepted, which is no help when the binding sits on a method the bean inherits: its own source carries none.
- **Minimal reproduction**: `UnproxyableInterceptedBeanTest` (vauban-core): a final `@Dependent` bean bound only through `@Marked` on an inherited default method threw `DefinitionException`; a bean with a final method bound only through `@Marked` on a superclass method threw `Intercepted bean … has final method pinned`, naming neither (RED `fv2r1-red-unproxyable.log`).
- **Fix**: the "is this bean intercepted" check keeps the reason it found — the class-level bindings, `InterceptorManager#describeMethodBindings` (the bindings of the declaration and whether it is declared, inherited or an interface default method), the constructor binding, or the target `@AroundInvoke` method — and every proxyability error is a `DeploymentException` that ends with "An intercepted bean must be proxyable (CDI 4.1 §3.10); <bean> is intercepted because of …".

## BUG-20261004-08 — A default method shadowed by a private superclass method cannot be called through the generated classes

- **Date**: 2026-10-04
- **Status**: FIXED 2026-10-04 (`1de9e00`; re-review fixes `016858e`, `3caac57`, `1e3a925`; branch-review fixes `54e5c9c`, `abe4c2c`, `d3fa351`, branch `pr/ybl/inherited-interceptor-method`)
- **Module**: `vauban-core` (`ShadowedDefaults`, `InterceptorSubclassGenerator`, `InterceptedEmitter`, `RuntimeClientProxyGenerator`, `ClientProxyEmitter`, `MethodShape`, `ClientProxyShape`), `vauban-processor` (`InterceptedShapeFromElements`, `InterceptedSourceRenderer`, `ClientProxyShapeFromElements`, `ClientProxySourceRenderer`)
- **Surfaced by**: the review follow-up on `BusinessMethods#declarationOf` (BUG-20261004-04).
- **Symptom**: `class C extends Base implements I`, where `I` has a default `m()` and `Base` a *private* `m()` with the same signature. `C`'s member is `I.m()` (JLS 8.4.8), but once `C` is intercepted a call through `I` ended in `IllegalAccessError: C$$Intercepted tried to access private method Base.m()`: the interceptors ran, then the `$$super$m` bridge's `super.m()` failed. Normal-scoped, the client proxy did not forward `m()` (the private method held its key), and a call on the proxy ended in `AbstractMethodError`.
- **Minimal reproduction**: `DefaultMethodInterceptionTest#privateSuperclassMethodDoesNotShadowTheDefault` and `#shadowedDefaultThroughTheClientProxy` (vauban-core, run-time front-ends), `ShadowedDefaultModulePathTest` (vauban-module-it, processor front-ends): RED in `fv2r2-red-shadowed.log`.
- **Cause**: the JVM resolves `invokespecial C.m` — the bridge's `super.m()` — and `invokevirtual C.m` through `C`'s superclasses before its superinterfaces (JVMS 5.4.3.3), finds the private `Base.m()` and refuses it: a class-typed call from outside `Base`'s nest fails the same way in plain Java (observed). HotSpot 25 also gives `AbstractMethodError` on an interface call to any class that inherits `I` only through its superclass under such a shadow (`class D extends C`; observed with plain javac classes, although JVMS 5.4.6 selects the default there): a generated subclass or proxy that does not override `m()` cannot run it either.
- **Fix**: generated code reaches the default through an interface, never through the bean class. `ShadowedDefaults` (run time) and `InterceptedShapeFromElements#isShadowedDefault`/`#accessibleDefaultOwner` (processor) detect a default method whose name and descriptor a class of the bean's chain declares, and pick its owner: the declaring interface when the generated class can name it, else an interface among the bean's supertypes that inherits it and can be named. The `$$Intercepted` subclass lists the owner among its direct superinterfaces — `invokespecial` on an interface method must name one (JVMS 4.9.2) — and its bridge and pre-init guard invoke the default explicitly: an `invokespecial` on the interface method in the bytecode emitter; in the rendered source a `MethodHandles.lookup().findSpecial(I.class, …)` handle, because `I.super.m()` is a compile error there: JLS 15.12.1 forbids it when the class's superclass (the bean) is itself a subtype of `I` ("redundant interface I is extended by C"). The client proxies forward through the owner (`invokeinterface`; `((I) delegate).m(…)` in source). `MethodShape#defaultOwner` and `ProxyMethodShape#interfaceOwner` carry it.
- **Fallback, tested**: when no interface carrying the default can be named from the bean's package — the bean inherits it only from a package-private interface of another package, through a superclass — no class may list it (JVMS 5.4.4 for the run-time subclass, JLS 6.6.1 for the rendered one), so the method is left out of the subclass and the proxy, and is not intercepted. A call then behaves as on a plain instance of the bean, which on HotSpot 25 is the `AbstractMethodError` above. Tests: `InaccessibleDefaultOwnerTest` (vauban-core: the bean's other methods are intercepted, `carried()` behaves as on a plain instance), `InterceptedShapeFromElementsTest#shadowedDefaultMethods` and `ClientProxyInheritanceCrossCheckTest#shadowedDefaultMethods` (vauban-processor: both front-ends agree on the owner, and on leaving the inaccessible case out).
- **Regression found by the re-review, fixed**: `isShadowedDefault` took *any* class declaration with the default's descriptor for a shadow, a public inherited one included, against its own Javadoc. In `@ApplicationScoped class TaggedScoped extends PlainTagBase implements Tagged`, where `PlainTagBase.tag(String)` (public) implements the default `Tagged.tag(String)`, the processor's client proxy forwarded `tag(String)` through the bean class *and* through `Tagged`: `method tag(String) is already defined`. Only a declaration that is not a member of the bean — private, or package-private in another package (JLS 8.4.8) — shadows now, at run time (`ShadowedDefaults#isShadowed`) and in the processor (`InterceptedShapeFromElements#isShadowedDefault`); the processor's proxy shapes (`from` and `fromColocated`) never declare a signature twice, whatever path finds it, and the cross-check helper fails on a signature listed twice whatever its `#mh`/`@owner` suffix. Tests: `ClientProxyInheritanceCrossCheckTest#inheritedClassMethodIsNoShadow`, `InterceptedShapeFromElementsTest#inheritedClassMethodIsNoShadow`, `ShadowedDefaultModulePathTest#inheritedClassMethodIsNoShadow` (RED `fv2r3-red-noshadow-*.log`: the duplicate, and vauban-module-it not compiling).
- **Generic interface, found by the re-review, fixed**: a shadowed default of a generic interface — `class GenericShadowed extends PrivBase implements Labeled<String>`, `PrivBase` declaring a private `label(Object)` — made the processor render a raw `implements Labeled` next to the bean's `Labeled<String>`: "Labeled cannot be inherited with different arguments: <> and <java.lang.String>". `MethodShape#defaultOwnerSource` now carries the interface as the bean parameterises it, taken from the bean's supertypes (erased for a generic bean, which its subclass extends raw), and `InterceptedSourceRenderer`, the only renderer that lists the interface, writes it so (`implements Labeled<java.lang.String>`); the `findSpecial` class literal stays erased. The proxies only cast to the interface, which needs no type arguments. Tests: `InterceptedSourceRendererTest#parameterisedOwner`, `ShadowedDefaultModulePathTest#genericInterface` (RED `fv2r3-red-generic-owner-*.log`).
- **Producer proxies (raised as a concern, fixed)**: a producer's proxy is rendered in the *producer's* package (#42), where a package-private interface of the produced type's package cannot be named, yet the owner was chosen as seen from the produced type's package: a build break. The processor now chooses the owner as seen from the package the proxy is rendered in (`ClientProxyShapeFromElements#from(bean, proxyPackage, …)`, `InterceptedShapeFromElements#accessibleDefaultOwner(…, generatedIn, …)`; an unknown package asks for an interface public and exported to all), like the run-time producer path, which takes public exported interfaces only. When none can be named there, the method is not forwarded through an interface — not forwarded at all, as when no interface qualifies — instead of breaking the build. Test: `ClientProxyInheritanceCrossCheckTest#shadowedDefaultFromAnotherPackage` (RED `fv2r3-red-producer-package.log`: the shape forwarded through the package-private interface).
- **Shadow by a package-private method of another package, run-time proxy (regression of `1de9e00`, found by the branch review, fixed in `54e5c9c`, never on `main`)**: for `@ApplicationScoped class C extends q.PkgBase implements Tagged`, where `PkgBase.tag(String)` is package-private in another package and `Tagged` has a default `tag(String)`, `RuntimeClientProxyGenerator#shapeOf` forwarded `PkgBase.tag` through a MethodHandle — a method the bean does not inherit (JLS 8.4.8), and a lookup the JVM refuses — then forwarded the default through `Tagged` as well: `ClassFormatError: Duplicate method name "tag"` when the proxy was defined. `BusinessMethods#declarationOf` also stood that declaration for the default, so `getMethod()` and the bindings named `PkgBase.tag`. The processor's shapes already left the method out; the run-time walk now takes the same decision through the bean-member rule — first made public as `BusinessMethods#isBusinessMethodOf`, it now lives in `BeanMembers#isBusinessMethodOf` (`io.vidocq.vauban.core.codegen`, exported to the processor only, `ff80960`), and `BusinessMethods`, package-private again, delegates to it — and `declarationOf` skips it too. Tests: `CrossPackageShadowedDefaultTest` (vauban-core, RED `fv2r4-red-core.log`: the `ClassFormatError`, and `getMethod()` naming `ForeignPackagePrivateTagBase.tag`), `ClientProxyInheritanceCrossCheckTest#crossPackagePackagePrivateIsNoMember` (RED `fv2r4-red-processor.log`: the run-time shape listing `tag(String)` twice), `InterceptedShapeFromElementsTest#crossPackagePackagePrivateIsNoMember`, `ShadowedDefaultModulePathTest#crossPackagePackagePrivateShadow`.
- **Type argument the bean's package cannot name (found by the branch review, fixed in `abe4c2c`)**: `class HiddenArgBean extends q.Base`, where `Base implements Labeled<Hidden>` with `Hidden` package-private in `q`, under a private `label(Object)`: the rendered subclass wrote `implements q.Labeled<q.Hidden>` and the override `label(q.Hidden)` — the member signature — neither of which Java source in the bean's package may write: a build break. As when no interface qualifies, the method is now left out of the rendered subclass (not intercepted) and of the rendered client proxy (not forwarded) when the member signature, or the interface as the bean parameterises it, names a type the generated class's package cannot (`InterceptedShapeFromElements#memberSignatureNameableFrom`, `#nameableFrom(TypeMirror, …)`). The bytecode generators keep the method: a class file overrides by descriptor and lists the raw interface, which the cross-checks pin as the one documented divergence. Tests: `InterceptedShapeFromElementsTest#shadowedDefaultWithAnInaccessibleTypeArgument`, `ClientProxyInheritanceCrossCheckTest#shadowedDefaultWithAnInaccessibleTypeArgument`, `ShadowedDefaultModulePathTest#inaccessibleTypeArgument` (RED `fv2r4-red-moduleit.log`: `HiddenArgument is not public in io.vidocq.vauban.moduleit`).
- **Known limitation, not fixed — a non-member declaration with the default's name and parameters but another return type**: `class C extends Base implements Tagged`, where `Base` declares a private `Integer tag(String)` (or a package-private one in another package) and `Tagged` a default `String tag(String)`. Both proxy walks key on name and parameter types and the non-member declaration takes the key first (`RuntimeClientProxyGenerator#shapeOf`, `ClientProxyShapeFromElements#from`/`#fromColocated`), while `ShadowedDefaults#isShadowed` and `InterceptedShapeFromElements#isShadowedDefault` compare the whole descriptor, return type included, so the default is neither shadowed nor forwarded: a call on the client proxy runs the default on the proxy instance and is not intercepted, on both paths alike (the generated subclass of a dependent bean does intercept it). Found by the round-4 re-review (probe `n1`: `PrivRetScoped`, `CrossRetScoped`). Not a regression: no client proxy forwarded a default before BUG-20261004-02. Fix direction: record the key only after the member check — a declaration that is not a member of the bean then no longer holds it, and the default is forwarded through the bean class like any other inherited default. That is enough: `ShadowedDefaults#isShadowed` and `InterceptedShapeFromElements#isShadowedDefault` must keep comparing the return type, because the JVM resolves a call by its full descriptor, so a private `Integer tag(String)` is never what `invokevirtual C.tag:(Ljava/lang/String;)Ljava/lang/String;` reaches, and is no shadow.
- **Readability of the interface's module (found by the branch review, fixed in `d3fa351`)**: the processor checked that the interface's module *exports* its package, not that the bean's module *reads* that module (JLS 7.7.1). A bean inheriting the interface through a superclass of another module, with no `requires` of the interface's module and no `requires transitive` along the way, cannot name it: "module m.bean does not read module m.iface". `nameableFrom` now walks the bean's module's `requires`, following the transitive ones (`java.base` and the unnamed module always read). The run time already asked `Module#canRead`. Test: `DefaultOwnerReadabilityTest` (three Java modules compiled in-process; RED `fv2r4-red-readability.log` with the check disabled).

## BUG-20261004-09 — The processor's generated sources do not compile when an inherited member's signature names a type the bean's package cannot

- **Date**: 2026-10-04
- **Status**: FIXED 2026-10-07 (vauban#120, branch `pr/ybl/unnameable-inherited-member-types`): `n3a`, `n3b`, `n3d`, `n11b`, `n11c`, see **Fix** below. Pre-existing on `main` 7384ac1; found by the round-4 re-review of `pr/ybl/inherited-interceptor-method`, probes `n3a`, `n3b`, `n3d`. The shapes that were a regression of that branch (`n11a`, `n11d`) were FIXED 2026-10-04 (`ee46fc4`), with `n11e`. What stays as it was is listed under **Left as is**.
- **Module**: `vauban-processor` (`InterceptedShapeFromElements`, `InterceptedSourceRenderer`, `ClientProxyShapeFromElements`, `ClientProxySourceRenderer`); for `n3b` also `vauban-core` (`InterceptedEmitter`, the run-time subclass)
- **Symptom**: a bean inherits a member whose parameter or return type is a type its own package cannot name — a package-private class of the superclass's package — and the processor renders an override of it: the build breaks. Three shapes, with `q.Hidden` package-private in `q`:
  - `n3a`: `@Dependent @Audited class Bean extends q.HiddenBase`, `HiddenBase implements Labeled<Hidden>` (default `label(T)`, no shadow): `Bean$$Intercepted` declares `label(q.Hidden)` (on `main`, the erased `label(Object)`: a name clash with `label(Hidden)`) — "Hidden is not public in q" / "name clash … have the same erasure, yet neither overrides the other";
  - `n3b`: `@Dependent @Audited class Bean extends q.TakesHidden`, `TakesHidden` declaring `public String take(Hidden h)`: `Bean$$Intercepted` declares `take(q.Hidden)` — "Hidden is not public in q";
  - `n3d`: the same `TakesHidden` superclass under an `@ApplicationScoped` bean: `Bean_ClientProxy` declares `take(q.Hidden)` — same error.
  - The fourth shape, `n3c` (a normal-scoped bean over the `Labeled<Hidden>` default), compiled on `main` because no client proxy forwarded a default then; the branch's BUG-20261004-02 fix made it a build break, which was fixed on the branch: the rendered proxy leaves the method out, with a warning (`ClientProxyInheritanceCrossCheckTest#nonShadowedDefaultWithAnInaccessibleMemberType`, `InaccessibleMemberTypeModulePathTest`).
- **The run-time generators on the same shapes** (probe `n7` of the round-5 re-review: the three beans compiled with `-proc:none`, interceptor enabled; `main` and the branch behave alike):
  - `n3a` works: the run-time `$$Intercepted` overrides `label(Ljava/lang/Object;)` — the hidden type erases away — and the call is intercepted;
  - `n3d` works: the run-time client proxy forwards `take(Lq/Hidden;)` by descriptor and the call reaches the contextual instance;
  - `n3b` does not: every call of `take` on the run-time `$$Intercepted` throws `IllegalAccessError: failed to access class q.Hidden from class …$$Intercepted`.
- **Cause**: Java source can only override a method with its signature as a member of the bean, and that signature may name a type the bean's package cannot (JLS 6.6.1): `n3a`, `n3b`, `n3d`. A class file can carry that type in a method descriptor — the JVM does not check access to the classes a descriptor names, which is why the run-time client proxy handles `n3d` — but it does check access whenever it resolves a class constant (JVMS 5.4.3.1, 5.4.4). The run-time `$$Intercepted` resolves one for every reference parameter of an intercepted method: `ldc` of the parameter class to look its `$$super$` bridge up with `getDeclaredMethod` (`InterceptedEmitter.java:272`), and `checkcast` of each argument in the invoker (`:406`). In `n3a` that class is `Object`; in `n3b` it is `q.Hidden`, hence the `IllegalAccessError` at the first call. By the same rule a hidden reference return type would fail at the `checkcast` of the returned value (`:363`); not probed.
- **Fix direction — needs design.** Constraints:
  - Leaving such a bean to the run-time generators, as the processor does for a supertype it cannot complete, fixes `n3d` and `n3a` only. For `n3b` it turns the build break into an `IllegalAccessError` on every call, and on the strict module path the run-time definition also needs the bean's package opened to `io.vidocq.vauban.core` (BUG-20261004-05).
  - `n3b` needs the bytecode emitter to stop resolving a class the subclass cannot access. It would get the bridge's parameter classes without a class constant: `Class.forName(name, false, loader)` from the bean's loader, which checks no access, or the parameter types of the superclass method `getDeclaredMethods` returns. It would also pass such an argument, and such a return value, without the `checkcast` the verifier otherwise requires, for instance through a `MethodHandle` adapted to `Object` with `asType`: a `MethodType` may hold a class the caller cannot access.
  - The processor cannot render that class as source at all. It can emit it as bytecode with such an emitter, but the source `_VaubanComponents` provider instantiates the subclass by name and must still reach it. Or it can leave the method out with a warning, as for a shadowed default with no nameable interface (BUG-20261004-08): the build then passes, but a class method of the bean is not intercepted, and only the warning says so.
- **Same family — a private nested type of the bean's own package** (found by the final review of the branch; probe `n11` of its fix wave, against the round-5 exports of `main` 7384ac1 and of `00c22c8`, and after the fix against the working tree): `InterceptedShapeFromElements#nameableFrom(TypeElement, PackageElement, Elements)` answered "nameable" for any type of the generated class's package before it looked at the enclosing types. A `private` nested type of a top-level class of that package passed, although a generated top-level class cannot name it (JLS 6.6.1). With `p11.Outer` declaring `private static class Hidden`, `public static class LabeledBase implements Labeled<Hidden>`, `public static class TakesBase { public String take(Hidden h) }` and `public static class ShadowedLabeledBase extends PrivLabel implements Labeled<Hidden>` (`PrivLabel` declaring a private `label(Object)`):
  - **FIXED 2026-10-04 (`ee46fc4`)**: in the same package the check now refuses a private type or a type nested in one. Each shape below compiles again, with the processor's warning, and its generated sources are byte-identical to `main`'s:
    - `n11a`, `@ApplicationScoped class ScopedLabeled extends Outer.LabeledBase` (the default `label(Outer.Hidden)` through the client proxy). It compiled on `main`, whose proxies forwarded no default. Before the fix the branch's rendered `_ClientProxy` declared `label(p11.Outer.Hidden)` — "Hidden has private access in Outer" — a regression of BUG-20261004-02 that the guard of `d6023f5` missed. Now the default is left out of the proxy, as for `n3c`, and runs on the proxy instance.
    - `n11d`, the same shape through a producer's proxy rendered in the produced type's own package (`@Produces @ApplicationScoped Produced make()`, `Produced extends Outer.LabeledBase`): the same regression and the same fix. The warning sits on the producer method.
    - `n11e`, the shadowed default of `ShadowedLabeledBase`: its client proxy now leaves it out, and its `$$Intercepted` (`@Dependent @Marked`) leaves it out too. That subclass is not `main`'s, which did not compile (name clash).
  - Tests: `ClientProxyInheritanceCrossCheckTest#nonShadowedDefaultWithAPrivateNestedMemberType` and `InterceptedShapeFromElementsTest#shadowedDefaultWithAPrivateNestedTypeArgument` (RED `final2-vauban-n11a-red-processor.log`); `InaccessibleMemberTypeModulePathTest#privateNestedDefaultLeftAlone` (RED `final2-vauban-n11a-red-moduleit.log`: vauban-module-it did not compile).
  - **Still OPEN**, pre-existing: `n11b` (client proxy over `take(Outer.Hidden)`) and `n11c` (a `@Dependent` bean with a class-level binding, over the non-shadowed default) break the build on `main` and on the branch alike. The branch reports "Hidden has private access in Outer"; on `main`, `n11b` fails the same way and `n11c` with a name clash. These are the class-method and subclass shapes of this entry, which the nameability check does not cover: for a class method the processor does not check names at all, and the subclass checks names for a shadowed default only.
  - Divergences left by the fix. Every processor shape picks the owner of a shadowed default with this check, and `ShadowedDefaults#accessible` accepts any interface of the bean's package and loader: a class file gives a private nested interface package access. So when the only interface carrying the default is a private nested one of the bean's package, the processor's shapes no longer use it and the run-time ones still do:
    - client proxy: the co-located (bytecode) proxy no longer forwards through it, the run-time proxy still does. This is from the code, not probed. `main` forwarded no default on either path.
    - intercepted subclass: the processor's subclass leaves the method out (`n14` of the re-review of the fix wave: `class Bean extends Outer.Mid`, `Mid extends Shadow implements PI`, `PI` a private nested interface with a default `m()`, `Shadow` with a private `m()`), so a call throws `AbstractMethodError` on HotSpot 25. The run-time subclass lists `Outer$PI` and intercepts `m()`. On `main` both paths threw `IllegalAccessError`. Pinned by `InterceptedShapeFromElementsTest#omittedDefaultReportsCompareTheGenerators` (`PrivateCarrierBean`), and the subclass warning says that the bytecode generators decide by class-file access (`8710532`).
  - **Recorded, not changed — a protected nested type of a superclass in another package** (nit of the re-review of the fix wave, probes `n12h`, `n12x`): for another package, `nameableFrom(TypeElement, …)` requires `public` through every enclosing type. A `protected` nested type of a superclass of another package therefore counts as unnameable, although the generated class, a subclass of that superclass, may name it (javac accepts `label(q12.QBase.Prot)` in such a proxy, `n12x`). A non-shadowed default whose member signature names it is not forwarded, with a warning that says the package "cannot" name the type (`n12h`). That output is byte-identical to `main`'s, which forwarded no default, so nothing that worked is lost. A fix would accept a `protected` nested type when the generated class extends its enclosing class.
- **Fix (2026-10-07, vauban#120)**. Java source cannot write these overrides, and the bytecode generators can, so the processor now emits the class Java source cannot render as bytecode, with the same emitters the run-time path and the Maven plugin use. Everything else is rendered as source, as before.
  - **The decision.** `InterceptedShapeFromElements#from(…, omitted, unnameable)` reports each method it keeps whose signature as a member of the bean names a type the bean's package cannot name: a non-shadowed default or a class method (a shadowed default in that case is still left out, BUG-20261004-08). `ClientProxyShapeFromElements#unnameableForwards` does the same for the class methods a source proxy forwards and for the non-shadowed defaults (`n3c`, `n11a`, see below). When either list is not empty, `VaubanProcessor` writes the subclass with `InterceptedEmitter#emit` and the proxy with `ClientProxyEmitter#emit`/`#emitAt` from `ClientProxyShapeFromElements#fromErased`/`#fromProducedErased`, the erased shape of `from`, through the `Filer` as class files, and says so in a note.
  - **How the provider reaches them.** The processor generates in the last round, and javac does not read back the class files created in the last round, so the source `_VaubanComponents` cannot name them ("cannot find symbol", observed). It instantiates them through its own `MethodHandles.lookup()` instead: `$$construct(binaryName, parameterTypes, args)` (`findConstructor`), and for a proxy `$$proxy(binaryName, delegate)` (`findVirtual` of `$$setDelegate`). The provider's lookup has full privileges in its own package, so the module still needs no `opens`. These helpers are written only into a provider that has such a class; every other provider is unchanged (`ComponentProviderGenerator#generateFrom(…, bytecodeClasses)`).
  - **`n3a`, `n11c`** (`$$Intercepted` over the default `label(Hidden)`): the bytecode subclass overrides the erased descriptor `label(Ljava/lang/Object;)`, as the run-time subclass does, and the call is intercepted. Source cannot: `label(Hidden)` names the type, and `label(Object)` is a name clash.
  - **`n3b`** (`$$Intercepted` over `take(q.Hidden)`): the bytecode subclass had the same `IllegalAccessError` as the run-time one, so the emitter changed for both. `MethodShape#namesInaccessibleTypes` marks a method whose descriptor has a parameter class the subclass may not access (JVMS 5.4.4: a class of another runtime package that is not public in its class file, or not exported to the bean's module). For such a method only, `InterceptedEmitter` loads the bridge's parameter classes with `Class.forName(name, false, <subclass>.class.getClassLoader())` instead of `ldc`, and the `$$ti$` glue calls the `$$super$` bridge through `MethodHandles.lookup().unreflect(…).bindTo(target).invokeWithArguments(params)` instead of `checkcast` + `invokevirtual`, since the verifier requires the cast and the cast resolves the class. Both front-ends set the flag by the same rule: `InterceptorSubclassGenerator#accessibleFrom` (run time) and `InterceptedShapeFromElements#resolvableFrom` (processor). The bytecode of every other method is unchanged (`GoldenBytecodeTest`).
  - **A return type the subclass may not access** (not probed before; `HiddenTaker#give()` now): nothing in a class outside that type's package can type the `Object` the interceptor chain returns. `checkcast` throws `IllegalAccessError`, and so does a `MethodHandle#invokeExact` whose descriptor names the type (observed). Such a method is left out of the subclass by both front-ends, and the processor warns: a call runs it as on a plain instance of the bean, not intercepted. Before, the run-time subclass threw `IllegalAccessError` on every call, and the processor's source did not compile.
  - **`n3d`, `n11b`** (`_ClientProxy` over `take(q.Hidden)`, `take(Outer.Secret)`): the bytecode proxy forwards by descriptor (`invokevirtual Bean.take:(Lq/Hidden;)…`), which resolves no class constant for the parameter, as the run-time proxy already did. A producer's proxy (#42) takes the same path, in the producer's package.
  - **`n3c`, `n11a`** (`_ClientProxy` over a non-shadowed default whose member signature is unnameable; second round of the fix): the source proxy left the default out, with the warning of BUG-20261004-02, so a call ran the default body on the proxy instance, while the bytecode generators forwarded it. Such a default now triggers the bytecode proxy too, which forwards its erased descriptor to the contextual instance, as the run-time proxy does: the two generators agree on these shapes. `ClientProxyShapeFromElements#from` keeps its omission for a caller that renders source, but the processor no longer renders these proxies as source. Comparison against `main`: no generated source or class changes for any bean outside the fixed shapes (`n3c`/`n11a` themselves are `ScopedHiddenDefaultService` and `ScopedSecretLabeledService` in vauban-module-it). Tests: `InaccessibleMemberTypeModulePathTest#defaultForwarded`, `#privateNestedDefaultForwarded` (the expectation was reversed: RED `bug09b/red-n3c.log`, the default ran on the proxy instance), `ClientProxyInheritanceCrossCheckTest#unnameableClassMethods`, `UnnameableMemberBytecodeEmissionTest#nonShadowedDefault`. `OmittedDefaultDiagnosticTest` moved to a shadowed default (a private `label(Object)` on the base), which the source proxy still leaves out with that warning.
  - **Readability.** The new checks do not ask whether the generated class's module reads the type's module. That check (`reads`, `d3fa351`) reads the `requires` of the module being compiled, and so completes it. Run for every forwarded method, it made javac resolve the `provides … with _VaubanComponents` before the last round wrote that class: "cannot find symbol" in `vauban-producer-module-it/bean-b` and `example-cdi1015-app` (observed on the first green run). The shadowed-default owner keeps the check. So a type of a module the bean's module does not read is still taken as nameable, as on `main`, and `resolvableFrom` does not mirror the run-time `Module#canRead`.
  - **Tests.** RED on `main` (`bug09/red-core.log`, `red-processor.log`, `red-moduleit-main.log`): `InaccessibleMemberTypeTest` (vauban-core, run-time path: `IllegalAccessError` on `take`, `takeMany` and `give`, and through the proxy), `InterceptedShapeFromElementsTest#unnameableMemberSignatures`, `ClientProxyInheritanceCrossCheckTest#unnameableClassMethods`, `ComponentProviderGeneratorTest#bytecodeClassesThroughTheLookup` (vauban-processor: new API, does not compile), `UnnameableMemberBytecodeEmissionTest#producerProxy` (vauban-processor, through `VaubanProcessor`: a producer's proxy over `take(HiddenArg)` is a class file, the provider reaches it with `$$proxy`, and it forwards to the produced instance), `ConstructorInjectedInterceptedModulePathTest#bytecodeSubclass` (the provider builds a bytecode subclass through an injected constructor; it waited on BUG-20261007-03), and `UnnameableMemberTypeModulePathTest` (vauban-module-it, `n3a`, `n3b`, `n3d`, `n11b`, `n11c`, a class method over `Outer.Secret`, and a producer's proxy over `HiddenTaker`: the module did not compile, "HiddenArgument is not public" and "Secret has private access").
  - **Left as is.**
    - A *shadowed* default whose member signature, or carrying interface, the source cannot name is still left out of the source subclass and proxy (BUG-20261004-08 above), and the bytecode generators keep it. That divergence is unchanged.
    - A bytecode subclass or proxy has no `@Vetoed` and is not `final`, unlike the source one. Vauban never discovers it, since it is not in the index, but a container that scans the archive could.
      - 2026-10-28 (vauban#227): a client proxy emitted as bytecode is now `final` and `@Vetoed`, like the source one (`ClientProxyEmitter`). A bytecode intercepted subclass still is neither.
    - `$$construct` and the `$$ti$` glue of a flagged method look up their handles on every call. These shapes are rare, so nothing is cached.
      - 2026-10-28 (vauban#227): the provider no longer looks a constructor up on every call. `$$construct` and `$$proxy` gave way to one holder class `$$Ctor<n>` per constructor, whose `MethodHandle` is looked up once, on first use, and called with `invokeExact`. A checked exception from such a constructor now propagates as is, as from a `new` (vauban#145), instead of wrapped in an `IllegalStateException`. The `$$ti$` glue still looks its handle up on every call.

## BUG-20261004-10 — The Maven plugin pre-generates the intercepted subclass only for a bean with a class-level binding

- **Date**: 2026-10-04
- **Status**: FIXED 2026-10-07 (vauban#121, `82670c48`, branch `pr/ybl/plugin-pregenerates-every-intercepted-bean`). Was pre-existing on `main` 7384ac1, noted in the FV2 report and the final review of `pr/ybl/inherited-interceptor-method`.
- **Module**: `vauban-maven-plugin` (`VaubanGenerator#generate`, the `vauban:generate` goal)
- **Symptom**: `vauban:generate` writes `<Bean>$$Intercepted` only for a managed, non-final bean whose class-level interceptor bindings are not empty (`VaubanGenerator.java:298-301`: `bean.kind() == BeanKind.MANAGED && !bean.interceptorBindings().isEmpty() && !isFinal`). `BeanDescriptor#interceptorBindings` holds the bindings on the bean class, plus the `@Inherited` ones of its superclasses (`BeanDiscovery#extractInterceptorBindings`). A bean intercepted any other way gets no pre-generated subclass from the plugin: through a method-level binding it declares or inherits, from a superclass or on an interface default method; through a constructor binding; or through an `@AroundInvoke` method of its own. The container then defines the subclass at run time. On the class path that works. On the strict module path it needs the bean's package opened to `io.vidocq.vauban.core`, and fails otherwise with `DeploymentException: Could not define interceptor subclass`, the mechanism of BUG-20261004-05.
- **Scope**: an archive the annotation processor compiled is not affected: `VaubanProcessor#isInterceptedTarget` counts the method-level bindings of every member since BUG-20261004-05, and the plugin skips a subclass that is already on disk.
- **Minimal reproduction**: a module built without the processor, holding `@Dependent class Audit { @Logged public void write() {} }` and a `@Logged` interceptor, run through `vauban:generate`, writes no `Audit$$Intercepted.class`; booted on the module path without `opens`, it fails as above.
- **Fix direction**: decide on the same rule as the processor. A managed bean is pre-generated when the container would wrap it: a class-level binding, a method-level binding on a business method it declares or inherits (`BeanMembers#isBusinessMethodOf`, CDI 4.1 §4.2), a constructor binding, or an `@AroundInvoke` method. The plugin holds the loaded `Class` at that point, so it can apply the rule the way the container does.
- **Cause**: the plugin kept its own rule, on the index-based `BeanDescriptor#interceptorBindings` (class-level only), instead of the walk the container runs in `InterceptorBeanWrapper#wrapInterceptedBeans`.
- **Fix**: the walk that decides whether, and why, the container wraps a bean moved to `vauban-core` (`InterceptionTargets#causeOf`): class-level bindings, then a method-level binding of a business method the bean declares or inherits (superclass or interface default method, resolved from the bean class, so an overriding declaration without the binding does not count, CDI 4.1 §4.2), then a constructor binding, then an `@AroundInvoke` method of the bean's class chain. The container asks it whether enabled interceptors select each member, as before; `vauban:generate` asks whether each member carries a binding (`InterceptionTargets#carriesInterception`), since the interceptors enabled in the deployment are not known at build time, and an unused subclass costs nothing. Like the container and the processor, the plugin no longer writes a subclass for an `@Interceptor` class, which the container never wraps; a bean whose members cannot be linked on the plugin's class path is skipped with a warning rather than failing the goal.
- **Tests**: `InterceptedSubclassGenerationTest` (vauban-maven-plugin; fixtures in `generate/intercepted`): `declaredMethodBinding`, `inheritedMethodBinding`, `defaultMethodBinding`, `constructorBinding`, `ownAroundInvoke` and `notIntercepted` (the `@Interceptor` case) failed before the fix (RED `bug10-red.log`: `MethodBound$$Intercepted must be pre-generated; generated: [ClassBound$$Intercepted, LoggedInterceptor$$Intercepted]`); `classLevelBinding` and `overriddenBindingDoesNotCount` pin the unchanged cases. Over the 74 interceptor packages of the CDI TCK 4.1.0 jar, the plugin writes the same 62 subclasses as `main` for the beans it already handled, byte for byte except one whose two methods come out in another order (the order depends on what the JVM loaded before, on `main` too), 30 new ones, and none of the 152 for `@Interceptor` classes.

## BUG-20261004-11 — The processor's index-based client proxy forwards only the methods the bean class declares

- **Date**: 2026-10-04
- **Status**: FIXED 2026-10-07 (vauban#122, `515fdcc`, branch `pr/ybl/index-proxy-inherited-methods`). Pre-existing on `main` 7384ac1 and still on `b95b8b1`, reproduced there by the tests below.
- **Module**: `vauban-processor` (`codegen/proxy/ClientProxyGenerator`, called from `VaubanProcessor`)
- **Symptom**: for a normal-scoped bean, the processor renders a source `_ClientProxy` when it finds the bean's `TypeElement` and the bean has a non-private constructor. Otherwise it emits a bytecode proxy from the index (`generateClass(ClientProxyGenerator.generate(classInfo))`). `ClientProxyGenerator#shapeOf` overrides `ClassInfo#methods()`, the methods the bean class declares, and no others, as its Javadoc says. So through that proxy, a method the bean inherits from a superclass, or an interface default method, runs on the proxy instance: it does not reach the contextual instance, and no interceptor runs. BUG-20261004-02 closed that gap for the two other proxy front-ends (`RuntimeClientProxyGenerator`, `ClientProxyShapeFromElements`).
- **When it is reached**: two cases.
  - A bean whose `TypeElement` the processor does not find. The lookup replaces every `$` of the binary name with `.`, so a top-level class whose own name contains `$` is one such case.
  - A bean with private constructors only. It is unproxyable under CDI 4.1 §3.10 unless the `ProxyLink` entry constructor is woven into it (vauban#24). For that reason `DeploymentValidator` reports it as `UNPROXYABLE_BEAN`, which the processor turns into a warning rather than an error.
- **Fix direction**: walk the superclasses and the default methods as `ClientProxyShapeFromElements#fromColocated` does. The index has the superclasses' `ClassInfo` when they are indexed. For the first case, find the `TypeElement` by its binary name rather than by a `$`-to-`.` rewrite, and keep the source path.
- **Fix**: the index-built proxy is no longer used for a bean the processor can see. `VaubanProcessor#typeElementByBinaryName` tries each top-level candidate of the binary name, then walks its member types, and keeps the one whose binary name matches, so a top-level `A$B` is found as well as a nested `Outer$Inner`. The source proxy then names the bean by its canonical name (`ClientProxySourceRenderer#render(shape, beanSourceName)`), not by a `$`-to-`.` rewrite. A bean with private constructors only gets the shape of a proxy placed in its own package, `ClientProxyShapeFromElements#fromColocated`, emitted as bytecode by `ClientProxyEmitter`: the inherited members and the interface default methods are forwarded under the rules the other front-ends share, the constructor chaining is unchanged (plain `super()`, retargeted by the weaver to the woven `(ProxyLink)` constructor). `ClientProxyGenerator` is left for a bean whose `TypeElement` the compiler cannot resolve, which no managed bean compiled here reaches (the index is built from `TypeElement`s, and the classes an extension adds are skipped as external); it still forwards declared methods only, since the index holds a superclass or an interface only when it is indexed itself. The generated sources and classes of `vauban-module-it` and `vauban-bench` are the same as `main`'s apart from the new fixture and the known entry order of `_VaubanComponents`. Tests: `IndexProxyInheritedMethodsTest` (vauban-processor: `dollarBeanTakesTheSourceRoute`, `dollarBeanForwardsInheritedMethods`, `privateConstructorBeanForwardsInheritedMethods`, the last two loading the proxy, woven by the auto-started javac plugin, and calling a declared, an inherited and a default method), `IndexProxyInheritedMethodModulePathTest` (vauban-module-it, module path: `Dollar$SelfNamingService` through the container). RED on `b95b8b1`: `bug11-red-final.log`.

## BUG-20261007-01 — The rendered intercepted subclass of a top-level class whose name contains `$` does not compile

- **Date**: 2026-10-07
- **Status**: FIXED 2026-10-07 (vauban#122, same root as BUG-20261004-11; `fc031fc` and `41a9d61`, branch `pr/ybl/index-proxy-inherited-methods`). Pre-existing on `main` `b95b8b1`; found while reproducing BUG-20261004-11.
- **Module**: `vauban-processor` (`codegen/interceptor/InterceptedSourceRenderer`, `codegen/proxy/ClientProxySourceRenderer`, `codegen/proxy/InterfaceProxySourceRenderer`), `vauban-core` (`interceptor/TypeRef#sourceName`)
- **Symptom**: an intercepted bean declared as a top-level class whose own name contains `$` (`@ApplicationScoped @Audited public class Dollar$SelfNamingService`) breaks the build: the rendered `Dollar$SelfNamingService$$Intercepted` `extends io.vidocq.vauban.moduleit.Dollar.SelfNamingService`, which javac resolves as a nested type of a class `Dollar` and refuses (`cannot find symbol`, `method does not override or implement a method from a supertype`).
The same goes for a normal-scoped producer of such a class (`Dollar_Produced$$…_ClientProxy`: `extends …Dollar.Produced`) or interface (`Dollar_Port$$…_ClientProxy`), and for any generated method signature naming such a type (`public app.Dollar.Note echo(app.Dollar.Note p0)` in a client proxy or a subclass, or the producer bean's own proxy for `Dollar$Produced produced()`): `app.Dollar$Note is not visible`.
- **Cause**: every source renderer derived a type's source name from its binary name by turning every `$` into `.`, which is right for a nested type only: `InterceptedSourceRenderer#render` for the bean, `ClientProxySourceRenderer#renderAt(shape, proxyBinaryName)` for a produced class, `InterfaceProxySourceRenderer#render` for a produced interface, and `TypeRef#sourceName` for every type in a signature.
- **Fix**: the names come from the `TypeElement`s, as BUG-20261004-11 did for the bean's source client proxy. `TypeRef` carries the canonical name an element front-end knows (`TypeRef.ofReference(binaryName, dims, canonicalName)`, set by `InterceptedShapeFromElements#typeRefOf` and for the interface a shadowed default is forwarded through); it is not part of the type's identity, so the shapes the front-ends build still compare equal, and `sourceName()` writes it, falling back on the `$` rewrite for a `TypeRef` built from a `Class` or the index. `InterceptedSourceRenderer#render(shape, beanSourceName)` and `ClientProxySourceRenderer#renderAt(shape, proxyBinaryName, beanSourceName)` take the bean's or produced type's qualified name, and `InterfaceProxySourceRenderer` uses the interface's. `VaubanProcessor` also finds the producer bean and the provider's bean element with `typeElementByBinaryName`. In `41a9d61`, the annotation artefacts (`AnnotationArtefacts`, rendered from the index) write an annotation type, a class value or an enum value by the canonical name the processor resolves with `typeElementByBinaryName` (`AnnotationArtefacts.render(types, canonicalNames)`), keeping the `$` rewrite only for a type the compiler does not know; and `resolveProducedTypeElement`/`resolveProducedInterfaceElement` look the produced type up by binary name too, so a producer of a nested class or interface gets its build-time client proxy, which `getTypeElement(binaryName)` never found. The generated sources and classes of the four modules the processor runs on (`vauban-module-it` built from `main`'s sources, `vauban-bench`, `vauban-producer-module-it/bean-b`, `vauban-examples/example-cdi1015-app`) are byte-identical to `main`'s, apart from the `_VaubanComponents` entry order (sorted sources and normalised `javap -c -p` equal). A produced type that is parameterised still has its first class supertype picked rather than its raw type; that is unchanged, and with this fix a nested supertype found first is now resolved too. Tests: `DollarNamedTypeRenderingTest` (vauban-processor: `interceptedDollarBean`, `dollarTypeInSignatures`, `producerOfDollarClass`; RED `bug701-red-processor.log`), `DollarNamedTypeModulePathTest` (vauban-module-it, module path: `interceptedDollarBean`, `producerOfDollarClass`, `producerOfDollarInterface`; RED `bug701-red-moduleit-reactor.log`, processor of `79863dc` in the reactor: the module did not compile; the earlier `bug701-red-moduleit.log` ran `-pl vauban-module-it -am`, which leaves the processor out of the reactor and takes it from the local repository), `DollarNamedTypeRenderingTest#annotationArtefactsOfDollarTypes` and `#producerOfNestedTypes` (RED `left-red-processor.log`), `DollarQualifierAndNestedProducerModulePathTest` (vauban-module-it: a `$`-named qualifier with enum and class members resolved with annotation reflection forbidden, producers of a nested class and interface; RED `left-red-moduleit.log`: the provider did not compile).

## BUG-20261007-02 — The bytes of a run-time-generated subclass depend on what the JVM loaded before

- **Date**: 2026-10-07
- **Status**: FIXED 2026-10-07 (`0393dd78`, branch `pr/ybl/plugin-pregenerates-every-intercepted-bean`)
- **Module**: `vauban-core` (`InterceptorSubclassGenerator#fromClass`, `RuntimeClientProxyGenerator`), reached at build time through `vauban:generate`
- **Surfaced by**: the generated-bytes comparison of BUG-20261004-10.
- **Symptom**: `vauban:generate` run twice over the same input can write a `<Bean>$$Intercepted` whose methods come in another order. Over the interceptor packages of the CDI TCK 4.1.0 jar, `contract/invocationContext/SimpleBean$$Intercepted` lists `testGetTimer` before `testGetMethod` when that package is generated with the 73 others, and the reverse when it is generated alone, on `main` as well. Same members, same size: the class works either way, but the build is not reproducible.
- **Minimal reproduction**: generate the subclass of one bean in two JVMs that loaded different classes before it, and compare the bytes.
- **Cause**: the shapes are built from `Class#getDeclaredMethods`, `Class#getMethods` and `Class#getDeclaredConstructors`, whose order the JDK leaves unspecified ("not sorted and not in any particular order"). HotSpot returns the methods in the order of their name symbols in memory, which depends on the classes the JVM loaded before. The order also decides which of two `getMethods` entries with the same name and parameters is kept.
- **Fix**: `BeanMembers#STABLE_ORDER` (name, then parameter types, then return type, then declaring class) and `BeanMembers#inStableOrder`; `InterceptorSubclassGenerator#fromClass` and `RuntimeClientProxyGenerator#shapeOf` read `getDeclaredMethods` and `getMethods` through it, so each group of methods (declared, inherited public, inherited non-public for the subclass; each class of the hierarchy, then the default methods for the proxy) comes sorted, and the first of two same-key `getMethods` entries is always the same one. Constructors stay in reflection order: they share one name, so the symbol order cannot move them. The processor's front-ends read javac's elements in source order and are not affected.
- **Tests**: `StableMemberOrderTest` (vauban-core), fixtures `OrderedBean`, `OrderedBeanSuper` and `OrderedBeanPrimer`, the last loaded first so that its reverse-order method names set the symbol order. Both tests failed before the fix (RED `bug07-02-red.log`: `interceptedSubclassOrder` got `[soZulu(), soXray(), soSierra(), …, soAlpha(), soYankee(), soHotel(int), soBravo(), soPapa(), soDelta()]`, `clientProxyOrder` `[soZulu(), …, soAlpha(), soYankee(), soPapa(), soHotel(int), soDelta(), soBravo()]`). Over the interceptor packages of the CDI TCK 4.1.0 jar, the subclasses and client proxies `vauban:generate` writes are now identical byte for byte whether the 74 packages are generated in one order, in the reverse order, or one alone.
- **Not the same cause**: the entry order of a `_VaubanComponents` provider follows the order of the beans, which `BeanDiscovery#discoverBeans` takes from `VaubanIndex#getKnownClasses`. `VaubanIndex` keeps its classes in `Map.copyOf`, whose iteration order the JDK randomizes from one JVM run to the next, for the plugin and the processor alike; `ComponentCollector#collect` expects its caller to sort the beans and neither does. Not fixed here.

## BUG-20261007-04 — The entry order of a generated `_VaubanComponents` changes from one build to the next

- **Date**: 2026-10-07
- **Status**: FIXED 2026-10-08 (`fdf6ded1`, branch `pr/ybl/plugin-pregenerates-every-intercepted-bean`)
- **Module**: `vauban-indexer` (`ComponentCollector#collect`), its callers `VaubanProcessor` and `VaubanGenerator` (vauban-maven-plugin)
- **Surfaced by**: BUG-20261007-02 (the generated-bytes comparison).
- **Symptom**: the processor and `vauban:generate` can write a different `_VaubanComponents` for the same input: the same entries, in another order. Two plugin runs, in two JVMs, over the same fixture directory wrote the components of `ClassBound`, `MethodBound`, `InheritedBound`, … in two different orders.
- **Cause**: the beans come from `BeanDiscovery#discoverBeans`, which walks `VaubanIndex#getKnownClasses`. `VaubanIndex` keeps its classes in `Map.copyOf`, whose iteration order the JDK randomizes from one JVM run to the next. `ComponentCollector#collect` documents that its caller sorts the beans, and neither caller does. The other inputs of a provider follow bean order too: the client proxies, the producers' proxies, and the processor's annotation types and service file.
- **Fix direction**: sort where the provider is assembled, on a documented key, without changing the index's iteration order, which bean discovery depends on.
- **Fix**: `ComponentCollector#collect` sorts the beans by binary name before it groups them, so each package lists its components (a bean's `$$Intercepted` right after it), field injections and method invocations in that order, whatever order the caller hands them in; bean discovery keeps the index order. The other inputs of a provider are sorted where the callers assemble it: the client proxies by name, the producers' proxies by produced-type key then proxy name, the processor's annotation types by name, and the service file's provider names.
- **Tests**: `ComponentCollectorOrderTest` (vauban-indexer): `entriesInNameOrder` and `sameOutputWhateverTheInputOrder` failed before the fix (RED `bug07-04-red.log`: `expected: <[app.Alpha, app.Alpha$$Intercepted, app.Bravo, app.Charlie]> but was: <[app.Charlie, app.Alpha, app.Alpha$$Intercepted, app.Bravo]>`). End to end: two builds of `vauban-module-it` and `vauban-producer-module-it` with the reactor's processor write the same `_VaubanComponents` sources, classes and service files (8 files); two earlier builds with the processor from the local repository did not (the client-proxy cases and the annotation literals moved). Two `vauban:generate` runs over the same input, in two JVMs, write the same `_VaubanComponents`.

## BUG-20261007-05 — The plugin's `_VaubanComponents` does not list the `$$Intercepted` subclasses it pre-generates

- **Date**: 2026-10-07
- **Status**: FIXED 2026-10-08 (`6945745f`, branch `pr/ybl/plugin-pregenerates-every-intercepted-bean`)
- **Module**: `vauban-maven-plugin` (`VaubanGenerator#generateComponentProvider`, `#generateDependencyProviders`)
- **Surfaced by**: BUG-20261004-10.
- **Symptom**: the plugin builds every `ProvidedClass` with `intercepted = false`, so `ComponentCollector` never adds a `<Bean>$$Intercepted` component to the provider, although the plugin wrote that subclass next to it. The processor sets the flag for each subclass it renders.
- **What it costs at run time** (from `InterceptorBeanWrapper` and `VaubanLookup`; the qualified-export case is reproduced by the test below): the container finds the pre-generated subclass (`Class.forName`), so it does not define one. It then instantiates it through `instantiatePreferProvider`: the provider has no entry, so it tries `MethodHandles.publicLookup()` on the subclass constructor, which the emitter makes public. That works, without `opens`, when the bean's package is exported to everyone. When the package is exported to `io.vidocq.vauban.core` only (a qualified export), the public lookup is refused and the container falls back to `privateLookupIn`, which needs `opens <pkg> to io.vidocq.vauban.core`: without it, creating the bean fails with "Cannot reflectively access <Bean>$$Intercepted on the module path". So the cost is one reflective constructor call when the package is exported to everyone, and a boot failure without `opens` when the export is qualified.
- **Fix direction**: set `intercepted` for a bean whose `$$Intercepted` the plugin wrote or found on disk, as the processor does.
- **Fix**: both provider paths of the plugin (`generateComponentProvider`, `generateDependencyProviders`) set `intercepted` when `<Bean>$$Intercepted` is on disk in the output directory, written by this run or found there, so `ComponentCollector` adds its component right after the bean's, as for the processor.
- **Tests**: `InterceptedSubclassGenerationTest#providerListsThePreGeneratedSubclasses` (RED `bug07-05-red.log`: `the provider must create …ClassBound$$Intercepted; it knows [… the beans only …]`). `PluginBuiltModulePathTest` (vauban-maven-plugin) builds a module the way a plugin user does (javac, then the generator over the classes, then `module-info.java` alone, declaring the provider), with a bean bound only through a method it declares, in a package exported to `io.vidocq.vauban.core` only and no `opens`, and boots it in a module layer: the bean is the pre-generated subclass and the interceptor runs. Without this fix (RED `bug07-05-e2e-red.log`) it fails with `Cannot reflectively access vauban.plugin.it.beans.MethodBoundService$$Intercepted on the module path.`; it also covers BUG-20261004-10 on the module path. Over the interceptor packages of the CDI TCK 4.1.0 jar, with the providers of the scanned jars written too, the 69 providers keep every entry `main` writes, in sorted order, and 40 of them gain one `$$Intercepted` entry for each subclass written next to them (90 in all).

## BUG-20261007-03 — An intercepted bean with an injected constructor reads its parameters' qualifiers by reflection

- **Date**: 2026-10-07
- **Status**: FIXED 2026-10-07 (branch `pr/ybl/unnameable-inherited-member-types`). Pre-existing on `main` b95b8b1; found while writing the tests of BUG-20261004-09.
- **Module**: `vauban-core` (`InterceptorBeanWrapper`, `QualifierHelper`)
- **Symptom**: with `vauban.annotations.reflection=forbid` (every vauban-module-it test), a `@Dependent @Audited` bean whose `@Inject` constructor takes a `Collaborator` fails when it is created: `Forbidden: reading the qualifiers off parameter 0 of …CtorInjectedAuditedProbe.<init>() needs reflection`. The parameter has no annotation. The same bean without interception, or with a no-arg constructor, is created in-module.
- **Minimal reproduction**: on `main`, in vauban-module-it, `@Dependent @Audited public class CtorInjectedAuditedProbe { @Inject public CtorInjectedAuditedProbe(Collaborator c) {…} }` and `VaubanContainer.builder().addBeanClass(AuditInterceptor.class).addBeanClass(Collaborator.class).addBeanClass(CtorInjectedAuditedProbe.class).build().select(CtorInjectedAuditedProbe.class)`; log `bug09/probe-ctor-main.log`.
- **Cause (from the trace)**: `InterceptorBeanWrapper$1#create` (`InterceptorBeanWrapper.java:824`) resolves the constructor arguments itself, through `QualifierHelper#extractParamQualifiers(Parameter)`, which reads the parameter's annotations reflectively. It does not use the metadata the processor generates for the bean's injection points.
- **Fix**: `InterceptorBeanWrapper`'s intercepted factory takes each constructor parameter's qualifiers from the bean's descriptor (`VaubanContainer#describedParameterQualifiers`), as `VaubanContainer` does for a bean that is not intercepted, and reads the parameter back only when nothing describes it. This covers both of its constructor paths: with and without an `@AroundConstruct` chain. Interceptor instances built with constructor arguments (`getOrCreateInterceptorInstance`) still read their parameters: they have no bean descriptor there. Not probed.
- **Tests**: `ConstructorInjectedInterceptedModulePathTest#sourceSubclass` and `#bytecodeSubclass` (vauban-module-it; RED `bug09b/red-ctor.log`: `Forbidden … reading the qualifiers off parameter 0 of …<init>()` for both).

## BUG-20261009-01 — BCE-added injection annotations do not create the corresponding injection points

- **Date**: 2026-10-09
- **Status**: FIXED (branch `fix/bce-injection-enhancement`, PR #139)
- **Module**: `vauban-core` (`extensions/EnhancementApplier`, `container/BeanInjector`, `container/ManagedBean`)
- **Symptom**: a CDI Build Compatible Extension that adds `@Inject` to a plain field or initializer leaves it absent from the bean's injection-point metadata and runtime injection. Qualifier changes to constructor and initializer parameters are ignored; class-level method annotation additions can also be misclassified as interceptor bindings. Enhanced bean classes created after scope promotion miss the member changes.
- **Minimal reproduction**:
  ```
  cd vauban/main
  ./mvnw -ntp -pl vauban-core -Dtest='BceEnhancementTest,BceInjectionEnhancementTest' test
  ```
  The direct descriptor regressions were red before the fix: the field and initializer points were missing and a constructor parameter kept `@Default` after an explicit qualifier was added.
- **Hypothesized cause**: `EnhancementApplier` only matched field changes against already-discovered field points, left parameter changes as a no-op, and treated every added method annotation as an interceptor binding. Runtime injection enumerated source-bytecode `@Inject` annotations only; newly enhanced beans created after scope promotion bypassed the enhancer.
- **Investigations**:
  - 2026-10-09 : the direct descriptor regressions reproduced the missing field and initializer injection points and ignored constructor qualifier mutation.
  - 2026-10-09 : fixed member metadata application, retained qualifier member values, recorded exact initializer signatures for runtime injection, applied enhancements to scope-promoted beans, and added an end-to-end test covering an enhanced field, constructor parameter, overloaded initializer, member-value qualifier and bean metadata.
  - 2026-10-09 : `./mvnw -ntp -pl vauban-core -Dtest='BceEnhancementTest,BceInjectionEnhancementTest' test` passed (27 tests).
  - 2026-10-09 : adapted to `main` `afe2ed3e` without restoring the duplicate scope-promotion
    code removed by #131. RED `scopePromotionAppliesMemberEnhancementsBeforeRegistration`:
    the centralized `BceProcessor.beansAfterEnhancement` returned zero member points for a promoted
    bean. The helper now enhances that descriptor too, so Registration and boot share the result.
  - 2026-10-09 : the zero-opens module regression exposed another missing case: an initializer
    that gained `@Inject` but whose parameters were not modified had no parameter descriptor and
    tried reading qualifiers reflectively under `vauban.annotations.reflection=forbid`. Every
    parameter of an added initializer is now described, retaining its source qualifier members.

## BUG-20261009-02 — APT providers cannot write public fields enhanced only by a runtime BCE

- **Date**: 2026-10-09
- **Status**: FIXED (branch `fix/bce-injection-enhancement`, PR #139)
- **Module**: `vauban-processor` (`VaubanProcessor`, `ComponentProviderGenerator`)
- **Symptom**: the runtime injection metadata fixed by BUG-20261009-01 contains a BCE-enhanced
  public field, but `_VaubanComponents.injectField()` has no matching write case. The reported
  Mansart module-path persistence integration consequently still cannot inject it without reflection.
- **Minimal reproduction**:
  ```
  cd vauban/main
  ./mvnw -ntp -pl vauban-processor -am \
    -Dtest=ComponentProviderCompileTimeTest -Dsurefire.failIfNoSpecifiedTests=false test
  ```
- **Hypothesized cause**: the shared `ComponentCollector.collectFields()` selects source-level
  `@Inject` fields only; annotations added by a runtime BCE are unknown during APT.
- **Investigations**:
  - 2026-10-09: `publicFieldsCanBeInjectedAfterRuntimeEnhancement` was red: provider coverage
    contained only `alreadyInjected`, not the unannotated public `context`, `unit` and `repositories`.
  - 2026-10-09: supplemented the APT collector output with public, non-static, non-final reference
    fields whose erased types are accessible from the provider package. Existing cases are
    deduplicated; private field types are skipped. Runtime injection metadata remains authoritative.
    Nested bean write cases use canonical source owner names and binary dispatch keys.
  - 2026-10-09: `ComponentProviderCompileTimeTest` passes (10 tests), including a named-module
    ServiceLoader probe without opens or an export to the API module. All 143 processor tests pass:
    `./mvnw -ntp -pl vauban-processor -am -Dtest='io.vidocq.vauban.processor.**.*Test'
    -Dsurefire.failIfNoSpecifiedTests=false test`.
  - 2026-10-09: the Mansart module-path IT (`mansart-jpa-cdi-module-it`) then failed to compile:
    `module-info.java:[14,98] cannot find symbol … class _VaubanComponents`. Root cause: a regression
    of this supplement, not of the fixture. It filtered candidate field types with
    `InterceptedShapeFromElements.nameableFrom(type, pkg, elements)`, whose module *readability* check
    reads `ModuleElement.getDirectives()` of the module being compiled. That completes the module, so
    javac resolves its `provides … with pkg._VaubanComponents` before the last annotation-processing
    round has written the provider, and the error is frozen (the BUG-20261004-09 hazard; a
    `jakarta.persistence.EntityManager` field from another module triggered it, an earlier
    same-module `String` fixture did not).
  - 2026-10-09: RED `publicFieldOfAnotherModuleTypeDoesNotCompleteTheCompiledModule` — one javac
    pass over a named module (`requires java.sql`, `provides … with app._VaubanComponents`) with a
    `public java.sql.Connection` field — failed with the same `cannot find symbol` in module-info.
    Fix: the supplement calls the new `nameableFrom(type, pkg, elements, false)` overload, leaving out
    readability. Sound there: the field's own declaration names the type, so javac already checks the
    module reads it. Accessibility/export checks remain. GREEN; processor suite 144/144; the Mansart
    module IT compiles and its 4 tests pass unchanged.
  - Initial generated-write limitations (the lookup limitation below was superseded on merging
    `main` `afe2ed3e`): write cases cover only
    public, non-static, non-final, reference-typed fields declared in a class of the compiling
    module that already has a package provider (nested owners need a component-table entry), with
    an erased type nameable from that package. Non-public fields, primitives, fields inherited from a
    class of another module, and initializer methods whose `@Inject` is added only by a runtime BCE
    get no generated access; they fall back to `VaubanLookup`, which needs the qualified `opens` and
    fails loudly without it (`RuntimeException` "Cannot reflectively access …", caused by `IllegalAccessException`) — never a silent skip.
  - 2026-10-09 : adapted member access to the generated provider's `ModuleLookupGrant` bridge.
    Direct public-field write cases stay first; `VaubanLookup` consults `ModuleLookups` before its
    existing explicit-lookup/qualified-opens fallback. A managed class with a granting package
    provider can therefore receive private fields and private runtime-added initializers without
    opening anything. RED/GREEN `ModuleLookupModulePathTest.runtimeEnhancementInjectsPrivateMembersThroughTheGrantedLookup`.
    This does not grant access to arbitrary classes: the upstream managed-class/provider/module
    checks and registration lifetime remain authoritative. A declaring class without such a grant
    still needs the existing supplied lookup or qualified opens.
## BUG-20261008-01 — A synthetic bean type given as a language-model type is lost between build time and run time

- **Date**: 2026-10-08
- **Status**: FIXED 2026-10-08 (`ca601963`, branch `pr/ybl/rest-client-module-path`)
- **Affected module**: `vauban-core` (`SyntheticMetadataSerializer.writeBeanBuilder`)
- **Symptom**: a build compatible extension run by the Vauban processor declares a synthetic bean with
  `components.addBean(Object.class).type(langModelType)` — the only way to name a type the compilation is still
  producing, which cannot be loaded. The processor validates the deployment against that type, then writes
  `META-INF/vauban-synthetic-metadata.properties` with `bean.N.types=java.lang.Object` only: at run time the bean has
  no other type and every injection point of that type is unsatisfied. Seen with Cyrano's `@RestClient` beans on the
  Vidocq runtime (`DeploymentException: Unsatisfied dependency: field RelayResource.greetings of type GreetingClient`)
  after cyrano BUG-20261008-06 made the extension declare them that way.
- **Minimal repro**: `new VaubanSyntheticBeanBuilder<>(Object.class).type(new VaubanClassType(DotName.of("com.example.Api"), null))`,
  `SyntheticMetadataSerializer.write(...)`, `readBeans(...)`: the types are `[java.lang.Object]`.
- **Root cause**: `VaubanSyntheticBeanBuilder.type(Type)` keeps language-model types in `indexTypes`;
  `writeBeanBuilder` writes `getTypes()` (the `Class` types) only.
- **Investigations**:
  - 2026-10-08: `SyntheticMetadataSerializerTest.shouldRoundTripALanguageModelClassType` read back `[java.lang.Object]`.
    Fixed: a class type given through the language model is written by name with the other types and loaded at run
    time like them. Parameterized language-model types (`Optional<String>`…) are still not written: the `types` list
    has no notation for them. With the fix, the Vidocq Rest Client example (Cyrano's `@RestClient` bean, declared at
    build time) starts and answers on the module path and from its jlink image.

## BUG-20261008-02 — A build-time synthetic bean param that is an array, an annotation or a ClassInfo is silently dropped

- **Date**: 2026-10-08
- **Status**: FIXED 2026-10-08 (Vidocq/vauban#130, branch `pr/ybl/synthetic-param-types`)
- **Affected module**: `vauban-core` (`SyntheticMetadataSerializer.encodeParam`, `SyntheticComponentRegistrar.applyParam`)
- **Surfaced by**: Foy Phase 2 (`FoyWebExtension`, foy branch `pr/ybl/servlet-completion-phase2`).
- **Symptom**: a build compatible extension run by `vauban-processor` calls
  `SyntheticBeanBuilder.withParam("classes", Class<?>[])`; at run time the creator's
  `Parameters.get("classes", Class[].class)` is `null`, and nothing is reported at build time (no `.param.classes=`
  line in `META-INF/vauban-synthetic-metadata.properties`). CDI 4.1 Lite requires every `withParam` overload to reach
  the creator: primitive and `String`/`Class`/`Enum` arrays, `ClassInfo`, `AnnotationInfo`/`Annotation`, `InvokerInfo`.
- **Minimal repro**: `@Synthesis` with `syn.addBean(X.class).type(X.class).withParam("classes", new Class<?>[]{String.class}).createWith(Creator.class)`,
  compiled with `vauban-processor`, then booted: `Creator` receives `null`. Pinned by foy's spike test
  `BceCapabilitySpikeTest.synthesisWithClassArrayParam` (foy `383b850`).
- **Root cause**: `encodeParam` (`main` @ `4ce059fa`, ~l.158-168) encodes `String`, `Boolean`, `Integer`, `Long`,
  `Double`, `Class`, `Enum` and returns `null` ("unsupported param type — skipped") for everything else.
- **Workaround (Foy)**: one `String` param of comma-joined binary names, split by the creator.
- **Investigations**:
  - 2026-10-08: confirmed on `main` @ `4ce059fa` by reading `encodeParam`; observed end to end on `0.4.0-SNAPSHOT`
    from `feat/dependency-providers` @ `7384ac10`.
  - 2026-10-08: `BceRuntimeParamsTest` (boot from written metadata) fails on `main` `76f530bc`: the creator gets a
    `String` where it asked for an enum. Reading the boot side showed more of the same: `applyParam` dropped `Class`
    and `Enum` params too (its switch had no case for them), and the params of a synthetic observer were never
    applied at all.
- **Fix**: `SyntheticParamCodec` writes every value `withParam` accepts — primitive, `String`, `Class`, `Enum` and
  annotation arrays, `ClassInfo` (read back as `Class`), `Annotation` and `AnnotationInfo` (read back as
  `Annotation`, every member kept), `InvokerInfo` (bean class, method, lookups) — in length-prefixed frames, so a
  value may hold any character. Earlier files still read. A value of another type fails the build with the bean and
  the param named; a class the boot cannot load fails the deployment the same way. Observer params are applied, and
  the duplicate check of synthetic beans compares array params by content. Tests: `SyntheticParamCodecTest`,
  `BceRuntimeParamsTest`, `BceCompileTimeTest.syntheticParamsOfEveryKindSurviveTheBuild` (Foy's `Class<?>[]`).

## BUG-20261008-03 — @Registration does not see the beans that @Enhancement creates

- **Date**: 2026-10-08
- **Status**: FIXED 2026-10-08 (Vidocq/vauban#131, branch `pr/ybl/registration-after-enhancement`)
- **Affected module**: `vauban-core` (`BceProcessor`)
- **Surfaced by**: Foy Phase 2 (`FoyWebExtension`).
- **Symptom**: an extension adds `@Dependent` in `@Enhancement(types = Object.class, withAnnotations = WebServlet.class)`
  to an unscoped `@WebServlet` class. The class becomes a bean (listed in `vauban-beans.list`, resolvable at run time),
  but the extension's `@Registration(types = Servlet.class)` method is never called for it. CDI 4.1 Lite runs
  Registration on the bean set as it stands after Enhancement.
- **Minimal repro**: foy `FoyWebExtensionTest.scopesAndIndex` (foy-cdi-vauban) before Foy's workaround — the index built
  in `@Registration` held only the class that already had a scope.
- **Root cause**: `BceProcessor` (`main` @ `4ce059fa`, ~l.229-234) passes the same pre-enhancement `beans` list (from
  discovery) to `processEnhancement` and `processRegistration`; the enhancement modifications are applied to the
  descriptors afterwards (`applyEnhancements`).
- **Workaround (Foy)**: `FoyWebExtension` also records the classes it sees in `@Enhancement`; foy-core skips listed
  classes that end up without a bean, with a WARNING.
- **Investigations**:
  - 2026-10-08: confirmed on `main` @ `4ce059fa` by reading `BceProcessor`.
  - 2026-10-08: `BceCompileTimeTest.registrationSeesTheBeansEnhancementCreated` fails on `main` `76f530bc`. The
    first failure is a different one: `bean.scope().name()` threw a `NullPointerException` in `@Registration`,
    because `VaubanBceScopeInfo.annotation()` returned `null` for a scope outside the index, which every built-in
    scope is at build time. A second cause showed when reading `process`: each extension went through all its phases
    before the next extension started, so an extension's `@Registration` could run before another extension's
    `@Enhancement`. At container start the bug was masked: the index is rebuilt with the enhanced annotations
    before discovery (but see BUG-20261008-05).
- **Fix**: `BceProcessor.process` runs each phase for every extension before the next phase, and hands
  `@Registration` the beans as `@Enhancement` left them, through `beansAfterEnhancement`. That method is now the one
  place that applies the enhancements and makes a bean of a class that gained a scope. The processor and the
  container each had a copy of that code; both copies are gone. `VaubanBceScopeInfo` names a scope outside the index
  and gives the stub declaration a class type gives. Tests: `BceCompileTimeTest.registrationSeesTheBeansEnhancementCreated`
  (the extension that registers is listed before the one that enhances), `VaubanBceScopeInfoTest`.

## BUG-20261008-05 — An annotation added by @Enhancement loses its member values

- **Date**: 2026-10-08
- **Status**: FIXED 2026-10-08 (Vidocq/vauban#135, branch `pr/ybl/enhancement-annotation-members`)
- **Affected module**: `vauban-core` (`VaubanContainerBuilder` index rebuild, `EnhancementPatchSerializer`,
  `VaubanClassConfig`, `VaubanFieldConfig`, `EnhancementApplier`), `vauban-processor`, `vauban-maven-plugin`
- **Surfaced by**: the tests of BUG-20261008-03.
- **Symptom**: `ClassConfig.addAnnotation(NamedLiteral.of("enhanced"))` on a `@Dependent` bean gives the bean its
  default name, not `enhanced`; a qualifier with members added the same way, to a class or to a field, loses its
  members, so an injection point that tells two beans apart by a member is unsatisfied. The default name of a nested
  class on that path keeps the binary simple name (`bceRegistrationAfterEnhancementTest$Plain`).
- **Minimal repro**: two extensions on `SeContainerInitializer.addBeanClasses`, one adding `@Named("enhanced")` to a
  bean in `@Enhancement`, the other recording `BeanInfo.name()` in `@Registration`: it records the default name.
- **Root cause**: `VaubanClassConfig.addAnnotation(Annotation)` and `VaubanFieldConfig.addAnnotation(Annotation)`
  kept the annotation type only. At container start, the rebuilt index wrote each added annotation with no member,
  through the 8-argument `ClassInfo` constructor that loses the simple name; `addAnnotation(AnnotationInfo)` did not
  reach it at all. At build time, the frozen patch `META-INF/vauban-enhancements.properties` recorded annotation
  names only, in the processor and in `vauban:generate`. `EnhancementApplier` built the added qualifiers from their
  type and never applied an added `@Named` to the bean's name.
- **Investigations**:
  - 2026-10-08: found on `pr/ybl/registration-after-enhancement` (based on `main` `76f530bc`).
    `BceEnhancementMembersTest` fails on `main`: `Unsatisfied dependency ... @Channel(value=beta)`.
- **Fix**: the class and field configs keep the instance an extension gives; `VaubanClassConfig.getAddedAnnotationsIndexed()`
  gives every added annotation in index form, members included, whichever `addAnnotation` added it. The rebuilt index
  uses it and `ClassInfo.withAnnotations`, which keeps the simple name. The frozen patch writes each annotation with
  its members through `SyntheticParamCodec.encodeAnnotation`; the names-only form of earlier builds still reads.
  `EnhancementApplier` builds added qualifiers from the instance and applies an added `@Named` (its value, or the
  default name) to the bean and its `@Named` qualifier. Tests: `BceEnhancementMembersTest` (name, default name of a
  nested class, class and field qualifier members, seen by another extension's `@Registration`),
  `EnhancementPatchSerializerTest`, `BceCompileTimeTest.frozenPatchKeepsMembers`.

## BUG-20261008-04 — Application stereotypes are not bean-defining at build time

- **Date**: 2026-10-08
- **Status**: FIXED 2026-10-08 (Vidocq/vauban#132, branch `pr/ybl/stereotype-bean-defining`)
- **Affected module**: `vauban-processor` (indexing / bean-defining annotations), `vauban-core` (stereotype scope)
- **Surfaced by**: Foy Phase 2 (`FoyWebExtension`, fix round of Task 2.10).
- **Symptom**: with `vauban-processor`, (1) a class whose only CDI annotation is an application stereotype
  (`@Stereotype @ApplicationScoped @interface Managed`) is not indexed — no bean — although a scoped stereotype is
  bean-defining (CDI 4.1 §2.5.1, §2.8); (2) a class indexed for another reason and carrying that stereotype gets
  `@Dependent` instead of the stereotype's `@ApplicationScoped`. Same result with the stereotype in the same compilation
  or in a separate library jar.
- **Minimal repro**: a `@WebServlet @Managed` class plus a control class carrying only `@Managed`, compiled with
  `vauban-processor` + foy-cdi-vauban on the processor path: the first is a `@Dependent` bean, the second is no bean.
  (Foy dropped the harness tests and covers its logic with `FoyWebExtensionScopeTest`.)
- **Root cause**: not investigated.
- **Investigations**:
  - 2026-10-08: observed on `0.4.0-SNAPSHOT` from `feat/dependency-providers` @ `7384ac10`; not re-run on `main`
    (`main` gained build-time annotation-type indexing in vauban#70, which may change part (2)).
  - 2026-10-08: `BceCompileTimeTest.applicationStereotypeIsBeanDefining` fails on `main` `76f530bc` with part (1):
    the class carrying only the stereotype is not in the bean list. Root cause: the processor only looked at the
    classes carrying an annotation of a fixed list (`ApplicationScoped`, `RequestScoped`, `Dependent`, `Singleton`,
    `Produces`, `Interceptor`) plus the extensions' triggers, so a class whose only bean-defining annotation was a
    stereotype, a custom scope, `@SessionScoped` or `@Model` never entered the index. Part (2) no longer reproduces
    on `main`: a class indexed through its producer gets the stereotype's `@ApplicationScoped`, with the stereotype
    in the same compilation or in a library (the annotation-type indexing of vauban#70 sees it).
- **Fix**: the processor supports every annotation (`*`, never claimed) and indexes a class carrying one of the fixed
  list, an extension trigger, or an annotation meta-annotated `@Stereotype`, `@NormalScope` or `@Scope`. Tests:
  `BceCompileTimeTest.applicationStereotypeIsBeanDefining`, `libraryStereotypeIsBeanDefining` (stereotype compiled
  separately, on the class path); both also check that the beans are normal-scoped (they get a client proxy).

## BUG-20261009-03 — Frozen enhancements drop field, initializer and parameter additions at boot

- **Date**: 2026-10-09
- **Status**: FIXED (branch `fix/bce-injection-enhancement`, PR #139)
- **Module**: `vauban-core` (`EnhancementPatchSerializer`, `VaubanContainerBuilder`),
  `vauban-processor`, `vauban-maven-plugin`
- **Symptom**: an extension run at build time adds `@Inject` to a field and private initializer,
  and changes a constructor parameter qualifier. After boot from the processed archive, the field
  and initializer remain null and the constructor parameter keeps its source qualifiers.
- **Minimal reproduction**:
  ```
  ./mvnw -ntp -pl vauban-processor -am \
    -Dtest='BceCompileTimeTest#frozenPatchAppliesAddedFieldInitializerAndParameterQualifierAtBoot' \
    -Dsurefire.failIfNoSpecifiedTests=false test
  ```
- **Hypothesized cause**: the frozen patch records class additions only. Its presence suppresses
  replay for the whole class, silently dropping its member additions.
- **Investigations**:
  - 2026-10-09: RED after `scanClasspath()` enabled the processed-archive path: the enhanced field
    was null. An explicit-class-only bootstrap misleadingly passed by replaying the extension.
    The regression now asserts that no enhancement runs at boot.
  - 2026-10-09: one shared collector writes class, field, method and parameter additions for both
    APT and the Maven plugin. Member keys include method descriptors and parameter positions;
    annotation frames retain every member value and the old class-only formats still read.
    Boot restores the member configs before descriptor enhancement. A member-only patch also
    suppresses replay without needing an added class annotation.
  - 2026-10-09: GREEN for promoted and already-scoped beans, private initializer invocation,
    unchanged source parameter qualifiers, added constructor/field qualifier members and member
    target round trips. The frozen format still records additions, not annotation removals; that
    pre-existing limitation is outside this change.
  - 2026-10-09: verified on Java 25.0.4-tem / Maven 3.9.16: clean Vauban install (981 non-TCK
    tests, zero failures/errors, two existing skips), full CDI Lite 774/774 without new exclusions,
    AtInject runner green, and the actual Mansart `ContainerModuleTest` 4/4 after a clean build.

## BUG-20261010-01 — Default SE discovery reads every `beans.xml` as `bean-discovery-mode="all"`

- **Date**: 2026-10-10
- **Status**: FIXED (branch `fix/beans-xml-discovery-mode`, vauban#141)
- **Module**: `vauban-core` (`ContainerScanner.scanBeanArchivesFromClasspath`)
- **Symptom**: `SeContainerInitializer.newInstance().initialize()` with no class or package added
  makes every concrete class of an archive with a `META-INF/beans.xml` a bean, whatever the file
  says. An `annotated` or empty `beans.xml` (annotated since CDI 4.0) over-discovers plain helper
  classes; a `none` archive is not skipped. Found on 2026-10-09 while comparing Vauban with
  Hypospray, right after `beans.xml` (`annotated`) files were added to five bricks for portability
  without Vauban (Vidocq/vidocq-workspace#15). No brick and no Vidocq runtime path uses this
  discovery; only SE applications do.
- **Minimal reproduction**: an archive with `beans.xml` declaring `bean-discovery-mode="annotated"`,
  a `@Dependent` class and a class with no annotation; `initialize()` with that archive's class
  loader resolves both. `BeanArchiveDiscoveryModeTest` covers `all`, `annotated` (directory and
  jar), `none`, an empty file, a file without the attribute and a commented-out attribute; on
  `main` six of seven fail (`PlainFixture is a bean ==> expected: <false> but was: <true>`).
- **Cause**: the scanner never read the file; it forced every class of the archive through
  discovery (`forcedDiscoveryClasses`).
- **Fix**: `ContainerScanner.BeanDiscoveryMode` reads the attribute (XML comments stripped; no
  attribute = `annotated`; another value is a `DeploymentException`). `none` skips the archive,
  `all` keeps forcing, `annotated` adds the classes unforced so discovery keeps only those with a
  bean-defining annotation.
- **Verification**: clean install with `-Ptck`: 1821 tests, 0 failures, 2 existing skips; CDI Lite
  TCK 774/774; AtInject TCK green.

## BUG-20261010-02 — An intercepted bean in a non-exported package fails at creation

- **Date**: 2026-10-10
- **Status**: FIXED (branch `fix/intercepted-init-without-exports`)
- **Module**: `vauban-core` (`InterceptorBeanWrapper`, the `create` of the intercepted-subclass
  bean, around line 845)
- **Symptom**: in a Vidocq application whose module exports and opens nothing (what
  `vidocq create -x heisenberg-fault-tolerance` scaffolds), the first use of a bean with a
  `@Retry` method throws `CreationException: IllegalAccessException: class
  io.vidocq.vauban.core.container.InterceptorBeanWrapper$2 (in module io.vidocq.vauban.core)
  cannot access class io.example.ft.only.Flaky$$Intercepted (in module io.example.ft.only)
  because module io.example.ft.only does not export io.example.ft.only to module
  io.vidocq.vauban.core`. Adding `exports io.example.ft.only;` makes it work. Any interceptor
  binding is affected, not only Fault Tolerance. Found on 2026-10-10 while checking heisenberg
  BUG-005 end to end.
- **Minimal reproduction**: `vidocq create --name ftonly -g io.example -x heisenberg-fault-tolerance`,
  an `@ApplicationScoped` bean with a `@Retry` method, `main` calling it through
  `CDI.current().select(...)` after `start()`; add `requires jakarta.cdi; requires jakarta.inject;
  requires microprofile.fault.tolerance.api;` and no `exports`; `mvn package vidocq:run`.
- **Cause**: the subclass instance is created in-module through the generated `_VaubanComponents`
  (`instantiatePreferProvider`), but its post-construction wiring is then called with
  `finalInterceptedClass.getMethod(InterceptedShape.INIT_METHOD, ...).invoke(...)`. A reflective
  call to a public method needs the package to be exported to `io.vidocq.vauban.core`. The tests
  that cover interception on a module path export their package
  (`heisenberg-cdi-vauban-module-it`), and the REST scaffold adds an unqualified `opens`, so
  neither hits it.
- **Fix candidates**: have the generated `$$Intercepted` implement a Vauban interface carrying
  `$$init` (an interface call needs no export), or route `$$init` through the generated provider
  like the constructor; keep the reflective call as the fallback for subclasses generated before.
  Both generators (APT and Class-File) emit the subclass.
- **Fix**: `InterceptorBeanWrapper.initIntercepted` calls `$$init` through a method handle found
  with `ModuleLookups.lookupFor(beanClass)`, the full-privilege lookup granted by the generated
  `_VaubanComponents` of the bean's package (same module: no exports, no opens), at all three
  call sites; reflection stays the fallback when no provider grants a lookup. No generator
  change: subclasses already generated are wired the same way.
- **Verification**: `NonExportedInterceptedModulePathTest` (`vauban-module-it`, a `@Dependent`
  intercepted bean in the non-exported package `io.vidocq.vauban.moduleit.internal`) red on `main`
  with the same `IllegalAccessException`, green now. Clean install with `-Ptck`: 1822 tests,
  0 failures, 2 existing skips; CDI Lite TCK 774/774; AtInject TCK green. The Fault Tolerance
  application with no `exports` runs its `@Retry` bean under `vidocq:run` and in a jlink image
  (with the scaffold fix of vidocq BUG-20261010-01).

## BUG-20261010-03 — A bean method that declares a checked exception breaks the generated provider

- **Date**: 2026-10-10
- **Status**: FIXED (branch `fix/145-checked-exceptions`, vauban#145)
- **Module**: `vauban-processor` (`ComponentProviderGenerator`, `BeanFactorySourceRenderer`,
  `ClientProxySourceRenderer`, `InterceptedSourceRenderer`)
- **Symptom**: a bean whose observer declares `throws Exception` does not compile once the
  processor runs: `_VaubanComponents.java:[21,68] unreported exception java.lang.Exception; must
  be caught or declared to be thrown`. CDI allows checked exceptions on constructors,
  initializers, observers, producers and disposers. Found while writing a small Vidocq
  application for vidocq-workspace#15.
- **Minimal reproduction**: an `@ApplicationScoped` bean with
  `void onStart(@Observes @Initialized(ApplicationScoped.class) Object e) throws Exception`,
  compiled with `vauban-processor` on the processor path.
- **Cause**: the generated source calls bean methods and constructors directly, from methods
  that declare no checked exception (`VaubanComponentProvider#create`, `#invoke`,
  `#createClientProxy`, `BeanFactory#create`), and the client-proxy and intercepted-subclass
  constructors call a throwing super constructor without declaring it. The Class-File path is
  not affected: bytecode has no checked exceptions.
- **Fix**: `_VaubanComponents` and `_Factory` wrap those bodies in
  `try { … } catch (Throwable t) { throw sneaky(t); }`, a generic helper that rethrows the
  exception unchanged; the `_ClientProxy` and `$$Intercepted` constructors declare
  `throws java.lang.Throwable`. The caller sees the exception the bean method threw, unwrapped,
  as `VaubanComponentProvider#invoke` documents.
- **Verification**: `ComponentProviderCompileTimeTest#beanMethodsDeclaringCheckedExceptionsCompile`
  (throwing `@Inject` and no-arg constructors, initializer, observer, producer; the observer's
  `IOException` and a constructor's `IOException` reach the caller unwrapped) and
  `#interceptedBeanWithThrowingConstructorsCompiles`: red without the fix, green now. Clean
  install green; CDI Lite TCK 774/774; AtInject TCK green. The original Vidocq application, with
  its `throws Exception` observer, compiles and runs under `vidocq:run`.

## BUG-20261010-04 — A context registered by an extension is lost when the application is compiled with the processor

- **Date**: 2026-10-10
- **Status**: FIXED (branch `feat/webcontexts-session`)
- **Module**: `vauban-core` (`VaubanContainerBuilder`), `vauban-processor`
- **Symptom**: a build-compatible extension registers a context with
  `MetaAnnotations.addContext(scope, normal, contextClass)`; in an application compiled with the
  Vauban annotation processor, every bean of that scope fails at its first use with
  `ContextNotActiveException: No context registered for scope ...`. Found with
  `vauban-webcontexts` (`@SessionScoped`) on Foy (Vidocq/foy#21). With
  `VaubanContainer.builder().scanClasspath()` on classes the processor never saw, it works.
- **Minimal reproduction**: compile a `@SessionScoped` bean and a servlet with the processor and
  `vauban-webcontexts` on the processor path, serve them with `FoyChappeBoot` and Vauban's
  `BeanManager`, call the servlet (`FoyWebExtensionTest#sessionScopedThroughFoyChappeBoot` in
  `foy-cdi-vauban`).
- **Cause**: when every source of the deployment was processed at build time, the builder skips
  the extensions at boot (`allSourcesProcessed`), and the processor wrote down the result of every
  other phase (`vauban-enhancements.properties`, `vauban-synthetic-metadata.properties`) but not the
  contexts `@Discovery` registered, which are runtime objects. Nothing installed them.
- **Fix**: the processor writes them to `META-INF/vauban-contexts.list`
  (`<scope>;<context class>;<normal>`); the builder reads every such list of the deployment and
  installs those contexts next to the ones registered by extensions run at boot, without
  duplicates (`FrozenContexts`).
- **Verification**: `BceRuntimeListCompileTimeTest$FrozenContexts` (the list is written, and only
  when a context is registered) and `FrozenContextsTest` (the lists are read; the container installs
  a frozen context with no extension to run): red without the fix, green now. The Foy test above
  green. Clean install green; CDI Lite TCK 774/774; AtInject TCK green.

## BUG-20261010-05 — No RequestContextController, no @ActivateRequestContext; the imperative request context shared by every thread

- **Date**: 2026-10-10
- **Status**: FIXED (branch `fix/147-request-context-controller`, vauban#147)
- **Module**: `vauban-core` (`RequestContext`, `VaubanBeanManager`, `VaubanContainerBuilder`)
- **Symptom**: a `@RequestScoped` bean used from a servlet under Foy and Vauban fails with
  `ContextNotActiveException: RequestScope is not active`. Foy activates the request context
  through the standard `RequestContextController`, which Vauban did not provide, so the lookup
  found nothing and nothing was activated. `@ActivateRequestContext` had no effect either. Found
  while working on Vidocq/foy#21.
- **Minimal reproduction**: inject `RequestContextController` into any bean: unsatisfied
  dependency. Or a `@RequestScoped` bean injected into a `@WebServlet`, served by `FoyChappeBoot`
  with Vauban's `BeanManager`.
- **Cause**: both are CDI Lite (CDI 4.1 §6.5.8, §6.5.9), but the TCK tests them in its `se` group,
  which the runner does not run, so nothing caught their absence. Separately, `RequestContext`
  kept the state of an imperative `activate()` in an instance field every thread shared.
- **Fix**: a `@Dependent` built-in `RequestContextController` bean; a built-in
  `@ActivateRequestContext` interceptor at `PLATFORM_BEFORE + 100`, registered when a class of the
  deployment uses the binding; the imperative state per thread; the `@Initialized`,
  `@BeforeDestroyed` and `@Destroyed(RequestScoped.class)` events, fired only when observed.
- **Verification**: `RequestContextControllerTest` (7) and `ActivateRequestContextTest` (4), after
  the TCK's `ActivateRequestContextProgrammaticallyTest` and `ActivateRequestContextByInterceptorTest`,
  plus one request context per thread: red before, green now. Foy's
  `FoyWebExtensionTest#requestScopedThroughFoyChappeBoot` (an application compiled with the
  processor, 8 concurrent requests, one instance each): red before, green now. Clean install
  green; CDI Lite TCK 774/774; AtInject TCK green.

## BUG-20261013-01 — VaubanClassLoader defines classes without a code source

- **Date** : 2026-10-13
- **Statut** : FIXED (branch fix/classloader-code-source, vauban#153)
- **Module touché** : vauban-classloader / VaubanClassLoader.java
- **Symptôme** : every class the loader defines, placed proxies included, has a
  `CodeSource` whose location is `null`. Code that finds where a class comes from fails
  inside a Vauban layer. Foy skips the jar of a `ServletContainerInitializer` and calls it
  with no `@HandlesTypes` classes. Vaadin then fails with "ServletContainerInitializer is
  called but the provided set of classes is 'null'".
- **Reproduction minimale** : `VaubanClassLoader.of(List.of(dir), parent, PluginContext.empty())`,
  then `Class.forName(name, true, loader).getProtectionDomain().getCodeSource().getLocation()`
  returns `null` (`VaubanClassLoaderTest.classesReportTheirArchiveAsCodeSource`,
  `PlacedProxyTest.placedProxyReportsTheProducedTypesArchive`).
- **Hypothèse de cause** : `findClass` and `placeClass` call `defineClass(name, bytes, 0, len)`
  without a `ProtectionDomain`.
- **Investigations** :
  - 2026-10-13 : found in the Vaadin-on-Foy spike, with the Vaadin jars moved into the
    application layer. Fixed with one `ProtectionDomain(CodeSource(archive location))` per
    archive. A placed proxy takes the domain of the archive that owns its package.
  - Left as is: `VaubanContainerBuilder.isClassFromBceProcessedSource` compares the
    `vauban:file:…!/` root of a marker resource with the `file:` code source. It never matches
    for layer classes, as before this fix; making it match is a separate change.

## BUG-20261013-02 — The layer loader ignores the versioned entries of a multi-release jar

- **Date** : 2026-10-13
- **Statut** : FIXED (vauban#154)
- **Module touché** : vauban-classloader / BuiltInReaders.JarReader
- **Symptôme** : in a Vauban layer, a multi-release jar defines its base classes and resources, never the
  `META-INF/versions/N/` ones; a class that exists only there is not found.
- **Reproduction minimale** : `MultiReleaseJarTest.versionedEntriesWin`.
- **Hypothèse de cause** : `new JarFile(File)` is not version-aware, and `classEntries()` listed raw names.
- **Investigations** :
  - 2026-10-13 : found while designing vidocq#247 (every library in the application layer). Fixed by opening
    the jar for `Runtime.version()` and listing `versionedStream()`.

## BUG-20261013-03 — Threads sharing one request each create a `@RequestScoped` bean

- **Date**: 2026-10-13
- **Status**: FIXED fae281ef (`fix/request-context-concurrent-create`, Vidocq/vauban#280)
- **Module**: `vauban-core` (`RequestContext`)
- **Surfaced by**: the concurrency review of Tassis M9 (`tassis-cdi-vauban`), 2026-10-10.
- **Symptom**: when several threads work inside the same request, two of them using the same
  `@RequestScoped` bean for the first time each create an instance and each work on their own.
  One instance is kept in the context and destroyed with the request; the other never gets its
  `@PreDestroy`. Two simultaneous writes can also corrupt the map of instances (not observed).
- **When a request is shared**: `runInScope` binds the request state to a `ScopedValue`, and the
  subtasks forked in a `StructuredTaskScope` inherit that binding, so they all reach the same
  `RequestContextState`. `StructuredTaskScope` is a preview API in Java 25: only an application
  run with `--enable-preview` is concerned. A request activated imperatively is not concerned
  either: since vauban#147 (BUG-20261010-05) its state is per thread.
- **Minimal reproduction**: a `@RequestScoped` bean `Slow` counting its `@PostConstruct` and
  `@PreDestroy` calls, and two subtasks calling it through its client proxy:

  ```java
  var slow = container.select(Slow.class);
  container.requestContext().runInScope(() -> {
      try (var scope = StructuredTaskScope.open()) {
          var a = scope.fork(slow::id);
          var b = scope.fork(slow::id);
          scope.join();
          // a.get() and b.get() differ; 2 @PostConstruct, 1 @PreDestroy
      }
  });
  ```

  Not added to the test suite: the build does not enable preview features.
- **Cause**: `RequestContext.get(Contextual, CreationalContext)` reads `state.instances`, then
  creates the instance and puts it, with nothing held between the read and the write;
  `instances` is a plain `HashMap`. `destroy` and `deactivate` use the same map unguarded.
- **Investigations**:
  - 2026-10-13: the code on `main` `56953110` matches the description above. A lock per request
    state would have to be reentrant, as creating a bean can resolve another bean of the same
    scope (which also rules out `ConcurrentHashMap.computeIfAbsent`). `ApplicationContext` was
    not checked for the same pattern.
  - 2026-10-13: reproduced on `main` `56953110`, Temurin 25, `--enable-preview`, with a
    stand-alone class on the class path of `vauban-core`. With `@PostConstruct` holding each
    thread until both are creating: 2 instances created, 1 destroyed, the two subtasks got
    different ids. Without any forcing: 1972 rounds out of 2000 created two instances.
- **Fix** (2026-11-07, Vidocq/vauban#280): each `RequestContextState` carries a `ReentrantLock` guarding
  `get` (lookup, create, put), `destroy`, `activate` and `deactivate`; `active` is `volatile`. Reentrant
  because creating a bean can resolve another bean of the same scope. The regression test
  `RequestContextConcurrencyTest` needs no preview feature: the package-private
  `RequestContext.inCurrentRequest` binds plain virtual threads to the request. Red before (2 instances),
  green after. `ApplicationContext` was not audited here.


## BUG-20261028-01 — A custom scope only the bean's class loader sees becomes `@Dependent`

- **Date** : 2026-10-28
- **Statut** : FIXED (0150bd7b, branch `perf/227-generated-hot-path`)
- **Module touché** : vauban-core / `ManagedBean.getScope()` (`scopeAnnotationClass`)
- **Symptôme** : `getScope()` loads the scope annotation with the one-argument
  `Class.forName(name)`, that is through `vauban-core`'s own loader, and falls back on
  `@Dependent` on `ClassNotFoundException` without a word. A custom scope declared in an
  application module layer that `vauban-core`'s loader cannot see is reported as `@Dependent`,
  so the client proxy looks up the `@Dependent` context instead of the scope's own. The same
  call runs on every client-proxy invocation: about 95 % of `clientProxyCall` in
  BENCH-20261028-01 (276 ns for a call that does nothing) is spent in `Class.forName`.
- **Reproduction minimale** :
  ```
  ./mvnw -ntp test -pl vauban-core -Dtest=ManagedBeanScopeTest
  # scopeVisibleOnlyToTheBeanLoader: expected <...isolated.IsolatedScope> but was <jakarta.enterprise.context.Dependent>
  ```
- **Hypothèse de cause** : the scope class is resolved by name on each call, through the
  caller's loader instead of the bean's (`ManagedBean.classLoader`), and never cached.
- **Investigations** :
  - 2026-10-28 : found while profiling the client proxy for vauban#227 (`-prof stack`).
    Confirmed by `ManagedBeanScopeTest` (red). About twenty other one-argument
    `Class.forName` calls remain in `vauban-core` (discovery, `InterceptorManager`,
    `VaubanInterceptor`, `QualifierResolver`, ...): same loader assumption, to review
    separately.
  - 2026-10-28 : fixed in 0150bd7b: the scope is resolved once, at construction, through the
    bean's loader (falling back on `vauban-core`'s for the built-in scopes). `ManagedBeanScopeTest`
    green; `clientProxyCall` 276 ns → 5.1 ns (BENCH-20261028-02).
  - 2026-10-28 : the other one-argument calls reviewed (branch `fix/227-class-for-name-loaders`).
    Fifteen methods loaded a deployment class through `vauban-core`'s loader: discovery
    (`BeanDiscovery.mapScope`, `typedBeanTypes`, `extractNameWithStereotypesFromAnnotations`,
    `StereotypeResolver` x2, `ObserverDisposerDiscovery`), `DeploymentValidator`, the BCE
    `AnnotationBuilderFactory` and synthetic observer builder, and `VaubanInterceptor` (its class and
    its binding types). They now go through `DeploymentClasses.load` (context loader of the build,
    the composite deployment loader) or, for `VaubanInterceptor` created after the build, the
    container's bean class loader; four hand-written copies of `DeploymentClasses.load` were folded
    into it. Red then green: `BeanDiscoveryReflectionFallbackTest.scopeOnlyTheDeploymentLoaderSees`
    (`mapScope` returned `null`), `ContainerBestEffortPathsTest.interceptorClassOnlyTheContainerLoaderSees`
    (`IllegalArgumentException`), and `SingleArgumentForNameTest`, a bytecode check that only the
    deliberate fallbacks it lists still call `Class.forName(String)`.

## BUG-20261028-02 — The two producer-proxy eligibility checks disagree on overrides, `finalize()`/`clone()` and generic overrides

- **Date** : 2026-10-28
- **Statut** : FIXED (branch `fix/227-producer-proxy-eligibility-parity`)
- **Module touché** : `vauban-core` `io.vidocq.vauban.core.proxy.ProducerProxyEligibility` (used by the Maven plugin) and `vauban-processor` `io.vidocq.vauban.processor.codegen.proxy.ProducerProxyEligibility` (used by the processor)
- **Symptôme** : the same produced type gets a different verdict depending on which copy judges it, so a proxy for it is placed by one build path and refused by the other:
  - **E1** — a `protected` method a subclass overrides as `public`: core judged the overridden method too (`PROTECTED_VIRTUALS`), the processor judged only the override (`ELIGIBLE`).
  - **E2** — a protected `finalize()` or `clone()` override: core exempted them, as no proxy forwards them (`ELIGIBLE`); the processor did not (`PROTECTED_VIRTUALS`).
  - **E3** — `echo(String)` overriding an inherited `protected echo(T)` with `T = String`: the processor keyed methods on unerased declared types, saw two methods and judged the overridden one (`PROTECTED_VIRTUALS`).
- **Reproduction minimale** :
  ```
  ./mvnw -pl vauban-processor test -Dtest='ClientProxyInheritanceCrossCheckTest#overridesAndUnforwardedMethodsAreEligible'
  # fixtures WidenedHookChild (E1), FinalizingCloneable (E2), StringEchoChild (E3)
  ```
- **Hypothèse de cause** : the rule is written twice (vauban#227, duplication), and the parity test (`ProducerProxyEligibilityParityTest`) compares only the two `Reason` enums, not verdicts; the verdict cross-check covered none of these shapes.
- **Investigations** :
  - 2026-10-28 : found by the vauban#227 duplication survey; each fixture red on the old code (E1 on the bytecode path, E2 and E3 on the source path). Both copies now follow one rule: walking upwards, the most derived override of a signature decides (core: erased signature, a bridge marking the signature it stands for; processor: the signature as a member of the type, `InterceptedShapeFromElements.memberSignatureKey`); `private` and `static` methods override nothing; `finalize()` and `clone()` are never forwarded. The rules are to be merged into one over a shared type view (next step of vauban#227).

## BUG-20261028-03 — A final method hidden behind a protected one makes an unproxyable produced type look placeable

- **Date** : 2026-10-28
- **Statut** : FIXED (branch `refactor/227-type-view`)
- **Module touché** : `vauban-core` `io.vidocq.vauban.core.proxy.ProducerProxyEligibility` (and its former processor copy)
- **Symptôme** : the methods verdict was the first obstacle met, in the order the model lists the methods. A type holding a `protected` method listed before a `public final` one got `PROTECTED_VIRTUALS` instead of `FINAL_VIRTUALS`; `PROTECTED_VIRTUALS` is placeable, so a co-located proxy was planned for a type CDI 4.1 §3.10 declares unproxyable. Likewise a package-private method after a protected one was reported as `PROTECTED_VIRTUALS`.
- **Reproduction minimale** :
  ```
  ./mvnw -pl vauban-core test -Dtest=ProducerProxyEligibilityVerdictTest
  # fixtures FinalAndProtected, ProtectedAndPackagePrivate
  ```
- **Hypothèse de cause** : early return on the first non-public method, with no ordering between obstacles; `getDeclaredMethods()` and `getEnclosedElements()` list methods in different orders, so the two build paths could also disagree.
- **Investigations** :
  - 2026-10-28 : both fixtures red on the old code (`PROTECTED_VIRTUALS` for each). The rule now keeps the worst verdict (final, then package-private, then protected), whatever the order; it is written once, over `TypeView` (`ClassTypeView` for a `Class`, `ElementTypeView` for a `TypeElement`), and the processor copy and its enum parity test are gone (vauban#227).

## BUG-20261028-04 — The super constructor of a client proxy depends on the order a model lists the constructors in

- **Date** : 2026-10-28
- **Statut** : FIXED (branch `refactor/227-super-constructor`)
- **Module touché** : `vauban-core` `RuntimeClientProxyGenerator`, `vauban-processor` `ClientProxyShapeFromElements`
- **Symptôme** : with two non-private constructors of the same arity, the proxy chained to the first one listed: declaration order for the processor, `getDeclaredConstructors()` order (unspecified) for the bytecode path. `(String)` declared before `(Integer)` gave `(String)`, the reverse gave `(Integer)`, and the two build paths could pick different constructors, with different side effects on the default values passed.
- **Reproduction minimale** :
  ```
  ./mvnw -pl vauban-core test -Dtest=SuperConstructorTest
  # fixtures StringFirst, IntegerFirst
  ```
- **Hypothèse de cause** : the selection was written twice, each keeping the first candidate of the lowest arity.
- **Investigations** :
  - 2026-10-28 : `SuperConstructorTest` red on the old code (`StringFirst` chained to `(String)`). One rule now, `ClientProxyShape.superConstructor(TypeView)`: the `(ProxyLink)` constructor, else the fewest parameters, ties broken on the canonical parameter type names; `ProducerProxyEligibility`, `RuntimeClientProxyGenerator` and `ClientProxyShapeFromElements` all call it (vauban#227). `ClientProxyInheritanceCrossCheckTest.sameSuperConstructorOnBothPaths` checks both paths agree.

## BUG-20261028-05 — The processor's client proxies forward other methods than the run-time and Maven plugin ones

- **Date** : 2026-10-28
- **Statut** : FIXED (branch `refactor/227-forwarded-methods`)
- **Module touché** : `vauban-processor` `ClientProxyShapeFromElements` (`from`, `fromColocated`), `vauban-core` `RuntimeClientProxyGenerator`
- **Symptôme** : for the same bean, the proxy the processor writes and the one the Maven plugin or the run-time fallback generates forward different methods:
  - **F1** — a bean's source proxy forwarded inherited methods only when public: a protected or package-private method of a superclass in the bean's own package was left out, so a class of that package calling it on the proxy ran the superclass body against the proxy's empty state. The bytecode proxies forward it.
  - **F2** — the co-located shape judged an inherited package-private method by its declaring package alone: for `A(p) <- B(q) <- C(p)` it forwarded `A`'s method, which `C` does not inherit (JLS 8.4.8).
  - **F3** — the processor's proxies stopped below `java.lang.Object`: `toString()`, `hashCode()` and `equals(Object)` ran on the proxy, while the bytecode proxies forward them to the contextual instance.
  - **F4** — a declared protected `clone()` or `finalize()` was forwarded by the processor's proxies, never by the bytecode ones.
- **Reproduction minimale** :
  ```
  ./mvnw -pl vauban-processor test -Dtest='ClientProxyInheritanceCrossCheckTest#sourceShapeForwardsSamePackageInheritedMembers+packagePrivateIsNoMemberAcrossAnotherPackage+objectMethodsAreForwardedOnEveryPath'
  ```
- **Hypothèse de cause** : the class-method selection was written three times (`forwardedClassMethods`, `fromColocated`, `RuntimeClientProxyGenerator.addDeclaredMethods`), each with its own walk and predicates (vauban#227).
- **Investigations** :
  - 2026-10-28 : F1, F2 and F3 red on the old code. One rule now, `ForwardedMethods.select(TypeView, Placement)` in `vauban-core`: the walk includes `Object`; in the type's package every member is forwarded (a package-private one when inherited, JLS 8.4.8), an inherited protected one of another package through a `MethodHandle`; in another package, public methods only. Java source cannot forward through a `MethodHandle`, so such a bean proxy is now emitted as bytecode by the processor (`UnnameableMemberBytecodeEmissionTest#methodHandleForward`, red before: the source proxy left `hook()` out).

## VAU-INT-011 — Interceptor methods are called by reflection: a non-public one needs its package opened
- **Date**: 2026-10-31 — **Status**: FIXED on `feat/generated-interceptor-invokers` (`PackagePrivateInterceptorModulePathTest`)
- **Severity**: medium (the first intercepted call fails with an `IllegalAccessException` on the strict module path,
  unless the application opens the interceptor's package to `io.vidocq.vauban.core`)

### Symptom
The Vidocq tutorial (finding R3 of the vidocq-tutorial session) has `AuditedInterceptor` in `com.example.lib.support`,
with a package-private `@AroundInvoke Object audit(InvocationContext)`. Without `opens com.example.lib.support;`,
`GET /books` answered 500: `IllegalAccessException: class …VaubanInvocationContext$InterceptorInvocation (in module
io.vidocq.vauban.core) cannot access class com.example.lib.support.AuditedInterceptor … does not export
com.example.lib.support to module io.vidocq.vauban.core`.

### Repro
An application module with an interceptor whose `@AroundInvoke` is not public, in a package the module neither
opens nor exports; call an intercepted method.

### Cause
The target method of an intercepted bean has a generated `TargetInvoker` (no reflection), but the interceptor chain
does not: `InterceptorManager` builds each `VaubanInvocationContext.InterceptorInvocation(target, Method)` from the
`java.lang.reflect.Method`, and `InterceptorInvocation.invoke` / `VaubanInvocationContext.proceedAt` (and
`VaubanInterceptor.intercept`, the SPI path) call it with `Method.invoke` after `VaubanLookup.makeAccessible`. That
needs only a read edge for a public method of a public class in an exported package, and deep access (`opens`)
otherwise. The lifecycle callbacks of interceptors (`addLifecycleInvocations`) go the same way.

### Fix
The processor's `_VaubanComponents` now lists the interceptor methods of its package among the methods it invokes:
`ComponentCollector` collects `@AroundInvoke` and `@AroundConstruct` methods next to the `@PostConstruct` /
`@PreDestroy` ones it already took (an interceptor's lifecycle callbacks among them), private ones excepted.
`VaubanInvocationContext.InterceptorInvocation.invoke` asks that provider first (`VaubanLookup.invokeGenerated`, which
returns `NOT_INVOKED` when no provider owns the method) and keeps `Method.invoke` as the fallback for the interceptors
the processor did not see; the interceptor chain, the target-class callbacks of an `@AroundConstruct` chain and
`VaubanInterceptor.intercept` all go through it. What an interceptor method throws still reaches the caller
unchanged. `CodegenCoverage` reports these calls as covered when the provider declares them.

Tests: `PackagePrivateInterceptorModulePathTest` (module path, package neither exported nor opened: `@AroundConstruct`,
the interceptor's `@PostConstruct` and `@AroundInvoke`, plus a checked exception through the chain) failed with the
tutorial's `IllegalAccessException` before the fix; `ComponentCollectorMemberSelectionTest.packagePrivateInterceptorMethodsAreCollected`;
`CodegenCoverageTest.interceptorAroundInvokeDeclaredIsCovered`. Vidocq's `vidocq:check-module-info` still asks for the
`opens` (Vidocq BUG-20261031-04) until it can tell a Vauban release with this fix on the module path.
