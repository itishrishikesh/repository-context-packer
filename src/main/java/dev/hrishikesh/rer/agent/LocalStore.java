package dev.hrishikesh.rer.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

final class LocalStore {
  static final ObjectMapper JSON = new ObjectMapper();
  final Path directory;

  record TaskCase(int version, String name, String revision, String prompt, String check) {}

  LocalStore(Repository repository) throws IOException {
    this(repository, defaultDirectory());
  }

  LocalStore(Repository repository, Path base) throws IOException {
    base = base.toAbsolutePath().normalize();
    Path ancestor = base;
    while (!Files.exists(ancestor)) ancestor = ancestor.getParent();
    Path resolved = ancestor.toRealPath().resolve(ancestor.relativize(base)).normalize();
    if (resolved.startsWith(repository.root))
      throw new IOException("Rer data must be outside the repository: " + resolved);
    Files.createDirectories(base);
    Path realBase = base.toRealPath();
    if (realBase.startsWith(repository.root))
      throw new IOException("Rer data must be outside the repository: " + realBase);
    directory =
        realBase
            .resolve("repositories")
            .resolve(
                Repository.hash(repository.root.toString().getBytes(StandardCharsets.UTF_8))
                    .substring(0, 24));
    Files.createDirectories(directory);
    Files.setPosixFilePermissions(
        directory, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
  }

  private static Path defaultDirectory() {
    String override = System.getenv("RER_DATA_HOME");
    if (override != null && !override.isBlank()) return Path.of(override).toAbsolutePath();
    String xdg = System.getenv("XDG_DATA_HOME");
    if (xdg != null && !xdg.isBlank()) return Path.of(xdg).toAbsolutePath().resolve("rer");
    Path home = Path.of(System.getProperty("user.home"));
    return System.getProperty("os.name").startsWith("Mac")
        ? home.resolve("Library/Application Support/rer")
        : home.resolve(".local/share/rer");
  }

  Path casePath(String name) {
    if (name == null || !name.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}"))
      throw new IllegalArgumentException(
          "Case names must use 1–64 letters, digits, underscores or hyphens");
    return directory.resolve("cases").resolve(name + ".json");
  }

  void add(TaskCase task) throws IOException {
    Path path = casePath(task.name());
    Files.createDirectories(path.getParent());
    // CREATE_NEW refuses accidental replacement of an existing case.
    Files.writeString(
        path,
        JSON.writerWithDefaultPrettyPrinter().writeValueAsString(task) + "\n",
        StandardOpenOption.CREATE_NEW);
  }

  TaskCase load(String name) throws IOException {
    Path path = casePath(name);
    if (!Files.isRegularFile(path))
      throw new IOException("No saved case named " + name + "; use rer case add");
    TaskCase task = JSON.readValue(path.toFile(), TaskCase.class);
    if (task.version() != 1
        || !name.equals(task.name())
        || task.revision() == null
        || !task.revision().matches("[a-f0-9]{40,64}")
        || task.prompt() == null
        || task.prompt().isBlank()
        || task.check() == null
        || task.check().isBlank()) throw new IOException("Invalid saved case: " + path);
    return task;
  }

  List<String> names() throws IOException {
    Path cases = directory.resolve("cases");
    if (!Files.isDirectory(cases)) return List.of();
    try (var paths = Files.list(cases)) {
      return paths
          .map(p -> p.getFileName().toString())
          .filter(p -> p.endsWith(".json"))
          .map(p -> p.substring(0, p.length() - 5))
          .sorted()
          .toList();
    }
  }
}
