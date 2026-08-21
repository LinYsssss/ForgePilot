package com.forgepilot.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.forgepilot.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Guards what {@code scripts/phase1-compose-smoke.sh} asserts about the default
 * profile, over a real servlet container.
 *
 * <p>MockMvc cannot prove this: a 404 only becomes visible after the container
 * re-dispatches to {@code /error}, and that dispatch is what the security chain
 * has to let through. Permitting {@code /actuator/**} without it turns the 404
 * this test demands into a 401 and breaks the smoke script instead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActuatorExposureTest extends PostgresTestBase {

    @Value("${local.server.port}")
    private int port;

    @Test
    void onlyHealthIsReachableAndAnUnexposedEndpointIsNotFoundRatherThanUnauthorized() throws Exception {
        assertThat(get("/actuator/health")).satisfies(response -> {
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"status\":\"UP\"");
        });

        assertThat(get("/actuator/metrics").statusCode()).isEqualTo(404);

        // Everything outside the two permitted paths stays behind authentication.
        assertThat(get("/api/auth/me").statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + this.port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
