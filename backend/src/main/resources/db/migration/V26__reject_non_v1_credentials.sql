-- Track B / V26：清除非 v1 格式的凭据，配合 CryptoService 改为“非 v1 即拒绝”。
--
-- 背景：CryptoService.decrypt 过去对不以 "v1:" 开头的值原样返回，因此任何未加密写入的
-- 凭据都会被当作解密结果正常使用，且没有任何信号。改为失败关闭后，这类历史数据会在
-- 运行时抛异常，必须先在数据层清理干净。
--
-- 为什么是置空而不是就地加密：加密密钥只存在于应用进程（TOKEN_ENCRYPT_KEY），
-- SQL 迁移无法执行 AES-GCM。置空是失败关闭的选择——管理员通过接口重新填写即可，
-- 而把明文继续留在库里既不安全，也会让新的校验在运行时才炸。
--
-- 本仓库当前生产库实测：三列均为 0 条非 v1 记录，因此本迁移在此环境是防御性的空操作。

update code_repository
set access_token = null
where access_token is not null
  and access_token not like 'v1:%';

update scm_installation
set encrypted_webhook_secret = null
where encrypted_webhook_secret is not null
  and encrypted_webhook_secret not like 'v1:%';

update scm_installation
set encrypted_credential = null
where encrypted_credential is not null
  and encrypted_credential not like 'v1:%';
