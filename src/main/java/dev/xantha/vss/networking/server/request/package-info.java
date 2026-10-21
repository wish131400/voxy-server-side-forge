/**
 * <p><b>职责：</b>批量请求、取消/控制消息与缓存→磁盘→生成读取管线。
 * <p><b>依赖：</b>构造注入共享生命周期和交付服务；不依赖 VSSServerNetworking，不复制发送和会话状态。
 * <p><b>线程：</b>服务端线程验证请求与玩家；磁盘异步回调返回后重新检查 registry 身份和 epoch。
 * <p><b>生命周期：</b>取消、过期和停止路径都结束请求/释放许可；失败响应必须与原请求编号匹配。
 */
package dev.xantha.vss.networking.server.request;
