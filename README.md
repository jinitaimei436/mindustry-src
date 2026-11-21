# Mindustry core source (code only)

Fork of [Anuken/Mindustry](https://github.com/Anuken/Mindustry) 的 **纯源码快照**，用于 AI 代码导入/评审。

保留原因：官方仓库共 4278 个文件，其中 3350+ 是贴图、音效、地图、语言包等二进制资产；
本仓库只保留 `core/src`（834 个 .java 文件，8.1 MB），以满足单次导入 1000 文件的上限。

包含内容：
- Mindustry 全部 Java 游戏源码（`mindustry.*` 包）
- 本项目对上游的改动：
  - `mindustry/game/CampaignRules.java` — 新增 `globalLogistics` 开关
  - `mindustry/game/SectorInfo.java` — 新增 `strategyProfile` 与编译钩子
  - `mindustry/game/Universe.java` — 秒级 tick 接入全局物流
  - `mindustry/game/strategy/` — 全球物流与战略底座（新增包）
  - `mindustry/game/strategy/GlobalStrategyManager.java` — 修复 lambda 捕获编译错误

说明：本仓库不可构建（缺少 assets、gradle 工程与 Arc 依赖），仅作源码阅读/修改参考。
可构建的完整版本见 [mindustry](https://github.com/jinitaimei436/Mindustry)。
