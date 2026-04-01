package fr.vidocq.vauban.core.container;

import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Validation des exceptions de déploiement")
class DeploymentExceptionTest {

    public interface Greeter {
        String greet();
    }

    @ApplicationScoped
    public static class EnglishGreeter implements Greeter {
        @Override
        public String greet() { return "Hello"; }
    }

    @ApplicationScoped
    public static class FrenchGreeter implements Greeter {
        @Override
        public String greet() { return "Bonjour"; }
    }

    @ApplicationScoped
    public static class Client {
        @Inject Greeter greeter;
    }

    @ApplicationScoped
    public static class UnsatisfiedClient {
        @Inject Runnable missing;
    }

    @Test
    @DisplayName("Ambiguous dependency should throw DeploymentException")
    void shouldThrowDeploymentExceptionForAmbiguousDependency() {
        assertThrows(DeploymentException.class, () -> {
            VaubanContainer.builder()
                    .addBeanClass(EnglishGreeter.class)
                    .addBeanClass(FrenchGreeter.class)
                    .addBeanClass(Client.class)
                    .build();
        });
    }

    @Test
    @DisplayName("Unsatisfied dependency should throw DeploymentException")
    void shouldThrowDeploymentExceptionForUnsatisfiedDependency() {
        assertThrows(DeploymentException.class, () -> {
            VaubanContainer.builder()
                    .addBeanClass(UnsatisfiedClient.class)
                    .build();
        });
    }

    @jakarta.enterprise.inject.Stereotype
    @jakarta.inject.Named("prefix")
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    public @interface PrefixStereotype {}

    @PrefixStereotype
    @ApplicationScoped
    public static class PrefixedBean {}

    @jakarta.inject.Named("prefix.conflict")
    @ApplicationScoped
    public static class ConflictingBean {}

    @Test
    @DisplayName("Name prefix conflict should throw DefinitionException")
    void shouldThrowDefinitionExceptionForNamePrefixConflict() {
        assertThrows(jakarta.enterprise.inject.spi.DefinitionException.class, () -> {
            VaubanContainer.builder()
                    .addBeanClass(PrefixedBean.class)
                    .addBeanClass(ConflictingBean.class)
                    .build();
        });
    }
}
