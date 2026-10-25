# D1+E2 Integration — API34

## Authorization and scope

- 负责人2026-10-25在D1独立功能验收APPROVE（`cf29bad`）后同意启动独立集成任务，目标为：同步/合并D1与E2、统一Coordinator接口、重新构建并联调回归；不合并main、不创建/合并PR、不关闭issue、不进入G2。
- 工作树：`D:/record/.worktrees/d1-e2-integration`（分支`codex/d1-e2-integration`，基于`origin/main=c612e89`）。先合入E2 PR #29（`0b68bb6`，已含main），再合入D1 PR #30（`52e3398`，代码`cf29bad`）；E2 PR #29已合入main `992181f`，集成分支同步main `8d74329`后推送为 [PR #32](https://github.com/paher-din/record/pull/32)。
- PR #32 已合并 main `4f92878`；D1 PR #30 由其替代，issue #14 已关闭；issue #13 由 E2 #29 合并关闭。

## Integration result

- **冲突：** E2合入干净；D1合并只在`docs/index.md`与`docs/specs/record2/v1/plan.md`冲突。解决方式：保留G0 §31、E2 §32，D1顺延为§33（标题/子节/内部引用/index链接同步）；源代码由Git自动合并后人工核对。
- **接口统一：** E2的`AgentRunCoordinator.run(sessionId, userText, identity: RunIdentity? = null)`保留；`AgentRunOwner.launchRun`预生成并传入`RunIdentity`。D1的`recordTools`、Run墙钟、GuardedHttpClient（持久Run状态/授权/墙钟/用量）、UsageUnknown、poisoned与业务硬失败全部保留在合并后的Coordinator。E2 owner的取消/授权失效/删除/恢复路径可调用该Coordinator。
- **测试适配：** E2 host/device coordinator显式传入与history/security相同的时钟（`sessionClock`/epoch 0），因为D1 Run墙钟以`start.startedAt`为基准；否则固定时钟夹具会被误判为超时。D1 host的`ToolFakeHistory`补齐E2新增的`activeRun/deleteSession`接口。
- **新增集成测试：** `D1E2IntegrationContractTest.ownedRunWithRecordToolsIsCancelledBeforeLateReply`——`AgentRunOwner` + D1 `recordTools` + 真实Room；首个模型请求触发`search_records`，第二个请求阻塞时owner取消；结果：持久CANCELLED、`handle.await()`取消、无assistant消息。

## Verification

| Suite | Result |
|---|---|
| host Debug/Release | **150/150 PASS** |
| combined device regression | **OK (179 tests)**：D1 `RecordToolsContractTest` 17、`D1E2IntegrationContractTest` 1、E2 `SessionLifecycleContractTest` 9、E1 `SessionRunContractTest` 15，其余Schema/Proposal/Commit/Note/Finance/Link/AiSecurity/Keystore/DeepSeekProtocolFailure等；`SessionRunRealContractTest`无Key按既有约定Assume |
| E2 process matrix | **RUN_LIFECYCLE: PASS**（`build/e2/process-94852b01681045f8979f267f3186b268/`；真实force-stop/冷启动零自动模型/工具调用、新Run恰+1） |
| build | `check :app:assembleDebug :app:assembleDebugAndroidTest` PASS；`:app:assembleRelease` PASS |

- **APK：** debug `3aa2b03702f18898f79aba2e1c18744113e7a2612ece5ecbdcd70482c5b81e9e`；androidTest `b681389efe80af37351ee69e58b19508ab3d54ce6e6629126620369eac2a1c45`；release-unsigned `0f88685c22944e05b5cb44fead804ebe0a1d15a12d683e30f19ceb235a0876ff`。设备安装的debug/test摘要与构建产物一致；E2进程脚本在安装后复核身份。
- **回执：** `record2/build/d1-e2/instrument-integration2.txt`（1）、`instrument-combined-regression.txt`（179）、`instrument-e2-lifecycle.txt`（9）、`e2-process-verification.txt`、`d1-e2-build.log`、`d1-e2-test-build2.log`、`d1-e2-release.log`；原始DB/回执在忽略的`record2/build/`。
- **真实DeepSeek：** 本轮未重跑；D1分支最后真实结果仍为3/3（prepare/revise、search、撤销0请求），见[D1证据](2026-10-25-d1.md)。集成回归使用脚本传输。

## Boundaries / next steps

- PR #32 已合并 main `4f92878`；E2 #29 已合并，D1 #30 由 #32 替代，issue #13/#14 已关闭。合并后的 main 仍需执行 G2 联合验收。
- 组合验证覆盖 D1 工具 + E2 owner 的 RunIdentity/取消/进程恢复/既有 Room 回归；真实 DeepSeek 组合全量未重跑。
- F2 UI 绑定、备份/G0/G1/G2 及产品默认预算冻结不在本任务范围。
