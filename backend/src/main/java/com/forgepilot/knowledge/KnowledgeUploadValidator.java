package com.forgepilot.knowledge;

import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;

import com.forgepilot.common.ApiException;
import org.springframework.stereotype.Component;

/**
 * 在任何内容被分块或向量化之前，先拒绝那些绝不该进入数据库的文本。
 *
 * <p>这不是对数据库约束的防御性重复。这四道检查里有两道数据库根本做不到；
 * 另外两道提前做，是为了避免为**证明落不了库**的输入向 embedding provider 付费。
 */
@Component
public class KnowledgeUploadValidator {

    /** ARCHITECTURE.md 7.2。varlena 上限起不到防护作用：实测 600 MB 文本照样落库成功。 */
    static final int MAX_TEXT_BYTES = 5 * 1024 * 1024;
    static final int MAX_TITLE_LENGTH = 255;

    public void validate(String title, String text) {
        if (title == null || title.isBlank()) {
            throw ApiException.unprocessable("A document needs a title.");
        }
        if (title.length() > MAX_TITLE_LENGTH) {
            throw ApiException.unprocessable("The title is longer than " + MAX_TITLE_LENGTH + " characters.");
        }
        if (text == null || text.isBlank()) {
            throw ApiException.unprocessable("The document has no readable text.");
        }
        rejectNulByte(text);
        rejectUnencodableText(text);
        rejectOversizedText(text);
    }

    /**
     * PostgreSQL 会以 22021 拒绝它，但那已经是在分块和向量化的钱都花完之后了。
     */
    private void rejectNulByte(String text) {
        if (text.indexOf('\0') >= 0) {
            throw ApiException.unprocessable("The document text contains a NUL byte.");
        }
    }

    /**
     * 数据库唯一抓不住的那一种。实测：孤立的 UTF-16 代理项会被 JDBC 驱动
     * 静默替换成 '?'，于是 PostgreSQL 收到的是合法 UTF-8，永远不会抛 22021——
     * 文本被损坏了，却哪里都没有报错。
     * {@link CharsetEncoder#canEncode} 正是让这件事变得可见的地方。
     */
    private void rejectUnencodableText(String text) {
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder();
        if (!encoder.canEncode(text)) {
            throw ApiException.unprocessable(
                    "The document text is not valid Unicode; it contains an unpaired surrogate.");
        }
    }

    /** 按 UTF-8 字节计数，因为列里存的就是 UTF-8 字节。 */
    private void rejectOversizedText(String text) {
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_TEXT_BYTES) {
            throw ApiException.unprocessable(
                    "The document is " + bytes + " bytes, over the " + MAX_TEXT_BYTES + " byte limit.");
        }
    }
}
