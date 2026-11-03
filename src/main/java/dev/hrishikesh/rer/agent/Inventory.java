package dev.hrishikesh.rer.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

final class Inventory {
  record Entry(String path, String kind, int bytes, String sha256) {}

  record Finding(String code, String path, String message) {}

  record Report(
      int formatVersion, String coverage, long bytes, List<Entry> files, List<Finding> findings) {}

  static List<Entry> entries(Map<String, byte[]> files) {
    return files.entrySet().stream()
        .map(
            e ->
                new Entry(
                    e.getKey(),
                    kind(e.getKey()),
                    e.getValue().length,
                    Repository.hash(e.getValue())))
        .toList();
  }

  private static String kind(String path) {
    if (path.endsWith("SKILL.md")) return "skill";
    if (path.endsWith("AGENTS.md")
        || path.endsWith("AGENTS.override.md")
        || path.endsWith("CLAUDE.md")
        || path.endsWith("CLAUDE.local.md")) return "instructions";
    return "supporting configuration";
  }

  static Report inspect(Repository repo) throws IOException, InterruptedException {
    var files = repo.configuration();
    var findings = new ArrayList<Finding>();
    var hashes = new HashMap<String, String>();
    Pattern links = Pattern.compile("\\[[^\\]]*]\\(([^\\s)]+)(?:\\s+[^)]*)?\\)");
    for (var entry : files.entrySet()) {
      String path = entry.getKey();
      String first = hashes.putIfAbsent(Repository.hash(entry.getValue()), path);
      if (first != null)
        findings.add(
            new Finding(
                "DUPLICATE",
                path,
                "Identical bytes to " + first + "; scopes may differ, review before removing"));
      if (!path.endsWith(".md")) continue;
      String content = new String(entry.getValue(), StandardCharsets.UTF_8);
      var matcher = links.matcher(content);
      while (matcher.find()) {
        String target = matcher.group(1);
        if (target.startsWith("#")
            || target.startsWith("/")
            || target.contains(":")
            || target.contains("%")
            || target.startsWith("<")) continue;
        target = target.split("#", 2)[0];
        Path resolved = repo.root.resolve(path).getParent().resolve(target).normalize();
        if (!resolved.startsWith(repo.root)) continue;
        if (!Files.exists(resolved))
          findings.add(
              new Finding("BROKEN_LINK", path, "Local Markdown link target is absent: " + target));
      }
      if (kind(path).equals("instructions") && entry.getValue().length > 16_384)
        findings.add(
            new Finding(
                "LARGE_INSTRUCTIONS",
                path,
                "Over 16 KiB; review whether task-specific material can be loaded on demand"
                    + " (heuristic)"));
    }
    return new Report(
        1,
        "Repository-owned, Git-visible configuration only. Personal/managed settings, ignored"
            + " files, runtime tool schemas and actual prompt exposure are not measured. Bytes are"
            + " not tokens.",
        files.values().stream().mapToLong(b -> b.length).sum(),
        entries(files),
        List.copyOf(findings));
  }
}
