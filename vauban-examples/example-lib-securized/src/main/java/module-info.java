module io.vidocq.vauban.example.securized {
    requires jakarta.cdi;

    // Exported: public API — stays in clear text for compilation
    exports io.vidocq.vauban.example.securized.api;

    // Internal packages are NOT exported — they get encrypted by vauban:encrypt
    // io.vidocq.vauban.example.securized.internal → encrypted
}
