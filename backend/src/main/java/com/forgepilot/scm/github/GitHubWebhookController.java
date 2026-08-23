package com.forgepilot.scm.github;

import java.net.URI;
import java.net.URISyntaxException;

import com.forgepilot.common.ApiException;
import com.forgepilot.scm.InstanceIdentity;
import com.forgepilot.scm.PullRequestSyncService;
import com.forgepilot.scm.ScmProvider;
import com.forgepilot.scm.ScmRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * GitHub 的 webhook 端点。
 *
 * <p>路径里不带 {@code projectId}：一次投递并不知道项目的存在，
 * 而校验它所需的密钥正是「载荷自身所标识的那一行」上的一个列。
 * 因此顺序是固定的——从请求体里读出仓库身份、加载该行、对未经改动的字节
 * 校验签名，只有到这一步之后才开始动作。在最后一步之前失败的一切
 * 都以同一个响应体答 401，并且不写任何东西、也不发起任何拉取。
 *
 * <p>请求体以 {@code byte[]} 接收，之后才交给 Jackson，
 * 因此被认证的字节就是被据以行动的字节。
 */
@RestController
class GitHubWebhookController {

    static final String PATH = "/api/scm/github/webhook";
    private static final String PULL_REQUEST_EVENT = "pull_request";

    private final PullRequestSyncService sync;
    private final GitHubClient github;
    private final ObjectMapper json;

    GitHubWebhookController(PullRequestSyncService sync, GitHubClient github, ObjectMapper json) {
        this.sync = sync;
        this.github = github;
        this.json = json;
    }

    /**
     * PR 提交之后返回 202。此时还没有 Review 需要创建，因此
     * ARCHITECTURE.md 3.1 的“PR 与其 PENDING Review 均已提交之后”
     * 在这里退化为前半句。
     */
    @PostMapping(PATH)
    @ResponseStatus(HttpStatus.ACCEPTED)
    void receive(@RequestBody byte[] body,
            @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(name = "X-GitHub-Event", required = false) String event) {
        // 直到校验完成为止的所有步骤都跑在这个保护块内。本端点是公开且未认证的，
        // 因此一个恶意载荷绝不能抛出一个以堆栈和脱离契约的响应体逃逸出去的异常：
        // 在该出现字符串的地方放一个容器节点，或让一个超过 63 字符的 DNS label
        // 走到 IDN.toASCII——在此之前这两种情况都能做到。
        // 每一种「校验前失败」都与签名错误答得一模一样，
        // 这也同时保住了 §3.4 的不可区分性。
        ScmRepository repository;
        JsonNode payload;
        try {
            payload = parse(body);
            JsonNode repositoryNode = payload.path("repository");
            repository = sync.authenticate(ScmProvider.GITHUB,
                    instanceIdentityOf(required(repositoryNode.path("html_url"))),
                    required(repositoryNode.path("id")), body, signature);
        } catch (ApiException rejected) {
            throw rejected;
        } catch (RuntimeException malformed) {
            throw unverifiable();
        }

        if (!PULL_REQUEST_EVENT.equals(event)) {
            // 一次已通过校验、但本部署无事可做的投递——例如 GitHub 在
            // 创建 hook 时发来的那个 ping。
            return;
        }
        sync.apply(repository, github.fetch(repository, pullRequestNumber(payload)));
    }

    private JsonNode parse(byte[] body) {
        try {
            return json.readTree(body);
        } catch (JacksonException unparseable) {
            throw unauthenticated();
        }
    }

    /**
     * 实例取自仓库面向用户的 URL，并按注册该仓库时生成
     * {@code instance_identity} 的同一套规则归一化。
     */
    private static String instanceIdentityOf(String htmlUrl) {
        try {
            URI url = new URI(htmlUrl);
            if (url.getHost() == null || url.getScheme() == null) {
                throw unauthenticated();
            }
            return InstanceIdentity.of(url);
        } catch (URISyntaxException malformed) {
            throw unauthenticated();
        }
    }

    /** 无法路由的投递，与「指向未知仓库」的投递答得一模一样。 */
    private static String required(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            throw unauthenticated();
        }
        return node.asString();
    }

    /** 与签名错误同一形态：调用方无法从差异中学到任何东西。 */
    private static ApiException unverifiable() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized",
                "The delivery could not be verified.");
    }

    /** 到这一步签名已通过校验，因此可以说明具体哪里出了问题。 */
    private static int pullRequestNumber(JsonNode payload) {
        JsonNode number = payload.path("number");
        if (!number.isIntegralNumber()) {
            throw ApiException.unprocessable("The delivery has no pull request number.");
        }
        return number.asInt();
    }

    private static ApiException unauthenticated() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "The delivery could not be verified.");
    }
}
