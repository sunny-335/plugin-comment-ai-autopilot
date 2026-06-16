package top.nxxy335.commentaiautopilot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import run.halo.app.core.extension.content.Reply;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import top.nxxy335.commentaiautopilot.extension.AiCommentReply;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class AiReplyOrchestrator {

    private static final String CONFIG_MAP_NAME = "comment-ai-autopilot-configmap";

    private final ContextExtractor contextExtractor;
    private final PromptBuilder promptBuilder;
    private final AiReplyService aiReplyService;
    private final SentimentService sentimentService;
    private final ReviewService reviewService;
    private final CommentReplyPublisher commentReplyPublisher;
    private final FilterService filterService;
    private final RateLimitService rateLimitService;
    private final ReactiveExtensionClient client;
    private final ObjectMapper objectMapper;

    // In-memory dedup: tracks which comment/reply is currently being processed
    // Prevents duplicate replies when Reconciler fires multiple times
    // Value is the timestamp when the lock was acquired, used for leak detection
    private final ConcurrentHashMap<String, Long> processingLocks = new ConcurrentHashMap<>();

    private static final long LOCK_EXPIRY_MS = 2 * 60 * 1000L; // 2 minutes

    public AiReplyOrchestrator(ContextExtractor contextExtractor,
                               PromptBuilder promptBuilder,
                               AiReplyService aiReplyService,
                               SentimentService sentimentService,
                               ReviewService reviewService,
                               CommentReplyPublisher commentReplyPublisher,
                               FilterService filterService,
                               RateLimitService rateLimitService,
                               ReactiveExtensionClient client) {
        this.contextExtractor = contextExtractor;
        this.promptBuilder = promptBuilder;
        this.aiReplyService = aiReplyService;
        this.sentimentService = sentimentService;
        this.reviewService = reviewService;
        this.commentReplyPublisher = commentReplyPublisher;
        this.filterService = filterService;
        this.rateLimitService = rateLimitService;
        this.client = client;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Process a new comment or reply.
     *
     * @param commentName     the parent Comment name
     * @param replyName       the Reply name that triggered this (null for top-level comments)
     * @param isAiConversation true when someone replied to AI's reply (conversation continuation)
     * @param personaName     the persona name to use (null for default persona)
     */
    public Mono<Void> processComment(String commentName, String replyName, boolean isAiConversation,
                                      String personaName) {
        String lockKey = isAiConversation ? commentName + ":conv:" + replyName : commentName + ":top";

        // Clean up stale locks before acquiring new one
        cleanupStaleLocks();

        // In-memory dedup: if already processing, skip immediately
        if (processingLocks.putIfAbsent(lockKey, System.currentTimeMillis()) != null) {
            log.info("[Orchestrator] Already processing: {}, skipping duplicate", lockKey);
            return Mono.empty();
        }

        log.info("[Orchestrator] Start processing: comment={}, replyName={}, isAiConversation={}, personaName={}",
            commentName, replyName, isAiConversation, personaName);

        return isAutoReplyEnabled()
            .flatMap(enabled -> {
                if (!enabled) {
                    log.info("[Orchestrator] Auto reply disabled, skipping: {}", commentName);
                    return Mono.empty();
                }
                return getRateLimit()
                    .flatMap(rateLimit -> {
                        if (!rateLimitService.tryAcquire(rateLimit)) {
                            log.info("[Orchestrator] 速率限制，跳过: {}", commentName);
                            return Mono.empty();
                        }
                        return filterService.shouldProcess(commentName)
                            .flatMap(shouldProcess -> {
                                if (!shouldProcess) {
                                    log.info("[Orchestrator] Filtered out by rules: {}", commentName);
                                    return Mono.empty();
                                }
                        // For top-level comments: skip if we already have ANY reply record
                        // For AI conversation: skip if we already replied to THIS specific reply
                        if (!isAiConversation) {
                            return hasExistingReply(commentName)
                                .flatMap(hasReply -> {
                                    if (hasReply) {
                                        log.info("[Orchestrator] Already have reply record for: {}, skipping", commentName);
                                        return Mono.empty();
                                    }
                                    return doProcess(commentName, replyName, isAiConversation, personaName);
                                });
                        }
                        // Check conversation rounds limit
                        return getMaxConversationRounds()
                            .flatMap(maxRounds -> getConversationRounds(commentName)
                                .flatMap(rounds -> {
                                    if (rounds >= maxRounds) {
                                        log.info("[Orchestrator] 对话轮次已达上限({}/{}), 跳过: {}", rounds, maxRounds, commentName);
                                        return Mono.empty();
                                    }
                                    return hasExistingConversationReply(replyName)
                                        .flatMap(hasReply -> {
                                            if (hasReply) {
                                                log.info("[Orchestrator] Already replied to reply: {}, skipping", replyName);
                                                return Mono.empty();
                                            }
                                            return doProcess(commentName, replyName, isAiConversation, personaName);
                                        });
                                })
                            );
                            });
                    });
            })
            .doOnError(e -> log.error("[Orchestrator] Error processing comment {}: {}", commentName, e.getMessage(), e))
            .doFinally(signal -> {
                // Always release the lock when processing completes
                processingLocks.remove(lockKey);
                log.debug("[Orchestrator] Released processing lock for: {}", lockKey);
            })
            .then();
    }

    private Mono<Void> doProcess(String commentName, String replyName, boolean isAiConversation,
                                  String personaName) {
        return getModelName().flatMap(modelName ->
            contextExtractor.extract(commentName, replyName, isAiConversation)
                .flatMap(context -> sentimentService.analyzeSentiment(context.commentContent(), modelName)
                    .flatMap(sentimentResult -> {
                        log.info("[Orchestrator] Sentiment for {}: {} (confidence: {})",
                            commentName, sentimentResult.sentiment(), sentimentResult.confidence());
                        return promptBuilder.buildPrompt(context, sentimentResult.sentiment(), personaName)
                            .flatMap(prompt -> createAiCommentReply(context, sentimentResult.sentiment(), personaName)
                                .flatMap(replyRecord -> generateAndPublish(prompt, context, replyRecord, modelName, personaName))
                            );
                    })
                )
        );
    }

    /**
     * Check if there's already ANY AiCommentReply record for this top-level comment.
     * Checks for ANY record (not just published) to prevent race conditions.
     */
    private Mono<Boolean> hasExistingReply(String commentName) {
        return client.list(AiCommentReply.class,
                record -> commentName.equals(record.getSpec().getCommentId())
                    && !Boolean.TRUE.equals(record.getSpec().getIsAiConversation()),
                null)
            .hasElements()
            .defaultIfEmpty(false)
            .onErrorResume(e -> {
                log.debug("[Orchestrator] Failed to check existing replies: {}", e.getMessage());
                return Mono.just(false);
            });
    }

    /**
     * Check if we already have ANY AiCommentReply record for this specific reply (conversation).
     * Checks for ANY record (not just published) to prevent race conditions.
     */
    private Mono<Boolean> hasExistingConversationReply(String replyName) {
        if (replyName == null || replyName.isBlank()) {
            return Mono.just(false);
        }
        return client.list(AiCommentReply.class,
                record -> replyName.equals(record.getSpec().getReplyTo())
                    && Boolean.TRUE.equals(record.getSpec().getIsAiConversation()),
                null)
            .hasElements()
            .defaultIfEmpty(false)
            .onErrorResume(e -> {
                log.debug("[Orchestrator] Failed to check existing conversation replies: {}", e.getMessage());
                return Mono.just(false);
            });
    }

    /**
     * Generate AI reply, optionally review it, then publish.
     * Includes retry logic for empty AI replies and review failures.
     */
    private Mono<Void> generateAndPublish(String prompt, ContextExtractor.CommentContext context,
                                           AiCommentReply replyRecord, String modelName,
                                           String personaName) {
        return aiReplyService.generateReply(prompt, modelName)
            .defaultIfEmpty("")
            .flatMap(aiReply -> {
                if (aiReply.isBlank()) {
                    log.warn("[Orchestrator] AI generated empty reply for: {}", context.commentId());
                    return retryOrFail(replyRecord, context, modelName, personaName, "AI generated empty reply");
                }

                log.info("[Orchestrator] AI generated reply for {}: {} chars",
                    context.commentId(), aiReply.length());

                return reviewService.review(context.postContent(), context.commentContent(), aiReply, modelName)
                    .flatMap(reviewResult -> {
                        log.info("[Orchestrator] Review for {}: score={}, status={}, reason={}",
                            context.commentId(), reviewResult.score(), reviewResult.status(), reviewResult.reason());
                        if ("FAIL".equals(reviewResult.status())) {
                            log.warn("[Orchestrator] Content safety review FAILED for: {}, not publishing",
                                context.commentId());
                            // Save the failed reply content, then retry
                            return updateRecord(replyRecord, aiReply, 0, "FAIL", false, null)
                                .then(retryOrFail(replyRecord, context, modelName, personaName, "Content safety review failed"));
                        }
                        return publishReply(context, aiReply, replyRecord, reviewResult.score(), personaName);
                    })
                    .switchIfEmpty(
                        publishReply(context, aiReply, replyRecord, 100, personaName)
                    )
                    .onErrorResume(e -> {
                        log.warn("[Orchestrator] Review error, auto-passing: {}", e.getMessage());
                        return publishReply(context, aiReply, replyRecord, 100, personaName);
                    });
            });
    }

    /**
     * Decide whether to retry or mark as final FAIL.
     * If retryCount < maxRetryCount, increment retryCount, set status to PENDING,
     * delay with exponential backoff, then re-execute the generate+review+publish flow.
     * Otherwise, mark as final FAIL.
     */
    private Mono<Void> retryOrFail(AiCommentReply replyRecord,
                                    ContextExtractor.CommentContext context,
                                    String modelName,
                                    String personaName,
                                    String reason) {
        return getMaxRetryCount().flatMap(maxRetry -> {
            int currentRetryCount = replyRecord.getSpec().getRetryCount() != null
                ? replyRecord.getSpec().getRetryCount() : 0;

            if (currentRetryCount < maxRetry) {
                int newRetryCount = currentRetryCount + 1;
                long delaySeconds = 5L * (1L << currentRetryCount); // 5 * 2^retryCount
                log.info("[Orchestrator] Retrying ({}/{}) for {} after {}s, reason: {}",
                    newRetryCount, maxRetry, context.commentId(), delaySeconds, reason);

                // Update retryCount and reset status to PENDING
                return updateRecordForRetry(replyRecord, newRetryCount)
                    .delayElement(Duration.ofSeconds(delaySeconds))
                    .then(retryGenerate(context, replyRecord, modelName, personaName));
            } else {
                log.warn("[Orchestrator] Max retry count ({}) exceeded for: {}, marking as FAIL. Reason: {}",
                    maxRetry, context.commentId(), reason);
                return updateRecord(replyRecord, "", 0, "FAIL", false, null).then();
            }
        });
    }

    /**
     * Re-execute the core generate+review+publish flow for a retry.
     * Rebuilds the prompt from context and sentiment, then calls generateAndPublish again.
     */
    private Mono<Void> retryGenerate(ContextExtractor.CommentContext context,
                                      AiCommentReply replyRecord,
                                      String modelName,
                                      String personaName) {
        return sentimentService.analyzeSentiment(context.commentContent(), modelName)
            .flatMap(sentimentResult -> promptBuilder.buildPrompt(context, sentimentResult.sentiment(), personaName)
                .flatMap(prompt -> generateAndPublish(prompt, context, replyRecord, modelName, personaName))
            );
    }

    /**
     * Update the record's retryCount and reset status to PENDING for a retry attempt.
     */
    private Mono<AiCommentReply> updateRecordForRetry(AiCommentReply record, int newRetryCount) {
        return client.fetch(AiCommentReply.class, record.getMetadata().getName())
            .flatMap(latest -> {
                latest.getSpec().setRetryCount(newRetryCount);
                latest.getSpec().setStatus("PENDING");
                latest.getSpec().setReply("");
                latest.getSpec().setScore(0);
                latest.getSpec().setPublished(false);
                return client.update(latest);
            })
            .retryWhen(Retry.backoff(3, Duration.ofMillis(100))
                .filter(e -> e instanceof OptimisticLockingFailureException)
                .doBeforeRetry(signal -> log.debug("[Orchestrator] Retrying retry-update for {} due to optimistic lock",
                    record.getMetadata().getName()))
            )
            .doOnSuccess(updated -> log.debug("[Orchestrator] Record {} updated for retry: retryCount={}",
                record.getMetadata().getName(), newRetryCount));
    }

    /**
     * Publish the reply and update the record.
     * When autoPublish=true: create Reply extension and save replyName.
     * When autoPublish=false (draft mode): do NOT create Reply extension, only save AI reply content.
     */
    private Mono<Void> publishReply(ContextExtractor.CommentContext context, String aiReply,
                                     AiCommentReply replyRecord, int score, String personaName) {
        return isAutoPublishEnabled()
            .flatMap(autoPublish -> {
                if (autoPublish) {
                    // Auto publish: create Reply extension and save replyName
                    return commentReplyPublisher.publishReply(
                            context.commentId(), aiReply, context.postId(), context.replyTo(), true, personaName)
                        .flatMap(publishedReply -> {
                            String replyName = publishedReply.getMetadata().getName();
                            log.info("[Orchestrator] Reply published for: {}, replyName={}", context.commentId(), replyName);
                            return updateRecord(replyRecord, aiReply, score, "PASS", true, replyName);
                        })
                        .switchIfEmpty(Mono.defer(() -> {
                            // publishReply returned empty (dedup: AI reply already exists)
                            // Fallback to draft mode to avoid leaving record in PENDING state
                            log.warn("[Orchestrator] publishReply returned empty for {}, falling back to draft mode", context.commentId());
                            return updateRecord(replyRecord, aiReply, score, "PASS", false, null);
                        }))
                        .flatMap(updated -> Mono.empty());
                } else {
                    // Draft mode: do NOT create Reply extension, only save AI reply content
                    log.info("[Orchestrator] Draft mode: saving reply content without creating Reply extension for: {}", context.commentId());
                    return updateRecord(replyRecord, aiReply, score, "PASS", false, null)
                        .flatMap(updated -> Mono.empty());
                }
            })
            .then();
    }

    private Mono<String> getModelName() {
        return client.fetch(ConfigMap.class, CONFIG_MAP_NAME)
            .mapNotNull(cm -> {
                var data = cm.getData();
                if (data == null) return null;
                String modelJson = data.get("model");
                if (modelJson == null || modelJson.isBlank()) return null;
                try {
                    JsonNode node = objectMapper.readTree(modelJson);
                    JsonNode nameNode = node.get("modelName");
                    if (nameNode != null && !nameNode.asText().isBlank()) {
                        return nameNode.asText();
                    }
                } catch (Exception e) {
                    log.warn("[Orchestrator] Failed to parse modelName from ConfigMap: {}", e.getMessage());
                }
                return null;
            })
            .onErrorResume(e -> {
                log.debug("[Orchestrator] Failed to fetch model setting from ConfigMap: {}", e.getMessage());
                return Mono.empty();
            })
            .defaultIfEmpty("");
    }

    private Mono<Boolean> isAutoReplyEnabled() {
        return client.fetch(ConfigMap.class, CONFIG_MAP_NAME)
            .mapNotNull(cm -> {
                var data = cm.getData();
                if (data == null) return null;
                String basicJson = data.get("basic");
                if (basicJson == null || basicJson.isBlank()) return null;
                try {
                    JsonNode node = objectMapper.readTree(basicJson);
                    if (!node.has("autoReply")) {
                        return true;
                    }
                    return node.get("autoReply").asBoolean(true);
                } catch (Exception e) {
                    log.warn("[Orchestrator] Failed to parse autoReply from ConfigMap: {}", e.getMessage());
                    return null;
                }
            })
            .onErrorResume(e -> {
                log.debug("[Orchestrator] Failed to fetch autoReply setting from ConfigMap: {}", e.getMessage());
                return Mono.empty();
            })
            .defaultIfEmpty(true);
    }

    private Mono<Boolean> isAutoPublishEnabled() {
        return client.fetch(ConfigMap.class, CONFIG_MAP_NAME)
            .mapNotNull(cm -> {
                var data = cm.getData();
                if (data == null) return null;
                String basicJson = data.get("basic");
                if (basicJson == null || basicJson.isBlank()) return null;
                try {
                    JsonNode node = objectMapper.readTree(basicJson);
                    if (!node.has("autoPublish")) {
                        return true;
                    }
                    return node.get("autoPublish").asBoolean(true);
                } catch (Exception e) {
                    log.warn("[Orchestrator] Failed to parse autoPublish from ConfigMap: {}", e.getMessage());
                    return null;
                }
            })
            .onErrorResume(e -> {
                log.debug("[Orchestrator] Failed to fetch autoPublish setting from ConfigMap: {}", e.getMessage());
                return Mono.empty();
            })
            .defaultIfEmpty(true);
    }

    private Mono<Integer> getMaxRetryCount() {
        return client.fetch(ConfigMap.class, CONFIG_MAP_NAME)
            .mapNotNull(cm -> {
                var data = cm.getData();
                if (data == null) return null;
                String basicJson = data.get("basic");
                if (basicJson == null || basicJson.isBlank()) return null;
                try {
                    JsonNode node = objectMapper.readTree(basicJson);
                    if (node.has("maxRetryCount")) {
                        return node.get("maxRetryCount").asInt(3);
                    }
                } catch (Exception e) {
                    log.warn("[Orchestrator] Failed to parse maxRetryCount from ConfigMap: {}", e.getMessage());
                }
                return null;
            })
            .onErrorResume(e -> {
                log.debug("[Orchestrator] Failed to fetch maxRetryCount setting from ConfigMap: {}", e.getMessage());
                return Mono.empty();
            })
            .defaultIfEmpty(3);
    }

    private Mono<Integer> getMaxConversationRounds() {
        return client.fetch(ConfigMap.class, CONFIG_MAP_NAME)
            .mapNotNull(cm -> {
                var data = cm.getData();
                if (data == null) return null;
                String basicJson = data.get("basic");
                if (basicJson == null || basicJson.isBlank()) return null;
                try {
                    JsonNode node = objectMapper.readTree(basicJson);
                    if (node.has("maxConversationRounds")) {
                        return node.get("maxConversationRounds").asInt(8);
                    }
                } catch (Exception e) {
                    log.warn("[Orchestrator] Failed to parse maxConversationRounds from ConfigMap: {}", e.getMessage());
                }
                return null;
            })
            .onErrorResume(e -> {
                log.debug("[Orchestrator] Failed to fetch maxConversationRounds setting from ConfigMap: {}", e.getMessage());
                return Mono.empty();
            })
            .defaultIfEmpty(8);
    }

    private Mono<Integer> getConversationRounds(String commentName) {
        return client.list(Reply.class,
                reply -> {
                    if (!commentName.equals(reply.getSpec().getCommentName())) {
                        return false;
                    }
                    var owner = reply.getSpec().getOwner();
                    if (owner == null) return false;
                    // Check AI annotation marker (CommentReplyPublisher sets this on all AI replies)
                    var annotations = owner.getAnnotations();
                    return annotations != null
                        && "true".equals(annotations.get("comment-ai-autopilot.nxxy335.top/is-ai"));
                },
                null)
            .collectList()
            .map(replies -> replies.size())
            .onErrorResume(e -> {
                log.warn("[Orchestrator] Failed to count conversation rounds: {}", e.getMessage());
                return Mono.just(0);
            });
    }

    private Mono<Integer> getRateLimit() {
        return client.fetch(ConfigMap.class, CONFIG_MAP_NAME)
            .mapNotNull(cm -> {
                var data = cm.getData();
                if (data == null) return null;
                String basicJson = data.get("basic");
                if (basicJson == null || basicJson.isBlank()) return null;
                try {
                    JsonNode node = objectMapper.readTree(basicJson);
                    if (node.has("rateLimitPerMinute")) {
                        return node.get("rateLimitPerMinute").asInt(10);
                    }
                } catch (Exception e) {
                    log.warn("[Orchestrator] Failed to parse rateLimitPerMinute from ConfigMap: {}", e.getMessage());
                }
                return null;
            })
            .onErrorResume(e -> {
                log.debug("[Orchestrator] Failed to fetch rateLimitPerMinute setting from ConfigMap: {}", e.getMessage());
                return Mono.empty();
            })
            .defaultIfEmpty(10);
    }

    private Mono<AiCommentReply> createAiCommentReply(ContextExtractor.CommentContext context, String sentiment,
                                                        String personaName) {
        AiCommentReply record = new AiCommentReply();
        record.setMetadata(new Metadata());
        record.getMetadata().setName("ai-reply-" + UUID.randomUUID().toString().substring(0, 8));
        record.setSpec(new AiCommentReply.Spec());
        record.getSpec().setCommentId(context.commentId());
        record.getSpec().setPostId(context.postId());
        record.getSpec().setPostSlug(context.postSlug());
        record.getSpec().setReply("");
        record.getSpec().setScore(0);
        record.getSpec().setStatus("PENDING");
        record.getSpec().setRetryCount(0);
        record.getSpec().setReplyTo(context.replyTo());
        record.getSpec().setIsAiConversation(context.isAiConversation());
        record.getSpec().setPublished(false);
        record.getSpec().setSentiment(sentiment);
        record.getSpec().setPersonaName(personaName);
        return client.create(record)
            .doOnSuccess(created -> log.info("[Orchestrator] Created AiCommentReply record: {}",
                created.getMetadata().getName()));
    }

    private Mono<AiCommentReply> updateRecord(AiCommentReply record, String reply,
                                               int score, String status, boolean published,
                                               String replyName) {
        log.debug("[Orchestrator] Updating record {}: status={}, score={}, published={}, replyName={}",
            record.getMetadata().getName(), status, score, published, replyName);
        return client.fetch(AiCommentReply.class, record.getMetadata().getName())
            .flatMap(latest -> {
                latest.getSpec().setReply(reply);
                latest.getSpec().setScore(score);
                latest.getSpec().setStatus(status);
                latest.getSpec().setPublished(published);
                latest.getSpec().setReplyName(replyName);
                return client.update(latest);
            })
            .retryWhen(Retry.backoff(3, Duration.ofMillis(100))
                .filter(e -> e instanceof OptimisticLockingFailureException)
                .doBeforeRetry(signal -> log.debug("[Orchestrator] Retrying update for {} due to optimistic lock",
                    record.getMetadata().getName()))
            )
            .doOnSuccess(updated -> log.debug("[Orchestrator] Record {} updated: status={}, score={}, published={}, replyName={}",
                record.getMetadata().getName(), status, score, published, replyName));
    }

    /**
     * Clean up stale locks that have been held longer than LOCK_EXPIRY_MS.
     * This prevents memory leaks in case of unexpected errors or cancellations
     * that bypass the doFinally cleanup.
     */
    private void cleanupStaleLocks() {
        long now = System.currentTimeMillis();
        processingLocks.entrySet().removeIf(entry -> {
            long age = now - entry.getValue();
            if (age > LOCK_EXPIRY_MS) {
                log.warn("[Orchestrator] Removing stale lock: {} (held for {}ms)", entry.getKey(), age);
                return true;
            }
            return false;
        });
    }
}
