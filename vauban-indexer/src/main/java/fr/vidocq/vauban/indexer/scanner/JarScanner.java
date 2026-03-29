package fr.vidocq.vauban.indexer.scanner;

import fr.vidocq.vauban.indexer.model.ClassInfo;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

public final class JarScanner {

    private JarScanner() {}

    public static List<ClassInfo> scan(Path jarPath) throws IOException {
        var classes = new ArrayList<ClassInfo>();
        try (var jar = new JarFile(jarPath.toFile())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.getName().endsWith(".class") && !entry.isDirectory()) {
                    try (var is = jar.getInputStream(entry)) {
                        var bytes = is.readAllBytes();
                        classes.add(ClassFileScanner.scan(bytes));
                    } catch (Exception e) {
                        // Skip malformed class files
                    }
                }
            }
        }
        return classes;
    }
}
