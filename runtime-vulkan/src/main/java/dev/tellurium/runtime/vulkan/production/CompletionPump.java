// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan.production;

import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

public final class CompletionPump {
    public <T> void observe(CompletionStage<T> stage, Consumer<T> success, Consumer<Throwable> failure) { stage.whenComplete((value, error) -> { if (error == null) success.accept(value); else failure.accept(error); }); }
}
