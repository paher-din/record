# Record 2.0 Android UI/UX Skills 来源调研

调研日期：2026-10-24（Asia/Shanghai）。范围：六个指定上游仓库、可安装目录、许可、Codex 可用形式、职责重叠及采用限制。本文件是来源笔记，不批准视觉方向、页面实现、依赖升级或业务契约变更。原配色、单时间线首页和蓝色助手均可作为候选比较，不在本笔记中冻结。

## 1. 结论与最小组合

建议采用四个职责明确的技能：`m3-expressive` 的文本精简版作为重点创意和动效参考，`mobile-android-design` 负责 Android 原生模式索引，`material-3` 负责设计系统与主题检查，`compose-agent` 负责 Compose 实现质量。Expressive 的“按需加载”指按页面选择相关参考，不是可有可无的补充。已有 Android 官方技能负责特定平台问题，不重复整包安装。`mobile-design` 可补充 UX 审查，但其默认实现方向是 React Native/Expo，不应成为 Record 的默认代码实现入口。`jetpack-compose-audit` 留给明确的工程审计任务，不在本次设计调研中自动运行。依据为下表的原始 SKILL/README，而非仓库名称或 star 数。

这些工具不能自动产生 Record 的品牌辨识度，也不能替代真实任务流、中文排版、金额语义、可访问性和截图验收。安装后仍需按项目版本核验；发现的具体技术错误见 §4。

## 2. 固定版本、目录与许可

下列 SHA 来自 GitHub 默认分支的 commit API，时间为对应提交的 committer UTC 时间，不等同于每个技能内容最后修改时间。全部按 SHA 下载只读研究副本；没有执行上游脚本。

| 仓库与永久入口 | 默认分支与固定 SHA | 提交时间（UTC） | 可安装技能路径 | 许可结论 |
|---|---|---|---|---|
| [wshobson/agents README](https://github.com/wshobson/agents/blob/0874a27b17a22e2b7c2e2bcd725768e0803cad06/README.md) | `main` / `0874a27b17a22e2b7c2e2bcd725768e0803cad06` | 2026-10-17 21:39:17 | `plugins/ui-design/skills/mobile-android-design` | [根 LICENSE：MIT](https://github.com/wshobson/agents/blob/0874a27b17a22e2b7c2e2bcd725768e0803cad06/LICENSE)，不是安装整个 marketplace 的授权要求。 |
| [hamen/material-3-skill README](https://github.com/hamen/material-3-skill/blob/14385f2bf3804d8779f8b4db2604211f1e70b4c1/README.md) | `master` / `14385f2bf3804d8779f8b4db2604211f1e70b4c1` | 2026-07-15 22:10:48 | `skills/material-3` | [根 LICENSE：MIT](https://github.com/hamen/material-3-skill/blob/14385f2bf3804d8779f8b4db2604211f1e70b4c1/LICENSE)。 |
| [RubenGlez/mobile-design README](https://github.com/RubenGlez/mobile-design/blob/97474ed9b969d0c7f8a2acf712e30eeb0b9078d1/README.md) | `main` / `97474ed9b969d0c7f8a2acf712e30eeb0b9078d1` | 2026-07-21 10:59:50 | 仓库根目录 `.`，`SKILL.md` 在根目录 | [根 LICENSE：MIT](https://github.com/RubenGlez/mobile-design/blob/97474ed9b969d0c7f8a2acf712e30eeb0b9078d1/LICENSE)。README 明确安装器可能复制整个根目录，包括 `AGENTS.md`/`CLAUDE.md`；不能用这些文件覆盖项目约束。 |
| [abhixv/m3-expressive-design-skill README](https://github.com/abhixv/m3-expressive-design-skill/blob/5e7a9cd90d62bbff2dbe7a708206aeb4003e8a4e/README.md) | `main` / `5e7a9cd90d62bbff2dbe7a708206aeb4003e8a4e` | 2026-10-10 19:39:18 | `skills/m3-expressive` | [LICENSE](https://github.com/abhixv/m3-expressive-design-skill/blob/5e7a9cd90d62bbff2dbe7a708206aeb4003e8a4e/LICENSE) 与 [NOTICE.md](https://github.com/abhixv/m3-expressive-design-skill/blob/5e7a9cd90d62bbff2dbe7a708206aeb4003e8a4e/NOTICE.md)：原创结构/文字组织/脚本 MIT；M3 指南衍生内容标为 CC BY 4.0；AndroidX token 标为 Apache-2.0；图片保留 Google 版权，不能因根 MIT 就宣称图片 MIT。 |
| [hamen/compose_skill README](https://github.com/hamen/compose_skill/blob/f815c31d6cc1a4af4ce2796d08eb7d8b506785c9/README.md) | `main` / `f815c31d6cc1a4af4ce2796d08eb7d8b506785c9` | 2026-07-15 22:10:46 | `skills/compose-agent`；`skills/jetpack-compose-audit` | [根 LICENSE：MIT](https://github.com/hamen/compose_skill/blob/f815c31d6cc1a4af4ce2796d08eb7d8b506785c9/LICENSE)。README 另说明部分性能规则改写自 Apache-2.0 来源，应保留来源说明。 |
| [android/skills README](https://github.com/android/skills/blob/4ebabf2b0fd6c04b7150023c8b612f80592a6226/README.md) | `main` / `4ebabf2b0fd6c04b7150023c8b612f80592a6226` | 2026-10-23 14:35:22 | 各业务目录下独立技能；见 §6 | [LICENSE.txt：Apache-2.0](https://github.com/android/skills/blob/4ebabf2b0fd6c04b7150023c8b612f80592a6226/LICENSE.txt)。官方来源身份不意味着任意版本/API 自动适合项目。 |

元数据可复查入口：[wshobson 固定提交](https://api.github.com/repos/wshobson/agents/commits/0874a27b17a22e2b7c2e2bcd725768e0803cad06)、[material-3 固定提交](https://api.github.com/repos/hamen/material-3-skill/commits/14385f2bf3804d8779f8b4db2604211f1e70b4c1)、[mobile-design 固定提交](https://api.github.com/repos/RubenGlez/mobile-design/commits/97474ed9b969d0c7f8a2acf712e30eeb0b9078d1)、[Expressive 固定提交](https://api.github.com/repos/abhixv/m3-expressive-design-skill/commits/5e7a9cd90d62bbff2dbe7a708206aeb4003e8a4e)、[Compose 固定提交](https://api.github.com/repos/hamen/compose_skill/commits/f815c31d6cc1a4af4ce2796d08eb7d8b506785c9)、[Android 固定提交](https://api.github.com/repos/android/skills/commits/4ebabf2b0fd6c04b7150023c8b612f80592a6226)。

## 3. 各技能真实能力与安装形式

### 3.1 mobile-android-design

[SKILL.md](https://github.com/wshobson/agents/blob/0874a27b17a22e2b7c2e2bcd725768e0803cad06/plugins/ui-design/skills/mobile-android-design/SKILL.md) 是真正的 Kotlin/Compose 技能，涵盖 MaterialTheme、动态色、48dp 触控、状态提升、导航、屏幕尺寸和预览。四份参考分别是 `details.md`、`compose-components.md`、`material3-theming.md`、`android-navigation.md`。入口约 3.8 KB，参考完整，不需要 Node 运行时才能阅读。

上游根 README 的 skills-only 安装支持 `gh skill` 与 `npx skills`；对本项的直接选择为 `npx skills add wshobson/agents --skill mobile-android-design`。整个 `ui-design` 插件还包含 Web/iOS/RN agents、commands 和 Node token 工作流；这些不是安装本项技能所必需。独立目录安装更符合 Record 最小组合。[插件 README](https://github.com/wshobson/agents/blob/0874a27b17a22e2b7c2e2bcd725768e0803cad06/plugins/ui-design/README.md) 的 `/plugin install ui-design` 属于插件用法，不应当成 Codex 独立技能命令。

判断：适合作为模式索引与入门检查；内容偏通用，不能仅靠该技能推出有辨识度的 Record 视觉语言。导航示例主要为 `NavController`/字符串 route，不能据此强制更换当前架构。

### 3.2 material-3

当前 [SKILL.md](https://github.com/hamen/material-3-skill/blob/14385f2bf3804d8779f8b4db2604211f1e70b4c1/skills/material-3/SKILL.md) 和 README 都明确 **Jetpack Compose 为 primary，Flutter secondary，Web limited**。因此“该仓库只适合 CSS/Web”不符合本次固定版本。但其主体仍有较多 HTML/CSS 示例、Web element 列、CSS token，不能原样移植到 Compose。六份 reference 文件确实存在，并包含 Compose 映射。

官方推荐命令：`npx --yes skills add hamen/material-3-skill --skill material-3 -y`。README 也支持直接复制/链接 `skills/material-3` 到 Codex 技能目录。其 `.claude-plugin` manifest、`user-invokable`、`argument-hint` 仅是上游声明，不构成 Codex 必须采用 slash-command 的证明。保留 `name`/`description` 和整套 references 即可作为文件型技能使用。

判断：适合 Token 语义、主题一致性、组件变体选择、十类设计审查。其“MD3 compliance 100 分”是上游自定义评分，不是 Google 验收标准；`0` 同时用于 N/A/不存在的规则尤其不宜机械用于 Record。技术错误见 §4。

### 3.3 mobile-design

[SKILL.md](https://github.com/RubenGlez/mobile-design/blob/97474ed9b969d0c7f8a2acf712e30eeb0b9078d1/SKILL.md) 约 8.9 KB，角色同时是移动产品设计师与 React Native 工程师；`Implementation Scope` 明确 RN/Expo 为默认实现路径，但也明确允许在检查原生工程/工具链后提供 Jetpack Compose 实现。因此应区分“默认跨平台偏置”与“不支持原生”。

值得吸收的是任务/受众/场景设计读取、五秒理解检查、先修层级再装饰、操作恢复、状态覆盖、手势可见替代、密度与动效分档。其 [Android reference](https://github.com/RubenGlez/mobile-design/blob/97474ed9b969d0c7f8a2acf712e30eeb0b9078d1/references/platform-android.md) 有 Compose 的 semantics、动态色和系统 inset 示例；[design-process](https://github.com/RubenGlez/mobile-design/blob/97474ed9b969d0c7f8a2acf712e30eeb0b9078d1/references/design-process.md) 和 [visual-system](https://github.com/RubenGlez/mobile-design/blob/97474ed9b969d0c7f8a2acf712e30eeb0b9078d1/references/visual-system.md) 比单纯组件目录更适合形成产品设计判断。

官方安装命令：`npx skills add RubenGlez/mobile-design`。根目录布局使安装可能携带仓库自身 `AGENTS.md`/`CLAUDE.md`，README 对此有说明；它们是技能维护材料，不是 Record 工作规范。建议暂作按需 UX 审查参考，不为它引入 RN、Expo、Reanimated、FlashList 或新包。

### 3.4 m3-expressive

[SKILL.md](https://github.com/abhixv/m3-expressive-design-skill/blob/5e7a9cd90d62bbff2dbe7a708206aeb4003e8a4e/skills/m3-expressive/SKILL.md) 约 9.2 KB，真实内容包括 Token 查询路由、spatial/effects spring 区分、局部强调字重、颜色角色、形状、组件状态/变体、平台可用性及“规范事实/项目决定”分开标注。其 [expressive-tactics](https://github.com/abhixv/m3-expressive-design-skill/blob/5e7a9cd90d62bbff2dbe7a708206aeb4003e8a4e/skills/m3-expressive/references/expressive-tactics.md) 将表现力用于注意力和层级，不要求每页做装饰效果。

完整树包含全部 22 份 Markdown references；`visuals/` 的 2,588 个 blob 是 2,587 张图片加 `INDEX.tsv`，元数据合计 512,388,392 bytes。图片清单与大小通过 [Git tree API](https://api.github.com/repos/abhixv/m3-expressive-design-skill/git/trees/5e7a9cd90d62bbff2dbe7a708206aeb4003e8a4e?recursive=1) 核实，本研究未下载图片。图片是规范参考渲染图，不是可以直接作为 Record 产品图标/素材的资产包。[visuals 使用说明](https://github.com/abhixv/m3-expressive-design-skill/blob/5e7a9cd90d62bbff2dbe7a708206aeb4003e8a4e/skills/m3-expressive/references/visuals.md) 也不把量像素当规格。

README 官方提供 Codex 技能目录复制/链接与纯文本 slim 安装。其 slim 命令是 `rsync -a --exclude visuals/ <skill>/ <destination>/`；Windows 可做同等定向复制。保留 `SKILL.md`、全部 references、`scripts/`（仅作为可选维护工具）、可选 `visuals/INDEX.tsv`，并额外保留根 LICENSE 与 NOTICE。现成文本不会因为没有图片就缺少必需 Token；但引用的图片路径会不可用，报告必须明确不能声称已看图。不要运行 `refresh-m3.js` 或 `refresh-component-tokens.js` 作为安装步骤，后者会覆盖参考文件。

许可判断的边界：NOTICE 记录第三方内容来源和不同许可，并没有给图片另列宽泛再分发许可。本轮采用纯文本参考、保留 attribution；不将图片打入 Record 仓库或产品。此处是对上游声明的核对，不提供法律授权保证。

### 3.5 Compose Skill Suite

两个目标均为真实目录型技能，而不是仅有 agent YAML：

- [`skills/compose-agent/SKILL.md`](https://github.com/hamen/compose_skill/blob/f815c31d6cc1a4af4ce2796d08eb7d8b506785c9/skills/compose-agent/SKILL.md)：4.4.0，约 16.3 KB，15 份 topic reference；状态、effect、Flow、Modifier、槽位 API、focus、navigation、animation、paging、测试等。官方命令 `npx --yes skills add hamen/compose_skill --skill compose-agent -y`，也支持 Codex 目录链接。
- [`skills/jetpack-compose-audit/SKILL.md`](https://github.com/hamen/compose_skill/blob/f815c31d6cc1a4af4ce2796d08eb7d8b506785c9/skills/jetpack-compose-audit/SKILL.md)：4.3.2，约 24.1 KB，5 份 Markdown reference 和 [`scripts/compose-reports.init.gradle`](https://github.com/hamen/compose_skill/blob/f815c31d6cc1a4af4ce2796d08eb7d8b506785c9/skills/jetpack-compose-audit/scripts/compose-reports.init.gradle)。官方命令 `npx --yes skills add hamen/compose_skill --skill jetpack-compose-audit -y`。

两者不共享运行状态；编写时用前者，明确工程审计才用后者。后者默认会尝试编译、注入 Gradle init script、写入 `COMPOSE-AUDIT-REPORT.md`；fallback 还写临时 init script 并要求删除。Record 根不是 Gradle root，且临时文件删除也受本地规则限制，因此不能无条件启动其流程。自动运行其默认审计不属于本次来源调研。

入口中的 `allowed-tools: ... Bash, Agent` 不是 Codex 额外授权。两者都不评定品牌成熟度；audit 的四个分数不包含完整无障碍或视觉设计质量。其上游 eval JSON 是维护者的测试资料，不证明本环境完成过模型评测。

### 3.6 Android 官方 Skills

官方 README 强调模块化工作流和模型易错点，而不是覆盖所有基础 Compose 知识。官方当前支持 Android CLI 单技能安装 `android skills add <name> --project=.`，也列出 Codex plugin marketplace 方式。对于 Record，本机已有同名技能，整包安装只会增加重复入口；优先按实际任务读已有内容和当前官方 API。对应文件及差异见 §6。

## 4. 实际发现的冲突、陈旧点与处理

| 来源中的具体问题 | 证据 | 对 Record 的处理建议 |
|---|---|---|
| `mobile-android-design` 把“不稳定 lambda→remember”写成通用性能建议；旧 NavHost 示例为字符串 route。 | [SKILL Common Issues](https://github.com/wshobson/agents/blob/0874a27b17a22e2b7c2e2bcd725768e0803cad06/plugins/ui-design/skills/mobile-android-design/SKILL.md)、[details 导航示例](https://github.com/wshobson/agents/blob/0874a27b17a22e2b7c2e2bcd725768e0803cad06/plugins/ui-design/skills/mobile-android-design/references/details.md) | 当前 Strong Skipping 会记忆包括不稳定捕获的 lambda；不为遵循旧建议加无意义 remember。匹配工程既有导航，而非抄模板迁移。 |
| `material-3` 自称 Compose-first，但仍有大量 CSS、HTML、Web element。 | [SKILL Web/Common Patterns](https://github.com/hamen/material-3-skill/blob/14385f2bf3804d8779f8b4db2604211f1e70b4c1/skills/material-3/SKILL.md) | 采用角色/原则；实现只用当前 Kotlin/Compose API；Web 依赖不进入工程。 |
| `material-3` elevation 表仍将层级写成 primary overlay 百分比；其 reference 还机械对应 elevation level 与 surfaceContainer。与 Expressive 文本的 tone-based surface 说明冲突。 | [material-3 Elevation](https://github.com/hamen/material-3-skill/blob/14385f2bf3804d8779f8b4db2604211f1e70b4c1/skills/material-3/SKILL.md)、[typography-and-shape](https://github.com/hamen/material-3-skill/blob/14385f2bf3804d8779f8b4db2604211f1e70b4c1/skills/material-3/references/typography-and-shape.md)、[Expressive tokens](https://github.com/abhixv/m3-expressive-design-skill/blob/5e7a9cd90d62bbff2dbe7a708206aeb4003e8a4e/skills/m3-expressive/references/tokens.md) | 不合并成“唯一正确”表；以项目使用库的组件默认值、ColorScheme 语义与官方当前参考核验；布局区域色和 shadow/tonalElevation 分开决定。 |
| `compose-agent/performance.md` 首段把 SSM 参数比较泛化为结构相等，后文称 fresh 对象可能靠结构相等得以跳过。 | [performance.md](https://github.com/hamen/compose_skill/blob/f815c31d6cc1a4af4ce2796d08eb7d8b506785c9/skills/compose-agent/references/performance.md) | Android 官方明确 unstable 参数按实例相等、stable 参数按对象相等。使用 [Strong Skipping 官方页](https://developer.android.com/develop/ui/compose/performance/stability/strongskipping) 的区别，不以该措辞诊断。 |
| Compose Suite 将 Kotlin 2.0.20+ 与旧 Compose Compiler 1.5.4+ 混在默认 track 中。 | [compose-agent Target track](https://github.com/hamen/compose_skill/blob/f815c31d6cc1a4af4ce2796d08eb7d8b506785c9/skills/compose-agent/SKILL.md) | 先读取 Kotlin Compose compiler plugin 与项目实际配置，不能用这两个条件机械判定 SSM，也不能强制升级。 |
| Expressive platform reference 保留“later in 2026, Material Compose 1.5.0 将稳定”和所有 Expressive API 都实验性的笼统描述；内容自报 July 2026 捕获。 | [platforms.md](https://github.com/abhixv/m3-expressive-design-skill/blob/5e7a9cd90d62bbff2dbe7a708206aeb4003e8a4e/skills/m3-expressive/references/platforms.md)、[NOTICE accuracy](https://github.com/abhixv/m3-expressive-design-skill/blob/5e7a9cd90d62bbff2dbe7a708206aeb4003e8a4e/NOTICE.md) | 2026-10-24 查询官方 release 页：稳定线为 1.4.0，预发布为 1.5.0-beta02；部分 1.5 API 已逐项去实验标记。必须按具体 artifact/API/项目版本核验，不把“1.5”误作 Compose UI 全家版本，也不为表现力升级。 |
| `mobile-design` 默认 RN/Expo，Expressive 推荐较多场景采用 expressive scheme，material-3 声称其规则优先于通用 frontend taste。 | 各 SKILL 的 Implementation Scope、Quick reference、Relationship 段 | 这些是技能内部偏好，不能取得项目设计决定权。Record 先按当前任务与负责人批准决定导航、色彩、动效与密度，再选择适用参考。 |
| audit 自动构建/写文件/删除 fallback 文件；Expressive refresh 覆盖 reference；mobile-design 根目录附带维护指令。 | 各 README/SKILL 与上游文件结构 | 读取材料不等于执行它们。范围、对象权限和项目红线优先；本次未执行上述工作流。 |

当前 API 核验的第一方依据：[Compose Material3 release notes](https://developer.android.com/jetpack/androidx/releases/compose-material3)（2026-10-21 的 1.5.0-beta02；1.5.0-alpha19 的按钮/FAB/menu、alpha23 的 list API 已有具体变更）。技能 README 的 14/14 Token 问答是作者在 Claude 环境自报，不能作为本次 Codex 识别验证；同样，通用 M3 研究中的注意力结果不能当作 Record 已获得的效率改善。

## 5. 职责路由与避免重叠

| 工作 | 主参考 | 辅助参考 | 不应自动带来的动作 |
|---|---|---|---|
| 产品信息层级、任务流、状态与恢复 | 项目产品合同/负责人候选设计；按需 `mobile-design` UX 文件 | `mobile-android-design` 的 Android 习惯 | 不引入 RN，不自行变更记账/笔记语义。 |
| ColorScheme、Typography、Shapes、区域层级 | `material-3`，并查当前官方资料 | `m3-expressive` 文本规格 | 不硬套紫色、动态色默认开关或动画方案，不升级依赖。 |
| Compose 组件 API、状态、副作用、Flow | `compose-agent` 的对应 topic | 官方 Compose API 与已有项目接缝 | 不为了示例引入 Paging/Nav3/新库。 |
| 系统栏/IME | 已有官方 `edge-to-edge` | `mobile-design/platform-android` | 不重复叠加 padding；需具体设备验证时另按授权。 |
| 窗口变化和多面板 | 已有官方 `adaptive` | 当前 `navigation-3`、`navigation-event` | 不按设备名称硬编码，不自动升级新 Grid/FlexBox/MediaQuery。 |
| 性能问题 | 官方 `jetpack-compose-performance` + 测量 | `compose-agent`；明确任务才 `jetpack-compose-audit` | 不把重组次数/自定义分数等同于实际帧耗时。 |
| 截图、语义、状态矩阵 | 既有测试能力与 `testing-setup` 参考 | `compose-agent/testing` | 不因安装技能就自动创建新测试基础设施或运行真机。 |

最小组合的含义是“少量入口、按主题加载”，不是每次设计都同时加载四套相互覆盖的规则。遇到冲突先查项目锁定版本/已批契约与当前第一方来源，并记录项目取舍；不能因为技能写了 must 就覆盖负责人决定。

## 6. 本机已有官方技能对比

对 `D:/record/skills/<name>/SKILL.md` 与上表固定 SHA 的同名上游文件做 UTF-8 文本比较，换行统一后结果如下。此比较只证明入口内容差异，不证明全部本地 references 已与上游同步。

| 技能 | 上游目录 | 本机比较结果 | 本轮建议 |
|---|---|---|---|
| adaptive | [`jetpack-compose/adaptive`](https://github.com/android/skills/tree/4ebabf2b0fd6c04b7150023c8b612f80592a6226/jetpack-compose/adaptive) | 本机标记 2026-10-16；上游 10-22 新增桌面快捷键/tooltip/cursor 路由及相关引用。 | 已有，按任务查参考；不整体升级。 |
| jetpack-compose-performance | [`jetpack-compose/jetpack-compose-performance`](https://github.com/android/skills/tree/4ebabf2b0fd6c04b7150023c8b612f80592a6226/jetpack-compose/jetpack-compose-performance) | 本机 10-14；上游 10-22 修正了 `LaunchedEffect` 一定在 Draw 后的说法，改为异步分发不保证 after Draw。 | 不沿用旧时序结论；性能核验用当前官方来源。 |
| navigation-3 | [`navigation/navigation-3`](https://github.com/android/skills/tree/4ebabf2b0fd6c04b7150023c8b612f80592a6226/navigation/navigation-3) | 仅 `last-updated` 由 10-17→10-22，正文相同。 | 已有，不重复安装，不为设计审查迁移导航。 |
| navigation-event | [`navigation/navigation-event`](https://github.com/android/skills/tree/4ebabf2b0fd6c04b7150023c8b612f80592a6226/navigation/navigation-event) | 入口正文相同。 | 已有；预测返回任务才使用。 |
| edge-to-edge | [`system/edge-to-edge`](https://github.com/android/skills/tree/4ebabf2b0fd6c04b7150023c8b612f80592a6226/system/edge-to-edge) | 入口正文相同。 | 已有。 |
| testing-setup | [`testing/testing-setup`](https://github.com/android/skills/tree/4ebabf2b0fd6c04b7150023c8b612f80592a6226/testing/testing-setup) | 入口正文相同。 | 已有；本次不自动搭新测试栈。 |
| styles | [`jetpack-compose/theming/styles`](https://github.com/android/skills/tree/4ebabf2b0fd6c04b7150023c8b612f80592a6226/jetpack-compose/theming/styles) | 入口正文相同。 | 有技能不等于项目有兼容 API；暂不因设计系统规划迁移 Styles。 |

## 7. 安装与识别的证据边界

本来源调研任务未安装技能。实际安装项、目标路径、完整性检查与重启后识别应由主任务的安装记录拥有，避免本文件伪造安装成功。以下是合格的分层验证方法：

1. 静态安装：目标目录顶层有 UTF-8 `SKILL.md`；YAML `name`/`description` 可读；引用的必需 references 与选定固定 SHA 一致；保留适用 LICENSE/NOTICE。`jetpack-compose-audit` 若安装，必须连 init script 一起。
2. 当前会话手动阅读：证明文件可访问并能按路由找到规则，不等于启动时自动发现。超过约 8 KB 的入口（material-3/compose-agent/audit/mobile-design/Expressive）还需检查具体运行时是否截断或限制；不能只依据 YAML 存在宣布完整加载。
3. 重启后自动发现：新会话技能目录中能看到准确 name/description；显式调用技能并读取对应 reference，回答受控核验问题。Expressive 可用作者例题：fast spatial 为 damping 0.6/stiffness 800；48dp 外圆角且16dp padding 的内圆角按该规则为32dp。仅答对数字仍不能证明自动路由，需确认实际读取路径。
4. 适用性验证：给小范围现有 Compose 代码做只读审查，检查是否避免 RN/Web API，是否尊重当前依赖、项目权限及语义；不执行全量审计来代替技能发现验证。

## 8. 阅读清单与未验证项

已完整读取/针对结论核对的入口：六个仓库 README、六个根 LICENSE（Android 为 LICENSE.txt）、五个社区仓库的六个目标 SKILL（Compose 两个）、Expressive NOTICE、wshobson ui-design README；另对七个已有官方技能入口做文本差异核对。必要参考已深入读取：wshobson `details.md`；material-3 `typography-and-shape.md`；mobile-design `design-process.md`、`platform-android.md`、`visual-system.md`、`review-rules.md`、`source-map.md`、`feasibility-risk.md`；Expressive `tokens.md`、`expressive-tactics.md`、`motion.md`、`platforms.md`、`visuals.md` 与 INDEX 清单；compose-agent `component-api.md`、`performance.md`。其余参考下载并核对目录/引用完整性，没有声称逐行完成内容审校。

已保存的只读研究缓存为 `C:/Users/p3148/AppData/Local/Temp/record-uiux-skill-research-20261024/`；缓存包含固定 SHA metadata/tree 与文本副本，没有下载 Expressive 2,587 张图片，也没有删除临时目录。GitHub API 曾触发限流，Ruben 的元数据后来重试取得；一次 `git ls-remote` 连接重置，固定 SHA 最终来自成功的 GitHub commit/tree API，不依赖失败结果。

未验证：本笔记不证明安装后 Codex 启动发现、上游模型 eval 复现、任何 Compose 编译/运行/设备截图、当前 Record 依赖中所有 Expressive API、图片逐张内容或图片再分发授权。与项目具体版本的兼容评估须结合主报告的依赖审查；上游 remote 内容不是执行许可，也不是 Record 验收合同。
