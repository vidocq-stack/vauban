package io.vidocq.vauban.core.container;

import io.vidocq.vauban.core.VaubanComponentProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Resolves component instances through the module-supplied {@link VaubanComponentProvider}s
 * (loaded via {@link ServiceLoader}) so the container can instantiate application components
 * without reflection and without {@code opens … to io.vidocq.vauban.core}.
 *
 * <p>The container consults this resolver first; only when no provider owns a class does it
 * fall back to reflective instantiation ({@link VaubanLookup}). This keeps backward
 * compatibility (class path, unnamed modules, jars not yet APT-processed) while removing the
 * need for a qualified {@code opens} whenever a generated provider is present.
 */
final class ComponentProviders {

    private final List<VaubanComponentProvider> providers;

    ComponentProviders(List<VaubanComponentProvider> providers) {
        this.providers = List.copyOf(providers);
    }

    /**
     * Combines explicitly-registered providers (priority order) with every
     * {@link VaubanComponentProvider} discovered via {@link ServiceLoader} from {@code cl}.
     * A misconfigured service declaration is non-fatal — the container falls back to reflection.
     *
     * @param cl    class loader scanned for service providers
     * @param extra programmatically-registered providers, consulted before the discovered ones
     */
    static ComponentProviders load(ClassLoader cl, List<VaubanComponentProvider> extra) {
        var all = new ArrayList<>(extra);
        try {
            ServiceLoader.load(VaubanComponentProvider.class, cl)
                    .stream()
                    .map(ServiceLoader.Provider::get)
                    .forEach(all::add);
        } catch (Throwable _) {
            // misconfigured/absent services — keep the explicit providers, fall back otherwise
        }
        return new ComponentProviders(all);
    }

    /**
     * Returns a fresh instance of {@code className} from the first provider that owns it, or
     * {@code null} if none does (so the caller falls back to reflection). A provider that
     * throws is skipped rather than failing startup.
     */
    Object create(String className) {
        for (var provider : providers) {
            try {
                var instance = provider.create(className);
                if (instance != null) return instance;
            } catch (RuntimeException _) {
                // a misbehaving provider must not break container startup — try the next one
            }
        }
        return null;
    }

    /**
     * Returns a fresh instance of {@code className} from the first provider that owns it,
     * passing the container-resolved constructor {@code args} (in declared order) so the
     * {@code new X(args…)} call happens in-module; {@code null} if no provider owns it (the
     * caller then falls back to reflection). A provider that throws is skipped.
     */
    Object create(String className, Object[] args) {
        for (var provider : providers) {
            try {
                var instance = provider.create(className, args);
                if (instance != null) return instance;
            } catch (RuntimeException _) {
                // a misbehaving provider must not break container startup — try the next one
            }
        }
        return null;
    }

    boolean isEmpty() {
        return providers.isEmpty();
    }
}
