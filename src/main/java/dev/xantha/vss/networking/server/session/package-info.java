/**
 * <p><b>职责：</b>玩家注册/握手、客户端已知列查询、世界生成配置与模板快照交付。
 * <p><b>依赖：</b>PlayerSessionManager/Lookup 注入唯一 registry/lifecycle；世界生成扩展通过 api.worldgen/公共描述，不引用客户端预测。
 * <p><b>线程：</b>玩家和真实注册表读取在服务端线程或目标允许的上下文；大型编码/模板结果必须验证会话版本后交付。
 * <p><b>生命周期：</b>登录/登出/切维度沿现有会话重置；停止统一表现为无可用会话，模板/配置缓存随世界与资源身份失效。
 */
package dev.xantha.vss.networking.server.session;
