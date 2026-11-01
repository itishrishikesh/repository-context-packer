package dev.hrishikesh.rer.context.policy;

import dev.hrishikesh.rer.context.PackException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

public final class GitIgnorePolicy {
  public record Reply(int code, String text) {}

  public static Reply git(Path directory, String... args) {
    return run(directory, null, args);
  }

  private static Reply run(Path directory, byte[] input, String... args) {
    var command = new ArrayList<String>(List.of("git", "-C", directory.toString()));
    command.addAll(List.of(args));
    try {
      var builder = new ProcessBuilder(command).redirectErrorStream(true);
      builder.environment().put("GIT_TERMINAL_PROMPT", "0");
      var process = builder.start();
      try (var stdin = process.getOutputStream()) {
        if (input != null) stdin.write(input);
      }
      String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      return new Reply(process.waitFor(), text);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PackException(3, "Git interrupted", e);
    } catch (IOException e) {
      throw new PackException(3, "Cannot run Git: " + e.getMessage(), e);
    }
  }

  public Optional<String> ignoredRule(Path root, String path, String identity) {
    // Non-verbose status is essential: -v can return 0 for a negated rule.
    Reply status = git(root, "check-ignore", "-q", "--", path);
    if (status.code == 1) return Optional.empty();
    if (status.code != 0)
      throw new PackException(3, identity + ": Git ignore check failed: " + status.text.strip());
    Reply verbose =
        run(
            root,
            (path + "\u0000").getBytes(StandardCharsets.UTF_8),
            "check-ignore",
            "-v",
            "-z",
            "--stdin",
            "--");
    if (verbose.code != 0) throw new PackException(3, identity + ": Git ignore provenance failed");
    String[] fields = verbose.text.split("\u0000", -1);
    if (fields.length < 4)
      throw new PackException(3, identity + ": malformed Git ignore provenance");
    String ruleFile = fields[0].replace('\\', '/');
    // Global excludes may live outside the repository. Never export their absolute path.
    if (ruleFile.startsWith("/") || ruleFile.matches("^[A-Za-z]:.*"))
      ruleFile = "external/" + ruleFile.substring(ruleFile.lastIndexOf('/') + 1);
    return Optional.of(ruleFile + ":" + fields[1] + ":" + fields[2]);
  }
}
