package com.forgepilot.scm.github;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.forgepilot.common.ApiException;
import com.forgepilot.scm.ChangedFile;
import com.forgepilot.scm.OutboundUrlPolicy;
import com.forgepilot.scm.PullRequestSnapshot;
import com.forgepilot.scm.ScmRepository;
import com.forgepilot.scm.ScmSecretCipher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * 从 GitHub 读取权威的 PR 快照。
 *
 * <p>base URI 来自 {@code scm_repository.api_base}，代码中绝不硬编码任何 host。
 * 这不是为测试让步：自建实例必须可用，而同一个列也正是
 * 集成测试得以在无凭据、无生产改动的前提下把仓库指向回环桩服务的原因。
 * 每次调用都会用 {@link OutboundUrlPolicy} 重新校验地址，因为该列可由 LEADER
 * 配置，而策略可能在此期间收紧过。
 *
 * <p>仓库以其数字 id 而非 {@code owner/repo} 来寻址：id 能在改名或转移后存活，
 * slug 不能。
 */
@Component
public class GitHubClient {

    private static final int PAGE_SIZE = 100;
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

    private final OutboundUrlPolicy outbound;
    private final ScmSecretCipher cipher;
    private final JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();

    public GitHubClient(OutboundUrlPolicy outbound, ScmSecretCipher cipher) {
        this.outbound = outbound;
        this.cipher = cipher;
        this.requestFactory.setReadTimeout(READ_TIMEOUT);
    }

    PullRequestSnapshot fetch(ScmRepository repository, int number) {
        RestClient client = clientFor(repository);
        String externalId = repository.getExternalId();

        JsonNode pullRequest = client.get()
                .uri("/repositories/{repository}/pulls/{number}", externalId, number)
                .retrieve()
                .body(JsonNode.class);

        return new PullRequestSnapshot(
                number,
                required(pullRequest.path("base"), "sha", "base.sha"),
                required(pullRequest.path("head"), "sha", "head.sha"),
                required(pullRequest.path("head"), "ref", "head.ref"),
                required(pullRequest, "title"),
                // GitHub 不提供稳定的 diff 修订号，因此这一格保持为空，
                // 定序完全依赖 updated_at。
                null,
                Instant.parse(required(pullRequest, "updated_at")),
                required(pullRequest.path("user"), "id", "user.id"),
                required(pullRequest.path("user"), "login", "user.login"),
                changedFiles(client, externalId, number));
    }

    public void applyDecision(ScmRepository repository, int number, boolean approved) {
        RestClient client = clientFor(repository);
        JsonNode pullRequest = client.get()
                .uri("/repositories/{repository}/pulls/{number}", repository.getExternalId(), number)
                .retrieve()
                .body(JsonNode.class);
        String headRef = required(pullRequest.path("head"), "ref", "head.ref");
        String defaultBranch = required(pullRequest.path("base").path("repo"), "default_branch",
                "base.repo.default_branch");
        if (approved) {
            client.put()
                    .uri("/repositories/{repository}/pulls/{number}/merge", repository.getExternalId(), number)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"merge_method\":\"merge\"}")
                    .retrieve()
                    .toBodilessEntity();
        } else {
            client.patch()
                    .uri("/repositories/{repository}/pulls/{number}", repository.getExternalId(), number)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"state\":\"closed\"}")
                    .retrieve()
                    .toBodilessEntity();
        }
        if (!headRef.equals(defaultBranch)) {
            client.delete()
                    .uri("/repositories/{repository}/git/refs/heads/{branch}", repository.getExternalId(), headRef)
                    .retrieve()
                    .toBodilessEntity();
        }
    }

    private RestClient clientFor(ScmRepository repository) {
        URI base = outbound.requireAllowed(repository.getApiBase());
        // 按仓库逐个构建，而不是用注入的共享 builder：api_base 是**按仓库**
        // 存在的数据（自建实例），因此不存在一个共享 builder 能携带的
        // 统一 base URI；而携带固定 host 的那种，正是被禁止的硬编码。
        return RestClient.builder()
                .baseUrl(base.toString())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultHeader(HttpHeaders.AUTHORIZATION,
                        "Bearer " + cipher.decrypt(repository.getEncryptedToken()))
                .build();
    }

    /**
     * 文件列表是分页的，且跨页顺序是惯例而非契约——这正是指纹要排序的原因。
     * 循环的上界与行本身的清单上限一致，因此超大 PR 会显式失败，
     * 而不是先在内存里累积起来。
     */
    private List<ChangedFile> changedFiles(RestClient client, String externalId, int number) {
        List<ChangedFile> files = new ArrayList<>();
        int characters = 0;
        for (int page = 1; ; page++) {
            int current = page;
            JsonNode batch = client.get()
                    .uri(uri -> uri.path("/repositories/{repository}/pulls/{number}/files")
                            .queryParam("per_page", PAGE_SIZE)
                            .queryParam("page", current)
                            .build(externalId, number))
                    .retrieve()
                    .body(JsonNode.class);
            for (JsonNode file : batch) {
                JsonNode patch = file.path("patch");
                // 「缺席」不等于「空」：二进制文件或超出 GitHub 自身 diff 上限的
                // 文件根本不带 patch，而指纹会把这两种情况区分开。
                String content = patch.isMissingNode() || patch.isNull() ? null : patch.asString();
                ChangedFile changed = new ChangedFile(required(file, "filename"),
                        required(file, "status"), content);
                characters += changed.path().length() + (content == null ? 0 : content.length());
                if (characters > ChangedFile.MAX_TOTAL_CHARS) {
                    throw ApiException.unprocessable(
                            "This pull request's diff is larger than this deployment stores.");
                }
                files.add(changed);
            }
            if (batch.size() < PAGE_SIZE) {
                return files;
            }
        }
    }

    /**
     * 一个权威字段若缺席、为 null 或是个容器，就必须大声失败。
     * {@code JsonNode.asString()} 对缺失或 null 的节点会返回 ""，于是就会写下
     * base_sha='' 或 author_external_user_id=''——两者都满足 NOT NULL，
     * 数据库因此抓不住，而指纹随后会基于空 SHA 计算出来。
     * author_external_user_id 更糟：它是授权键，
     * 于是所有「幽灵作者」的 PR 都会共享同一个身份。
     * R3 要求畸形输入必须显式失败，而不是存下坏数据。
     */
    private static String required(JsonNode parent, String field, String label) {
        JsonNode node = parent.path(field);
        if (!node.isValueNode() || node.isNull()) {
            throw ApiException.unprocessable(
                    "The provider's pull request is missing " + label + ".");
        }
        String value = node.asString();
        if (value.isBlank()) {
            throw ApiException.unprocessable("The provider's pull request has an empty " + label + ".");
        }
        return value;
    }

    private static String required(JsonNode parent, String field) {
        return required(parent, field, field);
    }
}
