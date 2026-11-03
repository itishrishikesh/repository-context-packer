package dev.hrishikesh.rer.agent;

import static org.assertj.core.api.Assertions.*;

import dev.hrishikesh.rer.context.cli.Main;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentWorkflowTest {
  @TempDir Path temp;

  private Repository fixture() throws Exception {
    Path repo = Files.createDirectory(temp.resolve("repo"));
    Processes.capture(repo, "git", "init", "-q");
    Files.writeString(repo.resolve("answer.txt"), "broken\n");
    Processes.capture(repo, "git", "add", ".");
    Processes.capture(
        repo,
        "git",
        "-c",
        "user.name=Test",
        "-c",
        "user.email=test@example.com",
        "-c",
        "commit.gpgsign=false",
        "commit",
        "-qm",
        "fixture");
    return new Repository(repo);
  }

  @Test
  void inventoryIsReadOnlyAndReportsFactsRatherThanTokenClaims() throws Exception {
    Repository repo = fixture();
    Files.writeString(repo.root.resolve("AGENTS.md"), "See [missing](missing.md)\n");
    Files.writeString(repo.root.resolve("CLAUDE.md"), "See [missing](missing.md)\n");
    Files.writeString(repo.root.resolve(".gitignore"), "ignored/\n");
    Files.createDirectory(repo.root.resolve("ignored"));
    Files.writeString(repo.root.resolve("ignored/AGENTS.md"), "ignored");
    String before = Processes.capture(repo.root, "git", "status", "--porcelain");
    var report = Inventory.inspect(repo);
    assertThat(report.files())
        .extracting(Inventory.Entry::path)
        .containsExactly("AGENTS.md", "CLAUDE.md");
    assertThat(report.findings())
        .extracting(Inventory.Finding::code)
        .contains("DUPLICATE", "BROKEN_LINK");
    assertThat(report.coverage()).contains("Bytes are not tokens");
    assertThat(Processes.capture(repo.root, "git", "status", "--porcelain")).isEqualTo(before);
    Files.createSymbolicLink(repo.root.resolve("nested"), temp);
    assertThatThrownBy(() -> Repository.safe(repo.root, "nested/CLAUDE.md"))
        .hasMessageContaining("Symlink");
    assertThatThrownBy(() -> Repository.safe(repo.root, "../escape"))
        .hasMessageContaining("Unsafe");
  }

  @Test
  void casesStayOutsideRepositoryAndCannotBeOverwrittenOrTraversed() throws Exception {
    Repository repo = fixture();
    var store = new LocalStore(repo, temp.resolve("data"));
    var task =
        new LocalStore.TaskCase(
            1, "fix", repo.revision("HEAD"), "fix answer", "test -f answer.txt");
    store.add(task);
    assertThat(store.load("fix")).isEqualTo(task);
    assertThat(store.names()).containsExactly("fix");
    assertThatThrownBy(() -> store.add(task)).isInstanceOf(FileAlreadyExistsException.class);
    assertThatThrownBy(() -> store.casePath("../escape"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LocalStore(repo, repo.root.resolve("data")))
        .hasMessageContaining("outside");
  }

  @Test
  void overlayRemovesOldConfigurationPreservesSourceAndExecutableModes() throws Exception {
    Repository repo = fixture();
    Files.writeString(repo.root.resolve("AGENTS.md"), "old");
    Map<String, byte[]> config = Map.of(".claude/hooks/check.sh", "#!/bin/sh\nexit 0\n".getBytes());
    Repository.overlay(repo.root, config, Set.of(".claude/hooks/check.sh"));
    assertThat(repo.root.resolve("AGENTS.md")).doesNotExist();
    assertThat(Files.isExecutable(repo.root.resolve(".claude/hooks/check.sh"))).isTrue();
    assertThat(Files.readString(repo.root.resolve("answer.txt"))).isEqualTo("broken\n");
  }

  @Test
  void traceParsersHandleBothFormatsUnknownUsageAndErrors() throws Exception {
    Path trace = temp.resolve("trace");
    Files.writeString(
        trace,
        """
{"type":"item.completed","item":{"type":"command_execution","command":"ls"}}
{"type":"turn.completed","usage":{"input_tokens":100,"cached_input_tokens":40,"output_tokens":10}}
{"type":"turn.completed","usage":{"input_tokens":20,"output_tokens":5}}
""");
    var codex = AgentAdapter.parse(AgentAdapter.Agent.codex, trace);
    assertThat(codex.complete()).isTrue();
    assertThat(codex.inputTokens()).isEqualTo(120);
    assertThat(codex.cachedInputTokens())
        .isNull(); // Missing on one turn: don't report a partial sum as the total.
    assertThat(codex.cacheWriteInputTokens()).isNull();
    assertThat(codex.commands()).isEqualTo(1);
    Files.writeString(
        trace,
        """
{"type":"assistant","message":{"model":"claude-test","content":[{"type":"tool_use","name":"Bash"}]}}
{"type":"result","subtype":"success","is_error":false,"usage":{"input_tokens":50,"cache_read_input_tokens":80,"cache_creation_input_tokens":10,"output_tokens":9}}
""");
    var claude = AgentAdapter.parse(AgentAdapter.Agent.claude, trace);
    assertThat(claude.inputTokens()).isEqualTo(50);
    assertThat(claude.cachedInputTokens()).isEqualTo(80);
    assertThat(claude.models()).containsExactly("claude-test");
    Files.writeString(trace, "{\"type\":\"result\",\"is_error\":true}\nnot JSON\n");
    var error = AgentAdapter.parse(AgentAdapter.Agent.claude, trace);
    assertThat(error.failed()).isTrue();
    assertThat(error.malformedLines()).isEqualTo(1);
    assertThat(error.inputTokens()).isNull();
  }

  @Test
  void timeoutStopsAProcessRatherThanWaitingIndefinitely() throws Exception {
    long start = System.nanoTime();
    var result =
        Processes.run(
            List.of("/bin/sh", "-c", "sleep 30"),
            temp,
            temp.resolve("out"),
            temp.resolve("err"),
            1);
    assertThat(result.timedOut()).isTrue();
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(6));
  }

  @Test
  void fullCliComparesBothAgentsAndRetainsEvidenceWithoutChangingCheckout() throws Exception {
    Repository repo = fixture();
    Path bin = fakeAgents(false);
    Files.writeString(repo.root.resolve("AGENTS.md"), "candidate instructions\n");
    String before = Processes.capture(repo.root, "git", "status", "--porcelain");
    assertThat(
            cli(
                    repo,
                    bin,
                    "case",
                    "add",
                    "fix",
                    "--prompt",
                    "Fix answer.txt",
                    "--check",
                    "test \"$(cat answer.txt)\" = fixed")
                .exit)
        .isZero();
    var result =
        cli(
            repo,
            bin,
            "compare",
            "--base",
            "HEAD",
            "--case",
            "fix",
            "--codex-model",
            "codex-test",
            "--claude-model",
            "claude-test");
    assertThat(result.exit).withFailMessage(result.text).isZero();
    assertThat(result.text).contains("codex", "claude", "PASS", "Report:");
    var store = new LocalStore(repo, temp.resolve("data"));
    Path report;
    try (var files = Files.list(store.directory.resolve("reports"))) {
      report = files.findFirst().orElseThrow();
    }
    var data =
        LocalStore.JSON.readValue(report.resolve("report.json").toFile(), Comparison.Report.class);
    assertThat(data.runs())
        .hasSize(4)
        .allSatisfy(
            run -> {
              assertThat(run.status()).isEqualTo("PASS");
              assertThat(run.precheckExit()).isEqualTo(1);
              assertThat(run.checkExit()).isZero();
              assertThat(report.resolve(run.artifactDirectory()).resolve("trace.jsonl"))
                  .isRegularFile();
            });
    assertThat(data.candidate().files()).extracting(Inventory.Entry::path).contains("AGENTS.md");
    assertThat(data.baseline().files()).isEmpty();
    assertThat(Files.readString(repo.root.resolve("answer.txt"))).isEqualTo("broken\n");
    assertThat(Processes.capture(repo.root, "git", "status", "--porcelain")).isEqualTo(before);
    assertThat(cli(repo, bin, "case", "remove", "fix").exit).isZero();
    assertThat(store.names()).isEmpty();
  }

  @Test
  void malformedSuccessfulProcessIsAnErrorNotAPassingTask() throws Exception {
    Repository repo = fixture();
    Path bin = fakeAgents(true);
    assertThat(cli(repo, bin, "case", "add", "bad", "--prompt", "fix", "--check", "true").exit)
        .isZero();
    var result =
        cli(
            repo,
            bin,
            "compare",
            "--base",
            "HEAD",
            "--case",
            "bad",
            "--agent",
            "codex",
            "--codex-model",
            "test");
    assertThat(result.exit).isEqualTo(3);
    assertThat(result.text).contains("ERROR", "valid terminal trace");
  }

  @Test
  void candidateCheckFailureIsReportedAsAPairedRegression() throws Exception {
    Repository repo = fixture();
    Path bin = fakeAgents(false);
    Path executable = bin.resolve("codex");
    Files.writeString(
        executable,
        Files.readString(executable)
            .replace(
                "printf 'fixed\\n' > answer.txt",
                "if [ -f CLAUDE.md ]; then printf 'broken\\n"
                    + "' > answer.txt; else printf 'fixed\\n"
                    + "' > answer.txt; fi"));
    assertThat(
            cli(
                    repo,
                    bin,
                    "case",
                    "add",
                    "regression",
                    "--prompt",
                    "fix",
                    "--check",
                    "test \"$(cat answer.txt)\" = fixed")
                .exit)
        .isZero();
    Files.writeString(repo.root.resolve("CLAUDE.md"), "candidate configuration");
    var result =
        cli(
            repo,
            bin,
            "compare",
            "--base",
            "HEAD",
            "--case",
            "regression",
            "--agent",
            "codex",
            "--codex-model",
            "test");
    assertThat(result.exit).withFailMessage(result.text).isEqualTo(1);
    assertThat(result.text).contains("1 paired check regressions", "PASS", "FAIL");
  }

  @Test
  void committedInvalidUtf8ConfigurationIsRejectedWithoutReplacementDecoding() throws Exception {
    Repository repo = fixture();
    Files.write(repo.root.resolve("AGENTS.md"), new byte[] {(byte) 0xff});
    Processes.capture(repo.root, "git", "add", ".");
    Processes.capture(
        repo.root,
        "git",
        "-c",
        "user.name=Test",
        "-c",
        "user.email=test@example.com",
        "-c",
        "commit.gpgsign=false",
        "commit",
        "-qm",
        "invalid text");
    assertThatThrownBy(() -> repo.configuration(repo.revision("HEAD")))
        .hasMessageContaining("not UTF-8");
  }

  @Test
  void cliRejectsMissingModelsAndDryRunNeverRequiresAgentExecution() throws Exception {
    Repository repo = fixture();
    Path bin = fakeAgents(false);
    assertThat(cli(repo, bin, "case", "add", "task", "--prompt", "fix", "--check", "true").exit)
        .isZero();
    assertThat(cli(repo, bin, "compare", "--base", "HEAD", "--case", "task", "--dry-run").exit)
        .isZero();
    assertThat(cli(repo, bin, "compare", "--base", "HEAD", "--case", "task").exit).isEqualTo(2);
    assertThat(cli(repo, bin, "compare", "--base", "HEAD", "--case", "task", "--repeat", "0").exit)
        .isEqualTo(2);
  }

  private Path fakeAgents(boolean malformed) throws Exception {
    Path bin = Files.createDirectory(temp.resolve("bin"));
    for (String name : List.of("codex", "claude")) {
      String event =
          name.equals("codex")
              ? "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":100,\"cached_input_tokens\":30,\"output_tokens\":10}}"
              : "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"usage\":{\"input_tokens\":80,\"output_tokens\":10}}";
      Path executable = bin.resolve(name);
      Files.writeString(
          executable,
          "#!/bin/sh\nif [ \"$1\" = --version ]; then echo '"
              + name
              + " test'; exit 0; fi\nprintf 'fixed\\n' > answer.txt\ncat <<'EVENT'\n"
              + (malformed ? "not JSON" : event)
              + "\nEVENT\n");
      executable.toFile().setExecutable(true);
    }
    return bin;
  }

  record CliResult(int exit, String text) {}

  private CliResult cli(Repository repo, Path bin, String... args) throws Exception {
    var command =
        new ArrayList<>(
            List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                Main.class.getName()));
    Collections.addAll(command, args);
    command.addAll(List.of("--repo", repo.root.toString()));
    var process =
        new ProcessBuilder(command).directory(repo.root.toFile()).redirectErrorStream(true);
    process.environment().put("RER_DATA_HOME", temp.resolve("data").toString());
    process.environment().put("PATH", bin + File.pathSeparator + System.getenv("PATH"));
    Path output = Files.createTempFile(temp, "cli-", ".txt");
    var child = process.redirectOutput(output.toFile()).start();
    if (!child.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
      child.destroyForcibly();
      fail("CLI hung");
    }
    return new CliResult(child.exitValue(), Files.readString(output));
  }
}
