package com.ossagent.issue.domain;

import com.ossagent.support.ExternalText;
import java.util.List;

/**
 * 분석 단계로 넘기는 이슈 <b>값</b> — 규율 ④.
 *
 * <p>{@code candidate} 도메인이 {@link Issue} 엔티티나 {@code IssueJpaRepository} 를 직접
 * import 하면 애그리거트 경계가 무너진다. 넘어가는 것은 이 값 하나다.
 *
 * <p>⚠️ <b>{@code filterOutcome} 을 함께 넘기는 것이 설계다.</b> {@code PASSED} 와
 * {@code UNDECIDED} 는 <b>둘 다 분석 대상이지만 같은 것이 아니다</b> —
 * {@code UNDECIDED} 는 「규칙으로 가를 수 없었다」는 신호이고, 그 사실 자체가
 * 하류에 전달돼야 할 정보다({@link FilterOutcome} javadoc). 프롬프트가 이것을 알면
 * 모델에게 「규칙 필터가 판단을 보류한 건이다」라고 말해 줄 수 있다.
 *
 * <p>🔴 {@code title}·{@code body} 는 <b>대상 저장소 사람이 쓴 텍스트</b>다. 시크릿이
 * 섞여 있을 수 있고, 이 값이 그대로 LLM 프롬프트로 간다 — 스크럽은 송신 직전
 * {@code PromptScrubber} 가 강제한다 (S-4).
 *
 * @param filterPriority 라벨 우선순위 점수. {@code null} 일 수 있다 — 정렬은 호출자가
 *                       {@code COALESCE} 로 결정적으로 만든다
 */
public record AnalyzableIssue(
        Long id,
        Long repositoryId,
        Integer githubIssueNumber,
        @ExternalText(ExternalText.Source.TARGET_REPOSITORY) String title,
        @ExternalText(ExternalText.Source.TARGET_REPOSITORY) String body,
        List<String> labels,
        String url,
        FilterOutcome filterOutcome,
        Short filterPriority) {

    public AnalyzableIssue {
        if (id == null) {
            throw new IllegalArgumentException("이슈 식별자는 필수다");
        }
        if (repositoryId == null) {
            throw new IllegalArgumentException("저장소 식별자는 필수다 — S-5 게이트가 이 값으로 선다");
        }
        labels = labels == null ? List.of() : List.copyOf(labels);
    }

    /** 규칙 필터가 판단을 보류한 건인가 — 프롬프트가 이 사실을 모델에게 알린다. */
    public boolean isUndecidedByRules() {
        return filterOutcome == FilterOutcome.UNDECIDED;
    }

    /**
     * 키셋 커서의 우선순위 성분.
     *
     * <p>🔴 <b>쿼리의 {@code COALESCE(filter_priority, 0)} 와 같은 값이어야 한다.</b>
     * 어긋나면 커서가 정렬과 다른 곳을 가리켜 <b>행을 건너뛰거나 무한 반복</b>한다.
     * 그래서 호출자가 {@code null} 을 직접 다루지 않게 여기서 준다.
     */
    public short priorityKey() {
        return filterPriority == null ? 0 : filterPriority;
    }

    /**
     * 🔴 <b>본문·제목을 찍지 않는다.</b> 대상 저장소가 시크릿을 커밋해 뒀을 수 있고,
     * 임의 텍스트를 로그에 넣는 것 자체가 인젝션 경로다 — {@code logging.md}.
     */
    @Override
    public String toString() {
        return "AnalyzableIssue[id=%d, repositoryId=%d, number=%s, outcome=%s, priority=%s, bodySize=%d]"
                .formatted(id, repositoryId, githubIssueNumber, filterOutcome, filterPriority,
                        body == null ? 0 : body.length());
    }
}
