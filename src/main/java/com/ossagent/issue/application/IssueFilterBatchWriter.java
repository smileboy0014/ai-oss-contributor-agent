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
import org.springframework.transaction.annotation.Propagation;
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
 * <h2>🔴 그리고 전파를 {@code REQUIRES_NEW} 로 못 박는다</h2>
 *
 * <p>클래스를 가르는 것만으로는 <b>절반만 닫힌다.</b> 기본 전파({@code REQUIRED})면
 * 호출자가 연 트랜잭션에 합류하는데, 그 호출자(#14 스케줄러가 유력하다)가
 * {@code @Transactional(readOnly = true)} 를 달면 Hibernate 가 flush 모드를
 * {@code MANUAL} 로 내린다. 그러면 <b>auto-flush 가 꺼져</b> 매 배치가 같은 200건을 다시
 * 읽고, 루프는 상한까지 돌아 「20,000건 판정 완료」를 반환한다. <b>DB 는 하나도 바뀌지
 * 않았는데 로그도 반환값도 성공이다</b> — 위와 정확히 같은 부류의 조용한 실패다.
 *
 * <p>{@code REQUIRES_NEW} 면 바깥이 무엇이든 배치마다 독립 트랜잭션이 열리고 닫힌다.
 * 「배치마다 짧게 연다」는 이 클래스의 주장이 그제서야 <b>구조적으로 참</b>이 된다 —
 * 평범한 {@code @Transactional} 래퍼 하나만 씌워도 20,000건이 한 트랜잭션에 쌓여
 * 배치 분할의 목적이 조용히 사라지기 때문이다.
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
     * @return 이번 배치의 판정 결과. 비어 있으면 더 판정할 것이 없다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Batch judgeBatch(Long repositoryId, IssueFilter filter, int batchSize) {
        List<Issue> batch = issues.findByRepositoryIdAndFilterResultIsNull(
                repositoryId, PageRequest.of(0, batchSize, Sort.by(Sort.Direction.ASC, "id")));

        Instant now = clock.instant();
        Map<FilterOutcome, Integer> counts = new EnumMap<>(FilterOutcome.class);
        for (Issue issue : batch) {
            FilterVerdict verdict = filter.evaluate(issue);
            issue.applyFilter(verdict, now);
            counts.merge(verdict.outcome(), 1, Integer::sum);
        }
        return new Batch(counts, batch.isEmpty() ? null : batch.get(0).getId(), batch.size());
    }

    /**
     * 한 배치의 결과.
     *
     * <p>{@code firstId} 와 {@code size} 를 함께 돌려주는 것은 호출자가 <b>전진을 단언</b>하기
     * 위해서다. 판정이 커밋되지 않으면 다음 배치가 <b>같은 행을 그대로</b> 다시 읽는데,
     * 건수만 보면 그것이 「열심히 일하는 중」과 구분되지 않는다. 배치 상한은 루프를
     * <b>멈추게는 하지만 결과가 거짓말하는 것을 막지 못한다</b> — 제동이지 경보가 아니다.
     *
     * @param counts  판정별 건수
     * @param firstId 이 배치에서 가장 작은 이슈 id. 빈 배치면 {@code null}
     * @param size    이 배치의 건수
     */
    public record Batch(Map<FilterOutcome, Integer> counts, Long firstId, int size) {

        public Batch {
            counts = counts == null ? Map.of() : Map.copyOf(counts);
        }

        public boolean isEmpty() {
            return size == 0;
        }

        /** 앞 배치와 <b>완전히 같은 구간</b>인가 — 커밋되지 않았다는 신호다. */
        public boolean repeats(Batch previous) {
            return previous != null && size > 0
                    && size == previous.size && firstId != null && firstId.equals(previous.firstId);
        }
    }
}
