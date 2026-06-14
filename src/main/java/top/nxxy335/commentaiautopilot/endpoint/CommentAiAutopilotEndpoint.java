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
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.PageRequestImpl;
import top.nxxy335.commentaiautopilot.extension.AiCommentReply;
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
    private final ObjectMapper objectMapper;

    private static final String CONFIG_MAP_NAME = "comment-ai-autopilot-configmap";

    public CommentAiAutopilotEndpoint(ReactiveExtensionClient client, AiReplyOrchestrator orchestrator, AiReplyCleanupService cleanupService) {
        this.client = client;
        this.orchestrator = orchestrator;
        this.cleanupService = cleanupService;
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
        return client.listAll(AiCommentReply.class, ListOptions.builder().build(), Sort.unsorted())
            .collectList()
            .map(replies -> {
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

                ZoneId zoneId = ZoneId.systemDefault();
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
                LocalDate today = LocalDate.now(zoneId);
                Map<LocalDate, Long> dailyMap = new HashMap<>();
                for (int i = 0; i < 7; i++) {
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
                for (int i = 0; i < 7; i++) {
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
        return client.fetch(ConfigMap.class, CONFIG_MAP_NAME)
            .mapNotNull(cm -> {
                var data = cm.getData();
                if (data == null) return new PersonaResponse("小回", "", "");
                String personaJson = data.get("persona");
                if (personaJson == null || personaJson.isBlank()) return new PersonaResponse("小回", "", "");
                try {
                    JsonNode node = objectMapper.readTree(personaJson);
                    String name = node.has("personaName") ? node.get("personaName").asText("小回") : "小回";
                    String prompt = node.has("personaPrompt") ? node.get("personaPrompt").asText("") : "";
                    String email = node.has("personaEmail") ? node.get("personaEmail").asText("") : "";
                    return new PersonaResponse(name, prompt, email);
                } catch (Exception e) {
                    log.warn("Failed to parse persona config: {}", e.getMessage());
                    return new PersonaResponse("小回", "", "");
                }
            })
            .defaultIfEmpty(new PersonaResponse("小回", "", ""))
            .onErrorResume(e -> {
                log.warn("Failed to fetch persona settings: {}", e.getMessage());
                return Mono.just(new PersonaResponse("小回", "", ""));
            })
            .flatMap(persona -> ServerResponse.ok().bodyValue(persona));
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
                // Trigger the orchestrator
                return orchestrator.processComment(commentName, null, false)
                    .then(ServerResponse.ok().bodyValue(Map.of("message", "已触发AI回复")));
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
                        return orchestrator.processComment(commentName, replyName, true)
                            .then(ServerResponse.ok().bodyValue(Map.of("message", "已触发AI对话回复")));
                    });
            })
            .switchIfEmpty(ServerResponse.notFound().build());
    }

    private Mono<Reply> findReplyForRecord(AiCommentReply record) {
        // Find the Reply that belongs to the same comment and was created by AI
        return client.list(Reply.class,
                reply -> {
                    if (!record.getSpec().getCommentId().equals(reply.getSpec().getCommentName())) {
                        return false;
                    }
                    var owner = reply.getSpec().getOwner();
                    if (owner == null) return false;
                    var annotations = owner.getAnnotations();
                    return annotations != null && "true".equals(annotations.get("comment-ai-autopilot.nxxy335.top/is-ai"));
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
}
