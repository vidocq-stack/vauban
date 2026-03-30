# CDI TCK 4.1 Results - Vauban 0.1.0-SNAPSHOT

## Date : 2026-03-30

## Resultats (CDI Lite)

| Metrique | Valeur |
|----------|--------|
| Tests CDI Lite | ~769 (non-skippes) |
| **Passes** | **~404 (52.5%)** |
| Echoues | ~365 |
| Erreurs | 0 |
| Temps | ~5s |

## Progression

```
Debut     ██████░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░   ~12%
Session 1 ██████████████████████████████████████░░░░░░  38.2% (+200)
Session 2 ████████████████████████████████████████████████████ 52.5% (+113)
```

## Fonctionnalites majeures
- Intercepteurs runtime (Class-File API @AroundInvoke)
- Client proxies runtime (Class-File API, normal-scoped beans)
- TypeVariable resolution dans la hierarchie generique
- EventMetadata injection dans les observer methods
- @Repeatable qualifiers

## Commande
```bash
mvn install -DskipTests -q && mvn test -pl vauban-tck-runner -Ptck
```
