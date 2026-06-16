package top.nxxy335.commentaiautopilot.extension;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(
    group = "comment-ai-autopilot.nxxy335.top",
    version = "v1alpha1",
    kind = "AiPersona",
    plural = "aipersonas",
    singular = "aipersona"
)
public class AiPersona extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private AiPersonaSpec spec;

    @Data
    @Schema(name = "AiPersonaSpec")
    public static class AiPersonaSpec {

        @Schema(description = "角色昵称")
        private String displayName;

        @Schema(description = "人格提示词")
        private String prompt;

        @Schema(description = "邮箱（用于Gravatar头像）")
        private String email;

        @Schema(description = "是否为默认角色")
        @JsonProperty("isDefault")
        private Boolean isDefault;

        @Schema(description = "角色优先级，数值越小优先级越高")
        private Integer priority;
    }
}
