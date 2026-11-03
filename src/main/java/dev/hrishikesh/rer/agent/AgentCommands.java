package dev.hrishikesh.rer.agent;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import picocli.CommandLine.*;

public final class AgentCommands {
  private AgentCommands() {}

  abstract static class Operation implements Callable<Integer> {
    @Option(
        names = "--repo",
        defaultValue = ".",
        description = "Repository directory (default: current directory).")
    Path cwd;

    @Spec Model.CommandSpec spec;

    abstract int execute() throws IOException, InterruptedException;

    @Override
    public final Integer call() {
      try {
        return execute();
      } catch (IllegalArgumentException e) {
        spec.commandLine().getErr().println(e.getMessage());
        return 2;
      } catch (IOException e) {
        spec.commandLine().getErr().println(e.getMessage());
        return 3;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        spec.commandLine().getErr().println("Interrupted; running child processes stopped");
        return 130;
      }
    }

    Repository repository() throws IOException, InterruptedException {
      return new Repository(cwd.toAbsolutePath());
    }
  }

  @Command(
      name = "inspect",
      mixinStandardHelpOptions = true,
      description =
          "Inspect Git-visible repository agent configuration without running agents or writing"
              + " repository files.")
  public static final class Inspect extends Operation {
    @Option(names = "--json", description = "Print a machine-readable inventory.")
    boolean json;

    @Override
    int execute() throws IOException, InterruptedException {
      var report = Inventory.inspect(repository());
      PrintWriter out = spec.commandLine().getOut();
      if (json)
        out.println(LocalStore.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
      else {
        out.printf(
            "%d configuration files, %d bytes (not tokens)%n",
            report.files().size(), report.bytes());
        report
            .files()
            .forEach(
                file -> out.printf("  %s  %d bytes  %s%n", file.path(), file.bytes(), file.kind()));
        report
            .findings()
            .forEach(
                finding ->
                    out.printf("%s: %s — %s%n", finding.code(), finding.path(), finding.message()));
        out.println(report.coverage());
      }
      return 0;
    }
  }

  @Command(
      name = "case",
      mixinStandardHelpOptions = true,
      description = "Manage reusable tasks stored outside the repository.",
      subcommands = {AddCase.class, ListCases.class, RemoveCase.class})
  public static final class Cases {}

  @Command(
      name = "add",
      mixinStandardHelpOptions = true,
      description =
          "Save a prompt, committed HEAD fixture, and explicit verification command locally.")
  public static final class AddCase extends Operation {
    @Parameters(index = "0", description = "Unique case name.")
    String name;

    static class Prompt {
      @Option(names = "--prompt", required = true, description = "Task text.")
      String text;

      @Option(
          names = "--prompt-file",
          required = true,
          description = "UTF-8 task file (relative to the calling directory).")
      Path file;
    }

    @ArgGroup(exclusive = true, multiplicity = "1")
    Prompt prompt;

    @Option(
        names = "--check",
        required = true,
        description = "Verification command, executed with /bin/sh in disposable clones.")
    String check;

    @Override
    int execute() throws IOException, InterruptedException {
      Repository repo = repository();
      LocalStore store = new LocalStore(repo);
      String text = prompt.text;
      if (prompt.file != null) {
        if (Files.size(prompt.file) > Repository.FILE_LIMIT)
          throw new IllegalArgumentException("Prompt exceeds 1 MiB");
        text = Files.readString(prompt.file);
      }
      if (text == null
          || text.isBlank()
          || text.length() > Repository.FILE_LIMIT
          || check.isBlank())
        throw new IllegalArgumentException(
            "A nonempty prompt (up to 1 MiB) and check are required");
      String revision = repo.revision("HEAD");
      store.add(new LocalStore.TaskCase(1, name, revision, text, check));
      spec.commandLine()
          .getOut()
          .printf(
              "Saved %s%nFixture: committed HEAD %s (uncommitted source edits are not saved)%n",
              store.casePath(name), revision);
      return 0;
    }
  }

  @Command(
      name = "list",
      mixinStandardHelpOptions = true,
      description = "List local cases for this repository.")
  public static final class ListCases extends Operation {
    @Override
    int execute() throws IOException, InterruptedException {
      LocalStore store = new LocalStore(repository());
      for (String name : store.names()) spec.commandLine().getOut().println(name);
      return 0;
    }
  }

  @Command(
      name = "remove",
      mixinStandardHelpOptions = true,
      description = "Delete a named local case; reports are retained.")
  public static final class RemoveCase extends Operation {
    @Parameters(index = "0")
    String name;

    @Override
    int execute() throws IOException, InterruptedException {
      Path path = new LocalStore(repository()).casePath(name);
      if (!Files.deleteIfExists(path))
        throw new IllegalArgumentException("No saved case named " + name);
      spec.commandLine().getOut().println("Removed " + path);
      return 0;
    }
  }

  @Command(
      name = "data",
      mixinStandardHelpOptions = true,
      description = "Print this repository's external data directory (cases and reports).")
  public static final class Data extends Operation {
    @Override
    int execute() throws IOException, InterruptedException {
      spec.commandLine().getOut().println(new LocalStore(repository()).directory);
      return 0;
    }
  }

  @Command(
      name = "compare",
      mixinStandardHelpOptions = true,
      description =
          "Compare baseline and working-tree configuration on identical committed task code. Runs"
              + " real agents and verification commands.")
  public static final class Compare extends Operation {
    enum Selection {
      both,
      codex,
      claude
    }

    @Option(
        names = "--base",
        required = true,
        description = "Git revision supplying baseline configuration.")
    String base;

    @Option(names = "--case", required = true, description = "Previously saved local case name.")
    String caseName;

    @Option(
        names = "--agent",
        defaultValue = "both",
        description = "${COMPLETION-CANDIDATES} (default: both).")
    Selection selection;

    @Option(
        names = "--codex-model",
        description = "Explicit Codex model identifier; required when running Codex.")
    String codexModel;

    @Option(
        names = "--claude-model",
        description = "Explicit Claude model identifier; required when running Claude.")
    String claudeModel;

    @Option(
        names = "--repeat",
        defaultValue = "1",
        description = "Paired repetitions, 1–20; repeated runs alternate variant order.")
    int repeats;

    @Option(
        names = "--timeout",
        defaultValue = "600",
        description = "Time limit in seconds per agent/check process, 1–3600.")
    int timeout;

    @Option(
        names = "--dry-run",
        description = "Show the fixture and configuration changes without running agents/checks.")
    boolean dryRun;

    @Override
    int execute() throws IOException, InterruptedException {
      if (repeats < 1 || repeats > 20 || timeout < 1 || timeout > 3600)
        throw new IllegalArgumentException("--repeat must be 1–20 and --timeout 1–3600");
      Repository repo = repository();
      LocalStore store = new LocalStore(repo);
      var task = store.load(caseName);
      var baseline = repo.configuration(repo.revision(base));
      var candidate = repo.configuration();
      PrintWriter out = spec.commandLine().getOut();
      out.printf(
          "Case: %s%nSource fixture: %s%nBaseline configuration: %s%nConfiguration files: %d"
              + " baseline / %d candidate%n",
          caseName, task.revision(), repo.revision(base), baseline.size(), candidate.size());
      var paths = new TreeSet<>(baseline.keySet());
      paths.addAll(candidate.keySet());
      var baselineExec = repo.executables(repo.revision(base), baseline);
      var candidateExec = repo.executables(null, candidate);
      for (String path : paths)
        if (!Arrays.equals(baseline.get(path), candidate.get(path))
            || baselineExec.contains(path) != candidateExec.contains(path))
          out.println("Changed: " + path);
      if (dryRun) {
        out.printf(
            "Dry run: %s, %d repetitions; no agents or checks started.%nCheck: %s%n",
            selection, repeats, task.check());
        return 0;
      }
      var agents = new LinkedHashMap<AgentAdapter.Agent, String>();
      if (selection != Selection.claude)
        agents.put(AgentAdapter.Agent.codex, requireModel(codexModel, "--codex-model"));
      if (selection != Selection.codex)
        agents.put(AgentAdapter.Agent.claude, requireModel(claudeModel, "--claude-model"));
      out.printf(
          "Starting %d paid agent runs; checks run before and after each successful agent. Reports"
              + " are advisory.%n",
          agents.size() * repeats * 2);
      out.flush();
      Path report = Comparison.compare(repo, store, task, base, agents, repeats, timeout, out);
      var result =
          LocalStore.JSON.readValue(
              report.resolve("report.json").toFile(), Comparison.Report.class);
      out.println(Files.readString(report.resolve("report.md")));
      out.println("Report: " + report.resolve("report.md"));
      if (result.runs().stream().anyMatch(run -> run.status().equals("ERROR"))) return 3;
      return result.runs().stream().anyMatch(run -> run.status().equals("FAIL")) ? 1 : 0;
    }

    private static String requireModel(String value, String option) {
      if (value == null || value.isBlank())
        throw new IllegalArgumentException(
            option + " is required; use a full model identifier for reproducible comparisons");
      return value;
    }
  }
}
