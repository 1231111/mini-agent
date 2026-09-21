package com.miniagent.agent.context;

/**
 * 系统上下文贡献者。新增块加实现，不要再往 {@link ContextLoader} 里堆。
 */
@FunctionalInterface
public interface SystemContextContributor {

    ContextFragment contribute(ContextBuildContext context);
}
