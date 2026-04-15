module fr.vidocq.vauban.processor {
    requires fr.vidocq.vauban.core;
    requires fr.vidocq.vauban.indexer;
    requires java.compiler;

    uses jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;

    exports fr.vidocq.vauban.processor;
    exports fr.vidocq.vauban.processor.apt;
    exports fr.vidocq.vauban.processor.codegen;
    exports fr.vidocq.vauban.processor.codegen.factory;
    exports fr.vidocq.vauban.processor.codegen.proxy;

    provides javax.annotation.processing.Processor
        with fr.vidocq.vauban.processor.VaubanProcessor;
}
