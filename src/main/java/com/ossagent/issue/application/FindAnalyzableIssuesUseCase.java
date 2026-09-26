package com.ossagent.issue.application;

import com.ossagent.issue.adapter.out.persistence.IssueJpaRepository;
import com.ossagent.issue.domain.AnalyzableIssue;
import com.ossagent.issue.domain.FilterOutcome;
import com.ossagent.issue.domain.Issue;
import java.util.List;
import java.util.Optional;
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

    /**
     * 이슈 <b>한 건</b>을 값으로 읽는다 — 이슈 #16(구현 계획 수립)이 쓴다.
     *
     * <p>🔴 <b>필터 판정으로 거르지 않는다.</b> {@link #findAnalyzable} 는 「아직 분석하지 않은
     * 후보감」을 고르는 조회라 {@code PASSED}·{@code UNDECIDED} 로 좁히지만, 여기 오는 이슈는
     * <b>이미 분석을 통과해 후보가 된</b> 것이다. 같은 필터를 걸면 그 사이 판정이 바뀐 이슈가
     * 조용히 사라져, 사람이 고른 후보가 「이슈가 없다」로 실패한다.
     *
     * <p>🔴 <b>{@code candidate} 가 {@code Issue} 엔티티를 직접 읽지 못하게 하는 것이
     * 이 메서드의 존재 이유다</b> — 규율 ④. 넘어가는 것은 값 하나다.
     *
     * @return 없으면 빈 값. 「없다」는 정상 상황이 아니지만(후보가 가리키는 이슈다),
     *         그 판정은 호출자가 한다 — 이 도메인은 후보를 모른다
     */
    @Transactional(readOnly = true)
    public Optional<AnalyzableIssue> findOne(Long issueId) {
        if (issueId == null) {
            throw new IllegalArgumentException("이슈 식별자는 필수다");
        }
        return issues.findById(issueId).map(FindAnalyzableIssuesUseCase::toValue);
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
