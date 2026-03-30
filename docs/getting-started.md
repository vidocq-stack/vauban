# Guide de demarrage Vauban CDI Lite

## Prerequis

- **JDK 25** (Temurin recommande)
- **Maven 4.0.0-rc-5**

```bash
# Installation via SDKMAN!
sdk install java 25-tem
sdk install maven 4.0.0-rc-5
```

## 1. Creer un projet

Ajoutez les dependances Vauban dans votre `pom.xml` :

```xml
<dependencies>
    <!-- Runtime CDI -->
    <dependency>
        <groupId>fr.vidocq.vauban</groupId>
        <artifactId>vauban-core</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </dependency>

    <!-- Tests JUnit 6 -->
    <dependency>
        <groupId>fr.vidocq.vauban</groupId>
        <artifactId>vauban-junit</artifactId>
        <version>0.1.0-SNAPSHOT</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

## 2. Ecrire un bean CDI

```java
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class GreetingService {

    @Inject
    GreetingRepository repository;

    public String greet(String name) {
        return repository.getTemplate().formatted(name);
    }
}
```

```java
import jakarta.enterprise.context.Dependent;

@Dependent
public class GreetingRepository {

    public String getTemplate() {
        return "Bonjour %s !";
    }
}
```

## 3. Demarrer le conteneur

### Programmatique

```java
import fr.vidocq.vauban.core.container.VaubanContainer;

public class Main {
    public static void main(String[] args) {
        // Construire le conteneur avec les beans
        var container = VaubanContainer.builder()
                .addBeanClass(GreetingService.class)
                .addBeanClass(GreetingRepository.class)
                .build();

        // Obtenir une instance
        var service = container.select(GreetingService.class);
        System.out.println(service.greet("Vauban"));
        // -> "Bonjour Vauban !"

        // Fermer le conteneur
        container.close();
    }
}
```

### Via CDI.current()

```java
import jakarta.enterprise.inject.spi.CDI;

// Apres build(), CDI.current() est disponible
var container = VaubanContainer.builder()
        .addBeanClass(GreetingService.class)
        .addBeanClass(GreetingRepository.class)
        .build();

var service = CDI.current()
        .select(GreetingService.class)
        .get();

System.out.println(service.greet("CDI"));
container.close();
```

## 4. Tester avec JUnit 6

```java
import fr.vidocq.vauban.junit.VaubanTest;
import fr.vidocq.vauban.junit.AddBeans;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@VaubanTest
@AddBeans({GreetingService.class, GreetingRepository.class})
class GreetingServiceTest {

    @Inject
    GreetingService service;

    @Test
    void shouldGreet() {
        assertEquals("Bonjour Vauban !", service.greet("Vauban"));
    }

    @Test
    void shouldNotBeNull() {
        assertNotNull(service);
    }
}
```

`@VaubanTest` demarre un conteneur CDI avant les tests et le ferme apres.
`@AddBeans` specifie les classes de beans a inclure.

## 5. Producers

```java
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ConfigProducer {

    @Produces
    @ApplicationScoped
    public AppConfig createConfig() {
        return new AppConfig("production", 8080);
    }
}

public record AppConfig(String env, int port) {}
```

```java
@ApplicationScoped
public class ServerService {

    @Inject
    AppConfig config;

    public void start() {
        System.out.println("Demarrage sur port " + config.port());
    }
}
```

## 6. Evenements

```java
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

// Producteur d'evenements
@ApplicationScoped
public class OrderService {

    @Inject
    Event<OrderPlaced> orderEvent;

    public void placeOrder(String item) {
        // Logique metier...
        orderEvent.fire(new OrderPlaced(item));
    }
}

// Evenement
public record OrderPlaced(String item) {}

// Observateur
@ApplicationScoped
public class NotificationService {

    void onOrder(@Observes OrderPlaced event) {
        System.out.println("Commande recue: " + event.item());
    }
}
```

## 7. Qualifiers

```java
import jakarta.inject.Qualifier;
import java.lang.annotation.*;

@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
public @interface French {}

@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
public @interface English {}
```

```java
@Dependent @French
public class FrenchGreeting implements Greeting {
    public String hello() { return "Bonjour"; }
}

@Dependent @English
public class EnglishGreeting implements Greeting {
    public String hello() { return "Hello"; }
}
```

```java
@ApplicationScoped
public class MultilingualService {

    @Inject @French
    Greeting frenchGreeting;

    @Inject @English
    Greeting englishGreeting;
}
```

## 8. Scopes disponibles

| Scope | Annotation | Comportement |
|---|---|---|
| Application | `@ApplicationScoped` | Une instance par conteneur (singleton) |
| Request | `@RequestScoped` | Une instance par requete (ThreadLocal) |
| Dependent | `@Dependent` | Nouvelle instance a chaque injection |
| Singleton | `@Singleton` | Identique a Application (pseudo-scope) |

## 9. Alternatives

```java
public interface PaymentService {
    void pay(double amount);
}

@ApplicationScoped
public class RealPaymentService implements PaymentService {
    public void pay(double amount) { /* paiement reel */ }
}

@Alternative
@Priority(1)
@ApplicationScoped
public class MockPaymentService implements PaymentService {
    public void pay(double amount) { /* mock */ }
}
```

L'alternative avec la plus haute `@Priority` est selectionnee automatiquement.

## Limitations actuelles

- **Intercepteurs** : le manager existe mais l'integration runtime n'est pas encore complete
- **Client proxies** : les beans `@ApplicationScoped` ne sont pas proxies (pas de lazy loading)
- **Build Compatible Extensions** : structure en place, pas encore fonctionnelle
- **Decorateurs** : non supportes
- **Conversation scope** : non supporte

## Ressources

- [CDI 4.1 Specification](https://jakarta.ee/specifications/cdi/4.1/)
- [CDI TCK](https://github.com/jakartaee/cdi-tck)
- [JDK 25 Class-File API](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/classfile/package-summary.html)
