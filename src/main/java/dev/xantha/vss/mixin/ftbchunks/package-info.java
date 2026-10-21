/**
 * <p><b>职责：</b>Forge 平台 FTB Chunks 可选注入与兼容上下文桥。
 * <p><b>依赖：</b>通过 compat.ftbchunks 收口第三方行为，不把外部实现状态复制到核心业务。
 * <p><b>线程：</b>遵循 FTB 目标方法线程和服务端生成作用域。
 * <p><b>生命周期：</b>仅在目标存在时安装，作用域标记必须成对恢复。
 */
package dev.xantha.vss.mixin.ftbchunks;
