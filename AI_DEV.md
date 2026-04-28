# Rapport de développement assisté par IA — Vauban

> Retour d'expérience sur la construction de Vauban avec Claude Code (Sonnet 4.6 / Opus 4.7).
> Durée réelle : **~1 mois**. Développeur : 1 senior (25 ans d'expérience Java/Jakarta EE).

---

## Ce qui a été construit

| Module | Rôle |
|--------|------|
| `vauban-indexer` | Scanner bytecode sans dépendance externe (remplace Jandex) |
| `vauban-core` | Conteneur CDI 4.1 Lite — injection, scopes, events, intercepteurs, proxies client |
| `vauban-processor` | Annotation processor (APT) — découverte et génération de code à la compilation |
| `vauban-maven-plugin` | Plugin Maven — `generate`, `encrypt`, `dist` |
| `vauban-classloader-spi` | SPI extensible pour chargement de classes custom |
| `vauban-sjar` | Chiffrement in-JAR AES-256-GCM basé sur `module-info.class` |
| `vauban-junit` | Extension JUnit 6 pour tests CDI |
| `vauban-tck-runner` | Runner CDI TCK 4.1 officiel |
| `vauban-test-suite` | Suite de tests d'intégration |

**Caractéristiques transversales**

- JPMS natif — chaque module a son `module-info.java`
- Zéro dépendance bytecode externe — génération via Class-File API JDK 25
- Virtual threads — `ScopedValue` (JEP 487) au lieu de `ThreadLocal` partout
- Build Compatible Extensions — 5 phases, compile-time + runtime + replay
- CDI TCK 4.1 Lite : **774/774 tests (100%)**

---

## Estimation sans IA

### Développeur seul (profil senior, 25 ans d'expérience)

| Bloc | Durée estimée |
|------|--------------|
| Indexeur bytecode + APT | 1,5 mois |
| Conteneur core (injection, scopes, events) | 3 mois |
| Intercepteurs + proxies (Class-File API JDK 25) | 2 mois |
| Build Compatible Extensions — 5 phases | 2 mois |
| Maven plugin + SJAR | 1,5 mois |
| TCK — atteindre 100% (774/774) | 2 mois |
| Friction JPMS transversale | +30 % sur l'ensemble |
| **Total** | **~15–18 mois** |

> Le TCK seul est chroniquement sous-estimé. Chaque échec sur un corner case de spec CDI
> peut représenter une journée de debug. Atteindre 100 % (pas 95 %) coûte disproportionnément cher.

### Équipe de 2 seniors

Environ **8–10 mois** — la coordination, les revues de code et l'absence de parallélisme parfait
limitent le gain linéaire.

---

## Facteur d'accélération

```
~15x
```

1 mois avec IA ≈ 15–18 mois solo.

---

## Ce que l'IA a apporté

### Zéro page blanche
Chaque composant démarrait avec une base solide et cohérente avec l'existant.
Le coût du "premier jet" est passé de jours à minutes.

### Disponibilité de la spec CDI
Les questions sur les règles d'assignabilité CDI 4.1, les phases BCE, le comportement
exact des scopes en présence d'intercepteurs — obtenues en secondes plutôt qu'en heures
de lecture de spec.

### Class-File API JDK 25
L'API est récente, la documentation sparse. L'IA avait le contexte suffisant pour
générer du bytecode correct (factories, proxies, sous-classes interceptées) sans
itérations longues sur des erreurs de format de classe.

### Parallélisme cognitif
Pendant les décisions d'architecture (choix d'un trade-off JPMS, design d'une API publique),
le code était déjà en cours d'écriture. Le temps de réflexion du développeur n'a plus
bloqué la production de code.

### Pattern recognition sur les échecs TCK
Les erreurs TCK récurrentes (propagation d'annotations BCE, faux positifs de validation,
dispatch par MethodHandle sur méthodes protected cross-package) ont été reconnues et
corrigées sans spirale de debug prolongée.

---

## Ce que l'IA n'a pas remplacé

- **Vision architecturale** — les décisions sur la structure des modules JPMS,
  la frontière compile-time / runtime, le modèle d'extension BCE.
- **Jugement sur l'élégance** — distinguer un hack qui passe les tests d'une solution
  correcte selon la spec.
- **Connaissance du domaine** — savoir *quoi* tester, *quels* corner cases de CDI
  valent la peine d'être couverts, *où* la spec est ambiguë.
- **Décisions produit** — périmètre (CDI Lite vs Full), choix de ne pas dépendre d'ASM,
  priorité au JPMS natif dès le départ.

---

## Observations sur la méthode de travail

### Ce qui a bien fonctionné

- **Agents spécialisés en parallèle** — exploration du codebase, analyse TCK, génération
  de bytecode et revue tournaient simultanément.
- **TDD strict** — les tests rouges avant l'implémentation ont évité les régressions
  silencieuses dans un codebase aussi dense.
- **Plan mode systématique** — sur toute tâche à 3+ étapes, rédiger le plan avant
  de coder a réduit les allers-retours.
- **Context-mode** — externaliser les outputs volumineux (build, TCK, logs) a maintenu
  la fenêtre de contexte propre sur une session longue.

### Ce qui a coûté du temps malgré l'IA

- Les **migrations de packages** (fr.vidocq → io.vidocq) sur ~300 fichiers — mécanique
  mais source d'oublis (fichiers sans extension, références hardcodées).
- Les **bugs d'idempotence** (SjarEncryptor, double chiffrement en CI) — détectés
  seulement en environnement CI, invisibles en développement local.
- Les **erreurs de parsing Mermaid** dans la documentation — la syntaxe `timeline`
  et les entités HTML dans les nœuds `[]` sont des pièges fréquents.

---

## Conclusion

Pour un projet de cette complexité technique — spec formelle (CDI 4.1), génération
de bytecode, JPMS, TCK officiel — l'assistance IA a représenté un multiplicateur
de **~15x** sur la vitesse de développement.

Le gain n'est pas uniforme : il est maximal sur le code mécanique et structurel
(boilerplate, implémentations de spec, tests de conformité), et nul sur les décisions
d'architecture et le jugement d'ingénierie senior.

Le modèle le plus précis n'est pas "l'IA code à la place du développeur" mais
**"le développeur senior pilote à la vitesse de sa pensée plutôt qu'à la vitesse
de sa frappe"**.
