package com.forgepilot.project;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 在删除 {@code project_member} 行的那个事务**内部**、且在删除**之前**发布。
 *
 * <p>存在的理由是依赖方向。移除一个成员必须撤销 {@code requirement.assignee_id}、
 * {@code finding.assignee_id} 与 {@code project_member_scm_binding}，而
 * ARCHITECTURE.md 1.3 规定 {@code project} 只能依赖 {@code common}——它够不到那三个
 * 模块，1.4 第 4 条还禁止跨 feature 注入对方 {@code *Repository}。于是方向被反转：
 * 类型定义在 {@code project}，由 {@code requirement} / {@code review} / {@code scm}
 * 各自 import 并监听。这与 {@code scm} 发布 {@link com.forgepilot.scm.PullRequestChanged}
 * 而 {@code review} 监听是同一个形状。
 *
 * <p>同步的 {@code @EventListener} 会加入该事务，因此监听器一旦失败，成员移除就
 * 随之回滚。{@code @TransactionalEventListener} 在这里是错的工具，且被禁止使用——
 * 它的默认阶段在提交之后运行，那时成员行已经删了，撤销再失败也回滚不了。
 *
 * <p><strong>漏掉一个监听器不会静默通过。</strong>那三处引用的外键都没有
 * {@code ON DELETE}，所以任何未被撤销的引用都会让删除被数据库以 23503 拒绝。
 * 外键本身就是「每一处引用都真的撤销了」的证明，应用层不必再自己数一遍。
 *
 * <p>这是一个**收集型**事件：监听器把自己撤销了多少条回填进来，发布方据此写留痕
 * 的 detail。刻意不用 {@code record}——它带一个会被写入的累加器，用 record 会让
 * 「看起来不可变、实际被改」这件事更难看清。可变在这里是安全的：Spring 的同步发布
 * 是单线程、同事务，发布方在所有监听器返回之后才继续。
 */
public final class ProjectMemberRemoving {

    private final long projectId;
    private final long userId;
    private final Map<String, Integer> revoked = new LinkedHashMap<>();

    public ProjectMemberRemoving(long projectId, long userId) {
        this.projectId = projectId;
        this.userId = userId;
    }

    public long projectId() {
        return projectId;
    }

    public long userId() {
        return userId;
    }

    /** 记下某一类引用被撤销的条数。计数为 0 也要记：「没有需求指派」是一条事实。 */
    public void revoked(String reference, int count) {
        revoked.merge(reference, count, Integer::sum);
    }

    /** 按监听器回填的顺序渲染留痕摘要。 */
    public String summary() {
        return revoked.entrySet().stream()
                .map(entry -> entry.getKey() + ": " + entry.getValue())
                .collect(Collectors.joining("; "));
    }
}
