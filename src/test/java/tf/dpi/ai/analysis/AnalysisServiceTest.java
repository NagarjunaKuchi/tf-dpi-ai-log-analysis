package tf.dpi.ai.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tf.dpi.ai.exception.CapacityExceededException;
import tf.dpi.ai.exception.ConflictException;
import tf.dpi.ai.exception.NotFoundException;
import tf.dpi.ai.masking.MaskingEngine;
import tf.dpi.ai.masking.MaskingPolicy;
import tf.dpi.ai.persistence.AnalysisRepository;
import tf.dpi.ai.persistence.AnalysisRepository.AnalysisRow;
import tf.dpi.ai.persistence.AnalysisRepository.ReportRow;

class AnalysisServiceTest {

    private final AnalysisRepository repo = mock(AnalysisRepository.class);
    private final MaskingEngine masking = new MaskingEngine(MaskingPolicy.defaults());

    @SuppressWarnings("unchecked")
    private AnalysisService service(java.util.concurrent.ExecutorService ex) {
        return new AnalysisService(repo, mock(org.bsc.langgraph4j.CompiledGraph.class), masking, ex,
                Clock.fixed(Instant.parse("2026-11-06T10:00:00Z"), ZoneOffset.UTC), new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    @Test
    void fullQueueMarksAnalysisFailedAndSignalsCapacity() {
        var ex = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1),
                new ThreadPoolExecutor.AbortPolicy());
        var block = new java.util.concurrent.CountDownLatch(1);
        Runnable blocker = () -> {
            try {
                block.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        };
        ex.execute(blocker);
        ex.execute(blocker);
        try {
            assertThatThrownBy(() -> service(ex).submit("why?", "u", "cid-12345678"))
                    .isInstanceOf(CapacityExceededException.class);
            verify(repo).updateStatus(any(), eq(AnalysisStatus.FAILED), eq("REJECTED_CAPACITY"));
        } finally {
            block.countDown();
            ex.shutdownNow();
        }
    }

    @Test
    void reviewOfAlreadyReviewedReportConflicts() {
        UUID id = UUID.randomUUID();
        when(repo.find(id)).thenReturn(Optional.of(new AnalysisRow(id, "q", "u", AnalysisStatus.AWAITING_REVIEW, null,
                Instant.now(), Instant.now())));
        when(repo.findReport(id)).thenReturn(Optional.of(new ReportRow(id, "{}", "PENDING_REVIEW", null, null, null)));
        when(repo.review(eq(id), any(), any(), any(), any())).thenReturn(false);
        assertThatThrownBy(() -> service(mock(java.util.concurrent.ExecutorService.class))
                .review(id, ReviewDecision.APPROVED, "ok", "rev", "cid-12345678")).isInstanceOf(ConflictException.class);
    }

    @Test
    void unknownAnalysisIsNotFoundAndNothingIsReviewed() {
        UUID id = UUID.randomUUID();
        when(repo.find(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service(mock(java.util.concurrent.ExecutorService.class)).get(id))
                .isInstanceOf(NotFoundException.class);
        verify(repo, never()).review(any(), any(), any(), any(), any());
    }

    @Test
    void reviewCommentIsMaskedBeforeStorage() {
        UUID id = UUID.randomUUID();
        when(repo.find(id)).thenReturn(Optional.of(new AnalysisRow(id, "q", "u", AnalysisStatus.AWAITING_REVIEW, null,
                Instant.now(), Instant.now())));
        when(repo.findReport(id)).thenReturn(Optional.of(new ReportRow(id, "{}", "PENDING_REVIEW", null, null, null)));
        when(repo.review(eq(id), any(), any(), any(), any())).thenReturn(true);
        service(mock(java.util.concurrent.ExecutorService.class)).review(id, ReviewDecision.REJECTED,
                "contact someone@example.com", "rev", "cid-12345678");
        var cap = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(repo).review(eq(id), eq(ReviewDecision.REJECTED), eq("rev"), cap.capture(), any());
        assertThat(cap.getValue()).doesNotContain("someone@example.com");
    }
}
