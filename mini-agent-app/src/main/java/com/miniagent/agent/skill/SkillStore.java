package com.miniagent.agent.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Skill 存储 - 扫描 skills 目录、解析 SKILL.md 前言、管理 CRUD
 *
 * 目录结构（对齐 hermes-agent）:
 *   ~/.mini-agent/skills/
 *   +-- my-skill/
 *   |   +-- SKILL.md           # 主文件（YAML 前言 + Markdown 指令）
 *   |   +-- references/        # 补充文档
 *   |   +-- templates/         # 模板
 *   +-- category/
 *       +-- another-skill/
 *           +-- SKILL.md
 */
public class SkillStore {

    private static final Logger log = LoggerFactory.getLogger(SkillStore.class);

    private final Path skillsDir;

    private volatile List<Map<String, String>> cachedSkillList = Collections.emptyList();
    private volatile long lastScanTime = 0;
    private static final long CACHE_TTL_MS = 30_000;

    public SkillStore(Path skillsDir) {
        this.skillsDir = skillsDir;
        ensureDir(skillsDir);
        log.info("SkillStore initialized: skillsDir={}", skillsDir);
    }

    public Path getSkillsDir() {
        return skillsDir;
    }

    // ========== 查询 ==========

    public List<Map<String, String>> listSkills() {
        if (System.currentTimeMillis() - lastScanTime > CACHE_TTL_MS) {
            refreshCache();
        }
        return cachedSkillList;
    }

    public String getSkillListSummary() {
        List<Map<String, String>> skills = listSkills();
        if (skills.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("# Available Skills\n\n");
        for (Map<String, String> skill : skills) {
            sb.append("- ").append(skill.get("name"));
            String desc = skill.get("description");
            if (desc != null && !desc.isEmpty()) {
                sb.append(": ").append(desc);
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    public String viewSkill(String name) {
        Path skillMd = findSkillMd(name);
        if (skillMd == null) {
            return null;
        }
        try {
            return Files.readString(skillMd, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Read skill failed: {}", name, e);
            return null;
        }
    }

    public String viewSkillFile(String name, String relativePath) {
        Path skillDir = findSkillDir(name);
        if (skillDir == null) {
            return null;
        }
        Path target = skillDir.resolve(relativePath).normalize();
        if (!target.startsWith(skillDir)) {
            return "Error: path traversal";
        }
        if (!Files.exists(target)) {
            return null;
        }
        try {
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Read skill file failed: {}/{}", name, relativePath, e);
            return null;
        }
    }

    // ========== 管理 ==========

    @SuppressWarnings("unchecked")
    public Map<String, Object> createSkill(String name, String description, String content, String category) {
        Map<String, Object> result = new LinkedHashMap<>();
        String cleanName = sanitizeName(name);
        if (cleanName.isEmpty()) {
            result.put("success", false);
            result.put("error", "name cannot be empty");
            return result;
        }

        Path skillDir;
        if (category != null && !category.isEmpty()) {
            skillDir = skillsDir.resolve(category).resolve(cleanName);
        } else {
            skillDir = skillsDir.resolve(cleanName);
        }

        if (Files.exists(skillDir)) {
            result.put("success", false);
            result.put("error", "skill already exists: " + cleanName);
            return result;
        }

        try {
            Files.createDirectories(skillDir);
            StringBuilder md = new StringBuilder();
            md.append("---\n");
            md.append("name: ").append(cleanName).append("\n");
            md.append("description: ").append(description != null ? description : "").append("\n");
            md.append("---\n\n");
            if (content != null && !content.isEmpty()) {
                md.append(content);
            } else {
                md.append("# ").append(cleanName).append("\n\nAdd instructions here...\n");
            }
            Files.writeString(skillDir.resolve("SKILL.md"), md.toString(), StandardCharsets.UTF_8);
            refreshCache();
            result.put("success", true);
            result.put("name", cleanName);
            result.put("path", skillDir.toString());
        } catch (IOException e) {
            result.put("success", false);
            result.put("error", "create failed: " + e.getMessage());
        }
        return result;
    }

    public Map<String, Object> editSkill(String name, String newContent) {
        Map<String, Object> result = new LinkedHashMap<>();
        Path skillMd = findSkillMd(name);
        if (skillMd == null) {
            result.put("success", false);
            result.put("error", "skill not found: " + name);
            return result;
        }
        try {
            String existing = Files.readString(skillMd, StandardCharsets.UTF_8);
            int endOfFrontmatter = existing.indexOf("---", 3);
            String frontmatter = endOfFrontmatter > 0 ? existing.substring(0, endOfFrontmatter + 3) : "---\nname: " + name + "\n---";
            String updated = frontmatter + "\n\n" + newContent;
            Files.writeString(skillMd, updated, StandardCharsets.UTF_8);
            refreshCache();
            result.put("success", true);
        } catch (IOException e) {
            result.put("success", false);
            result.put("error", "edit failed: " + e.getMessage());
        }
        return result;
    }

    public Map<String, Object> patchSkill(String name, String oldText, String newText) {
        Map<String, Object> result = new LinkedHashMap<>();
        Path skillMd = findSkillMd(name);
        if (skillMd == null) {
            result.put("success", false);
            result.put("error", "skill not found: " + name);
            return result;
        }
        try {
            String content = Files.readString(skillMd, StandardCharsets.UTF_8);
            if (!content.contains(oldText)) {
                result.put("success", false);
                result.put("error", "text not found");
                return result;
            }
            String updated = content.replace(oldText, newText);
            Files.writeString(skillMd, updated, StandardCharsets.UTF_8);
            result.put("success", true);
        } catch (IOException e) {
            result.put("success", false);
            result.put("error", "patch failed: " + e.getMessage());
        }
        return result;
    }

    public Map<String, Object> deleteSkill(String name) {
        Map<String, Object> result = new LinkedHashMap<>();
        Path skillDir = findSkillDir(name);
        if (skillDir == null) {
            result.put("success", false);
            result.put("error", "skill not found: " + name);
            return result;
        }
        try {
            deleteRecursively(skillDir);
            refreshCache();
            result.put("success", true);
        } catch (IOException e) {
            result.put("success", false);
            result.put("error", "delete failed: " + e.getMessage());
        }
        return result;
    }

    // ========== 内部方法 ==========

    private void refreshCache() {
        List<Map<String, String>> skills = new ArrayList<>();
        if (!Files.isDirectory(skillsDir)) {
            cachedSkillList = skills;
            lastScanTime = System.currentTimeMillis();
            return;
        }
        try (Stream<Path> stream = Files.walk(skillsDir, 3)) {
            stream.filter(p -> p.getFileName().toString().equals("SKILL.md"))
                  .forEach(skillMd -> {
                      Map<String, String> meta = parseMetadata(skillMd);
                      if (meta != null) {
                          Path skillDir2 = skillMd.getParent();
                          Path parentDir = skillDir2.getParent();
                          String category = "";
                          if (parentDir != null && !parentDir.equals(skillsDir)) {
                              category = parentDir.getFileName().toString();
                          }
                          meta.put("category", category);
                          skills.add(meta);
                      }
                  });
        } catch (IOException e) {
            log.error("Scan skills dir failed", e);
        }
        cachedSkillList = skills;
        lastScanTime = System.currentTimeMillis();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseMetadata(Path skillMd) {
        try {
            String content = Files.readString(skillMd, StandardCharsets.UTF_8);
            if (!content.startsWith("---")) {
                return null;
            }
            int end = content.indexOf("---", 3);
            if (end < 0) return null;
            String yamlStr = content.substring(3, end).trim();
            Yaml yaml = new Yaml();
            Map<String, Object> frontmatter = yaml.load(yamlStr);
            if (frontmatter == null) return null;

            String skillName = String.valueOf(frontmatter.getOrDefault("name", ""));
            if (skillName.isEmpty() || "null".equals(skillName)) {
                skillName = skillMd.getParent().getFileName().toString();
            }
            String desc = String.valueOf(frontmatter.getOrDefault("description", ""));
            if ("null".equals(desc)) desc = "";

            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("name", skillName);
            meta.put("description", desc);
            meta.put("path", skillMd.getParent().toString());
            return meta;
        } catch (Exception e) {
            log.debug("Parse SKILL.md failed: {}", skillMd, e);
            return null;
        }
    }

    private Path findSkillMd(String name) {
        Path direct = skillsDir.resolve(name).resolve("SKILL.md");
        if (Files.exists(direct)) return direct;

        try (Stream<Path> stream = Files.walk(skillsDir, 3)) {
            return stream.filter(p -> p.getFileName().toString().equals("SKILL.md"))
                         .filter(p -> {
                             Map<String, String> meta = parseMetadata(p);
                             return meta != null && name.equals(meta.get("name"));
                         })
                         .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private Path findSkillDir(String name) {
        Path skillMd = findSkillMd(name);
        return skillMd != null ? skillMd.getParent() : null;
    }

    private static String sanitizeName(String name) {
        if (name == null) return "";
        return name.toLowerCase()
                   .replaceAll("[^a-z0-9-]", "-")
                   .replaceAll("-{2,}", "-")
                   .replaceAll("^-|-$", "");
    }

    private static void ensureDir(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            // ignore
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            try (Stream<Path> stream = Files.list(dir)) {
                for (Path child : stream.toList()) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(dir);
    }
}
