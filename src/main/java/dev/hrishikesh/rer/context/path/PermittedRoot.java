package dev.hrishikesh.rer.context.path;

import java.nio.file.Path;

public record PermittedRoot(String name, Path directory, Path gitDirectory) {}
