module io.vidocq.vauban.sjar {
    requires io.vidocq.vauban.classloader.spi;

    exports io.vidocq.vauban.sjar;
    exports io.vidocq.vauban.sjar.cli;

    provides io.vidocq.vauban.classloader.spi.ByteSourcePlugin
            with io.vidocq.vauban.sjar.SjarPlugin;
}
