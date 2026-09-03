package com.qilu.controller;

import com.qilu.dto.Result;
import com.qilu.dto.TicketEvaluationDTO;
import com.qilu.entity.ServiceTicket;
import com.qilu.entity.TicketComment;
import com.qilu.service.IServiceTicketService;
import com.qilu.service.ITicketCommentService;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/ticket")
public class TicketController {

    private static final long MAX_ATTACHMENT_SIZE = 20L * 1024 * 1024;
    private static final File ATTACHMENT_DIR = new File("D:\\JAVATXT\\QiLu-LifeServer-upgrade\\.run\\campus-ticket-attachments");
    private static final Set<String> ALLOWED_ATTACHMENT_SUFFIXES = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "gif", "webp", "bmp",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt",
            "zip", "rar", "7z"
    ));

    @Resource
    private IServiceTicketService serviceTicketService;

    @Resource
    private ITicketCommentService ticketCommentService;

    @PostMapping
    public Result createTicket(@RequestBody ServiceTicket ticket) {
        return serviceTicketService.createTicket(ticket);
    }

    @PostMapping("/attachment")
    public Result uploadAttachment(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return Result.fail("attachment file is required");
        }
        if (file.getSize() > MAX_ATTACHMENT_SIZE) {
            return Result.fail("attachment file is too large");
        }
        String originalFilename = FileUtil.getName(file.getOriginalFilename());
        String suffix = StrUtil.subAfter(originalFilename, ".", true);
        if (StrUtil.isBlank(suffix) || !ALLOWED_ATTACHMENT_SUFFIXES.contains(suffix.toLowerCase())) {
            return Result.fail("attachment file type is not supported");
        }
        if (!ATTACHMENT_DIR.exists() && !ATTACHMENT_DIR.mkdirs()) {
            return Result.fail("attachment directory is not available");
        }
        String storedName = UUID.randomUUID() + "." + suffix.toLowerCase();
        File target = new File(ATTACHMENT_DIR, storedName);
        try {
            file.transferTo(target);
        } catch (IOException e) {
            return Result.fail("attachment upload failed");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("name", originalFilename);
        data.put("url", "/ticket/attachment/" + storedName);
        data.put("size", file.getSize());
        data.put("type", file.getContentType());
        return Result.ok(data);
    }

    @GetMapping("/attachment/{fileName}")
    public ResponseEntity<org.springframework.core.io.Resource> downloadAttachment(@PathVariable("fileName") String fileName) {
        String safeName = FileUtil.getName(fileName);
        File file = new File(ATTACHMENT_DIR, safeName);
        if (!file.isFile()) {
            return ResponseEntity.notFound().build();
        }
        FileSystemResource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(safeName, StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(resource);
    }

    @GetMapping("/mine")
    public Result queryMyTickets(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return serviceTicketService.queryMyTickets(current);
    }

    @GetMapping("/{id}")
    public Result queryTicketDetail(@PathVariable("id") Long id) {
        return serviceTicketService.queryTicketDetail(id);
    }

    @DeleteMapping("/{id}")
    public Result hideMyTicket(@PathVariable("id") Long id) {
        return serviceTicketService.hideMyTicket(id);
    }

    @PostMapping("/{id}/comment")
    public Result addComment(@PathVariable("id") Long id, @RequestBody TicketComment comment) {
        comment.setTicketId(id);
        return ticketCommentService.addComment(comment);
    }

    @PostMapping("/{id}/evaluate")
    public Result evaluateTicket(@PathVariable("id") Long id, @RequestBody TicketEvaluationDTO evaluation) {
        return serviceTicketService.evaluateTicket(id, evaluation.getRating(), evaluation.getEvaluation());
    }
}
