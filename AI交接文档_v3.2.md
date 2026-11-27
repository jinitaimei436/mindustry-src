# AI 交接文档（Mindustry 魔改分支 v3.2）

> 本文档供新对话中的 AI 快速接手项目上下文，避免重复排查历史改动与约束。接手后请先按需读取本文档提到的核心文件，再响应用户的新需求。

---

## 1. 项目概况与工作区环境

- **项目性质**：Anuken/Mindustry 的大战略魔改分支，当前分支版本号为 **`v3.2`**（定义于 `core/src/mindustry/core/Version.java` 的 `forkVersion = "v3.2"`）。
- **工作区限制（极重要）**：
  - 当前工作区为纯代码快照（仅包含 `core/src/`、`tests/` 及根目录文档），**没有 Gradle 构建配置与 Arc/Mindustry 依赖 jar**，因此无法在本地编译或运行测试。
  - **每次提交代码修改后，必须向用户明确标注“未编译验证”**，并严格靠静态代码审读保证零语法/类型/导入错误。
- **禁改范围**：除非用户明确要求，不要修改 `.github/`、`android/`、`build.gradle`、`gradle/`、`public/`、`README.md` 或根目录 `VERSION`。
- **文档资产**：根目录已整理完整倍率速查表 **`v3.2版本速率一览图表.md`**，涉及游戏数值/倍率查询或后续平衡调整可直接参考并同步更新。

---

## 2. 下游 Android 编译硬性红线（写 Java 必看）

用户的下游实际构建面向 Android 平台（`minSdk = 21`，**未开启 core library desugaring**）。编写任何 Java 代码必须严格遵守：

1. **禁止 Java 8 接口静态方法**：严禁使用 `Comparator.comparing*`、`Comparator.naturalOrder()`、`Comparator.reverseOrder()`、`List.of(...)`、`Map.of(...)`、`Set.of(...)`、`Map.entry(...)` 等方法；排序请使用显式 lambda 或手写静态方法（例如 `(a, b) -> comparePorts(a, b)`）。
2. **禁止 API 24+ 标准库方法**：例如严禁使用 `Math.floorMod`。
3. **`short` ID 装箱显式强转**：Mindustry 中 `Content.id`、`Block.id`、`Item.id` 为 `short`，放进 `Integer` 泛型容器（如 `Map<Integer, ...>`、`Set<Integer>`）前必须显式强转为 `(int)`。
4. **集合命名冲突处理**：在导入了 `arc.struct.*` 的文件（尤其是 `PlanetDialog.java`）中，使用 JDK 集合时必须写全限定名（`java.util.Map`、`java.util.List`、`java.util.Collection`）。
5. **编辑前先读磁盘**：用户可能会在对话间隙手动微调代码（例如曾手动在 `SurgeSectorPowerHub.java` 添加 `import mindustry.type.*;`），改动既有文件前务必先读取当前最新版本。

---

## 3. 截至 v3.2 已落地的核心系统

| 版本 | 模块 | 核心源码路径 | 关键设计摘要 |
| :--- | :--- | :--- | :--- |
| **v1.6** | 慢战斗节奏平衡 | `core/src/mindustry/content/CombatPaceBalancer.java`<br>`core/src/mindustry/game/Rules.java` | 默认 `slowCombatPace = true`；规则层伤害 `0.70x`、造兵速度 `0.75x`（耗材 `1.0x`）、波次间隔 `1.80x`；内容层装填 `1.40x`、移速 `0.75x`、护甲 `0.70x`、修理/治疗约 `0.55x`、DoT `0.50x`；**保留采矿、传送带、建造为 `1.0x` 原版节奏**。 |
| **v2.1** | 持久战术编队 | `core/src/mindustry/ai/formation/`（`FormationMath`、`FormationManager`、`TacticalFormation`） | 跨兵种同队按最慢存活成员移速巡航同步；超前平滑刹车；落后最高 `1.30x` 追赶。 |
| **v2.5** | 跨区块电网 | `core/src/mindustry/world/blocks/power/SurgeSectorPowerHub.java`<br>`core/src/mindustry/game/power/` | 4×4 巨浪区块电力枢纽，每区限 1 座，局部连接半径 20 格，跨区无限吞吐、零线损、共享电池池。 |
| **v2.8** | 跨区块传送带 | `core/src/mindustry/world/blocks/distribution/SectorConveyorPort.java`<br>`core/src/mindustry/game/logistics/` | 初级 3×3（2/s、容 20、不耗电）与高级 4×4（4/s、容 40、起始耗电 240/s）；每区初+高起始端合计限 4 座；相邻区块 1 对 1 FIFO 无损背压。 |
| **v2.8.1** | 超速与发射台调优 | `OverdriveProjector.java`、`Blocks.java`、`StatusEffects.java` | 超速投影仪（`1.25x` / 相控布 `1.625x`）、超速穹顶（`1.75x`）及 `overdrive`/`overclock` 状态额外增益全面砍半；发射台/高级发射台改为 `50 秒/次`。 |
| **v3.0 ~ v3.1** | 全球实时战略结算 | `core/src/mindustry/game/Universe.java`<br>`core/src/mindustry/game/strategy/`（`GlobalStrategyManager`、`CrossSectorExtension`、`CrossSectorPowerExtension`、`CrossSectorConveyorExtension`、`SectorProfileCompiler`、`core/StrategySimulationEngine`、`core/PlanetNetworkSolver` 等） | **已彻底删去原版 2 分钟 `runTurn()` 多区块资源结算**（`runTurn()` 仅保留敌军入侵判定与回合计数）。改为每 `1 秒` 全局实时结算，通过 `CrossSectorExtension` 扩展点统一调度跨区电网、跨区传送带及未来新增跨区装置。 |
| **v3.2** | 272 区块大后期性能优化 | 见下方第 4 节 | 针对赛普罗 272 区块全占领极端场景完成一轮兼容安全型 CPU/GC/IO/渲染优化。 |

---

## 4. v3.2 性能优化已完成项 & 刻意跳过项

### 已完成（均已落盘并同步更新单测与版本号 `v3.2`）
1. **跨区电网缓存与降频刷盘**（`SectorPowerGridMath.java`、`SectorPowerGridManager.java`）：
   - 引入版本号驱动的 `componentCache`（`topologyVersion`）与 `aggregateCache`（`telemetryVersion`）；新增 `stepBackgroundComponentsTracked(...)`。
   - 移除 `updateGlobalSecond()` 中的每秒全量序列化，改为脏标记 + `saveIfDirty(boolean)`，并去重 `SaveWriteEvent`。
2. **跨区传送带分桶索引与低开销刷新**（`SectorConveyorMath.java`、`SectorConveyorManager.java`、`CrossSectorConveyorExtension.java`）：
   - 新增按区块有序桶 `portsBySector`、`O(1)` `hasAnyPort`、`getAllSectorPortsReadOnly`、`advanceRateWindowTracked`、`stepBackgroundLinksTracked`。
   - 仅在真实活动或清理时递增 `uiRevision`；移除每秒 Base64 序列化改为 `saveIfDirty(boolean)`；扩展层复用草稿容器。
3. **战略模拟引擎与求解器优化**（`StrategySimulationEngine.java`、`PlanetNetworkSolver.java`）：
   - 实时步进上限 `maxRealtimeSubSteps = 4`（最小子步 `0.25f`），离线追赶上限 `maxBatchSubSteps = 64`。
   - 全局不动点收敛时跳过多余的收尾 `solveLocalSector`；复用求解器临时集合/数组。
4. **后台脏区块分批刷盘**（`GlobalStrategyManager.java`、`Universe.java`）：
   - `dirtySectors` 改为 `LinkedHashSet`（FIFO），每 `10 秒` 分批刷盘 `30` 个脏区块（`DEFAULT_FLUSH_BATCH_SIZE = 30`）；`SaveWriteEvent` 与 `DisposeEvent` 触发全量刷盘。
   - 移除 `runTurn()` 中对 272 个区块无条件全量 `saveInfo()` 的卡顿风暴。
5. **星球地图渲染裁剪**（`PlanetDialog.java`）：
   - 新增 `tmpCamDir` 半球背面剔除（弧线阈值 `-0.30f`、点位阈值 `-0.25f`），连接数 `> 120` 或 `zoom > 1.6f` 时动态降至 `14` 段弧线。
6. **单元测试同步**（`tests/src/test/java/mindustry/` 下相关测试文件）：
   - 所有版本号断言已更新为 `"v3.2"`，并在 `RealTimeSettlementTests.java` 补充了 3 项 272 区块相关测试用例。

### 刻意暂缓未改的项目（用户明确要求保留兼容性，请勿擅自修改）
因用户后续计划继续加入大量新内容，以下 3 项容易引发存档格式或扩展兼容问题的改动在 v3.2 中**有意未做**：
1. **`SectorStrategyProfile` 的 JSON 序列化结构改动**；
2. **将 `lastSyncedStorage` 替换为定长 `int[]` 数组**；
3. **`ExportStat` 低速率阈值过滤**。

---

## 5. 用户协作习惯提醒

- **大型改造或性能优化**：建议先列出方案、预期收益和潜在副作用/风险，待用户审核确认后再动手改代码。
- **版本号管理**：当完成一轮功能或架构变更时，注意确认是否需要推进 `Version.forkVersion` 及测试中的版本号断言；如需同步更新倍率表，请一并维护 `v3.2版本速率一览图表.md`（或新版本对应图表）。
- **当前状态**：代码库干净，无未完成任务，随时可以开始下一阶段新功能开发。
