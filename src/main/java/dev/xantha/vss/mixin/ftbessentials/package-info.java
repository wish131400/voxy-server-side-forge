/**
 * <p><b>职责：</b>FTB Essentials 传送过程的非阻塞适配。
 * <p><b>依赖：</b>只修改外部调用交接点；权限、命令和传送规则仍由原模组负责。
 * <p><b>线程：</b>遵守服务端命令/异步加载 continuation 的线程交接。
 * <p><b>生命周期：</b>目标缺失时不安装；失败和取消沿原调用路径结束。
 */
package dev.xantha.vss.mixin.ftbessentials;
