package com.cloudai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudai.common.BizException;
import com.cloudai.common.Json;
import com.cloudai.entity.OpsEntities.MemoryDoc;
import com.cloudai.entity.OpsEntities.MemoryStore;
import com.cloudai.mapper.Mappers.MemoryDocMapper;
import com.cloudai.mapper.Mappers.MemoryStoreMapper;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * 记忆库文档：文件存放在 data/memory/<space>/<store>/ 下，memory_doc 记录元数据与索引状态。
 * 每次新建/保存/上传后异步调用执行器 embedding 写入 Milvus，并生成 _index/keywords.md 关键词索引。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryService {
    private final MemoryStoreMapper storeMapper;
    private final MemoryDocMapper docMapper;
    private final ExecutorClient executor;
    private final Storage storage;

    public static final Set<String> TEXT_EXT = Set.of("md", "txt", "csv", "json", "yaml", "yml", "py", "js", "ts", "sql", "log", "html", "xml");
    public static final Set<String> EXCEL_EXT = Set.of("xlsx", "xls", "xlsm");

    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "memory-index");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }

    public Path dir(MemoryStore s) {
        return storage.memoryDir(s.getSpaceId(), s.getId());
    }

    public static String norm(String path) {
        String p = path == null ? "" : path.trim().replace('\\', '/');
        p = p.replaceAll("/+", "/");
        if (!p.startsWith("/")) p = "/" + p;
        BizException.check(!p.contains("/../") && !p.endsWith("/..") && p.length() > 1, "文件路径不合法");
        BizException.check(!p.startsWith("/_index/") , "_index 为系统目录");
        return p;
    }

    public static String ext(String path) {
        int i = path.lastIndexOf('.');
        return i < 0 ? "" : path.substring(i + 1).toLowerCase();
    }

    public MemoryDoc find(String storeId, String path) {
        return docMapper.selectOne(new LambdaQueryWrapper<MemoryDoc>().eq(MemoryDoc::getStoreId, storeId).eq(MemoryDoc::getPath, path));
    }

    /** 写文本文档（新建或覆盖）并触发索引 */
    public MemoryDoc write(MemoryStore s, String path, String content, String updatedBy) {
        String p = norm(path);
        BizException.check(TEXT_EXT.contains(ext(p)), "支持 .md/.txt/.csv/.json/.yaml/.py/.js/.ts 等文本格式");
        Path f = Storage.within(dir(s), p);
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, content == null ? "" : content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BizException("写入失败：" + e.getMessage());
        }
        return upsert(s, p, f, updatedBy, true);
    }

    /** 写入二进制原文件（Excel 等）；Excel 会再转换成同名 .md 用于检索与浏览 */
    public List<MemoryDoc> writeUpload(MemoryStore s, String dirPath, String fileName, byte[] bytes, String updatedBy) {
        String base = dirPath == null || dirPath.isBlank() ? "" : norm(dirPath);
        String p = norm(base + "/" + Storage.safe(fileName));
        String e = ext(p);
        List<MemoryDoc> out = new ArrayList<>();
        if (TEXT_EXT.contains(e)) {
            out.add(write(s, p, new String(bytes, StandardCharsets.UTF_8), updatedBy));
            return out;
        }
        BizException.check(EXCEL_EXT.contains(e), "支持文本文件及 Excel（.xlsx .xls .xlsm）");
        Path f = Storage.within(dir(s), p);
        try {
            Files.createDirectories(f.getParent());
            Files.write(f, bytes);
        } catch (IOException ex) {
            throw new BizException("写入失败：" + ex.getMessage());
        }
        MemoryDoc raw = upsert(s, p, f, updatedBy, false);
        raw.setIndexStatus("skipped");
        docMapper.updateById(raw);
        out.add(raw);
        JsonNode r = executor.call("POST", "/docs/convert", Json.obj().put("file_path", f.toString()), 120);
        String mdPath = p.substring(0, p.lastIndexOf('.')) + ".md";
        out.add(write(s, mdPath, r.path("text").asText(""), updatedBy));
        return out;
    }

    private MemoryDoc upsert(MemoryStore s, String p, Path f, String updatedBy, boolean index) {
        MemoryDoc d = find(s.getId(), p);
        long size;
        try {
            size = Files.size(f);
        } catch (IOException e) {
            size = 0;
        }
        if (d == null) {
            d = new MemoryDoc();
            d.setStoreId(s.getId());
            d.setPath(p);
            d.setHidden(p.substring(p.lastIndexOf('/') + 1).startsWith("."));
            d.setChunkCount(0);
        }
        d.setSize(size);
        d.setUpdatedBy(updatedBy);
        d.setIndexStatus(index ? "pending" : "skipped");
        d.setIndexError(null);
        d.setUpdatedAt(LocalDateTime.now());
        if (d.getId() == null) docMapper.insert(d);
        else docMapper.updateById(d);
        if (index) indexAsync(s, d);
        return d;
    }

    public void indexAsync(MemoryStore s, MemoryDoc d) {
        Long id = d.getId();
        pool.submit(() -> index(s, id));
    }

    private void index(MemoryStore s, Long docId) {
        MemoryDoc d = docMapper.selectById(docId);
        if (d == null) return;
        try {
            Path f = Storage.within(dir(s), d.getPath());
            String text = Files.readString(f, StandardCharsets.UTF_8);
            JsonNode r = executor.call("POST", "/embed/index", Json.obj().put("space_id", s.getSpaceId()).put("store_id", s.getId())
                    .put("doc_id", d.getId()).put("path", d.getPath()).put("text", text), 600);
            d.setIndexStatus("indexed");
            d.setChunkCount(r.path("chunks").asInt());
            d.setIndexError(null);
        } catch (Exception e) {
            d.setIndexStatus("failed");
            d.setIndexError(RunService.truncate(e.getMessage(), 1000));
        }
        docMapper.updateById(d);
    }

    public void delete(MemoryStore s, String path) {
        String p = norm(path);
        List<MemoryDoc> docs = docMapper.selectList(new LambdaQueryWrapper<MemoryDoc>().eq(MemoryDoc::getStoreId, s.getId())
                .and(w -> w.eq(MemoryDoc::getPath, p).or().likeRight(MemoryDoc::getPath, p + "/")));
        for (MemoryDoc d : docs) {
            try {
                executor.call("POST", "/embed/delete", Json.obj().put("store_id", s.getId()).put("doc_id", d.getId())
                        .put("space_id", s.getSpaceId()).put("path", d.getPath()), 30);
            } catch (Exception e) {
                log.warn("delete vectors failed: {}", e.getMessage());
            }
            docMapper.deleteById(d.getId());
        }
        Path target = Storage.within(dir(s), p);
        Storage.deleteRecursively(target);
    }

    /** 同步磁盘：Agent 可能直接在目录中写文件，补建记录并索引；磁盘已删除的移除记录 */
    public void syncDisk(MemoryStore s) {
        Path root = dir(s);
        Map<String, MemoryDoc> known = new HashMap<>();
        for (MemoryDoc d : docMapper.selectList(new LambdaQueryWrapper<MemoryDoc>().eq(MemoryDoc::getStoreId, s.getId()))) known.put(d.getPath(), d);
        Set<String> seen = new HashSet<>();
        if (Files.isDirectory(root)) {
            try (Stream<Path> w = Files.walk(root)) {
                for (Path f : w.filter(Files::isRegularFile).toList()) {
                    String rel = "/" + root.relativize(f).toString().replace('\\', '/');
                    if (rel.startsWith("/_index/")) continue;
                    seen.add(rel);
                    MemoryDoc d = known.get(rel);
                    if (d == null) {
                        boolean text = TEXT_EXT.contains(ext(rel));
                        upsert(s, rel, f, "agent", text);
                    } else {
                        long size = Files.size(f);
                        if (size != (d.getSize() == null ? -1 : d.getSize()) && TEXT_EXT.contains(ext(rel))) upsert(s, rel, f, "agent", true);
                    }
                }
            } catch (IOException e) {
                log.warn("sync memory dir failed: {}", e.getMessage());
            }
        }
        for (var e : known.entrySet()) if (!seen.contains(e.getKey())) docMapper.deleteById(e.getValue().getId());
    }

    public void reindexAll(MemoryStore s) {
        for (MemoryDoc d : docMapper.selectList(new LambdaQueryWrapper<MemoryDoc>().eq(MemoryDoc::getStoreId, s.getId()))) {
            if (TEXT_EXT.contains(ext(d.getPath()))) {
                d.setIndexStatus("pending");
                docMapper.updateById(d);
                indexAsync(s, d);
            }
        }
    }

    public MemoryStore loadStore(String spaceId, String id) {
        MemoryStore s = storeMapper.selectById(id);
        if (s == null || !s.getSpaceId().equals(spaceId)) throw BizException.notFound("记忆库");
        return s;
    }
}
