# CDI TCK 4.1 Results - Vauban 0.1.0-SNAPSHOT

## Date : 2026-03-30

## Resultats (CDI Lite)

| Metrique | Valeur |
|----------|--------|
| Tests CDI Lite | ~769 (non-skippes) |
| **Passes** | **~349 (45.4%)** |
| Echoues | ~420 |
| Erreurs | 0 |
| Temps | ~5s |

## Progression

```
Debut     ██████░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░   ~12%
Session 1 ██████████████████████████████████████░░░░░░  38.2% (+200)
Session 2 ████████████████████████████████████████████  45.4% (+58)
```

## Changements Session 2

- Observer qualifiers (getObservedQualifiers, event qualifier matching)
- Instance<T> qualifier-aware (select/get/iterator)
- QualifierUtils extraction de ManagedBean
- ClassValidator : ~15 validations DefinitionException
- Validations par reflexion (raw Event/Instance, producer types, generic beans)
- VaubanTestEnricher : qualifiers, resolution via BeanManager
- VaubanDeployableContainer : extraction JARs imbriques (WEB-INF/lib)
- @Any qualifier fix dans computeQualifiers
- isMatchingBean/Event : qualifier validation, @Default/@Any handling
- Producer/observer exception unwrap (InvocationTargetException)
- Observer reception/transactionPhase + priority
- Observer method injection parameters (multi-param observers)
- Package-level @Vetoed support
- Reflection fallback pour scope/qualifier/stereotype detection
- Custom scope recognition dans hasBeanDefiningAnnotation
- Retrait validation circulaire deployment-time

## Commande
```bash
mvn install -DskipTests -q && mvn test -pl vauban-tck-runner -Ptck
```

## Tests propres Vauban : ~225 (tous verts)
