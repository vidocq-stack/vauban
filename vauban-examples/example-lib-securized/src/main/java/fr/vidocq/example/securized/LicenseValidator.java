package fr.vidocq.example.securized;

import jakarta.enterprise.context.Dependent;

/**
 * A @Dependent-scoped bean simulating license validation logic.
 * The encryption protects this business logic from reverse engineering.
 */
@Dependent
public class LicenseValidator {

    private static final String VALID_PREFIX = "VAUBAN-";

    public boolean isValid(String licenseKey) {
        if (licenseKey == null || licenseKey.isBlank()) {
            return false;
        }
        return licenseKey.startsWith(VALID_PREFIX) && licenseKey.length() >= 12;
    }

    public String generateTrial() {
        return VALID_PREFIX + "TRIAL-" + System.currentTimeMillis() % 10000;
    }
}
