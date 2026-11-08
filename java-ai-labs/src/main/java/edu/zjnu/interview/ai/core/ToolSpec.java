package edu.zjnu.interview.ai.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具声明：告诉模型「有哪些工具可用、怎么调」。
 *
 * <p>工具声明是模型与运行时之间的契约。生产上常用的做法是把
 * {@link #parameters()} 同时用于两件事：喂给模型的 JSON Schema，以及运行时入参校验。
 * 一份声明两处复用，才能避免「模型以为的参数」和「代码真正接受的参数」长期漂移。
 *
 * @param name              工具名（模型用它发起调用）
 * @param description       工具说明（模型据此判断何时调用，写得差=召回不到）
 * @param parameters        参数 schema，key 为参数名
 * @param sideEffect        副作用等级，权限策略据此决定是否需要审批
 * @param requiresApproval  是否必须人工/策略审批后才能执行
 */
public record ToolSpec(String name,
                       String description,
                       Map<String, Param> parameters,
                       SideEffect sideEffect,
                       boolean requiresApproval) {

    /** 副作用等级。 */
    public enum SideEffect {
        /** 只读本地资源。 */
        READ_ONLY,
        /** 会修改状态（写文件、改内存库）。 */
        WRITE,
        /** 会访问外部网络。 */
        NETWORK
    }

    /**
     * 单个参数声明。
     *
     * @param type         类型名（string / number / boolean / array / object）
     * @param description  参数说明
     * @param required     是否必填
     * @param defaultValue 默认值，可为 null
     */
    public record Param(String type, String description, boolean required, Object defaultValue) {

        public static Param required(String type, String description) {
            return new Param(type, description, true, null);
        }

        public static Param optional(String type, String description, Object defaultValue) {
            return new Param(type, description, false, defaultValue);
        }
    }

    public ToolSpec {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        sideEffect = sideEffect == null ? SideEffect.READ_ONLY : sideEffect;
    }

    public static Builder builder(String name, String description) {
        return new Builder(name, description);
    }

    /** 声明式 builder，避免工具代码里出现大段 Map.of 嵌套。 */
    public static final class Builder {
        private final String name;
        private final String description;
        private final Map<String, Param> params = new LinkedHashMap<>();
        private SideEffect sideEffect = SideEffect.READ_ONLY;
        private boolean requiresApproval;

        private Builder(String name, String description) {
            this.name = name;
            this.description = description;
        }

        public Builder param(String paramName, String type, boolean required, String paramDescription) {
            params.put(paramName, new Param(type, paramDescription, required, null));
            return this;
        }

        public Builder optional(String paramName, String type, String paramDescription, Object defaultValue) {
            params.put(paramName, new Param(type, paramDescription, false, defaultValue));
            return this;
        }

        public Builder sideEffect(SideEffect effect) {
            this.sideEffect = effect;
            return this;
        }

        public Builder requiresApproval(boolean value) {
            this.requiresApproval = value;
            return this;
        }

        public ToolSpec build() {
            return new ToolSpec(name, description, params, sideEffect, requiresApproval);
        }
    }
}
