## 提交信息建议

```bash
git add -A
git commit -m "feat: 实现完整的类型化 LLM 错误处理系统

- 新增语义化异常分类（认证、限流、上下文过长、网络错误）
- 实现 Agent 自动恢复策略（上下文压缩、智能重试）
- 限流恢复支持 Retry-After 优先和指数退避
- 新增 17 个测试（LlmException + Agent 恢复策略）
- 保持向后兼容性

测试: 144/144 通过
详见: docs/错误处理改进完成报告.md
"
```

## 关键改动

### 核心文件
- `src/main/java/com/mewcode/llm/LlmException.java` - 异常分类体系
- `src/main/java/com/mewcode/llm/StreamEvent.java` - 类型化错误事件
- `src/main/java/com/mewcode/llm/LangChainClient.java` - 异常集成
- `src/main/java/com/mewcode/agent/Agent.java` - 恢复策略

### 测试文件
- `src/test/java/com/mewcode/llm/LlmExceptionTest.java` - 新增 11 个测试
- `src/test/java/com/mewcode/agent/AgentErrorRecoveryTest.java` - 新增 6 个测试

### 文档
- `docs/错误处理改进完成报告.md` - 完整技术报告
- `IMPLEMENTATION_SUMMARY.md` - 快速总结
- `FIXES.md` - 更新完成状态
