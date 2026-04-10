module fr.vidocq.example.securized {
    requires jakarta.cdi;

    // Exported: public API — stays in clear text for compilation
    exports fr.vidocq.example.securized.api;

    // Internal packages are NOT exported — they get encrypted by vauban:encrypt
    // fr.vidocq.example.securized.internal → encrypted
}
