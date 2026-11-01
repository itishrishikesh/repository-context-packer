package dev.hrishikesh.rer.context.manifest;

import java.nio.file.Path;
import java.util.List;

public record InputManifest(
    int version,
    long byteBudget,
    List<RootDeclaration> roots,
    List<SourceDeclaration> sources,
    List<ExclusionDeclaration> exclusions) {
  public record RootDeclaration(String name, Path path) {}

  public record SourceDeclaration(String root, String path, boolean allowIgnored) {
    public String identity() {
      return root + "/" + path;
    }
  }

  public record ExclusionDeclaration(String root, String path) {}
}
