# Mindustry core source (code only)

Fork of [Anuken/Mindustry](https://github.com/Anuken/Mindustry) 的 **纯源码快照**，用于 AI 代码导入/评审。

保留原因：官方仓库共 4278 个文件，其中 3350+ 是贴图、音效、地图、语言包等二进制资产；
本仓库只保留 `core/src`（834 个 .java 文件，8.1 MB），以满足单次导入 1000 文件的上限。

包含内容：
- 当前版本：**v1.6**（基于 v1.5 扩展）
- Mindustry 全部 Java 游戏源码（`mindustry.*` 包）
- 本项目对上游的改动：
  - `mindustry/game/CampaignRules.java` — 新增 `globalLogistics` 开关与 `slowCombatPace` 战斗节奏开关
  - `mindustry/game/Rules.java` — 新增 `slowCombatPace` 开关与幂等 `applyCombatPace()`（伤害 0.70x、波次间隔 1.80x、造兵速度 0.75x）
  - `mindustry/maps/Map.java` — 非编辑器模式自动应用 v1.6 规则层战斗节奏倍率
  - `mindustry/content/CombatPaceBalancer.java` — v1.6 内容层集中缩放（炮塔/持续光束塔/蓄能激光塔/点防塔、单位/导弹单位、物理弹道射程保持、共享子弹去重、护甲线性对齐、维修与力场护盾、状态 DoT）
  - `mindustry/core/ContentLoader.java` — 内容加载完成后严格执行一次 `CombatPaceBalancer.apply()`
  - `mindustry/core/Version.java` — 记录当前分支版本 `forkVersion = "v1.6"`
  - `mindustry/game/SectorInfo.java` — 新增 `strategyProfile` 与编译钩子
  - `mindustry/game/Universe.java` — 秒级 tick 接入全局物流
  - `mindustry/game/strategy/` — 全球物流与战略底座（新增包）
  - `tests/src/test/java/mindustry/content/CombatPaceBalancerTests.java` — v1.6 战斗节奏与平衡性单元测试

说明：本仓库不可构建（缺少 assets、gradle 工程与 Arc 依赖），仅作源码阅读/修改参考。
可构建的完整版本见 [mindustry](https://github.com/jinitaimei436/Mindustry)。
