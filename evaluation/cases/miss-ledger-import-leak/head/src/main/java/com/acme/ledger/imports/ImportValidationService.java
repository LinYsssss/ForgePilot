package com.acme.ledger.imports;

import com.acme.ledger.model.LedgerEntry;
import com.acme.ledger.repository.LedgerEntryStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 导入校验：文件内去重 + 与已入库数据去重，金额与方向的业务约束。
 */
public class ImportValidationService {

    /** 单笔金额上限：10 万元（分）。超出判为可疑数据，人工复核。 */
    private static final long MAX_AMOUNT_FEN = 10_000_000L;

    private final LedgerEntryStore store;

    public ImportValidationService(LedgerEntryStore store) {
        this.store = store;
    }

    /** 校验解析产物，返回接受/拒绝清单。 */
    public ValidationOutcome validate(List<LedgerCsvParser.ParsedRow> rows) {
        List<LedgerEntry> accepted = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        Set<String> seenOrderNos = new HashSet<>();

        for (LedgerCsvParser.ParsedRow row : rows) {
            if (!row.isValid()) {
                rejected.add("第 " + row.lineNo() + " 行: " + row.error());
                continue;
            }
            LedgerEntry entry = row.entry();
            if (!seenOrderNos.add(entry.getBizOrderNo())) {
                rejected.add("第 " + row.lineNo() + " 行: 文件内重复单号 " + entry.getBizOrderNo());
                continue;
            }
            if (entry.getAmountFen() <= 0) {
                rejected.add("第 " + row.lineNo() + " 行: 金额必须为正");
                continue;
            }
            if (entry.getAmountFen() > MAX_AMOUNT_FEN) {
                rejected.add("第 " + row.lineNo() + " 行: 金额超上限，转人工复核");
                continue;
            }
            if (store.existsByBizOrderNo(entry.getTenantId(), entry.getBizOrderNo())) {
                rejected.add("第 " + row.lineNo() + " 行: 单号已入库 " + entry.getBizOrderNo());
                continue;
            }
            accepted.add(entry);
        }
        return new ValidationOutcome(accepted, rejected);
    }

    /** 校验结果：接受条目 + 拒绝原因清单。 */
    public record ValidationOutcome(List<LedgerEntry> accepted, List<String> rejected) {
    }
}
