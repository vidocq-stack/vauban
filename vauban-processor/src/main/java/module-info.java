module fr.vidocq.vauban.processor {
    requires fr.vidocq.vauban.core;
    requires fr.vidocq.vauban.indexer;
    requires java.compiler;

    exports fr.vidocq.vauban.processor;
    exports fr.vidocq.vauban.processor.codegen;
    exports fr.vidocq.vauban.processor.codegen.factory;
    exports fr.vidocq.vauban.processor.codegen.proxy;
}
