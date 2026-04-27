package io.vidocq.vauban.example.securized.internal;

import io.vidocq.vauban.example.securized.api.CryptoService;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Internal implementation of {@link CryptoService}.
 * This class is in a non-exported package and will be encrypted by vauban:encrypt.
 */
@ApplicationScoped
public class CryptoServiceImpl implements CryptoService {

    @Override
    public String encode(String plaintext) {
        return Base64.getEncoder().encodeToString(
                plaintext.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String decode(String encoded) {
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }
}
