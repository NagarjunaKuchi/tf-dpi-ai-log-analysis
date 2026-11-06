package tf.dpi.ai.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tf.dpi.ai.dto.KnowledgeRequestDto;
import tf.dpi.ai.exception.NotFoundException;
import tf.dpi.ai.runbook.KnowledgeIngestionResult;
import tf.dpi.ai.runbook.KnowledgeIngestionService;

@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {

    private final KnowledgeIngestionService service;

    public KnowledgeController(KnowledgeIngestionService service) {
        this.service = service;
    }

    @Operation(summary = "Ingest or update a runbook / incident / procedure (stored UNVERIFIED)")
    @PostMapping
    KnowledgeIngestionResult ingest(@Valid @RequestBody KnowledgeRequestDto body) {
        return service.ingest(body.docKey(), body.kind(), body.title(), body.sourceRef(), body.content());
    }

    @Operation(summary = "Engineer validation of one knowledge document version")
    @PostMapping("/{docKey}/versions/{version}/validate")
    ResponseEntity<Void> validate(@PathVariable String docKey, @PathVariable int version, Authentication auth) {
        if (!service.validate(docKey, version, auth.getName())) {
            throw new NotFoundException("knowledge document version not found");
        }
        return ResponseEntity.noContent().build();
    }
}
