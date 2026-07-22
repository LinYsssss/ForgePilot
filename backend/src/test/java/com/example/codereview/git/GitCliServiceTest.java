package com.example.codereview.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.codereview.common.security.CryptoService;
import org.junit.jupiter.api.Test;

class GitCliServiceTest {

    private final GitCliService service = new GitCliService(mock(CryptoService.class), false);

    @Test
    void normalizeRemoteIgnoresTrailingSlashDotGitAndWhitespace() {
        String base = service.normalizeRemote("https://github.com/acme/app");
        assertThat(service.normalizeRemote("https://github.com/acme/app.git")).isEqualTo(base);
        assertThat(service.normalizeRemote("https://github.com/acme/app/")).isEqualTo(base);
        assertThat(service.normalizeRemote("https://github.com/acme/app///")).isEqualTo(base);
        assertThat(service.normalizeRemote("  https://github.com/acme/app.git  ")).isEqualTo(base);
        assertThat(service.normalizeRemote("https://github.com/acme/app.GIT")).isEqualTo(base);
    }

    @Test
    void normalizeRemoteDistinguishesDifferentRepositories() {
        assertThat(service.normalizeRemote("https://github.com/acme/app"))
                .isNotEqualTo(service.normalizeRemote("https://github.com/acme/other"));
    }

    @Test
    void normalizeRemoteHandlesNullAndShortInput() {
        assertThat(service.normalizeRemote(null)).isEmpty();
        assertThat(service.normalizeRemote(".git")).isEmpty();      // 仅后缀 -> 去掉后为空串
        assertThat(service.normalizeRemote("ab")).isEqualTo("ab");  // 长度 < 4,regionMatches 负偏移安全返回 false
    }
}
