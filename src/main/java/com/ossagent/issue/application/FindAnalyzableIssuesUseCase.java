package com.ossagent.issue.application;

import com.ossagent.issue.adapter.out.persistence.IssueJpaRepository;
import com.ossagent.issue.domain.AnalyzableIssue;
import com.ossagent.issue.domain.FilterOutcome;
import com.ossagent.issue.domain.Issue;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 분석 단계로 넘길 이슈를 공급한다 — 규율 ④ 의 경계 지점.
 *
 * <p>{@code candidate} 도메인이 {@link Issue} 엔티티도 {@link IssueJpaRepository} 도
 * 직접 만지지 않게 하는 것이 이 클래스의 존재 이유다. 넘어가는 것은 {@link AnalyzableIssue}
 * <b>값</b>뿐이라, 이슈 스키마가 바뀌어도 후보 쪽 컴파일이 깨지지 않는다.
 *
 * <p>🔴 <b>「후보가 이미 있는가」는 여기서 보지 않는다.</b> 그것은 다른 애그리거트의 사실이고,
 * 이 도메인이 알면 경계가 무너진다. 호출자가 거른다.
 */
@Service
public class FindAnalyzableIssuesUseCase {

    /**
     * 🔴 {@code UNDECIDED} 가 빠지면 안 된다 — 「규칙으로 가를 수 없다, LLM 이 본다(#11)」는
     * 신호이고 배제가 아니다({@link FilterOutcome#excluded()}).
     */
    private static final Set<String> ANALYZABLE_OUTCOMES =
            Set.of(FilterOutcome.PASSED.name(), FilterOutcome.UNDECIDED.name());

    private final IssueJpaRepository issues;

    public FindAnalyzableIssuesUseCase(IssueJpaRepository issues) {
        this.issues = issues;
    }

    /**
     * 우선순위 내림차순 · id 오름차순으로 한 페이지 읽는다.
     *
     * <p>커서는 직전 페이지 마지막 항목의 {@link AnalyzableIssue#priorityKey()} 와
     * {@link AnalyzableIssue#id()} 다. 첫 페이지는 둘 다 {@code null} 로 부른다.
     *
     * @throws IllegalArgumentException 커서 두 성분 중 하나만 준 경우 — 반쪽 커서는
     *                                  조용히 첫 페이지로 되돌아가 <b>무한 루프</b>가 된다
     */
    @Transactional(readOnly = true)
    public List<AnalyzableIssue> findAnalyzable(Long repositoryId, Short afterPriority,
            Long afterId, int size) {
        if (repositoryId == null) {
            throw new IllegalArgumentException("저장소 식별자는 필수다");
        }
        if ((afterPriority == null) != (afterId == null)) {
            throw new IllegalArgumentException(
                    "커서는 우선순위와 id 를 함께 준다 — 한쪽만 주면 첫 페이지로 되돌아간다 "
                            + "(afterPriority=" + afterPriority + ", afterId=" + afterId + ")");
        }
        if (size < 1) {
            throw new IllegalArgumentException("페이지 크기는 1 이상이다: " + size);
        }
        return issues
                .findAnalyzable(repositoryId, ANALYZABLE_OUTCOMES, afterPriority, afterId,
                        PageRequest.of(0, size))
                .stream()
                .map(FindAnalyzableIssuesUseCase::toValue)
                .toList();
    }

    private static AnalyzableIssue toValue(Issue issue) {
        return new AnalyzableIssue(
                issue.getId(),
                issue.getRepositoryId(),
                issue.getGithubIssueNumber(),
                issue.getTitle(),
                issue.getBody(),
                issue.labelList(),
                issue.getUrl(),
                FilterOutcome.valueOf(issue.getFilterResult()),
                issue.getFilterPriority());
    }
}
