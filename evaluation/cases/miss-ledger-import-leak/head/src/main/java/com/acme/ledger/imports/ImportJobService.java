package com.acme.ledger.imports;

import com.acme.ledger.repository.LedgerEntryStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 导入作业编排：解析 → 校验 → 分批入库 → 汇总。
 */
public class ImportJobService {

    /** 单批插入条数，控制事务大小。 */
    private static final int BATCH_SIZE = 500;

    private final LedgerCsvParser parser;
    private final ImportValidationService validationService;
    private final LedgerEntryStore store;

    public ImportJobService(LedgerCsvParser parser,
                            ImportValidationService validationService,
                            LedgerEntryStore store) {
        this.parser = parser;
        this.validationService = validationService;
        this.store = store;
    }

    /** 执行一次导入作业。 */
    public ImportSummary runImport(Long tenantId, Path csvFile) throws IOException {
        List<LedgerCsvParser.ParsedRow> rows = parser.parse(csvFile, tenantId);
        ImportValidationService.ValidationOutcome outcome = validationService.validate(rows);

        int inserted = 0;
        List<com.acme.ledger.model.LedgerEntry> accepted = outcome.accepted();
        for (int from = 0; from < accepted.size(); from += BATCH_SIZE) {
            int to = Math.min(from + BATCH_SIZE, accepted.size());
            store.batchInsert(accepted.subList(from, to));
            inserted += to - from;
        }
        return new ImportSummary(rows.size(), inserted, outcome.rejected());
    }

    /** 导入汇总：总行数（不含表头）、入库条数、拒绝清单。 */
    public record ImportSummary(int totalRows, int inserted, List<String> rejected) {
    }
}
