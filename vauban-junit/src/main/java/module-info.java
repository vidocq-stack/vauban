module io.vidocq.vauban.junit {
    requires transitive io.vidocq.vauban.core;
    requires transitive org.junit.jupiter.api;
    requires jakarta.inject;

    exports io.vidocq.vauban.junit;
}
