package com.ossagent.support.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ossagent.agent.domain.LlmCallSite;
import com.ossagent.agent.domain.LlmFailureReason;
import com.ossagent.agent.domain.LlmTransientException;
import com.ossagent.agent.domain.SandboxPermanentException;
import com.ossagent.agent.domain.SandboxTransientException;
import com.ossagent.agent.domain.WorkspaceException;
import com.ossagent.candidate.domain.ImplementationDeferredException;
import com.ossagent.pullrequest.domain.DraftPrException;
import com.ossagent.pullrequest.domain.ForkPublishException;
import com.ossagent.pullrequest.domain.UpstreamWriteAttemptException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * 운영 경로의 예외가 500 으로 뭉개지지 않는다 — #112 · #98.
 *
 * <p>「upstream 이 움직였다 → 다시 착수」와 「서버 고장」이 같은 500 이면 운영자는 재시도할지 고칠지
 * 모른다. 여기서 보는 것은 <b>대응을 가르는 상태코드와 reason</b>이다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler(
            Clock.fixed(Instant.parse("2026-09-28T00:00:00Z"), ZoneOffset.UTC));

    @Test
    @DisplayName("착수 미룸(#98)은 503 + Retry-After — 후보는 SELECTED 로 되돌아가 있다")
    void 미룸은_503_이다() {
        ResponseEntity<ProblemDetail> response = handler.handleImplementationDeferred(
                new ImplementationDeferredException(7L, "일시 장애로 미룬다 (SandboxTransientException)"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
        assertThat(response.getBody().getProperties()).containsEntry("candidateId", 7L);
    }

    @Test
    @DisplayName("PR 게이트 실패는 409 — 후보는 READY_FOR_PR 그대로이고 사유가 다음 행동이다")
    void draft_pr_실패는_409_다() {
        ProblemDetail detail = handler.handleDraftPr(new DraftPrException("Fork 의 기준 브랜치를 맞추지 못했습니다"));

        assertThat(detail.getStatus()).isEqualTo(409);
        assertThat(detail.getProperties()).containsEntry("reason", "DRAFT_PR");
    }

    @Test
    @DisplayName("S-1 게이트 발화는 500 이되 reason 으로 구분된다 — 재시도하지 말라는 신호")
    void s1_게이트는_구분되는_500_이다_S1() {
        ProblemDetail detail = handler.handleUpstreamWriteAttempt(
                new UpstreamWriteAttemptException("쓰기 대상이 Fork 가 아니다 owner=spring-projects"));

        assertThat(detail.getStatus()).isEqualTo(500);
        assertThat(detail.getProperties()).containsEntry("reason", "S1_GATE");
        assertThat(detail.getDetail()).contains("재시도하지 말고");
    }

    @Test
    @DisplayName("일시 장애(워크스페이스·샌드박스·LLM·GitHub)는 전부 503 + Retry-After 다")
    void 일시_장애는_503_이다() {
        assertThat(handler.handleWorkspace(new WorkspaceException("clone 이 끊겼다")).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(handler.handleSandboxTransient(new SandboxTransientException("이미지 없음")).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        ResponseEntity<ProblemDetail> llm = handler.handleLlmTransient(new LlmTransientException(
                LlmFailureReason.RATE_LIMITED, LlmCallSite.CODE, Duration.ofSeconds(42)));
        assertThat(llm.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(llm.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .as("LLM 이 준 Retry-After 를 호출자에게 그대로 전한다")
                .isEqualTo("42");
    }

    @Test
    @DisplayName("재시도해도 같은 것(샌드박스 영구·Fork 거부)은 422 · 502 다")
    void 영구_실패는_4xx_5xx_로_갈린다() {
        assertThat(handler.handleSandboxPermanent(new SandboxPermanentException("Maven 은 지원하지 않는다")).getStatus())
                .isEqualTo(422);
        assertThat(handler.handleForkPublish(new ForkPublishException("Fork 가 준비되지 않았다")).getStatus())
                .isEqualTo(502);
    }
}
