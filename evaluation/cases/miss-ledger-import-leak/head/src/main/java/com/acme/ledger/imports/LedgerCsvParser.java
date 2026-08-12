package com.acme.ledger.imports;

import com.acme.ledger.model.LedgerEntry;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 台账 CSV 解析。
 *
 * <p>列约定：biz_order_no,biz_date,amount_fen,direction。金额列必须是
 * 整数「分」，出现小数点直接判为格式错误。编码支持 UTF-8（含 BOM）与
 * GBK，通过文件头嗅探判定。
 */
public class LedgerCsvParser {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final int EXPECTED_COLUMNS = 4;

    /**
     * 嗅探文件编码：带 UTF-8 BOM 判 UTF-8，否则按首行是否为合法 UTF-8
     * 简易判定，不合法回落 GBK。
     */
    Charset detectCharset(Path file) throws IOException {
        InputStream in = Files.newInputStream(file);
        byte[] head = new byte[3];
        int read = in.read(head);
        if (read == 3 && (head[0] & 0xFF) == 0xEF
                && (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        byte[] probe = new byte[4096];
        int probeLen = in.read(probe);
        if (probeLen <= 0) {
            return StandardCharsets.UTF_8;
        }
        String decoded = new String(probe, 0, probeLen, StandardCharsets.UTF_8);
        return decoded.contains("�") ? Charset.forName("GBK") : StandardCharsets.UTF_8;
    }

    /** 解析整个 CSV 文件，首行为表头，逐行转 {@link ParsedRow}。 */
    public List<ParsedRow> parse(Path file, Long tenantId) throws IOException {
        Charset charset = detectCharset(file);
        List<ParsedRow> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, charset)) {
            String line = reader.readLine(); // 表头行跳过
            int lineNo = 1;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                rows.add(parseLine(line, lineNo, tenantId));
            }
        }
        return rows;
    }

    private ParsedRow parseLine(String line, int lineNo, Long tenantId) {
        String[] cols = line.split(",", -1);
        if (cols.length != EXPECTED_COLUMNS) {
            return ParsedRow.invalid(lineNo, "列数错误: " + cols.length);
        }
        String orderNo = cols[0].trim();
        if (orderNo.isEmpty()) {
            return ParsedRow.invalid(lineNo, "业务单号为空");
        }
        LocalDate bizDate;
        try {
            bizDate = LocalDate.parse(cols[1].trim(), DATE_FORMAT);
        } catch (Exception ex) {
            return ParsedRow.invalid(lineNo, "日期格式错误: " + cols[1]);
        }
        long amountFen;
        try {
            amountFen = Long.parseLong(cols[2].trim());
        } catch (NumberFormatException ex) {
            return ParsedRow.invalid(lineNo, "金额必须为整数分: " + cols[2]);
        }
        String direction = cols[3].trim();
        if (!"IN".equals(direction) && !"OUT".equals(direction)) {
            return ParsedRow.invalid(lineNo, "方向必须为 IN/OUT: " + direction);
        }
        return ParsedRow.valid(lineNo,
                new LedgerEntry(tenantId, orderNo, bizDate, amountFen, direction));
    }

    /** 解析行：合法行携带条目，非法行携带原因。 */
    public record ParsedRow(int lineNo, LedgerEntry entry, String error) {

        static ParsedRow valid(int lineNo, LedgerEntry entry) {
            return new ParsedRow(lineNo, entry, null);
        }

        static ParsedRow invalid(int lineNo, String error) {
            return new ParsedRow(lineNo, null, error);
        }

        public boolean isValid() {
            return error == null;
        }
    }
}
