package com.mineplus.config;

public class MineplusConfig {

    private final boolean additionalDebugLogs;

    public MineplusConfig() {
        this(false);
    }

    public MineplusConfig(boolean additionalDebugLogs) {
        this.additionalDebugLogs = additionalDebugLogs;
    }

    public boolean isAdditionalDebugLogs() {
        return additionalDebugLogs;
    }

    public boolean getAdditionalDebugLogs() {
        return additionalDebugLogs;
    }
}
