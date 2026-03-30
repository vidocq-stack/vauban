# CDI TCK 4.1 Results - Vauban 0.1.0-SNAPSHOT

## Date : 2026-03-30

## Resultats (CDI Lite)

| Metrique | Valeur |
|----------|--------|
| Tests CDI Lite | ~769 (non-skippes) |
| **Passes** | **~381 (49.5%)** |
| Echoues | ~388 |
| Erreurs | 0 |
| Temps | ~5s |

## Progression

```
Debut     ██████░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░   ~12%
Session 1 ██████████████████████████████████████░░░░░░  38.2% (+200)
Session 2 ████████████████████████████████████████████  49.5% (+90)
Objectif  █████████████████████████████████████████████ 50.0%
```

## Score oscille entre 49.4% et 49.7% selon les runs (2 tests des 50%)

## Commande
```bash
mvn install -DskipTests -q && mvn test -pl vauban-tck-runner -Ptck
```
