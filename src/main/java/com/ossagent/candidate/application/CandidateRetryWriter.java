package com.ossagent.candidate.application;

import com.ossagent.candidate.adapter.out.persistence.AgentRunRepository;
import com.ossagent.candidate.adapter.out.persistence.ContributionCandidateRepository;
import com.ossagent.candidate.domain.AgentRun;
import com.ossagent.candidate.domain.CandidateNotFoundException;
import com.ossagent.candidate.domain.CandidateStatus;
import com.ossagent.candidate.domain.ContributionCandidate;
import com.ossagent.candidate.domain.StatusTransition;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재시도 루프의 <b>트랜잭션 구간</b>만 담는다 — #21 · S-6.
 *
 * <h2>🔴 왜 별도 빈인가 — self-invocation 함정</h2>
 *
 * <p>{@code @Transactional} 은 프록시로 걸리므로 <b>같은 빈 안에서 부르면 적용되지 않는다.</b>
 * 증상이 예외가 아니라 「설정이 조용히 무시된다」라 눈에 띄지 않는다 —
 * {@code CandidateImplementationWriter}(#18)가 같은 이유로 갈라져 있고, #11·#16 은
 * 그것 때문에 「저장이 사라진다」를 겪었다.
 *
 * <p>{@code ImplementCandidateUseCase} 는 <b>트랜잭션 없이</b> 루프를 돌고
 * (바퀴마다 샌드박스 최대 30분 · LLM 호출 둘), 트랜잭션이 필요한 조각만 이 빈을 통해 부른다.
 * 🔴 <b>루프 전체를 감싸면 커넥션이 최대 90분 잡힌다.</b>
 *
 * <h2>🔴 실패 사유가 DB 에 남는다 — #18 이 남긴 잔여를 닫는다</h2>
 *
 * <p>#18 은 사유를 <b>로그에만</b> 남겼고 그 javadoc 이 이렇게 적어 두었다 —
 * <i>「{@code FAILED} 는 종단이라 되돌릴 수 없는데, 사람이 API·DB 로 볼 수 있는 것은
 * 상태뿐이고 왜 실패했는지는 로그를 뒤져야 안다. 「그 자체가 사람에게 넘기는 신호」라면서
 * 신호에 내용이 없는 상태다.」</i>
 *
 * <p>{@link #fail} 이 {@code AgentRun} 행을 함께 남겨 그것을 닫는다.
 *
 * <p>⚠️ <b>{@code AgentRunRecorder} 를 쓰지 않는다.</b> 그쪽은 <b>LLM 호출 1건</b>을 세는
 * 축이라 {@code started}/{@code succeeded}/{@code failed} 가 전부 {@code LlmUsage} 를
 * 받는데, {@code VERIFY} 실패에는 사용량이 없다. 축이 다르므로 같은 문으로 들어가지 않는다.
 */
@Component
class CandidateRetryWriter {

    private static final Logger log = LoggerFactory.getLogger(CandidateRetryWriter.class);

    private final ContributionCandidateRepository candidates;
    private final AgentRunRepository runs;
    private final ExecutionProperties properties;
    private final Clock clock;

    CandidateRetryWriter(ContributionCandidateRepository candidates,
            AgentRunRepository runs,
            ExecutionProperties properties,
            Clock clock) {
        this.candidates = candidates;
        this.runs = runs;
        this.properties = properties;
        this.clock = clock;
    }

    /** {@code IMPLEMENTING → TESTING} — 같은 바퀴 안이라 {@code attempt} 를 올리지 않는다. */
    @Transactional
    StatusTransition startTesting(Long candidateId) {
        return transition(candidateId, candidate -> candidate.startTesting(clock));
    }

    /** {@code TESTING → REVIEWING} — 같은 바퀴 안. */
    @Transactional
    StatusTransition startReview(Long candidateId) {
        return transition(candidateId, candidate -> candidate.startReview(clock));
    }

    /**
     * 🔴 다음 바퀴로 — {@code TESTING·REVIEWING → IMPLEMENTING}, 또는 <b>상한이면 {@code FAILED}</b>.
     *
     * <p>판정은 {@code retryImplementation} 이 한다. 여기서 {@code attempt} 를 미리 비교하지
     * 않는 이유는 <b>도메인 상수가 두 군데가 되기 때문</b>이다 — 상한의 주인은 후보 루트다
     * (불변식 ⑧).
     *
     * @return 전이 결과. 🔴 도착이 {@code FAILED} 면 <b>상한이 소진된 것</b>이다 —
     *         호출자가 {@link StatusTransition#to()} 로 그것을 보고 루프를 끝낸다
     */
    @Transactional
    RetryOutcome retry(Long candidateId, String reasonIfExhausted) {
        ContributionCandidate candidate = load(candidateId);
        StatusTransition transition =
                candidate.retryImplementation(properties.maxAttempts(), clock);

        if (transition.to() == CandidateStatus.FAILED) {
            // 🔴 상한 소진이다. 마지막 실패 사유를 그대로 싣는다 —
            //    별도 행을 하나 더 만들지 않는다(같은 실패가 두 번 세어진다)
            recordFailure(candidateId, candidate.getAttempt(), AgentRun.Stage.CODE,
                    "재시도 상한 소진 (" + properties.maxAttempts() + "바퀴) — " + reasonIfExhausted);
            log.warn("재시도 상한 소진 candidateId={} attempts={} — FAILED (S-6)",
                    candidateId, properties.maxAttempts());
            return new RetryOutcome(transition, candidate.getAttempt(), true);
        }
        log.info("다음 바퀴 candidateId={} attempt={}/{}",
                candidateId, candidate.getAttempt(), properties.maxAttempts());
        return new RetryOutcome(transition, candidate.getAttempt(), false);
    }

    /**
     * ✅ {@code REVIEWING → READY_FOR_PR}.
     *
     * <p>🔴 <b>여기서 자동화가 멈춘다.</b> 다음은 {@code PR_CREATED} 가 아니라
     * <b>세 번째 승인 게이트</b>(#23)다 — S-2. 이 빈은 {@code markPrCreated} 를 부르지 않고,
     * {@code ApprovalGateArchitectureTest} 가 그 사실을 고정한다.
     */
    @Transactional
    StatusTransition readyForPr(Long candidateId) {
        StatusTransition transition = transition(candidateId,
                candidate -> candidate.markReadyForPr(clock));
        log.info("검증·리뷰 통과 candidateId={} → READY_FOR_PR (PR 생성은 사람이 지시한다 — S-2)",
                candidateId);
        return transition;
    }

    /**
     * 🔴 후보를 {@code FAILED} 로 — <b>종단이고 그 자체가 사람에게 넘기는 신호</b>다 (S-6).
     *
     * <p>{@code AgentRun} 행을 <b>함께</b> 남긴다. 상태만 남기면 「왜」가 로그에만 있고,
     * 종단이라 되돌릴 수도 없다.
     *
     * @param stage  실패한 단계 — {@code CODE}·{@code VERIFY}·{@code REVIEW}
     * @param reason 🔴 <b>우리 어휘로만.</b> 빌드 출력·모델 응답·예외 메시지를 그대로 넣지
     *               않는다. {@code AgentRun.fail} 의 스크럽은 <b>마지막 그물</b>이지
     *               이 규약의 대체가 아니다 (S-4)
     */
    @Transactional
    void fail(Long candidateId, int attempt, AgentRun.Stage stage, String reason) {
        ContributionCandidate candidate = load(candidateId);
        candidate.fail(clock);
        recordFailure(candidateId, attempt, stage, reason);
        log.warn("후보 실패 candidateId={} stage={} attempt={} reason={}",
                candidateId, stage, attempt, reason);
    }

    /**
     * 🔴 후보를 {@code SELECTED} 로 되돌린다 — <b>일시 장애다. 태우지 않는다</b> (#98).
     *
     * <p>{@link #fail} 과 같은 모양으로 {@code AgentRun} 행을 남긴다 — 「왜 미뤘는가」가
     * DB 에 있어야 사람이 다시 누를지 판단한다. 행은 실패로 기록된다(그 바퀴는 실제로 실패했다).
     * 후보만 종단이 아니다.
     */
    @Transactional
    void defer(Long candidateId, int attempt, AgentRun.Stage stage, String reason) {
        ContributionCandidate candidate = load(candidateId);
        candidate.deferImplementation(clock);
        recordFailure(candidateId, attempt, stage, reason);
        log.warn("착수 미룸 candidateId={} stage={} attempt={} reason={} — SELECTED 로 되돌린다 (S-6)",
                candidateId, stage, attempt, reason);
    }

    /**
     * {@code AgentRun} 실패 행을 남긴다.
     *
     * <p>⚠️ {@code attempt} 가 0 일 수 있다 — {@code IMPLEMENTING} 전이 <b>전</b>에 죽은
     * 경우다({@code PLAN} 단계). {@code AgentRun.start} 는 1 이상을 요구하므로 1 로 올린다.
     * 🔴 <b>그 보정을 여기서만 한다</b> — 도메인의 「0 은 아직 착수 전」 의미를 바꾸지 않는다.
     */
    private void recordFailure(Long candidateId, int attempt, AgentRun.Stage stage, String reason) {
        AgentRun run = AgentRun.start(candidateId, stage, Math.max(attempt, 1), clock);
        // 🔴 유일한 대입 지점이 스크럽한다 (S-4 강제 지점 표)
        run.fail(reason, clock);
        runs.save(run);
    }

    private StatusTransition transition(Long candidateId,
            java.util.function.Function<ContributionCandidate, StatusTransition> step) {
        return step.apply(load(candidateId));
    }

    private ContributionCandidate load(Long candidateId) {
        return candidates.findById(candidateId)
                .orElseThrow(() -> new CandidateNotFoundException(candidateId));
    }

    /**
     * 재시도 전이의 결과.
     *
     * @param exhausted 🔴 상한이 소진돼 {@code FAILED} 로 갔는가. 호출자는 이것으로 루프를 끝낸다
     */
    record RetryOutcome(StatusTransition transition, int attempt, boolean exhausted) {}
}
