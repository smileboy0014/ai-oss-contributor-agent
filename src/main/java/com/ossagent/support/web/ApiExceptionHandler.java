package com.ossagent.support.web;

import com.ossagent.candidate.domain.CandidateNotFoundException;
import com.ossagent.repository.domain.RepositoryAlreadyRegisteredException;
import com.ossagent.repository.domain.ScanAlreadyRunningException;
import com.ossagent.repository.domain.RepositoryNotFoundException;
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
}
