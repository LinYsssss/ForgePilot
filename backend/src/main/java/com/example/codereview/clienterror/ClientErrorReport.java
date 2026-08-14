package com.example.codereview.clienterror;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 前端错误上报载荷,与前端挂载侧({@code window.onerror} / {@code unhandledrejection} →
 * {@code sendBeacon})约定的形状:{@code {message, stack?, url, ts}},stack 可空。
 *
 * <p>刻意不在这里加长度上限:契约是「超限截断而不拒绝」——一次真实崩溃的超长堆栈
 * 也是诊断素材,拒收等于丢样本。截断在 {@link ClientErrorReportService} 落日志前做。
 * ts 是前端时钟的 epoch 毫秒,仅作参考关联;服务端不信任它,排障以日志落盘时间为准。
 */
public record ClientErrorReport(
        @NotBlank String message,
        String stack,
        @NotBlank String url,
        @NotNull Long ts
) {
}
