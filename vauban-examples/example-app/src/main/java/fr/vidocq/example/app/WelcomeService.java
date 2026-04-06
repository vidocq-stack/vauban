package fr.vidocq.example.app;

import fr.vidocq.example.lib.GreetingService;
import fr.vidocq.example.lib.TimeService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Application bean that injects beans from the external library (example-lib).
 * Demonstrates cross-JAR CDI injection powered by vauban-maven-plugin.
 */
@ApplicationScoped
public class WelcomeService {

    @Inject
    GreetingService greetingService;

    @Inject
    TimeService timeService;

    public String welcome(String name) {
        return greetingService.greet(name) + " Il est " + timeService.now() + ".";
    }
}
