# 缓存读取与废弃整步装饰接口审查（2026-09-23）

## 清理

游戏植被生成按 `RustVegetationStage.place` 顺序调用 `placedFeature`，从未调用 `decorateStep`。已在 NeoForge 1.21.1 和 Forge 1.20.1 同步移除 Java JNI 声明、Rust JNI 入口、`World::decorate_step`、它唯一调用的 `decoration_indices`、专属整步 checkpoint 及只覆盖这一未使用入口的测试。`Schedule::allowed`、逐特征 `placed` 和逐特征事务/回滚测试保留。五平台原生库重新交叉构建；删除死代码不会缩短现在游戏的植被加载时间。

## 读回的实测收益

生产缓存从索引区读取独立压缩记录，并通过 32 KiB 缓冲逐字段解码。成品网格缓存另外需要验证资源、地形、颜色、植被和洞口补片的签名；这段输入准备及任务队列/GPU 上传不是纯磁盘 I/O。保留既有区域索引和 zlib 格式，无需迁移旧缓存。

使用 `PredictionMeshCacheIntegrationBenchmark` 写出的真实编码网格记录，交替 31 轮，先预热 8 轮，两种读取方式逐字节比对。原实现 `readNBytes(length)` 对大 payload 做额外的分段分配与复制；现改为一次 `new byte[length]` 加 `DataInputStream.readFully(bytes)`，长度仍受 16 MiB 上限和整个缓存记录校验限制：

| 网格轴 | 压缩记录 | 网格 payload | `readNBytes` 中位数 | `readFully` 中位数 | 分配字节（旧→新） |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 16 | 11,272 B | 55,977 B | 0.067 ms | 0.060 ms | 146,648 → 90,440 |
| 64 | 178,096 B | 1,157,693 B | 1.404 ms | 1.269 ms | 2,354,224 → 1,192,152 |

这组 64 轴数据的 payload 读取减少约 9.6% 耗时、约 49.3% 临时分配；16 轴分别减少约 9.4% 和 38.3%。**这不是**整个 tile 读回或进入游戏速度的提升：计时只包含从同一份真实压缩缓存记录解压出 payload，不包括区域索引磁盘读取、世界身份验证、输入签名、对象恢复、任务排队或 GPU 上传。更大收益在减少 GC/临时分配，而非单条读盘延迟。

粗到细的可见延迟还包含 `restoreCached` 的最多两个缓存任务准入、预加载候选顺序、逐点颜色/补洞/植被、签名与网格读回、覆盖交接、GPU 上传。仅优化上面 1.6 ms 的 payload 不会消除这些等待。进一步提速要在运行中的玩家世界分别看 `stageDetailMs.disk/color`、`scheduling.avgQueuedMs`、`meshHits`，再决定是否调整恢复并发和先后次序；未经测量不扩大并发或预读整片世界。

## 验证

原生 `cargo test --release --lib --tests` 通过；NeoForge 和 Forge 完整 Gradle build、缓存损坏/失效/网格回归与 opt-in payload A/B 测试通过。产物保持 `lib/vss-0.3.3-*.jar` 原有命名。没有提交或推送。
