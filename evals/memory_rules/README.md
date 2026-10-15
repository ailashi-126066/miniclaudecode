# memory_rules（存档）

存档，依赖的运行时已删除，不可直接运行。

这里保存的是旧记忆流水线中的程序规则优化（`memory_rules.py`）、规则对比评估
（`memory_rule_evaluation.py`、`memory_evaluation.py`）、对应测试和
`run_rule_comparison_quality.py` 评估脚本。它们依赖的 `pycode.core.memory_evidence`、
`pycode.services.memory_commit` 等模块已在简化记忆时删除，导入会失败。

- 不再维护，仅供查阅历史实现。
- pytest 不收集本目录（`pyproject.toml` 的 `norecursedirs`），ruff 在 `pyproject.toml`
  中排除本目录，`scripts/check_architecture.py` 跳过本目录；`compileall` 只检查语法，仍然覆盖。
