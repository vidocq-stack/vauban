package io.vidocq.vauban.example.securized.api;

/**
 * Public API for license validation — exported, stays in clear text.
 */
public interface LicenseValidator {

    boolean isValid(String licenseKey);

    String generateTrial();
}
