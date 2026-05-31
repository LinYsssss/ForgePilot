package com.example.codereview.review;

import com.example.codereview.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewTaskStatusService {

    private final ReviewTaskRepository tasks;

    public ReviewTaskStatusService(ReviewTaskRepository tasks) {
        this.tasks = tasks;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRunning(Long taskId) {
        ReviewTask task = getTask(taskId);
        task.markRunning();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long taskId, String error) {
        ReviewTask task = getTask(taskId);
        task.markFailed(error);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDead(Long taskId, String error) {
        ReviewTask task = getTask(taskId);
        task.markDead(error);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean isCanceled(Long taskId) {
        return getTask(taskId).isCanceled();
    }

    private ReviewTask getTask(Long taskId) {
        return tasks.findById(taskId)
                .orElseThrow(() -> new BusinessException(6002, "审查任务不存在"));
    }
}
