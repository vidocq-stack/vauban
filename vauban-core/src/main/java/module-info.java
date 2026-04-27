module io.vidocq.vauban.core {
    requires transitive io.vidocq.vauban.api;
    requires io.vidocq.vauban.indexer;
    requires static io.vidocq.vauban.classloader.spi;
    requires transitive jakarta.cdi.lang.model;
    requires jakarta.el;
    requires jdk.unsupported;

    uses io.vidocq.vauban.classloader.spi.ByteSourcePlugin;

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

    provides jakarta.enterprise.inject.build.compatible.spi.BuildServices
            with io.vidocq.vauban.core.extensions.VaubanBuildServices;
}
