package fr.vidocq.example.securized;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * A bean that injects other beans from the same encrypted library.
 * Demonstrates intra-SJAR injection works correctly.
 */
@ApplicationScoped
public class SecureGreeting {

    @Inject
    CryptoService cryptoService;

    public String greetEncoded(String name) {
        var message = "Bienvenue, " + name + " (securise)";
        return cryptoService.encode(message);
    }
}
