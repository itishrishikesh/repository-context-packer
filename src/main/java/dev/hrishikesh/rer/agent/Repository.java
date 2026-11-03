package dev.hrishikesh.rer.agent;

import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.util.*;

final class Repository {
  static final int FILE_LIMIT = 1024 * 1024;
  static final int TOTAL_LIMIT = 16 * FILE_LIMIT;
  final Path root;

  Repository(Path cwd) throws IOException, InterruptedException {
    root =
        Path.of(Processes.capture(cwd, "git", "rev-parse", "--show-toplevel").strip()).toRealPath();
  }

  String revision(String ref) throws IOException, InterruptedException {
    return Processes.capture(
            root, "git", "rev-parse", "--verify", "--end-of-options", ref + "^{commit}")
        .strip();
  }

  static boolean isConfiguration(String path) {
    String name = path.substring(path.lastIndexOf('/') + 1);
    if (Set.of("AGENTS.md", "AGENTS.override.md", "CLAUDE.md", "CLAUDE.local.md", ".mcp.json")
        .contains(name)) return true;
    String p = "/" + path;
    return p.contains("/.agents/skills/")
        || p.contains("/.codex/skills/")
        || p.contains("/.claude/skills/")
        || p.contains("/.claude/rules/")
        || p.contains("/.claude/agents/")
        || p.contains("/.claude/commands/")
        || p.contains("/.claude/hooks/")
        || p.contains("/.codex/rules/")
        || p.endsWith("/.codex/config.toml")
        || p.endsWith("/.claude/settings.json")
        || p.endsWith("/.claude/settings.local.json");
  }

  SortedMap<String, byte[]> configuration() throws IOException, InterruptedException {
    var result = new TreeMap<String, byte[]>();
    String files =
        Processes.capture(
            root, "git", "ls-files", "--cached", "--others", "--exclude-standard", "-z");
    for (String file : files.split("\0")) {
      if (!isConfiguration(file)) continue;
      Path path = safe(root, file);
      if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) continue;
      if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
        throw new IOException("Configuration is not a regular file: " + file);
      if (Files.size(path) > FILE_LIMIT)
        throw new IOException("Configuration exceeds 1 MiB: " + file);
      result.put(file, Files.readAllBytes(path));
    }
    validateSize(result);
    return result;
  }

  SortedMap<String, byte[]> configuration(String revision)
      throws IOException, InterruptedException {
    var result = new TreeMap<String, byte[]>();
    String tree = Processes.capture(root, "git", "ls-tree", "-r", "-z", revision);
    for (String entry : tree.split("\0")) {
      if (entry.isEmpty()) continue;
      String[] fields = entry.substring(0, entry.indexOf('\t')).split(" ");
      String path = entry.substring(entry.indexOf('\t') + 1);
      if (!isConfiguration(path)) continue;
      if (!fields[0].equals("100644") && !fields[0].equals("100755"))
        throw new IOException("Unsupported configuration entry: " + path);
      long size =
          Long.parseLong(Processes.capture(root, "git", "cat-file", "-s", fields[2]).strip());
      if (size > FILE_LIMIT) throw new IOException("Configuration exceeds 1 MiB: " + path);
      // These are text configuration files; reject non-UTF-8 in the shared validator.
      result.put(path, Processes.captureBytes(root, "git", "cat-file", "blob", fields[2]));
    }
    validateSize(result);
    return result;
  }

  static void validateSize(Map<String, byte[]> files) throws IOException {
    long total = 0;
    for (var entry : files.entrySet()) {
      total += entry.getValue().length;
      try {
        java.nio.charset.StandardCharsets.UTF_8
            .newDecoder()
            .decode(java.nio.ByteBuffer.wrap(entry.getValue()));
      } catch (java.nio.charset.CharacterCodingException e) {
        throw new IOException("Configuration is not UTF-8: " + entry.getKey());
      }
    }
    if (total > TOTAL_LIMIT) throw new IOException("Configuration exceeds 16 MiB in total");
  }

  static Path safe(Path root, String relative) throws IOException {
    Path path = root.resolve(relative).normalize();
    if (relative.isEmpty()
        || Path.of(relative).isAbsolute()
        || !path.startsWith(root)
        || Arrays.asList(relative.split("/")).contains(".git"))
      throw new IOException("Unsafe path: " + relative);
    Path cursor = root;
    for (Path part : root.relativize(path)) {
      cursor = cursor.resolve(part);
      if (Files.isSymbolicLink(cursor))
        throw new IOException("Symlink in configuration path: " + relative);
    }
    return path;
  }

  static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  void checkout(Path destination, String revision) throws IOException, InterruptedException {
    String tree = Processes.capture(root, "git", "ls-tree", "-r", revision);
    if (tree.lines().anyMatch(line -> line.startsWith("160000 ")))
      throw new IOException(
          "Task fixture contains submodules; this release does not replay submodules");
    Processes.capture(
        root,
        "git",
        "clone",
        "--no-local",
        "--no-checkout",
        "--",
        root.toString(),
        destination.toString());
    Processes.capture(
        destination, "git", "-c", "core.hooksPath=/dev/null", "checkout", "--detach", revision);
    Processes.capture(destination, "git", "remote", "remove", "origin");
  }

  Set<String> executables(String revision, Map<String, byte[]> config)
      throws IOException, InterruptedException {
    var result = new TreeSet<String>();
    if (revision == null) {
      for (String path : config.keySet())
        if (Files.isExecutable(safe(root, path))) result.add(path);
    } else {
      for (String entry :
          Processes.capture(root, "git", "ls-tree", "-r", "-z", revision).split("\0")) {
        if (entry.startsWith("100755 ")) result.add(entry.substring(entry.indexOf('\t') + 1));
      }
      result.retainAll(config.keySet());
    }
    return result;
  }

  static void overlay(Path workspace, Map<String, byte[]> config, Set<String> executable)
      throws IOException {
    Files.createDirectories(workspace);
    try (var paths = Files.walk(workspace)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        String relative = workspace.relativize(path).toString().replace('\\', '/');
        if (!relative.startsWith(".git/")
            && isConfiguration(relative)
            && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) Files.delete(path);
      }
    }
    for (var entry : config.entrySet()) {
      Path path = safe(workspace, entry.getKey());
      Files.createDirectories(path.getParent());
      Files.write(path, entry.getValue());
      var permissions = Files.getPosixFilePermissions(path);
      permissions.remove(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
      permissions.remove(java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE);
      permissions.remove(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE);
      if (executable.contains(entry.getKey()))
        permissions.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
      Files.setPosixFilePermissions(path, permissions);
    }
  }

  static void deleteTree(Path root) throws IOException {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
    }
  }
}
