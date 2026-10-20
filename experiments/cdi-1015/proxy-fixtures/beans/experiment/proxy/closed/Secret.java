package experiment.proxy.closed;

/**
 * A separate package that is neither opened nor exported by the bean module.
 */
public final class Secret {
    private Secret() {
    }

    private static String secret() {
        return "unopened-package-secret";
    }
}
