# CDI TCK 4.1 Results - Vauban 0.1.0-SNAPSHOT

## Date : 2026-03-30

## Resultats (CDI Lite)

| Metrique | Valeur |
|----------|--------|
| Tests CDI Lite | 761 (non-skippes) |
| **Passes** | **~330 (43.3%)** |
| Echoues | ~431 |
| Erreurs | 0 |
| Temps | ~3s |

## Progression

```
Debut     ██████░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░   ~12%
Session 1 ██████████████████████████████████████░░░░░░  38.2%
Session 2 ████████████████████████████████████████░░░░  40.7%
Session 3 ██████████████████████████████████████████░░  43.3%
```

## Changements Session 3

- VaubanTestEnricher qualifier-aware : extraction qualifiers des champs @Inject
- Validations par réflexion : raw Event/Instance, producer type variable, generic initializer
- DeploymentValidator : beans unproxyables (normal-scoped + final class)
- Corrections faux positifs : generic managed beans, producer List<T> autorisé

## Commande
```bash
mvn install -DskipTests -q && mvn test -pl vauban-tck-runner -Ptck
```

## Tests propres Vauban : ~225 (tous verts)

## Note
- CDI Full exclus (groupe cdi-full)
- ~10 tests BCE extensions non supportes
- ~70 tests interceptors runtime non-supportes
- ~38 tests invokers CDI 4.1 non-supportes
- ~37 tests DefinitionException restants (dont raw Event/Instance = 12)
- Score varie de 42-44% entre runs (pollution etat inter-tests)
- IMPORTANT: `mvn install` requis avant TCK
