package top.nxxy335.commentaiautopilot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Reply;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import top.nxxy335.commentaiautopilot.extension.AiPersona;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
@Slf4j
public class CommentReplyPublisher {

    private final ReactiveExtensionClient client;

    public CommentReplyPublisher(ReactiveExtensionClient client) {
        this.client = client;
    }

    private static final String AI_PERSONA_OWNER_PREFIX = "ai-persona-";

    /**
     * Publish a reply to a comment automatically using AI Persona identity.
     * Includes a final dedup check: if an AI reply already exists for this comment,
     * skip publishing to prevent duplicate replies.
     *
     * @param personaName the persona name to use (null for default persona)
     */
    public Mono<Reply> publishReply(String parentCommentName, String replyContent,
                                     String postName, String quoteReplyName, boolean autoPublish,
                                     String personaName) {
        return checkExistingAiReply(parentCommentName, quoteReplyName)
            .flatMap(exists -> {
                if (exists) {
                    log.info("[Publisher] AI reply already exists for comment: {}, skipping duplicate publish",
                        parentCommentName);
                    return Mono.empty();
                }
                return doPublish(parentCommentName, replyContent, postName, quoteReplyName, autoPublish, personaName);
            });
    }

    private Mono<Boolean> checkExistingAiReply(String parentCommentName, String quoteReplyName) {
        return client.list(Reply.class,
                reply -> {
                    if (!parentCommentName.equals(reply.getSpec().getCommentName())) {
                        return false;
                    }
                    var owner = reply.getSpec().getOwner();
                    if (owner == null || owner.getName() == null) {
                        return false;
                    }
                    boolean isAiOwner = owner.getName().startsWith(AI_PERSONA_OWNER_PREFIX);
                    boolean hasAiAnnotation = owner.getAnnotations() != null
                        && "true".equals(owner.getAnnotations().get("comment-ai-autopilot.nxxy335.top/is-ai"));
                    boolean isAiReply = isAiOwner || hasAiAnnotation;

                    if (!isAiReply) {
                        return false;
                    }

                    if (quoteReplyName != null && !quoteReplyName.isBlank()) {
                        return quoteReplyName.equals(reply.getSpec().getQuoteReply());
                    }
                    return true;
                },
                null)
            .hasElements()
            .defaultIfEmpty(false);
    }

    private Mono<Reply> doPublish(String parentCommentName, String replyContent,
                                   String postName, String quoteReplyName, boolean autoPublish,
                                   String personaName) {
        return resolvePersona(personaName).flatMap(persona -> {
            String displayName = persona.displayName();
            String email = persona.email();

            Reply reply = new Reply();
            reply.setMetadata(new Metadata());
            reply.getMetadata().setName(generateReplyName());
            reply.setSpec(new Reply.ReplySpec());

            var spec = reply.getSpec();
            spec.setCommentName(parentCommentName);
            spec.setRaw(replyContent);
            spec.setContent(replyContent);
            spec.setApproved(autoPublish);
            if (autoPublish) {
                spec.setApprovedTime(Instant.now());
            }
            spec.setPriority(0);
            spec.setTop(false);
            spec.setAllowNotification(false);
            spec.setHidden(false);

            if (quoteReplyName != null && !quoteReplyName.isBlank()) {
                spec.setQuoteReply(quoteReplyName);
            }

            var owner = new Comment.CommentOwner();
            owner.setKind(Comment.CommentOwner.KIND_EMAIL);
            if (email != null && !email.isBlank()) {
                owner.setName(email);
            } else {
                owner.setName(AI_PERSONA_OWNER_PREFIX + displayName);
            }
            owner.setDisplayName(displayName + " AI");

            Map<String, String> ownerAnnotations = new HashMap<>();
            ownerAnnotations.put("comment-ai-autopilot.nxxy335.top/is-ai", "true");
            // 使用Gravatar邮箱头像
            if (email != null && !email.isBlank()) {
                String gravatarUrl = generateGravatarUrl(email);
                ownerAnnotations.put(Comment.CommentOwner.AVATAR_ANNO, gravatarUrl);
            }
            owner.setAnnotations(ownerAnnotations);
            spec.setOwner(owner);

            log.info("[Publisher] Creating reply for comment: {}, owner: kind={}, name={}, displayName={}, annotations={}",
                parentCommentName, owner.getKind(), owner.getName(), owner.getDisplayName(), ownerAnnotations);

            return client.create(reply)
                .doOnSuccess(created -> {
                    var createdOwner = created.getSpec().getOwner();
                    log.info("[Publisher] AI Persona '{}' reply published for comment: {}, quoteReply: {}, owner annotations after create: {}",
                        displayName, parentCommentName, quoteReplyName,
                        createdOwner != null ? createdOwner.getAnnotations() : "null");
                })
                .doOnError(e -> log.error("[Publisher] Failed to publish AI reply: {}", e.getMessage()));
        });
    }

    /**
     * Resolve persona info from AiPersona extension.
     * Priority:
     * 1. If personaName is provided, fetch from AiPersona extension
     * 2. If personaName is empty, find the default AiPersona (isDefault=true)
     * 3. If no AiPersona found, return default "小回" with empty email
     */
    private Mono<ResolvedPersona> resolvePersona(String personaName) {
        if (personaName != null && !personaName.isBlank()) {
            return client.fetch(AiPersona.class, personaName)
                .map(p -> {
                    log.info("[Publisher] Resolved persona by name: {}, email={}",
                        personaName, p.getSpec().getEmail());
                    return new ResolvedPersona(
                        p.getSpec().getDisplayName(),
                        p.getSpec().getEmail()
                    );
                })
                .defaultIfEmpty(new ResolvedPersona("小回", ""));
        }
        // Find default persona
        return client.list(AiPersona.class,
                persona -> persona.getSpec() != null && Boolean.TRUE.equals(persona.getSpec().getIsDefault()),
                null)
            .next()
            .map(p -> {
                log.info("[Publisher] Resolved default persona: {}, email={}",
                    p.getMetadata().getName(), p.getSpec().getEmail());
                return new ResolvedPersona(
                    p.getSpec().getDisplayName(),
                    p.getSpec().getEmail()
                );
            })
            .defaultIfEmpty(new ResolvedPersona("小回", ""));
    }

    private record ResolvedPersona(String displayName, String email) {}

    private String generateReplyName() {
        return "ai-comment-reply-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Generate Gravatar URL from email address using SHA-256 hash.
     */
    private String generateGravatarUrl(String email) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hashBytes = digest.digest(email.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
            var hexString = new StringBuilder();
            for (byte b : hashBytes) {
                hexString.append(String.format("%02x", b));
            }
            return "https://cn.cravatar.com/avatar/" + hexString;
        } catch (Exception e) {
            log.error("[Publisher] Failed to generate Gravatar URL: {}", e.getMessage());
            return "";
        }
    }
}
