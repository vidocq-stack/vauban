package fr.vidocq.vauban.core.extensions;

import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import jakarta.enterprise.inject.build.compatible.spi.ObserverInfo;
import jakarta.enterprise.lang.model.AnnotationTarget;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects info/warn/error messages during BCE processing.
 * Errors cause DeploymentException at the end of the phase.
 */
public final class VaubanMessages implements Messages {

    private final List<String> errors = new ArrayList<>();

    // info() and warn() are intentionally no-op: only errors are collected for deployment validation
    @Override
    public void info(String message) { /* no-op */ }

    @Override
    public void info(String message, AnnotationTarget target) { /* no-op */ }

    @Override
    public void info(String message, BeanInfo bean) { /* no-op */ }

    @Override
    public void info(String message, ObserverInfo observer) { /* no-op */ }

    @Override
    public void warn(String message) { /* no-op */ }

    @Override
    public void warn(String message, AnnotationTarget target) { /* no-op */ }

    @Override
    public void warn(String message, BeanInfo bean) { /* no-op */ }

    @Override
    public void warn(String message, ObserverInfo observer) { /* no-op */ }

    @Override
    public void error(String message) {
        errors.add(message);
    }

    @Override
    public void error(String message, AnnotationTarget target) {
        errors.add(message + " [at " + target + "]");
    }

    @Override
    public void error(String message, BeanInfo bean) {
        errors.add(message + " [bean " + bean.declaringClass().name() + "]");
    }

    @Override
    public void error(String message, ObserverInfo observer) {
        errors.add(message + " [observer " + observer + "]");
    }

    @Override
    public void error(Exception exception) {
        errors.add(exception.getMessage() != null ? exception.getMessage() : exception.getClass().getName());
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public List<String> getErrors() {
        return List.copyOf(errors);
    }
}
