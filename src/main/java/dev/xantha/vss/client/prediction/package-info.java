/**
 * <p><b>职责：</b>预测需求、地形采样、网格与缓存、覆盖交接和独立 GPU 场景。内部 package-private 类型属于同一实现模块。
 * <p><b>依赖：</b>通过 api.ClientLodSession 查询网络会话，compat 提供 Voxy 实际覆盖；不得依赖 networking.client 实现包，包含连接身份、门面与适配器。
 * <p><b>线程：</b>客户端线程捕获可变世界和交接结果；工作线程计算安全快照；OpenGL 创建、上传、绘制和释放只在渲染线程。
 * <p><b>生命周期：</b>ClientPredictionState 管理维度会话，PredictionTileManager 管 CPU 场景/缓存，PredictionPlanningPipeline 管四通道准备任务，PredictionRenderer 管 GPU；旧版本结果丢弃但须释放租约和暂存。
 */
package dev.xantha.vss.client.prediction;
