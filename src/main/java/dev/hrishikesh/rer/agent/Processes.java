package dev.hrishikesh.rer.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Bounded external processes. Arguments are passed directly, except the user's explicit check. */
final class Processes {
  private static final long OUTPUT_LIMIT = 32L * 1024 * 1024;
  private static final Set<Process> ACTIVE = ConcurrentHashMap.newKeySet();

  static {
    Runtime.getRuntime().addShutdownHook(new Thread(() -> ACTIVE.forEach(Processes::stop)));
  }

  record Result(int exitCode, boolean timedOut, boolean outputLimited, long millis) {}

  static Result run(List<String> args, Path cwd, Path stdout, Path stderr, int seconds)
      throws IOException, InterruptedException {
    return run(args, cwd, stdout, stderr, seconds, Path.of("/dev/null"));
  }

  static Result run(List<String> args, Path cwd, Path stdout, Path stderr, int seconds, Path input)
      throws IOException, InterruptedException {
    long start = System.nanoTime();
    Process process =
        new ProcessBuilder(args)
            .directory(cwd.toFile())
            .redirectInput(ProcessBuilder.Redirect.from(input.toFile()))
            .redirectOutput(stdout.toFile())
            .redirectError(stderr.toFile())
            .start();
    ACTIVE.add(process);
    boolean timedOut = false;
    boolean limited = false;
    var children = new HashSet<ProcessHandle>();
    try {
      while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
        process.descendants().forEach(children::add);
        limited = Files.size(stdout) > OUTPUT_LIMIT || Files.size(stderr) > OUTPUT_LIMIT;
        timedOut = System.nanoTime() - start > Duration.ofSeconds(seconds).toNanos();
        if (limited || timedOut) {
          stop(process);
          process.waitFor(5, TimeUnit.SECONDS);
          break;
        }
      }
      limited |= Files.size(stdout) > OUTPUT_LIMIT || Files.size(stderr) > OUTPUT_LIMIT;
      return new Result(
          process.isAlive() ? -1 : process.exitValue(),
          timedOut,
          limited,
          Duration.ofNanos(System.nanoTime() - start).toMillis());
    } finally {
      children.forEach(
          child -> {
            if (child.isAlive()) child.destroyForcibly();
          });
      stop(process);
      ACTIVE.remove(process);
    }
  }

  private static void stop(Process process) {
    process
        .descendants()
        .forEach(
            child -> {
              if (child.isAlive()) child.destroyForcibly();
            });
    if (process.isAlive()) process.destroyForcibly();
  }

  static String capture(Path cwd, String... args) throws IOException, InterruptedException {
    byte[] bytes = captureBytes(cwd, args);
    return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
  }

  static byte[] captureBytes(Path cwd, String... args) throws IOException, InterruptedException {
    Path out = Files.createTempFile("rer-out-", ".txt");
    Path err = Files.createTempFile("rer-err-", ".txt");
    try {
      Result result = run(List.of(args), cwd, out, err, 60);
      if (result.exitCode() != 0 || result.timedOut() || result.outputLimited()) {
        String error = Files.readString(err);
        throw new IOException(
            args[0] + " failed: " + error.substring(0, Math.min(error.length(), 2000)));
      }
      return Files.readAllBytes(out);
    } finally {
      Files.deleteIfExists(out);
      Files.deleteIfExists(err);
    }
  }
}
