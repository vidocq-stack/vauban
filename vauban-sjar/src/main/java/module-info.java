module fr.vidocq.vauban.sjar {
    requires fr.vidocq.vauban.classloader.spi;

    exports fr.vidocq.vauban.sjar;
    exports fr.vidocq.vauban.sjar.cli;

    provides fr.vidocq.vauban.classloader.spi.ByteSourcePlugin
            with fr.vidocq.vauban.sjar.SjarPlugin;
}
