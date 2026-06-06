module io.vidocq.vauban.processor {
    requires io.vidocq.vauban.core;
    requires io.vidocq.vauban.indexer;
    requires java.compiler;

    uses jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;

    exports io.vidocq.vauban.processor;
    exports io.vidocq.vauban.processor.apt;
    exports io.vidocq.vauban.processor.codegen;
    exports io.vidocq.vauban.processor.codegen.factory;
    exports io.vidocq.vauban.processor.codegen.proxy;
    exports io.vidocq.vauban.processor.codegen.interceptor;

    provides javax.annotation.processing.Processor
        with io.vidocq.vauban.processor.VaubanProcessor;
}
