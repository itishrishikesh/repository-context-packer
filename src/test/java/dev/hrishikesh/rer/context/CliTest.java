package dev.hrishikesh.rer.context;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hrishikesh.rer.context.cli.Main;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class CliTest {
  @TempDir Path temp;

  @Test
  void packagesAndMapsArgumentManifestAndFilesystemErrors() throws Exception {
    var f = new Fixture(temp);
    f.file("a", "hello");
    f.select(100, "a");
    var output = new StringWriter();
    var errors = new StringWriter();
    var cli =
        new CommandLine(new Main()).setOut(new PrintWriter(output)).setErr(new PrintWriter(errors));
    assertThat(
            cli.execute(
                "context", "--manifest", f.manifest.toString(), "--output", f.output.toString()))
        .isZero();
    assertThat(output.toString()).contains("Included: 1 files, 5 unique bytes", "Omitted: 0 files");
    assertThat(cli.execute("context")).isEqualTo(2);
    f.select(100, "../outside");
    assertThat(
            cli.execute(
                "context", "--manifest", f.manifest.toString(), "--output", f.output.toString()))
        .isEqualTo(2);
    f.select(100, "missing");
    assertThat(
            cli.execute(
                "context", "--manifest", f.manifest.toString(), "--output", f.output.toString()))
        .isEqualTo(3);
    assertThat(errors.toString()).contains("repo/missing");
  }

  @Test
  void changedSourceMapsToExitFourAndPreservesOutput() throws Exception {
    var f = new Fixture(temp);
    f.file("a", "hello");
    f.select(100, "a");
    assertThat(f.pack().exitCode()).isZero();
    String previous = f.json();
    var packer = new ContextPacker(dev.hrishikesh.rer.context.capture.TestReaders.changing("size"));
    var errors = new StringWriter();
    var cli =
        new CommandLine(new dev.hrishikesh.rer.context.cli.ContextCommand(packer))
            .setErr(new PrintWriter(errors));
    assertThat(cli.execute("--manifest", f.manifest.toString(), "--output", f.output.toString()))
        .isEqualTo(4);
    assertThat(errors.toString()).contains("repo/a", "changed during capture");
    assertThat(f.json()).isEqualTo(previous);
  }

  @Test
  void helpListsContext() {
    var out = new StringWriter();
    var cli = new CommandLine(new Main()).setOut(new PrintWriter(out));
    assertThat(cli.execute("--help")).isZero();
    assertThat(out.toString()).contains("context");
  }
}
