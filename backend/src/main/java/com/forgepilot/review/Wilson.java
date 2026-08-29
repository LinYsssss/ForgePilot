package com.forgepilot.review;

/**
 * 二项比例的 95% Wilson 区间。
 *
 * <p>公式与常数逐字对应 {@code evaluation/tools/formal_evaluation.py} 里的
 * {@code wilson(successes, total)}：同一个 z 值、{@code total} 为 0 时同样答
 * 「没有区间」、端点同样夹到 {@code [0,1]} 并保留六位小数。两处必须给出同一个数——
 * 正式评测报告与产品页面若对同一批计数得出不同的区间，读者无从判断该信哪个。
 * 改这里就要同时改那里。
 *
 * <p>用 Wilson 而不是正态近似（Wald），因为本用途的样本量一开始就很小：
 * Wald 在 n 小或比例贴近 0 与 1 时会给出越界的端点，甚至在比例恰为 0 或 1 时
 * 给出宽度为零的区间——而那正是「样本不足」最需要被看见的时刻。
 */
final class Wilson {

    private static final double Z = 1.959963984540054;

    private Wilson() {
    }

    /**
     * @return 该比例的 95% 区间；{@code total} 为 0 时返回 {@code null}，
     *         表示「没有区间可言」，而不是 {@code [0, 0]}——后者会被读成
     *         「已经测得比例为零」。
     */
    static ReviewViews.Interval interval(long successes, long total) {
        if (total == 0) {
            return null;
        }
        double proportion = (double) successes / total;
        double denominator = 1 + Z * Z / total;
        double center = (proportion + Z * Z / (2.0 * total)) / denominator;
        double margin = Z * Math.sqrt(proportion * (1 - proportion) / total
                + Z * Z / (4.0 * total * total)) / denominator;
        return new ReviewViews.Interval(round(Math.max(0.0, center - margin)),
                round(Math.min(1.0, center + margin)));
    }

    private static double round(double value) {
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }
}
