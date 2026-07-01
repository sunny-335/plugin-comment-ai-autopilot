package top.nxxy335.commentaiautopilot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Reply;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 评论前置过滤服务：在 AI 回复之前检测评论合规性。
 *
 * 检测维度：
 * 1. 敏感词/辱骂/广告/恶意攻击 — 通过 AI 分类判断
 * 2. 自动处置 — 违规评论跳过 AI 回复，可选将评论设为待审核状态
 */
@Component
@Slf4j
public class CommentPreFilterService {

    private final ReactiveExtensionClient client;
    private final ObjectMapper objectMapper;
    private final AiFoundationClient aiFoundationClient;

    private static final String CONFIG_MAP_NAME = "comment-ai-autopilot-configmap";

    private static final String CLEAN = "正常";
    private static final String SPAM = "广告";
    private static final String ABUSE = "辱骂攻击";
    private static final String SENSITIVE = "敏感内容";
    private static final String MEANINGLESS = "无意义";
    private static final List<String> CLASSIFY_CHOICES = List.of(CLEAN, SPAM, ABUSE, SENSITIVE, MEANINGLESS);

    private static final Map<String, String> CATEGORY_DESCRIPTIONS = Map.of(
        SPAM, "检测到推广链接、产品推销或引流信息",
        ABUSE, "检测到辱骂、人身攻击、恶意挑衅或歧视性言论",
        SENSITIVE, "检测到政治敏感、违法违规或色情暴力内容",
        MEANINGLESS, "检测到纯乱码或无意义字符堆砌"
    );

    private static final String CLASSIFY_SYSTEM_PROMPT = """
            你是评论内容合规检测员。请判断以下评论属于哪个类别：

            类别定义：
            - 正常：正常的评论、提问、讨论、赞美、闲聊等，即使与文章主题无关也算正常
            - 广告：包含推广链接、产品推销、引流信息等
            - 辱骂攻击：包含辱骂、人身攻击、恶意挑衅、歧视性言论等
            - 敏感内容：涉及政治敏感、违法违规、色情暴力等
            - 无意义：纯乱码、无意义字符堆砌（如随机符号、键盘乱敲）

            ═══════════════════════════════════════
            核心判断原则（必须严格遵守）：
            ═══════════════════════════════════════

            【原则一：上下文优先】
            绝对禁止仅凭单个词汇进行机械拦截。必须结合整句话的语境、语气和前后文逻辑进行综合判断。一个词是否违规，取决于它在句子中的功能，而非词汇本身。

            【原则二：口语化宽容】
            中文互联网存在大量口语化简写、谐音和省略表达。如果某个词在特定语境下明显是中性词或亲属称谓的口语化表达，且整句无攻击性、无恶意，必须判定为"正常"。
            常见口语化中性用法示例：
            - "他妈" → 可能是"他妈妈"的简称，如"小轩是他妈的朋友"=小轩是他妈妈的朋友 → 正常
            - "你妹" → 可能是"你妹妹"的简称，如"你妹在哪上学"=你妹妹在哪上学 → 正常
            - "卧槽" → 可能是语气词表示惊讶，如"卧槽这也太强了"=哇塞这也太厉害了 → 正常
            - "牛逼" → 口语化赞美，如"这文章写得牛逼" → 正常
            - "靠" → 语气词表示无奈或惊讶，如"靠又忘了" → 正常

            【原则三：恶意导向判定】
            只有当词汇被明确用作辱骂、人身攻击、引战或带有较强负面情绪时，才判定为"辱骂攻击"。
            恶意用法示例（这些才应判为"辱骂攻击"）：
            - "你他妈的" → 直接对他人进行辱骂 → 辱骂攻击
            - "你妹的" → 带有攻击性的语气词 → 辱骂攻击
            - "傻逼" → 直接辱骂他人 → 辱骂攻击

            【原则四：宁放勿杀】
            当你无法确定评论是否违规时，应判定为"正常"而非"辱骂攻击"。误杀正常评论比漏判违规评论的负面影响更大。

            【原则五：闲聊不算无意义】
            与文章主题无关的闲聊、灌水、打招呼等属于"正常"，不要误判为"无意义"。

            只返回类别名称，不要返回其他内容。""";

    public CommentPreFilterService(ReactiveExtensionClient client,
                                    ObjectMapper objectMapper,
                                    AiFoundationClient aiFoundationClient) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.aiFoundationClient = aiFoundationClient;
    }

    /**
     * 检测评论是否合规。
     *
     * @param commentContent 评论内容（纯文本）
     * @param modelName       AI 模型名称
     * @return 检测结果
     */
    public Mono<PreFilterResult> check(String commentContent, String modelName) {
        return loadConfig().flatMap(config -> {
            if (!config.enabled()) {
                log.info("[PreFilter] Pre-filter is DISABLED, allowing all comments");
                return Mono.just(new PreFilterResult(true, CLEAN, "前置过滤未启用"));
            }

            // 剥离 HTML 标签，获取纯文本
            String plainText = stripHtml(commentContent);
            String truncated = truncate(plainText, 500);
            String userPrompt = "评论内容：\n" + truncated;
            log.info("[PreFilter] Checking comment (enabled=true): {}", truncated.substring(0, Math.min(50, truncated.length())));

            return aiFoundationClient.classify(CLASSIFY_SYSTEM_PROMPT, userPrompt, CLASSIFY_CHOICES, modelName)
                .doOnNext(result -> log.info("[PreFilter] AI classify returned: '{}'", result))
                .map(result -> {
                    if (CLEAN.equals(result)) {
                        log.info("[PreFilter] Comment passed: category={}", result);
                        return new PreFilterResult(true, CLEAN, "评论合规");
                    }
                    // 空结果视为分类失败
                    if (result == null || result.isBlank()) {
                        log.warn("[PreFilter] AI classify returned empty/blank result, blocking for safety");
                        return new PreFilterResult(false, MEANINGLESS, "AI分类返回空结果，安全拦截");
                    }
                    String desc = CATEGORY_DESCRIPTIONS.getOrDefault(result, "检测到违规内容");
                    String snippet = truncated.substring(0, Math.min(50, truncated.length()));
                    String reason = desc + " — 「" + snippet + "」";
                    log.warn("[PreFilter] Comment BLOCKED: category={}, content={}", result, snippet);
                    return new PreFilterResult(false, result, reason);
                })
                // 分类失败时拦截评论（安全优先），而非放行
                .defaultIfEmpty(new PreFilterResult(false, MEANINGLESS, "AI分类服务不可用，安全拦截"))
                .onErrorResume(e -> {
                    log.warn("[PreFilter] Detection error, BLOCKING comment for safety: {}", e.getMessage(), e);
                    return Mono.just(new PreFilterResult(false, MEANINGLESS, "AI分类服务异常，安全拦截"));
                });
        });
    }

    /**
     * 对违规评论执行自动处置：将评论或回复设为待审核状态。
     *
     * <p>当 replyName 不为空时（AI 对话场景或回复触发），取消通过的是包含违规内容的 Reply；
     * 否则取消通过的是顶层 Comment。这样可避免误伤父级 Comment 中正常的内容。
     *
     * @param commentName 评论的 metadata.name
     * @param replyName   回复的 metadata.name（可为 null，表示顶层评论）
     * @return Mono<Void>
     */
    public Mono<Void> penalize(String commentName, String replyName) {
        return loadConfig().flatMap(config -> {
            if (!config.pendingOnViolation()) {
                return Mono.empty();
            }
            // 优先处理 Reply：AI 对话场景下违规内容来自 Reply
            if (replyName != null && !replyName.isBlank()) {
                return penalizeReply(replyName);
            }
            return penalizeComment(commentName);
        });
    }

    private Mono<Void> penalizeComment(String commentName) {
        return client.fetch(Comment.class, commentName)
            .flatMap(comment -> {
                var spec = comment.getSpec();
                if (spec == null) return Mono.<Comment>empty();
                // 只要 approved 不是 false，就强制设为 false
                // 覆盖 approved=true 和 approved=null 两种情况
                if (!Boolean.FALSE.equals(spec.getApproved())) {
                    log.info("[PreFilter] Penalizing comment {}: approved={} → false", commentName, spec.getApproved());
                    spec.setApproved(false);
                    spec.setApprovedTime(null);
                    return client.update(comment)
                        .doOnSuccess(c -> log.info("[PreFilter] Comment {} set to pending for violation", commentName));
                }
                log.debug("[PreFilter] Comment {} already unapproved, skip penalize", commentName);
                return Mono.<Comment>empty();
            })
            .retryWhen(Retry.backoff(3, Duration.ofMillis(100))
                .filter(OptimisticLockingFailureException.class::isInstance)
                .doBeforeRetry(sig -> log.debug("[PreFilter] Retrying penalizeComment {} (attempt {})", commentName, sig.totalRetries() + 1)))
            .onErrorResume(e -> {
                log.warn("[PreFilter] Failed to penalize comment {} after retries: {}", commentName, e.getMessage());
                return Mono.empty();
            })
            .then();
    }

    private Mono<Void> penalizeReply(String replyName) {
        return client.fetch(Reply.class, replyName)
            .flatMap(reply -> {
                var spec = reply.getSpec();
                if (spec == null) return Mono.<Reply>empty();
                // 只要 approved 不是 false，就强制设为 false
                if (!Boolean.FALSE.equals(spec.getApproved())) {
                    log.info("[PreFilter] Penalizing reply {}: approved={} → false", replyName, spec.getApproved());
                    spec.setApproved(false);
                    spec.setApprovedTime(null);
                    return client.update(reply)
                        .doOnSuccess(r -> log.info("[PreFilter] Reply {} set to pending for violation", replyName));
                }
                log.debug("[PreFilter] Reply {} already unapproved, skip penalize", replyName);
                return Mono.<Reply>empty();
            })
            .retryWhen(Retry.backoff(3, Duration.ofMillis(100))
                .filter(OptimisticLockingFailureException.class::isInstance)
                .doBeforeRetry(sig -> log.debug("[PreFilter] Retrying penalizeReply {} (attempt {})", replyName, sig.totalRetries() + 1)))
            .onErrorResume(e -> {
                log.warn("[PreFilter] Failed to penalize reply {} after retries: {}", replyName, e.getMessage());
                return Mono.empty();
            })
            .then();
    }

    /**
     * 加载前置过滤配置。
     */
    private Mono<PreFilterConfig> loadConfig() {
        return client.fetch(ConfigMap.class, CONFIG_MAP_NAME)
            .mapNotNull(cm -> {
                var data = cm.getData();
                if (data == null) return new PreFilterConfig(true, true);
                String basicJson = data.get("basic");
                if (basicJson == null || basicJson.isBlank()) return new PreFilterConfig(true, true);
                try {
                    JsonNode node = objectMapper.readTree(basicJson);
                    boolean enabled = !node.has("preFilterEnabled")
                        || node.get("preFilterEnabled").asBoolean(true);
                    boolean pendingOnViolation = !node.has("preFilterPendingOnViolation")
                        || node.get("preFilterPendingOnViolation").asBoolean(true);
                    return new PreFilterConfig(enabled, pendingOnViolation);
                } catch (Exception e) {
                    log.warn("[PreFilter] Failed to parse config: {}", e.getMessage());
                    return new PreFilterConfig(true, true);
                }
            })
            .defaultIfEmpty(new PreFilterConfig(true, true))
            .onErrorResume(e -> {
                log.warn("[PreFilter] Failed to load config: {}", e.getMessage());
                return Mono.just(new PreFilterConfig(true, true));
            });
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    private String stripHtml(String html) {
        if (html == null || html.isBlank()) return "";
        return Jsoup.clean(html, Safelist.none()).trim();
    }

    public record PreFilterResult(boolean passed, String category, String reason) {}

    public record PreFilterConfig(boolean enabled, boolean pendingOnViolation) {}
}
