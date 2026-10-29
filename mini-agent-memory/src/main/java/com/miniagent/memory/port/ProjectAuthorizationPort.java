package com.miniagent.memory.port;

/** 把执行侧的项目成员授权快照投影到独立记忆服务。 */
public interface ProjectAuthorizationPort {
    ProjectAuthorizationResult synchronize(ProjectAuthorizationCommand command);
}
