module io.vidocq.vauban.indexer {
    requires static io.vidocq.vauban.classloader.spi;

    exports io.vidocq.vauban.indexer;
    exports io.vidocq.vauban.indexer.model;
    exports io.vidocq.vauban.indexer.scanner;
}
