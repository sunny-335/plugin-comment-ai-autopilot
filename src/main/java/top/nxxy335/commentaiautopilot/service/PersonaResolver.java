package top.nxxy335.commentaiautopilot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.core.extension.content.Category;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.Tag;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.ReactiveExtensionClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Shared service for resolving AI persona name from a comment's associated
 * post/category/tag annotations and ConfigMap category persona mapping.
 *
 * <p>解析优先级：唤醒词角色 &gt; Post 标注 &gt; Category 标注 &gt; Tag 标注
 * &gt; ConfigMap 分类角色映射 &gt; 全局默认角色
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PersonaResolver {

    private static final String AI_PERSONA_ANNOTATION = "comment-ai-autopilot.nxxy335.top/ai-persona";
    private static final String CONFIG_MAP_NAME = "comment-ai-autopilot-configmap";

    private final ReactiveExtensionClient reactiveClient;
    private final ObjectMapper objectMapper;

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
                    // Moment / SinglePage 等不支持角色标注，使用默认角色
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
                // 2. Category annotations (check sequentially, return first match)
                var spec = post.getSpec();
                List<String> categories = (spec != null && spec.getCategories() != null)
                    ? spec.getCategories() : List.of();
                // 3. Tag annotations (fallback if no category match)
                List<String> tags = (spec != null && spec.getTags() != null)
                    ? spec.getTags() : List.of();

                // 4. ConfigMap 分类角色映射（最终兜底，再回退到全局默认）
                return resolveFromCategories(categories)
                    .switchIfEmpty(resolveFromTags(tags))
                    .switchIfEmpty(getPersonaNameByCategory(String.join(",", categories)));
            })
            .defaultIfEmpty("");
    }

    /**
     * Sequentially check category annotations, returning the first non-empty persona.
     * Uses concatMap to preserve order and short-circuit on first match.
     */
    private Mono<String> resolveFromCategories(List<String> categoryNames) {
        if (categoryNames == null || categoryNames.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(categoryNames)
            .concatMap(this::resolveFromCategory)
            .next();
    }

    /**
     * Sequentially check tag annotations, returning the first non-empty persona.
     * Uses concatMap to preserve order and short-circuit on first match.
     */
    private Mono<String> resolveFromTags(List<String> tagNames) {
        if (tagNames == null || tagNames.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(tagNames)
            .concatMap(this::resolveFromTag)
            .next();
    }

    private Mono<String> resolveFromCategory(String categoryName) {
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
            .onErrorResume(e -> {
                log.warn("Failed to resolve persona from category {}: {}", categoryName, e.getMessage());
                return Mono.empty();
            });
    }

    private Mono<String> resolveFromTag(String tagName) {
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
            .onErrorResume(e -> {
                log.warn("Failed to resolve persona from tag {}: {}", tagName, e.getMessage());
                return Mono.empty();
            });
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
                // 4. ConfigMap 分类角色映射（最终兜底，再回退到全局默认）
                if (spec != null && spec.getCategories() != null && !spec.getCategories().isEmpty()) {
                    String categoryNames = String.join(",", spec.getCategories());
                    String mapPersona = getPersonaNameByCategoryBlocking(client, categoryNames);
                    if (mapPersona != null && !mapPersona.isBlank()) {
                        return mapPersona;
                    }
                }
                return null;
            })
            .orElse(null);
    }

    /**
     * 从 ConfigMap 中解析 categoryPersonaMap，返回 分类显示名 -&gt; 角色名 的映射。
     *
     * <p>v1.4.0 起 categoryPersonaMap 移至 persona 配置组；为兼容旧版配置，
     * 此方法优先读取 persona 组，缺失时回退到 model 组。
     *
     * @param cm 插件 ConfigMap，可为 null
     * @return 解析后的映射，无配置或解析失败返回 null
     */
    private Map<String, String> parseCategoryPersonaMap(ConfigMap cm) {
        if (cm == null) return null;
        var data = cm.getData();
        if (data == null) return null;
        // 优先读取 persona 组（v1.4.0+）
        Map<String, String> result = extractCategoryPersonaMap(data.get("persona"));
        if (result != null) return result;
        // 回退到 model 组（旧版兼容）
        return extractCategoryPersonaMap(data.get("model"));
    }

    /**
     * 从指定 JSON 文本中解析 categoryPersonaMap 字段。
     */
    private Map<String, String> extractCategoryPersonaMap(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode mapNode = node.get("categoryPersonaMap");
            if (mapNode == null || mapNode.isNull()) return null;
            // textarea 通常存储为字符串，需要二次解析；兼容直接为对象的情况
            if (mapNode.isTextual()) {
                String mapJsonStr = mapNode.asText();
                if (mapJsonStr.isBlank()) return null;
                return objectMapper.readValue(mapJsonStr, new TypeReference<Map<String, String>>() {});
            }
            if (mapNode.isObject()) {
                return objectMapper.convertValue(mapNode, new TypeReference<Map<String, String>>() {});
            }
            return null;
        } catch (Exception e) {
            log.warn("[PersonaResolver] 解析 categoryPersonaMap 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 根据分类名从 ConfigMap 的 categoryPersonaMap 解析角色名（blocking 版本，供 Reconciler 调用）。
     * 接受逗号分隔的分类 metadata.name，逐个获取 Category 的显示名称后匹配映射表，
     * 返回第一个匹配的角色名。
     *
     * @param client         ExtensionClient，用于读取 ConfigMap 和 Category
     * @param categoryNames  逗号分隔的分类 metadata.name（来自 Post.getSpec().getCategories()）
     * @return 第一个匹配的角色名，无匹配返回 null
     */
    public String getPersonaNameByCategoryBlocking(ExtensionClient client, String categoryNames) {
        if (categoryNames == null || categoryNames.isBlank()) {
            return null;
        }

        var cmOpt = client.fetch(ConfigMap.class, CONFIG_MAP_NAME);
        if (cmOpt.isEmpty()) return null;
        Map<String, String> personaMap = parseCategoryPersonaMap(cmOpt.get());
        if (personaMap == null || personaMap.isEmpty()) return null;

        for (String categoryName : categoryNames.split(",")) {
            String trimmedName = categoryName.trim();
            if (trimmedName.isEmpty()) continue;

            // 通过 metadata.name 获取 Category，再取显示名称进行匹配
            var catOpt = client.fetch(Category.class, trimmedName);
            if (catOpt.isPresent()) {
                var cat = catOpt.get();
                String displayName = cat.getSpec() != null ? cat.getSpec().getDisplayName() : null;
                if (displayName != null && personaMap.containsKey(displayName)) {
                    return personaMap.get(displayName);
                }
            }
        }
        return null;
    }

    /**
     * 根据分类名从 ConfigMap 的 categoryPersonaMap 解析角色名（reactive 版本）。
     * 接受逗号分隔的分类 metadata.name，逐个获取 Category 的显示名称后匹配映射表，
     * 返回第一个匹配的角色名。
     *
     * @param categoryNames 逗号分隔的分类 metadata.name（来自 Post.getSpec().getCategories()）
     * @return 包含角色名的 Mono，无匹配返回 Mono.empty()
     */
    public Mono<String> getPersonaNameByCategory(String categoryNames) {
        if (categoryNames == null || categoryNames.isBlank()) {
            return Mono.empty();
        }

        return reactiveClient.fetch(ConfigMap.class, CONFIG_MAP_NAME)
            .mapNotNull(this::parseCategoryPersonaMap)
            .flatMap(personaMap -> {
                if (personaMap.isEmpty()) return Mono.empty();
                List<String> names = Arrays.stream(categoryNames.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
                // 按顺序逐个匹配，返回第一个命中的角色名
                return Flux.fromIterable(names)
                    .concatMap(catName ->
                        reactiveClient.fetch(Category.class, catName)
                            .mapNotNull(cat -> {
                                String displayName = cat.getSpec() != null
                                    ? cat.getSpec().getDisplayName() : null;
                                if (displayName != null && personaMap.containsKey(displayName)) {
                                    return personaMap.get(displayName);
                                }
                                return null;
                            })
                            .onErrorResume(e -> {
                                log.warn("[PersonaResolver] 获取分类 {} 失败: {}", catName, e.getMessage());
                                return Mono.empty();
                            })
                    )
                    .next();
            });
    }
}
