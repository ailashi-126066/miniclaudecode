# TeamState、无活动超时与任务交接

## 数据归属

TeamState 包含 agents、tasks、mailboxes。`state.py` 定义 schema 和按 ID 合并的 reducer，`framework.py` 用不调用模型的 LangGraph 执行一次业务状态更新。协调图明确关闭 checkpoint 继承，不占用子 Agent 的执行 checkpoint。

Team 的业务变更仍追加到原来的 `team_transition` JSONL；落盘成功后更新同一份 State。成员、任务和邮箱的旧 records 属性只是这份 State 的访问入口，不是三套独立副本。原始会话、工具结果和消息 parent_id 继续保存在各会话 JSONL 中。没有新增磁盘 checkpoint 数据库。

任务执行对象持有子会话及活动通知；子会话的运行容器持有该任务的图和内存 checkpointer。A→B 交接保留 session_id、run_id、thread_id、worktree 和已完成结果，只更换成员身份、执行代次及运行资源。

## 派发和验收

主 Agent 使用 create_task 定义 description、depends_on、acceptance 和 scope，再用 spawn_agent 指定 task_id。没有 task_id 时，spawn 为这项委派建立任务。任务说明由看板自动附加：目标、依赖负责人及结果引用、验收条件、允许修改范围。成员不能自行领取另一个任务；需要重新分工时发消息给 lead。

scope 使用工作区相对文件或目录，不使用 glob。空列表代表整个自有工作区。worker 的文件写入和 Bash 沙箱都使用这个范围；读取仍可查看工作区中与任务有关的其他代码。

任务保存 open、in_progress、submitted、done、failed、cancelled。依赖不满足时 open 在查询结果中附带 blocked_by。只有上游 done 且其 worker 代码已合并，才可派发下游。新 worker 从包含上游成果的当前主分支创建工作区。

子 Agent 正常完成只将任务转为 submitted。成员可以通过 update_task 提交 result、artifact_refs 和 checks；只有 lead 可以验收为 done。验收必须等待成员执行结束，必要验收项全部通过，合并也必须先验收。修改任务合同需要先由 lead 退回 open；活跃成员必须先停止，才能取消其任务。

check 包含 name、status（passed/failed/not_run）和实际工具结果的 output_id（未执行时可省略）。程序从该任务会话读取真实结果，核对错误状态和退出码。worker 命令执行前后记录代码树版本，代码变化或证据版本不匹配时不能宣称通过。测试通过仍不代替 lead 对需求的语义验收。

artifact_refs 使用现有 output_id，或 worker 变更中的 `file:相对路径`。大报告、diff 和完整日志留在原有文件/工具输出存储中，看板只保存简短结果和引用。

## 邮件

send_message 支持 to、text、task_id、kind 和 artifact_refs。kind 为 message、progress、blocked 或 delivery。发送者与执行代次来自绑定的 TeamMember，不接受模型伪造。各 Agent 只接收发给自己的邮件；ACK 只表示已接收，不表示已执行或已验收。

交接把相关未处理邮件改投新负责人，保留原消息 ID 和发送者。已接收内容仍在任务会话中，不重新生成一份交接历史。普通取消保留其原有终止语义，不冒充交接。

## 无活动超时

ChildEventRelay 接收模型请求开始/结束、文本与推理输出、工具实际调用/输出/结束、图运行边界和审批事件。现有 tool_execution_start 仍表示准备；新增 tool_action_start 位于权限检查之后，tool_action_end 表示调用退出；tool_execution_end 仍只在结果保存后发布。

`TeamState.agents[id].last_activity_at` 是最近有效活动时间。phase 和按请求/操作 ID 区分的 active_steps 用于解释正在等待什么。活动只接受当前会话、当前运行和当前代次，重复事件、普通通知、其他任务和旧成员不能续期。高频输出只更新内存 State，阶段边界落盘，不逐 token 追加 Team JSONL。

watchdog 等待活动通知或到期时间，不扫描日志文件，不请求模型。没有活动超过 idle_timeout 才触发处理。模型/工具实际输出会重新计时；审批等待暂停计时。安静的 Bash 使用其自身执行期限，加上停止确认时间供进程收尾；并行工具保留各自期限。任务总时长默认不设硬限制。

默认配置：

| CLI | 默认值 |
|---|---:|
| `--team-idle-timeout` | 180 秒 |
| `--team-stop-timeout` | 15 秒 |
| `--team-max-handoffs` | 1 次 |

同名 AppConfig 字段分别为 team_idle_timeout、team_stop_timeout、team_max_handoffs。0 次自动交接仍会停止超时执行并报告失败，不会无限换人。

## A→B 交接

lead 可以调用 handoff_task(task_id)。无活动超时、可恢复的 provider_timeout/provider_connection 也可以在次数限制内发起自动交接。权限拒绝、测试不通过、依赖未满足不会因为换人而被绕过。

1. 准备暂停，保留任务运行容器，不执行普通取消的 run_end 收尾。
2. 标记 stopping，取消旧 driver，并等待其工具清理。
3. 停止未确认时记录 stop_unconfirmed，不创建 B；旧执行后来退出才更新为 interrupted。
4. 停止确认后更换负责人、递增 generation，转交待处理邮件，保留原 worktree。
5. 新成员重建模型/工具资源，复用任务的内存 checkpointer 和执行数据，通过原 thread_id 继续。
6. ToolSearch 披露结果和已读取文件版本一并衔接。已保存的结果不重复执行；中断的写操作先保存效果不确定的结果，不能从 checkpoint 直接盲重放。

如果旧执行在取消期间已经正常完成，任务进入待验收，不再创建替代者。用户取消与交接竞争时，取消阻止后续替代者启动。

## 整个程序退出

当前 checkpointer 仍在内存。整个程序退出后只能从 JSONL 恢复历史与业务状态，旧执行标记 interrupted，不宣称恢复丢失的图位置。用户明确发起的新运行使用历史重新开始；进程内的 handoff_task 与此区别清楚。

本实现不声称能在同一个 Python 事件循环硬卡死时强制杀死单个 Agent，也不增加跨进程租约或取证式恢复系统。

## 验证

测试覆盖有效输出续期、重复与其他任务事件不续期、安静长工具、审批暂停、停止未确认、自动交接预算、邮件转交、旧代次写入拒绝、验收与依赖。真实 SDK/LangGraph 测试验证同一 run_id 续接、用户输入只保存一次、已完成读取不重复、文件读取版本衔接，以及已批准写操作中断后不重复执行。
