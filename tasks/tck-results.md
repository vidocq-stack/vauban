# CDI TCK 4.1 Results - Vauban 0.1.0-SNAPSHOT

## Date : 2026-03-30

## Resultats (CDI Lite)

| Metrique | Valeur |
|----------|--------|
| Tests CDI Lite | ~769 (non-skippes) |
| **Passes** | **~379 (49.2%)** |
| Echoues | ~390 |
| Erreurs | 0 |
| Temps | ~5s |

## Progression

```
Debut     ██████░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░   ~12%
Session 1 ██████████████████████████████████████░░░░░░  38.2% (+200)
Session 2 ████████████████████████████████████████████  49.2% (+88)
```

## Commande
```bash
mvn install -DskipTests -q && mvn test -pl vauban-tck-runner -Ptck
```
