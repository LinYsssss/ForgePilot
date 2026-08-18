package com.example.codereview.dashboard;

import com.example.codereview.common.api.ErrorCode;
import com.example.codereview.common.exception.BusinessException;
import java.time.Duration;

public enum MetricsWindow {
    DAYS_7("7d", Duration.ofDays(7)),
    DAYS_30("30d", Duration.ofDays(30)),
    DAYS_90("90d", Duration.ofDays(90));

    private final String value;
    private final Duration duration;

    MetricsWindow(String value, Duration duration) {
        this.value = value;
        this.duration = duration;
    }

    public String value() {
        return value;
    }

    public Duration duration() {
        return duration;
    }

    public static MetricsWindow parse(String raw) {
        String value = raw == null || raw.isBlank() ? "30d" : raw;
        for (MetricsWindow window : values()) {
            if (window.value.equals(value)) {
                return window;
            }
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "window 仅支持 7d、30d、90d");
    }
}
