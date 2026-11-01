package dev.hrishikesh.rer.context.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
    name = "rer",
    mixinStandardHelpOptions = true,
    version = "rer 1.0.0",
    description = "Bounded, traceable repository context.",
    subcommands = ContextCommand.class)
public final class Main {
  public static void main(String[] args) {
    System.exit(new CommandLine(new Main()).execute(args));
  }
}
