package com.ossagent.support.web;

import com.ossagent.candidate.domain.CandidateNotFoundException;
import com.ossagent.candidate.domain.CandidateTransitionException;
import com.ossagent.candidate.domain.ImplementationNotReadyException;
import com.ossagent.repository.domain.ContributionNotAllowedException;
import com.ossagent.repository.domain.PolicyResolutionRejectedException;
import com.ossagent.repository.domain.RepositoryAlreadyRegisteredException;
import com.ossagent.repository.domain.RepositoryNotScannableException;
import com.ossagent.repository.domain.ScanAlreadyRunningException;
import com.ossagent.repository.domain.RepositoryNotFoundException;
import com.ossagent.repository.domain.RepositoryPolicyNotFoundException;
import com.ossagent.support.github.GitHubRateLimitException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * HTTP 매핑은 여기 한 곳에만 둔다.
 *
 * <p>도메인 예외에 {@code @ResponseStatus} 를 달면 domain 이 HTTP 를 알게 되고,
 * 같은 예외를 스케줄러·이벤트 진입점에서 던질 때 의미가 어긋난다 —
 * {@code .claude/rules/conventions/architecture.md} 규율 ①.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private final Clock clock;

    public ApiExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    /**
     * GitHub 레이트리밋 — <b>503 + {@code Retry-After}</b>. 실패가 아니라 <b>지연</b>이다.
     *
     * <p>사람이 트리거하는 경로(착수 · PR 생성)가 규약·컨텍스트·Fork 를 읽다 리밋에 닿으면 여기로 온다.
     * PLAN-15 가 「첫 소비자가 이것을 {@code FAILED} 로 받으면 그 번역이 위반」이라고 #16 에 넘겼는데
     * #16 이 받지 않아, 실제로는 착수 중 리밋 한 번이 후보를 <b>종단 {@code FAILED}</b> 로 보냈다.
     * 지금은 착수 UseCase 가 대외 읽기를 전이 <b>앞</b>에 두어 후보가 {@code SELECTED} 에 그대로 남고,
     * 이 매핑이 「언제 다시 부르면 되는가」를 알려 준다.
     *
     * <p>⚠️ 429 가 아니다. 우리 API 가 호출자를 제한하는 것이 아니라 <b>우리가 의존하는 쪽</b>이
     * 잠긴 것이다 — 4xx 로 주면 호출자가 자기 요청을 고치려 든다.
     */
    @ExceptionHandler(GitHubRateLimitException.class)
    public ResponseEntity<ProblemDetail> handleRateLimit(GitHubRateLimitException e) {
        Instant now = clock.instant();
        Instant retryAt = e.earliestRetryAt(now);
        long seconds = retryAt == null ? DEFAULT_RETRY_AFTER.toSeconds()
                : Math.max(1, Duration.between(now, retryAt).toSeconds());
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "GitHub 레이트리밋(%s)에 닿았습니다 — 후보는 그대로 있습니다. %d초 뒤 다시 요청하세요"
                        .formatted(e.scope(), seconds));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds))
                .body(detail);
    }

    /** {@code Retry-After} 도 {@code resetAt} 도 없을 때 — 스캔 쪽(#8)과 같은 보수적 기본값. */
    private static final Duration DEFAULT_RETRY_AFTER = Duration.ofMinutes(5);

    @ExceptionHandler(RepositoryNotFoundException.class)
    public ProblemDetail handleNotFound(RepositoryNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(CandidateNotFoundException.class)
    public ProblemDetail handleCandidateNotFound(CandidateNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /**
     * 🔴 <b>409 이지 500 이 아니다.</b> 스캔이 이미 돌고 있거나 큐가 찼다는 것은
     * <b>정상 상태</b>이고, 호출자는 잠시 뒤 다시 부르면 된다 — #14 FR-4.
     *
     * <p>{@code reason} 을 본문에 실어 「이미 돌고 있음」과 「큐가 참」을 가른다.
     * 둘 다 「지금은 안 된다」이지만 대응이 다르다 — 전자는 기다리면 되고,
     * 후자는 동시 실행 한도(NFR-2)에 부딪힌 것이다.
     */
    /** {@code enabled = false} 인 저장소 — 「지금은 안 된다」가 아니라 「보고 있지 않다」다. */
    @ExceptionHandler(RepositoryNotScannableException.class)
    public ProblemDetail handleNotScannable(RepositoryNotScannableException e) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setProperty("reason", "REPOSITORY_DISABLED");
        return problem;
    }

    @ExceptionHandler(ScanAlreadyRunningException.class)
    public ProblemDetail handleScanAlreadyRunning(ScanAlreadyRunningException e) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setProperty("reason", e.reason().name());
        return problem;
    }

    @ExceptionHandler(RepositoryAlreadyRegisteredException.class)
    public ProblemDetail handleConflict(RepositoryAlreadyRegisteredException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    /**
     * 정책 행이 아직 없다 — 404. <b>분석을 먼저 돌려야 한다</b>(S-5).
     *
     * <p>⚠️ 아래 {@link #handleResolutionRejected}(409)와 <b>다른 상태다.</b>
     * 409 는 「해소할 수 없는 상태」이고 이쪽은 「해소할 대상이 없다」다.
     * 둘을 같은 코드로 뭉개면 호출자가 「분석을 돌려라」와 「이미 금지다」를 구분하지 못하고,
     * 「그럼 다시 해소해 보자」로 흘러간다.
     */
    @ExceptionHandler(RepositoryPolicyNotFoundException.class)
    public ProblemDetail handlePolicyNotFound(RepositoryPolicyNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /**
     * 허용되지 않은 상태 전이 — 409. 승인 게이트를 두 번 누른 경우가 여기다 (S-6).
     *
     * <p>🔴 <b>200 으로 뭉개지 않는다.</b> {@code SELECTED} 인 후보에 {@code select} 를 다시
     * 불렀을 때 「이미 그 상태니 성공」으로 돌려주면 <b>승인을 몇 번 눌러도 같다</b>가 되어,
     * 「사람이 한 번 골랐다」를 사후에 셀 수 없게 된다.
     *
     * <p>메시지에는 상태 이름과 식별자만 들어간다 — 도메인 예외가 그렇게 만든다(S-4).
     */
    @ExceptionHandler(CandidateTransitionException.class)
    public ProblemDetail handleTransitionConflict(CandidateTransitionException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    /** 해소할 수 없는 상태 — 409 (Q-8 · #24). */
    @ExceptionHandler(PolicyResolutionRejectedException.class)
    public ProblemDetail handleResolutionRejected(PolicyResolutionRejectedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    /**
     * 같은 행을 둘이 동시에 바꿨다 — 409.
     *
     * <p>🔴 <b>500 으로 새어 나가면 안 된다.</b> 승인 버튼을 두 번 누른 것과 같은 종류의
     * 정상적인 경합이고, 「서버 오류」로 보이면 <b>다시 눌러 보게</b> 된다.
     *
     * <p>⚠️ Spring Data 가 {@code ObjectOptimisticLockingFailureException} 으로 번역하므로
     * 상위 타입인 {@link OptimisticLockingFailureException} 으로 받는다 — 하위 타입만 걸면
     * JPA 밖(그리고 다른 번역 경로)에서 온 같은 성격의 예외를 놓친다.
     *
     * <p>⚠️ <b>메시지를 그대로 내보내지 않는다.</b> 영속 계층 예외라 테이블·컬럼·SQL 조각이
     * 섞여 나올 수 있다.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLock(OptimisticLockingFailureException e) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "다른 요청이 같은 대상을 먼저 바꿨습니다 — 다시 조회한 뒤 시도하세요");
    }

    /**
     * 기여할 수 없는 저장소 — <b>403</b> (S-5).
     *
     * <p>⚠️ <b>404 도 409 도 아니다.</b> 대상은 존재하고(404 아님) 상태 경합도 아니다(409 아님).
     * 「대상 저장소의 규약이 금지하거나 판정이 서지 않아 <b>우리가 하지 않기로 한</b>」 것이라
     * 거부가 맞다. 404 로 숨기면 호출자가 등록 문제로 오인해 다시 등록하려 든다.
     *
     * <p>⚠️ 보류({@code UNDETERMINED})도 여기로 온다 — 「판정 불가를 통과로 처리」가
     * 정확히 S-5 위반이다. 해소 경로는 {@code POST /api/repositories/&#123;id&#125;/policy/resolution}.
     */
    @ExceptionHandler(ContributionNotAllowedException.class)
    public ProblemDetail handleContributionNotAllowed(ContributionNotAllowedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    }

    /**
     * 🔴 착수 실행기가 준비되지 않았다 — <b>503</b> (#18 · S-6).
     *
     * <p>요청이 틀린 것이 아니라 <b>지금 할 수 없는 것</b>이라 4xx 가 아니다.
     * 같은 요청이 배선 뒤에는 성공한다.
     *
     * <p>⚠️ <b>후보는 그대로 남는다.</b> 전이 <b>전에</b> 막는 것이 요점이다 —
     * {@code IMPLEMENTING} 에서 나갈 길이 {@code TESTING}·{@code FAILED} 뿐이고
     * {@code FAILED} 는 종단이라, 실행기 없이 전이하면 사람이 버튼 한 번으로
     * <b>후보를 영구히 죽인다.</b>
     */
    @ExceptionHandler(ImplementationNotReadyException.class)
    public ProblemDetail handleImplementationNotReady(ImplementationNotReadyException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }
}
