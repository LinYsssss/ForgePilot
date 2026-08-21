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
 * GitHub's webhook endpoint.
 *
 * <p>The path carries no {@code projectId}: a delivery does not know about
 * projects, and the secret needed to verify it is a column of the row the payload
 * itself identifies. So the order is fixed — read the repository identity out of
 * the body, load the row, verify the signature over the untouched bytes, and only
 * then act. Everything that fails before the last step answers 401 with one body,
 * and nothing is written and nothing is fetched.
 *
 * <p>The body is taken as {@code byte[]} and handed to Jackson afterwards, so the
 * bytes that were authenticated are the bytes that are acted on.
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
     * 202 once the pull request is committed. There is no Review to create yet, so
     * ARCHITECTURE.md 3.1's "after the pull request and its PENDING Review are both
     * committed" degenerates to the first half.
     */
    @PostMapping(PATH)
    @ResponseStatus(HttpStatus.ACCEPTED)
    void receive(@RequestBody byte[] body,
            @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(name = "X-GitHub-Event", required = false) String event) {
        // Everything up to and including verification runs inside this guard. The
        // endpoint is public and unauthenticated, so a hostile payload must not be
        // able to raise an exception that escapes as a stack trace and an
        // off-contract body: a container node where a string belongs, or a DNS label
        // over 63 characters reaching IDN.toASCII, both did before this.
        // Every pre-verification failure answers exactly like a bad signature, which
        // also keeps §3.4's indistinguishability intact.
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
            // A verified delivery this deployment has nothing to do with — the ping
            // GitHub sends when the hook is created, for instance.
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
     * The instance is taken from the repository's user facing URL, normalized the
     * same way {@code instance_identity} was when the repository was registered.
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

    /** A delivery that cannot be routed is answered exactly like one for an unknown repository. */
    private static String required(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            throw unauthenticated();
        }
        return node.asString();
    }

    /** Same shape as a bad signature: the caller learns nothing from the difference. */
    private static ApiException unverifiable() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized",
                "The delivery could not be verified.");
    }

    /** Verified by now, so this may say what is wrong. */
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
