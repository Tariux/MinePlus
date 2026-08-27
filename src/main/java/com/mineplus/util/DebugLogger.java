package com.mineplus.util;

import com.mineplus.config.MineplusConfig;

import java.util.logging.Level;
import java.util.logging.Logger;

public class DebugLogger {

    private static MineplusConfig config;
    private static Logger logger;

    public static void init(MineplusConfig mineplusConfig, Logger pluginLogger) {
        config = mineplusConfig;
        logger = pluginLogger;
    }

    public static boolean isEnabled() {
        return config != null && logger != null && config.isAdditionalDebugLogs();
    }

    public static void debug(String message) {
        if (config != null && logger != null && config.isAdditionalDebugLogs()) {
            logger.log(Level.INFO, "[DEBUG] " + message);
        }
    }

    public static void debug(String format, Object... args) {
        if (config != null && logger != null && config.isAdditionalDebugLogs()) {
            logger.log(Level.INFO, String.format("[DEBUG] " + format, args));
        }
    }

    public static void debug(String message, Throwable thrown) {
        if (config != null && logger != null && config.isAdditionalDebugLogs()) {
            logger.log(Level.INFO, "[DEBUG] " + message, thrown);
        }
    }

    public static void info(String message) {
        if (config != null && logger != null && config.isAdditionalDebugLogs()) {
            logger.log(Level.INFO, message);
        }
    }

    public static void info(String format, Object... args) {
        if (config != null && logger != null && config.isAdditionalDebugLogs()) {
            logger.log(Level.INFO, String.format(format, args));
        }
    }

    public static void warning(String message) {
        if (config != null && logger != null && config.isAdditionalDebugLogs()) {
            logger.log(Level.WARNING, message);
        }
    }

    public static void warning(String format, Object... args) {
        if (config != null && logger != null && config.isAdditionalDebugLogs()) {
            logger.log(Level.WARNING, String.format(format, args));
        }
    }

    public static void severe(String message) {
        if (config != null && logger != null && config.isAdditionalDebugLogs()) {
            logger.log(Level.SEVERE, message);
        }
    }

    public static void severe(String format, Object... args) {
        if (config != null && logger != null && config.isAdditionalDebugLogs()) {
            logger.log(Level.SEVERE, String.format(format, args));
        }
    }

    public static void severe(String message, Throwable thrown) {
        if (config != null && logger != null && config.isAdditionalDebugLogs()) {
            logger.log(Level.SEVERE, message, thrown);
        }
    }
}
