# Lessons Learned

## 1. Pas de preview features
**Contexte**: L'utilisateur a explicitement refuse `--enable-preview`.
**Regle**: Utiliser uniquement les APIs finalisees du JDK 25. Pas de Stable Values (JEP 502), Scoped Values (JEP 506), ni Structured Concurrency (JEP 505). Utiliser des implementations classiques (`ConcurrentHashMap`, `ThreadLocal`, etc.).

## 2. Maven 4 RC est acceptable
**Contexte**: L'utilisateur veut Maven 4.0.0-rc-5, pas Maven 3.9.
**Regle**: Utiliser les features Maven 4 (POM model 4.1.0, decouverte auto sous-projets, nouveau lifecycle). Ne pas proposer de downgrade vers Maven 3.
