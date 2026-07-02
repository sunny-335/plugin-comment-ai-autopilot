package top.nxxy335.commentaiautopilot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.SchemeManager;

/**
 * 瞬间插件(Moments)集成检测服务。
 *
 * <p>通过 SchemeManager 检测瞬间插件的 Moment 扩展是否已注册，
 * 以判断瞬间插件是否已安装并启用。不直接引用瞬间插件的 API 类，
 * 避免未安装时触发 NoClassDefFoundError。
 */
@Component
@Slf4j
public class MomentsIntegrationService {

    private static final String MOMENT_GROUP = "moment.halo.run";
    private static final String MOMENT_KIND = "Moment";

    private final SchemeManager schemeManager;

    public MomentsIntegrationService(SchemeManager schemeManager) {
        this.schemeManager = schemeManager;
    }

    /**
     * 检测瞬间插件是否已安装并启用（Moment 扩展已注册）。
     */
    public boolean isMomentsAvailable() {
        try {
            return schemeManager.fetch(new GroupVersionKind(MOMENT_GROUP, "v1alpha1", MOMENT_KIND))
                .isPresent();
        } catch (Exception e) {
            log.debug("[Moments] Failed to check moments availability: {}", e.getMessage());
            return false;
        }
    }
}
