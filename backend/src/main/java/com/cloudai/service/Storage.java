package com.cloudai.service;

import com.cloudai.common.BizException;
import com.cloudai.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** data 目录布局：.md、Skill、上传文件、记忆文档、会话工作区按类型分目录存放 */
@Service
@RequiredArgsConstructor
public class Storage {
    private final AppProperties props;

    public Path root() {
        return props.dataRoot();
    }

    public Path skillDir(String spaceId, String skillName, String version) {
        return root().resolve("skills").resolve(spaceId).resolve(safe(skillName)).resolve(safe(version));
    }

    public Path filesDir(String spaceId, String category) {
        return root().resolve("files").resolve(spaceId).resolve(category);
    }

    public Path memoryDir(String spaceId, String storeId) {
        return root().resolve("memory").resolve(spaceId).resolve(storeId);
    }

    public Path agentDir(String spaceId, String agentId) {
        return root().resolve("agents").resolve(spaceId).resolve(agentId);
    }

    public Path workspaceDir(String sessionOrKey) {
        return root().resolve("workspaces").resolve(safe(sessionOrKey));
    }

    /** data 根目录下的相对路径 */
    public String rel(Path p) {
        return root().relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    public Path abs(String rel) {
        Path p = root().resolve(rel).normalize();
        if (!p.startsWith(root())) throw new BizException("非法路径");
        return p;
    }

    /** 把用户提供的库内路径解析到 base 下，防止 ../ 越界 */
    public static Path within(Path base, String userPath) {
        String cleaned = userPath == null ? "" : userPath.replace('\\', '/');
        while (cleaned.startsWith("/")) cleaned = cleaned.substring(1);
        Path p = base.resolve(cleaned).normalize();
        if (!p.startsWith(base.normalize())) throw new BizException("非法路径: " + userPath);
        return p;
    }

    public static String safe(String s) {
        return s == null ? "_" : s.replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]", "_");
    }

    public static void deleteRecursively(Path p) {
        if (p == null || !Files.exists(p)) return;
        try (Stream<Path> walk = Files.walk(p)) {
            walk.sorted(Comparator.reverseOrder()).forEach(x -> {
                try {
                    Files.deleteIfExists(x);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    public static String guessMime(String fileName) {
        String n = fileName.toLowerCase();
        if (n.endsWith(".md")) return "text/markdown";
        if (n.endsWith(".txt") || n.endsWith(".log")) return "text/plain";
        if (n.endsWith(".csv")) return "text/csv";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".yaml") || n.endsWith(".yml")) return "application/yaml";
        if (n.endsWith(".html")) return "text/html";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (n.endsWith(".xls")) return "application/vnd.ms-excel";
        if (n.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".py")) return "text/x-python";
        if (n.endsWith(".js") || n.endsWith(".ts")) return "text/javascript";
        try {
            String probe = Files.probeContentType(Path.of(fileName));
            if (probe != null) return probe;
        } catch (Exception ignored) {
        }
        return "application/octet-stream";
    }
}
