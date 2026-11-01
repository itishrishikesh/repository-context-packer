package dev.hrishikesh.rer.context.path;

import dev.hrishikesh.rer.context.PackException;
import dev.hrishikesh.rer.context.manifest.InputManifest;
import dev.hrishikesh.rer.context.manifest.InputManifest.SourceDeclaration;
import dev.hrishikesh.rer.context.policy.GitIgnorePolicy;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class PathValidator {
  public Path output(Path output) {
    Path absolute = output.toAbsolutePath().normalize();
    if (absolute.getParent() == null)
      throw new PackException(2, "Output cannot be a filesystem root");
    checkLinks(absolute.getRoot(), absolute, "output");
    if (Files.exists(absolute) && !Files.isDirectory(absolute))
      throw new PackException(3, "Output is not a directory");
    return absolute;
  }

  public Map<String, PermittedRoot> roots(InputManifest manifest, Path output) throws IOException {
    var roots = new TreeMap<String, PermittedRoot>();
    for (var declaration : manifest.roots()) {
      Path directory;
      try {
        directory = declaration.path().toRealPath();
      } catch (IOException e) {
        throw new PackException(3, "Root " + declaration.name() + ": " + e.getMessage(), e);
      }
      if (!Files.isDirectory(directory))
        throw new PackException(2, "Root " + declaration.name() + " is not a directory");
      if (directory.startsWith(output))
        throw new PackException(2, "Root " + declaration.name() + " is inside output");
      var tree = GitIgnorePolicy.git(directory, "rev-parse", "--is-inside-work-tree");
      if (tree.code() != 0 || !tree.text().strip().equals("true"))
        throw new PackException(3, "Root " + declaration.name() + " is not a Git working tree");
      var gitDir = GitIgnorePolicy.git(directory, "rev-parse", "--absolute-git-dir");
      if (gitDir.code() != 0)
        throw new PackException(3, "Root " + declaration.name() + ": cannot locate Git metadata");
      roots.put(
          declaration.name(),
          new PermittedRoot(
              declaration.name(), directory, Path.of(gitDir.text().strip()).toRealPath()));
    }
    for (var root : roots.values())
      if (output.startsWith(root.gitDirectory()))
        throw new PackException(2, "Output cannot be inside Git metadata");
    return Collections.unmodifiableMap(roots);
  }

  public ValidatedSource source(SourceDeclaration source, PermittedRoot root) {
    Path lexical = root.directory().resolve(source.path()).normalize();
    if (!lexical.startsWith(root.directory()))
      throw new PackException(2, source.identity() + ": path escapes root");
    checkLinks(root.directory(), lexical, source.identity());
    if (!Files.isRegularFile(lexical, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(lexical))
      throw new PackException(
          3, source.identity() + ": source is missing, unreadable, or not a regular file");
    try {
      Path real = lexical.toRealPath();
      if (!real.startsWith(root.directory()))
        throw new PackException(2, source.identity() + ": resolved path escapes root");
      return new ValidatedSource(source.root(), source.path(), real, source.allowIgnored());
    } catch (IOException e) {
      throw new PackException(3, source.identity() + ": " + e.getMessage(), e);
    }
  }

  private static void checkLinks(Path start, Path target, String identity) {
    Path cursor = start;
    for (Path part : start.relativize(target)) {
      cursor = cursor.resolve(part);
      if (Files.isSymbolicLink(cursor))
        throw new PackException(2, identity + ": symbolic link component: " + part);
    }
  }
}
