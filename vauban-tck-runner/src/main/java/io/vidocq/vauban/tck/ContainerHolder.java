package io.vidocq.vauban.tck;

import io.vidocq.vauban.core.container.VaubanContainer;

/**
 * Thread-safe holder for the current VaubanContainer instance.
 * Used to bridge between the Arquillian adapter (test scope) and TCK SPI implementations (main scope).
 */
public final class ContainerHolder {

    private static volatile VaubanContainer current;

    public static void set(VaubanContainer container) {
        current = container;
    }

    public static VaubanContainer get() {
        return current;
    }

    public static void clear() {
        current = null;
    }

    private ContainerHolder() {
    }
}
