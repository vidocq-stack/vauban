package fr.vidocq.vauban.maven.module;

public enum ModuleType {
    EXPLICIT,    // Has module-info.class
    AUTOMATIC,   // Has Automatic-Module-Name in MANIFEST.MF but no module-info.class
    UNNAMED      // Neither module-info nor Automatic-Module-Name
}
