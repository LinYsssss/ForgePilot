package com.forgepilot.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import com.forgepilot.PostgresTestBase;
import com.forgepilot.ai.AiGateway;
import com.forgepilot.common.ApiException;
import com.forgepilot.project.ProjectRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Ingestion, D005's copy-on-promote rule, and the role and project boundaries. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class KnowledgeServiceTest extends PostgresTestBase {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired
    private KnowledgeService knowledge;

    @Autowired
    private KnowledgeDocumentRepository documents;

    @Autowired
    private KnowledgeChunkRepository chunks;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * The gateway's own HTTP behaviour — timeout, the single retry, malformed
     * answers — is proved against a real socket in AiGatewayTest. What belongs
     * here is knowledge's orchestration, so the provider is replaced rather than
     * re-tested at this level.
     */
    @MockitoBean
    private AiGateway ai;

    @BeforeEach
    void embeddingsAreFourDimensional() {
        when(ai.embed(anyList(), any(), any())).thenAnswer(call -> {
            List<String> texts = call.getArgument(0);
            return texts.stream().map(text -> new float[] {0.1f, 0.2f, 0.3f, 0.4f}).toList();
        });
    }

    @Test
    void ingestingSplitsTheTextEmbedsEveryPieceAndOnlyThenBecomesReady() {
        Fixture fixture = new Fixture();
        String text = "第一段。\n".repeat(400);

        long document = knowledge.createProjectKnowledge(fixture.project, fixture.leader, "手册.md", text);

        assertThat(documents.findByProjectIdAndId(fixture.project, document))
                .get().extracting(KnowledgeDocument::getStatus)
                .isEqualTo(KnowledgeStatus.READY);

        List<KnowledgeChunk> stored =
                chunks.findByProjectIdAndDocumentIdOrderBySeqAsc(fixture.project, document);
        assertThat(stored).hasSizeGreaterThan(1)
                .allSatisfy(chunk -> assertThat(chunk.getContent()).isNotBlank());

        // READY has to mean retrievable: every chunk carries a vector, and the
        // document would otherwise return nothing while looking like an empty corpus.
        assertThat(jdbc.queryForObject(
                "select count(*) from knowledge_chunk where document_id = ? and embedding is null",
                Integer.class, document)).isZero();
        assertThat(knowledge.search(fixture.project, fixture.leader, null,
                new float[] {0.1f, 0.2f, 0.3f, 0.4f}, 10)).hasSize(stored.size());
    }

    @Test
    void promotingCopiesTheDocumentAndLeavesTheOriginalAttachmentAlone() {
        Fixture fixture = new Fixture();
        long attachment = knowledge.createRequirementAttachment(
                fixture.project, fixture.leader, fixture.requirement, "附件.md", "附件正文");

        long promoted = knowledge.promoteToProjectKnowledge(fixture.project, fixture.leader, attachment);

        assertThat(promoted).isNotEqualTo(attachment);
        KnowledgeDocument original = documents.findByProjectIdAndId(fixture.project, attachment).orElseThrow();
        KnowledgeDocument copy = documents.findByProjectIdAndId(fixture.project, promoted).orElseThrow();

        // D005: the attachment keeps its ownership, so nothing that already
        // referenced it changes meaning underneath.
        assertThat(original.getSourceType()).isEqualTo(KnowledgeSourceType.REQUIREMENT_ATTACHMENT);
        assertThat(original.getSourceRequirementId()).isEqualTo(fixture.requirement);
        assertThat(copy.getSourceType()).isEqualTo(KnowledgeSourceType.PROJECT_KNOWLEDGE);
        assertThat(copy.getSourceRequirementId()).isNull();
        assertThat(copy.getText()).isEqualTo(original.getText());

        assertThat(statusOf(() -> knowledge.promoteToProjectKnowledge(
                fixture.project, fixture.leader, promoted)))
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void onlyALeaderMayIngestAndAnotherProjectSeesNothing() {
        Fixture fixture = new Fixture();
        Fixture other = new Fixture();
        long developer = fixture.member(ProjectRole.DEVELOPER);

        assertThat(statusOf(() -> knowledge.createProjectKnowledge(
                fixture.project, developer, "x.md", "正文")))
                .isEqualTo(HttpStatus.FORBIDDEN);

        long document = knowledge.createProjectKnowledge(fixture.project, fixture.leader, "x.md", "正文");

        // A non-member gets the same answer as for a document that never existed.
        assertThat(statusOf(() -> knowledge.promoteToProjectKnowledge(
                other.project, other.leader, document)))
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statusOf(() -> knowledge.promoteToProjectKnowledge(
                fixture.project, other.leader, document)))
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aPublicDocumentCannotBeAttachedToARequirement() {
        Fixture fixture = new Fixture();
        long publicDocument = knowledge.createProjectKnowledge(
                fixture.project, fixture.leader, "公共.md", "正文");

        // The database refuses it; nothing in the service re-checks ownership.
        assertThatThrownBy(() -> jdbc.update(
                "insert into requirement_attachment (project_id, requirement_id, document_id) "
                        + "values (?, ?, ?)", fixture.project, fixture.requirement, publicDocument))
                .hasMessageContaining("fk_requirement_attachment_document_scope");
    }

    /**
     * 删除是硬删，且级联是应用层显式做的，不是数据库 {@code ON DELETE}——全库只有
     * {@code pull_request.author_user_id} 一条（D010）。这里同时钉住 AC4：检索只读
     * {@code knowledge_chunk}，所以 chunk 被删掉就等于该文档此后不可能被召回。
     * 那一条是本任务里唯一「实现不写代码、正确性完全依赖别处」的验收点，必须直接证明。
     */
    @Test
    void deletingADocumentTakesItsChunksAndRemovesItFromRetrieval() {
        Fixture fixture = new Fixture();
        float[] query = {0.1f, 0.2f, 0.3f, 0.4f};
        long kept = knowledge.createProjectKnowledge(fixture.project, fixture.leader, "留下.md", "留下的正文");
        long doomed = knowledge.createProjectKnowledge(fixture.project, fixture.leader, "删掉.md", "删掉的正文");
        assertThat(knowledge.search(fixture.project, fixture.leader, null, query, 10)).hasSize(2);

        knowledge.deleteProjectKnowledge(fixture.project, fixture.leader, doomed);

        assertThat(documents.findByProjectIdAndId(fixture.project, doomed)).isEmpty();
        assertThat(chunks.findByProjectIdAndDocumentIdOrderBySeqAsc(fixture.project, doomed)).isEmpty();
        assertThat(knowledge.search(fixture.project, fixture.leader, null, query, 10))
                .as("AC4: 检索只读 knowledge_chunk，因此删掉 chunk 就是删掉可召回性")
                .hasSize(1);
        assertThat(documents.findByProjectIdAndId(fixture.project, kept)).isPresent();

        // 硬删之后重复删除得到 404——对硬删来说这就是明确结果（AC14）。
        assertThat(statusOf(() -> knowledge.deleteProjectKnowledge(
                fixture.project, fixture.leader, doomed)))
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject(
                "select detail from project_deletion_record where project_id = ? "
                        + "and resource_type = 'KNOWLEDGE_DOCUMENT' and resource_id = ?",
                String.class, fixture.project, doomed))
                .isEqualTo("chunks: 1");
    }

    /**
     * 附件文档被拒绝（D022）：附件关系是需求侧的事实。判定只看本表的
     * {@code source_type}，因为 {@code ck_knowledge_document_scope_matches_type} 加上
     * 附件侧 NOT NULL 的 {@code requirement_id} 已经让「公共知识永远进不了附件表」
     * 成为结构事实——这条测试同时证明那个等价关系没有被绕过。
     */
    @Test
    void anAttachmentDocumentIsRefusedRatherThanCascadingIntoTheRequirement() {
        Fixture fixture = new Fixture();
        long attachment = knowledge.createRequirementAttachment(
                fixture.project, fixture.leader, fixture.requirement, "附件.md", "附件正文");

        assertThat(statusOf(() -> knowledge.deleteProjectKnowledge(
                fixture.project, fixture.leader, attachment)))
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(documents.findByProjectIdAndId(fixture.project, attachment)).isPresent();
        assertThat(chunks.findByProjectIdAndDocumentIdOrderBySeqAsc(fixture.project, attachment))
                .isNotEmpty();

        // 提升出来的副本是公共知识，删它不碰原附件。
        long promoted = knowledge.promoteToProjectKnowledge(fixture.project, fixture.leader, attachment);
        knowledge.deleteProjectKnowledge(fixture.project, fixture.leader, promoted);
        assertThat(documents.findByProjectIdAndId(fixture.project, promoted)).isEmpty();
        assertThat(documents.findByProjectIdAndId(fixture.project, attachment)).isPresent();
    }

    @Test
    void onlyALeaderMayDeleteAndAnotherProjectCannotReachTheDocument() {
        Fixture fixture = new Fixture();
        Fixture other = new Fixture();
        long developer = fixture.member(ProjectRole.DEVELOPER);
        long document = knowledge.createProjectKnowledge(fixture.project, fixture.leader, "x.md", "正文");

        assertThat(statusOf(() -> knowledge.deleteProjectKnowledge(
                fixture.project, developer, document))).isEqualTo(HttpStatus.FORBIDDEN);
        // 跨项目与不存在同答，因此别的项目的 id 不可被探测（AC13）。
        assertThat(statusOf(() -> knowledge.deleteProjectKnowledge(
                other.project, other.leader, document))).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(documents.findByProjectIdAndId(fixture.project, document)).isPresent();
    }

    // ---------------------------------------------------------------- fixture

    private final class Fixture {

        private final long leader;
        private final long project;
        private final long requirement;

        private Fixture() {
            this.leader = account();
            this.project = jdbc.queryForObject(
                    "insert into project (name, created_by, status) values (?, ?, 'ACTIVE') returning id",
                    Long.class, "kp-" + SEQUENCE.incrementAndGet(), leader);
            jdbc.update("with member as (insert into project_member (project_id, user_id) values (?, ?) returning project_id, user_id) insert into project_member_role (project_id, user_id, role) select project_id, user_id, 'LEADER' from member",
                    project, leader);
            this.requirement = jdbc.queryForObject(
                    "insert into requirement (project_id, status) values (?, 'DRAFT') returning id",
                    Long.class, project);
        }

        private long account() {
            return jdbc.queryForObject(
                    "insert into user_account (username, display_name, password_hash) values (?, 'Test User', 'x') returning id",
                    Long.class, "ku-" + SEQUENCE.incrementAndGet());
        }

        private long member(ProjectRole role) {
            long user = account();
            jdbc.update("with member as (insert into project_member (project_id, user_id) values (?, ?) returning project_id, user_id) insert into project_member_role (project_id, user_id, role) select project_id, user_id, ? from member",
                    project, user, role.name());
            return user;
        }
    }

    private static HttpStatus statusOf(Runnable action) {
        try {
            action.run();
            return null;
        } catch (ApiException exception) {
            return exception.getStatus();
        }
    }

    @Test
    void chunkingKeepsEveryPieceWithinTheBudget() {
        List<String> pieces = KnowledgeService.split("段落\n".repeat(1_000));

        assertThat(pieces).isNotEmpty()
                .allSatisfy(piece -> assertThat(piece.length())
                        .isLessThanOrEqualTo(KnowledgeService.MAX_CHUNK_CHARS));
    }
}
