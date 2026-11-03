package dev.hrishikesh.rer.agent;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

final class Comparison {
  record Snapshot(List<Inventory.Entry> files, Set<String> executablePaths) {}

  record Run(
      String agent,
      String requestedModel,
      String agentVersion,
      String variant,
      int repetition,
      String status,
      Integer precheckExit,
      Integer checkExit,
      Processes.Result process,
      AgentAdapter.Trace trace,
      String artifactDirectory,
      String error) {}

  record Report(
      int formatVersion,
      String caseName,
      String fixtureRevision,
      String baselineRevision,
      Snapshot baseline,
      Snapshot candidate,
      List<String> limitations,
      List<Run> runs) {}

  private static final List<String> LIMITATIONS =
      List.of(
          "Advisory experiment; one passing check is not proof the requested task was completed.",
          "Identical committed task code is used for both variants; only discovered repository"
              + " configuration is replaced.",
          "Personal/managed settings and external MCP state are inherited, not snapshotted. Clone"
              + " paths can affect trust and config discovery.",
          "Ignored configuration and ancestor-directory instructions are not copied. Dependency"
              + " installs, submodules and Git LFS hydration are not performed.",
          "Agent/model defaults, caches and external services may vary. Pin full model identifiers;"
              + " inspect observed models where available.",
          "Usage is provider-reported cumulative run usage, not context-window occupancy or a"
              + " dollar bill. Codex input includes cached input; Claude reports uncached input and"
              + " cache categories separately.",
          "Tool observations are partial; commands do not establish skill activation or causality."
              + " No unobserved usage is filled with zero.",
          "Disposable clones isolate repository edits, not the operating system. User check"
              + " commands execute with your account permissions.");

  static Path compare(
      Repository repo,
      LocalStore store,
      LocalStore.TaskCase task,
      String base,
      Map<AgentAdapter.Agent, String> agents,
      int repeats,
      int timeout,
      PrintWriter progress)
      throws IOException, InterruptedException {
    String baselineRevision = repo.revision(base);
    String fixtureRevision = repo.revision(task.revision());
    var baseline = repo.configuration(baselineRevision);
    var candidate = repo.configuration();
    var baselineExec = repo.executables(baselineRevision, baseline);
    var candidateExec = repo.executables(null, candidate);
    // Preflight every executable before spending tokens on either provider.
    var versions = new EnumMap<AgentAdapter.Agent, String>(AgentAdapter.Agent.class);
    for (var agent : agents.keySet())
      versions.put(agent, Processes.capture(repo.root, agent.name(), "--version").strip());
    String id =
        Instant.now().toString().replace(':', '-')
            + "-"
            + UUID.randomUUID().toString().substring(0, 8);
    Path reportDir = store.directory.resolve("reports").resolve(id);
    Files.createDirectories(reportDir);
    LocalStore.JSON
        .writerWithDefaultPrettyPrinter()
        .writeValue(reportDir.resolve("case.json").toFile(), task);
    Repository.overlay(reportDir.resolve("configuration/baseline"), baseline, baselineExec);
    Repository.overlay(reportDir.resolve("configuration/candidate"), candidate, candidateExec);
    var runs = new ArrayList<Run>();
    try {
      for (int repeat = 1; repeat <= repeats; repeat++) {
        for (var agent : agents.entrySet()) {
          // Alternate order so a warm cache does not always favor the candidate.
          List<String> order =
              repeat % 2 == 1 ? List.of("baseline", "candidate") : List.of("candidate", "baseline");
          for (String variant : order) {
            progress.printf("%s / %s / repetition %d%n", agent.getKey(), variant, repeat);
            progress.flush();
            runs.add(
                run(
                    repo,
                    task,
                    fixtureRevision,
                    agent.getKey(),
                    agent.getValue(),
                    versions.get(agent.getKey()),
                    variant,
                    repeat,
                    variant.equals("baseline") ? baseline : candidate,
                    variant.equals("baseline") ? baselineExec : candidateExec,
                    reportDir,
                    timeout));
            write(
                reportDir,
                new Report(
                    1,
                    task.name(),
                    fixtureRevision,
                    baselineRevision,
                    new Snapshot(Inventory.entries(baseline), baselineExec),
                    new Snapshot(Inventory.entries(candidate), candidateExec),
                    LIMITATIONS,
                    List.copyOf(runs)));
          }
        }
      }
    } finally {
      if (!runs.isEmpty())
        write(
            reportDir,
            new Report(
                1,
                task.name(),
                fixtureRevision,
                baselineRevision,
                new Snapshot(Inventory.entries(baseline), baselineExec),
                new Snapshot(Inventory.entries(candidate), candidateExec),
                LIMITATIONS,
                List.copyOf(runs)));
    }
    return reportDir;
  }

  private static Run run(
      Repository repo,
      LocalStore.TaskCase task,
      String fixtureRevision,
      AgentAdapter.Agent agent,
      String model,
      String version,
      String variant,
      int repeat,
      Map<String, byte[]> config,
      Set<String> executable,
      Path reportDir,
      int timeout)
      throws IOException, InterruptedException {
    String artifact = agent + "-" + variant + "-" + repeat;
    Path logs = reportDir.resolve(artifact);
    Files.createDirectory(logs);
    Path temp = Files.createTempDirectory("rer-replay-");
    Path workspace = temp.resolve("workspace");
    Processes.Result process = null;
    AgentAdapter.Trace trace = null;
    Integer precheck = null, check = null;
    String status = "ERROR", error = null;
    try {
      repo.checkout(workspace, fixtureRevision);
      Repository.overlay(workspace, config, executable);
      // Commit the overlaid configuration only inside the disposable clone, so diffs describe agent
      // edits.
      Processes.capture(workspace, "git", "add", "-A");
      Processes.capture(
          workspace,
          "git",
          "-c",
          "core.hooksPath=/dev/null",
          "-c",
          "user.name=rer",
          "-c",
          "user.email=rer@localhost",
          "-c",
          "commit.gpgsign=false",
          "commit",
          "--allow-empty",
          "-m",
          "rer configuration fixture");
      precheck = check(task.check(), workspace, logs, "precheck", timeout);
      if (precheck == -1) throw new IOException("Precheck timed out or exceeded its output limit");
      // The precheck may generate build products, but must not change tracked source/configuration.
      if (!Processes.capture(workspace, "git", "status", "--porcelain", "--untracked-files=no")
          .isBlank())
        throw new IOException(
            "Precheck modified tracked files; use a verification command that leaves sources"
                + " unchanged");
      List<String> command = AgentAdapter.command(agent, model);
      Files.writeString(logs.resolve("prompt.txt"), task.prompt());
      LocalStore.JSON
          .writerWithDefaultPrettyPrinter()
          .writeValue(logs.resolve("command.json").toFile(), command);
      process =
          Processes.run(
              command,
              workspace,
              logs.resolve("trace.jsonl"),
              logs.resolve("agent.stderr"),
              timeout,
              logs.resolve("prompt.txt"));
      trace = AgentAdapter.parse(agent, logs.resolve("trace.jsonl"));
      if (process.exitCode() == 0
          && !process.timedOut()
          && !process.outputLimited()
          && trace.complete()
          && !trace.failed()
          && trace.malformedLines() == 0) {
        check = check(task.check(), workspace, logs, "check", timeout);
        status = check == 0 ? "PASS" : "FAIL";
      } else {
        error =
            "Agent did not finish successfully with a valid terminal trace; inspect trace.jsonl and"
                + " agent.stderr";
      }
      Processes.capture(workspace, "git", "add", "--intent-to-add", ".");
      Files.writeString(
          logs.resolve("changes.patch"),
          Processes.capture(workspace, "git", "diff", "HEAD", "--binary"));
      Files.writeString(
          logs.resolve("files.txt"), Processes.capture(workspace, "git", "status", "--porcelain"));
    } catch (IOException e) {
      status = "ERROR";
      error = e.getMessage();
    } finally {
      Repository.deleteTree(temp);
    }
    return new Run(
        agent.name(),
        model,
        version,
        variant,
        repeat,
        status,
        precheck,
        check,
        process,
        trace,
        artifact,
        error);
  }

  private static int check(String command, Path workspace, Path logs, String prefix, int timeout)
      throws IOException, InterruptedException {
    var result =
        Processes.run(
            List.of("/bin/sh", "-c", command),
            workspace,
            logs.resolve(prefix + ".stdout"),
            logs.resolve(prefix + ".stderr"),
            timeout);
    LocalStore.JSON.writeValue(logs.resolve(prefix + ".json").toFile(), result);
    return result.timedOut() || result.outputLimited() ? -1 : result.exitCode();
  }

  private static void write(Path directory, Report report) throws IOException {
    atomic(
        directory.resolve("report.json"),
        LocalStore.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
    StringBuilder md =
        new StringBuilder(
            "# Agent context comparison\n\nCase: `"
                + report.caseName()
                + "`\n\nFixture: `"
                + report.fixtureRevision()
                + "`\n\nBaseline configuration: `"
                + report.baselineRevision()
                + "`\n\n");
    for (String agent : report.runs().stream().map(Run::agent).distinct().toList()) {
      long regressions =
          report.runs().stream()
              .filter(
                  run ->
                      run.agent().equals(agent)
                          && run.variant().equals("candidate")
                          && run.status().equals("FAIL"))
              .filter(
                  run ->
                      report.runs().stream()
                          .anyMatch(
                              other ->
                                  other.agent().equals(agent)
                                      && other.variant().equals("baseline")
                                      && other.repetition() == run.repetition()
                                      && other.status().equals("PASS")))
              .count();
      md.append(agent)
          .append(": ")
          .append(regressions)
          .append(" paired check regressions; input token delta (candidate minus baseline mean): ")
          .append(delta(report.runs(), agent))
          .append(".\n\n");
    }
    md.append(
        "| Agent | Variant | Run | Check result | Input | Cache read | Cache write | Output | Agent"
            + " ms |\n"
            + "| --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
    for (Run run : report.runs()) {
      var t = run.trace();
      md.append("| ")
          .append(run.agent())
          .append(" | ")
          .append(run.variant())
          .append(" | ")
          .append(run.repetition())
          .append(" | ")
          .append(run.status())
          .append(" | ")
          .append(value(t == null ? null : t.inputTokens()))
          .append(" | ")
          .append(value(t == null ? null : t.cachedInputTokens()))
          .append(" | ")
          .append(value(t == null ? null : t.cacheWriteInputTokens()))
          .append(" | ")
          .append(value(t == null ? null : t.outputTokens()))
          .append(" | ")
          .append(value(run.process() == null ? null : run.process().millis()))
          .append(" |\n");
    }
    md.append(
        "\n"
            + "PASS means the agent completed and the user-supplied check exited 0. It does not"
            + " independently prove task correctness.\n\n");
    for (Run run : report.runs()) {
      md.append("- ")
          .append(run.agent())
          .append(" / ")
          .append(run.variant())
          .append(" / ")
          .append(run.repetition())
          .append(": [trace](")
          .append(run.artifactDirectory())
          .append("/trace.jsonl), [verification output](")
          .append(run.artifactDirectory())
          .append("/check.stdout), [tracked changes](")
          .append(run.artifactDirectory())
          .append("/changes.patch).\n");
      if (Objects.equals(run.precheckExit(), 0))
        md.append(
            "  Check already passed before the agent ran; this case may not distinguish a"
                + " successful fix.\n");
      if (run.error() != null)
        md.append("  Error: ").append(run.error().replace('\n', ' ')).append("\n");
    }
    md.append("\n## Interpretation limits\n\n");
    report.limitations().forEach(limit -> md.append("- ").append(limit).append("\n"));
    atomic(directory.resolve("report.md"), md.toString());
  }

  private static String value(Object value) {
    return value == null ? "unknown" : value.toString();
  }

  private static String delta(List<Run> runs, String agent) {
    var relevant = runs.stream().filter(run -> run.agent().equals(agent)).toList();
    if (relevant.stream()
        .anyMatch(
            run ->
                run.trace() == null
                    || run.trace().inputTokens() == null
                    || run.status().equals("ERROR"))) return "unknown";
    var baseline =
        relevant.stream()
            .filter(run -> run.variant().equals("baseline"))
            .mapToLong(run -> run.trace().inputTokens())
            .average();
    var candidate =
        relevant.stream()
            .filter(run -> run.variant().equals("candidate"))
            .mapToLong(run -> run.trace().inputTokens())
            .average();
    return baseline.isPresent() && candidate.isPresent()
        ? String.format(Locale.ROOT, "%+.1f", candidate.getAsDouble() - baseline.getAsDouble())
        : "unknown";
  }

  private static void atomic(Path path, String value) throws IOException {
    Path temporary = Files.createTempFile(path.getParent(), ".report-", ".tmp");
    try {
      Files.writeString(temporary, value);
      try {
        Files.move(
            temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }
}
