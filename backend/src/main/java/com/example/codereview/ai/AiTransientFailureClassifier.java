package com.example.codereview.ai;

import com.example.codereview.common.api.ErrorCode;
import com.example.codereview.common.exception.AiCallTransientException;
import com.example.codereview.common.exception.BusinessException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RetriableException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Objects;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * AI 提供方失败的「瞬时 vs 永久」唯一决策表(批C Stage 2 收敛,≥3 处准入线):
 * HTTP 429 / 5xx、超时与连接层失败 → {@link AiCallTransientException}(resilience4j 按该类型
 * 重试/熔断);其余(4xx、鉴权、标记不可重试、无法识别)→ {@link BusinessException} 不重试。
 *
 * <p>同一决策表带两个入口,对应两个异常族各自的**分发语义**,不做同质化:
 * <ul>
 *   <li>{@link #classify(RuntimeException)}(实例):LangChain4j SDK 族——SDK 把网络原因层层
 *       包装,须沿因果链遍历;两个客户端仅差消息前缀、永久错误码与兜底命名(见
 *       {@link UnrecognizedNaming}),以参数保留。</li>
 *   <li>{@link #classifyRestClientFailure}(静态):Spring RestClient 族——按**顶层**异常类型
 *       分发(原 catch 阶梯语义)。改成链遍历会让「包着 429 的未知异常」从永久变瞬时,
 *       反向同理,均属行为变更,故两形态并存。</li>
 * </ul>
 *
 * <p>永久分支保留 {@code BusinessException(int, String)} 传统构造:6003/6004 经
 * {@code resolveHttpStatus} 落 HTTP 400,换成 ErrorCode 枚举构造会静默变成 503——
 * error-handling.md 明文禁止在重构里「顺手规整」legacy 码与状态的对应。
 *
 * <p>三处调用点横跨 ai 与 ai.langchain4j 两个 Java 包,类无法 package-private;放 ai 顶层包
 * 依据 r4 依赖方向:分类逻辑的词汇是提供方异常分类学(langchain4j/Spring Web),common
 * 不得反向依赖领域知识,仅共享纯标记异常 {@link AiCallTransientException}(FQN 被
 * application.yml 的 resilience4j 配置钉死,不可再动)。
 */
public final class AiTransientFailureClassifier {

    /**
     * 兜底(因果链走完无一命中)时,「invalid response」消息里点名哪一层异常。
     * 两个既有实现在此处历史行为不同,写实保留而非统一。
     */
    public enum UnrecognizedNaming {
        /** 点名链上最深一层(LangChain4jAgentModelClient 的既有行为)。 */
        DEEPEST_CAUSE,
        /** 点名抛出的顶层异常(LangChain4jEmbeddingClient 的既有行为)。 */
        THROWN_FAILURE
    }

    private final String messagePrefix;
    private final int permanentErrorCode;
    private final UnrecognizedNaming unrecognizedNaming;

    private AiTransientFailureClassifier(
            String messagePrefix,
            int permanentErrorCode,
            UnrecognizedNaming unrecognizedNaming
    ) {
        this.messagePrefix = Objects.requireNonNull(messagePrefix, "messagePrefix");
        this.permanentErrorCode = permanentErrorCode;
        this.unrecognizedNaming = Objects.requireNonNull(unrecognizedNaming, "unrecognizedNaming");
    }

    /** LangChain4j SDK 族入口的工厂:消息前缀、永久错误码与兜底命名按调用点参数化。 */
    public static AiTransientFailureClassifier causalChain(
            String messagePrefix,
            int permanentErrorCode,
            UnrecognizedNaming unrecognizedNaming
    ) {
        return new AiTransientFailureClassifier(messagePrefix, permanentErrorCode, unrecognizedNaming);
    }

    /** 沿因果链遍历分类(LangChain4j SDK + JDK 网络异常族)。逻辑自两个 classify 逐字收敛。 */
    public RuntimeException classify(RuntimeException failure) {
        Throwable current = failure;
        Throwable deepest = failure;
        while (current != null) {
            deepest = current;
            if (current instanceof RetriableException
                    || current instanceof HttpTimeoutException
                    || current instanceof SocketTimeoutException
                    || current instanceof ConnectException) {
                return transientFailure(current.getClass().getSimpleName(), failure);
            }
            if (current instanceof HttpException http) {
                if (isTransientHttpStatus(http.statusCode())) {
                    return transientFailure("HTTP " + http.statusCode(), failure);
                }
                return permanent(current);
            }
            if (current instanceof NonRetriableException) {
                return permanent(current);
            }
            current = current.getCause();
        }
        Throwable named = unrecognizedNaming == UnrecognizedNaming.DEEPEST_CAUSE ? deepest : failure;
        return new BusinessException(
                permanentErrorCode,
                messagePrefix + " returned an invalid response ("
                        + named.getClass().getSimpleName() + ")"
        );
    }

    /**
     * Spring RestClient 族入口:按顶层异常类型分发(保留原 catch 阶梯语义与逐条消息)。
     * 调用方先自行放行 {@link BusinessException},再把其余异常连同 describeFailure 结果交来。
     *
     * <p>写实保留的两处口径差:4xx 面上仅字面 429 视为瞬时(不复用
     * {@link #isTransientHttpStatus},手工构造的 5xx HttpClientErrorException 维持既有的
     * 永久分支走向);响应体提取阶段的读超时包装为 RestClientException,落兜底永久分支
     * (特征测试 AiReviewFailureClassificationCharacterizationTest 钉死)。
     */
    // P2 起 requirement 包的体检客户端也走此入口,包私有升 public(语义不变)。
    public static RuntimeException classifyRestClientFailure(Exception failure, String describedFailure) {
        if (failure instanceof HttpClientErrorException client) {
            // 4xx 是确定性的客户端问题(错 key/错请求),重试无益;唯 429 是瞬时限流。
            if (client.getStatusCode().value() == 429) {
                return new AiCallTransientException("AI 调用被限流(429): " + describedFailure, failure);
            }
            return new BusinessException(ErrorCode.AI_CALL_FAILED, "AI 调用失败: " + describedFailure);
        }
        if (failure instanceof HttpServerErrorException) {
            return new AiCallTransientException("AI 服务端错误(5xx): " + describedFailure, failure);
        }
        if (failure instanceof ResourceAccessException) {
            // 连接超时或连接被重置——瞬时,值得重试。
            return new AiCallTransientException("AI 调用网络异常: " + describedFailure, failure);
        }
        return new BusinessException(ErrorCode.AI_CALL_FAILED, "AI 调用失败: " + describedFailure);
    }

    /** 决策表的 HTTP 状态行:429(限流)与 5xx(服务端)为瞬时。 */
    private static boolean isTransientHttpStatus(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    private AiCallTransientException transientFailure(String detail, RuntimeException failure) {
        return new AiCallTransientException(
                messagePrefix + " call failed transiently (" + detail + ")",
                failure
        );
    }

    private BusinessException permanent(Throwable matched) {
        return new BusinessException(
                permanentErrorCode,
                messagePrefix + " call failed permanently ("
                        + matched.getClass().getSimpleName() + ")"
        );
    }
}
