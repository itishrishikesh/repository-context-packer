package dev.hrishikesh.rer.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class Fixture {
  public final Path repo, manifest, output;

  public Fixture(Path parent) throws Exception {
    // macOS exposes its temporary directory through /var -> /private/var.
    // Fixtures must use physical parents; production output validation still rejects symlinks.
    parent = Files.createDirectories(parent).toRealPath();
    repo = Files.createDirectories(parent.resolve("repo"));
    manifest = parent.resolve("context.yaml");
    output = parent.resolve("out");
    git("init", "-q");
    Path noIgnores = Files.writeString(parent.resolve("no-ignores"), "");
    git("config", "core.excludesFile", noIgnores.toString());
  }

  public void git(String... args) throws Exception {
    var command = new ArrayList<String>(List.of("git", "-C", repo.toString()));
    command.addAll(List.of(args));
    var process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String text = new String(process.getInputStream().readAllBytes());
    assertThat(process.waitFor()).as(text).isZero();
  }

  public Path file(String name, String text) throws IOException {
    Path path = repo.resolve(name);
    Files.createDirectories(path.getParent());
    return Files.writeString(path, text);
  }

  public void select(long budget, String... paths) throws IOException {
    StringBuilder yaml =
        new StringBuilder(
            "version: 1\nbyteBudget: "
                + budget
                + "\nroots:\n  - name: repo\n    path: repo\nsources:\n");
    if (paths.length == 0) yaml.append("  []\n");
    for (String path : paths)
      yaml.append("  - root: repo\n    path: '").append(path.replace("'", "''")).append("'\n");
    Files.writeString(manifest, yaml);
  }

  public void append(String text) throws IOException {
    Files.writeString(manifest, text, StandardOpenOption.APPEND);
  }

  public PackResult pack() {
    return new ContextPacker().pack(new PackRequest(manifest, output));
  }

  public String json() throws IOException {
    return Files.readString(output.resolve("manifest.json"));
  }

  public String markdown() throws IOException {
    return Files.readString(output.resolve("context.md"));
  }
}
