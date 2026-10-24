# Record 2.0 UI/UX Skills 安装与验证

2026-10-24（Asia/Shanghai）。来源、许可和维护评估见[六仓库研究](record2-uiux-skill-research-20261024.md)。本记录只拥有本轮安装事实，不批准产品 UI 或变更工程契约。

## 1. 实际组合

| 角色 | 实际安装目录 | 上游固定提交 | 结果 |
|---|---|---|---|
| 重点创意：形状、字体、构图与动效 | `C:/Users/p3148/.codex/skills/m3-expressive` | `abhixv/m3-expressive-design-skill@5e7a9cd90d62bbff2dbe7a708206aeb4003e8a4e`，`skills/m3-expressive` | 文本精简安装，21 份 reference；保留 README、visuals/INDEX.tsv、LICENSE、NOTICE；不下载图片和更新脚本 |
| Android 原生模式 | `C:/Users/p3148/.codex/skills/mobile-android-design` | `wshobson/agents@0874a27b17a22e2b7c2e2bcd725768e0803cad06`，`plugins/ui-design/skills/mobile-android-design` | 安装入口及 4 份 reference，补保留根 LICENSE |
| Material roles / 组件 | `C:/Users/p3148/.codex/skills/material-3` | `hamen/material-3-skill@14385f2bf3804d8779f8b4db2604211f1e70b4c1`，`skills/material-3` | 安装入口及 6 份 reference，补保留根 LICENSE |
| Compose 实现与审查 | `C:/Users/p3148/.codex/skills/compose-agent` | `hamen/compose_skill@f815c31d6cc1a4af4ce2796d08eb7d8b506785c9`，`skills/compose-agent` | 安装入口及 15 份 reference，补保留根 LICENSE；Apache 衍生规则的上游来源见研究报告 |

前三个完整目录安装使用内置 skill-installer 的固定 SHA 下载/验证/复制逻辑。为遵守本项目“临时目录删除也需明确许可”，在专用临时目录制作其副本，仅把 `finally` 清理改为打印并保留缓存，并加入原 helper 的 import 路径；没有修改系统 installer。副本：`C:/Users/p3148/AppData/Local/Temp/record2-uiux-skills-20261024/install-skill-preserve-cache.py`。Expressive 从固定 SHA 的只读研究缓存定向复制，等价于上游 slim 路径，不运行远程脚本。

安装命令的实际参数形式：

```powershell
python <preserve-cache-helper> --repo wshobson/agents --path plugins/ui-design/skills/mobile-android-design --ref 0874a27b17a22e2b7c2e2bcd725768e0803cad06 --dest C:\Users\p3148\.codex\skills --method download
python <preserve-cache-helper> --repo hamen/material-3-skill --path skills/material-3 --ref 14385f2bf3804d8779f8b4db2604211f1e70b4c1 --dest C:\Users\p3148\.codex\skills --method download
python <preserve-cache-helper> --repo hamen/compose_skill --path skills/compose-agent --ref f815c31d6cc1a4af4ce2796d08eb7d8b506785c9 --dest C:\Users\p3148\.codex\skills --method download
```

目标此前均不存在；没有覆盖既有 Skills 或项目 AGENTS。未安装 Ruben 的 RN/Expo 默认实现入口、全量 Compose audit 或 Android 官方整包。既有 `adaptive`、`edge-to-edge`、`jetpack-compose-performance`、`navigation-event`、`testing-setup` 等继续按需使用，未整体升级。

## 2. Codex 实际发现

启动一个仅用于本地元数据读取的 `codex app-server --listen stdio://`，完成 `initialize` / `initialized`，调用：

```json
{"id":2,"method":"skills/list","params":{"cwds":["D:\\record"],"forceReload":true}}
```

实际返回四项均为 `scope=user`、`enabled=true`，路径均指向上表目录：

- `mobile-android-design`
- `m3-expressive`
- `material-3:material-3`
- `compose-agent:compose-agent`

后两项的命名空间来自保留的上游插件元数据；不能仅按裸名称过滤后误报未加载。此测试验证了本机当前 loader 的发现与 YAML 解析，不声称正在进行的主会话已自动注入全部正文。主会话已实际读取入口和相关 reference。新 Skills 可在下一轮使用；如果界面未刷新，可重启 Codex。[官方 Skills 发现说明](https://learn.chatgpt.com/docs/build-skills)目前列出 `.agents/skills` 等标准位置；本机 `.codex/skills` 路径的兼容性由上述实际接口返回证明。

loader 同时返回一项**既有、与本轮无关**的错误：`onetake/SKILL.md` description 超过 1024 字符。未修改它；它不阻止本轮四项发现，不能把整个 Skills 环境宣称为零错误。

## 3. 内容与完整性

| 入口 | 原样 SKILL.md SHA-256 |
|---|---|
| mobile-android-design | `0a209a1c45585ce0056548cc2f0fd352b7fde5980a3b5dd397a9daf5dff19fe7` |
| material-3 | `5bbd6a903bb3839561c496b6edac04dcdd414fe0669e4910a7dba44acdf0ee7f` |
| m3-expressive | `82bf71dff32f388793ae2524461be9741a91b405d2027b0c4660a3bf01f4a6d5` |
| compose-agent | `7c815dc8f21e4b77f561509f080a0ead20aa5fa8b021aa7e0d38ef886e874ebf` |

静态确认 UTF-8 入口、相关 references、许可证存在；Expressive 的视觉索引存在，图片按 slim 模式有意缺席。因此加载 `visuals.md` 时不能假装图片在本地或自动把它们复制进产品。若未来确需查看，回到官方来源按需阅读。

受控阅读核验：Expressive fast spatial 的示例 token 为 damping `0.6` / stiffness `800`，effects damping `1.0`；外圆角 48dp、内边距 16dp 的示例内圆角为 32dp。它们来自 `references/tokens.md` / `shape.md`，不是 Record 已批数值。Compose 入口可路由到 state/effects/animation 等专题，没有要求引入 RN 或 Web 运行时。普通 Host 的 `setContent { }` 不能被审成“已有产品重组问题”。

## 4. 项目集成与冲突裁决

新增 [skills/record-android-design](../../skills/record-android-design/SKILL.md) 作为 Record 专用入口；新增 [record2/PRODUCT.md](../../record2/PRODUCT.md) 为设计上下文指针。现有 `.agents/skills` 是指向 `D:/record/skills` 的 Junction，本轮没有更改这个映射。最终 loader 实际返回 `record-android-design`、`scope=repo`、`enabled=true`、路径为 `D:/record/skills/record-android-design/SKILL.md`。它们不替代现有来源，也不授予写 UI 权限。

| 冲突 | 本项目取舍 |
|---|---|
| 通用设计 Skill 限制弹性/大圆角，Expressive 主张形态变化 | 按负责人本轮表现力目标做具体候选；高频输入不弹跳，空间变化可用 spring；不机械套任一审美禁令 |
| 默认动态取色 vs 产品品牌 | 主方案静态品牌色；动态色作为以后可选主题，不能静默覆盖语义与主识别色 |
| Material 文档旧 overlay 表 vs 新 surface roles | 使用当前库 tone-based surface roles；不手工按海拔加主色透明层 |
| Compose Skill 的 Nav3/Paging 默认倾向 | 保留现有 Navigation 2.9.8；无实际分页收益不增 Paging 依赖；未来按独立任务评估 |
| Strong Skipping / compiler 轨道错误 | 以当前官方规则和 Kotlin 2.3.10 匹配插件为准；不加无证明的 Stable/Immutable 或 remember 绕过 |
| Expressive future API / 全部数值都来自 Skill 的主张 | 当前 resolved API 与第一方资料优先；Record 自定数值单独标为设计候选，不冒充 M3 官方 token |
| Skill 自动脚本、全量审计、更新与安装建议 | 只运行本轮授权的下载和验证；不触发代码重写、远端 tracker、依赖/环境/设备/Provider 操作 |

## 5. 验证边界

PASS：四技能及项目 router 的 Codex loader 发现；相关正文可读、许可证保留、精简图片边界明确。安装内容与固定 SHA 下载缓存逐文件比较：mobile-android-design 5/5、material-3 8/8、compose-agent 19/19、Expressive 所选文本/索引 24/24 字节一致，无缺失或改写。外加根 LICENSE/NOTICE 为有意补充。

本轮 T0：六份新增 Markdown/Skill 文件的 UTF-8、围栏和 40 个本地链接检查通过，`git diff --check` 通过；369 份任务前受保护文件中只有授权的 Plan 内容变化，Git index 未变化。既有 `docs/index.md` 的脏修改原样保留；AGENTS/R1/R2 Kotlin/构建/Schema 和旧 F0 稿均未被本任务改写。视觉生成与最终方向接受仍开放，不把文件完整性通过当审美通过。

NOT RUN：模型自动选择命中率对照实验、设计质量基准、Android 编译/设备/性能/业务验收。安装 Skill 不等于提升设计已被证明，负责人对视觉候选的拒绝应保留。后续以同一内容、同一任务的图稿/交互/性能证据评价设计收益。
