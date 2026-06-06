module io.vidocq.vauban.core {
    requires transitive io.vidocq.vauban.api;
    requires io.vidocq.vauban.indexer;
    requires io.vidocq.vauban.classloader.spi;
    requires transitive jakarta.cdi.lang.model;
    requires jakarta.el;
    requires jdk.unsupported;

    uses io.vidocq.vauban.classloader.spi.ByteSourcePlugin;

    // Instantiate Build Compatible Extensions through their `provides ... with` declaration,
    // so application modules need not `opens <pkg> to io.vidocq.vauban.core` on the module path.
    uses jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;

    // Instantiate application components (beans, contexts, synthetic creators) via APT-generated
    // providers, so application modules need not open their packages to the container.
    uses io.vidocq.vauban.api.VaubanComponentProvider;

    exports io.vidocq.vauban.core;
    exports io.vidocq.vauban.core.container;
    exports io.vidocq.vauban.core.langmodel;
    exports io.vidocq.vauban.core.langmodel.declarations;
    exports io.vidocq.vauban.core.langmodel.types;
    exports io.vidocq.vauban.core.types;
    exports io.vidocq.vauban.core.bean.model;
    exports io.vidocq.vauban.core.bean.discovery;
    exports io.vidocq.vauban.core.bean.resolution;
    exports io.vidocq.vauban.core.bean.validation;
    exports io.vidocq.vauban.core.context;
    exports io.vidocq.vauban.core.event;
    exports io.vidocq.vauban.core.interceptor;
    exports io.vidocq.vauban.core.enrichment;
    exports io.vidocq.vauban.core.extensions;

    provides jakarta.enterprise.inject.spi.CDIProvider
            with io.vidocq.vauban.core.container.VaubanCDIProvider;

    provides jakarta.enterprise.inject.se.SeContainerInitializer
            with io.vidocq.vauban.core.container.VaubanSeContainerInitializer;

    provides jakarta.enterprise.inject.build.compatible.spi.BuildServices
            with io.vidocq.vauban.core.extensions.VaubanBuildServices;
}
