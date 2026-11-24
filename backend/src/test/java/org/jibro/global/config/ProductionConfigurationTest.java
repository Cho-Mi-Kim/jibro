package org.jibro.global.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProductionConfigurationTest {
    private static final String URL = "jdbc:postgresql://db.internal:5432/jibro?sslmode=verify-full&sslrootcert=/run/rds-ca/root.pem";
    private static final String SECRET = "production-validation-fixture-32-bytes-only";

    @Test
    void acceptsVerifiedSameOriginConfiguration() {
        assertDoesNotThrow(() -> ProductionConfiguration.validate("https://housing.example.org", true, URL, SECRET));
    }

    @Test
    void rejectsLocalCookiesAndUnverifiedDatabaseWithoutLeakingValues() {
        assertThrows(IllegalStateException.class, () -> ProductionConfiguration.validate("http://127.0.0.1:5173", false, URL, SECRET));
        assertThrows(IllegalStateException.class, () -> ProductionConfiguration.validate("https://housing.example.org", false, URL, SECRET));
        var error = assertThrows(IllegalStateException.class, () -> ProductionConfiguration.validate("https://housing.example.org", true, "jdbc:postgresql://db/test?password=must-not-print", SECRET));
        assertFalse(error.getMessage().contains("must-not-print"));
        assertThrows(IllegalStateException.class, () -> ProductionConfiguration.validate("https://housing.example.org/path", true, URL, SECRET));
        assertThrows(IllegalStateException.class, () -> ProductionConfiguration.validate("https://housing.example.org:444", true, URL, SECRET));
        assertThrows(IllegalStateException.class, () -> ProductionConfiguration.validate("https://housing.example.org", true, URL, "REPLACE_WITH_AT_LEAST_32_RANDOM_BYTES"));
    }
}
