package com.acme.devhub.vcs;

/** vcs 操作失败。消息已脱敏，可直接外抛。 */
public class VcsOperationException extends RuntimeException {

    public VcsOperationException(String message) {
        super(message);
    }
}
