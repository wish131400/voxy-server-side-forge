/**
 * <p><b>职责：</b>常规列生成、chunky 扫描/生成队列、任务预算及增量可用通知。
 * <p><b>依赖：</b>使用注入的 lifecycle/session lookup、缓存与 runtime；ChunkyGenerationService 不反调装配门面。
 * <p><b>线程：</b>真实区块访问按服务端/生成 API 线程约定执行；扫描/缓存读写走 runtime，通知回到服务端线程。
 * <p><b>生命周期：</b>取消/停止释放任务容量与票据，旧 epoch 不交付；速度优先和队列限制遵从用户配置，重构不得暗加限速。
 */
package dev.xantha.vss.networking.server.generation;
