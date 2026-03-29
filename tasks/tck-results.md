# CDI TCK 4.1 Results - Vauban 0.1.0-SNAPSHOT

## Date : 2026-03-29

## Resultats (CDI Lite)

| Metrique | Valeur |
|----------|--------|
| Tests CDI Lite | 761 (non-skippes) |
| **Passes** | **~291 (38.2%)** |
| Echoues | ~470 |
| Erreurs | 0 |
| Temps | ~3s |

## Progression

```
Debut     ██████░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░   ~12% (estimé)
Final     ██████████████████████████████████████░░░░░░  38.2%
```

## Commande
```bash
mvn test -pl vauban-tck-runner -Ptck
```

## Tests propres Vauban : ~225 (tous verts)

## Note
- CDI Full exclus (groupe cdi-full) — sera dans vauban-full
- Score varie de 38.0% a 38.6% entre runs (pollution etat inter-tests)
- ~70 tests interceptors runtime non-supportes
- ~25 tests invokers CDI 4.1 non-supportes
