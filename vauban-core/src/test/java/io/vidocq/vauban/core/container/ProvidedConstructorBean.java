package io.vidocq.vauban.core.container;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;

/**
 * Test bean with an {@code @Inject} constructor, instantiated through a
 * {@link io.vidocq.vauban.core.VaubanComponentProvider} that receives the container-resolved
 * argument — proving in-module {@code new X(args…)} without {@code opens … to vauban.core}.
 */
@Dependent
public class ProvidedConstructorBean {

    private final ProvidedDependency dependency;

    @Inject
    public ProvidedConstructorBean(ProvidedDependency dependency) {
        this.dependency = dependency;
    }

    public String describe() {
        return "bean+" + dependency.value();
    }
}
