/**
 * <p><b>职责：</b>列交付准入、分片、优先级发送窗口、带宽与已生成列刷出。
 * <p><b>依赖：</b>ColumnDeliveryService 只验证和入队，QueuedColumnSender 执行发送；共享 lifecycle/registry，不反调装配门面。
 * <p><b>线程：</b>玩家/请求相关准入与发送在服务端线程；编码和队列对象的线程安全遵守类契约。
 * <p><b>生命周期：</b>队列属于 PlayerRequestState；准入不等于完成发送，拒绝/取消应清理对应请求，背压响应保持协议语义。
 */
package dev.xantha.vss.networking.server.sending;
