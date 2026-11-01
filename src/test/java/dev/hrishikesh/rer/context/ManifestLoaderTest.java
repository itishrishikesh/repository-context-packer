package dev.hrishikesh.rer.context;

import static org.assertj.core.api.Assertions.*;

import dev.hrishikesh.rer.context.manifest.ManifestLoader;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ManifestLoaderTest {
  @TempDir Path temp;

  @Test
  void validDefaultsAndManifestRelativeRoots() throws Exception {
    var f = new Fixture(temp);
    f.select(100, "./a//b.txt");
    var m = new ManifestLoader().load(f.manifest);
    assertThat(m.roots().getFirst().path()).isEqualTo(f.repo);
    assertThat(m.sources().getFirst().path()).isEqualTo("a/b.txt");
    assertThat(m.sources().getFirst().allowIgnored()).isFalse();
    assertThat(m.exclusions()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"../x", "/etc/passwd", "a/../b", "C:/foo", "\\\\host/share", "."})
  void rejectsUnsafePaths(String path) throws Exception {
    var f = new Fixture(temp);
    f.select(100, path);
    assertThat(f.pack().exitCode()).isEqualTo(2);
    assertThat(f.output).doesNotExist();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "version: 2",
        "byteBudget: 0",
        "byteBudget: -1",
        "byteBudget: true",
        "version: '1'",
        "byteBudget: 1.5",
        "byteBudget: 9223372036854775808"
      })
  void rejectsInvalidNumbers(String replacement) throws Exception {
    var f = new Fixture(temp);
    f.select(100, "a");
    String key = replacement.split(":")[0];
    Files.writeString(
        f.manifest, Files.readString(f.manifest).replaceAll("(?m)^" + key + ":.*$", replacement));
    assertThat(f.pack().exitCode()).isEqualTo(2);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "  - root: missing\n    path: a\n",
        "  - root: repo\n    path: ./a\n",
        "  - root: repo\n    path: b\n    allowIgnored: 'true'\n"
      })
  void rejectsUnknownReferenceDuplicateAndBadBoolean(String extra) throws Exception {
    var f = new Fixture(temp);
    f.select(100, "a");
    f.append(extra);
    assertThat(f.pack().exitCode()).isEqualTo(2);
  }

  @Test
  void rejectsMalformedUnknownDuplicateFieldsRootsAndExclusions() throws Exception {
    var f = new Fixture(temp);
    f.select(100, "a");
    String valid = Files.readString(f.manifest);
    for (String invalid :
        new String[] {
          "[unclosed",
          valid + "typo: 1\n",
          valid + "version: 1\n",
          valid + "---\nfoo: bar\n",
          valid.replace("sources:", "  - name: repo\n    path: repo\nsources:"),
          valid.replace("  - name: repo\n    path: repo", "  []"),
          valid + "exclusions:\n  - root: missing\n    path: x\n",
          valid + "exclusions:\n  - root: repo\n    path: ../x\n"
        }) {
      Files.writeString(f.manifest, invalid);
      assertThat(f.pack().exitCode()).as(invalid).isEqualTo(2);
      assertThat(f.output).doesNotExist();
    }
  }
}
