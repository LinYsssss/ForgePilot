package com.acme.devhub.vcs;

/**
 * 仓库句柄。
 *
 * <p>{@code remoteUrl} 对「仅本地构建的裸镜像」为 null；访问令牌以密文
 * 存储，运行时经 SecretBox 解开。
 */
public class RepoHandle {

    private final String mirrorDirName;
    private final String remoteUrl;
    private final String username;
    private final String accessTokenCipher;

    public RepoHandle(String mirrorDirName, String remoteUrl,
                      String username, String accessTokenCipher) {
        this.mirrorDirName = mirrorDirName;
        this.remoteUrl = remoteUrl;
        this.username = username;
        this.accessTokenCipher = accessTokenCipher;
    }

    public String getMirrorDirName() {
        return mirrorDirName;
    }

    /** @return 远端地址；本地裸镜像仓库返回 null */
    public String getRemoteUrl() {
        return remoteUrl;
    }

    public String getUsername() {
        return username;
    }

    public String getAccessTokenCipher() {
        return accessTokenCipher;
    }
}
