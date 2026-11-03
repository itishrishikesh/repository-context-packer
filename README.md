# rer — Agent Context Regression Checks

Find out whether a change to your Claude Code or Codex setup earns its cost. `rer` inspects repository guidance, saves engineering tasks locally, and compares baseline and candidate configurations against the same committed task code.

**No YAML, source-file selection, or committed evaluation files required.** Cases, snapshots, traces, and reports live outside your repository. Both **Claude Code and Codex** are supported in the first release.

## Install on Linux or macOS

Run this single command:

```sh
curl -fsSL https://raw.githubusercontent.com/itishrishikesh/repository-context-packer/main/scripts/install.sh | sh
```

The installer downloads the latest [GitHub Release](https://github.com/itishrishikesh/repository-context-packer/releases/latest), selects your OS/architecture, and verifies its SHA-256 checksum. Each archive bundles a Java runtime: **you do not need Java, Maven, or sudo**.

Supported downloads: Linux x86_64 and ARM64, macOS Intel and Apple Silicon. Linux requires glibc (not Alpine/musl); x86_64 builds use Ubuntu 22.04 and ARM64 builds use Ubuntu 24.04. macOS builds use macOS 15. Older systems are not verified.

The command installs to `~/.local/bin/rer`. If that directory is not on your `PATH`, the installer prints the line to add to your shell profile. You can immediately run `~/.local/bin/rer --help` either way. The installer does not edit your shell configuration.

```sh
rer --version
rer --help
```

To install a particular published version:

```sh
curl -fsSL https://raw.githubusercontent.com/itishrishikesh/repository-context-packer/main/scripts/install.sh | RER_VERSION=1.1.0 sh
```

Set `RER_INSTALL_DIR` and `RER_BIN_DIR` on `sh` to customize installation. Run the command again to install a newer release. Reinstalling the same version requires removing its existing version directory first; previous versions remain available until you remove them. For a manual install, download the matching release archive and `SHA256SUMS`, verify the archive, extract it, and run the extracted `rer` launcher. Keep its `runtime/` and `lib/` directories beside it.

## First useful command

From a Git repository:

```sh
rer inspect
```

This requires Git, but does not require an agent installation, login, or model call. It lists supported Git-visible configuration, SHA-256 identities in JSON output, exact duplicate content, broken relative Markdown links, and unusually large instruction files. Duplicate content can serve different scopes; findings are suggestions, not deletion instructions.

```sh
rer inspect --json
rer inspect --repo /path/to/project
```

The inspection covers `AGENTS.md`, `AGENTS.override.md`, `CLAUDE.md`, `CLAUDE.local.md`, `.mcp.json`, supported `.claude`/`.codex` settings, skills, rules, agents, commands, and hook support files. It uses Git-visible tracked and non-ignored untracked files. Configuration must be UTF-8, at most 1 MiB per file and 16 MiB total. Symlinked configuration paths are rejected.

This is a repository inventory, not a reconstruction of the agent's actual prompt. It excludes personal/managed settings, ignored local configuration, external skill directories, runtime schemas, and loaded tool responses. **Bytes are not tokens.**

## Compare a setup change, step by step

### 1. Prepare a task with committed starting code

Pick a small engineering task with a meaningful verification command. For example, an exporter escaping bug with a regression test that fails before the fix. The starting code and test must already be committed in your project.

Save the task locally; the inline prompt avoids creating any repository file:

```sh
rer case add exporter-fix \
  --prompt 'Fix the exporter escaping bug demonstrated by ExporterEscapingTest.' \
  --check 'mvn -q verify'
```

Alternatively, use `--prompt-file /path/to/task.md`. The file is read once and its text is saved with the case. The case stores your current committed `HEAD` revision and explicit check command. **Uncommitted task-code edits are not captured.** No agent runs at this step.

### 2. Change your agent configuration

Edit a repository instruction, skill, rule, or supported configuration file. You do not have to commit the change. Candidate configuration is captured from your current working tree, including supported non-ignored untracked files and deletions.

Preview the comparison for free:

```sh
rer compare --base HEAD --case exporter-fix --dry-run
```

`--base` supplies baseline configuration. Use `HEAD` for uncommitted changes or a previous commit/branch when the configuration change is already committed. Task code always comes from the revision saved with the case, regardless of the baseline revision.

### 3. Run both agents

Install and sign in to [Codex CLI](https://learn.chatgpt.com/docs/cli) and [Claude Code](https://code.claude.com/docs/en/overview) first. `rer` uses their existing authentication; it does not install the agents or copy credentials.

Choose model identifiers available to your accounts. Replace the placeholders below with full identifiers; aliases can change over time:

```sh
rer compare --base HEAD --case exporter-fix \
  --agent both \
  --codex-model YOUR_CODEX_MODEL \
  --claude-model YOUR_CLAUDE_MODEL
```

`both` is the default. To run one provider, use `--agent codex --codex-model YOUR_CODEX_MODEL` or `--agent claude --claude-model YOUR_CLAUDE_MODEL`.

**A comparison runs real agents and consumes your provider usage.** One repetition with both agents starts four agent processes: baseline and candidate for each. Use `--repeat 3` for repeated pairs; variant order alternates on each repetition. `--timeout 600` sets the per-process time limit for agents and checks; allowed range is 1–3600 seconds.

For each run, `rer`:

1. Creates a disposable local clone at the saved task revision, without changing your checkout or Git worktree metadata.
2. Replaces discovered configuration with the captured baseline or candidate snapshot, preserving executable support files. The source fixture stays the same for both variants.
3. Runs your verification command before the agent; reports when the check already passed, since it may not establish task completion.
4. Runs the chosen agent with the same requested model in both variants.
5. Runs your verification command again after successful agent completion, and saves traces, check output, configuration snapshots, and change evidence.
6. Removes the disposable clone and prints the local Markdown report path.

The check runs through `/bin/sh` using your account permissions. Use a check you trust. Codex uses its `workspace-write` sandbox with interactive approvals disabled; Claude uses `acceptEdits` and its existing permission rules. `rer` never passes a permission-bypass flag. Commands requiring additional approval can fail in non-interactive runs. Disposable clones isolate repository edits, **not the operating system or external services**.

Dependency installation is not automatic. Your check can bootstrap disposable dependencies if needed, but it must leave tracked files unchanged. Ignored build products may be retained between the precheck and agent. Submodule fixtures are rejected; Git LFS files are not hydrated. Personal settings, hooks, MCP data, and cache state are not frozen; fresh clone paths can affect native trust and configuration discovery. Use an external isolated runner for untrusted fixtures.

### 4. Read the result

The report shows baseline/candidate check results, reported input/output and cache tokens, agent duration, per-agent input deltas, and paired check regressions. JSON additionally records requested models, agent versions, observed models where exposed, tool observations, configuration hashes, and precheck results.

`PASS` means the agent completed successfully and your check exited 0. A passing generic build does not prove the requested change was made; use a targeted check. Agent self-reports and exit code alone are not accepted as success. Missing telemetry is `unknown`, not zero.

Compare each agent against its own baseline. Codex input usage includes cached input; Claude input usage is uncached and its cache categories are separate. These are cumulative run usage fields, not context-window occupancy or a subscription charge. Tool events provide partial observations, not proof of skill activation or why behavior changed. Repeated paired runs and meaningful checks are necessary before drawing conclusions about efficiency.

Reports are advisory: token increases alone do not fail a run. Abrupt termination or a process crash can leave a `rer-replay-*` directory in the system temporary directory; remove it after confirming no run is active. Exit codes are `0` when every agent/check succeeds, `1` when a verification check fails, `2` for invalid arguments, `3` for setup/process/trace errors, and `130` for an interrupted Java thread. Timeouts and malformed/missing terminal traces are errors. Partial reports are retained when completed runs exist.

## Where does data go?

```sh
rer data
rer case list
rer case remove exporter-fix
```

Default data roots:

- Linux: `${XDG_DATA_HOME:-~/.local/share}/rer`
- macOS: `~/Library/Application Support/rer` (or `$XDG_DATA_HOME/rer` when set)
- Override either platform with `RER_DATA_HOME=/absolute/path`.

Each checkout has a separate directory under `repositories/<path-hash>/`, with `cases/` and `reports/`. Its directory is restricted to your user. Moving a checkout changes this identity. `rer` refuses a data directory inside the repository.

Removing a case leaves its historical reports. To remove reports, delete the selected report directory under the path printed by `rer data`. To remove all rer data for a checkout, delete that checkout's printed data directory. Local snapshots and traces can contain prompts, source code, and configuration values; no report is uploaded by `rer`.

To uninstall the application, remove `~/.local/bin/rer` and the desired version directories under `~/.local/share/rer/versions`. Evaluation data is separate and remains until you delete it.

## Build and develop

Source builds require **JDK 21+**, **Maven 3.9+**, Git, and a POSIX system:

```sh
mvn -q verify
./rer --help
./rer inspect
sh scripts/test-installer.sh
sh scripts/package.sh
```

Java integration tests use temporary real Git repositories and simulated Claude/Codex executables, without paid model calls. They cover the paired comparison path, external storage, checkout preservation, trace parsing, errors, timeouts, and configuration boundaries. Installer fixtures cover all four OS/architecture combinations and checksum rejection. Release builds run tests and smoke-test the bundled launcher on each target runner. Compatibility with future CLI trace schemas requires ongoing verification; raw traces are retained for diagnosis.

To publish a release after changes are committed and pushed, tag the matching POM version and push the tag, for example `git tag v1.1.0` followed by `git push origin v1.1.0`. The release workflow tests and packages all four targets before publishing archives and `SHA256SUMS`. A manual workflow dispatch builds downloadable Actions artifacts without publishing a release.

## Existing context export

The original `rer context --manifest ... --output ...` command remains available. Its manual manifest workflow is optional and separate from the new commands. See the [legacy context packer guide](docs/legacy-context-packer.md), [original specification](docs/project.md), and [pivot research](docs/pivot-proposal.md).
