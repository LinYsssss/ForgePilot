package com.acme.devhub.common;

/** 对称密文封套：凭据密文的加解封，密钥来自部署配置。 */
public interface SecretBox {

    /** @return 明文；cipher 为 null 时返回 null */
    String open(String cipher);
}
