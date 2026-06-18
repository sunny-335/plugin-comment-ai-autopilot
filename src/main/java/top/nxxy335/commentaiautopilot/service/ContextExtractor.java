package top.nxxy335.commentaiautopilot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.content.ContentWrapper;
import run.halo.app.content.PostContentService;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.SinglePage;
import run.halo.app.core.extension.content.Reply;
import run.halo.app.extension.ReactiveExtensionClient;

@Component
@Slf4j
@RequiredArgsConstructor
public class ContextExtractor {

    private final ReactiveExtensionClient client;
    private final PostContentService postContentService;

    /**
     * Extract context from a comment event.
     * Returns a CommentContext record with all needed info.
     *
     * @param commentName     the Comment name (always required)
     * @param replyName       the Reply name that triggered this (null for top-level comments)
     * @param isAiConversation whether this is a continuation of AI conversation
     */
    public Mono<CommentContext> extract(String commentName, String replyName, boolean isAiConversation) {
        return client.fetch(Comment.class, commentName)
            .flatMap(comment -> {
                if (replyName != null && !replyName.isBlank()) {
                    // This is a reply to a comment - fetch the Reply for content
                    return client.fetch(Reply.class, replyName)
                        .flatMap(reply -> buildContextFromReply(comment, reply, isAiConversation))
                        .switchIfEmpty(buildContext(comment, isAiConversation));
                }
                return buildContext(comment, isAiConversation);
            });
    }

    /**
     * Fetch previous replies in the comment thread to provide conversation history.
     * Only includes replies created before the triggering reply.
     */
    private Mono<String> fetchConversationHistory(String commentName, String triggerReplyName) {
        if (triggerReplyName == null || triggerReplyName.isBlank()) {
            return Mono.just("");
        }
        return client.fetch(Reply.class, triggerReplyName)
            .flatMap(triggerReply -> {
                var triggerTime = triggerReply.getMetadata().getCreationTimestamp();
                return client.list(Reply.class,
                        reply -> {
                            if (!commentName.equals(reply.getSpec().getCommentName())) {
                                return false;
                            }
                            if (triggerReplyName.equals(reply.getMetadata().getName())) {
                                return false;
                            }
                            // Only include replies created before the trigger reply
                            var replyTime = reply.getMetadata().getCreationTimestamp();
                            return replyTime != null && triggerTime != null
                                && !replyTime.isAfter(triggerTime);
                        },
                        null)
                    .collectList()
                    .map(replies -> {
                        if (replies.isEmpty()) return "";
                        // Sort by creation time
                        replies.sort(java.util.Comparator.comparing(
                            r -> r.getMetadata().getCreationTimestamp()));
                        var sb = new StringBuilder();
                        for (var r : replies) {
                            var owner = r.getSpec().getOwner();
                            String name = (owner != null && owner.getDisplayName() != null)
                                ? owner.getDisplayName() : "匿名用户";
                            boolean isAi = owner != null && owner.getAnnotations() != null
                                && "true".equals(owner.getAnnotations().get("comment-ai-autopilot.nxxy335.top/is-ai"));
                            String role = isAi ? "AI" : "用户";
                            String content = extractReplyContent(r);
                            sb.append(role).append("(").append(name).append("): ")
                              .append(content).append("\n");
                        }
                        return sb.toString();
                    });
            })
            .defaultIfEmpty("");
    }

    private Mono<CommentContext> buildContext(Comment comment, boolean isAiConversation) {
        var commentContent = extractCommentContent(comment);
        var commentOwner = extractCommentOwner(comment);
        var subjectRef = comment.getSpec().getSubjectRef();

        if (subjectRef != null && "Post".equals(subjectRef.getKind())) {
            String postName = subjectRef.getName();
            return client.fetch(Post.class, postName)
                .flatMap(post -> getPostContent(postName)
                    .flatMap(content -> getCommentCount(comment.getMetadata().getName())
                        .map(commentCount -> new CommentContext(
                            comment.getMetadata().getName(),
                            postName,
                            post.getSpec().getSlug(),
                            commentContent,
                            commentOwner,
                            post.getSpec().getTitle(),
                            content,
                            null,
                            isAiConversation,
                            formatPostDate(post),
                            commentCount,
                            "",
                            "Post"
                        ))
                    )
                )
                .defaultIfEmpty(new CommentContext(
                    comment.getMetadata().getName(),
                    postName,
                    "",
                    commentContent,
                    commentOwner,
                    "",
                    "",
                    null,
                    isAiConversation,
                    "",
                    0,
                    "",
                    "Post"
                ));
        }

        if (subjectRef != null && "SinglePage".equals(subjectRef.getKind())) {
            String postName = subjectRef.getName();
            return client.fetch(SinglePage.class, postName)
                .flatMap(singlePage -> getPostContent(postName)
                    .flatMap(content -> getCommentCount(comment.getMetadata().getName())
                        .map(commentCount -> new CommentContext(
                            comment.getMetadata().getName(),
                            postName,
                            singlePage.getSpec().getSlug(),
                            commentContent,
                            commentOwner,
                            singlePage.getSpec().getTitle(),
                            content,
                            null,
                            isAiConversation,
                            formatSinglePageDate(singlePage),
                            commentCount,
                            "",
                            "SinglePage"
                        ))
                    )
                )
                .defaultIfEmpty(new CommentContext(
                    comment.getMetadata().getName(),
                    postName,
                    "",
                    commentContent,
                    commentOwner,
                    "",
                    "",
                    null,
                    isAiConversation,
                    "",
                    0,
                    "",
                    "SinglePage"
                ));
        }

        return Mono.just(new CommentContext(
            comment.getMetadata().getName(),
            "",
            "",
            commentContent,
            commentOwner,
            "",
            "",
            null,
            isAiConversation,
            "",
            0,
            "",
            ""
        ));
    }

    private Mono<CommentContext> buildContextFromReply(Comment comment, Reply reply, boolean isAiConversation) {
        var replyContent = extractReplyContent(reply);
        var replyOwner = extractReplyOwner(reply);
        var subjectRef = comment.getSpec().getSubjectRef();
        var commentName = comment.getMetadata().getName();
        var replyName = reply.getMetadata().getName();

        // Fetch conversation history for AI conversations
        Mono<String> historyMono = isAiConversation
            ? fetchConversationHistory(commentName, replyName)
            : Mono.just("");

        if (subjectRef != null && "Post".equals(subjectRef.getKind())) {
            String postName = subjectRef.getName();
            return client.fetch(Post.class, postName)
                .flatMap(post -> getPostContent(postName)
                    .flatMap(content -> getCommentCount(commentName)
                        .flatMap(commentCount -> historyMono
                            .map(history -> new CommentContext(
                                commentName,
                                postName,
                                post.getSpec().getSlug(),
                                replyContent,
                                replyOwner,
                                post.getSpec().getTitle(),
                                content,
                                replyName,
                                isAiConversation,
                                formatPostDate(post),
                                commentCount,
                                history,
                                "Post"
                            ))
                        )
                    )
                )
                .defaultIfEmpty(new CommentContext(
                    commentName,
                    postName,
                    "",
                    replyContent,
                    replyOwner,
                    "",
                    "",
                    replyName,
                    isAiConversation,
                    "",
                    0,
                    "",
                    "Post"
                ));
        }

        if (subjectRef != null && "SinglePage".equals(subjectRef.getKind())) {
            String postName = subjectRef.getName();
            return client.fetch(SinglePage.class, postName)
                .flatMap(singlePage -> getPostContent(postName)
                    .flatMap(content -> getCommentCount(commentName)
                        .flatMap(commentCount -> historyMono
                            .map(history -> new CommentContext(
                                commentName,
                                postName,
                                singlePage.getSpec().getSlug(),
                                replyContent,
                                replyOwner,
                                singlePage.getSpec().getTitle(),
                                content,
                                replyName,
                                isAiConversation,
                                formatSinglePageDate(singlePage),
                                commentCount,
                                history,
                                "SinglePage"
                            ))
                        )
                    )
                )
                .defaultIfEmpty(new CommentContext(
                    commentName,
                    postName,
                    "",
                    replyContent,
                    replyOwner,
                    "",
                    "",
                    replyName,
                    isAiConversation,
                    "",
                    0,
                    "",
                    "SinglePage"
                ));
        }

        return historyMono
            .map(history -> new CommentContext(
                commentName,
                "",
                "",
                replyContent,
                replyOwner,
                "",
                "",
                replyName,
                isAiConversation,
                "",
                0,
                history,
                ""
            ));
    }

    private String extractCommentContent(Comment comment) {
        var spec = comment.getSpec();
        // Prefer raw content (plain text / markdown), fall back to rendered HTML
        String raw = spec.getRaw();
        if (raw != null && !raw.isBlank()) {
            return raw;
        }
        String content = spec.getContent();
        if (content != null && !content.isBlank()) {
            return Jsoup.clean(content, Safelist.none());
        }
        return "";
    }

    private String extractCommentOwner(Comment comment) {
        var owner = comment.getSpec().getOwner();
        if (owner != null) {
            String displayName = owner.getDisplayName();
            if (displayName != null && !displayName.isBlank()) {
                return displayName;
            }
        }
        return "匿名用户";
    }

    private String extractReplyContent(Reply reply) {
        var spec = reply.getSpec();
        String raw = spec.getRaw();
        if (raw != null && !raw.isBlank()) {
            return raw;
        }
        String content = spec.getContent();
        if (content != null && !content.isBlank()) {
            return Jsoup.clean(content, Safelist.none());
        }
        return "";
    }

    private String extractReplyOwner(Reply reply) {
        var owner = reply.getSpec().getOwner();
        if (owner != null) {
            String displayName = owner.getDisplayName();
            if (displayName != null && !displayName.isBlank()) {
                return displayName;
            }
        }
        return "匿名用户";
    }

    private Mono<String> getPostContent(String postName) {
        return postContentService.getReleaseContent(postName)
            .map(ContentWrapper::getContent)
            .map(html -> {
                if (html != null && !html.isBlank()) {
                    return Jsoup.clean(html, Safelist.none());
                }
                return "";
            })
            .defaultIfEmpty("");
    }

    private String formatPostDate(Post post) {
        var publishTime = post.getSpec().getPublishTime();
        if (publishTime != null) {
            return publishTime.toString().substring(0, 10);
        }
        var creationTimestamp = post.getMetadata().getCreationTimestamp();
        if (creationTimestamp != null) {
            return creationTimestamp.toString().substring(0, 10);
        }
        return "";
    }

    private String formatSinglePageDate(SinglePage singlePage) {
        var publishTime = singlePage.getSpec().getPublishTime();
        if (publishTime != null) {
            return publishTime.toString().substring(0, 10);
        }
        var creationTimestamp = singlePage.getMetadata().getCreationTimestamp();
        if (creationTimestamp != null) {
            return creationTimestamp.toString().substring(0, 10);
        }
        return "";
    }

    private Mono<Integer> getCommentCount(String commentName) {
        return client.list(Reply.class,
                reply -> commentName.equals(reply.getSpec().getCommentName()),
                null)
            .collectList()
            .map(replies -> replies.size())
            .defaultIfEmpty(0);
    }

    public record CommentContext(
        String commentId,
        String postId,
        String postSlug,
        String commentContent,
        String commentOwner,
        String postTitle,
        String postContent,
        String replyTo,
        boolean isAiConversation,
        String postDate,
        int commentCount,
        String conversationHistory,
        String postKind
    ) {}
}
