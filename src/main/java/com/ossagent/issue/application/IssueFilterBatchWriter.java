package com.ossagent.issue.application;

import com.ossagent.issue.adapter.out.persistence.IssueJpaRepository;
import com.ossagent.issue.domain.FilterOutcome;
import com.ossagent.issue.domain.FilterVerdict;
import com.ossagent.issue.domain.Issue;
import com.ossagent.issue.domain.IssueFilter;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 미판정 이슈 한 배치를 읽어 판정하고 적재한다 — #9.
 *
 * <h2>🔴 왜 별도 빈인가</h2>
 *
 * <p>{@code FilterIssuesUseCase} 안의 메서드로 두면 <b>자기 호출이라 프록시를 타지 않아
 * 트랜잭션이 걸리지 않는다.</b> {@code open-in-view: false} 라 조회해 온 엔티티는
 * detached 상태가 되고, 변경이 flush 없이 사라진다. 로그도 반환값도 정상으로 보이고
 * DB 만 옛날 값인 <b>조용한 실패</b>라 클래스를 가른다 — #7 에서 실제로 겪었다.
 *
 * <h2>🔴 왜 항상 첫 페이지를 읽는가</h2>
 *
 * <p>조회 조건이 {@code filter_result IS NULL} 인데 판정이 바로 그 컬럼을 채운다.
 * 판정한 행은 다음 조회의 결과 집합에서 빠지므로, {@code page=1} 로 넘어가면
 * <b>배치 크기만큼을 건너뛴다.</b> 항상 첫 페이지를 다시 읽는 것이 옳다 —
 * 판정이 진행을 보장하므로 멈추지 않는다.
 *
 * <h2>⚠ 알려진 공백 — 스캔과 동시 실행</h2>
 *
 * <p>같은 저장소를 스캔과 필터가 동시에 돌면, 스캔이 갱신하며 지운 판정 위에
 * 필터가 <b>갱신 전 내용 기준 판정</b>을 덮어쓸 수 있다. 그 행은 더 이상
 * {@code NULL} 이 아니라 다시는 재판정되지 않는다.
 *
 * <p>지금은 스케줄러가 없어 동시 실행 경로 자체가 없다({@code IssuePageWriter} 가
 * 스캔-스캔 경합을 같은 자리에 남겨 뒀다). 스케줄러를 붙이는 <b>#14 가 저장소별 동시
 * 실행을 막아야 한다</b> — 낙관적 락을 여기 먼저 넣으면 실재하지 않는 경합을 위해
 * 스키마가 하나 더 늘어난다.
 */
@Component
public class IssueFilterBatchWriter {

    private final IssueJpaRepository issues;
    private final Clock clock;

    public IssueFilterBatchWriter(IssueJpaRepository issues, Clock clock) {
        this.issues = issues;
        this.clock = clock;
    }

    /**
     * 미판정 이슈 최대 {@code batchSize} 건을 판정한다.
     *
     * <p>⚠ <b>대외 호출이 없다.</b> 규칙은 전부 순수 함수이고 입력은 이미 저장된 행뿐이라
     * 트랜잭션 안에서 판정해도 커넥션을 오래 잡지 않는다 — 그것이 이 단계의 설계 전제다.
     *
     * @return 이번 배치의 판정별 건수. 비어 있으면 더 판정할 것이 없다
     */
    @Transactional
    public Map<FilterOutcome, Integer> judgeBatch(Long repositoryId, IssueFilter filter, int batchSize) {
        List<Issue> batch = issues.findByRepositoryIdAndFilterResultIsNull(
                repositoryId, PageRequest.of(0, batchSize, Sort.by(Sort.Direction.ASC, "id")));

        Instant now = clock.instant();
        Map<FilterOutcome, Integer> counts = new EnumMap<>(FilterOutcome.class);
        for (Issue issue : batch) {
            FilterVerdict verdict = filter.evaluate(issue);
            issue.applyFilter(verdict, now);
            counts.merge(verdict.outcome(), 1, Integer::sum);
        }
        return counts;
    }
}
