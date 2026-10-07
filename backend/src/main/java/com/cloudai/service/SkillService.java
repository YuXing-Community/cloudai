package com.cloudai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudai.common.BizException;
import com.cloudai.common.Json;
import com.cloudai.entity.PluginEntities.Skill;
import com.cloudai.entity.PluginEntities.SkillVersion;
import com.cloudai.mapper.Mappers.SkillMapper;
import com.cloudai.mapper.Mappers.SkillVersionMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Skill 包的解析、落盘（data/skills/<space>/<name>/<version>/）与版本管理 */
@Service
@RequiredArgsConstructor
public class SkillService {
    private final SkillMapper skillMapper;
    private final SkillVersionMapper versionMapper;
    private final Storage storage;

    public record Parsed(Map<String, byte[]> files, String name, String description, String version, String metadata) {}

    /** 读取上传内容为 相对路径 -> 字节；文件夹模式 files+paths，或 zip */
    public Map<String, byte[]> read(List<MultipartFile> files, List<String> paths, MultipartFile zip) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        if (zip != null && !zip.isEmpty()) {
            try (ZipInputStream zis = new ZipInputStream(zip.getInputStream(), StandardCharsets.UTF_8)) {
                ZipEntry e;
                long total = 0;
                while ((e = zis.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    String n = clean(e.getName());
                    if (n == null) continue;
                    byte[] b = zis.readAllBytes();
                    total += b.length;
                    BizException.check(total < 200L * 1024 * 1024, "压缩包解压后超过 200MB");
                    out.put(n, b);
                }
            }
        } else if (files != null) {
            for (int i = 0; i < files.size(); i++) {
                String p = paths != null && i < paths.size() && paths.get(i) != null ? paths.get(i) : files.get(i).getOriginalFilename();
                String n = clean(p);
                if (n != null) out.put(n, files.get(i).getBytes());
            }
        }
        BizException.check(!out.isEmpty(), "没有可用的技能文件");
        return stripCommonRoot(out);
    }

    /** 过滤系统文件并防止 zip-slip */
    private static String clean(String raw) {
        if (raw == null) return null;
        String n = raw.replace('\\', '/');
        while (n.startsWith("/")) n = n.substring(1);
        if (n.contains("../") || n.startsWith("..")) return null;
        if (n.startsWith("__MACOSX/") || n.endsWith(".DS_Store") || n.contains("/.git/") || n.startsWith(".git/")) return null;
        return n.isBlank() ? null : n;
    }

    /** 所有文件都在同一个顶层目录下时去掉这一层（选择文件夹或 zip 常见） */
    private static Map<String, byte[]> stripCommonRoot(Map<String, byte[]> in) {
        if (in.containsKey("SKILL.md")) return in;
        String root = null;
        for (String k : in.keySet()) {
            int i = k.indexOf('/');
            if (i < 0) return in;
            String r = k.substring(0, i);
            if (root == null) root = r;
            else if (!root.equals(r)) return in;
        }
        Map<String, byte[]> out = new LinkedHashMap<>();
        int len = root.length() + 1;
        in.forEach((k, v) -> out.put(k.substring(len), v));
        return out;
    }

    public Parsed parse(Map<String, byte[]> files) {
        byte[] md = files.get("SKILL.md");
        if (md == null) {
            for (var e : files.entrySet()) if (e.getKey().equalsIgnoreCase("skill.md")) md = e.getValue();
        }
        BizException.check(md != null, "技能包根目录必须包含 SKILL.md");
        Map<String, String> fm = frontMatter(new String(md, StandardCharsets.UTF_8));
        return new Parsed(files, fm.get("name"), fm.get("description"), fm.get("version"), fm.get("metadata"));
    }

    /** 解析 YAML front matter（--- 包裹），支持多行值与简单嵌套 */
    public static Map<String, String> frontMatter(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        String t = text.replace("\r\n", "\n");
        if (!t.startsWith("---")) return out;
        int end = t.indexOf("\n---", 3);
        if (end < 0) return out;
        String key = null;
        StringBuilder val = new StringBuilder();
        for (String line : t.substring(3, end).split("\n")) {
            if (line.isBlank()) continue;
            boolean top = !line.startsWith(" ") && !line.startsWith("\t") && line.contains(":");
            if (top) {
                if (key != null) out.put(key, unquote(val.toString().trim()));
                key = line.substring(0, line.indexOf(':')).trim();
                val = new StringBuilder(line.substring(line.indexOf(':') + 1).trim());
                if (val.toString().equals("|") || val.toString().equals(">")) val = new StringBuilder();
            } else if (key != null) {
                val.append(val.length() == 0 ? "" : "metadata".equals(key) ? "\n" : " ").append(line.trim());
            }
        }
        if (key != null) out.put(key, unquote(val.toString().trim()));
        return out;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) return s.substring(1, s.length() - 1);
        return s;
    }

    @Transactional
    public Skill save(String spaceId, long userId, Parsed p, String name, String version, String description, String category, List<String> tags) throws IOException {
        String n = (name == null || name.isBlank()) ? p.name() : name.trim();
        BizException.check(n != null && n.matches("^[a-zA-Z0-9][a-zA-Z0-9_\\-.]{0,127}$"), "技能名称只能包含字母、数字、下划线、点和短横线（可在 SKILL.md 的 name 中声明）");
        String v = (version == null || version.isBlank()) ? (p.version() == null || p.version().isBlank() ? "1.0.0" : p.version()) : version.trim();
        v = v.startsWith("v") ? v.substring(1) : v;
        BizException.check(v.matches("^[0-9A-Za-z.\\-]{1,32}$"), "版本号格式错误，例如 1.0.0");
        Skill s = skillMapper.selectOne(new LambdaQueryWrapper<Skill>().eq(Skill::getSpaceId, spaceId).eq(Skill::getName, n));
        String finalV = v;
        if (s != null) {
            long dup = versionMapper.selectCount(new LambdaQueryWrapper<SkillVersion>().eq(SkillVersion::getSkillId, s.getId()).eq(SkillVersion::getVersion, finalV));
            BizException.check(dup == 0, "版本 " + v + " 已存在，请修改版本号");
        }
        Path dir = storage.skillDir(spaceId, n, v);
        Storage.deleteRecursively(dir);
        Files.createDirectories(dir);
        long size = 0;
        for (var e : p.files().entrySet()) {
            Path f = Storage.within(dir, e.getKey());
            Files.createDirectories(f.getParent());
            Files.write(f, e.getValue());
            size += e.getValue().length;
        }
        Files.writeString(dir.resolve(".version"), v);
        boolean created = s == null;
        if (created) {
            s = new Skill();
            s.setId(Json.uuid());
            s.setSpaceId(spaceId);
            s.setName(n);
            s.setAuthorId(userId);
            s.setCallCount(0);
            s.setStatus("active");
            s.setSharedSpaces(Json.arr());
        }
        String desc = description == null || description.isBlank() ? p.description() : description;
        if (desc != null) s.setDescription(desc);
        if (category != null && !category.isBlank()) s.setCategory(category);
        if (tags != null && !tags.isEmpty()) {
            ArrayNode arr = Json.arr();
            tags.stream().map(String::trim).filter(x -> !x.isEmpty()).distinct().forEach(arr::add);
            s.setTags(arr);
        } else if (created) s.setTags(Json.arr());
        s.setCurrentVersion(v);
        if (created) skillMapper.insert(s);
        else skillMapper.updateById(s);
        SkillVersion sv = new SkillVersion();
        sv.setSkillId(s.getId());
        sv.setVersion(v);
        sv.setDirPath(storage.rel(dir));
        sv.setFileCount(p.files().size() + 1);
        sv.setTotalSize(size);
        sv.setCreatedBy(userId);
        versionMapper.insert(sv);
        return s;
    }

    public SkillVersion version(Skill s, String version) {
        String v = version == null || version.isBlank() ? s.getCurrentVersion() : version.replaceFirst("^v", "");
        SkillVersion sv = versionMapper.selectOne(new LambdaQueryWrapper<SkillVersion>().eq(SkillVersion::getSkillId, s.getId()).eq(SkillVersion::getVersion, v));
        if (sv == null) throw BizException.notFound("版本 " + v);
        return sv;
    }

    public List<Map<String, Object>> tree(SkillVersion sv) throws IOException {
        Path root = storage.abs(sv.getDirPath());
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        try (Stream<Path> w = Files.walk(root)) {
            w.filter(p -> !p.equals(root)).sorted().forEach(p -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("path", root.relativize(p).toString().replace('\\', '/'));
                m.put("dir", Files.isDirectory(p));
                try {
                    m.put("size", Files.isDirectory(p) ? 0 : Files.size(p));
                } catch (IOException e) {
                    m.put("size", 0);
                }
                out.add(m);
            });
        }
        return out;
    }

    public byte[] zip(SkillVersion sv, String folderName) throws IOException {
        Path root = storage.abs(sv.getDirPath());
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos, StandardCharsets.UTF_8); Stream<Path> w = Files.walk(root)) {
            for (Path p : w.filter(Files::isRegularFile).toList()) {
                zos.putNextEntry(new ZipEntry(folderName + "/" + root.relativize(p).toString().replace('\\', '/')));
                Files.copy(p, zos);
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }
}
