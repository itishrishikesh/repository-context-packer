package dev.hrishikesh.rer.context.policy;

import dev.hrishikesh.rer.context.manifest.InputManifest;
import dev.hrishikesh.rer.context.path.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class ExclusionPolicy {
  private final List<Path> excluded = new ArrayList<>();

  public ExclusionPolicy(InputManifest manifest, Map<String, PermittedRoot> roots, Path output)
      throws IOException {
    excluded.add(output);
    for (var root : roots.values()) excluded.add(root.gitDirectory());
    for (var declaration : manifest.exclusions()) {
      Path logical = roots.get(declaration.root()).directory().resolve(declaration.path());
      excluded.add(logical);
      // Resolve existing ancestors, including symlinked excluded directories, across roots.
      Path ancestor = logical;
      var tail = new ArrayDeque<Path>();
      while (ancestor != null && !Files.exists(ancestor)) {
        tail.addFirst(ancestor.getFileName());
        ancestor = ancestor.getParent();
      }
      if (ancestor != null) {
        Path resolved = ancestor.toRealPath();
        for (var part : tail) resolved = resolved.resolve(part);
        excluded.add(resolved);
      }
    }
  }

  public boolean excludes(ValidatedSource source) {
    for (Path part : Path.of(source.path())) if (part.toString().equals(".git")) return true;
    return excluded.stream().anyMatch(source.file()::startsWith);
  }
}
