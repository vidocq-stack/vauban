# CDI TCK 4.1 Results - Vauban 0.1.0-SNAPSHOT

## Date : 2026-03-29

## Resultats actuels

| Metrique | Valeur |
|----------|--------|
| Tests total | 1916 |
| **Passes** | **317 (27.1% des non-skippes)** |
| Echoues | 854 |
| Skippes | 745 |
| Erreurs | 0 |
| Temps | ~4s |

## Progression totale (depuis le debut)

**1228 -> 854 = -374 failures, +227 tests passes**

```
Baseline      ████░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░   6.8%
Final         ████████████████████░░░░░░░░░░░░░░░░░░░░░░  27.1%
```

## Commande
```bash
mvn test -pl vauban-tck-runner -Ptck
```
