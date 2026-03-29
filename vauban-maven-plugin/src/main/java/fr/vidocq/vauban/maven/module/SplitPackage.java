package fr.vidocq.vauban.maven.module;

import java.util.List;

public record SplitPackage(String packageName, List<String> jarFiles) {}
