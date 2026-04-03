package fr.vidocq.vauban.core.extensions;

import jakarta.enterprise.inject.build.compatible.spi.ScannedClasses;

import java.util.ArrayList;
import java.util.List;

public final class VaubanScannedClasses implements ScannedClasses {

    private final List<String> addedClasses = new ArrayList<>();

    @Override
    public void add(String className) {
        addedClasses.add(className);
    }

    public List<String> getAddedClasses() {
        return List.copyOf(addedClasses);
    }
}
