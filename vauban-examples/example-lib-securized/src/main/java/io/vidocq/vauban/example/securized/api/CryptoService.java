package io.vidocq.vauban.example.securized.api;

/**
 * Public API for the crypto service — exported, stays in clear text.
 * Consumers compile against this interface.
 */
public interface CryptoService {

    String encode(String plaintext);

    String decode(String encoded);
}
