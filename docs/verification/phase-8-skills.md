# Phase 8 Skills and GitHub installation evidence

Phase 8 remains in progress. Python resource tools have separate current-host
evidence in `skill-resources.md`; Hooks, MCP, and Worker fork execution have not
been accepted. The verified features below do not satisfy the
complete extension phase or authorize final Git history integration.

The focused suite in `skills-tests.xml` currently has 42 passing cases. It covers
source parser/substitution/precedence vectors captured before implementation,
native branch snapshots, actual SDK/LangGraph loopback requests, Textual commands,
and installation with controlled HTTP responses and real filesystem publication.
The latest full regression including resource integration and root-branch catalog
admission passed 525 tests, with one Windows file-symlink-privilege skip
(`latest-tests.xml`). See `skill-resources.md` for subsequent focused file-argument checks.

Verified Skill behavior:

- Four ordered sources are recursive and sorted; project sources require the
  explicit application trust option. Scanning reads frontmatter and hashes without
  importing Python resources. Bundled commit/test/review documents retain original
  instructions, mapping the old ReadFile declaration to the native Read tool.
- Initial context contains metadata only. A configurable index budget replaces
  an oversized listing with a SkillSearch hint. Search returns bounded metadata;
  unchanged reload leaves history and request prefix unchanged, while an explicitly
  changed budget is respected.
- LoadSkill and `/skill:NAME ARGUMENTS` append exact raw and substituted snapshots.
  Identical content/arguments are deduplicated. Disk updates, reopening, and branch
  changes preserve their own historical contents; explicit reload/load adds the new
  version. Resources remain on demand, bounded, and constrained to trusted paths.
- Active contents survive actual model-driven compaction and reopen. Every
  projected contribution has a real JSONL source ID; the compaction checkpoint
  preserves Skill snapshots without copying them into the ordinary system prompt.
  Subsequent requests contain each active instruction once.
- Declared tool sets intersect the parent scope and keep the parent permission
  check. Model tools use the common executor, output store, operation IDs, and
  results. A denied call does not execute. Worker-only fork returns an explicit
  unavailable error and does not activate in the parent.
- CLI search/load/reload and TUI search/load/reload/install use public AgentSession
  commands. Opening a pending operation or revert cannot append a new catalog;
  rejected commands leave canonical history unchanged.
- Branching to root preserves the empty target. The next fresh task rebuilds its
  catalog before execution, and cannot inherit old Skill activation. Loading from
  the empty settled branch also initializes metadata before loading instructions.

Verified GitHub installation behavior:

- Source vectors retain GitHub tree, raw SKILL.md, and skills.sh formats. Repository
  roots use the current default branch; explicit `--ref` and `--path` support refs
  containing `/`. Credentials/query/fragment, traversal, reserved Windows paths,
  unexpected hosts, and unsafe or duplicate resource paths are rejected.
- The source ref resolves to an immutable commit before Contents API traversal.
  Complete resources use bounded counts, bytes, depth, and entry limits. Raw URL
  fallback is pinned to the same repository/commit. Per-file SHA-256 and source
  provenance are persisted in the installation.
- An owned staging directory and portable writer lease serialize publication.
  Invalid manifests, unexpected resources, actual size overruns, or cancellation
  do not publish a partial Skill. Reinstallation keeps the entire old version,
  including user notes. A real rename failure restores the old directory.
- Installation refreshes the catalog but does not activate instructions or import
  Python resources. Model InstallSkill requires durable approval; reopen restores
  the same interrupt and settles one paired, stored result after approval/denial.

`skill-online.json` is separate real-network evidence from 2026-10-11: the public
`anthropics/skills` webapp-testing directory at commit
`dbd4588f9e1033efb41dad4bef2f7947c8993d44` downloaded six files (22,394 bytes).
All file hashes, catalog/document access, exact-commit reinstallation, and retained
backup passed. The acceptance script confines downloads to `.acceptance/skill-online`
and executes no downloaded resource. This is not remote model or Langfuse evidence.

Ruff, architecture/branding, 229 local source hashes and two upstream archives,
dependency consistency, and wheel/sdist build pass. Wheel inspection confirms all
three bundled Skill documents and explicit PyYAML/HTTPX dependencies.
`pycode/.git` remains absent; reference directories and final Git integration remain
untouched. Final platform, remote-provider, and Langfuse gates are still outstanding.
