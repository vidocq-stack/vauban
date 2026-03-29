# CDI TCK 4.1 Results - Vauban 0.1.0-SNAPSHOT

## Date : 2026-03-30

## Resultats (CDI Lite)

| Metrique | Valeur |
|----------|--------|
| Tests CDI Lite | 761 (non-skippes) |
| **Passes** | **~310 (40.7%)** |
| Echoues | ~451 |
| Erreurs | 0 |
| Temps | ~3s |

## Progression

```
Debut     ██████░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░   ~12% (estimé)
Session 1 ██████████████████████████████████████░░░░░░  38.2%
Session 2 ████████████████████████████████████████░░░░  40.7%
```

## Changements Session 2

- Observer qualifiers : getObservedQualifiers() retourne les vrais qualifiers
- Event qualifier matching : EventDispatcher filtre par qualifiers
- EventImpl.select() propage les qualifiers
- Instance<T> qualifier-aware : select/get/iterator utilisent les qualifiers
- QualifierUtils : extraction de ManagedBean pour reutilisation
- ClassValidator : validations DefinitionException (interceptor scope, producer types, stereotype conflicts, etc.)
- DeploymentValidator : validation unproxyable beans (normal-scoped + final)
- VaubanDeployableContainer : distinction DefinitionException vs DeploymentException

## Commande
```bash
mvn install -DskipTests -q && mvn test -pl vauban-tck-runner -Ptck
```

## Tests propres Vauban : ~225 (tous verts)

## Note
- CDI Full exclus (groupe cdi-full) — sera dans vauban-full
- ~10 tests BCE extensions non supportes
- ~70 tests interceptors runtime non-supportes
- ~25 tests invokers CDI 4.1 non-supportes
- IMPORTANT: `mvn install` requis avant TCK (surefire fork utilise JARs du repo local)
