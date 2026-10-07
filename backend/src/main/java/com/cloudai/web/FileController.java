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
import com.cloudai.entity.ChatSession;
import com.cloudai.entity.OpsEntities.FileInfo;
import com.cloudai.mapper.Mappers.ChatSessionMapper;
import com.cloudai.mapper.Mappers.FileInfoMapper;
import com.cloudai.service.SpaceService;
import com.cloudai.service.Storage;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 文件中心：会话文件（手动上传/聊天附件）与产物文件（Agent 产出），记录所在目录及元数据 */
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {
    private final FileInfoMapper mapper;
    private final ChatSessionMapper sessionMapper;
    private final Storage storage;
    private final SpaceService spaceService;

    @PostMapping
    public R<FileInfo> upload(@RequestParam("file") MultipartFile file,
                              @RequestParam(required = false) String name,
                              @RequestParam(defaultValue = "manual") String source,
                              @RequestParam(required = false) String sessionId) throws Exception {
        String sid = UserContext.spaceId();
        if ("chat".equals(source)) {
            if (!UserContext.has(Perm.SESSION_USE) && !UserContext.has(Perm.FILE_EDIT)) throw BizException.forbidden();
        } else {
            UserContext.require(Perm.FILE_EDIT);
            source = "manual";
        }
        BizException.check(!file.isEmpty(), "文件为空");
        BizException.check(file.getSize() <= 100L * 1024 * 1024, "文件超过 100MB 限制");
        if (sessionId != null && !sessionId.isBlank()) {
            ChatSession s = sessionMapper.selectById(sessionId);
            BizException.check(s != null && sid.equals(s.getSpaceId()), "会话不存在");
        } else sessionId = null;
        String original = Objects.requireNonNullElse(file.getOriginalFilename(), "file");
        String id = Json.uuid();
        Path dir = storage.filesDir(sid, "session").resolve(LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM")));
        Files.createDirectories(dir);
        Path dst = dir.resolve(id.substring(0, 8) + "_" + Storage.safe(original));
        file.transferTo(dst);
        FileInfo f = new FileInfo();
        f.setId(id);
        f.setSpaceId(sid);
        f.setName(name == null || name.isBlank() ? original : name.trim());
        f.setFileName(original);
        f.setCategory("session");
        f.setSource(source);
        f.setSize(file.getSize());
        String ct = file.getContentType();
        String guessed = Storage.guessMime(original);
        f.setMimeType(ct == null || ct.isBlank() || "application/octet-stream".equals(ct) ? guessed : ct);
        f.setDirPath(storage.rel(dir));
        f.setStoragePath(storage.rel(dst));
        f.setSessionId(sessionId);
        f.setVersion(1);
        int dot = original.lastIndexOf('.');
        f.setMetadata(Json.obj().put("contentType", ct).put("ext", dot >= 0 ? original.substring(dot + 1).toLowerCase() : ""));
        f.setUploaderId(UserContext.userId());
        mapper.insert(f);
        return R.ok(f);
    }

    @GetMapping
    @RequirePerm(Perm.FILE_VIEW)
    public R<PageResult<Map<String, Object>>> list(@RequestParam(defaultValue = "session") String category,
                                                   @RequestParam(defaultValue = "all") String source,
                                                   @RequestParam(required = false) String q,
                                                   @RequestParam(defaultValue = "1") long page,
                                                   @RequestParam(defaultValue = "20") long size) {
        var w = new LambdaQueryWrapper<FileInfo>().eq(FileInfo::getSpaceId, UserContext.spaceId()).eq(FileInfo::getCategory, category);
        if (!"all".equals(source)) w.eq(FileInfo::getSource, source);
        if (q != null && !q.isBlank()) w.and(x -> x.like(FileInfo::getName, q).or().like(FileInfo::getFileName, q));
        w.orderByDesc(FileInfo::getCreatedAt);
        Page<FileInfo> p = mapper.selectPage(new Page<>(page, size), w);
        Map<String, String> titles = new HashMap<>();
        return R.ok(PageResult.of(p, f -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = Json.M.convertValue(f, Map.class);
            if (f.getSessionId() != null) {
                m.put("sessionTitle", titles.computeIfAbsent(f.getSessionId(), k -> {
                    ChatSession s = sessionMapper.selectById(k);
                    return s == null ? "" : s.getTitle();
                }));
            }
            m.put("uploader", spaceService.userBrief(f.getUploaderId()));
            return m;
        }));
    }

    @GetMapping("/{id}")
    @RequirePerm({Perm.FILE_VIEW, Perm.AGENT_VIEW})
    public R<FileInfo> get(@PathVariable String id) {
        return R.ok(load(id));
    }

    @GetMapping("/{id}/download")
    @RequirePerm({Perm.FILE_VIEW, Perm.AGENT_VIEW})
    public ResponseEntity<Resource> download(@PathVariable String id, @RequestParam(defaultValue = "0") int inline) {
        FileInfo f = load(id);
        Path p = storage.abs(f.getStoragePath());
        BizException.check(Files.isRegularFile(p), "文件已不存在");
        String fn = URLEncoder.encode(f.getFileName(), StandardCharsets.UTF_8).replace("+", "%20");
        String disp = (inline == 1 ? "inline" : "attachment") + "; filename*=UTF-8''" + fn;
        MediaType mt;
        try {
            mt = MediaType.parseMediaType(f.getMimeType() == null ? "application/octet-stream" : f.getMimeType());
        } catch (Exception e) {
            mt = MediaType.APPLICATION_OCTET_STREAM;
        }
        // 产物由 Agent 生成，内容不可信：可执行脚本的类型（html/svg/xml/js）一律按纯文本内联，并禁止脚本执行
        String sub = mt.getSubtype().toLowerCase();
        boolean active = sub.contains("html") || sub.contains("svg") || sub.contains("xml") || sub.contains("javascript");
        if (inline == 1 && active) mt = MediaType.TEXT_PLAIN;
        if (inline == 1 && mt.getType().equals("text")) mt = new MediaType(mt.getType(), mt.getSubtype(), StandardCharsets.UTF_8);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, disp)
                .header("Content-Security-Policy", "sandbox; default-src 'none'; img-src 'self' data:; style-src 'unsafe-inline'")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(mt).body(new FileSystemResource(p));
    }

    @GetMapping("/{id}/text")
    @RequirePerm({Perm.FILE_VIEW, Perm.AGENT_VIEW})
    public R<Map<String, Object>> text(@PathVariable String id) throws Exception {
        FileInfo f = load(id);
        Path p = storage.abs(f.getStoragePath());
        BizException.check(Files.isRegularFile(p), "文件已不存在");
        BizException.check(Files.size(p) < 5L * 1024 * 1024, "文件超过 5MB，不支持在线预览，请下载查看");
        byte[] b = Files.readAllBytes(p);
        for (int i = 0; i < Math.min(b.length, 4096); i++) BizException.check(b[i] != 0, "二进制文件不支持文本预览");
        return R.ok(Map.of("name", f.getName(), "mimeType", Objects.toString(f.getMimeType(), ""), "content", new String(b, StandardCharsets.UTF_8)));
    }

    @DeleteMapping("/{id}")
    @RequirePerm(Perm.FILE_EDIT)
    public R<Void> delete(@PathVariable String id) throws Exception {
        FileInfo f = load(id);
        Files.deleteIfExists(storage.abs(f.getStoragePath()));
        mapper.deleteById(f.getId());
        return R.ok();
    }

    private FileInfo load(String id) {
        FileInfo f = mapper.selectById(id);
        if (f == null || !UserContext.spaceId().equals(f.getSpaceId())) throw BizException.notFound("文件");
        return f;
    }
}
