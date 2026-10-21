/**
 * <p><b>职责：</b>服务端平台事件与共享服务的唯一装配入口。
 * <p><b>依赖：</b>VSSServerNetworking 创建 registry/lifecycle/delivery/session lookup 并注入服务；旧命令与广播适配器可暂经静态门面，新业务不得反向调用。
 * <p><b>线程：</b>平台 handler 把游戏状态工作切回服务端线程；耗时读写提交 runtime。
 * <p><b>生命周期：</b>ServerNetworkingLifecycle 统一开始/停止，epoch 防止旧世界异步结果复活；玩家状态只有一份注册表。
 */
package dev.xantha.vss.networking.server;
