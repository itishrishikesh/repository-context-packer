# Release 5 — Repository Context Packer

## Product overview

Prepare a bounded, traceable context package from selected repository files and technical notes. Make the selection visible and support experiments on whether context choices improve task outcomes.

## Daily example

Before changing the tracker app's exporter, I select its implementation, tests, schema, and one design note. The tool creates a context document with file paths, source hashes, and line references. It reports omitted material and why it was omitted.

I use that document with my chosen coding tool. On another day, I compare this explicit selection with a simple lexical selector using the same evaluation tasks.

## First useful release

- A manifest listing permitted source roots and selected files.
- Respect Git ignore rules plus explicit product exclusions; require deliberate opt-in to ignored content.
- Restrict resolved paths to permitted roots, including symlink handling.
- Deduplicate identical content and apply deterministic ordering.
- Enforce a byte budget first; label any token estimates and the tokenizer used.
- Export a Markdown package and a JSON manifest with provenance and omission reasons.
- Report source edits during capture rather than silently describing mixed inputs as one snapshot.

## Acceptance evidence

Excluded files never appear. Oversized input produces a visible omission decision. Repeated identical input produces identical output. A source edit changes its recorded identity. Paths escaping permitted roots are rejected. A fixture with duplicated content is represented according to the documented deduplication rule.

## Engineering decisions to explain

Explain selection versus retrieval, boundary handling, deterministic output, freshness, and budget allocation. File contents are data; instructions inside them do not acquire authority merely because the packer included them.

This first release is a useful utility, not a strong retrieval-engine claim. Senior-level depth would come later from evaluated selection strategies, incremental indexing, and measured trade-offs.

## Eight-hour boundary

No embeddings service, vector database, AST analysis across languages, automatic secret-safety guarantee, or model integration. An explicit manifest should solve an actual daily problem before ranking is added.

## Later experiments

Compare explicit selection, simple lexical retrieval, and one improved strategy on the same task set. Measure relevant-file coverage, task outcomes, context size, and preparation latency. Smaller context is valuable only when the quality trade-off is understood.
