# 预测 LOD 移植：Forge 1.20.1

日期：2026-09-10。目标为 Minecraft 1.20.1、Forge 47.4.20、Java 17，模组版本固定为 `0.3-forge-1.20.1`。

源代码取自桌面 `voxyserverside-neoforge-1.21.1` 工作区的冻结快照，包含当时未提交的代码和已在本地提交的 Rust 核心。源仓库仍由另一个任务修改，因此本次没有持续同步其后续变化，也没有在源仓库修复或修改测试。文件指纹见 `port-1.20.1-source-manifest.json`。

## 已移植

- 客户端地形预测、逐级细化、望远镜选区、地表植被及结构、磁盘缓存和脏列更新。
- Rust JNI 地形采样、颜色与植被计算，以及受支持生成器的 Java 兼容路径。
- 预测纹理、材质、透明水面、Voxy 深度交接和光影桥接代码。
- 服务端世界生成快照、动态注册表和模板导出，预测能力协商与客户端解码。
- 客户端状态/预测采集命令及 Embeddium 设置页中的预测、植被、结构和距离选项。原命令精简任务已停止，本次保留源快照的命令功能。

## 1.20.1 专项适配

1. 使用 Forge SimpleChannel，新增每片最多 512 KiB 的生成快照传输；客户端检查总长度、传输标识与顺序，断线清理未完成数据。单次传输上限 144 MiB，与现有注册表/维度生成器上限留有余量。没有沿用 NeoForge 专用分片通道。
2. Forge RenderTickEvent.START 负责逐帧预算和深度状态重置；世界渲染事件使用 1.20.1 PoseStack 矩阵。
3. 动态注册表 Lifecycle、DataResult、ChunkStatus、NBT 限额读取、LevelData、顶点提交和 Java 17 集合 API 适配。
4. 结构模板从 1.20.1 的 `data/<namespace>/structures/` 导出。
5. Java 方块映射使用 `Blocks.GRASS`；Rust 植被识别增加 `minecraft:grass`，兼容核心原有的 `minecraft:short_grass`。
6. 樱花树参数反射同时支持开发映射字段与 Forge 发布包的 SRG 字段；光影探测兼容新 Iris 与旧 Oculus/Iris 包名。
7. 测试初始化补齐独立 JUnit 进程中缺少的 Forge 监听器、模组上下文及方块状态缓存。测试颜色断言使用 1.20.1 的 RGB 提供器语义；村庄测试读取本版本原版模板。

## 本次验证

- `gradlew test build --offline`：成功，包括 Forge reobfJar。
- Java 测试：591 项，572 项通过、19 项按原有条件跳过、0 失败、0 错误。
- Rust `cargo test --locked --release`：33 项通过，0 失败。
- 3 MiB 以上快照的生产编码、分片、重组、解码一致性测试通过；错序、跨传输标识、断线和越界片段检查通过。这不是远程 TCP 联机验收。
- Windows x64、Linux x64/ARM64、macOS x64/ARM64 原生库已重新构建并打入 JAR。
- `audit-native-package.py` 检查通过：五个平台的构建文件/包内哈希、架构、每个平台 30 个 JNI 导出、依赖及 macOS ARM64 ad-hoc 签名。
- 日志保存在 `build/port-1.20.1/`，完整测试报告在 `build/reports/tests/test/`。

## 交付与边界

JAR：`build/libs/vss-0.3-forge-1.20.1.jar`，4,205,777 字节。

SHA-256：`3bb41c94e3346e5c21cd68be432371279905ce8ed0e8dd4359411fe30473f532`。

本次没有安装到玩家整合包，没有提交或推送目标仓库。源仓库此前已经产生一个本地 Rust 提交，但未推送。

尚未完成 1.20.1 游戏内跑图、远程专用服联机、具体 Voxy/Oculus 版本及地形模组组合的验收。19 项跳过测试包括需要额外模组、外部数据或 GPU 环境的项目。跨平台原生库构建与符号检查不等于对应操作系统上的游戏验证；标准 macOS 驱动也不能满足 Voxy/Iris OpenGL 4.6 路径。

`tools/` 中随快照保留的历史研究报告和 1.21.1 采集/分析脚本不是本次 1.20.1 验收证明；部分独立采集工具仍需要按本版本 API 单独适配。Rust 原有数值回归样本保持原始来源，实际预测输入来自正在运行的 1.20.1 注册表，不能把旧世界样本当作当前世界的地形数据。
