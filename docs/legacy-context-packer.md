# Repository Context Packer

`rer` packages explicitly selected files from Git working trees into `context.md` and `manifest.json`. It records SHA-256 hashes, source line references, aliases, and omission decisions, and applies a budget to unique source bytes.

Requires **JDK 21 or newer**, **Maven 3.9+**, and **Git** on `PATH`. The executable launcher uses a POSIX shell. On other platforms use the JAR directly. CI runs the same build on JDK 21 and Linux.

## Build and try this repository

```sh
mvn -q verify
./rer context --manifest examples/context.yaml --output build/context
```

The build creates the dependency-inclusive `target/rer.jar`. You can also run:

```sh
java -jar target/rer.jar context --manifest examples/context.yaml --output build/context
```

Inspect `build/context/context.md` and `build/context/manifest.json`. To verify repeatability:

```sh
cp build/context/context.md /tmp/rer-first.md
cp build/context/manifest.json /tmp/rer-first.json
./rer context --manifest examples/context.yaml --output build/context
cmp /tmp/rer-first.md build/context/context.md
cmp /tmp/rer-first.json build/context/manifest.json
```

Both `cmp` commands should exit successfully with no output.

## Test on your project

Build this tool once, then create `context.yaml` in the project you want to package. The project must already be a Git working tree; a commit is not required. Replace these file paths with files that exist in your project:

```yaml
version: 1
byteBudget: 50000
roots:
  - name: app
    path: .
sources:
  - root: app
    path: src/exporter.ts
  - root: app
    path: tests/exporter.test.ts
  - root: app
    path: schema/export.json
  - root: app
    path: docs/export-design.md
exclusions:
  - root: app
    path: .env
  - root: app
    path: node_modules
```

For a daily exporter change, explicitly select its implementation, tests, schema, and one design note. From that project's directory run:

```sh
/home/fedora/Documents/projects/repository-context-packer/rer context \
  --manifest context.yaml --output build/context
```

Use the tool's actual checkout path if different. The launcher finds its JAR independently of the current directory. Root paths are relative to the **manifest's directory**; output paths are relative to the **calling directory**. Read `context.md`, inspect `manifest.json`, and add your output directory to your project's `.gitignore` if desired.

Useful manual checks:

- Reduce `byteBudget` to `1`: oversized files appear as `BUDGET_EXCEEDED`, and smaller files can still fit.
- Select two files with identical bytes: the first logical `root/path` is canonical; the other has `DUPLICATE_CONTENT` and the canonical identity.
- Select an ignored file: it has `GIT_IGNORED`. Set `allowIgnored: true` on that source alone to include it. An explicit exclusion still wins.
- Change a selected file and rerun: its raw-byte hash changes.
- Select `../outside.txt` or a symlink: the run fails and preserves the old package.

## Manifest contract

`version: 1`, a positive integer `byteBudget`, nonempty `roots`, and a `sources` list are required. An empty source list produces an empty complete package. `exclusions` defaults to an empty list; each source's `allowIgnored` defaults to `false`. Unknown fields, wrong scalar types, duplicate YAML keys, duplicate root names, duplicate normalized source identities, and unknown root references are rejected.

Root names use letters, digits, `_`, `-`, and `.` and start with a letter, digit, `_`, or `-`. A root can be a subdirectory of a Git working tree; its declared location is canonicalized. Source and exclusion paths are relative, use `/`, and select exact paths or component-based exclusion subtrees. Backslashes are normalized to `/`. Absolute paths, `..` components, control characters, and empty normalized paths are rejected. Globs and directories as selected sources are unsupported. Every selected source must exist and be a readable regular file, including sources that will subsequently be excluded.

A selected file with any symlink component below its canonical root is rejected. `.git` components, Git metadata, the output subtree, and explicit exclusions are always excluded before content capture. Resolved exclusion locations also apply across overlapping roots. `target` excludes `target/a`, while `targeted/a` remains eligible. Roots cannot be inside the output directory. Existing output ancestors cannot be symlinks, and output cannot be inside the declared roots' Git metadata.

Git makes ignore decisions using the installed Git executable, index, nested rules, negations, repository excludes, and your configured global excludes. Tracked files remain eligible even if a pattern matches. Only the ignored source's own `allowIgnored: true` overrides Git ignore; it cannot override product exclusions. Ignored omissions and opted-in included sources record the matching rule. External rule-file paths are represented as `external/<basename>` to avoid publishing machine-specific absolute paths. Reproducibility assumes equivalent Git configuration as well as equivalent source inputs.

## Output and budgeting

Sources sort by logical `root/path`, independently of declaration order. Equal raw-byte SHA-256 hashes form one group: the first identity is canonical, and remaining identities are aliases. A canonical file is included whole if its raw bytes fit the remaining budget. Oversized files are omitted and allocation continues. Empty files cost zero bytes.

Only unique raw source bytes count toward the budget. Markdown, JSON, aliases, and headings add output size beyond that budget. No truncation or token estimate is performed. Aliases cost zero and appear on included canonical entries. Every alias also has a `DUPLICATE_CONTENT` omission with its canonical identity, including when the canonical file exceeds the budget; follow that canonical's outcome to determine availability. Included canonical entries plus omission entries account for every selected source exactly once.

Both artifacts use UTF-8 and LF, stable ordering, and no timestamps or absolute repository paths. JSON has `formatVersion: 1` and `status: COMPLETE`, totals, included sources, and omissions. Included entries contain raw byte size, SHA-256, encoding, line range, and aliases. Budget omissions contain original size, required bytes, and remaining bytes. Policy omissions do not read content and carry no content hash or size. Invalid UTF-8 is omitted as `UNSUPPORTED_ENCODING`.

Raw bytes determine hashes and budgets; rendered text normalizes CRLF and lone CR to LF. An empty file has `L0-L0`. Other files have `L1-LN`, where a terminal newline adds no extra line. Markdown fences are longer than every backtick run in the captured content, and metadata is escaped.

## Failures and publication

| Exit code | Meaning |
| --- | --- |
| 0 | Complete package, possibly with ordinary omissions |
| 2 | Invalid manifest or unsafe path |
| 3 | Filesystem, Git, or output failure |
| 4 | Source changed during capture |

Ordinary omissions are `EXCLUDED`, `GIT_IGNORED`, `BUDGET_EXCEEDED`, `UNSUPPORTED_ENCODING`, and `DUPLICATE_CONTENT`. Errors identify the source where applicable; failed runs do not write partial artifacts.

Capture compares size, modification time, and file identity before and after each read, then hashes the raw captured bytes. This detects observable mid-read changes. It does not provide a repository-wide atomic snapshot or complete protection against hostile filesystem races. Changes that restore all observed metadata can escape detection.

Use a **dedicated output directory**. Replacement supports an absent directory, an empty directory, or a directory containing only regular `context.md` and/or `manifest.json` files. Unrelated entries are rejected. Both artifacts are rendered before publication, written into a sibling staging directory, then published by moving the old directory to a sibling backup and moving staging into place. A handled publication failure restores the backup, and owned temporary directories are cleaned up. If filesystem failure also prevents rollback, the backup is retained and its location is reported.

A directory rename is requested atomically when supported, with a regular same-parent move as fallback. Replacing an existing package requires **two directory moves**: readers can briefly see an absent output directory. This is not an atomic pair of file moves, and there is no crash recovery or durability guarantee. After a process crash, inspect `.rer-stage-*` and `.rer-backup-*` siblings; a backup may contain the previous complete package. Use one writer per output directory. Filesystem errors during cleanup may require manual removal of those owned temporary paths. Network filesystem and non-Linux rename behavior are not verified by CI.

Source contents are untrusted data: instructions inside them gain no authority from packaging. This utility does not automatically detect secrets. Review your explicit selections and output before sharing.

## Development

```sh
mvn -q verify
```

The fixture suite uses real temporary Git repositories and controlled capture/publication failures. It checks validation, boundaries, Git behavior, deterministic capture, UTF-8, budgets, aliases, line references, rendering, rollback, and CLI behavior. Production targets Java 21; local development also works on newer JDKs.

The original product and implementation specifications are preserved in [project.md](project.md) and [project-guide.md](project-guide.md). Retrieval, ranking, embeddings, AST parsing, globs, tokenizers, UI, and model integration are deferred.
