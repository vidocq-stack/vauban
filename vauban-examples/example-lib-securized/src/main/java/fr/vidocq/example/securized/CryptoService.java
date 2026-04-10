package fr.vidocq.example.securized;

import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * A CDI bean containing "proprietary" logic — distributed in an encrypted SJAR.
 * Demonstrates that encrypted beans behave identically to regular CDI beans.
 */
@ApplicationScoped
public class CryptoService {

    public String encode(String plaintext) {
        return Base64.getEncoder().encodeToString(
                plaintext.getBytes(StandardCharsets.UTF_8));
    }

    public String decode(String encoded) {
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }
}
