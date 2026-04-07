# Lessons Learned

## 1. Pas de preview features sauf APIs finalisees JDK 25
**Contexte**: L'utilisateur a explicitement refuse `--enable-preview`.
**Regle**: Utiliser uniquement les APIs finalisees du JDK 25. `ScopedValue` (JEP 487) est finalise en JDK 25 et doit etre utilise a la place de `ThreadLocal` pour la compatibilite virtual threads. Pas de Stable Values (JEP 502), ni Structured Concurrency (JEP 505).

## 2. Maven 4 RC est acceptable
**Contexte**: L'utilisateur veut Maven 4.0.0-rc-5, pas Maven 3.9.
**Regle**: Utiliser les features Maven 4 (POM model 4.1.0, decouverte auto sous-projets, nouveau lifecycle). Ne pas proposer de downgrade vers Maven 3.

## 3. maven-plugin-plugin incompatible JDK 25
**Contexte**: Phase 0 - le plugin-plugin 3.15.1 ne supporte pas class file version 69 (JDK 25).
**Regle**: Le module vauban-maven-plugin reste en packaging `jar` jusqu'a ce que maven-plugin-tools supporte JDK 25. Ne pas utiliser `<packaging>maven-plugin</packaging>` ni les annotations `@Mojo`.

## 4. Les sealed interfaces/classes locales sont interdites en Java
**Contexte**: Phase 0 - erreur de compilation dans SmokeTest avec sealed interface locale.
**Regle**: Toujours declarer les sealed types comme membres de classe (nested) ou top-level, jamais locaux dans une methode.

## 5. Packages vides avec module-info exports
**Contexte**: Phase 0 - erreur "package is empty or does not exist" quand un module exporte un package qui ne contient que package-info.java.
**Regle**: Chaque package exporte dans module-info.java doit contenir au moins une classe concrete (pas juste package-info.java).

## 6. Shell Claude Code et SDKMAN
**Contexte**: Le shell de Claude Code ne charge pas automatiquement le .sdkmanrc.
**Regle**: Toujours prefixer les commandes Maven avec `export MAVEN_HOME=~/.sdkman/candidates/maven/4.0.0-rc-5 && export PATH="$MAVEN_HOME/bin:$PATH" &&` pour garantir Maven 4.

## 7. Conflit de noms AnnotationValue entre model et JDK
**Contexte**: Phase 1 - `fr.vidocq.vauban.indexer.model.AnnotationValue` et `java.lang.classfile.AnnotationValue` ont le meme nom simple.
**Regle**: Dans `ClassFileScanner`, utiliser des FQN pour les references a `java.lang.classfile.AnnotationValue` et ses sous-types. Ne pas utiliser d'import wildcard pour les deux packages.

## 8. API Class-File JDK 25 : symbol vs raw
**Contexte**: Phase 1 - `FieldModel.fieldType()` retourne `Utf8Entry` (raw), `fieldTypeSymbol()` retourne `ClassDesc` (type). Idem pour `MethodModel`.
**Regle**: Toujours utiliser les methodes `*Symbol()` (`fieldTypeSymbol()`, `methodTypeSymbol()`) pour obtenir les types symboliques.

## 9. Les agents custom .claude/agents/ ne sont pas des subagent_type
**Contexte**: Phase 1 - `subagent_type: "tdd-writer"` echoue avec "Agent type not found".
**Regle**: Les agents custom sont invoques differemment (via @mention ou directive). Pour les sous-agents, utiliser `general-purpose` avec les instructions de l'agent dans le prompt.

## 10. Conflits de noms CDI lang model vs indexer model
**Contexte**: Phase 2 - Les interfaces CDI (`ClassInfo`, `FieldInfo`, `MethodInfo`, `AnnotationInfo`) ont les memes noms simples que nos records indexer.
**Regle**: Dans les implementations du lang model, utiliser des FQN ou des imports precis. Ne jamais importer en wildcard les deux packages. Prefixer `jakarta.enterprise.lang.model.declarations.ClassInfo` et `fr.vidocq.vauban.indexer.model.ClassInfo` explicitement.

## 11. Ne pas supposer l'origine des commits
**Contexte**: Les commits "Missing file to commit" etaient de l'utilisateur, pas des agents.
**Regle**: Ne pas faire d'hypotheses sur qui a fait un commit. Verifier avec l'utilisateur avant de consolider/rebase.

## 12. CDI DefinitionException vs DeploymentException
**Contexte**: Phase 10 - Le TCK est tres strict sur le type d'exception lancee. Les erreurs de syntaxe/definition sont des `DefinitionException`, les problemes de resolution de graphe (unsatisfied, ambiguous) sont des `DeploymentException`.
**Regle**: Toujours verifier la spec CDI (Section 2.8) pour le type d'exception attendu. Dans `VaubanContainer.builder().build()`, filtrer les erreurs par `ValidationError.Kind` pour lancer la bonne exception Jakarta EE.

## 13. Alternatives desactivees et decouverte de beans
**Contexte**: Phase 10 - `DisabledBeanNotAvailableForInjectionTest` echouait car un bean alternative sans `@Priority` etait quand meme decouvert.
**Regle**: CDI 4.1 Section 5.1.1 : une alternative n'est pas disponible pour l'injection si elle n'est pas activee. Il est preferable de les exclure des la phase `BeanDiscovery` pour eviter qu'elles ne polluent le `BeanResolver`.
