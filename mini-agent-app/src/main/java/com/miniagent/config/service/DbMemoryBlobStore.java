package com.miniagent.config.service;

import com.miniagent.config.entity.AgentUserMemory;
import com.miniagent.config.repository.AgentUserMemoryRepository;
import com.miniagent.memory.MemoryBlobStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "agent.memory.storage", havingValue = "db", matchIfMissing = true)
public class DbMemoryBlobStore implements MemoryBlobStore {

    private final AgentUserMemoryRepository repo;

    public DbMemoryBlobStore(AgentUserMemoryRepository repo) {
        this.repo = repo;
    }

    @Override
    @Transactional(readOnly = true)
    public Blob load(long userId) {
        return repo.findById(userId)
                .map(e -> new Blob(e.getMemoryContent(), e.getUserContent(), e.getMidtermContent()))
                .orElse(new Blob("", "", ""));
    }

    @Override
    @Transactional
    public void saveMemory(long userId, String content) {
        AgentUserMemory row = getOrCreate(userId);
        row.setMemoryContent(content == null ? "" : content);
        repo.save(row);
    }

    @Override
    @Transactional
    public void saveUser(long userId, String content) {
        AgentUserMemory row = getOrCreate(userId);
        row.setUserContent(content == null ? "" : content);
        repo.save(row);
    }

    @Override
    @Transactional
    public void saveMidterm(long userId, String content) {
        AgentUserMemory row = getOrCreate(userId);
        row.setMidtermContent(content == null ? "" : content);
        repo.save(row);
    }

    private AgentUserMemory getOrCreate(long userId) {
        return repo.findById(userId).orElseGet(() -> {
            AgentUserMemory n = new AgentUserMemory();
            n.setUserId(userId);
            return n;
        });
    }
}
