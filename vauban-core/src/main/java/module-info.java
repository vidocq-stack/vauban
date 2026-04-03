module fr.vidocq.vauban.core {
    requires transitive fr.vidocq.vauban.api;
    requires fr.vidocq.vauban.indexer;
    requires transitive jakarta.cdi.lang.model;
    requires jakarta.el;
    requires jdk.unsupported;

    exports fr.vidocq.vauban.core;
    exports fr.vidocq.vauban.core.container;
    exports fr.vidocq.vauban.core.langmodel;
    exports fr.vidocq.vauban.core.langmodel.declarations;
    exports fr.vidocq.vauban.core.langmodel.types;
    exports fr.vidocq.vauban.core.types;
    exports fr.vidocq.vauban.core.bean.model;
    exports fr.vidocq.vauban.core.bean.discovery;
    exports fr.vidocq.vauban.core.bean.resolution;
    exports fr.vidocq.vauban.core.bean.validation;
    exports fr.vidocq.vauban.core.context;
    exports fr.vidocq.vauban.core.event;
    exports fr.vidocq.vauban.core.interceptor;
    exports fr.vidocq.vauban.core.extensions;

    provides jakarta.enterprise.inject.spi.CDIProvider
            with fr.vidocq.vauban.core.container.VaubanCDIProvider;

    provides jakarta.enterprise.inject.build.compatible.spi.BuildServices
            with fr.vidocq.vauban.core.extensions.VaubanBuildServices;
}
