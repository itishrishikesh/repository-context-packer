package dev.hrishikesh.rer.context.cli;

import dev.hrishikesh.rer.context.*;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.*;

@Command(
    name = "context",
    mixinStandardHelpOptions = true,
    description = "Pack explicitly selected repository files.")
public final class ContextCommand implements Callable<Integer> {
  private final ContextPacker packer;

  public ContextCommand() {
    this(new ContextPacker());
  }

  public ContextCommand(ContextPacker packer) {
    this.packer = packer;
  }

  @Option(
      names = "--manifest",
      required = true,
      description = "YAML manifest (root paths are relative to this file).")
  Path manifest;

  @Option(
      names = "--output",
      required = true,
      description = "Dedicated output directory, relative to the current directory.")
  Path output;

  @Spec Model.CommandSpec spec;

  public Integer call() {
    var result = packer.pack(new PackRequest(manifest, output));
    if (result.exitCode() == 0) {
      var out = spec.commandLine().getOut();
      out.println(result.message());
      out.printf(
          "Included: %d files, %d unique bytes%nOmitted: %d files%n",
          result.includedFiles(), result.includedUniqueBytes(), result.omittedFiles());
    } else spec.commandLine().getErr().println(result.message());
    return result.exitCode();
  }
}
