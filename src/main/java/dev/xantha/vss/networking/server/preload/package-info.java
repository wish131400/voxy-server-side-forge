/**
 * <p><b>职责：</b>已存在区域的扫描、预读和客户端缺失列补齐。
 * <p><b>依赖：</b>注入共享 registry/lifecycle/delivery/runtime/storage；不经服务端静态门面绕回调用。
 * <p><b>线程：</b>目录/列读取在磁盘线程；玩家、范围和会话有效性在服务端线程重新确认。
 * <p><b>生命周期：</b>换服/离线/停止使旧读取失效；完成、拒绝和取消均归还预读计数，不能占住实时请求容量。
 */
package dev.xantha.vss.networking.server.preload;
