package top.nxxy335.commentaiautopilot.endpoint;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
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
import top.nxxy335.commentaiautopilot.service.CommentReplyPublisher;
import top.nxxy335.commentaiautopilot.service.PersonaResolver;
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

    private static final String CONFIG_MAP_NAME = "comment-ai-autopilot-configmap";

    public CommentAiAutopilotEndpoint(ReactiveExtensionClient client, AiReplyOrchestrator orchestrator, AiReplyCleanupService cleanupService, AiFoundationClient aiFoundationClient, CommentReplyPublisher commentReplyPublisher, ObjectMapper objectMapper, PersonaResolver personaResolver) {
        this.client = client;
        this.orchestrator = orchestrator;
        this.cleanupService = cleanupService;
        this.aiFoundationClient = aiFoundationClient;
        this.commentReplyPublisher = commentReplyPublisher;
        this.objectMapper = objectMapper;
        this.personaResolver = personaResolver;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route()
            .GET("/replies", this::listReplies)
            .POST("/replies/batch-approve", this::batchApproveReplies)
            .POST("/replies/batch-reject", this::batchRejectReplies)
            .POST("/replies/batch-delete", this::batchDeleteReplies)
            .DELETE("/replies/{name}", this::deleteReply)
            .GET("/stats", this::getStats)
            .GET("/persona", this::getPersona)
            .GET("/conversation/{commentName}", this::getConversation)
            .POST("/replies/{name}/approve", this::approveReply)
            .POST("/replies/{name}/reject", this::rejectReply)
            .POST("/comments/{commentName}/trigger", this::triggerReply)
            .POST("/replies/{replyName}/trigger-conversation", this::triggerConversationReply)
            .GET("/commenters", this::listCommenters)
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
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.comment-ai-autopilot.nxxy335.top", "v1alpha1");
    }

    private Mono<ServerResponse> listReplies(ServerRequest request) {
        var page = Integer.parseInt(request.queryParam("page").orElse("1"));
        var size = Integer.parseInt(request.queryParam("size").orElse("20"));
        var statusFilter = request.queryParam("status").orElse("");
        var sentimentFilter = request.queryParam("sentiment").orElse("");
        var keywordFilter = request.queryParam("keyword").orElse("");
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
                                if (reply == null || !reply.contains(keywordFilter)) return false;
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

    private Mono<ServerResponse> getStats(ServerRequest request) {
        return client.listAll(AiCommentReply.class, ListOptions.builder().build(), Sort.unsorted())
            .collectList()
            .map(replies -> {
                long total = replies.size();
                long passCount = replies.stream()
                    .filter(r -> "PASS".equals(r.getSpec().getStatus())).count();
                long failCount = replies.stream()
                    .filter(r -> "FAIL".equals(r.getSpec().getStatus())).count();

                long reviewingCount = replies.stream()
                    .filter(r -> "PASS".equals(r.getSpec().getStatus())
                        && !Boolean.TRUE.equals(r.getSpec().getPublished()))
                    .count();

                return new StatsResponse(total, passCount, failCount, reviewingCount);
            })
            .onErrorResume(e -> {
                log.warn("Failed to fetch stats: {}", e.getMessage());
                return Mono.just(new StatsResponse(0, 0, 0, 0));
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
        long reviewingCount
    ) {}

    public record PersonaResponse(
        String name,
        String prompt,
        String avatar
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
                    )
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
                    )
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
                    )
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

    private Mono<ServerResponse> triggerCleanup(ServerRequest request) {
        return cleanupService.getRetentionDays()
            .flatMap(retentionDays -> cleanupService.executeCleanup(retentionDays)
                .map(deleted -> Map.of("deletedCount", deleted, "retentionDays", retentionDays))
            )
            .flatMap(result -> ServerResponse.ok().bodyValue(result))
            .onErrorResume(e -> {
                log.warn("Failed to trigger cleanup: {}", e.getMessage());
                return ServerResponse.status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
                    .bodyValue(Map.of("message", "清理失败: " + e.getMessage()));
            });
    }

    private Mono<ServerResponse> health(ServerRequest request) {
        // AiFoundationClient is always created; availability is checked at runtime
        return aiFoundationClient.isAvailable()
            .map(available -> (HealthResponse) new HealthResponse(available, available, available, "", available ? "healthy" : "degraded"))
            .defaultIfEmpty(new HealthResponse(false, false, false, "", "unhealthy"))
            .flatMap(health -> ServerResponse.ok().bodyValue(health));
    }

    public record HealthResponse(
        boolean aiFoundationInstalled,
        boolean aiFoundationEnabled,
        boolean modelAvailable,
        String modelName,
        String status
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
                                return client.fetch(AiPersona.class, personaName)
                                    .flatMap(existing -> {
                                        persona.getMetadata().setVersion(existing.getMetadata().getVersion());
                                        return client.update(persona)
                                            .retryWhen(Retry.backoff(3, Duration.ofMillis(100))
                                                .filter(e -> e instanceof OptimisticLockingFailureException))
                                            .doOnSuccess(v -> results.add("角色 '" + persona.getSpec().getDisplayName() + "' 已更新"))
                                            .then();
                                    })
                                    .switchIfEmpty(client.create(persona)
                                        .doOnSuccess(v -> results.add("角色 '" + persona.getSpec().getDisplayName() + "' 已创建"))
                                        .then());
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
            .onErrorResume(e -> ServerResponse.badRequest()
                .bodyValue(java.util.Map.of("error", "导入失败: " + e.getMessage())));
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
                        if (!"FILTERED".equals(record.getSpec().getStatus())
                            && !"FALSE_POSITIVE".equals(record.getSpec().getStatus())) {
                            return ServerResponse.badRequest()
                                .bodyValue(Map.of("message", "仅已拦截或误报通过状态的记录可进行误报反馈"));
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

                        // 3. 异步触发 AI 回复（不阻塞 HTTP 响应）
                        // 使用 processFalsePositive 跳过前置过滤和去重检查
                        if ("aiReply".equals(action)) {
                            boolean isConversation = Boolean.TRUE.equals(record.getSpec().getIsAiConversation());
                            String recordName = record.getMetadata().getName();
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

                        return approveMono
                            .then(updateRecordMono)
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
}
