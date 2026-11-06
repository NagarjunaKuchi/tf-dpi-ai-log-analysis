package tf.dpi.ai.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tf.dpi.ai.analysis.AnalysisService;
import tf.dpi.ai.dto.AnalysisAcceptedDto;
import tf.dpi.ai.dto.AnalysisRequestDto;
import tf.dpi.ai.dto.AnalysisStatusDto;
import tf.dpi.ai.dto.ReviewRequestDto;
import tf.dpi.ai.persistence.AnalysisRepository.AnalysisRow;
import tf.dpi.ai.persistence.AnalysisRepository.ReportRow;
import tf.dpi.ai.security.CorrelationIdFilter;

@RestController
@RequestMapping("/api/v1/analyses")
public class AnalysisController {

    private final AnalysisService service;

    public AnalysisController(AnalysisService service) {
        this.service = service;
    }

    @Operation(summary = "Submit a log-analysis question; processing is asynchronous")
    @PostMapping
    ResponseEntity<AnalysisAcceptedDto> submit(@Valid @RequestBody AnalysisRequestDto body, Authentication auth) {
        UUID id = service.submit(body.question(), auth.getName(), MDC.get(CorrelationIdFilter.MDC_KEY));
        return ResponseEntity.accepted().body(new AnalysisAcceptedDto(id, "QUEUED", "/api/v1/analyses/" + id));
    }

    @Operation(summary = "Status of an analysis")
    @GetMapping("/{id}")
    AnalysisStatusDto status(@PathVariable UUID id) {
        AnalysisRow r = service.get(id);
        return new AnalysisStatusDto(r.analysisId(), r.status().name(), r.failureReason(), r.createdAt(), r.updatedAt());
    }

    @Operation(summary = "Incident report (sanitised, pending engineer review until decided)")
    @GetMapping(value = "/{id}/report", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> report(@PathVariable UUID id) {
        ReportRow r = service.report(id);
        return ResponseEntity.ok().header("X-Review-Status", r.reviewStatus()).body(r.reportJson());
    }

    @Operation(summary = "Engineer decision on a report; the human validation gate")
    @PostMapping("/{id}/review")
    ResponseEntity<Void> review(@PathVariable UUID id, @Valid @RequestBody ReviewRequestDto body, Authentication auth) {
        service.review(id, body.decision(), body.comment(), auth.getName(), MDC.get(CorrelationIdFilter.MDC_KEY));
        return ResponseEntity.noContent().build();
    }
}
