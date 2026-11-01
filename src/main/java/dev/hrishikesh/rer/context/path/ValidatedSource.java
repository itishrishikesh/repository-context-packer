package dev.hrishikesh.rer.context.path;

import java.nio.file.Path;

public record ValidatedSource(String root, String path, Path file, boolean allowIgnored) {
  public String identity() {
    return root + "/" + path;
  }
}
