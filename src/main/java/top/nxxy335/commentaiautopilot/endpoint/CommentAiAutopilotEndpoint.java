package top.nxxy335.commentaiautopilot.endpoint;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.Reply;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.PageRequestImpl;
import top.nxxy335.commentaiautopilot.extension.AiCommentReply;
import top.nxxy335.commentaiautopilot.extension.AiPersona;
import top.nxxy335.commentaiautopilot.service.AiFoundationClient;
import top.nxxy335.commentaiautopilot.service.AiReplyCleanupService;
import top.nxxy335.commentaiautopilot.service.AiReplyOrchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Sort;

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
    private final ObjectProvider<AiFoundationClient> aiFoundationClientProvider;
    private final ObjectMapper objectMapper;

    private static final String CONFIG_MAP_NAME = "comment-ai-autopilot-configmap";

    public CommentAiAutopilotEndpoint(ReactiveExtensionClient client, AiReplyOrchestrator orchestrator, AiReplyCleanupService cleanupService, ObjectProvider<AiFoundationClient> aiFoundationClientProvider) {
        this.client = client;
        this.orchestrator = orchestrator;
        this.cleanupService = cleanupService;
        this.aiFoundationClientProvider = aiFoundationClientProvider;
        this.objectMapper = new ObjectMapper();
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

        return client.listAll(AiCommentReply.class, ListOptions.builder().build(), Sort.unsorted())
            .collectList()
            .map(replies -> {
                var filtered = replies.stream()
                    .filter(r -> {
                        if (!statusFilter.isBlank()
                            && !statusFilter.equals(r.getSpec().getStatus())) {
                            return false;
                        }
                        if (!sentimentFilter.isBlank()
                            && !sentimentFilter.equals(r.getSpec().getSentiment())) {
                            return false;
                        }
                        if (!keywordFilter.isBlank()) {
                            String reply = r.getSpec().getReply();
                            if (reply == null || !reply.contains(keywordFilter)) {
                                return false;
                            }
                        }
                        return true;
                    })
                    .sorted(Comparator.comparing(
                        (AiCommentReply r) -> r.getMetadata().getCreationTimestamp(),
                        Comparator.nullsLast(Comparator.reverseOrder())
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

    private Mono<ServerResponse> deleteReply(ServerRequest request) {
        var name = request.pathVariable("name");
        return client.fetch(AiCommentReply.class, name)
            .flatMap(record -> client.delete(record))
            .then(ServerResponse.ok().bodyValue("{\"message\":\"deleted\"}"))
            .switchIfEmpty(ServerResponse.notFound().build());
    }

    private Mono<ServerResponse> getStats(ServerRequest request) {
        String range = request.queryParam("range").orElse("7");

        return client.listAll(AiCommentReply.class, ListOptions.builder().build(), Sort.unsorted())
            .collectList()
            .map(allReplies -> {
                // 根据 range 计算截止时间
                ZoneId zoneId = ZoneId.systemDefault();
                LocalDate today = LocalDate.now(zoneId);
                Instant cutoffInstant;
                int trendDays;

                if ("all".equals(range)) {
                    cutoffInstant = null; // 不做时间过滤
                    trendDays = 30; // "all" 时趋势也展示最近30天
                } else {
                    int days = Integer.parseInt(range);
                    cutoffInstant = today.minusDays(days).atStartOfDay(zoneId).toInstant();
                    trendDays = days;
                }

                // 根据 range 过滤记录
                List<AiCommentReply> replies;
                if (cutoffInstant != null) {
                    replies = allReplies.stream()
                        .filter(r -> {
                            Instant ts = r.getMetadata().getCreationTimestamp();
                            return ts != null && !ts.isBefore(cutoffInstant);
                        })
                        .toList();
                } else {
                    replies = allReplies;
                }

                long total = replies.size();
                long passCount = replies.stream()
                    .filter(r -> "PASS".equals(r.getSpec().getStatus())).count();
                long failCount = replies.stream()
                    .filter(r -> "FAIL".equals(r.getSpec().getStatus())).count();
                double avgScore = replies.stream()
                    .filter(r -> r.getSpec().getScore() != null && r.getSpec().getScore() > 0)
                    .mapToInt(r -> r.getSpec().getScore())
                    .average().orElse(0.0);

                long reviewingCount = replies.stream()
                    .filter(r -> "PASS".equals(r.getSpec().getStatus())
                        && !Boolean.TRUE.equals(r.getSpec().getPublished()))
                    .count();

                Map<String, Long> sentimentDistribution = new HashMap<>();
                sentimentDistribution.put("POSITIVE", 0L);
                sentimentDistribution.put("NEUTRAL", 0L);
                sentimentDistribution.put("NEGATIVE", 0L);
                sentimentDistribution.put("UNKNOWN", 0L);
                for (var r : replies) {
                    String sentiment = r.getSpec().getSentiment();
                    if (sentiment == null || sentiment.isBlank()) {
                        sentimentDistribution.merge("UNKNOWN", 1L, Long::sum);
                    } else {
                        sentimentDistribution.merge(sentiment, 1L, Long::sum);
                    }
                }

                // 计算 dailyTrend
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
                Map<LocalDate, Long> dailyMap = new HashMap<>();
                for (int i = 0; i < trendDays; i++) {
                    dailyMap.put(today.minusDays(i), 0L);
                }
                for (var r : replies) {
                    Instant timestamp = r.getMetadata().getCreationTimestamp();
                    if (timestamp != null) {
                        try {
                            LocalDate date = timestamp.atZone(zoneId).toLocalDate();
                            if (dailyMap.containsKey(date)) {
                                dailyMap.merge(date, 1L, Long::sum);
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
                List<DailyCount> dailyTrend = new ArrayList<>();
                for (int i = 0; i < trendDays; i++) {
                    LocalDate date = today.minusDays(i);
                    dailyTrend.add(new DailyCount(date.format(formatter), dailyMap.get(date)));
                }

                return new StatsResponse(total, passCount, failCount, avgScore,
                    reviewingCount, sentimentDistribution, dailyTrend);
            })
            .onErrorResume(e -> {
                log.warn("Failed to fetch stats: {}", e.getMessage());
                return Mono.just(new StatsResponse(0, 0, 0, 0.0, 0L,
                    Map.of("POSITIVE", 0L, "NEUTRAL", 0L, "NEGATIVE", 0L, "UNKNOWN", 0L),
                    List.of()));
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
                String avatarUrl = "";
                if (email != null && !email.isBlank()) {
                    try {
                        var digest = java.security.MessageDigest.getInstance("SHA-256");
                        var hashBytes = digest.digest(email.trim().toLowerCase().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        var hexString = new StringBuilder();
                        for (byte b : hashBytes) {
                            hexString.append(String.format("%02x", b));
                        }
                        avatarUrl = "https://cn.cravatar.com/avatar/" + hexString;
                    } catch (Exception ignored) {}
                }
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

    public record DailyCount(String date, long count) {}

    public record StatsResponse(
        long total,
        long passCount,
        long failCount,
        double avgScore,
        long reviewingCount,
        Map<String, Long> sentimentDistribution,
        List<DailyCount> dailyTrend
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

                var commentMsg = new ConversationMessage(
                    "comment", commentOwner, commentContent, commentTime, isCommentAi
                );

                return client.listAll(Reply.class, ListOptions.builder().build(), Sort.unsorted())
                    .filter(reply -> commentName.equals(reply.getSpec().getCommentName()))
                    .sort(Comparator.comparing(r -> r.getMetadata().getCreationTimestamp()))
                    .map(reply -> {
                        var replyOwner = extractOwnerName(reply.getSpec().getOwner());
                        var replyContent = extractContent(reply.getSpec().getRaw(), reply.getSpec().getContent());
                        var replyTime = String.valueOf(reply.getMetadata().getCreationTimestamp());
                        var isAi = isAiOwner(reply.getSpec().getOwner());
                        return new ConversationMessage("reply", replyOwner, replyContent, replyTime, isAi);
                    })
                    .collectList()
                    .map(replyList -> {
                        List<ConversationMessage> messages = new ArrayList<>();
                        messages.add(commentMsg);
                        messages.addAll(replyList);
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
                // Find the corresponding Reply and set approved=true
                return findReplyForRecord(record)
                    .flatMap(reply -> {
                        reply.getSpec().setApproved(true);
                        reply.getSpec().setApprovedTime(Instant.now());
                        return client.update(reply);
                    })
                    .then(Mono.defer(() -> {
                        // Update AiCommentReply record
                        return client.fetch(AiCommentReply.class, name)
                            .flatMap(latest -> {
                                latest.getSpec().setPublished(true);
                                return client.update(latest);
                            });
                    }))
                    .then(ServerResponse.ok().bodyValue(Map.of("message", "approved")));
            })
            .switchIfEmpty(ServerResponse.notFound().build());
    }

    private Mono<ServerResponse> rejectReply(ServerRequest request) {
        var name = request.pathVariable("name");
        return client.fetch(AiCommentReply.class, name)
            .flatMap(record -> {
                // Delete the draft Reply if it exists
                return findReplyForRecord(record)
                    .flatMap(reply -> client.delete(reply))
                    .then(Mono.defer(() -> {
                        // Update AiCommentReply record status to REJECTED
                        return client.fetch(AiCommentReply.class, name)
                            .flatMap(latest -> {
                                latest.getSpec().setStatus("REJECTED");
                                latest.getSpec().setPublished(false);
                                return client.update(latest);
                            });
                    }))
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
                            .flatMap(record -> findReplyForRecord(record)
                                .flatMap(reply -> {
                                    reply.getSpec().setApproved(true);
                                    reply.getSpec().setApprovedTime(Instant.now());
                                    return client.update(reply);
                                })
                                .then(Mono.defer(() -> client.fetch(AiCommentReply.class, name)
                                    .flatMap(latest -> {
                                        latest.getSpec().setPublished(true);
                                        return client.update(latest);
                                    })))
                                .thenReturn(true)
                            )
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
                            .flatMap(record -> findReplyForRecord(record)
                                .flatMap(reply -> client.delete(reply))
                                .then(Mono.defer(() -> client.fetch(AiCommentReply.class, name)
                                    .flatMap(latest -> {
                                        latest.getSpec().setStatus("REJECTED");
                                        latest.getSpec().setPublished(false);
                                        return client.update(latest);
                                    })))
                                .thenReturn(true)
                            )
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
                return getPersonaNameFromComment(commentName)
                    .flatMap(personaName ->
                        orchestrator.processComment(commentName, null, false, personaName)
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
                        return getPersonaNameFromComment(commentName)
                            .flatMap(personaName ->
                                orchestrator.processComment(commentName, replyName, true, personaName)
                                    .then(ServerResponse.ok().bodyValue(Map.of("message", "已触发AI对话回复")))
                            );
                    });
            })
            .switchIfEmpty(ServerResponse.notFound().build());
    }

    private static final String AI_PERSONA_ANNOTATION = "comment-ai-autopilot.nxxy335.top/ai-persona";

    private Mono<String> getPersonaNameFromComment(String commentName) {
        return client.fetch(Comment.class, commentName)
            .flatMap(comment -> {
                var subjectRef = comment.getSpec().getSubjectRef();
                if (subjectRef == null || !"Post".equals(subjectRef.getKind())) {
                    return Mono.justOrEmpty(null);
                }
                String postName = subjectRef.getName();
                return client.fetch(Post.class, postName)
                    .mapNotNull(post -> {
                        var annotations = post.getMetadata().getAnnotations();
                        if (annotations != null) {
                            String persona = annotations.get(AI_PERSONA_ANNOTATION);
                            if (persona != null && !persona.isBlank()) {
                                return persona;
                            }
                        }
                        return null;
                    });
            })
            .defaultIfEmpty("");
    }

    private Mono<Reply> findReplyForRecord(AiCommentReply record) {
        // Find the Reply that belongs to the same comment and was created by AI
        // If the record has a quoteReply, match by that too for precision
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
        boolean isAi
    ) {}

    public record CommenterInfo(
        String displayName,
        String email
    ) {}

    private Mono<ServerResponse> listCommenters(ServerRequest request) {
        return client.listAll(Comment.class, ListOptions.builder().build(), Sort.unsorted())
            .collectList()
            .map(comments -> {
                Set<String> seen = new HashSet<>();
                List<CommenterInfo> result = new ArrayList<>();
                for (var comment : comments) {
                    var owner = comment.getSpec() != null ? comment.getSpec().getOwner() : null;
                    if (owner == null) continue;
                    String displayName = owner.getDisplayName() != null ? owner.getDisplayName() : "";
                    String email = "EMAIL".equals(owner.getKind()) && owner.getName() != null
                        ? owner.getName() : "";
                    String key = displayName.toLowerCase() + "|" + email.toLowerCase();
                    if (seen.add(key)) {
                        result.add(new CommenterInfo(displayName, email));
                    }
                }
                return result;
            })
            .flatMap(commenters -> ServerResponse.ok().bodyValue(commenters));
    }

    private Mono<ServerResponse> triggerCleanup(ServerRequest request) {
        return Mono.fromCallable(() -> {
                int retentionDays = cleanupService.getRetentionDays();
                long deleted = cleanupService.executeCleanup(retentionDays);
                return Map.of("deletedCount", deleted, "retentionDays", retentionDays);
            })
            .flatMap(result -> ServerResponse.ok().bodyValue(result))
            .onErrorResume(e -> {
                log.warn("Failed to trigger cleanup: {}", e.getMessage());
                return ServerResponse.status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
                    .bodyValue(Map.of("message", "清理失败: " + e.getMessage()));
            });
    }

    private Mono<ServerResponse> health(ServerRequest request) {
        AiFoundationClient aiClient = aiFoundationClientProvider.getIfAvailable();
        boolean aiFoundationInstalled = aiClient != null;

        if (!aiFoundationInstalled) {
            return ServerResponse.ok().bodyValue(
                new HealthResponse(false, false, false, "", "unhealthy"));
        }

        // AI Foundation is installed, check if it's enabled and model is available
        return aiClient.chat("ping", null)
            .map(response -> (HealthResponse) new HealthResponse(true, true, true, "default", "healthy"))
            .onErrorResume(e -> {
                log.debug("Health check: AI Foundation call failed: {}", e.getMessage());
                return Mono.just(new HealthResponse(true, true, false, "", "degraded"));
            })
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
}
