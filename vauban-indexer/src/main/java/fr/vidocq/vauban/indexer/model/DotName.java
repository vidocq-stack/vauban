package fr.vidocq.vauban.indexer.model;

import java.util.Objects;

public record DotName(String value) implements Comparable<DotName> {

    public DotName {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("value must not be empty");
        }
    }

    public static DotName of(String fqcn) {
        return new DotName(fqcn);
    }

    public static DotName fromInternal(String internal) {
        return new DotName(internal.replace('/', '.'));
    }

    public static DotName fromDescriptor(String desc) {
        if (desc.startsWith("L") && desc.endsWith(";")) {
            return fromInternal(desc.substring(1, desc.length() - 1));
        }
        throw new IllegalArgumentException("Invalid class descriptor: " + desc);
    }

    public String simpleName() {
        int idx = value.lastIndexOf('.');
        return idx < 0 ? value : value.substring(idx + 1);
    }

    public String packageName() {
        int idx = value.lastIndexOf('.');
        return idx < 0 ? "" : value.substring(0, idx);
    }

    public String toInternal() {
        return value.replace('.', '/');
    }

    public String toDescriptor() {
        return "L" + toInternal() + ";";
    }

    @Override
    public int compareTo(DotName other) {
        return this.value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
