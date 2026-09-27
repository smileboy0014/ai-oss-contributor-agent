package com.ossagent.candidate.application;

import com.ossagent.candidate.adapter.out.persistence.ContributionCandidateRepository;
import com.ossagent.candidate.adapter.out.persistence.GeneratedChangeRepository;
import com.ossagent.candidate.domain.DiffReview;
import com.ossagent.candidate.domain.GeneratedChange;
import com.ossagent.candidate.domain.CandidateNotFoundException;
import com.ossagent.candidate.domain.ContributionCandidate;
import com.ossagent.candidate.domain.ImplementationNotReadyException;
import com.ossagent.candidate.domain.StageResult;
import com.ossagent.candidate.domain.StatusTransition;
import com.ossagent.candidate.domain.VerificationReport;
import com.ossagent.issue.application.FindAnalyzableIssuesUseCase;
import com.ossagent.issue.domain.AnalyzableIssue;
import com.ossagent.repository.application.AnalyzeRepositoryPolicyUseCase;
import com.ossagent.repository.domain.PolicyClearance;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 착수 게이트의 <b>트랜잭션 구간</b>만 담는다 — #18 · S-5 · S-6.
 *
 * <h2>🔴 왜 별도 빈인가 — self-invocation 함정</h2>
 *
 * <p>{@code @Transactional} 은 프록시로 걸리므로 <b>같은 빈 안에서 부르면 적용되지 않는다.</b>
 * 증상이 예외가 아니라 <b>「설정이 조용히 무시된다」</b>라 눈에 띄지 않는다 —
 * {@code AnalyzeRepositoryPolicyUseCase} 가 같은 함정을 주석으로 남겨 뒀고,
 * #11·#16 은 그것 때문에 <b>「저장이 사라진다」</b>를 겪었다.
 *
 * <p>{@code ImplementCandidateUseCase} 는 <b>트랜잭션 없이</b> 오케스트레이션하고
 * (샌드박스 최대 30분 · LLM 호출), 트랜잭션이 필요한 조각만 이 빈을 통해 부른다.
 *
 * <h2>🔴 통행증을 이 트랜잭션 안에서 발급하고 쓴다 (TOCTOU)</h2>
 *
 * <p>통행증은 <b>스냅샷</b>이다. 발급과 사용 사이에 정책이 바뀔 수 있으므로 같은
 * 트랜잭션 안에서 쓴다. 🔴 단 <b>샌드박스 실행은 이 트랜잭션 밖</b>이다 — 최대 30분짜리
 * 실행을 트랜잭션에 넣으면 커넥션이 30분 잡힌다.
 *
 * <h2>🔴 통행증은 <b>후보의 저장소</b>로만 조회한다</h2>
 *
 * <p>{@code startImplementing} 은 통행증이 이 후보의 것인지 <b>검증하지 못한다</b> —
 * 후보가 {@code issueId} 만 들고 있어 자기 {@code repositoryId} 를 모르기 때문이다.
 * 발급 경로가 {@code clearanceFor(repositoryId)} 하나뿐이므로 <b>후보의 저장소로
 * 조회하기만 하면 어긋날 수 없고, 그 한 줄이 이 이슈의 몫</b>이다.
 *
 * <p>⚠️ 그래서 {@code repositoryId} 를 <b>파라미터로 받지 않는다.</b> 받으면 호출자가
 * 다른 저장소의 통행증을 끌어올 수 있고, 그 순간 이 불변식이 호출자 신뢰에 기댄다.
 */
@Component
class CandidateImplementationWriter {

    private static final Logger log = LoggerFactory.getLogger(CandidateImplementationWriter.class);

    private final ContributionCandidateRepository candidates;
    private final GeneratedChangeRepository changes;
    private final FindAnalyzableIssuesUseCase issues;
    private final AnalyzeRepositoryPolicyUseCase policies;
    private final ExecutionProperties properties;
    private final Clock clock;

    CandidateImplementationWriter(ContributionCandidateRepository candidates,
            GeneratedChangeRepository changes,
            FindAnalyzableIssuesUseCase issues,
            AnalyzeRepositoryPolicyUseCase policies,
            ExecutionProperties properties,
            Clock clock) {
        this.candidates = candidates;
        this.changes = changes;
        this.issues = issues;
        this.policies = policies;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 게이트 <b>판정만</b> 한다 — 전이하지 않는다. 계획·컨텍스트·워크스페이스처럼 <b>대외를 읽는
     * 준비 단계</b>가 이 뒤, {@link #start} 앞에 온다.
     *
     * <h2>🔴 왜 판정과 전이를 갈랐나 — 레이트리밋은 실패가 아니라 지연이다</h2>
     *
     * <p>원래는 {@link #start} 가 판정과 전이를 한 번에 했고 계획 수립이 그 <b>뒤</b>였다. 그러면
     * 계획·컨텍스트 조회가 GitHub 레이트리밋에 닿았을 때 후보가 이미 {@code IMPLEMENTING} 이라
     * 나갈 길이 {@code FAILED} 뿐이었다 — 리밋 한 번이 후보를 <b>영구히 죽였다.</b>
     * PLAN-15 가 「첫 소비자가 리밋을 {@code FAILED} 로 받으면 그 번역이 위반」이라고 #16 에
     * 넘긴 것을 #16 이 받지 않아 생긴 구멍이다.
     *
     * <p>지금은 대외 읽기가 전부 <b>{@code SELECTED} 인 채로</b> 일어난다. 리밋이면 예외가 그대로
     * 올라가 503 + {@code Retry-After} 가 되고 후보는 그대로 남는다. 그래도 판정을 준비 단계
     * <b>앞에</b> 두는 이유는 — 고르지 않은 후보·금지 저장소 요청에 LLM 계획 비용을 태우지 않기
     * 위해서다. {@link #start} 가 같은 판정을 <b>다시</b> 한다(TOCTOU — 그 사이 정책이 바뀔 수 있다).
     *
     * @throws com.ossagent.repository.domain.ContributionNotAllowedException 보류·금지 저장소 → 403 (S-5)
     * @throws ImplementationNotReadyException 실행기 미배선 → 503. 순서는 {@link #start} 와 같다
     */
    @Transactional(readOnly = true)
    Admission admit(Long candidateId, boolean executorReady) {
        Gate gate = gate(candidateId, executorReady);
        return new Admission(candidateId, gate.issue());
    }

    /**
     * {@code SELECTED → IMPLEMENTING} — 🔴 <b>S-6 두 번째 승인 게이트</b>.
     *
     * <p>순서가 중요하다 — <b>통행증을 먼저 받고</b> 전이한다. 반대로 하면 정책 위반
     * 후보가 잠깐이라도 {@code IMPLEMENTING} 이 되고, 그 사이에 다른 경로가 그것을 본다.
     *
     * <p>{@link #admit} 이 이미 같은 판정을 했더라도 <b>여기서 다시 한다.</b> 통행증은 스냅샷이고
     * 준비 단계(LLM 계획 · clone)는 분 단위라, 그 사이에 정책이 금지로 바뀌었을 수 있다.
     *
     * @param executorReady 🔴 실행기가 배선됐는가. <b>순서가 중요하다</b> — 이 값은
     *                      <b>통행증 확인 뒤에</b> 본다. 앞에서 보면 정책이 막았어야 할
     *                      요청이 <b>503 으로 가려져</b> S-5 게이트가 한 번도 돌지 않는다.
     *                      그러면 「막는다」를 검증할 수 없고, 나중에 실행기가 붙는 순간
     *                      <b>그때 처음으로</b> 정책 경로가 실행된다
     * @throws com.ossagent.repository.domain.ContributionNotAllowedException
     *         보류·금지 저장소 — {@code ApiExceptionHandler} 가 <b>403</b> 으로 매핑한다 (S-5)
     */
    @Transactional
    ImplementationStart start(Long candidateId, boolean executorReady) {
        Gate gate = gate(candidateId, executorReady);
        ContributionCandidate candidate = gate.candidate();

        StatusTransition transition =
                candidate.startImplementing(gate.clearance(), properties.maxAttempts(), clock);

        logAfterCommit(candidateId, transition);
        return new ImplementationStart(candidateId, gate.issue(), candidate.getAttempt(), transition);
    }

    /** 판정 셋 — 통행증 → 후보 상태·상한·사람 선택 → 실행기. {@link #admit} 과 {@link #start} 가 공유한다. */
    private Gate gate(Long candidateId, boolean executorReady) {
        ContributionCandidate candidate = candidates.findById(candidateId)
                .orElseThrow(() -> new CandidateNotFoundException(candidateId));

        AnalyzableIssue issue = issues.findOne(candidate.getIssueId())
                .orElseThrow(() -> new IllegalStateException(
                        "후보가 가리키는 이슈가 없다 candidateId=" + candidateId));

        // 🔴 후보의 저장소로만 부른다 — 위 javadoc 의 불변식
        PolicyClearance clearance;
        try {
            clearance = policies.clearanceFor(issue.repositoryId());
        } catch (RuntimeException e) {
            // 🔴 거부는 여기서 남긴다. 아래 afterCommit 은 롤백 경로에서 돌지 않으므로
            //    거기 두면 「막았다」는 기록이 영영 남지 않는다
            log.warn("승인 게이트 거부 gate=착수 candidateId={} repositoryId={} reason={}",
                    candidateId, issue.repositoryId(), e.getMessage());
            throw e;
        }

        // 🔴 **판정을 먼저, 전이는 나중에.** 순서가 셋 다 중요하다.
        //
        //   ① 통행증 → 이 판정. 반대면 정책이 막았어야 할 요청이 아래 503 에 가려져
        //      S-5 게이트가 한 번도 돌지 않는다.
        //   ② 이 판정 → 실행기 검사. 반대면 「고르지 않은 후보」(409)가 503 에 가려진다 —
        //      요청이 틀린 것을 우리 사정으로 덮는 셈이다.
        //   ③ 실행기 검사 → 전이. 🔴 **전이를 하지 않으므로 롤백에 기대지 않는다.**
        //
        //   ⚠ 초안은 전이한 뒤 던져 롤백에 맡겼다. 작동은 했지만(부분 커밋 경로 없음을 확인)
        //     그 구간에 들어오는 **모든 부작용이 트랜잭션을 알아야 한다**는 제약이 생기고,
        //     코드가 그 제약을 말해주지 않는다 — 메트릭·이벤트는 롤백되지 않는다.
        //     「판정과 전이가 한 메서드에 묶여 어쩔 수 없다」는 **사실이 아니었다.**
        try {
            candidate.assertCanStartImplementing(clearance, properties.maxAttempts());
        } catch (RuntimeException e) {
            // 🔴 거부는 여기서 남긴다 — afterCommit 은 롤백 경로에서 돌지 않으므로
            //    거기 두면 「막았다」는 기록이 영영 남지 않는다
            log.warn("승인 게이트 거부 gate=착수 candidateId={} status={} reason={}",
                    candidateId, candidate.getStatus(), e.getMessage());
            throw e;
        }

        if (!executorReady) {
            log.warn("착수 중단 — 실행기 미배선 candidateId={} (전이하지 않는다)", candidateId);
            throw new ImplementationNotReadyException(
                    "코딩 에이전트와 산출 경로가 아직 배선되지 않았다 (#18 의 C·D)");
        }
        return new Gate(candidate, issue, clearance);
    }

    private record Gate(ContributionCandidate candidate, AnalyzableIssue issue,
            PolicyClearance clearance) {
    }

    /**
     * 샌드박스 검증 결과를 그 바퀴의 {@code GeneratedChange} 행에 남긴다 — 짧은 트랜잭션 (#19).
     *
     * <p>🔴 이 메서드가 오랫동안 <b>없었다.</b> #19 는 {@code StageResult} 값 타입을 세우고
     * 「컬럼에 앉히는 것은 #18」로, #18 은 「검증 전이라 담을 값이 없다」로 서로 넘겨
     * {@code testResult} 가 늘 {@code null} 이었다. PR 본문의 검증 절이 비어 나간 이유다.
     *
     * <p>단계별 요약을 이어 붙인다. {@code StageResult.summary} 는 이미 스크럽·절단됐고,
     * {@code GeneratedChange.recordVerification} 이 한 번 더 가린다.
     */
    @Transactional
    void recordVerification(Long changeId, VerificationReport report) {
        GeneratedChange change = loadChange(changeId);
        change.recordVerification(render(report));
        changes.save(change);
    }

    /** AI 리뷰 결과를 남긴다 — 짧은 트랜잭션 (#20). {@code DiffReview} 는 생성자에서 이미 스크럽됐다. */
    @Transactional
    void recordReview(Long changeId, DiffReview review) {
        GeneratedChange change = loadChange(changeId);
        change.recordReview(render(review));
        changes.save(change);
    }

    private GeneratedChange loadChange(Long changeId) {
        return changes.findById(changeId)
                .orElseThrow(() -> new IllegalStateException(
                        "이 바퀴의 GeneratedChange 행이 없다 changeId=" + changeId));
    }

    /**
     * PR 본문에 그대로 실릴 형태 — 판정을 말하지 않고 <b>무엇을 돌렸고 어떻게 끝났는지</b>만 적는다.
     * Q-4 가 열려 있어 「테스트 실패」가 코드 잘못이 아닐 수 있기 때문이다.
     */
    static String render(VerificationReport report) {
        StringBuilder out = new StringBuilder();
        for (StageResult stage : report.stages()) {
            out.append("## ").append(stage.stage()).append(" — ").append(stage.outcome());
            if (stage.exitCode() != null) {
                out.append(" (exit ").append(stage.exitCode()).append(')');
            }
            if (stage.outputTruncated()) {
                out.append(" [output truncated]");
            }
            out.append('\n');
            if (!stage.summary().isBlank()) {
                out.append(stage.summary().strip()).append('\n');
            }
            out.append('\n');
        }
        return out.toString().strip();
    }

    static String render(DiffReview review) {
        StringBuilder out = new StringBuilder("verdict: ").append(review.verdict()).append('\n');
        out.append(review.summary().strip()).append('\n');
        for (String finding : review.findings()) {
            out.append("- ").append(finding.strip()).append('\n');
        }
        return out.toString().strip();
    }

    /**
     * 🔴 생성 변경분을 남긴다 — <b>짧은 트랜잭션</b>이다 (#18).
     *
     * <p>{@code GeneratedChange.record(...)} 가 <b>유일한 생성 경로</b>이고 거기서 diff 를
     * 스크럽한다 (S-4). 여기서는 저장만 한다.
     *
     * <p>⚠️ <b>덮어쓰지 않는다.</b> 재시도마다 새 행을 남긴다 — 덮어쓰면 무엇이 어떻게
     * 바뀌었는지 추적이 사라진다.
     *
     * @param diff 🔴 <b>스크럽 전 원문</b>. 팩토리가 스크럽한다
     * @return 저장된 행의 id — 로그 상관관계용
     */
    @Transactional
    Long recordChange(Long candidateId, String branchName, String diff) {
        GeneratedChange change = changes.save(
                GeneratedChange.record(candidateId, branchName, diff, clock));
        return change.getId();
    }


    /** 🔴 커밋 확정 후에만 게이트 통과를 남긴다 — {@code logging.md} 「통과한 것도 남긴다」. */
    private void logAfterCommit(Long candidateId, StatusTransition transition) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.warn("트랜잭션 동기화가 없다 — 커밋 확정 여부를 알 수 없는 채로 남긴다 gate=착수");
            logGatePassed(candidateId, transition);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    logGatePassed(candidateId, transition);
                } catch (RuntimeException e) {
                    // 로깅 실패가 이미 커밋된 승인을 되돌릴 수는 없다. 삼키되 삼켰다는 사실을 남긴다
                    log.warn("게이트 통과 로그를 남기지 못했다 candidateId={}", candidateId, e);
                }
            }
        });
    }

    private void logGatePassed(Long candidateId, StatusTransition transition) {
        log.info("승인 게이트 통과 gate=착수 candidateId={} {} → {}",
                candidateId, transition.from(), transition.to());
    }

    /** {@link #admit} 의 결과 — 게이트를 통과했고 <b>아직 전이하지 않은</b> 후보. */
    record Admission(Long candidateId, AnalyzableIssue issue) {

        Long repositoryId() {
            return issue.repositoryId();
        }

        Integer issueNumber() {
            return issue.githubIssueNumber();
        }
    }

    /**
     * 트랜잭션 밖으로 나가는 값.
     *
     * <p>🔴 <b>엔티티를 내보내지 않는다.</b> 트랜잭션 밖에서 엔티티를 들고 다니면
     * 지연 로딩이 터지거나(detached) 우연히 살아 있는 영속 컨텍스트에 기대게 된다.
     */
    record ImplementationStart(Long candidateId, AnalyzableIssue issue,
            int attempt, StatusTransition transition) {

        Long repositoryId() {
            return issue.repositoryId();
        }

        Integer issueNumber() {
            return issue.githubIssueNumber();
        }
    }
}
