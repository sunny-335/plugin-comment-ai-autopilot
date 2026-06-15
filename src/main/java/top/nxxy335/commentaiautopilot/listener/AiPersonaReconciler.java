package top.nxxy335.commentaiautopilot.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.ControllerBuilder;
import run.halo.app.extension.controller.Reconciler;
import top.nxxy335.commentaiautopilot.extension.AiPersona;

@Component
@Slf4j
@RequiredArgsConstructor
public class AiPersonaReconciler implements Reconciler<Reconciler.Request> {

    private final ReactiveExtensionClient client;

    @Override
    public Result reconcile(Request request) {
        return new Result(false, null);
    }

    @Override
    public Controller setupWith(ControllerBuilder builder) {
        return builder
            .extension(new AiPersona())
            .syncAllOnStart(false)
            .build();
    }
}
