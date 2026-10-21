/**
 * <p><b>职责：</b>服务端地形快照扩展点、密度特征声明与可用快照选择。
 * <p><b>依赖：</b>通过快照与 provider 契约扩展；不引用客户端预测、网络或具体模组适配器。
 * <p><b>线程：</b>采集时遵守 TerrainSnapshotContext 的服务端上下文；跨线程只交付已经捕获的数据。
 * <p><b>生命周期：</b>provider 按身份注册，快照按世界/维度版本消费；不支持的能力必须显式回退。
 */
package dev.xantha.vss.api.worldgen;
