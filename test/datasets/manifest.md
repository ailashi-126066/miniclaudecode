# Evaluation dataset manifest

| Category | Local source directory | Pinned upstream commit | License | Planned use |
| --- | --- | --- | --- | --- |
| Code repair | `sources/multi-swe-bench` | `24f493f8a103` | Apache-2.0 | Java issue-resolution subset |
| Repository retrieval | `sources/repobench` | `e0cfd34c9e7c` | CC-BY-4.0 | Repository-level retrieval and completion queries |
| Cross-file retrieval | `sources/crosscodeeval` | `40c68d2b7ca2` | Apache-2.0 | Java cross-file retrieval/completion queries |
| Long-term memory | `sources/longmemeval` | `9e0b455f4ef0` | MIT | Retrieval protocol and memory-evaluation fixtures |
| Agent environments | `sources/agentbench` | `d1e4a10db08c` | Apache-2.0 | Tool-use and fault-injection evaluation patterns |

These upstream repositories are references and raw inputs, not drop-in MiniClaudeCode test cases.
Adapters must pin task IDs, preserve the original license, and create isolated temporary workspaces
before an Agent run. `multi-swe-bench` reports two Python-path case collisions on Windows; use its
Java subset only in this workspace.
