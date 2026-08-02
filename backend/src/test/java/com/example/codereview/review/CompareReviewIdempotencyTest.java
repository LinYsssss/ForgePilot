package com.example.codereview.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 对比审查的地基:同一提交、不同知识文档组合的审查任务必须能够共存,
 * 而相同组合的重复创建仍然幂等。唯一键因此从五元组扩展为含 doc_set_key 的六元组。
 */
@SpringBootTest(properties = {
        "app.security.token-secret=test-secret",
        "app.security.token-encrypt-key=test-encrypt-key",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "management.health.rabbit.enabled=false"
})
class CompareReviewIdempotencyTest {

    @Autowired
    private ReviewTaskRepository tasks;

    @Test
    void docSetKeyIsOrderAndDuplicateInsensitiveAndEmptyForNoDocs() {
        assertThat(ReviewTask.computeDocSetKey(null)).isEmpty();
        assertThat(ReviewTask.computeDocSetKey(List.of())).isEmpty();
        assertThat(ReviewTask.computeDocSetKey(List.of(3L, 1L, 2L)))
                .isEqualTo(ReviewTask.computeDocSetKey(List.of(1L, 2L, 2L, 3L)))
                .hasSize(64);
        assertThat(ReviewTask.computeDocSetKey(List.of(1L)))
                .isNotEqualTo(ReviewTask.computeDocSetKey(List.of(2L)));
    }

    @Test
    void sameCommitWithDifferentDocSetsCoexistsWhileSameSetStaysIdempotent() {
        String commit = "cmp-" + System.nanoTime();

        ReviewTask withDocs = tasks.saveAndFlush(
                new ReviewTask(90L, 9L, commit, null, "main", 1L, "diff", List.of(1L, 2L), null));
        ReviewTask withoutDocs = tasks.saveAndFlush(
                new ReviewTask(90L, 9L, commit, null, "main", 1L, "diff", List.of(), null));

        assertThat(withoutDocs.getId()).isNotEqualTo(withDocs.getId());

        // 幂等查询按文档集指纹各找各的
        assertThat(tasks.findIdempotentTask(90L, 9L, commit, "", "main", ReviewTask.computeDocSetKey(List.of(2L, 1L))))
                .hasValueSatisfying(t -> assertThat(t.getId()).isEqualTo(withDocs.getId()));
        assertThat(tasks.findIdempotentTask(90L, 9L, commit, "", "main", ""))
                .hasValueSatisfying(t -> assertThat(t.getId()).isEqualTo(withoutDocs.getId()));

        // 相同组合的第二次落库仍被唯一键拒绝(服务层由此回读已有任务)
        assertThatThrownBy(() -> tasks.saveAndFlush(
                new ReviewTask(90L, 9L, commit, null, "main", 1L, "diff", List.of(2L, 1L), null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
