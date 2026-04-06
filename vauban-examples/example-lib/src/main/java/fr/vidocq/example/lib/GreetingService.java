package fr.vidocq.example.lib;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * A standard CDI bean in an external library.
 * This class has no Vauban dependency — it only uses the Jakarta CDI API.
 */
@ApplicationScoped
public class GreetingService {

    public String greet(String name) {
        return "Bonjour, " + name + " !";
    }
}
