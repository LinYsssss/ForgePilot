package com.forgepilot.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicInteger;

import com.forgepilot.PostgresTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 通知渠道配置端点。锁三条：凭据<strong>只进不出</strong>、只有 LEADER 能碰、
 * 以及 {@link NotificationChannelType} 与 {@code ck_notification_channel_type}
 * 是同一份词表。
 */
@SpringBootTest
class NotificationChannelTest extends PostgresTestBase {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final String WEBHOOK =
            "https://oapi.dingtalk.com/robot/send?access_token=super-secret-token";
    private static final String SECRET = "SECsupersecretsigningkey";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    /**
     * <strong>凭据写进去之后再也读不出来。</strong>webhook URL 里就带着 access_token，
     * 把它回显给任何一个能打开这个页面的人，等于把发消息的权限一起给了出去。
     */
    @Test
    void neitherTheUrlNorTheSecretIsEverReturned() throws Exception {
        Scenario scenario = new Scenario();

        mockMvc.perform(configure(scenario, WEBHOOK, SECRET, true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.enabled").value(true));

        String body = mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/projects/{p}/notifications/dingtalk", scenario.projectId)
                        .with(user(scenario.leaderName)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("super-secret-token").doesNotContain(SECRET);
        // 也不该泄露密文：那同样是不必要的暴露面。
        assertThat(body).doesNotContain("encrypted");
    }

    /** 库里存的必须是密文，不是明文。这一条与上一条是两件事：一个防读取，一个防落盘。 */
    @Test
    void theCredentialsAreEncryptedAtRest() throws Exception {
        Scenario scenario = new Scenario();
        mockMvc.perform(configure(scenario, WEBHOOK, SECRET, true)).andExpect(status().isOk());

        String stored = jdbc.queryForObject(
                "select encrypted_webhook_url || '|' || encrypted_secret "
                        + "from project_notification_channel where project_id = ?",
                String.class, scenario.projectId);

        assertThat(stored).doesNotContain("super-secret-token").doesNotContain(SECRET);
    }

    /**
     * 不填密钥也能配成，且界面能看出它<strong>没加签</strong>。
     *
     * <p>钉钉的安全设置只在创建机器人时可选，已存在的机器人在很多客户端里改不了。
     * 强制要求加签换不来更安全的部署，只会换来无法部署——所以这一档必须走得通，
     * 同时必须被如实标出来，而不是悄悄降级。
     */
    @Test
    void aChannelWithoutASecretIsAllowedAndReportsItself() throws Exception {
        Scenario scenario = new Scenario();

        mockMvc.perform(configure(scenario, WEBHOOK, "", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.signed").value(false));

        assertThat(jdbc.queryForObject("select encrypted_secret from project_notification_channel "
                + "where project_id = ?", String.class, scenario.projectId))
                .as("「没有密钥」存成 NULL，不是空串——两者不是一回事").isNull();
    }

    /** 配了密钥的渠道如实报告自己加了签。这一条与上一条互为对照，缺一个就只证明了一半。 */
    @Test
    void aChannelWithASecretReportsThatItIsSigned() throws Exception {
        Scenario scenario = new Scenario();

        mockMvc.perform(configure(scenario, WEBHOOK, SECRET, true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signed").value(true));
    }

    /** 非钉钉地址被拒，且拒绝发生在落盘之前。 */
    @Test
    void aNonDingTalkUrlIsRefusedBeforeAnythingIsStored() throws Exception {
        Scenario scenario = new Scenario();

        mockMvc.perform(configure(scenario, "https://oapi.dingtalk.com.evil.com/robot/send",
                        SECRET, true))
                .andExpect(status().isUnprocessableEntity());

        assertThat(jdbc.queryForObject("select count(*) from project_notification_channel "
                + "where project_id = ?", Integer.class, scenario.projectId)).isZero();
    }

    /** 配置通知与配置仓库凭据是同一件事、同一个门槛：DEVELOPER 不得触碰。 */
    @Test
    void onlyALeaderMayConfigureTheChannel() throws Exception {
        Scenario scenario = new Scenario();

        mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/projects/{p}/notifications/dingtalk", scenario.projectId)
                        .with(user(scenario.developerName)))
                .andExpect(status().isForbidden());
        mockMvc.perform(MockMvcRequestBuilders
                        .put("/api/projects/{p}/notifications/dingtalk", scenario.projectId)
                        .with(user(scenario.developerName)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(WEBHOOK, SECRET, true)))
                .andExpect(status().isForbidden());
    }

    /**
     * 枚举与 CHECK 是同一份词表的两处表达。这条测试走遍枚举，因此新增一个值却忘了改
     * 那条 CHECK 时，会在这里变红，而不是在生产的某一次插入上。
     */
    @Test
    void everyChannelTypeIsAcceptedByTheCheckConstraint() {
        Scenario scenario = new Scenario();
        for (NotificationChannelType type : NotificationChannelType.values()) {
            jdbc.update("insert into project_notification_channel (project_id, channel, "
                    + "encrypted_webhook_url, encrypted_secret) values (?, ?, 'x', 'y')",
                    scenario.projectId, type.name());
        }
        assertThat(jdbc.queryForObject("select count(*) from project_notification_channel "
                + "where project_id = ?", Integer.class, scenario.projectId))
                .isEqualTo(NotificationChannelType.values().length);
    }

    // ----------------------------------------------------------------- helpers

    private static String payload(String url, String secret, boolean enabled) {
        return "{\"webhookUrl\":\"%s\",\"secret\":\"%s\",\"enabled\":%s}"
                .formatted(url, secret, enabled);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder configure(
            Scenario scenario, String url, String secret, boolean enabled) {
        return MockMvcRequestBuilders
                .put("/api/projects/{p}/notifications/dingtalk", scenario.projectId)
                .with(user(scenario.leaderName)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload(url, secret, enabled));
    }

    private long account(String role) {
        return jdbc.queryForObject("insert into user_account (username, display_name, password_hash) "
                + "values (?, 'Test User', 'bcrypt-placeholder') returning id", Long.class,
                "notify-" + role + "-" + SEQUENCE.incrementAndGet());
    }

    private String usernameOf(long userId) {
        return jdbc.queryForObject("select username from user_account where id = ?", String.class,
                userId);
    }

    /** 一个项目，一个 LEADER 和一个 DEVELOPER。 */
    private final class Scenario {

        private final long leader = account("leader");
        private final long developer = account("developer");
        private final String leaderName = usernameOf(leader);
        private final String developerName = usernameOf(developer);
        private final long projectId;

        private Scenario() {
            projectId = jdbc.queryForObject("insert into project (name, created_by, status) "
                    + "values (?, ?, 'ACTIVE') returning id", Long.class,
                    "notify-" + SEQUENCE.incrementAndGet(), leader);
            member(leader, "LEADER");
            member(developer, "DEVELOPER");
        }

        private void member(long userId, String role) {
            jdbc.update("with m as (insert into project_member (project_id, user_id) values (?, ?) "
                    + "returning project_id, user_id) insert into project_member_role "
                    + "(project_id, user_id, role) select project_id, user_id, ? from m",
                    projectId, userId, role);
        }
    }
}
