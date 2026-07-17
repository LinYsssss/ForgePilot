package com.example.codereview.rag;

import com.example.codereview.common.exception.BusinessException;

public class EmbeddingReindexRequiredException extends BusinessException {

    public EmbeddingReindexRequiredException(String message) {
        super(6005, message);
    }
}
