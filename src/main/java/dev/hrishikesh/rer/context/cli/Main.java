package dev.hrishikesh.rer.context.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
    name = "rer",
    mixinStandardHelpOptions = true,
    version = "rer 1.1.0",
    description = "Local-first agent context regression checks for Claude Code and Codex.",
    subcommands = {
      dev.hrishikesh.rer.agent.AgentCommands.Inspect.class,
      dev.hrishikesh.rer.agent.AgentCommands.Cases.class,
      dev.hrishikesh.rer.agent.AgentCommands.Compare.class,
      dev.hrishikesh.rer.agent.AgentCommands.Data.class,
      ContextCommand.class
    })
public final class Main {
  public static void main(String[] args) {
    System.exit(new CommandLine(new Main()).execute(args));
  }
}
