package com.forgepilot.knowledge;

import java.security.Principal;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import java.util.List;

import com.forgepilot.auth.AccountView;
import com.forgepilot.auth.UserDirectory;
import com.forgepilot.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Project knowledge metadata and member-only public document reading. Raw vectors never leave storage. */
@RestController
@RequestMapping("/api/projects/{projectId}/knowledge/documents")
class KnowledgeController {

    private final KnowledgeService knowledge;
    private final UserDirectory users;

    KnowledgeController(KnowledgeService knowledge, UserDirectory users) {
        this.knowledge = knowledge;
        this.users = users;
    }

    @GetMapping
    List<KnowledgeDocumentView> list(@PathVariable long projectId, Principal principal) {
        return knowledge.listProjectKnowledge(projectId, userIdOf(principal));
    }

    @GetMapping("/{documentId}/content")
    KnowledgeService.DocumentContent content(@PathVariable long projectId, @PathVariable long documentId,
            Principal principal) {
        return knowledge.publicContent(projectId, userIdOf(principal), documentId);
    }

    @GetMapping("/{documentId}/download")
    ResponseEntity<byte[]> download(@PathVariable long projectId, @PathVariable long documentId,
            Principal principal) {
        KnowledgeService.DocumentContent document = knowledge.publicContent(projectId, userIdOf(principal), documentId);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(document.title(), StandardCharsets.UTF_8).build().toString())
                .body(document.text().getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    KnowledgeDocumentView create(@PathVariable long projectId, @Valid @RequestBody DocumentRequest request,
            Principal principal) {
        long actorId = userIdOf(principal);
        long documentId = knowledge.createProjectKnowledge(projectId, actorId, request.title(), request.text());
        return knowledge.document(projectId, actorId, documentId);
    }

    @PostMapping("/{documentId}/promote")
    @ResponseStatus(HttpStatus.CREATED)
    KnowledgeDocumentView promote(@PathVariable long projectId, @PathVariable long documentId,
            Principal principal) {
        long actorId = userIdOf(principal);
        long promotedId = knowledge.promoteToProjectKnowledge(projectId, actorId, documentId);
        return knowledge.document(projectId, actorId, promotedId);
    }

    /**
     * 批量上传不在这里：一次多文件上传就是前端对本控制器 {@code POST} 的 N 次调用，
     * 每次自己一个事务，因此天然逐文件独立、逐文件有结果。做成一个批量端点要么变成
     * 一个横跨 N 次 embedding 外部调用的长事务（一个文件失败会回滚已经成功的九个），
     * 要么只是把前端的循环搬进服务端还得另发明一套逐行结果契约。
     */
    @DeleteMapping("/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable long projectId, @PathVariable long documentId, Principal principal) {
        knowledge.deleteProjectKnowledge(projectId, userIdOf(principal), documentId);
    }

    private long userIdOf(Principal principal) {
        return users.byUsername(principal.getName()).map(AccountView::id)
                .orElseThrow(ApiException::notFound);
    }

    record DocumentRequest(@NotBlank @Size(max = 255) String title, @NotBlank String text) {
    }
}
