package io.vidocq.vauban.processor.codegen;

/**
 * A class generated at build time as raw bytecode.
 *
 * @param className fully qualified class name (dot-separated)
 * @param bytecode  the class file bytes
 */
public record GeneratedClass(String className, byte[] bytecode) {

    /**
     * Internal name (slash-separated) for use with ClassFile API.
     */
    public String internalName() {
        return className.replace('.', '/');
    }
}
