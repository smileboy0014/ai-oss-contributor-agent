package com.ossagent.candidate.application;

import com.ossagent.candidate.domain.CandidateStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 목록 조회의 <b>프로젝션 결과</b>. JPQL constructor expression 이 직접 만든다.
 *
 * <p>🔴 <b>엔티티를 로드하지 않기 위해 존재한다.</b> {@code ContributionCandidate} 를 통째로 읽으면
 * {@code analysis}(TEXT, 수십 KB)가 함께 딸려 온다. 목록 20건이면 20배다.
 *
 * <p>⚠️ <b>이 타입이 {@code adapter/in/web/dto} 가 아니라 {@code application} 에 있는 이유</b> —
 * JPQL 을 담는 {@code adapter/out/persistence} 가 이 타입을 import 한다. 그것이 web DTO 였다면
 * <b>영속 계층이 web 진입점 없이는 존재할 수 없게</b> 된다. {@code web}/{@code worker} 프로필을
 * 가를 때(Q-3) 정확히 그 import 를 먼저 끊어야 한다 — 「나중에 떼어낼 수 있게 한다」의 반대 방향이다.
 * 컨트롤러가 web DTO 로 한 줄 매핑한다.
 *
 * <p>🔴 {@code confidence} 가 {@link BigDecimal} 인 것은 <b>컬럼이 nullable 이기 때문</b>이다.
 * {@code discover()} 가 채우지 않으므로 {@code DISCOVERED}·{@code ANALYZING} 후보는 전부 NULL 이고,
 * primitive 로 받으면 constructor expression 이 언박싱 NPE 를 내 <b>정상 데이터에서 목록이 500</b> 이 된다.
 *
 * <p>외부 텍스트({@code analysis}·{@code diff} 등)를 <b>하나도 담지 않는다</b> — S-4.
 */
public record CandidateSummaryView(
        Long id,
        Long issueId,
        CandidateStatus status,
        String difficulty,
        BigDecimal confidence,
        Integer attempt,
        Instant selectedAt) {
}
