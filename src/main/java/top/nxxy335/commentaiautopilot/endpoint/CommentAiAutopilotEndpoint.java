package top.nxxy335.commentaiautopilot.endpoint;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.Plugin;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Reply;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.index.query.Queries;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.PageRequestImpl;
import top.nxxy335.commentaiautopilot.extension.AiCommentReply;
import top.nxxy335.commentaiautopilot.extension.AiPersona;
import top.nxxy335.commentaiautopilot.service.AiFoundationClient;
import top.nxxy335.commentaiautopilot.service.AiReplyCleanupService;
import top.nxxy335.commentaiautopilot.service.AiReplyOrchestrator;
import top.nxxy335.commentaiautopilot.service.CommentNextDetectionService;
import top.nxxy335.commentaiautopilot.service.CommentReplyPublisher;
import top.nxxy335.commentaiautopilot.service.MomentsIntegrationService;
import top.nxxy335.commentaiautopilot.service.PersonaResolver;
import top.nxxy335.commentaiautopilot.service.WhitelistService;
import top.nxxy335.commentaiautopilot.util.GravatarUtil;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.springframework.web.reactive.function.server.RouterFunctions.route;

@Component
@Slf4j
public class CommentAiAutopilotEndpoint implements CustomEndpoint {

    private final ReactiveExtensionClient client;
    private final AiReplyOrchestrator orchestrator;
    private final AiReplyCleanupService cleanupService;
    private final AiFoundationClient aiFoundationClient;
    private final CommentReplyPublisher commentReplyPublisher;
    private final ObjectMapper objectMapper;
    private final PersonaResolver personaResolver;
    private final MomentsIntegrationService momentsIntegrationService;
    private final WhitelistService whitelistService;
    private final CommentNextDetectionService commentNextDetectionService;

    private static final String CONFIG_MAP_NAME = "comment-ai-autopilot-configmap";

    public CommentAiAutopilotEndpoint(ReactiveExtensionClient client, AiReplyOrchestrator orchestrator, AiReplyCleanupService cleanupService, AiFoundationClient aiFoundationClient, CommentReplyPublisher commentReplyPublisher, ObjectMapper objectMapper, PersonaResolver personaResolver, MomentsIntegrationService momentsIntegrationService, WhitelistService whitelistService, CommentNextDetectionService commentNextDetectionService) {
        this.client = client;
        this.orchestrator = orchestrator;
        this.cleanupService = cleanupService;
        this.aiFoundationClient = aiFoundationClient;
        this.commentReplyPublisher = commentReplyPublisher;
        this.objectMapper = objectMapper;
        this.personaResolver = personaResolver;
        this.momentsIntegrationService = momentsIntegrationService;
        this.whitelistService = whitelistService;
        this.commentNextDetectionService = commentNextDetectionService;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route()
            .GET("/replies", this::listReplies)
            .POST("/replies/batch-approve", this::batchApproveReplies)
            .POST("/replies/batch-reject", this::batchRejectReplies)
            .POST("/replies/batch-delete", this::batchDeleteReplies)
            .DELETE("/replies/{name}", this::deleteReply)
            // 仅删除AI回评（删除已发布的Reply扩展，保留日志记录）
            .DELETE("/replies/{name}/ai-reply", this::deleteAiReplyOnly)
            // 删除评论者评论（删除Comment及其关联Reply，但保留AiCommentReply日志记录）
            .DELETE("/replies/{name}/comment", this::deleteCommenterComment)
            // 取消通过AI回复（将 Reply approved 设为 false，保留日志）
            .POST("/replies/{name}/unpublish-ai-reply", this::unpublishAiReply)
            // 取消通过评论者评论（将 Comment approved 设为 false，保留日志）
            .POST("/replies/{name}/unpublish-comment", this::unpublishComment)
            .GET("/stats", this::getStats)
            .GET("/persona", this::getPersona)
            .GET("/conversation/{commentName}", this::getConversation)
            .POST("/replies/{name}/approve", this::approveReply)
            .POST("/replies/{name}/reject", this::rejectReply)
            .POST("/comments/{commentName}/trigger", this::triggerReply)
            .POST("/replies/{replyName}/trigger-conversation", this::triggerConversationReply)
            .GET("/commenters", this::listCommenters)
            .GET("/admins", this::listAdmins)
            .POST("/cleanup", this::triggerCleanup)
            .GET("/health", this::health)
            .GET("/personas", this::listPersonas)
            .GET("/personas/{name}", this::getPersonaByName)
            .POST("/personas", this::createPersona)
            .PUT("/personas/{name}", this::updatePersona)
            .DELETE("/personas/{name}", this::deletePersona)
            // 导出配置
            .GET("/export", this::exportConfig)
            // 导入配置
            .POST("/import", this::importConfig)
            // 更新草稿回复内容（同时更新 AiCommentReply 和 Reply 扩展）
            .PUT("/replies/{name}/content", this::updateReplyContent)
            // 误报反馈：将拦截记录标记为误报，可选触发AI回复
            .POST("/replies/{name}/false-positive", this::falsePositive)
            // 查询瞬间插件可用性
            .GET("/moments-status", this::momentsStatus)
            // 查询 Comment Next 插件冲突状态
            .GET("/comment-next-status", this::commentNextStatus)
            // 白名单评论者列表管理
            .GET("/whitelist", this::getWhitelist)
            .POST("/whitelist", this::updateWhitelist)
            .DELETE("/whitelist", this::clearWhitelist)
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.comment-ai-autopilot.nxxy335.top", "v1alpha1");
    }

    private Mono<ServerResponse> listReplies(ServerRequest request) {
        int page = parseIntSafely(request.queryParam("page").orElse("1"), 1);
        int size = parseIntSafely(request.queryParam("size").orElse("20"), 20);
        var statusFilter = request.queryParam("status").orElse("");
        var sentimentFilter = request.queryParam("sentiment").orElse("");
        var keywordFilter = request.queryParam("keyword").orElse("").toLowerCase();
        var startDateStr = request.queryParam("startDate").orElse("");
        var endDateStr = request.queryParam("endDate").orElse("");
        var sortOrder = request.queryParam("sortOrder").orElse("desc");

        // Parse date filters
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        ZoneId zoneId = ZoneId.systemDefault();
        Instant startInstant = null;
        Instant endInstant = null;
        try {
            if (!startDateStr.isBlank()) {
                startInstant = LocalDate.parse(startDateStr, dateFormatter).atStartOfDay(zoneId).toInstant();
            }
            if (!endDateStr.isBlank()) {
                endInstant = LocalDate.parse(endDateStr, dateFormatter).plusDays(1).atStartOfDay(zoneId).toInstant();
            }
        } catch (Exception e) {
            log.warn("Failed to parse date filter: {}", e.getMessage());
        }
        final Instant finalStartInstant = startInstant;
        final Instant finalEndInstant = endInstant;

        // Check if we need in-memory filtering (keyword or date range)
        boolean needsMemoryFilter = !keywordFilter.isBlank() || finalStartInstant != null || finalEndInstant != null;

        // Build server-side query for status and sentiment (indexed fields)
        var listOptionsBuilder = ListOptions.builder();
        if (!statusFilter.isBlank()) {
            listOptionsBuilder.andQuery(Queries.equal("spec.status", statusFilter));
        }
        if (!sentimentFilter.isBlank()) {
            listOptionsBuilder.andQuery(Queries.equal("spec.sentiment", sentimentFilter));
        }
        var listOptions = listOptionsBuilder.build();

        if (needsMemoryFilter) {
            // Fall back to listAll + in-memory filter for keyword/date queries
            return client.listAll(AiCommentReply.class, listOptions, Sort.unsorted())
                .collectList()
                .map(replies -> {
                    var filtered = replies.stream()
                        .filter(r -> {
                            if (!keywordFilter.isBlank()) {
                                String reply = r.getSpec().getReply();
                                if (reply == null || !reply.toLowerCase().contains(keywordFilter)) return false;
                            }
                            if (finalStartInstant != null || finalEndInstant != null) {
                                Instant creationTs = r.getMetadata().getCreationTimestamp();
                                if (creationTs == null) return false;
                                if (finalStartInstant != null && creationTs.isBefore(finalStartInstant)) return false;
                                if (finalEndInstant != null && !creationTs.isBefore(finalEndInstant)) return false;
                            }
                            return true;
                        })
                        .sorted(Comparator.comparing(
                            (AiCommentReply r) -> r.getMetadata().getCreationTimestamp(),
                            Comparator.nullsLast("asc".equalsIgnoreCase(sortOrder)
                                ? Comparator.<Instant>naturalOrder() : Comparator.<Instant>reverseOrder())
                        ))
                        .toList();

                    int total = filtered.size();
                    int fromIndex = (page - 1) * size;
                    int toIndex = Math.min(fromIndex + size, total);
                    List<AiCommentReply> pageContent = fromIndex < total
                        ? filtered.subList(fromIndex, toIndex) : List.of();

                    Map<String, Object> result = new HashMap<>();
                    result.put("items", pageContent);
                    result.put("total", total);
                    result.put("page", page);
                    result.put("size", size);
                    result.put("totalPages", (int) Math.ceil((double) total / size));
                    result.put("first", page == 1);
                    result.put("last", toIndex >= total);
                    return result;
                })
                .flatMap(result -> ServerResponse.ok().bodyValue(result));
        }

        // No memory filters needed - use server-side pagination directly
        Sort sort = "asc".equalsIgnoreCase(sortOrder)
            ? Sort.by(Sort.Order.asc("metadata.creationTimestamp"))
            : Sort.by(Sort.Order.desc("metadata.creationTimestamp"));

        return client.listBy(AiCommentReply.class, listOptions,
                PageRequestImpl.of(page - 1, size, sort))
            .map(listResult -> {
                Map<String, Object> result = new HashMap<>();
                result.put("items", listResult.getItems());
                result.put("total", listResult.getTotal());
                result.put("page", page);
                result.put("size", size);
                result.put("totalPages", (int) Math.ceil((double) listResult.getTotal() / size));
                result.put("first", page == 1);
                result.put("last", page >= (int) Math.ceil((double) listResult.getTotal() / size));
                return result;
            })
            .flatMap(result -> ServerResponse.ok().bodyValue(result));
    }

    private Mono<ServerResponse> deleteReply(ServerRequest request) {
        var name = request.pathVariable("name");
        return client.fetch(AiCommentReply.class, name)
            .flatMap(record -> client.delete(record))
            .then(ServerResponse.ok().bodyValue("{\"message\":\"deleted\"}"))
            .switchIfEmpty(ServerResponse.notFound().build());
    }

    /**
     * 仅删除AI回评：删除已发布的 Reply 扩展，保留 AiCommentReply 日志记录并将状态标记为 DELETED。
     */
    private Mono<ServerResponse> deleteAiReplyOnly(ServerRequest request) {
        var name = request.pathVariable("name");
        return client.fetch(AiCommentReply.class, name)
            .flatMap(record -> {
                String replyName = record.getSpec().getReplyName();
                Mono<Void> deleteReplyMono = Mono.empty();
                if (replyName != null && !replyName.isBlank()) {
                    deleteReplyMono = client.fetch(Reply.class, replyName)
                        .flatMap(client::delete)
                        .onErrorResume(e -> {
                            log.warn("[Endpoint] Failed to delete Reply {}: {}", replyName, e.getMessage());
                            return Mono.empty();
                        })
                        .then();
                }
                return deleteReplyMono.then(Mono.defer(() ->
                    // 重新 fetch 最新版本，避免删除 Reply 期间版本号过期导致乐观锁重试无效
                    client.fetch(AiCommentReply.class, name)
                        .flatMap(latest -> {
                            latest.getSpec().setStatus("DELETED");
                            latest.getSpec().setPublished(false);
                            return client.update(latest);
                        })
                        .retryWhen(Retry.backoff(3, Duration.ofMillis(100))
                            .filter(e -> e instanceof OptimisticLockingFailureException))
                        .then(ServerResponse.ok().bodyValue(Map.of("message", "AI回评已删除，日志已保留")))
                ));
            })
            .switchIfEmpty(ServerResponse.notFound().build());
    }

    /**
     * 删除评论者评论：删除 Comment 及其所有 Reply 扩展，但保留 AiCommentReply 日志记录。
     * 日志记录状态更新为 DELETED、published=false，便于审计追溯。
     */
    private Mono<ServerResponse> deleteCommenterComment(ServerRequest request) {
        var name = request.pathVariable("name");
        return client.fetch(AiCommentReply.class, name)
            .flatMap(record -> {
                String commentName = record.getSpec().getCommentId();
                if (commentName == null || commentName.isBlank()) {
                    return ServerResponse.badRequest().bodyValue(Map.of("message", "找不到关联的评论"));
                }
                // 1. 删除所有关联的 Reply 扩展
                Mono<Void> deleteRepliesMono = client.list(Reply.class,
                        reply -> {
                            var spec = reply.getSpec();
                            return spec != null && commentName.equals(spec.getCommentName());
                        }, null)
                    .flatMap(client::delete, 10)
                    .onErrorResume(e -> {
                        log.warn("[Endpoint] Failed to delete replies for comment {}: {}", commentName, e.getMessage());
                        return Mono.empty();
                    })
                    .then();
                // 2. 保留 AiCommentReply 日志记录，仅更新状态为 DELETED、published=false
                //    清空 replyName 避免前端误操作已删除的 Reply 扩展
                Mono<Void> markLogsDeletedMono = client.list(AiCommentReply.class,
                        r -> r.getSpec() != null && commentName.equals(r.getSpec().getCommentId()), null)
                    .flatMap(r -> {
                        r.getSpec().setStatus("DELETED");
                        r.getSpec().setPublished(false);
                        r.getSpec().setReplyName(null);
                        return client.update(r)
                            .retryWhen(Retry.backoff(3, Duration.ofMillis(100))
                                .filter(e -> e instanceof OptimisticLockingFailureException))
                            .onErrorResume(e -> {
                                log.warn("[Endpoint] Failed to mark AiCommentReply {} as DELETED: {}",
                                    r.getMetadata().getName(), e.getMessage());
                                return Mono.empty();
                            });
                    }, 10)
                    .then();
                // 3. 删除 Comment 本身
                Mono<Void> deleteCommentMono = client.fetch(Comment.class, commentName)
                    .flatMap(client::delete)
                    .onErrorResume(e -> {
                        log.warn("[Endpoint] Failed to delete Comment {}: {}", commentName, e.getMessage());
                        return Mono.empty();
                    })
                    .then();
                return deleteRepliesMono
                    .then(markLogsDeletedMono)
                    .then(deleteCommentMono)
                    .then(ServerResponse.ok().bodyValue(Map.of("message", "违规评论已删除，日志已保留")));
            })
            .switchIfEmpty(ServerResponse.notFound().build());
    }

    /**
     * 取消通过 AI 回复：将已发布的 Reply 扩展 approved 设为 false，保留 AiCommentReply 日志。
     * 用于"已发布"状态下撤回 AI 回复的发布状态。
     */
    private Mono<ServerResponse> unpublishAiReply(ServerRequest request) {
        var name = request.pathVariable("name");
        return client.fetch(AiCommentReply.class, name)
            .flatMap(record -> {
                String replyName = record.getSpec().getReplyName();
                if (replyName == null || replyName.isBlank()) {
                    return ServerResponse.badRequest()
                        .bodyValue(Map.of("message", "该记录未关联已发布的 Reply，无需取消通过"));
                }
                return client.fetch(Reply.class, replyName)
                    .flatMap(reply -> {
                        reply.getSpec().setApproved(false);
                        reply.getSpec().setApprovedTime(null);
                        return client.update(reply);
                    })
                    .retryWhen(Retry.backoff(3, Duration.ofMillis(100))
                        .filter(e -> e instanceof OptimisticLockingFailureException))
                    .then(Mono.defer(() -> client.fetch(AiCommentReply.class, name)
                        .flatMap(latest -> {
                            latest.getSpec().setPublished(false);
                            return client.update(latest);
                        })
                        .retryWhen(Retry.backoff(3, Duration.ofMillis(100))
                            .filter(e -> e instanceof OptimisticLockingFailureException))
                    ))
                    .then(ServerResponse.ok().bodyValue(Map.of("message", "AI回复已取消通过，日志已保留")));
            })
            .switchIfEmpty(ServerResponse.notFound().build());
    }

    /**
     * 取消通过评论者评论：将 Comment 扩展 approved 设为 false，保留 AiCommentReply 日志。
     * 用于"已发布"状态下撤回评论者评论的发布状态。
     */
    private Mono<ServerResponse> unpublishComment(ServerRequest request) {
        var name = request.pathVariable("name");
        return client.fetch(AiCommentReply.class, name)
            .flatMap(record -> {
                String commentName = record.getSpec().getCommentId();
                if (commentName == null || commentName.isBlank()) {
                    return ServerResponse.badRequest()
                        .bodyValue(Map.of("message", "找不到关联的评论"));
                }
                return client.fetch(Comment.class, commentName)
                    .flatMap(comment -> {
                        comment.getSpec().setApproved(false);
                        comment.getSpec().setApprovedTime(null);
                        return client.update(comment);
                    })
                    .retryWhen(Retry.backoff(3, Duration.ofMillis(100))
                        .filter(e -> e instanceof OptimisticLockingFailureException))
                    .then(ServerResponse.ok().bodyValue(Map.of("message", "评论者评论已取消通过，日志已保留")));
            })
            .switchIfEmpty(ServerResponse.notFound().build());
    }

    /**
     * 查询 Comment Next 插件冲突状态。
     * 前端据此判断是否显示冲突提示卡。
     */
    private Mono<ServerResponse> commentNextStatus(ServerRequest request) {
        return commentNextDetectionService.detect()
            .flatMap(status -> ServerResponse.ok().bodyValue(Map.of(
                "installed", status.installed(),
                "enabled", status.enabled()
            )));
    }

    /**
     * 获取白名单配置（启用状态 + 评论者列表）。
     */
    private Mono<ServerResponse> getWhitelist(ServerRequest request) {
        return whitelistService.getConfig()
            .map(config -> Map.of(
                "enabled", config.enabled(),
                "commenters", config.list()
            ))
            .flatMap(result -> ServerResponse.ok().bodyValue(result));
    }

    /**
     * 更新白名单评论者列表。
     * 请求体：{ "commenters": ["name1", "name2", ...] }
     */
    private Mono<ServerResponse> updateWhitelist(ServerRequest request) {
        return request.bodyToMono(String.class)
            .flatMap(body -> {
                List<String> commenters;
                try {
                    JsonNode node = objectMapper.readTree(body);
                    JsonNode commentersNode = node.get("commenters");
                    commenters = new ArrayList<>();
                    if (commentersNode != null && commentersNode.isArray()) {
                        commentersNode.forEach(n -> {
                            String text = n.asText("").trim();
                            if (!text.isEmpty()) {
                                commenters.add(text);
                            }
                        });
                    }
                } catch (Exception e) {
                    return ServerResponse.badRequest()
                        .bodyValue(Map.of("message", "请求体格式错误: " + e.getMessage()));
                }
                return whitelistService.updateWhitelistedCommenters(commenters)
                    .then(ServerResponse.ok().bodyValue(Map.of(
                        "message", "白名单已更新",
                        "commenters", commenters
                    )));
            });
    }

    /**
     * 清空白名单评论者列表。
     */
    private Mono<ServerResponse> clearWhitelist(ServerRequest request) {
        return whitelistService.clearWhitelistedCommenters()
            .then(ServerResponse.ok().bodyValue(Map.of("message", "白名单已清空")));
    }

    private Mono<ServerResponse> getStats(ServerRequest request) {
        return client.listAll(AiCommentReply.class, ListOptions.builder().build(), Sort.unsorted())
            .collectList()
            .map(replies -> {
                long total = replies.size();
                long passCount = replies.stream()
                    .filter(r -> "PASS".equals(r.getSpec().getStatus())).count();
                long failCount = replies.stream()
                    .filter(r -> "FAIL".equals(r.getSpec().getStatus())).count();
                long filteredCount = replies.stream()
                    .filter(r -> "FILTERED".equals(r.getSpec().getStatus())).count();

                long reviewingCount = replies.stream()
                    .filter(r -> "PASS".equals(r.getSpec().getStatus())
                        && !Boolean.TRUE.equals(r.getSpec().getPublished()))
                    .count();

                return new StatsResponse(total, passCount, failCount, reviewingCount, filteredCount);
            })
            .onErrorResume(e -> {
                log.warn("Failed to fetch stats: {}", e.getMessage());
                return Mono.just(new StatsResponse(0, 0, 0, 0, 0));
            })
            .flatMap(stats -> ServerResponse.ok().bodyValue(stats));
    }

    private Mono<ServerResponse> getPersona(ServerRequest request) {
        return client.list(AiPersona.class,
                persona -> persona.getSpec() != null && Boolean.TRUE.equals(persona.getSpec().getIsDefault()),
                null)
            .next()
            .flatMap(persona -> {
                String email = persona.getSpec().getEmail();
                String avatarUrl = GravatarUtil.generateUrl(email);
                return ServerResponse.ok().bodyValue(Map.of(
                    "name", persona.getSpec().getDisplayName(),
                    "prompt", persona.getSpec().getPrompt() != null ? persona.getSpec().getPrompt() : "",
                    "avatar", avatarUrl
                ));
            })
            .switchIfEmpty(ServerResponse.ok().bodyValue(Map.of(
                "name", "小回",
                "prompt", "",
                "avatar", ""
            )));
    }

    public record StatsResponse(
        long total,
        long passCount,
        long failCount,
        long reviewingCount,
        long filteredCount
    ) {}

    private Mono<ServerResponse> getConversation(ServerRequest request) {
        var commentName = request.pathVariable("commentName");
        return client.fetch(Comment.class, commentName)
            .flatMap(comment -> {
                var commentOwner = extractOwnerName(comment.getSpec().getOwner());
                var commentContent = extractContent(comment.getSpec().getRaw(), comment.getSpec().getContent());
                var commentTime = String.valueOf(comment.getMetadata().getCreationTimestamp());
                var isCommentAi = isAiOwner(comment.getSpec().getOwner());

                // 首条评论没有引用对象
                var commentMsg = new ConversationMessage(
                    "comment", commentOwner, commentContent, commentTime, isCommentAi, null, null
                );

                return client.list(Reply.class,
                        reply -> commentName.equals(reply.getSpec().getCommentName()),
                        null)
                    .sort(Comparator.comparing(r -> r.getMetadata().getCreationTimestamp()))
                    .collectList() // 收集为List以便统一处理引用映射
                    .map(replyList -> {
                        List<ConversationMessage> messages = new ArrayList<>();
                        messages.add(commentMsg);

                        // 构建 Reply 的映射字典，方便查找引用关系
                        Map<String, Reply> replyMap = new HashMap<>();
                        for (Reply r : replyList) {
                            replyMap.put(r.getMetadata().getName(), r);
                        }

                        for (Reply reply : replyList) {
                            var replyOwner = extractOwnerName(reply.getSpec().getOwner());
                            var replyContent = extractContent(reply.getSpec().getRaw(), reply.getSpec().getContent());
                            var replyTime = String.valueOf(reply.getMetadata().getCreationTimestamp());
                            var isAi = isAiOwner(reply.getSpec().getOwner());

                            String quoteOwner = null;
                            String quoteContent = null;

                            // 获取引用的 Reply 名称 (Halo中如果为空，代表直接回复顶级 Comment)
                            String quoteReplyName = reply.getSpec().getQuoteReply();
                            if (quoteReplyName != null && !quoteReplyName.isBlank()) {
                                Reply quotedReply = replyMap.get(quoteReplyName);
                                if (quotedReply != null) {
                                    quoteOwner = extractOwnerName(quotedReply.getSpec().getOwner());
                                    quoteContent = extractContent(quotedReply.getSpec().getRaw(), quotedReply.getSpec().getContent());
                                }
                            } else {
                                // 没有 quoteReply 表示直接回复首条评论
                                quoteOwner = commentOwner;
                                quoteContent = commentContent;
                            }

                            messages.add(new ConversationMessage("reply", replyOwner, replyContent, replyTime, isAi, quoteOwner, quoteContent));
                        }
                        return messages;
                    });
            })
            .flatMap(messages -> ServerResponse.ok().bodyValue(Map.of("messages", messages)))
            .switchIfEmpty(ServerResponse.ok().bodyValue(Map.of("messages", List.of())));
    }

    private String extractOwnerName(Comment.CommentOwner owner) {
        if (owner == null) return "匿名用户";
        var displayName = owner.getDisplayName();
        return (displayName != null && !displayName.isBlank()) ? displayName : "匿名用户";
    }

    private String extractContent(String raw, String content) {
        if (raw != null && !raw.isBlank()) return raw;
        if (content != null && !content.isBlank()) return content;
        return "";
    }

    private boolean isAiOwner(Comment.CommentOwner owner) {
        if (owner == null) return false;
        var annotations = owner.getAnnotations();
        if (annotations != null) {
            return "true".equals(annotations.get("comment-ai-autopilot.nxxy335.top/is-ai"));
        }
        return false;
    }

    private Mono<ServerResponse> approveReply(ServerRequest request) {
        var name = request.pathVariable("name");
        return client.fetch(AiCommentReply.class, name)
            .flatMap(record -> {
                String replyName = record.getSpec().getReplyName();
                if (replyName == null || replyName.isBlank()) {
                    // Draft mode: no Reply extension exists yet.
                    // First check if a Reply already exists (e.g. from a previous autoPublish=true run)
                    return findReplyForRecord(record)
                        .flatMap(existingReply -> {
                            // Reply already exists, just approve it
                            existingReply.getSpec().setApproved(true);
                            existingReply.getSpec().setApprovedTime(Instant.now());
                            return client.update(existingReply)
                                .retryWhen(Retry.backoff(3, Duration.ofMillis(100))
                                    .filter(e -> e instanceof OptimisticLockingFailureException))
                                .then(Mono.defer(() -> client.fetch(AiCommentReply.class, name)
                                    .flatMap(latest -> {
                                        latest.getSpec().setReplyName(existingReply.getMetadata().getName());
                                        latest.getSpec().setPublished(true);
                                        return client.update(latest);
                                    })
                                    .retryWhen(Retry.backoff(3, Duration.ofMillis(100))
                                        .filter(e -> e instanceof OptimisticLockingFailureException))
                                ))
                                .then(ServerResponse.ok().bodyValue(Map.of("message", "approved")));
                        })
                        .switchIfEmpty(Mono.defer(() -> {
                            // No existing Reply found, create a new approved one
                            return commentReplyPublisher.publishReply(
                                    record.getSpec().getCommentId(),
                                    record.getSpec().getReply(),
                                    record.getSpec().getPostId(),
                                    record.getSpec().getReplyTo(),
                                    true,
                                    record.getSpec().getPersonaName()
                                )
                                .flatMap(publishedReply -> {
                                    String newReplyName = publishedReply.getMetadata().getName();
                                    return client.fetch(AiCommentReply.class, name)
                                        .flatMap(latest -> {
                                            latest.getSpec().setReplyName(newReplyName);
                                            latest.getSpec().setPublished(true);
                                            return client.update(latest);
                                        })
                                        .retryWhen(Retry.backoff(3, Duration.ofMillis(100))
                                            .filter(e -> e instanceof OptimisticLockingFailureException));
                                })
                                .then(ServerResponse.ok().bodyValue(Map.of("message", "approved")));
                        }));
                } else {
                    // Reply extension already exists, set approved=true
                    return client.fetch(Reply.class, replyName)
                        .flatMap(reply -> {
                            reply.getSpec().setApproved(true);
                            reply.getSpec().setApprovedTime(Instant.now());
                            return client.update(reply);
                        })
                        .retryWhen(Retry.backoff(3, Duration.ofMillis(100))
                            .filter(e -> e instanceof OptimisticLockingFailureException))
                        .then(Mono.defer(() -> client.fetch(AiCommentReply.class, name)
                            .flatMap(latest -> {
                                latest.getSpec().setPublished(true);
                                return client.update(latest);
                            })
                            .retryWhen(Retry.backoff(3, Duration.ofMillis(100))
                                .filter(e -> e instanceof OptimisticLockingFailureException))
                        ))
                        .then(ServerResponse.ok().bodyValue(Map.of("message", "approved")));
                }
            })
            .switchIfEmpty(ServerResponse.notFound().build());
    }

    private Mono<ServerResponse> rejectReply(ServerRequest request) {
        var name = request.pathVariable("name");
        return client.fetch(AiCommentReply.class, name)
            .flatMap(record -> {
                String replyName = record.getSpec().getReplyName();
                Mono<Void> deleteReplyMono;
                if (replyName != null && !replyName.isBlank()) {
                    // Reply extension exists, delete it
                    deleteReplyMono = client.fetch(Reply.class, replyName)
                        .flatMap(reply -> client.delete(reply))
                        .then();
                } else {
                    // No Reply extension in draft mode, nothing to delete
                    deleteReplyMono = Mono.empty();
                }
                return deleteReplyMono
                    .then(Mono.defer(() -> client.fetch(AiCommentReply.class, name)
                        .flatMap(latest -> {
                            latest.getSpec().setStatus("REJECTED");
                            latest.getSpec().setPublished(false);
                            latest.getSpec().setReplyName(null);
                            return client.update(latest);
                        })
                        .retryWhen(Retry.backoff(3, Duration.ofMillis(100))
                            .filter(e -> e instanceof OptimisticLockingFailureException))
                    ))
                    .then(ServerResponse.ok().bodyValue(Map.of("message", "rejected")));
            })
            .switchIfEmpty(ServerResponse.notFound().build());
    }

    private Mono<ServerResponse> batchApproveReplies(ServerRequest request) {
        return request.bodyToMono(String.class)
            .flatMap(body -> {
                List<String> names;
                try {
                    JsonNode node = objectMapper.readTree(body);
                    names = new ArrayList<>();
                    node.get("names").forEach(n -> names.add(n.asText()));
                } catch (Exception e) {
                    return ServerResponse.badRequest()
                        .bodyValue(Map.of("successCount", 0, "failCount", 0));
                }
                return Flux.fromIterable(names)
                    .flatMap(name ->
                        client.fetch(AiCommentReply.class, name)
                            .flatMap(record -> {
                                String replyName = record.getSpec().getReplyName();
                                if (replyName == null || replyName.isBlank()) {
                                    // Draft mode: check if Reply already exists first
                                    return findReplyForRecord(record)
                                        .flatMap(existingReply -> {
                                            existingReply.getSpec().setApproved(true);
                                            existingReply.getSpec().setApprovedTime(Instant.now());
                                            return client.update(existingReply)
                                                .retryWhen(Retry.backoff(3, Duration.ofMillis(100))
                                                    .filter(e -> e instanceof OptimisticLockingFailureException))
                                                .then(Mono.defer(() -> client.fetch(AiCommentReply.class, name)
                                                    .flatMap(latest -> {
                                                        latest.getSpec().setReplyName(existingReply.getMetadata().getName());
                                                        latest.getSpec().setPublished(true);
                                                        return client.update(latest);
                                                    })
                                                    .retryWhen(Retry.backoff(3, Duration.ofMillis(100))
                                                        .filter(e -> e instanceof OptimisticLockingFailureException))
                                                ));
                                        })
                                        .switchIfEmpty(Mono.defer(() ->
                                            commentReplyPublisher.publishReply(
                                                    record.getSpec().getCommentId(),
                                                    record.getSpec().getReply(),
                                                    record.getSpec().getPostId(),
                                                    record.getSpec().getReplyTo(),
                                                    true,
                                                    record.getSpec().getPersonaName()
                                                )
                                                .flatMap(publishedReply -> {
                                                    String newReplyName = publishedReply.getMetadata().getName();
                                                    return client.fetch(AiCommentReply.class, name)
                                                        .flatMap(latest -> {
                                                            latest.getSpec().setReplyName(newReplyName);
                                                            latest.getSpec().setPublished(true);
                                                            return client.update(latest);
                                                        })
                                                        .retryWhen(Retry.backoff(3, Duration.ofMillis(100))
                                                            .filter(e -> e instanceof OptimisticLockingFailureException));
                                                })
                                        ));
                                } else {
                                    // Reply extension already exists, set approved=true
                                    return client.fetch(Reply.class, replyName)
                                        .flatMap(reply -> {
                                            reply.getSpec().setApproved(true);
                                            reply.getSpec().setApprovedTime(Instant.now());
                                            return client.update(reply);
                                        })
                                        .retryWhen(Retry.backoff(3, Duration.ofMillis(100))
                                            .filter(e -> e instanceof OptimisticLockingFailureException))
                                        .then(Mono.defer(() -> client.fetch(AiCommentReply.class, name)
                                            .flatMap(latest -> {
                                                latest.getSpec().setPublished(true);
                                                return client.update(latest);
                                            })
                                            .retryWhen(Retry.backoff(3, Duration.ofMillis(100))
                                                .filter(e -> e instanceof OptimisticLockingFailureException))
                                        ));
                                }
                            })
                            .thenReturn(true)
                            .onErrorResume(e -> {
                                log.warn("Batch approve failed for {}: {}", name, e.getMessage());
                                return Mono.just(false);
                            })
                            .defaultIfEmpty(false)
                    , 10)
                    .collectList()
                    .flatMap(results -> {
                        long successCount = results.stream().filter(b -> b).count();
                        long failCount = results.size() - successCount;
                        return ServerResponse.ok()
                            .bodyValue(Map.of("successCount", successCount, "failCount", failCount));
                    });
            });
    }

    private Mono<ServerResponse> batchRejectReplies(ServerRequest request) {
        return request.bodyToMono(String.class)
            .flatMap(body -> {
                List<String> names;
                try {
                    JsonNode node = objectMapper.readTree(body);
                    names = new ArrayList<>();
                    node.get("names").forEach(n -> names.add(n.asText()));
                } catch (Exception e) {
                    return ServerResponse.badRequest()
                        .bodyValue(Map.of("successCount", 0, "failCount", 0));
                }
                return Flux.fromIterable(names)
                    .flatMap(name ->
                        client.fetch(AiCommentReply.class, name)
                            .flatMap(record -> {
                                String replyName = record.getSpec().getReplyName();
                                Mono<Void> deleteReplyMono;
                                if (replyName != null && !replyName.isBlank()) {
                                    deleteReplyMono = client.fetch(Reply.class, replyName)
                                        .flatMap(reply -> client.delete(reply))
                                        .then();
                                } else {
                                    deleteReplyMono = Mono.empty();
                                }
                                return deleteReplyMono
                                    .then(Mono.defer(() -> client.fetch(AiCommentReply.class, name)
                                        .flatMap(latest -> {
                                            latest.getSpec().setStatus("REJECTED");
                                            latest.getSpec().setPublished(false);
                                            latest.getSpec().setReplyName(null);
                                            return client.update(latest);
                                        })
                                        .retryWhen(Retry.backoff(3, Duration.ofMillis(100))
                                            .filter(e -> e instanceof OptimisticLockingFailureException))
                                    ))
                                    .thenReturn(true);
                            })
                            .onErrorResume(e -> {
                                log.warn("Batch reject failed for {}: {}", name, e.getMessage());
                                return Mono.just(false);
                            })
                            .defaultIfEmpty(false)
                    , 10)
                    .collectList()
                    .flatMap(results -> {
                        long successCount = results.stream().filter(b -> b).count();
                        long failCount = results.size() - successCount;
                        return ServerResponse.ok()
                            .bodyValue(Map.of("successCount", successCount, "failCount", failCount));
                    });
            });
    }

    private Mono<ServerResponse> batchDeleteReplies(ServerRequest request) {
        return request.bodyToMono(String.class)
            .flatMap(body -> {
                List<String> names;
                try {
                    JsonNode node = objectMapper.readTree(body);
                    names = new ArrayList<>();
                    node.get("names").forEach(n -> names.add(n.asText()));
                } catch (Exception e) {
                    return ServerResponse.badRequest()
                        .bodyValue(Map.of("successCount", 0, "failCount", 0));
                }
                return Flux.fromIterable(names)
                    .flatMap(name ->
                        client.fetch(AiCommentReply.class, name)
                            .flatMap(record -> client.delete(record)
                                .thenReturn(true)
                            )
                            .onErrorResume(e -> {
                                log.warn("Batch delete failed for {}: {}", name, e.getMessage());
                                return Mono.just(false);
                            })
                            .defaultIfEmpty(false)
                    , 10)
                    .collectList()
                    .flatMap(results -> {
                        long successCount = results.stream().filter(b -> b).count();
                        long failCount = results.size() - successCount;
                        return ServerResponse.ok()
                            .bodyValue(Map.of("successCount", successCount, "failCount", failCount));
                    });
            });
    }

    private Mono<ServerResponse> triggerReply(ServerRequest request) {
        var commentName = request.pathVariable("commentName");

        // Check if there's already an AiCommentReply record for this comment
        return client.list(AiCommentReply.class,
                record -> commentName.equals(record.getSpec().getCommentId())
                    && !Boolean.TRUE.equals(record.getSpec().getIsAiConversation()),
                null)
            .hasElements()
            .flatMap(hasExisting -> {
                if (hasExisting) {
                    return ServerResponse.badRequest()
                        .bodyValue(Map.of("message", "该评论已有AI回复记录"));
                }
                // Read persona name from post annotations
                return personaResolver.getPersonaNameFromComment(commentName)
                    .flatMap(personaName ->
                        orchestrator.processComment(commentName, null, false, personaName, false)
                            .then(ServerResponse.ok().bodyValue(Map.of("message", "已触发AI回复")))
                    );
            });
    }

    private Mono<ServerResponse> triggerConversationReply(ServerRequest request) {
        var replyName = request.pathVariable("replyName");

        // First fetch the reply to get its parent comment name
        return client.fetch(Reply.class, replyName)
            .flatMap(reply -> {
                var commentName = reply.getSpec().getCommentName();

                // Check if there's already an AiCommentReply record for this conversation
                return client.list(AiCommentReply.class,
                        record -> replyName.equals(record.getSpec().getReplyTo())
                            && Boolean.TRUE.equals(record.getSpec().getIsAiConversation()),
                        null)
                    .hasElements()
                    .flatMap(hasExisting -> {
                        if (hasExisting) {
                            return ServerResponse.badRequest()
                                .bodyValue(Map.of("message", "该回复已有AI对话记录"));
                        }
                        return personaResolver.getPersonaNameFromComment(commentName)
                            .flatMap(personaName ->
                                orchestrator.processComment(commentName, replyName, true, personaName, false)
                                    .then(ServerResponse.ok().bodyValue(Map.of("message", "已触发AI对话回复")))
                            );
                    });
            })
            .switchIfEmpty(ServerResponse.notFound().build());
    }

    private Mono<Reply> findReplyForRecord(AiCommentReply record) {
        // First try using replyName if available
        String replyName = record.getSpec().getReplyName();
        if (replyName != null && !replyName.isBlank()) {
            return client.fetch(Reply.class, replyName);
        }
        // Fallback: find the Reply by commentName + owner annotations
        return client.list(Reply.class,
                reply -> {
                    if (!record.getSpec().getCommentId().equals(reply.getSpec().getCommentName())) {
                        return false;
                    }
                    var owner = reply.getSpec().getOwner();
                    if (owner == null) return false;
                    var annotations = owner.getAnnotations();
                    if (annotations == null || !"true".equals(annotations.get("comment-ai-autopilot.nxxy335.top/is-ai"))) {
                        return false;
                    }
                    // If record has a quoteReply, also match by quoteReply for precision
                    if (record.getSpec().getReplyTo() != null && !record.getSpec().getReplyTo().isBlank()) {
                        return record.getSpec().getReplyTo().equals(reply.getSpec().getQuoteReply());
                    }
                    return true;
                },
                null)
            .next()
            .switchIfEmpty(Mono.empty());
    }

    public record ConversationMessage(
        String type,
        String owner,
        String content,
        String time,
        boolean isAi,
        String quoteOwner,
        String quoteContent
    ) {}

    public record CommenterInfo(
        String displayName,
        String email,
        String avatarUrl
    ) {}

    private Mono<ServerResponse> listCommenters(ServerRequest request) {
        return client.list(Comment.class, null, null)
            .take(1000)
            .collectList()
            .map(comments -> {
                Set<String> seen = new HashSet<>();
                List<CommenterInfo> result = new ArrayList<>();
                for (var comment : comments) {
                    var owner = comment.getSpec() != null ? comment.getSpec().getOwner() : null;
                    if (owner == null) continue;
                    String displayName = owner.getDisplayName() != null ? owner.getDisplayName() : "";
                    String email = Comment.CommentOwner.KIND_EMAIL.equals(owner.getKind()) && owner.getName() != null
                        ? owner.getName() : "";
                    String key = displayName.toLowerCase() + "|" + email.toLowerCase();
                    if (seen.add(key)) {
                        // 优先使用 owner 注解中的头像，否则用邮箱生成 Gravatar
                        String avatarUrl = "";
                        if (owner.getAnnotations() != null && owner.getAnnotations().get(Comment.CommentOwner.AVATAR_ANNO) != null) {
                            avatarUrl = owner.getAnnotations().get(Comment.CommentOwner.AVATAR_ANNO);
                        } else if (!email.isBlank()) {
                            avatarUrl = GravatarUtil.generateUrl(email);
                        }
                        result.add(new CommenterInfo(displayName, email, avatarUrl));
                    }
                }
                return result;
            })
            .flatMap(commenters -> ServerResponse.ok().bodyValue(commenters));
    }

    private Mono<ServerResponse> listAdmins(ServerRequest request) {
        return whitelistService.getAdminList()
            .flatMap(result -> ServerResponse.ok().bodyValue(result));
    }

    private Mono<ServerResponse> triggerCleanup(ServerRequest request) {
        String daysParam = request.queryParam("days").orElse(null);
        Mono<Integer> daysMono;
        if (daysParam != null && !daysParam.isBlank()) {
            try {
                int days = Integer.parseInt(daysParam);
                daysMono = Mono.just(Math.max(1, days));
            } catch (NumberFormatException e) {
                daysMono = cleanupService.getRetentionDays();
            }
        } else {
            daysMono = cleanupService.getRetentionDays();
        }
        return daysMono
            .flatMap(days -> cleanupService.executeCleanup(days)
                .map(deleted -> Map.of("deletedCount", deleted, "retentionDays", days))
            )
            .flatMap(result -> ServerResponse.ok().bodyValue(result))
            .onErrorResume(e -> {
                log.warn("Failed to trigger cleanup: {}", e.getMessage());
                return ServerResponse.status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
                    .bodyValue(Map.of("message", "清理失败: " + e.getMessage()));
            });
    }

    private Mono<ServerResponse> health(ServerRequest request) {
        boolean classInstalled = aiFoundationClient.isInstalled();
        if (!classInstalled) {
            return ServerResponse.ok().bodyValue(new HealthResponse(false, false, false, "", "not-installed", "AI Foundation 插件未安装，请先安装该插件"));
        }
        return client.fetch(Plugin.class, "ai-foundation")
            .switchIfEmpty(client.fetch(Plugin.class, "plugin-ai-foundation"))
            .flatMap(plugin -> {
                boolean pluginEnabled = false;
                if (plugin.getStatus() != null && plugin.getStatus().getPhase() == Plugin.Phase.STARTED) {
                    pluginEnabled = true;
                } else if (plugin.getSpec() != null) {
                    try {
                        java.lang.reflect.Method getEnabled = plugin.getSpec().getClass().getMethod("getEnabled");
                        Object val = getEnabled.invoke(plugin.getSpec());
                        pluginEnabled = Boolean.TRUE.equals(val);
                    } catch (Exception ignored) {}
                }
                if (!pluginEnabled) {
                    return Mono.just(new HealthResponse(true, false, false, "", "not-enabled", "AI Foundation 插件已安装但未启用，请先启用插件"));
                }
                return checkAiFoundationStatus();
            })
            .switchIfEmpty(Mono.defer(() -> {
                return client.listAll(Plugin.class, ListOptions.builder().build(),
                        Sort.unsorted())
                    .filter(p -> {
                        String name = p.getMetadata() != null ? p.getMetadata().getName() : "";
                        return name.contains("ai-foundation") || name.contains("AiFoundation");
                    })
                    .next()
                    .flatMap(plugin -> {
                        boolean pluginEnabled = false;
                        if (plugin.getStatus() != null && plugin.getStatus().getPhase() == Plugin.Phase.STARTED) {
                            pluginEnabled = true;
                        }
                        if (!pluginEnabled) {
                            return Mono.just(new HealthResponse(true, false, false, "", "not-enabled", "AI Foundation 插件已安装但未启用，请先启用插件"));
                        }
                        return checkAiFoundationStatus();
                    })
                    .switchIfEmpty(Mono.defer(() -> checkAiFoundationStatus()));
            }))
            .onErrorResume(e -> Mono.just(new HealthResponse(true, false, false, "", "unhealthy", "AI Foundation 状态检测失败")))
            .flatMap(health -> ServerResponse.ok().bodyValue(health));
    }

    private Mono<HealthResponse> checkAiFoundationStatus() {
        return Mono.zip(
                aiFoundationClient.isAvailable(),
                getConfiguredModelName()
            )
            .flatMap(tuple -> {
                boolean available = tuple.getT1();
                String modelName = tuple.getT2();
                if (!available) {
                    return Mono.just(new HealthResponse(true, false, false, modelName, "unhealthy", "AI Foundation 服务不可用，请检查配置"));
                }
                return aiFoundationClient.hasModel(modelName)
                    .flatMap(hasModel -> {
                        if (hasModel) {
                            return Mono.just(new HealthResponse(true, true, true, modelName, "healthy", "AI Foundation 连接正常"));
                        }
                        return aiFoundationClient.hasModel(null)
                            .map(hasDefault -> {
                                if (hasDefault) {
                                    return new HealthResponse(true, true, false, modelName, "degraded", "指定的模型不可用，将使用默认模型");
                                }
                                return new HealthResponse(true, true, false, modelName, "no-model", "AI Foundation 未配置默认模型，已添加模型需前往设置默认模型");
                            });
                    });
            })
            .defaultIfEmpty(new HealthResponse(true, false, false, "", "unhealthy", "AI Foundation 服务不可用"));
    }

    private Mono<String> getConfiguredModelName() {
        return client.fetch(ConfigMap.class, CONFIG_MAP_NAME)
            .mapNotNull(cm -> {
                var data = cm.getData();
                if (data == null) return null;
                String modelJson = data.get("model");
                if (modelJson == null || modelJson.isBlank()) return "";
                try {
                    JsonNode node = objectMapper.readTree(modelJson);
                    if (node.has("modelName") && !node.get("modelName").asText("").isBlank()) {
                        return node.get("modelName").asText("");
                    }
                } catch (Exception e) {
                    log.debug("[Endpoint] Failed to parse modelName: {}", e.getMessage());
                }
                return "";
            })
            .defaultIfEmpty("");
    }

    public record HealthResponse(
        boolean aiFoundationInstalled,
        boolean aiFoundationEnabled,
        boolean modelConfigured,
        String modelName,
        String status,
        String message
    ) {}

    private Mono<ServerResponse> listPersonas(ServerRequest request) {
        return client.listAll(AiPersona.class, ListOptions.builder().build(), Sort.unsorted())
            .collectList()
            .map(personas -> personas.stream()
                .sorted(Comparator.comparing(
                    (AiPersona p) -> p.getSpec() != null ? p.getSpec().getPriority() : null,
                    Comparator.nullsLast(Comparator.naturalOrder())
                ))
                .toList()
            )
            .flatMap(personas -> ServerResponse.ok().bodyValue(personas));
    }

    private Mono<ServerResponse> getPersonaByName(ServerRequest request) {
        var name = request.pathVariable("name");
        return client.fetch(AiPersona.class, name)
            .flatMap(persona -> ServerResponse.ok().bodyValue(persona))
            .switchIfEmpty(ServerResponse.notFound().build());
    }

    private Mono<ServerResponse> createPersona(ServerRequest request) {
        return request.bodyToMono(AiPersona.class)
            .flatMap(persona -> {
                if (persona.getMetadata() == null) {
                    persona.setMetadata(new run.halo.app.extension.Metadata());
                }
                if (persona.getMetadata().getName() == null || persona.getMetadata().getName().isBlank()) {
                    persona.getMetadata().setName("ai-persona-" + java.util.UUID.randomUUID().toString().substring(0, 8));
                }
                return client.create(persona)
                    .flatMap(created -> ServerResponse.ok().bodyValue(created))
                    .onErrorResume(e -> {
                        log.warn("Failed to create persona: {}", e.getMessage());
                        return ServerResponse.badRequest()
                            .bodyValue(Map.of("message", "创建角色失败: " + e.getMessage()));
                    });
            })
            .switchIfEmpty(ServerResponse.badRequest()
                .bodyValue(Map.of("message", "请求体不能为空")));
    }

    private Mono<ServerResponse> updatePersona(ServerRequest request) {
        var name = request.pathVariable("name");
        return request.bodyToMono(AiPersona.class)
            .flatMap(updatedPersona -> client.fetch(AiPersona.class, name)
                .flatMap(existing -> {
                    existing.setSpec(updatedPersona.getSpec());
                    return client.update(existing);
                })
                .retryWhen(Retry.backoff(3, Duration.ofMillis(100))
                    .filter(e -> e instanceof OptimisticLockingFailureException))
                .flatMap(saved -> ServerResponse.ok().bodyValue(saved))
                .onErrorResume(e -> {
                    log.warn("Failed to update persona {}: {}", name, e.getMessage());
                    return ServerResponse.badRequest()
                        .bodyValue(Map.of("message", "更新角色失败: " + e.getMessage()));
                })
            )
            .switchIfEmpty(ServerResponse.notFound().build());
    }

    private Mono<ServerResponse> deletePersona(ServerRequest request) {
        var name = request.pathVariable("name");
        return client.fetch(AiPersona.class, name)
            .flatMap(persona -> {
                if (persona.getSpec() != null && Boolean.TRUE.equals(persona.getSpec().getIsDefault())) {
                    return ServerResponse.badRequest()
                        .bodyValue(Map.of("message", "默认角色不可删除，请先将其他角色设为默认"));
                }
                return client.delete(persona)
                    .then(ServerResponse.ok().bodyValue(Map.of("message", "deleted")));
            })
            .switchIfEmpty(ServerResponse.notFound().build());
    }

    private Mono<ServerResponse> exportConfig(ServerRequest request) {
        var result = new java.util.LinkedHashMap<String, Object>();
        // 导出 ConfigMap
        return client.fetch(run.halo.app.extension.ConfigMap.class, CONFIG_MAP_NAME)
            .map(configMap -> {
                result.put("configMap", configMap.getData());
                return result;
            })
            .defaultIfEmpty(result)
            .flatMap(r -> {
                // 导出所有 AiPersona
                return client.list(AiPersona.class, null, null)
                    .collectList()
                    .map(personas -> {
                        r.put("personas", personas);
                        return r;
                    });
            })
            .flatMap(r -> ServerResponse.ok()
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(r));
    }

    private Mono<ServerResponse> importConfig(ServerRequest request) {
        return request.bodyToMono(java.util.Map.class)
            .flatMap(body -> {
                if (body == null || !body.containsKey("configMap") && !body.containsKey("personas")) {
                    return ServerResponse.badRequest()
                        .bodyValue(java.util.Map.of("error", "无效的配置格式"));
                }
                var results = new java.util.ArrayList<String>();
                Mono<Void> importMono = Mono.empty();

                // 导入 ConfigMap
                if (body.containsKey("configMap")) {
                    @SuppressWarnings("unchecked")
                    var configMapData = (java.util.Map<String, String>) body.get("configMap");
                    importMono = importMono.then(
                        client.fetch(run.halo.app.extension.ConfigMap.class, CONFIG_MAP_NAME)
                            .flatMap(existing -> {
                                existing.setData(configMapData);
                                return client.update(existing)
                                    .retryWhen(Retry.backoff(3, Duration.ofMillis(100))
                                        .filter(e -> e instanceof OptimisticLockingFailureException))
                                    .doOnSuccess(v -> results.add("ConfigMap 已更新"))
                                    .then();
                            })
                            .switchIfEmpty(Mono.defer(() -> {
                                run.halo.app.extension.ConfigMap cm = new run.halo.app.extension.ConfigMap();
                                cm.setMetadata(new run.halo.app.extension.Metadata());
                                cm.getMetadata().setName(CONFIG_MAP_NAME);
                                cm.setData(configMapData);
                                return client.create(cm)
                                    .doOnSuccess(v -> results.add("ConfigMap 已创建"))
                                    .then();
                            }))
                    );
                }

                // 导入 AiPersona
                if (body.containsKey("personas")) {
                    @SuppressWarnings("unchecked")
                    var personasList = (java.util.List<java.util.Map<String, Object>>) body.get("personas");
                    for (var personaData : personasList) {
                        importMono = importMono.then(Mono.defer(() -> {
                            try {
                                var personaJson = objectMapper.writeValueAsString(personaData);
                                var persona = objectMapper.readValue(personaJson, AiPersona.class);
                                var personaName = persona.getMetadata().getName();
                                // 清洗 metadata：仅保留 name，移除只读字段（creationTimestamp/finalizers/labels/annotations 等）
                                // 更新时通过 fetch 获取已有记录的 version，避免校验失败
                                return client.fetch(AiPersona.class, personaName)
                                    .flatMap(existing -> {
                                        // 保留已有记录的 version 以通过乐观锁校验
                                        persona.getMetadata().setVersion(existing.getMetadata().getVersion());
                                        // 移除只读字段，避免更新校验失败
                                        persona.getMetadata().setCreationTimestamp(null);
                                        persona.getMetadata().setFinalizers(null);
                                        persona.getMetadata().setLabels(null);
                                        persona.getMetadata().setAnnotations(null);
                                        persona.getMetadata().setGenerateName(null);
                                        persona.getMetadata().setDeletionTimestamp(null);
                                        return client.update(persona)
                                            .retryWhen(Retry.backoff(3, Duration.ofMillis(100))
                                                .filter(e -> e instanceof OptimisticLockingFailureException))
                                            .doOnSuccess(v -> results.add("角色 '" + persona.getSpec().getDisplayName() + "' 已更新"))
                                            .onErrorResume(e -> {
                                                results.add("角色 '" + persona.getSpec().getDisplayName() + "' 更新失败: " + e.getMessage());
                                                return Mono.empty();
                                            })
                                            .then();
                                    })
                                    .switchIfEmpty(Mono.defer(() -> {
                                        persona.getMetadata().setCreationTimestamp(null);
                                        persona.getMetadata().setFinalizers(null);
                                        persona.getMetadata().setGenerateName(null);
                                        persona.getMetadata().setDeletionTimestamp(null);
                                        persona.getMetadata().setVersion(null);
                                        persona.getMetadata().setLabels(null);
                                        persona.getMetadata().setAnnotations(null);
                                        return client.create(persona)
                                            .doOnSuccess(v -> results.add("角色 '" + persona.getSpec().getDisplayName() + "' 已创建"))
                                            .onErrorResume(e -> {
                                                results.add("角色 '" + persona.getSpec().getDisplayName() + "' 创建失败: " + e.getMessage());
                                                return Mono.empty();
                                            })
                                            .then();
                                    }));
                            } catch (Exception e) {
                                results.add("导入角色失败: " + e.getMessage());
                                return Mono.<Void>empty();
                            }
                        }));
                    }
                }

                return importMono.then(
                    ServerResponse.ok().bodyValue(java.util.Map.of("results", results))
                );
            })
            .onErrorResume(e -> {
                log.error("[Config] 导入配置失败", e);
                return ServerResponse.badRequest()
                    .bodyValue(java.util.Map.of("error", "导入失败: " + e.getMessage()));
            });
    }

    private Mono<ServerResponse> updateReplyContent(ServerRequest request) {
        var name = request.pathVariable("name");
        return request.bodyToMono(String.class)
            .flatMap(body -> {
                String newReply;
                try {
                    JsonNode node = objectMapper.readTree(body);
                    JsonNode replyNode = node.get("reply");
                    if (replyNode == null || replyNode.asText().isBlank()) {
                        return ServerResponse.badRequest()
                            .bodyValue(Map.of("message", "reply 字段不能为空"));
                    }
                    newReply = replyNode.asText();
                } catch (Exception e) {
                    return ServerResponse.badRequest()
                        .bodyValue(Map.of("message", "请求体格式错误"));
                }

                return client.fetch(AiCommentReply.class, name)
                    .flatMap(record -> {
                        // Only allow when published is false (draft mode)
                        if (Boolean.TRUE.equals(record.getSpec().getPublished())) {
                            return ServerResponse.badRequest()
                                .bodyValue(Map.of("message", "已发布的回复不可编辑"));
                        }

                        // Update AiCommentReply.spec.reply
                        return client.fetch(AiCommentReply.class, name)
                            .flatMap(latest -> {
                                latest.getSpec().setReply(newReply);
                                return client.update(latest);
                            })
                            .retryWhen(Retry.backoff(3, Duration.ofMillis(100))
                                .filter(e -> e instanceof OptimisticLockingFailureException))
                            .flatMap(updatedRecord -> {
                                String replyName = updatedRecord.getSpec().getReplyName();
                                if (replyName != null && !replyName.isBlank()) {
                                    // Reply extension exists, update its content too
                                    return client.fetch(Reply.class, replyName)
                                        .flatMap(reply -> {
                                            reply.getSpec().setRaw(newReply);
                                            reply.getSpec().setContent(newReply);
                                            return client.update(reply);
                                        })
                                        .retryWhen(Retry.backoff(3, Duration.ofMillis(100))
                                            .filter(e -> e instanceof OptimisticLockingFailureException))
                                        .then(Mono.defer(() -> client.fetch(AiCommentReply.class, name)));
                                }
                                // Draft mode: no Reply extension, only update AiCommentReply
                                return Mono.just(updatedRecord);
                            })
                            .flatMap(finalRecord -> ServerResponse.ok().bodyValue(finalRecord));
                    })
                    .switchIfEmpty(ServerResponse.notFound().build());
            });
    }

    /**
     * 误报反馈：将被拦截的评论标记为误报（正常），并可选触发 AI 回复。
     *
     * 请求体：{ "action": "aiReply" | "approveOnly" }
     * - aiReply: 将评论审核状态设为已通过 + 触发 AI 生成回复
     * - approveOnly: 仅将评论审核状态设为已通过，不触发 AI 回复
     */
    private Mono<ServerResponse> falsePositive(ServerRequest request) {
        var name = request.pathVariable("name");
        return request.bodyToMono(String.class)
            .flatMap(body -> {
                String actionStr;
                try {
                    JsonNode node = objectMapper.readTree(body);
                    actionStr = node.has("action") ? node.get("action").asText("approveOnly") : "approveOnly";
                } catch (Exception e) {
                    actionStr = "approveOnly";
                }
                final String action = actionStr;

                return client.fetch(AiCommentReply.class, name)
                    .flatMap(record -> {
                        String currentStatus = record.getSpec().getStatus();
                        // 允许：FILTERED（拦截误报）、FALSE_POSITIVE（已通过但可触发AI）、FAIL（AI生成失败可重试）
                        if (!"FILTERED".equals(currentStatus)
                            && !"FALSE_POSITIVE".equals(currentStatus)
                            && !"FAIL".equals(currentStatus)) {
                            return ServerResponse.badRequest()
                                .bodyValue(Map.of("message", "仅已拦截、误报通过或AI生成失败的记录可进行此操作"));
                        }

                        String commentName = record.getSpec().getCommentId();
                        String replyName = record.getSpec().getReplyTo();

                        // 1. 将原评论/回复的审核状态设为已通过
                        Mono<Void> approveMono = approveOriginalComment(commentName, replyName);

                        // 2. 更新 AiCommentReply 记录状态
                        Mono<Void> updateRecordMono = Mono.defer(() -> client.fetch(AiCommentReply.class, name)
                            .flatMap(latest -> {
                                latest.getSpec().setFilterCategory("误报");
                                latest.getSpec().setFilterReason("用户确认为误报，已通过");
                                if ("aiReply".equals(action)) {
                                    latest.getSpec().setStatus("PENDING");
                                    latest.getSpec().setReply("");
                                } else {
                                    // 仅通过：使用 FALSE_POSITIVE 状态，区别于 PASS
                                    // 避免前端显示"通过/拒绝"按钮和"未发布"标签
                                    latest.getSpec().setStatus("FALSE_POSITIVE");
                                    latest.getSpec().setPublished(false);
                                }
                                return client.update(latest);
                            })
                            .retryWhen(Retry.backoff(3, Duration.ofMillis(100))
                                .filter(e -> e instanceof OptimisticLockingFailureException))
                            .then());

                        // 3. 异步触发 AI 回复（在记录更新完成后，不阻塞 HTTP 响应）
                        // 使用 processFalsePositive 跳过前置过滤和去重检查
                        final boolean isConversation = Boolean.TRUE.equals(record.getSpec().getIsAiConversation());
                        final String recordName = record.getMetadata().getName();

                        return approveMono
                            .then(updateRecordMono)
                            .doOnSuccess(v -> {
                                if ("aiReply".equals(action)) {
                                    personaResolver.getPersonaNameFromComment(commentName)
                                        .flatMap(personaName ->
                                            orchestrator.processFalsePositive(commentName, replyName, isConversation, personaName, recordName)
                                        )
                                        .subscribe(
                                            null,
                                            err -> log.warn("[FalsePositive] AI reply trigger failed for {}: {}", commentName, err.getMessage()),
                                            () -> log.info("[FalsePositive] AI reply trigger completed for {}", commentName)
                                        );
                                }
                            })
                            .then(ServerResponse.ok().bodyValue(Map.of(
                                "message", "aiReply".equals(action) ? "已标记为误报，AI回复正在后台生成" : "已标记为误报并通过"
                            )));
                    })
                    .switchIfEmpty(ServerResponse.notFound().build());
            });
    }

    /**
     * 将被拦截评论的原 Comment 或 Reply 审核状态设为已通过。
     */
    private Mono<Void> approveOriginalComment(String commentName, String replyName) {
        // 优先处理 Reply（AI 对话场景下违规内容来自 Reply）
        if (replyName != null && !replyName.isBlank()) {
            return client.fetch(Reply.class, replyName)
                .flatMap(reply -> {
                    var spec = reply.getSpec();
                    if (spec != null && !Boolean.TRUE.equals(spec.getApproved())) {
                        spec.setApproved(true);
                        spec.setApprovedTime(Instant.now());
                        return client.update(reply)
                            .retryWhen(Retry.backoff(3, Duration.ofMillis(100))
                                .filter(e -> e instanceof OptimisticLockingFailureException))
                            .doOnSuccess(r -> log.info("[FalsePositive] Reply {} approved", replyName))
                            .then();
                    }
                    return Mono.empty();
                })
                .switchIfEmpty(Mono.defer(() -> approveComment(commentName)));
        }
        return approveComment(commentName);
    }

    private Mono<Void> approveComment(String commentName) {
        return client.fetch(Comment.class, commentName)
            .flatMap(comment -> {
                var spec = comment.getSpec();
                if (spec != null && !Boolean.TRUE.equals(spec.getApproved())) {
                    spec.setApproved(true);
                    spec.setApprovedTime(Instant.now());
                    return client.update(comment)
                        .retryWhen(Retry.backoff(3, Duration.ofMillis(100))
                            .filter(e -> e instanceof OptimisticLockingFailureException))
                        .doOnSuccess(c -> log.info("[FalsePositive] Comment {} approved", commentName))
                        .then();
                }
                return Mono.empty();
            });
    }

    /**
     * 查询瞬间插件是否已安装并启用。
     * 前端通过此接口判断是否显示"瞬间评论区适配"开关。
     */
    private Mono<ServerResponse> momentsStatus(ServerRequest request) {
        boolean available = momentsIntegrationService.isMomentsAvailable();
        return ServerResponse.ok().bodyValue(Map.of(
            "installed", available,
            "enabled", available
        ));
    }

    private int parseIntSafely(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
