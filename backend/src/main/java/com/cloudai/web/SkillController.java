package com.cloudai.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.auth.Perm;
import com.cloudai.auth.RequirePerm;
import com.cloudai.auth.UserContext;
import com.cloudai.common.BizException;
import com.cloudai.common.Json;
import com.cloudai.common.PageResult;
import com.cloudai.common.R;
import com.cloudai.entity.PluginEntities.Skill;
import com.cloudai.entity.PluginEntities.SkillVersion;
import com.cloudai.entity.Space;
import com.cloudai.entity.SpaceMember;
import com.cloudai.mapper.CoreMappers.SpaceMapper;
import com.cloudai.mapper.CoreMappers.SpaceMemberMapper;
import com.cloudai.mapper.Mappers.SkillMapper;
import com.cloudai.mapper.Mappers.SkillVersionMapper;
import com.cloudai.service.SkillService;
import com.cloudai.service.SpaceService;
import com.cloudai.service.Storage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Skills：上传（文件夹/zip）、版本、文件树、预览、下载、跨空间共享 */
@RestController
@RequestMapping("/api/skills")
@RequiredArgsConstructor
public class SkillController {
    private final SkillMapper skillMapper;
    private final SkillVersionMapper versionMapper;
    private final SpaceMapper spaceMapper;
    private final SpaceMemberMapper memberMapper;
    private final SkillService skillService;
    private final SpaceService spaceService;
    private final Storage storage;

    /** 本空间 + 共享给本空间的 Skill */
    private LambdaQueryWrapper<Skill> visible(String sid) {
        return new LambdaQueryWrapper<Skill>().and(w -> w.eq(Skill::getSpaceId, sid)
                .or().apply("JSON_CONTAINS(COALESCE(shared_spaces, JSON_ARRAY()), JSON_QUOTE({0}))", sid));
    }

    @GetMapping
    @RequirePerm(Perm.PLUGIN_VIEW)
    public R<PageResult<Map<String, Object>>> list(@RequestParam(required = false) String q, @RequestParam(required = false) String tag,
                                                   @RequestParam(defaultValue = "false") boolean showInvalid,
                                                   @RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "12") long size) {
        String sid = UserContext.spaceId();
        var w = visible(sid);
        if (!showInvalid) w.eq(Skill::getStatus, "active");
        if (q != null && !q.isBlank()) w.and(x -> x.like(Skill::getName, q).or().like(Skill::getDescription, q));
        if (tag != null && !tag.isBlank()) w.apply("JSON_CONTAINS(COALESCE(tags, JSON_ARRAY()), JSON_QUOTE({0}))", tag);
        w.orderByDesc(Skill::getUpdatedAt);
        Page<Skill> p = skillMapper.selectPage(new Page<>(page, size), w);
        return R.ok(PageResult.of(p, s -> row(s, sid)));
    }

    private Map<String, Object> row(Skill s, String sid) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("name", s.getName());
        m.put("description", s.getDescription());
        m.put("category", s.getCategory());
        m.put("tags", s.getTags());
        m.put("author", spaceService.userBrief(s.getAuthorId()));
        m.put("currentVersion", s.getCurrentVersion());
        m.put("callCount", s.getCallCount());
        m.put("status", s.getStatus());
        m.put("shared", !sid.equals(s.getSpaceId()));
        m.put("sharedCount", s.getSharedSpaces() == null ? 0 : s.getSharedSpaces().size());
        SkillVersion sv = versionMapper.selectOne(new LambdaQueryWrapper<SkillVersion>().eq(SkillVersion::getSkillId, s.getId())
                .eq(SkillVersion::getVersion, s.getCurrentVersion()));
        m.put("fileCount", sv == null ? 0 : sv.getFileCount());
        m.put("createdAt", s.getCreatedAt());
        m.put("updatedAt", s.getUpdatedAt());
        return m;
    }

    @GetMapping("/tags")
    @RequirePerm(Perm.PLUGIN_VIEW)
    public R<List<String>> tags() {
        Set<String> out = new TreeSet<>();
        for (Skill s : skillMapper.selectList(visible(UserContext.spaceId()))) {
            if (s.getTags() != null) s.getTags().forEach(t -> out.add(t.asText()));
        }
        return R.ok(new ArrayList<>(out));
    }

    @GetMapping("/options")
    public R<List<Map<String, Object>>> options() {
        String sid = UserContext.spaceId();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Skill s : skillMapper.selectList(visible(sid).eq(Skill::getStatus, "active").orderByDesc(Skill::getUpdatedAt))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("name", s.getName());
            m.put("description", s.getDescription());
            m.put("currentVersion", s.getCurrentVersion());
            m.put("versions", versions(s.getId()));
            m.put("scope", sid.equals(s.getSpaceId()) ? "工作空间" : "共享");
            Map<String, Object> a = spaceService.userBrief(s.getAuthorId());
            m.put("author", a == null ? "" : a.get("displayName"));
            out.add(m);
        }
        return R.ok(out);
    }

    private List<String> versions(String skillId) {
        return versionMapper.selectList(new LambdaQueryWrapper<SkillVersion>().eq(SkillVersion::getSkillId, skillId).orderByDesc(SkillVersion::getCreatedAt))
                .stream().map(SkillVersion::getVersion).toList();
    }

    // ------------------------------------------------------------------ 上传

    @PostMapping("/parse")
    @RequirePerm(Perm.PLUGIN_EDIT)
    public R<Map<String, Object>> parse(@RequestParam(value = "files", required = false) List<MultipartFile> files,
                                        @RequestParam(value = "paths", required = false) List<String> paths,
                                        @RequestParam(value = "zip", required = false) MultipartFile zip) throws Exception {
        var p = skillService.parse(skillService.read(files, paths, zip));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.name());
        m.put("description", p.description());
        m.put("version", p.version());
        m.put("files", p.files().entrySet().stream().map(e -> Map.of("path", e.getKey(), "size", e.getValue().length)).toList());
        return R.ok(m);
    }

    @PostMapping("/upload")
    @RequirePerm(Perm.PLUGIN_EDIT)
    public R<Map<String, Object>> upload(@RequestParam(value = "files", required = false) List<MultipartFile> files,
                                         @RequestParam(value = "paths", required = false) List<String> paths,
                                         @RequestParam(value = "zip", required = false) MultipartFile zip,
                                         @RequestParam(required = false) String name,
                                         @RequestParam(required = false) String version,
                                         @RequestParam(required = false) String description,
                                         @RequestParam(required = false) String category,
                                         @RequestParam(required = false) String tags) throws Exception {
        var parsed = skillService.parse(skillService.read(files, paths, zip));
        List<String> tagList = tags == null ? List.of() : Arrays.stream(tags.split("[,，]")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        Skill s = skillService.save(UserContext.spaceId(), UserContext.userId(), parsed, name, version, description, category, tagList);
        return R.ok(Map.of("id", s.getId(), "name", s.getName(), "version", s.getCurrentVersion()));
    }

    // ------------------------------------------------------------------ 详情 / 文件

    @GetMapping("/{id}")
    @RequirePerm(Perm.PLUGIN_VIEW)
    public R<Map<String, Object>> get(@PathVariable String id) {
        String sid = UserContext.spaceId();
        Skill s = loadVisible(id);
        Map<String, Object> m = row(s, sid);
        m.put("versions", versions(id));
        return R.ok(m);
    }

    @GetMapping("/{id}/tree")
    @RequirePerm(Perm.PLUGIN_VIEW)
    public R<List<Map<String, Object>>> tree(@PathVariable String id, @RequestParam(required = false) String version) throws Exception {
        Skill s = loadVisible(id);
        return R.ok(skillService.tree(skillService.version(s, version)));
    }

    @GetMapping("/{id}/file")
    @RequirePerm(Perm.PLUGIN_VIEW)
    public R<Map<String, Object>> file(@PathVariable String id, @RequestParam(required = false) String version, @RequestParam String path) throws Exception {
        Skill s = loadVisible(id);
        Path root = storage.abs(skillService.version(s, version).getDirPath());
        Path f = Storage.within(root, path);
        BizException.check(Files.isRegularFile(f), "文件不存在");
        long size = Files.size(f);
        if (size > 2 * 1024 * 1024) return R.ok(Map.of("path", path, "size", size, "tooLarge", true));
        byte[] b = Files.readAllBytes(f);
        for (int i = 0; i < Math.min(4096, b.length); i++) if (b[i] == 0) return R.ok(Map.of("path", path, "size", size, "binary", true));
        return R.ok(Map.of("path", path, "size", size, "content", new String(b, StandardCharsets.UTF_8)));
    }

    @GetMapping("/{id}/download")
    @RequirePerm(Perm.PLUGIN_VIEW)
    public ResponseEntity<byte[]> download(@PathVariable String id, @RequestParam(required = false) String version) throws Exception {
        Skill s = loadVisible(id);
        SkillVersion sv = skillService.version(s, version);
        byte[] zip = skillService.zip(sv, s.getName());
        String fn = URLEncoder.encode(s.getName() + "-v" + sv.getVersion() + ".zip", StandardCharsets.UTF_8);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fn)
                .contentType(MediaType.parseMediaType("application/zip")).body(zip);
    }

    // ------------------------------------------------------------------ 编辑 / 删除

    @Data
    public static class MetaReq {
        private String description;
        private String category;
        private List<String> tags;
        private String status;
    }

    @PutMapping("/{id}")
    @RequirePerm(Perm.PLUGIN_EDIT)
    public R<Void> update(@PathVariable String id, @RequestBody MetaReq req) {
        Skill s = loadOwn(id);
        if (req.description != null) s.setDescription(req.description);
        if (req.category != null) s.setCategory(req.category);
        if (req.tags != null) {
            ArrayNode a = Json.arr();
            req.tags.stream().map(String::trim).filter(x -> !x.isEmpty()).distinct().forEach(a::add);
            s.setTags(a);
        }
        if (req.status != null) {
            BizException.check(Set.of("active", "invalid").contains(req.status), "状态错误");
            s.setStatus(req.status);
        }
        skillMapper.updateById(s);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @RequirePerm(Perm.PLUGIN_EDIT)
    @Transactional
    public R<Void> delete(@PathVariable String id) {
        Skill s = loadOwn(id);
        for (SkillVersion v : versionMapper.selectList(new LambdaQueryWrapper<SkillVersion>().eq(SkillVersion::getSkillId, id))) {
            Storage.deleteRecursively(storage.abs(v.getDirPath()));
        }
        versionMapper.delete(new LambdaQueryWrapper<SkillVersion>().eq(SkillVersion::getSkillId, id));
        skillMapper.deleteById(s.getId());
        Storage.deleteRecursively(storage.skillDir(s.getSpaceId(), s.getName(), "_").getParent());
        return R.ok();
    }

    // ------------------------------------------------------------------ 共享

    @GetMapping("/share-targets")
    @RequirePerm(Perm.PLUGIN_VIEW)
    public R<List<Map<String, Object>>> shareTargets() {
        String sid = UserContext.spaceId();
        List<Map<String, Object>> out = new ArrayList<>();
        for (SpaceMember m : memberMapper.selectList(new LambdaQueryWrapper<SpaceMember>().eq(SpaceMember::getUserId, UserContext.userId()))) {
            if (sid.equals(m.getSpaceId())) continue;
            Space s = spaceMapper.selectById(m.getSpaceId());
            if (s != null) out.add(Map.of("id", s.getId(), "name", s.getName()));
        }
        return R.ok(out);
    }

    @GetMapping("/{id}/shares")
    @RequirePerm(Perm.PLUGIN_VIEW)
    public R<List<Map<String, Object>>> shares(@PathVariable String id) {
        Skill s = loadOwn(id);
        List<Map<String, Object>> out = new ArrayList<>();
        if (s.getSharedSpaces() != null) for (JsonNode n : s.getSharedSpaces()) {
            Space sp = spaceMapper.selectById(n.asText());
            out.add(Map.of("id", n.asText(), "name", sp == null ? "已删除的空间" : sp.getName()));
        }
        return R.ok(out);
    }

    @PostMapping("/{id}/shares")
    @RequirePerm(Perm.PLUGIN_EDIT)
    public R<Void> share(@PathVariable String id, @RequestBody Map<String, String> body) {
        Skill s = loadOwn(id);
        String target = body.get("spaceId");
        BizException.check(target != null && !target.equals(s.getSpaceId()), "请选择其他工作空间");
        boolean member = memberMapper.selectCount(new LambdaQueryWrapper<SpaceMember>().eq(SpaceMember::getSpaceId, target)
                .eq(SpaceMember::getUserId, UserContext.userId())) > 0;
        BizException.check(member, "只能授权给你所在的工作空间");
        ArrayNode a = s.getSharedSpaces() == null ? Json.arr() : (ArrayNode) s.getSharedSpaces();
        for (JsonNode n : a) if (target.equals(n.asText())) return R.ok();
        a.add(target);
        s.setSharedSpaces(a);
        skillMapper.updateById(s);
        return R.ok();
    }

    @DeleteMapping("/{id}/shares/{spaceId}")
    @RequirePerm(Perm.PLUGIN_EDIT)
    public R<Void> unshare(@PathVariable String id, @PathVariable String spaceId) {
        Skill s = loadOwn(id);
        ArrayNode a = Json.arr();
        if (s.getSharedSpaces() != null) for (JsonNode n : s.getSharedSpaces()) if (!spaceId.equals(n.asText())) a.add(n.asText());
        s.setSharedSpaces(a);
        skillMapper.updateById(s);
        return R.ok();
    }

    // ------------------------------------------------------------------

    private Skill loadVisible(String id) {
        Skill s = skillMapper.selectById(id);
        String sid = UserContext.spaceId();
        if (s == null) throw BizException.notFound("Skill");
        boolean shared = false;
        if (s.getSharedSpaces() != null) for (JsonNode n : s.getSharedSpaces()) shared |= sid.equals(n.asText());
        if (!sid.equals(s.getSpaceId()) && !shared) throw BizException.notFound("Skill");
        return s;
    }

    private Skill loadOwn(String id) {
        Skill s = skillMapper.selectById(id);
        if (s == null || !UserContext.spaceId().equals(s.getSpaceId())) throw new BizException(404, "Skill 不存在或为共享技能（只读）");
        return s;
    }
}
