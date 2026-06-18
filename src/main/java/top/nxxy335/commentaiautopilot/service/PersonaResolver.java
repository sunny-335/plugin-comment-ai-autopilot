package top.nxxy335.commentaiautopilot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.core.extension.content.Category;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.Tag;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.ReactiveExtensionClient;
import reactor.core.publisher.Mono;

/**
 * Shared service for resolving AI persona name from a comment's associated
 * post/category/tag annotations.
 *
 * <p>Priority: Post annotation &gt; Category annotation &gt; Tag annotation
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PersonaResolver {

    private static final String AI_PERSONA_ANNOTATION = "comment-ai-autopilot.nxxy335.top/ai-persona";

    private final ReactiveExtensionClient reactiveClient;

    /**
     * Resolve persona name from a comment (reactive version).
     * Reads the post's annotations, then falls back to category and tag annotations.
     *
     * @param commentName the Comment metadata.name
     * @return the persona name, or empty string if none found
     */
    public Mono<String> getPersonaNameFromComment(String commentName) {
        return reactiveClient.fetch(Comment.class, commentName)
            .flatMap(comment -> {
                var subjectRef = comment.getSpec().getSubjectRef();
                if (subjectRef == null || !"Post".equals(subjectRef.getKind())) {
                    return Mono.just("");
                }
                String postName = subjectRef.getName();
                return resolveFromPost(postName);
            })
            .defaultIfEmpty("");
    }

    private Mono<String> resolveFromPost(String postName) {
        return reactiveClient.fetch(Post.class, postName)
            .flatMap(post -> {
                // 1. Post annotation takes priority
                var annotations = post.getMetadata().getAnnotations();
                if (annotations != null) {
                    String persona = annotations.get(AI_PERSONA_ANNOTATION);
                    if (persona != null && !persona.isBlank()) {
                        return Mono.just(persona);
                    }
                }
                // 2. Category annotations
                var spec = post.getSpec();
                if (spec != null && spec.getCategories() != null) {
                    for (String categoryName : spec.getCategories()) {
                        var persona = resolveFromCategory(categoryName);
                        if (persona != null) return Mono.just(persona);
                    }
                }
                // 3. Tag annotations
                if (spec != null && spec.getTags() != null) {
                    for (String tagName : spec.getTags()) {
                        var persona = resolveFromTag(tagName);
                        if (persona != null) return Mono.just(persona);
                    }
                }
                return Mono.just("");
            })
            .defaultIfEmpty("");
    }

    private String resolveFromCategory(String categoryName) {
        // Use block() here because this is called from a Reconciler (sync context)
        // For reactive context, the caller should use the reactive version
        try {
            return reactiveClient.fetch(Category.class, categoryName)
                .mapNotNull(cat -> {
                    var catAnnotations = cat.getMetadata().getAnnotations();
                    if (catAnnotations != null) {
                        String catPersona = catAnnotations.get(AI_PERSONA_ANNOTATION);
                        if (catPersona != null && !catPersona.isBlank()) {
                            return catPersona;
                        }
                    }
                    return null;
                })
                .block();
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveFromTag(String tagName) {
        try {
            return reactiveClient.fetch(Tag.class, tagName)
                .mapNotNull(tag -> {
                    var tagAnnotations = tag.getMetadata().getAnnotations();
                    if (tagAnnotations != null) {
                        String tagPersona = tagAnnotations.get(AI_PERSONA_ANNOTATION);
                        if (tagPersona != null && !tagPersona.isBlank()) {
                            return tagPersona;
                        }
                    }
                    return null;
                })
                .block();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Resolve persona name from a comment using blocking ExtensionClient
     * (for use in Reconciler sync context).
     */
    public String getPersonaNameFromCommentBlocking(ExtensionClient client, Comment comment) {
        var subjectRef = comment.getSpec().getSubjectRef();
        if (subjectRef == null || !"Post".equals(subjectRef.getKind())) {
            return null;
        }
        String postName = subjectRef.getName();
        return client.fetch(Post.class, postName)
            .map(post -> {
                // 1. Post annotation
                var annotations = post.getMetadata().getAnnotations();
                if (annotations != null) {
                    String persona = annotations.get(AI_PERSONA_ANNOTATION);
                    if (persona != null && !persona.isBlank()) {
                        return persona;
                    }
                }
                // 2. Category annotations
                var spec = post.getSpec();
                if (spec != null && spec.getCategories() != null) {
                    for (String categoryName : spec.getCategories()) {
                        var cat = client.fetch(Category.class, categoryName).orElse(null);
                        if (cat != null) {
                            var catAnnotations = cat.getMetadata().getAnnotations();
                            if (catAnnotations != null) {
                                String catPersona = catAnnotations.get(AI_PERSONA_ANNOTATION);
                                if (catPersona != null && !catPersona.isBlank()) {
                                    return catPersona;
                                }
                            }
                        }
                    }
                }
                // 3. Tag annotations
                if (spec != null && spec.getTags() != null) {
                    for (String tagName : spec.getTags()) {
                        var tag = client.fetch(Tag.class, tagName).orElse(null);
                        if (tag != null) {
                            var tagAnnotations = tag.getMetadata().getAnnotations();
                            if (tagAnnotations != null) {
                                String tagPersona = tagAnnotations.get(AI_PERSONA_ANNOTATION);
                                if (tagPersona != null && !tagPersona.isBlank()) {
                                    return tagPersona;
                                }
                            }
                        }
                    }
                }
                return null;
            })
            .orElse(null);
    }
}
