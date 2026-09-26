package com.ossagent.support.web;

import com.ossagent.candidate.domain.CandidateNotFoundException;
import jakarta.validation.ConstraintViolationException;
import com.ossagent.repository.domain.RepositoryAlreadyRegisteredException;
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

    /**
     * 쿼리 파라미터 제약 위반 — 400.
     *
     * <p>{@code @Validated} 컨트롤러의 메서드 검증은 {@link ConstraintViolationException} 을 던지는데
     * Spring 이 기본 매핑하지 않아 <b>500 이 된다.</b> 호출자 잘못을 서버 오류로 보고하면
     * 재시도해도 소용없는 요청을 계속 받게 된다.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(CandidateNotFoundException.class)
    public ProblemDetail handleCandidateNotFound(CandidateNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(RepositoryAlreadyRegisteredException.class)
    public ProblemDetail handleConflict(RepositoryAlreadyRegisteredException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}
