# CDI TCK 4.1 Results - Vauban 0.1.0-SNAPSHOT

## Date : 2026-03-29

## Resultats actuels

| Metrique | Valeur |
|----------|--------|
| Tests total | 1914 |
| **Passes** | **246 (20.7% des non-skippes)** |
| Echoues | 945 |
| Skippes | 723 |
| Erreurs | 0 |
| Temps | ~4s |

## Progression complete

| Etape | Failures | Passes | Delta |
|-------|----------|--------|-------|
| Baseline (adaptateur) | 1228 | ~90 | - |
| + Events (@Observes) | 1202 | ~116 | +26 |
| + Producers | 1205 | ~113 | -3 |
| + Intercepteurs | 1200 | ~118 | +5 |
| + Instance\<T\> | 1194 | ~124 | +6 |
| + BeanManager enrichi | 1185 | ~133 | +9 |
| + Qualifiers/types/resolve | 1165 | ~153 | +20 |
| + Validation deploiement | 1038 | ~162 | +127 |
| + ClassValidator | 1036 | ~155 | -7 |
| + Lifecycle (@PostConstruct) | 1030 | ~161 | +6 |
| + ParameterizedType | 1027 | ~164 | +3 |
| + TestEnricher + exceptions | 1003 | ~188 | +24 |
| + @Named + stubs | 998 | ~193 | +5 |
| + Built-in beans | 989 | ~202 | +9 |
| + Producer bean types fix | 960 | ~231 | +29 |
| + BM validations | 950 | ~241 | +10 |
| + Disposers + reflection inject | **945** | **~246** | **+5** |

**Total : 1228 -> 945 = -283 failures, +156 tests passes**

## Commande
```bash
mvn test -pl vauban-tck-runner -Ptck
```

## Decomposition des 945 failures restantes

| Categorie | ~ Tests | Note |
|-----------|---------|------|
| CDI Full (extensions, decorators, passivation) | ~350 | Hors scope CDI Lite |
| Interceptors runtime (wrapping) | ~60 | Necessite codegen runtime |
| Method Invokers (CDI 4.1) | ~25 | Feature non implementee |
| Events avances (async, conditional, metadata) | ~40 | Enrichissement events |
| Producers avances (disposal, lifecycle) | ~35 | Enrichissement producers |
| Stereotypes | ~17 | Feature a implementer |
| Dependent context lifecycle | ~19 | Tracking dependants |
| Client proxies runtime | ~15 | Wrapping runtime |
| InjectionPoint metadata | ~21 | Feature a implementer |
| Scope/qualifier definition | ~23 | Heritage, stereotypes |
| Autres (lookup, alternatives, etc.) | ~50 | Divers |
