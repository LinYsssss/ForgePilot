package com.example.codereview.agent.orchestration;

import com.example.codereview.context.ReviewRetrievalQuery;
import java.util.List;

/**
 * Agent 侧的证据检索端口:RETRIEVING_CONTEXT 步骤按项目/HEAD 限定的类型化范围取回引用证据。
 *
 * <p>与 {@link com.example.codereview.agent.model.AgentModelClient} 同一范式——端口定义在消费域
 * (agent),LangChain4j 适配器在 ai 侧实现并把框架 Query 的组装(原静态 {@code toQuery})与
 * Content→Evidence 的映射收进自己内部;步骤执行器由此不再知道任何检索框架细节。
 */
public interface AgentContextRetriever {

    List<AgentRetrievedContextCheckpoint.Evidence> retrieve(ReviewRetrievalQuery scope);
}
