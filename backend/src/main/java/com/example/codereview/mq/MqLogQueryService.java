package com.example.codereview.mq;

import com.example.codereview.common.api.ErrorCode;
import com.example.codereview.common.api.PageResponse;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.common.security.ProjectAuthorization;
import com.example.codereview.mq.MqLogDtos.MqLogResponse;
import com.example.codereview.review.ReviewTask;
import com.example.codereview.review.ReviewTaskRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Object-level authorization for MQ task logs.
 *
 * <p>The controller used to query the repository directly by {@code taskId}, so being authenticated
 * was enough to read any project's logs by guessing identifiers — the logs carry message payloads
 * and error text, so that is a real disclosure and not just noise.
 *
 * <p>Authorization walks the resource chain rather than trusting the parameter:
 * {@code taskId -> ReviewTask -> projectId -> owner}.
 */
@Service
public class MqLogQueryService {

    private final MqTaskLogRepository logs;
    private final ReviewTaskRepository tasks;
    private final ProjectAuthorization projectAuthorization;

    public MqLogQueryService(
            MqTaskLogRepository logs, ReviewTaskRepository tasks, ProjectAuthorization projectAuthorization) {
        this.logs = logs;
        this.tasks = tasks;
        this.projectAuthorization = projectAuthorization;
    }

    @Transactional(readOnly = true)
    public PageResponse<MqLogResponse> list(Long taskId, Long userId, Integer page, Integer size) {
        if (taskId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "taskId 不能为空");
        }
        ReviewTask task = tasks.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REVIEW_TASK_NOT_FOUND));
        projectAuthorization.requireRead(task.getProjectId(), userId);

        PageRequest pageRequest =
                PageRequest.of(PageResponse.sanitizePage(page), PageResponse.sanitizeSize(size));
        return PageResponse.from(
                logs.findByTaskIdOrderByCreatedAtDesc(taskId, pageRequest), MqLogResponse::from);
    }
}
