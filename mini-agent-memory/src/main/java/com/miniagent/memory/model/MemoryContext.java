package com.miniagent.memory.model;

import java.util.*;

/**
 * Memory Manager 返回给 Planner 的结构化上下文。
 * Planner 不直接访问数据库，只消费这个对象。
 */
public class MemoryContext {
    private WorkingMemory workingMemory;
    private List<String> facts;
    private List<Episode> episodes;
    private List<String> skills;
    private List<String> preferences;
    private List<MemoryEntry> rawMemories;

    public MemoryContext() {
        this.facts = new ArrayList<>();
        this.episodes = new ArrayList<>();
        this.skills = new ArrayList<>();
        this.preferences = new ArrayList<>();
        this.rawMemories = new ArrayList<>();
    }

    public WorkingMemory getWorkingMemory() { return workingMemory; }
    public void setWorkingMemory(WorkingMemory workingMemory) { this.workingMemory = workingMemory; }

    public List<String> getFacts() { return facts; }
    public void setFacts(List<String> facts) { this.facts = facts; }
    public void addFact(String fact) { this.facts.add(fact); }

    public List<Episode> getEpisodes() { return episodes; }
    public void setEpisodes(List<Episode> episodes) { this.episodes = episodes; }

    public List<String> getSkills() { return skills; }
    public void setSkills(List<String> skills) { this.skills = skills; }
    public void addSkill(String skill) { this.skills.add(skill); }

    public List<String> getPreferences() { return preferences; }
    public void setPreferences(List<String> preferences) { this.preferences = preferences; }
    public void addPreference(String pref) { this.preferences.add(pref); }

    public List<MemoryEntry> getRawMemories() { return rawMemories; }
    public void setRawMemories(List<MemoryEntry> rawMemories) { this.rawMemories = rawMemories; }

    /** 是否有可用记忆 */
    public boolean isEmpty() {
        return facts.isEmpty() && episodes.isEmpty() && skills.isEmpty()
            && preferences.isEmpty() && rawMemories.isEmpty()
            && (workingMemory == null || "ACTIVE".equals(workingMemory.getStatus()) == false);
    }
}
