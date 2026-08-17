package com.example.codereview.review;

import com.example.codereview.review.CoverageDtos.AcCoverage;
import com.example.codereview.review.CoverageDtos.AcRef;
import com.example.codereview.review.CoverageDtos.CoverageEvidence;
import com.example.codereview.review.CoverageDtos.CoverageInput;
import com.example.codereview.review.CoverageDtos.CoverageResult;
import com.example.codereview.review.CoverageDtos.CoverageVerdict;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 覆盖判定 mock 实现(P4a):确定性关键词启发,零外部依赖,离线演示三态皆可触达:
 * AC 关键词命中 diff → COVERED(证据=第一个 diff 文件);含模糊词 → AT_RISK;否则 NOT_FOUND。
 */
@Service
@ConditionalOnProperty(prefix = "app.ai", name = "provider", havingValue = "mock", matchIfMissing = true)
public class MockCoverageJudgeClient implements CoverageJudgeClient {

    private static final Pattern FIRST_DIFF_FILE = Pattern.compile("diff --git a/\\S+ b/(\\S+)");
    private static final Pattern VAGUE = Pattern.compile("尽量|适当|大概|等等|合理|友好");

    @Override
    public CoverageResult judge(CoverageInput input, String shardSummaries, String diffExcerpt) {
        String diffLower = (diffExcerpt == null ? "" : diffExcerpt).toLowerCase(Locale.ROOT);
        Matcher fileMatcher = FIRST_DIFF_FILE.matcher(diffExcerpt == null ? "" : diffExcerpt);
        String firstFile = fileMatcher.find() ? fileMatcher.group(1) : null;
        List<AcCoverage> coverage = new ArrayList<>();
        for (AcRef ac : input.acs()) {
            String text = ac.text() == null ? "" : ac.text();
            if (VAGUE.matcher(text).find()) {
                coverage.add(new AcCoverage(ac.acId(), text, CoverageVerdict.AT_RISK.name(), List.of(),
                        "AC 表述含模糊词，无法二值判定覆盖情况"));
                continue;
            }
            boolean hit = keywordHit(text, diffLower);
            if (hit && firstFile != null) {
                coverage.add(new AcCoverage(ac.acId(), text, CoverageVerdict.COVERED.name(),
                        List.of(new CoverageEvidence(firstFile, null, null, "关键词命中变更文件")),
                        "diff 中出现与 AC 关键词一致的改动"));
            } else {
                coverage.add(new AcCoverage(ac.acId(), text, CoverageVerdict.NOT_FOUND.name(), List.of(),
                        "diff 中未发现与该 AC 相关的实现"));
            }
        }
        return new CoverageResult(List.copyOf(coverage), "mock-coverage-judge", 0);
    }

    private boolean keywordHit(String acText, String diffLower) {
        for (String token : acText.split("[，。,.;:\\s/()（）]+")) {
            if (token.length() >= 2 && diffLower.contains(token.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
