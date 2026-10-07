package com.cloudai.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudai.auth.Perm;
import com.cloudai.auth.RequirePerm;
import com.cloudai.auth.UserContext;
import com.cloudai.common.BizException;
import com.cloudai.common.Json;
import com.cloudai.common.R;
import com.cloudai.entity.OpsEntities.MemoryDoc;
import com.cloudai.entity.OpsEntities.MemoryStore;
import com.cloudai.mapper.Mappers.MemoryDocMapper;
import com.cloudai.mapper.Mappers.MemoryStoreMapper;
import com.cloudai.service.ExecutorClient;
import com.cloudai.service.MemoryService;
import com.cloudai.service.Storage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** 记忆库（知识库）+ 向量库查询 + 执行器回调 */
@RestController
@RequiredArgsConstructor
public class MemoryController {
    private final MemoryStoreMapper storeMapper;
    private final MemoryDocMapper docMapper;
    private final MemoryService memoryService;
    private final ExecutorClient executor;
    private final JdbcTemplate jdbc;

    // ================================================================ 记忆库

    @GetMapping("/api/memory-stores")
    @RequirePerm(Perm.MEMORY_VIEW)
    public R<Map<String, Object>> list(@RequestParam(defaultValue = "compat") String mode, @RequestParam(defaultValue = "default") String namespace,
                                       @RequestParam(required = false) String q, @RequestParam(defaultValue = "false") boolean archived) {
        String sid = UserContext.spaceId();
        var w = new LambdaQueryWrapper<MemoryStore>().eq(MemoryStore::getSpaceId, sid).eq(MemoryStore::getMode, mode).eq(MemoryStore::getNamespace, namespace);
        if (!archived) w.eq(MemoryStore::getStatus, "active");
        if (q != null && !q.isBlank()) w.and(x -> x.like(MemoryStore::getName, q).or().like(MemoryStore::getDescription, q));
        w.orderByDesc(MemoryStore::getCreatedAt);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MemoryStore s : storeMapper.selectList(w)) {
            Map<String, Object> m = row(s);
            Map<String, Object> st = jdbc.queryForMap("SELECT COUNT(*) docs, COALESCE(SUM(chunk_count),0) chunks, SUM(index_status='failed') failed FROM memory_doc WHERE store_id = ?", s.getId());
            m.putAll(Map.of("docCount", st.get("docs"), "chunkCount", st.get("chunks"), "failedCount", st.get("failed") == null ? 0 : st.get("failed")));
            rows.add(m);
        }
        Long active = jdbc.queryForObject("SELECT COUNT(*) FROM memory_store WHERE space_id = ? AND status = 'active'", Long.class, sid);
        List<String> namespaces = jdbc.queryForList("SELECT DISTINCT namespace FROM memory_store WHERE space_id = ?", String.class, sid);
        return R.ok(Map.of("records", rows, "active", active == null ? 0 : active, "namespaces", namespaces.isEmpty() ? List.of("default") : namespaces));
    }

    private Map<String, Object> row(MemoryStore s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("name", s.getName());
        m.put("description", s.getDescription());
        m.put("provider", s.getProvider());
        m.put("namespace", s.getNamespace());
        m.put("mode", s.getMode());
        m.put("isolation", s.getIsolation());
        m.put("status", s.getStatus());
        m.put("createdAt", s.getCreatedAt());
        m.put("updatedAt", s.getUpdatedAt());
        return m;
    }

    @GetMapping("/api/memory-stores/options")
    public R<List<Map<String, Object>>> options() {
        return R.ok(storeMapper.selectList(new LambdaQueryWrapper<MemoryStore>().eq(MemoryStore::getSpaceId, UserContext.spaceId())
                .eq(MemoryStore::getStatus, "active").orderByDesc(MemoryStore::getCreatedAt)).stream().map(this::row).toList());
    }

    @GetMapping("/api/memory-stores/{id}")
    @RequirePerm(Perm.MEMORY_VIEW)
    public R<Map<String, Object>> get(@PathVariable String id) {
        return R.ok(row(memoryService.loadStore(UserContext.spaceId(), id)));
    }

    @Data
    public static class StoreReq {
        private String name;
        private String description;
        private String mode;
        private String isolation;
        private String namespace;
    }

    @PostMapping("/api/memory-stores")
    @RequirePerm(Perm.MEMORY_EDIT)
    public R<Map<String, Object>> create(@RequestBody StoreReq req) {
        BizException.check(req.name != null && !req.name.isBlank(), "请填写名称");
        MemoryStore s = new MemoryStore();
        s.setId(Json.uuid());
        s.setSpaceId(UserContext.spaceId());
        s.setName(req.name.trim());
        s.setDescription(req.description);
        s.setProvider("openviking");
        s.setNamespace(req.namespace == null || req.namespace.isBlank() ? "default" : req.namespace.trim());
        s.setMode("native".equals(req.mode) ? "native" : "compat");
        s.setIsolation("user".equals(req.isolation) ? "user" : "shared");
        s.setStatus("active");
        s.setCreatedBy(UserContext.userId());
        storeMapper.insert(s);
        return R.ok(row(s));
    }

    @PutMapping("/api/memory-stores/{id}")
    @RequirePerm(Perm.MEMORY_EDIT)
    public R<Map<String, Object>> update(@PathVariable String id, @RequestBody StoreReq req) {
        MemoryStore s = memoryService.loadStore(UserContext.spaceId(), id);
        if (req.name != null && !req.name.isBlank()) s.setName(req.name.trim());
        if (req.description != null) s.setDescription(req.description);
        if (req.isolation != null) s.setIsolation("user".equals(req.isolation) ? "user" : "shared");
        if (req.namespace != null && !req.namespace.isBlank()) s.setNamespace(req.namespace.trim());
        storeMapper.updateById(s);
        return R.ok(row(s));
    }

    @PutMapping("/api/memory-stores/{id}/archive")
    @RequirePerm(Perm.MEMORY_EDIT)
    public R<Void> archive(@PathVariable String id, @RequestBody Map<String, Boolean> body) {
        MemoryStore s = memoryService.loadStore(UserContext.spaceId(), id);
        s.setStatus(Boolean.TRUE.equals(body.get("archived")) ? "archived" : "active");
        storeMapper.updateById(s);
        return R.ok();
    }

    @DeleteMapping("/api/memory-stores/{id}")
    @RequirePerm(Perm.MEMORY_EDIT)
    public R<Void> delete(@PathVariable String id) {
        MemoryStore s = memoryService.loadStore(UserContext.spaceId(), id);
        try {
            executor.call("POST", "/embed/delete", Json.obj().put("store_id", s.getId()), 30);
        } catch (Exception ignored) {
            // 执行器不可用时向量残留不影响功能（检索按 store 过滤）
        }
        docMapper.delete(new LambdaQueryWrapper<MemoryDoc>().eq(MemoryDoc::getStoreId, s.getId()));
        Storage.deleteRecursively(memoryService.dir(s));
        storeMapper.deleteById(s.getId());
        return R.ok();
    }

    // ================================================================ 文档

    @GetMapping("/api/memory-stores/{id}/tree")
    @RequirePerm(Perm.MEMORY_VIEW)
    public R<List<MemoryDoc>> tree(@PathVariable String id, @RequestParam(defaultValue = "false") boolean showHidden,
                                   @RequestParam(required = false) String q) {
        MemoryStore s = memoryService.loadStore(UserContext.spaceId(), id);
        memoryService.syncDisk(s);
        var w = new LambdaQueryWrapper<MemoryDoc>().eq(MemoryDoc::getStoreId, id).orderByAsc(MemoryDoc::getPath);
        if (!showHidden) w.eq(MemoryDoc::getHidden, false);
        List<MemoryDoc> docs = docMapper.selectList(w);
        if (q != null && !q.isBlank()) {
            String k = q.toLowerCase();
            Path root = memoryService.dir(s);
            docs = docs.stream().filter(d -> {
                if (d.getPath().toLowerCase().contains(k)) return true;
                if (!MemoryService.TEXT_EXT.contains(MemoryService.ext(d.getPath()))) return false;
                try {
                    return Files.readString(Storage.within(root, d.getPath()), StandardCharsets.UTF_8).toLowerCase().contains(k);
                } catch (Exception e) {
                    return false;
                }
            }).toList();
        }
        return R.ok(docs);
    }

    @GetMapping("/api/memory-stores/{id}/doc")
    @RequirePerm(Perm.MEMORY_VIEW)
    public R<Map<String, Object>> doc(@PathVariable String id, @RequestParam String path) throws Exception {
        MemoryStore s = memoryService.loadStore(UserContext.spaceId(), id);
        String p = MemoryService.norm(path);
        MemoryDoc d = memoryService.find(id, p);
        BizException.check(d != null, "文档不存在");
        Path f = Storage.within(memoryService.dir(s), p);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("doc", d);
        if (MemoryService.TEXT_EXT.contains(MemoryService.ext(p)) && Files.size(f) < 5L * 1024 * 1024) m.put("content", Files.readString(f, StandardCharsets.UTF_8));
        else m.put("binary", true);
        return R.ok(m);
    }

    @Data
    public static class DocReq {
        private String path;
        private String content;
        private Boolean hidden;
    }

    @PostMapping("/api/memory-stores/{id}/doc")
    @RequirePerm(Perm.MEMORY_EDIT)
    public R<MemoryDoc> createDoc(@PathVariable String id, @RequestBody DocReq req) {
        MemoryStore s = memoryService.loadStore(UserContext.spaceId(), id);
        BizException.check(memoryService.find(id, MemoryService.norm(req.path)) == null, "该路径已存在文档");
        return R.ok(memoryService.write(s, req.path, req.content, UserContext.username()));
    }

    @PutMapping("/api/memory-stores/{id}/doc")
    @RequirePerm(Perm.MEMORY_EDIT)
    public R<MemoryDoc> saveDoc(@PathVariable String id, @RequestBody DocReq req) {
        MemoryStore s = memoryService.loadStore(UserContext.spaceId(), id);
        return R.ok(memoryService.write(s, req.path, req.content, UserContext.username()));
    }

    @PutMapping("/api/memory-stores/{id}/doc/hidden")
    @RequirePerm(Perm.MEMORY_EDIT)
    public R<Void> hide(@PathVariable String id, @RequestBody DocReq req) {
        memoryService.loadStore(UserContext.spaceId(), id);
        MemoryDoc d = memoryService.find(id, MemoryService.norm(req.path));
        BizException.check(d != null, "文档不存在");
        d.setHidden(Boolean.TRUE.equals(req.hidden));
        docMapper.updateById(d);
        return R.ok();
    }

    @DeleteMapping("/api/memory-stores/{id}/doc")
    @RequirePerm(Perm.MEMORY_EDIT)
    public R<Void> deleteDoc(@PathVariable String id, @RequestParam String path) {
        memoryService.delete(memoryService.loadStore(UserContext.spaceId(), id), path);
        return R.ok();
    }

    @PostMapping("/api/memory-stores/{id}/upload")
    @RequirePerm(Perm.MEMORY_EDIT)
    public R<List<MemoryDoc>> upload(@PathVariable String id, @RequestParam("files") List<MultipartFile> files,
                                     @RequestParam(required = false) String dir) throws Exception {
        MemoryStore s = memoryService.loadStore(UserContext.spaceId(), id);
        List<MemoryDoc> out = new ArrayList<>();
        for (MultipartFile f : files) {
            String name = Objects.requireNonNullElse(f.getOriginalFilename(), "file.txt");
            int slash = name.lastIndexOf('/');
            String sub = slash > 0 ? name.substring(0, slash) : "";
            String fileName = slash >= 0 ? name.substring(slash + 1) : name;
            String targetDir = (dir == null ? "" : dir) + (sub.isEmpty() ? "" : "/" + sub);
            out.addAll(memoryService.writeUpload(s, targetDir, fileName, f.getBytes(), UserContext.username()));
        }
        return R.ok(out);
    }

    @PostMapping("/api/memory-stores/{id}/reindex")
    @RequirePerm(Perm.MEMORY_EDIT)
    public R<Void> reindex(@PathVariable String id) {
        memoryService.reindexAll(memoryService.loadStore(UserContext.spaceId(), id));
        return R.ok();
    }

    @GetMapping("/api/memory-stores/{id}/keywords")
    @RequirePerm(Perm.MEMORY_VIEW)
    public R<Map<String, Object>> keywords(@PathVariable String id) throws Exception {
        MemoryStore s = memoryService.loadStore(UserContext.spaceId(), id);
        Path f = memoryService.dir(s).resolve("_index").resolve("keywords.md");
        return R.ok(Map.of("content", Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : ""));
    }

    // ================================================================ 向量库查询

    @Data
    public static class SearchReq {
        private String query;
        private List<String> storeIds;
        private Integer topK;
    }

    @PostMapping("/api/vector/search")
    @RequirePerm(Perm.MEMORY_VIEW)
    public R<Map<String, Object>> search(@RequestBody SearchReq req) {
        String sid = UserContext.spaceId();
        BizException.check(req.query != null && !req.query.isBlank(), "请输入查询内容");
        Map<String, MemoryStore> stores = new HashMap<>();
        for (MemoryStore s : storeMapper.selectList(new LambdaQueryWrapper<MemoryStore>().eq(MemoryStore::getSpaceId, sid).eq(MemoryStore::getStatus, "active"))) {
            stores.put(s.getId(), s);
        }
        List<String> ids = req.storeIds == null || req.storeIds.isEmpty() ? new ArrayList<>(stores.keySet())
                : req.storeIds.stream().filter(stores::containsKey).toList();
        BizException.check(!ids.isEmpty(), "当前空间没有可检索的记忆库");
        ObjectNode body = Json.obj().put("space_id", sid).put("query", req.query).put("top_k", req.topK == null ? 8 : Math.min(50, req.topK));
        ArrayNode arr = body.putArray("store_ids");
        ids.forEach(arr::add);
        long t0 = System.currentTimeMillis();
        JsonNode r = executor.call("POST", "/embed/search", body, 120);
        List<Map<String, Object>> results = new ArrayList<>();
        for (JsonNode h : r.path("results")) {
            MemoryStore s = stores.get(h.path("store_id").asText());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("storeId", h.path("store_id").asText());
            m.put("storeName", s == null ? "" : s.getName());
            m.put("docId", h.path("doc_id").asLong());
            m.put("path", h.path("path").asText());
            m.put("chunkIndex", h.path("chunk_index").asInt());
            m.put("text", h.path("text").asText());
            m.put("score", h.path("score").asDouble());
            results.add(m);
        }
        return R.ok(Map.of("results", results, "model", r.path("model").asText(), "dim", r.path("dim").asInt(), "tookMs", System.currentTimeMillis() - t0));
    }

    @GetMapping("/api/vector/status")
    public R<Map<String, Object>> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            JsonNode h = executor.call("GET", "/health", null, 3);
            m.put("ok", true);
            m.put("model", h.path("embedding_model").asText());
            m.put("dim", h.path("dim").asInt(512));
            m.put("milvus", h.path("milvus").asText());
            m.put("loaded", h.path("embedding_loaded").asBoolean());
        } catch (Exception e) {
            m.put("ok", false);
            m.put("model", "BAAI/bge-small-zh-v1.5");
            m.put("dim", 512);
            m.put("error", e.getMessage());
        }
        return R.ok(m);
    }

    // ================================================================ 执行器回调

    /** Agent 通过 memory_write 写入文档后，执行器通知后端同步记录并重建索引 */
    @PostMapping("/api/internal/memory/{storeId}/changed")
    public R<Void> changed(@PathVariable String storeId, @RequestBody Map<String, String> body) {
        MemoryStore s = storeMapper.selectById(storeId);
        if (s == null) throw BizException.notFound("记忆库");
        memoryService.syncDisk(s);
        MemoryDoc d = memoryService.find(storeId, MemoryService.norm(body.getOrDefault("path", "/")));
        if (d != null && MemoryService.TEXT_EXT.contains(MemoryService.ext(d.getPath()))) {
            d.setUpdatedBy("agent");
            docMapper.updateById(d);
            memoryService.indexAsync(s, d);
        }
        return R.ok();
    }
}
