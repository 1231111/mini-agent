package com.miniagent.agent.memory.repository;

import com.miniagent.agent.memory.entity.AgentMemoryIndexOutboxEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AgentMemoryIndexOutboxRepository
        extends JpaRepository<AgentMemoryIndexOutboxEntity, Long> {

    /**
     * 取一批待投递记录。
     *
     * <p><b>这里不能加 {@code @Lock(PESSIMISTIC_WRITE)}</b>。悲观锁按 JPA 规范要求
     * 调用时必须处于活跃事务中，而唯一的调用方 {@code MemoryIndexOutboxService.drain()}
     * 是刻意的无事务方法 —— 见它的 javadoc：整批不再套一个事务，是为了消除
     * 「已 unlock 但尚未 commit」这个重复投递窗口（unlock 在 finally 里，先于事务提交执行）。
     *
     * <p>两者组合的后果不是「锁没生效」那么温和，而是**每次调度都直接抛**：
     * <pre>
     * InvalidDataAccessApiUsageException:
     *   Query requires transaction be in progress, but no transaction is known to be in progress
     * </pre>
     * 取批是 drain() 的第一条语句，所以整个方法一次都没跑完过：队列一条都投不出去，
     * 而日志每 5 秒刷一条 ERROR。实测现象就是启动后日志里反复出现
     * "Unexpected error occurred in scheduled task"，栈顶停在 MemoryIndexOutboxService.drain:121。
     *
     * <p>跨实例互斥不缺这一条锁：{@code MemoryTaskLock} 用 Redis {@code SET NX EX}
     * 加锁、Lua 按 token 释放（无 Redis 的单机部署退化为进程内锁），同一时刻只有一个实例
     * 能进入 drain。而且即便把这条锁留下也保护不了什么 —— 取批所用的事务一旦提交，
     * 行锁立刻释放，而逐条处理是在各自独立的事务里按 id 重新加载实体的
     * （见 {@code process(Long)} 的入参说明）。锁的存活期完全覆盖不到它想保护的那段逻辑。
     *
     * <p>同一时刻有这条 {@code @Lock} 的反面证据也在本类：下面的
     * {@code findByStatus} 没有加锁，被 {@code init()} 在 {@code @PostConstruct}
     * 里（同样无事务）正常调用，从未报错 —— 触发条件就是那个锁，不是"缺事务"。
     */
    List<AgentMemoryIndexOutboxEntity>
    findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
            AgentMemoryIndexOutboxEntity.Status status, LocalDateTime nextAttemptAt);

    List<AgentMemoryIndexOutboxEntity> findByStatus(AgentMemoryIndexOutboxEntity.Status status);
}
