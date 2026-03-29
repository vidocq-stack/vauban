# CDI TCK 4.1 Results - Vauban 0.1.0-SNAPSHOT

## Date : 2026-03-29

## Resultats actuels

| Metrique | Valeur |
|----------|--------|
| Tests total | 1912 |
| **Passes** | **277 (23.3% des non-skippes)** |
| Echoues | 914 |
| Skippes | 721 |
| Erreurs | 0 |
| Temps | ~4s |

## Progression depuis le debut

| Etape | Failures | Passes | Cumul |
|-------|----------|--------|-------|
| Baseline (adaptateur seul) | 1228 | ~90 | - |
| + Events, Producers, Intercepteurs | 1200 | ~118 | +28 |
| + Instance, BeanManager | 1165 | ~153 | +63 |
| + Validation deploiement | 1038 | ~162 | +72 |
| + Lifecycle, Enricher, Qualifiers | 989 | ~202 | +112 |
| + Built-in beans, Producer types | 950 | ~241 | +151 |
| + Stereotypes, Event valid, Alt/Stereo | 913 | ~278 | +188 |
| **+ Instance valid, Literals** | **914** | **~277** | **+187** |

**Total : 1228 -> 914 = -314 failures**

## Commande
```bash
mvn test -pl vauban-tck-runner -Ptck
```

## Tests propres Vauban : ~215 (tous verts)

## Pour atteindre 25% (~298 passes)
- InjectionPoint metadata (~21 tests)
- Client proxy runtime pour normal-scoped (~5 tests)
- Qualifier inheritance (@Inherited) (~3 tests)
- Fine-tuning getBeans() pour edge cases

## Pour atteindre 30% (~358 passes)
- Interceptor runtime wrapping (~60 tests)
- Dependent context lifecycle tracking (~13 tests)
- Advanced event handling (async, conditional) (~20 tests)
