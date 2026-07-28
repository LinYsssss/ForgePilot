package com.example.codereview.knowledge;

import com.example.codereview.common.api.ErrorCode;
import com.example.codereview.common.exception.BusinessException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * Input boundary for knowledge uploads.
 *
 * <p>The previous implementation trusted the file extension and then called {@code getBytes()},
 * which reads the whole upload into memory with no ceiling, and decoded it with a lenient UTF-8
 * decoder that silently turns arbitrary binary into replacement characters. A ".md" suffix on a
 * 500 MB binary was therefore accepted and indexed.
 *
 * <p>Filenames are reduced to a sanitised basename before they are stored. They are only ever
 * displayed, never used to build a path, and this keeps it that way even if a caller sends
 * {@code ../../etc/passwd}.
 */
@Component
public class KnowledgeUploadValidator {

    private static final long ABSOLUTE_MAX_BYTES = 8L * 1024 * 1024;
    private static final int MAX_FILENAME_LENGTH = 255;

    private final long maxBytes;
    private final int maxCharacters;

    public KnowledgeUploadValidator(
            @Value("${app.knowledge.max-file-bytes:2097152}") long maxBytes,
            @Value("${app.knowledge.max-characters:400000}") int maxCharacters
    ) {
        this.maxBytes = Math.min(maxBytes, ABSOLUTE_MAX_BYTES);
        this.maxCharacters = maxCharacters;
    }

    /** @return the decoded text, once the upload has been proven to be text within the limits. */
    public String readText(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_UPLOAD_REJECTED, "上传文件为空");
        }
        requireSupportedExtension(file.getOriginalFilename());
        if (file.getSize() > maxBytes) {
            throw new BusinessException(
                    ErrorCode.PAYLOAD_TOO_LARGE, "文档超出大小限制（最大 " + (maxBytes / 1024) + " KB）");
        }

        byte[] bytes = readBounded(file);
        rejectBinaryContent(bytes);
        String text = decodeStrictUtf8(bytes);
        if (text.isBlank()) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_UPLOAD_REJECTED, "文档内容为空");
        }
        if (text.length() > maxCharacters) {
            throw new BusinessException(
                    ErrorCode.PAYLOAD_TOO_LARGE, "文档文本超出长度限制（最大 " + maxCharacters + " 字符）");
        }
        return text;
    }

    /**
     * Strips any directory component and control characters. The result is for display only; it
     * must never be concatenated into a filesystem path.
     */
    public String sanitizeFileName(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "untitled.md";
        }
        String basename = originalFilename
                .replace('\\', '/')
                .substring(originalFilename.replace('\\', '/').lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}]", "")
                .strip();
        // "..", "." and an empty remainder are all path expressions rather than names.
        if (basename.isBlank() || basename.equals(".") || basename.equals("..")) {
            return "untitled.md";
        }
        return basename.length() > MAX_FILENAME_LENGTH
                ? basename.substring(0, MAX_FILENAME_LENGTH)
                : basename;
    }

    private void requireSupportedExtension(String originalFilename) {
        String name = originalFilename == null ? "" : originalFilename.toLowerCase(Locale.ROOT);
        if (!(name.endsWith(".md") || name.endsWith(".txt"))) {
            throw new BusinessException(
                    ErrorCode.KNOWLEDGE_UPLOAD_REJECTED, "仅支持 Markdown 和 TXT 文档");
        }
    }

    /**
     * Reads at most {@code maxBytes + 1} so an over-sized upload is detected without materialising
     * it. {@code MultipartFile#getSize} is checked first, but it comes from the request and a
     * streaming upload can exceed it.
     */
    private byte[] readBounded(MultipartFile file) {
        try (InputStream stream = file.getInputStream()) {
            byte[] bytes = stream.readNBytes((int) Math.min(maxBytes + 1, Integer.MAX_VALUE));
            if (bytes.length > maxBytes) {
                throw new BusinessException(
                        ErrorCode.PAYLOAD_TOO_LARGE, "文档超出大小限制（最大 " + (maxBytes / 1024) + " KB）");
            }
            return bytes;
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_UPLOAD_REJECTED, "读取文件失败");
        }
    }

    private void rejectBinaryContent(byte[] bytes) {
        for (byte value : bytes) {
            if (value == 0) {
                throw new BusinessException(
                        ErrorCode.KNOWLEDGE_UPLOAD_REJECTED, "文档内容不是纯文本");
            }
        }
    }

    /**
     * Decodes with a strict decoder so malformed sequences are an error rather than a stream of
     * U+FFFD. Silently accepting them would mean indexing corrupted text and embedding it.
     */
    private String decodeStrictUtf8(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException ex) {
            throw new BusinessException(
                    ErrorCode.KNOWLEDGE_UPLOAD_REJECTED, "文档不是有效的 UTF-8 文本");
        }
    }
}
