package com.ossagent.support.observability;

import com.ossagent.agent.domain.LlmCallSite;
import com.ossagent.agent.domain.LlmUsage;
import com.ossagent.repository.domain.ContributionNotAllowedException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 파이프라인 계측 — 🔴 <b>태그를 만드는 유일한 지점</b>이다.
 *
 * <h2>왜 한 곳에 가두나</h2>
 *
 * <p>{@code Timer.builder(...).tag(...)} 를 호출부에 흩으면 「이번엔 저장소 이름을 태그로
 * 넣자」가 언젠가 들어오고, <b>S-4 와 카디널리티를 리뷰가 매번 다시 봐야 한다.</b>
 *
 * <p><b>시그니처가 enum 만 받으면 문자열을 넣을 방법이 없다.</b>
 * {@code ScrubbedRules}·{@code IssueAnalysis} 가 스크럽을 값 타입으로 강제한 것과 같은 수법이다.
 *
 * <h2>🔴 태그로 쓰면 안 되는 것</h2>
 *
 * <table border="1">
 *   <caption>금지 태그와 이유</caption>
 *   <tr><th>값</th><th>왜</th></tr>
 *   <tr><td>이슈 제목·본문·LLM 응답</td><td>대상 저장소 텍스트다. 토큰이 섞여 있을 수 있다</td></tr>
 *   <tr><td>예외 <b>메시지</b></td><td>요청 URL·모델 응답이 실려 온다</td></tr>
 *   <tr><td>{@code candidateId}·{@code repositoryId}</td><td>시크릿은 아니나 <b>카디널리티가 무한</b>이다</td></tr>
 * </table>
 *
 * <p>카디널리티가 S-4 와 같은 방향인 것이 핵심이다 — 무한 카디널리티 태그는 메트릭
 * 저장소를 터뜨릴 뿐 아니라 <b>식별자를 외부 모니터링 시스템으로 계속 밀어낸다.</b>
 *
 * <h2>⚠️ 계측이 업무를 죽이지 않는다</h2>
 *
 * <p>모든 공개 메서드가 예외를 삼킨다. 메트릭 하나 때문에 스캔이 죽으면 본말전도다.
 * <b>다만 조용히 넘어가지는 않는다</b> — 계측이 죽은 것을 모르면 「지표가 0 이니 아무 일도
 * 없었다」로 읽는다. 방어를 여기 두는 이유는 호출부마다 {@code try/catch} 를 쓰면
 * 언젠가 빠뜨리기 때문이다.
 */
@Component
public class PipelineMetrics {

    private static final Logger log = LoggerFactory.getLogger(PipelineMetrics.class);

    private final MeterRegistry registry;

    public PipelineMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * LLM 호출 1건 — 건수 · 토큰 · 시도 번호.
     *
     * <p>🔴 <b>실패도 토큰을 센다.</b> 절단({@code TRUNCATED})은 응답을 받았으므로
     * 사용량을 알고, 모델은 이미 토큰을 생성했다. 성공만 세면 <b>장부가 거짓말을 한다</b> —
     * {@code RecordingLanguageModel} 이 같은 이유로 실패도 기록한다.
     */
    public void llmCall(LlmCallSite site, LlmOutcome outcome, LlmUsage usage, int attempt) {
        record(() -> {
            Counter.builder(MetricNames.LLM_CALLS)
                    .tag(MetricNames.TAG_CALL_SITE, site.name())
                    .tag(MetricNames.TAG_OUTCOME, outcome.name())
                    .register(registry)
                    .increment();

            if (usage != null) {
                tokens(site, "input", usage.inputTokens());
                tokens(site, "output", usage.outputTokens());
            }
            registry.summary(MetricNames.LLM_CALL_ATTEMPT,
                            MetricNames.TAG_CALL_SITE, site.name())
                    .record(attempt);
        });
    }

    /** 파이프라인 단계 1회 — 소요 시간과 결과. */
    public void pipelineStage(PipelineStage stage, StageOutcome outcome, Duration took) {
        record(() -> Timer.builder(MetricNames.PIPELINE_STAGE)
                .tag(MetricNames.TAG_STAGE, stage.name())
                .tag(MetricNames.TAG_OUTCOME, outcome.name())
                .register(registry)
                .record(took));
    }

    /**
     * 🔴 안전 게이트 판정 1회 — <b>통과와 차단을 모두 센다</b>.
     *
     * <p>차단만 세면 분모가 없어 「막힌 비율」을 계산할 수 없고, 계측이 죽었을 때
     * <b>「0건 차단」과 「계측 고장」이 구분되지 않는다.</b>
     *
     * @param reason 차단 사유. 통과면 {@code null} → 태그는 {@code NONE}
     */
    public void safetyGate(SafetyClause clause, GateOutcome outcome,
            ContributionNotAllowedException.Reason reason) {
        record(() -> Counter.builder(MetricNames.SAFETY_GATE)
                .tag(MetricNames.TAG_CLAUSE, clause.name())
                .tag(MetricNames.TAG_OUTCOME, outcome.name())
                // 🔴 enum 이거나 상수 "NONE" 이다. 여기에 문자열을 흘릴 경로가 없다
                .tag(MetricNames.TAG_REASON, reason == null ? "NONE" : reason.name())
                .register(registry)
                .increment());
    }

    /**
     * 이슈 분석의 <b>건당</b> 결과 — {@code analysis_success_rate} 의 분모·분자.
     *
     * <p>⚠️ 단계 타이머와 다른 것을 잰다. 타이머는 「ANALYZE <b>단계</b>가 예외 없이
     * 끝났는가」이고, 이것은 「이슈 N건 중 <b>몇 건이</b> 성공했는가」다.
     *
     * <p>⚠️ {@code REJECTED} 는 실패가 아니다 — 임계에 걸러진 것은 정상 동작이다.
     * 성공률을 어떻게 정의할지는 <b>대시보드가 정한다.</b> 우리는 네 값을 그대로 준다.
     */
    public void analysisOutcome(AnalysisOutcome outcome, int count) {
        if (count <= 0) {
            return;
        }
        record(() -> Counter.builder(MetricNames.ANALYSIS_OUTCOME)
                .tag(MetricNames.TAG_OUTCOME, outcome.name())
                .register(registry)
                .increment(count));
    }

    /**
     * 후보 상태 게이지를 등록한다 — 값은 호출자가 들고 있는 {@link AtomicLong} 을 읽는다.
     *
     * <p>🔴 <b>이 메서드가 있는 이유는 「태그를 만드는 유일한 지점」을 사실로 유지하기
     * 위해서다.</b> 초안에서는 {@code CandidateStatusGauge} 가 {@code MeterRegistry} 를
     * 직접 받아 등록했는데, 그러면 이 클래스의 javadoc 이 <b>거짓말</b>이 되고
     * 다음 사람이 같은 패턴을 복제할 근거가 된다.
     *
     * <p>{@code CandidateMetricStatus} 를 따로 둔 이유는 {@link PipelineStage} 와 같다 —
     * {@code support} 가 {@code candidate.domain} 을 import 하지 않게 한다.
     */
    public void registerCandidateGauge(String statusName, AtomicLong value) {
        Gauge.builder(MetricNames.CANDIDATE_COUNT, value, AtomicLong::doubleValue)
                .tag(MetricNames.TAG_STATUS, statusName)
                .register(registry);
    }

    private void tokens(LlmCallSite site, String direction, int amount) {
        Counter.builder(MetricNames.LLM_TOKENS)
                .tag(MetricNames.TAG_CALL_SITE, site.name())
                .tag(MetricNames.TAG_DIRECTION, direction)
                .register(registry)
                .increment(amount);
    }

    /**
     * 🔴 계측 실패가 업무 흐름으로 번지지 않게 한다 — 그러나 <b>삼키고 조용히 넘어가지 않는다.</b>
     *
     * <p>{@code Error} 는 잡지 않는다. OOM 을 삼키면 더 나쁜 일이 뒤에 생긴다.
     */
    private void record(Runnable instrumentation) {
        try {
            instrumentation.run();
        } catch (RuntimeException e) {
            // ⚠ 예외는 마지막 인자로 — 스택트레이스를 잃지 않는다
            log.warn("메트릭 기록 실패 — 업무는 계속한다", e);
        }
    }
}
