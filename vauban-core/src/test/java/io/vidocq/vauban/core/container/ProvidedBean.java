package io.vidocq.vauban.core.container;

import jakarta.enterprise.context.Dependent;

/**
 * Test bean instantiated through a {@link io.vidocq.vauban.core.VaubanComponentProvider}
 * (see {@link CountingComponentProvider}) rather than by reflection.
 */
@Dependent
public class ProvidedBean {
    public String hello() {
        return "hello";
    }
}
