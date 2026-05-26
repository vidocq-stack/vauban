# CDI TCK 4.1 Results - Vauban 0.1.0-SNAPSHOT

## Date: 2026-03-30

## Results (CDI Lite)

| Metric | Value |
|----------|--------|
| CDI Lite tests | ~769 (non-skipped) |
| **Passed** | **~404 (52.5%)** |
| Failed | ~365 |
| Errors | 0 |
| Time | ~5s |

## Progression

```
Start     ██████░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░   ~12%
Session 1 ██████████████████████████████████████░░░░░░  38.2% (+200)
Session 2 ████████████████████████████████████████████████████ 52.5% (+113)
```

## Major features
- Runtime interceptors (Class-File API @AroundInvoke)
- Runtime client proxies (Class-File API, normal-scoped beans)
- TypeVariable resolution in the generic hierarchy
- EventMetadata injection in observer methods
- @Repeatable qualifiers

## Command
```bash
mvn install -DskipTests -q && mvn test -pl vauban-tck-runner -Ptck
```
