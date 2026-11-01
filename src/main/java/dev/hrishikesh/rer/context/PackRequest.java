package dev.hrishikesh.rer.context;

import java.nio.file.Path;

public record PackRequest(Path manifest, Path output) {}
