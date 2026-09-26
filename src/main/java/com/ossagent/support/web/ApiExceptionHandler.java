package com.ossagent.support.web;

import com.ossagent.candidate.domain.CandidateNotFoundException;
import com.ossagent.candidate.domain.CandidateTransitionException;
import com.ossagent.repository.domain.ContributionNotAllowedException;
import com.ossagent.repository.domain.PolicyResolutionRejectedException;
import com.ossagent.repository.domain.RepositoryAlreadyRegisteredException;
import com.ossagent.repository.domain.RepositoryNotScannableException;
import com.ossagent.repository.domain.ScanAlreadyRunningException;
import com.ossagent.repository.domain.RepositoryNotFoundException;
import com.ossagent.repository.domain.RepositoryPolicyNotFoundException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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
}
