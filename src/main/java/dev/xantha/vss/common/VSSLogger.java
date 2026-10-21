package dev.xantha.vss.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VSSLogger {
    private static final Logger LOG = LoggerFactory.getLogger("VSS");

    private static volatile java.util.function.BooleanSupplier debugEnabled = () -> false;

    /** 启动入口提供动态配置查询，日志底层不反向加载配置或持有配置快照。 */
    public static void configureDebug(java.util.function.BooleanSupplier source) {
        debugEnabled = java.util.Objects.requireNonNull(source, "source");
    }

    private VSSLogger() {
    }

    public static void debug(String msg) {
        if (!isDebugEnabled()) {
            return;
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug(msg);
        } else {
            LOG.info("[debug] " + msg);
        }
    }

    public static boolean isDebugEnabled() {
        try {
            return debugEnabled.getAsBoolean() || LOG.isDebugEnabled();
        } catch (Throwable ignored) {
            return LOG.isDebugEnabled();
        }
    }

    public static void info(String msg) {
        LOG.info(msg);
    }

    public static void warn(String msg) {
        LOG.warn(msg);
    }

    public static void warn(String msg, Throwable t) {
        LOG.warn(msg, t);
    }

    public static void error(String msg, Throwable t) {
        LOG.error(msg, t);
    }
}
