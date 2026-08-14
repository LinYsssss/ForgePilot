package com.example.codereview.finding;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewFeedbackRepository extends JpaRepository<ReviewFeedback, Long> {

    /** 误报/确认的幂等键查询:同 reporter+finding+type 至多一条(有唯一索引背书)。 */
    Optional<ReviewFeedback> findByReporterIdAndFindingIdAndType(Long reporterId, Long findingId, ReviewFeedbackType type);

    /**
     * MISS_REPORT 的候选集查询。幂等匹配(path+line)刻意放在内存做:line 可空,
     * Spring Data 派生查询对 null 参数生成 {@code = null},SQL 语义下永不命中——
     * 这里取回同 run 同 reporter 的少量漏报记录后用 Objects.equals 判定。
     */
    List<ReviewFeedback> findByAgentRunIdAndReporterIdAndType(Long agentRunId, Long reporterId, ReviewFeedbackType type);

    /** 导出全量;id 升序保证多次导出行序稳定,便于 r8 侧 diff 增量。 */
    List<ReviewFeedback> findAllByOrderByIdAsc();

    /** 导出增量:createdAt 在 upsert 时会刷新,被更新过的旧记录也会落进增量窗口。 */
    List<ReviewFeedback> findByCreatedAtGreaterThanEqualOrderByIdAsc(Instant since);
}
