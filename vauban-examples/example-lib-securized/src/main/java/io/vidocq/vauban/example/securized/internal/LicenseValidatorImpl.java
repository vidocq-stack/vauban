package io.vidocq.vauban.example.securized.internal;

import io.vidocq.vauban.example.securized.api.LicenseValidator;
import jakarta.enterprise.context.Dependent;

/**
 * Internal implementation of {@link LicenseValidator}.
 * Contains the validation logic — encrypted to protect business rules.
 */
@Dependent
public class LicenseValidatorImpl implements LicenseValidator {

    private static final String VALID_PREFIX = "VAUBAN-";

    @Override
    public boolean isValid(String licenseKey) {
        if (licenseKey == null || licenseKey.isBlank()) {
            return false;
        }
        return licenseKey.startsWith(VALID_PREFIX) && licenseKey.length() >= 12;
    }

    @Override
    public String generateTrial() {
        return VALID_PREFIX + "TRIAL-" + System.currentTimeMillis() % 10000;
    }
}
