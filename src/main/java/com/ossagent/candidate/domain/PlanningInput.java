package com.ossagent.candidate.domain;

import com.ossagent.issue.domain.AnalyzableIssue;
import com.ossagent.repository.domain.ContributionConstraints;
import com.ossagent.repository.domain.RepositoryContext;

/**
 * 계획 수립에 필요한 것 전부 — 이슈 #16.
 *
 * <p>인자를 4개 늘어놓는 대신 값 하나로 묶는다. 재생성(FR-5)이 <b>같은 입력에 피드백만
 * 더해</b> 다시 부르는 구조라, 묶여 있어야 「무엇이 달라졌는가」가 한눈에 보인다.
 *
 * <p>🔴 <b>전부 남의 애그리거트의 값 타입이다.</b> {@code AnalyzableIssue} 는 {@code issue},
 * {@code RepositoryContext}·{@code ContributionConstraints} 는 {@code repository} 소유이고,
 * 엔티티나 Spring Data 인터페이스는 하나도 넘어오지 않는다 — 규율 ④.
 *
 * @param issue       무엇을 고칠 것인가
 * @param context     어디를 볼 것인가 (#15). <b>이미 상한이 서 있다</b> — 여기서 더 늘리지 않는다
 * @param constraints 대상 저장소 규약 (S-5 · FR-6)
 * @param feedback    직전 검증이 거부한 사유. <b>첫 시도에는 {@code null}</b> 이다
 */
public record PlanningInput(
        AnalyzableIssue issue,
        RepositoryContext context,
        ContributionConstraints constraints,
        PlanVerdict feedback) {

    public PlanningInput {
        if (issue == null) {
            throw new IllegalArgumentException("계획을 세울 이슈는 필수다");
        }
        if (context == null) {
            throw new IllegalArgumentException("저장소 컨텍스트는 필수다 — 볼 것 없이 계획할 수 없다 (#15)");
        }
        constraints = constraints == null ? ContributionConstraints.unknown() : constraints;
    }

    /** 재생성인가 — 어댑터가 프롬프트에 피드백을 실을지 판단한다 */
    public boolean isRetry() {
        return feedback != null && !feedback.isPassed();
    }

    /** 거부 사유를 얹은 사본. 원본을 바꾸지 않는다 */
    public PlanningInput withFeedback(PlanVerdict verdict) {
        return new PlanningInput(issue, context, constraints, verdict);
    }

    /**
     * 🔴 <b>본문을 찍지 않는다.</b> 이슈 본문·파일 내용·규약 문자열이 전부 대상 저장소에서
     * 온 임의 텍스트다 — {@code logging.md}.
     */
    @Override
    public String toString() {
        return "PlanningInput[issue=#%s, contextFiles=%d, retry=%s]"
                .formatted(issue.githubIssueNumber(), context.files().size(), isRetry());
    }
}
