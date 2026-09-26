package com.ossagent.candidate.application;

import com.ossagent.candidate.adapter.out.persistence.ContributionCandidateRepository;
import com.ossagent.candidate.domain.CandidateStatus;
import com.ossagent.support.observability.PipelineMetrics;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 후보 상태 분포 게이지 — {@code candidate_count{status=…}} (#25 FR-6).
 *
 * <h2>🔴 카운터가 아니라 게이지다</h2>
 *
 * <p>후보는 상태를 <b>옮겨 다닌다</b>({@code DISCOVERED → ANALYZING → ANALYZED → …}).
 * 카운터로 두면 옮길 때마다 올라가기만 해서 「지금 몇 건이 {@code ANALYZED} 인가」에
 * 답하지 못한다 — 제품의 성공 지표(「End-to-End 완주율」)가 묻는 것이 정확히 그것이다.
 *
 * <h2>왜 11개 상태를 모두 미리 등록하나</h2>
 *
 * <p>DB 에 없는 상태는 조회 결과에 나오지 않는다. 그때 게이지를 만들지 않으면
 * <b>「0건」과 「그 상태가 존재하지 않음」이 대시보드에서 같아 보인다.</b>
 * 상태 어휘는 {@link CandidateStatus} 11개로 <b>유한하고 우리가 통제</b>하므로
 * 전부 등록해도 카디널리티가 터지지 않는다.
 *
 * <p>⚠️ 이것은 「없는 지표를 0으로 만들지 않는다」(PLAN-25)와 모순이 아니다.
 * 그쪽은 <b>측정 대상 자체가 없는</b> 단계(#18~#23)를 말하고, 여기는 <b>측정 대상이
 * 실재하고 값이 0</b>인 경우다.
 */
@Component
public class CandidateStatusGauge {

    private static final Logger log = LoggerFactory.getLogger(CandidateStatusGauge.class);

    private final ContributionCandidateRepository candidates;
    private final Map<CandidateStatus, AtomicLong> values = new EnumMap<>(CandidateStatus.class);

    /**
     * ⚠️ {@code MeterRegistry} 를 직접 받지 <b>않는다.</b> {@link PipelineMetrics} 가
     * 「태그를 만드는 유일한 지점」이라는 주장이 사실이려면 여기서도 그것을 거쳐야 한다 —
     * 초안은 레지스트리를 직접 받아 그 주장을 거짓으로 만들고 있었다.
     *
     * <p>⚠️ 등록 실패는 <b>흡수하지 않는다</b>(fail-fast). 기동이 실패하면 사람이 바로
     * 알아차리는 반면, 흡수하면 「게이지가 없는 채로 도는」 상태가 조용히 계속된다.
     * 이것은 {@link #refresh()} 의 방어(업무를 죽이지 않는다)와 <b>다른 판단</b>이고,
     * 의도한 것이다.
     */
    public CandidateStatusGauge(ContributionCandidateRepository candidates,
            PipelineMetrics metrics) {
        this.candidates = candidates;
        for (CandidateStatus status : CandidateStatus.values()) {
            AtomicLong holder = new AtomicLong();
            values.put(status, holder);
            metrics.registerCandidateGauge(status.name(), holder);
        }
    }

    /** 기동 직후 한 번 — 재기동하면 게이지가 0 인데 DB 에는 후보가 있는 상태를 없앤다. */
    @EventListener(ApplicationReadyEvent.class)
    public void refreshOnStartup() {
        refresh();
    }

    /**
     * DB 에서 다시 읽어 게이지를 채운다.
     *
     * <h2>🔴 상태 전이 시점에 증감시키지 않는다</h2>
     *
     * <p>전이 경로가 늘어날 때마다 갱신을 붙여야 하고, <b>한 곳을 빠뜨리면 조용히
     * 틀린 숫자</b>가 대시보드에 남는다. 다시 읽으면 언제나 DB 가 정답이다.
     *
     * <p>부르는 곳은 둘이다 — 기동 직후, 그리고 분석 배치가 끝난 뒤
     * ({@code AnalyzeIssuesUseCase} — 후보 분포가 실제로 바뀌는 유일한 지점).
     * ⚠️ 따라서 게이지는 <b>「마지막 배치 시점의 값」</b>이지 실시간이 아니다.
     * 스크레이프마다 읽게 하면 11개 게이지가 각자 쿼리를 쳐서 1회 스크레이프에
     * 11번 나간다.
     *
     * <p>⚠️ {@code @Transactional} 을 붙이지 않는다 — 붙여도 <b>적용되지 않는다</b>.
     * {@code refreshOnStartup} 이 {@code this} 로 부르는 self-invocation 이고,
     * 애초에 필요도 없다(단일 조회이고 Spring Data 메서드가 자기 트랜잭션을 연다).
     * 달아 두면 「트랜잭션 안에서 읽는다」는 알리바이만 남는다.
     *
     * <p>🔴 <b>실패해도 던지지 않는다.</b> 메트릭 갱신이 예외를 올리면 그것을 부른
     * 업무 흐름이 죽는다. 직전 값을 유지하고 로그만 남긴다.
     */
    public void refresh() {
        try {
            List<Object[]> rows = candidates.countGroupedByStatus();
            Map<CandidateStatus, Long> fresh = new EnumMap<>(CandidateStatus.class);
            for (Object[] row : rows) {
                fresh.put((CandidateStatus) row[0], (Long) row[1]);
            }
            values.forEach((status, holder) -> holder.set(fresh.getOrDefault(status, 0L)));
        } catch (RuntimeException e) {
            log.warn("후보 상태 집계에 실패했다 — 직전 값을 유지한다", e);
        }
    }
}
