# CDI TCK 4.1 Results - Vauban 0.1.0-SNAPSHOT

## Date : 2026-03-30

## Resultats (CDI Lite)

| Metrique | Valeur |
|----------|--------|
| Tests CDI Lite | 761 (non-skippes) |
| **Passes** | **~330 (43.3%)** |
| Echoues | ~431 |
| Erreurs | 0 |
| Temps | ~5s |

## Progression

```
Debut     ██████░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░   ~12%
Session 1 ██████████████████████████████████████░░░░░░  38.2% (+200)
Session 2 ████████████████████████████████████████░░░░  40.7% (+19)
Session 3 ██████████████████████████████████████████░░  43.3% (+20)
```

## Changements Session 3

- Observer qualifiers + event qualifier matching
- Instance<T> qualifier-aware
- QualifierUtils extraction
- ClassValidator : ~15 validations DefinitionException
- DeploymentValidator : beans unproxyables
- Validations par reflexion : raw Event/Instance, producer types, generic beans
- VaubanTestEnricher : extraction qualifiers, resolution via BeanManager
- VaubanDeployableContainer : extraction JARs imbriques (WEB-INF/lib)

## Commande
```bash
mvn install -DskipTests -q && mvn test -pl vauban-tck-runner -Ptck
```

## Tests propres Vauban : ~225 (tous verts)

## Prochains quick wins identifies
- Dynamic InjectionPoint (7 tests) — injection InjectionPoint dans beans
- Event fires @Any (3 tests)
- Instance destroy/handle (7 tests)
- Producer field lifecycle (2 tests)
- expected [true] but [false] (56 tests) — divers bugs de matching/resolution
