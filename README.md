# Mindustry core source (code only)

Fork of [Anuken/Mindustry](https://github.com/Anuken/Mindustry) 的 **纯源码快照**，用于 AI 代码导入/评审。

保留原因：官方仓库共 4278 个文件，其中 3350+ 是贴图、音效、地图、语言包等二进制资产；
本仓库只保留 `core/src`（834 个 .java 文件，8.1 MB），以满足单次导入 1000 文件的上限。

包含内容：
- 当前版本：**v2.0.1 (V2.0.1)**（基于 v2.0 修复编译与 Android 打包配置）
- Mindustry 全部 Java 游戏源码（`mindustry.*` 包）
- 本项目对上游的改动：
  - **V2.0.1 编译与 Android R8 脱糖打包修复：**
    - `mindustry/io/JsonIO.java` — 将 `ProductionNode` 与 `SectorStrategyProfile` 序列化器中的 6 处 `Map.Entry` 显式限定为 `java.util.Map.Entry`，消除与 `mindustry.maps.Map` 的编译歧义
    - `.github/workflows/build-apk.yml` & `VERSION` — 引入修复版 Android APK 构建流程：将 AGP 从 `8.2.2` 升级至 `8.13.2`、保持 `minifyEnabled`/`shrinkResources` 开启以确保 R8 正常执行 default interface method 脱糖、提供 `fork-version.txt` 兜底 Java 资源，并在出包后自动校验 dex 中存在 `Application$-CC` 伴随类
  - **V2 多区块战役联机与星球占用可视化：**
    - `mindustry/game/coop/` — 新增 V2 联机协同包：`ClientSectorMode`、`CampaignPermissions`（全局+单房客独立权限）、`SectorOccupancy`（全星球玩家分布与单持有者租约）、`CampaignChunkTransport`（分片 `.msav` 存档传输）、`RemoteSectorBridge`（独立区块实时全球物流桥接）、`MultiSectorCoordinator`（房主权威主控、无缝移交、跨区聊天与共享科技树）
    - `mindustry/net/CampaignPacket.java` — V2 自定义高优先级战役联机控制与分片同步包
    - `mindustry/net/Net.java` / `NetConnection.java` / `WorldReloader.java` — 解耦 `detachedLocalSim`（独立区块本地模拟保持房主长连接）与同区玩家定向重载/快照过滤
    - `mindustry/core/NetServer.java` / `NetClient.java` / `Control.java` / `World.java` / `Tile.java` — 多区块主机/房客同步过滤、房主切区无缝移交与房客留守、断开前强制回传存档
    - `mindustry/core/GameState.java` / `BaseDialog.java` / `HudFragment.java` — 多人全员强制禁用暂停（`isPauseLocked()`，单人保留暂停）
    - `mindustry/game/Universe.java` / `GlobalStrategyManager.java` / `Saves.java` / `Sector.java` — 全球物流打通独立经营区块、防止房客污染本地单人存档、同步权威区块状态
    - `mindustry/ui/dialogs/PlanetDialog.java` — 开放房客战役星球界面、有人区块统一标黄轮廓并在区块中央悬浮显示玩家个数、按房主权限控制切区/发射/放弃
    - `mindustry/ui/dialogs/HostDialog.java` / `CampaignRulesDialog.java` / `PlayerListFragment.java` / `ResearchDialog.java` / `PausedDialog.java` / `ChatFragment.java` — 房主权限开关（全局与单人覆盖）、跨区玩家列表、共享科技树解锁与跨区聊天
    - `tests/src/test/java/mindustry/game/coop/MultiSectorCoopTests.java` — V2 权限、租约/占用、分片传输、远程物流桥接与反暂停单元测试
  - **v1.6 战斗节奏 2.0x 放缓与全平衡改造：**
    - `mindustry/game/CampaignRules.java` — 新增 `globalLogistics` 开关与 `slowCombatPace` 战斗节奏开关
    - `mindustry/game/Rules.java` — 新增 `slowCombatPace` 开关与幂等 `applyCombatPace()`（伤害 0.70x、波次间隔 1.80x、造兵速度 0.75x）
    - `mindustry/maps/Map.java` — 非编辑器模式自动应用 v1.6 规则层战斗节奏倍率
    - `mindustry/content/CombatPaceBalancer.java` — v1.6 内容层集中缩放（炮塔/持续光束塔/蓄能激光塔/点防塔、单位/导弹单位、物理弹道射程保持、共享子弹去重、护甲线性对齐、维修与力场护盾、状态 DoT）
    - `mindustry/core/ContentLoader.java` — 内容加载完成后严格执行一次 `CombatPaceBalancer.apply()`
    - `tests/src/test/java/mindustry/content/CombatPaceBalancerTests.java` — v1.6 战斗节奏与平衡性单元测试
  - **v1.5 全球物流与战略底座：**
    - `mindustry/game/SectorInfo.java` — 新增 `strategyProfile` 与编译钩子
    - `mindustry/game/strategy/` — 全球物流与战略底座（新增包）
  - `mindustry/core/Version.java` — 记录当前分支版本 `forkVersion = "v2.0.1"`

说明：本仓库不可构建（缺少 assets、gradle 工程与 Arc 依赖），仅作源码阅读/修改参考。
可构建的完整版本见 [mindustry](https://github.com/jinitaimei436/Mindustry)。
注意：下游打包 Android APK 时请务必使用 AGP ≥ 8.3（推荐 `8.13.2`）并保持 R8 `minifyEnabled = true`，切勿为绕开旧版 AGP 错误而关闭 R8，否则会导致 `arc.Application` 默认接口方法未脱糖而启动闪退。
