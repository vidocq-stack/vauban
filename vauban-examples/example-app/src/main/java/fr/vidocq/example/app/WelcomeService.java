package fr.vidocq.example.app;

import fr.vidocq.example.lib.GreetingService;
import fr.vidocq.example.lib.TimeService;
import fr.vidocq.example.securized.CryptoService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Application bean that injects beans from both libraries:
 * <ul>
 *   <li>{@link GreetingService} and {@link TimeService} from example-lib (plain JAR)</li>
 *   <li>{@link CryptoService} from example-lib-securized (encrypted SJAR)</li>
 * </ul>
 * Demonstrates cross-JAR CDI injection with mixed plain and encrypted sources.
 */
@ApplicationScoped
public class WelcomeService {

    @Inject
    GreetingService greetingService;

    @Inject
    TimeService timeService;

    @Inject
    CryptoService cryptoService;

    public String welcome(String name) {
        return greetingService.greet(name) + " Il est " + timeService.now() + ".";
    }

    public String welcomeEncoded(String name) {
        var message = welcome(name);
        return cryptoService.encode(message);
    }
}
