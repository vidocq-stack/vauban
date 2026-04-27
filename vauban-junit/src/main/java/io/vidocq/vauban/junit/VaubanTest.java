package io.vidocq.vauban.junit;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a test class for Vauban CDI integration testing.
 * Bootstraps a CDI container before tests and shuts it down after.
 * Use {@link AddBeans} to specify which beans to include.
 * Use {@code @jakarta.inject.Inject} on fields to get beans injected.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(VaubanExtension.class)
public @interface VaubanTest {
}
