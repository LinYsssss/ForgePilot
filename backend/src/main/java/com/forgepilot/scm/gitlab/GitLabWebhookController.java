package com.forgepilot.scm.gitlab;

import java.net.URI;
import java.net.URISyntaxException;

import com.forgepilot.common.ApiException;
import com.forgepilot.scm.InstanceIdentity;
import com.forgepilot.scm.PullRequestSyncService;
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

/** GitLab webhook 接收端：先对原始字节做认证，再拉取权威的 MR 状态。 */
@RestController
class GitLabWebhookController {

    static final String PATH = "/api/scm/gitlab/webhook";
    private static final String MERGE_REQUEST_EVENT = "Merge Request Hook";

    private final PullRequestSyncService sync;
    private final GitLabClient gitlab;
    private final ObjectMapper json;

    GitLabWebhookController(PullRequestSyncService sync, GitLabClient gitlab, ObjectMapper json) {
        this.sync = sync;
        this.gitlab = gitlab;
        this.json = json;
    }

    @PostMapping(PATH)
    @ResponseStatus(HttpStatus.ACCEPTED)
    void receive(@RequestBody byte[] body,
            @RequestHeader(name = "X-Gitlab-Event", required = false) String event,
            @RequestHeader(name = "X-Gitlab-Token", required = false) String token,
            @RequestHeader(name = "webhook-signature", required = false) String signature,
            @RequestHeader(name = "webhook-id", required = false) String messageId,
            @RequestHeader(name = "webhook-timestamp", required = false) String timestamp) {
        ScmRepository repository;
        JsonNode payload;
        try {
            payload = parse(body);
            JsonNode project = payload.path("project");
            repository = sync.authenticateGitLab(
                    instanceIdentityOf(requiredText(project, "web_url")),
                    requiredValue(project, "id"), body, signature, messageId, timestamp, token);
        } catch (ApiException rejected) {
            throw rejected;
        } catch (RuntimeException malformed) {
            throw unauthenticated();
        }

        if (!MERGE_REQUEST_EVENT.equals(event)) {
            return;
        }
        sync.apply(repository, gitlab.fetch(repository, mergeRequestIid(payload)));
    }

    private JsonNode parse(byte[] body) {
        try {
            return json.readTree(body);
        } catch (JacksonException unparseable) {
            throw unauthenticated();
        }
    }

    private static String instanceIdentityOf(String webUrl) {
        try {
            URI uri = new URI(webUrl);
            if (uri.getHost() == null || uri.getScheme() == null) {
                throw unauthenticated();
            }
            return InstanceIdentity.of(uri);
        } catch (URISyntaxException malformed) {
            throw unauthenticated();
        }
    }

    private static String requiredText(JsonNode parent, String field) {
        JsonNode node = parent.path(field);
        if (!node.isTextual() || node.asString().isBlank()) {
            throw unauthenticated();
        }
        return node.asString();
    }

    private static String requiredValue(JsonNode parent, String field) {
        JsonNode node = parent.path(field);
        if (!node.isValueNode() || node.isNull() || node.asString().isBlank()) {
            throw unauthenticated();
        }
        return node.asString();
    }

    private static int mergeRequestIid(JsonNode payload) {
        JsonNode iid = payload.path("object_attributes").path("iid");
        if (!iid.isIntegralNumber() || !iid.canConvertToInt()) {
            throw ApiException.unprocessable("The delivery has no merge request IID.");
        }
        return iid.asInt();
    }

    private static ApiException unauthenticated() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized",
                "The delivery could not be verified.");
    }
}
