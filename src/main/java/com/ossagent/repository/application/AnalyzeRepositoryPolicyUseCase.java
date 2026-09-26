package com.ossagent.repository.application;

import com.ossagent.agent.domain.LlmTransientException;
import com.ossagent.repository.adapter.out.persistence.OssRepositoryRepository;
import com.ossagent.repository.adapter.out.persistence.RepositoryPolicyRepository;
import com.ossagent.repository.domain.ContributionConstraints;
import com.ossagent.repository.domain.ContributionNotAllowedException;
import com.ossagent.repository.domain.ContributionRuleInterpreter;
import com.ossagent.repository.domain.OssRepository;
import com.ossagent.repository.domain.PolicyDocumentPath;
import com.ossagent.repository.domain.PolicyDocumentSource;
import com.ossagent.repository.domain.RepositoryCoordinates;
import com.ossagent.repository.domain.RepositoryDocuments;
import com.ossagent.repository.domain.RepositoryNotFoundException;
import com.ossagent.repository.domain.PolicyClearance;
import com.ossagent.repository.domain.RepositoryPolicy;
import com.ossagent.repository.domain.RepositorySource;
import com.ossagent.repository.domain.RuleReading;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 대상 저장소의 기여 규약을 수집·판정해 {@link RepositoryPolicy} 로 고정한다 — 이슈 #7.
 *
 * <p><b>이 UseCase 가 S-5 의 실행체다.</b> 「못 읽음」을 「규약 없음」으로 번역하면
 * 규약 위반 PR 이 외부 OSS 로 나간다.
 *
 * <h2>🔴 트랜잭션 경계</h2>
 *
 * <p>GitHub 수집과 LLM 판정은 <b>트랜잭션 밖</b>이다. 경로 13개 × 대외 호출 + LLM 호출이라
 * 한 트랜잭션에 넣으면 DB 커넥션을 그 시간 내내 잡는다. 조회와 저장만 짧은 트랜잭션이다.
 */
@Service
public class AnalyzeRepositoryPolicyUseCase {

    private static final Logger log = LoggerFactory.getLogger(AnalyzeRepositoryPolicyUseCase.class);

    private final OssRepositoryRepository repositories;
    private final RepositoryPolicyRepository policies;
    private final RepositorySource repositorySource;
    private final PolicyDocumentSource documentSource;
    private final ContributionRuleInterpreter interpreter;
    private final RepositoryPolicyWriter writer;

    public AnalyzeRepositoryPolicyUseCase(OssRepositoryRepository repositories,
            RepositoryPolicyRepository policies, RepositorySource repositorySource,
            PolicyDocumentSource documentSource, ContributionRuleInterpreter interpreter,
            RepositoryPolicyWriter writer) {
        this.repositories = repositories;
        this.policies = policies;
        this.repositorySource = repositorySource;
        this.documentSource = documentSource;
        this.interpreter = interpreter;
        this.writer = writer;
    }

    /**
     * 규약을 분석한다.
     *
     * @return 저장된 정책. <b>일시적 실패로 중단하면 {@link Optional#empty()}</b> —
     *         아무것도 쓰지 않았다는 뜻이고, 다음 스캔이 다시 시도한다
     */
    public Optional<RepositoryPolicy> analyze(Long repositoryId) {
        Snapshot snapshot = load(repositoryId);

        // ① 이미 보류·금지면 더 볼 것이 없다. 반영할 수 없는 판정에 토큰을 쓰지 않는다 — S-5 ⑤
        if (snapshot.blocksReanalysis()) {
            log.info("재분석 대상이 아니다 repo={} — 보류·금지는 사람이 해소한다 (#24)",
                    snapshot.coordinates().fullName());
            return Optional.of(snapshot.policy());
        }

        try {
            // ② 보관된 저장소는 PR 을 받지 않는다. 여기서 안 거르면 파이프라인을 끝까지
            //    돌린 뒤 PR 생성에서야 실패한다
            if (!repositorySource.fetchMetadata(snapshot.coordinates()).acceptsContributions()) {
                log.info("보관된 저장소다 repo={} — 규약을 분석하지 않는다",
                        snapshot.coordinates().fullName());
                return Optional.empty();
            }

            RepositoryDocuments documents =
                    documentSource.collect(snapshot.coordinates(), PolicyDocumentPath.all());

            // ③ 🔴 일시적 실패 — 아무것도 쓰지 않고 중단한다.
            //    보류로 만들면 한 시간 뒤면 저절로 풀렸을 일이 되돌릴 수단 없는 영구 보류가
            //    된다(#24 미구현). 정책이 없으므로 구현 단계는 어차피 막히고(FR-4),
            //    안전 성질은 보류와 같으면서 복구만 자동이다
            if (documents.hasTransientlyUnreadableRequired()) {
                log.warn("규약 문서를 일시적으로 읽지 못했다 repo={} — 기록 없이 중단, 다음 스캔에서 재시도",
                        snapshot.coordinates().fullName());
                return Optional.empty();
            }

            // ④ 영구적으로 못 읽었다 → 보류. 사람이 봐야 풀린다
            if (documents.hasPermanentlyUnreadableRequired()) {
                return Optional.of(writer.savePending(
                        snapshot.repository(), snapshot.policy(), documents.requiredPendingReason()));
            }

            // ⑤ 필수 경로가 전부 404 → 허용. 금지 표기가 존재할 수 없다 (Q-8 확정 ①).
            //    LLM 을 부르지 않는다 — 판정할 텍스트가 없는데 토큰을 쓸 이유가 없다
            RuleReading reading = documents.readDocuments().isEmpty()
                    ? RuleReading.allowedByAbsence()
                    : interpreter.interpret(snapshot.coordinates(), documents);

            // ⑥ 판정이 서지 않았다 → 보류
            if (reading.isUndetermined()) {
                return Optional.of(writer.savePending(
                        snapshot.repository(), snapshot.policy(), "LLM_UNDETERMINED"));
            }
            return Optional.of(writer.saveAnalyzed(snapshot.repository(),
                    snapshot.policy() == null ? null : snapshot.policy().getId(), reading));

        } catch (LlmTransientException e) {
            // LLM 쪽 일시적 실패도 ③과 같다 — 기록 없이 중단
            log.warn("규약 판정이 일시적으로 실패했다 repo={} reason={} — 기록 없이 중단",
                    snapshot.coordinates().fullName(), e.reason());
            return Optional.empty();
        }
    }

    /**
     * 🔴 <b>정책이 없을 때만 분석하고, 스캔 파이프라인이 쓸 판정을 돌려준다</b> — #14 FR-0.
     *
     * <h2>⚠️ 이것은 게이트가 아니다</h2>
     *
     * <p>게이트는 {@link #assertContributionAllowed} <b>하나뿐</b>이다. 이 메서드는
     * 「수집을 시작할 가치가 있는가」를 <b>미리</b> 보는 것이고, 빠지거나 틀려도 게이트가
     * 여전히 막는다. <b>이것만 부르고 넘어가면 S-5 가 뚫린다</b> — 이름이 비슷해 혼동하기
     * 쉬운 자리라 적어 둔다.
     *
     * <h2>🔴 왜 「없을 때만」인가</h2>
     *
     * <p>{@link #analyze} 는 보류·금지면 비용 없이 즉시 반환하지만 <b>허용이면 재분석</b>한다
     * ({@code blocksReanalysis()} 가 false). 스케줄러가 매 주기 부르면 규약이 바뀌지도
     * 않았는데 저장소마다 GitHub 호출 + LLM 1회씩을 <b>영구히</b> 태운다.
     *
     * <p>⚠️ 대가는 「한 번 허용이면 영원히 허용」이다. 대상 저장소가 나중에 AI 기여를
     * 금지해도 모른다. 갱신 주기(TTL)는 「규약이 얼마나 자주 바뀌는가」 데이터가 없어
     * 지금 정하지 않는다 — PLAN-14 R-4.
     *
     * <h2>🔴 트랜잭션을 걸지 않는다</h2>
     *
     * <p>이 안에서 {@link #analyze} 가 GitHub·LLM 을 부른다. 감싸면 그 호출이 트랜잭션
     * 안으로 들어간다 — {@code architecture.md} §2. 조회는 {@link #load} 가 짧게 연다.
     *
     * <p>⚠️ {@code AnalyzeIssuesUseCase.assertNoTransaction()} 은 <b>이 단계를 잡아주지
     * 못한다.</b> 그 가드는 파이프라인 4단계에 있고, 그때는 여기서 열었던 트랜잭션이 이미
     * 닫혀 통과한다. 그래서 같은 단언을 여기에도 둔다.
     */
    public ScanTarget analyzeIfAbsent(Long repositoryId) {
        assertNoTransaction();
        Snapshot snapshot = load(repositoryId);

        RepositoryPolicy policy = snapshot.policy() != null
                ? snapshot.policy()
                : analyze(repositoryId).orElse(null);

        return toScanTarget(repositoryId, snapshot.coordinates(), policy);
    }

    private static ScanTarget toScanTarget(Long repositoryId, RepositoryCoordinates coordinates,
            RepositoryPolicy policy) {
        if (policy == null) {
            // 일시적 실패 — 그리고 보관된 저장소도 여기로 온다. 가를 수단이 없다
            return ScanTarget.skip(repositoryId, coordinates,
                    ScanTarget.SkipReason.POLICY_UNAVAILABLE);
        }
        if (policy.isAiContributionUndetermined()) {
            return ScanTarget.skip(repositoryId, coordinates,
                    ScanTarget.SkipReason.POLICY_UNDETERMINED);
        }
        if (policy.isAiContributionForbidden()) {
            return ScanTarget.skip(repositoryId, coordinates,
                    ScanTarget.SkipReason.CONTRIBUTION_FORBIDDEN);
        }
        return ScanTarget.allowed(repositoryId, coordinates);
    }

    /**
     * 🔴 대외 호출이 트랜잭션 안에 들어가는 것을 막는다.
     *
     * <p>이 클래스는 javadoc 으로만 「트랜잭션 밖」을 지키고 있었다. 호출자가 감싸면
     * GitHub 응답과 LLM 응답을 기다리는 내내 DB 커넥션이 잡히는데, <b>증상이 「느리다」뿐</b>이라
     * 리뷰에서도 놓치기 쉽다. #14 가 자동 경로(스케줄러)를 만들면서 호출자가 늘어나므로
     * 규약을 코드로 바꾼다.
     */
    private static void assertNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "규약 분석을 트랜잭션 안에서 부를 수 없다 — GitHub·LLM 호출이 커넥션을 "
                            + "점유한다. 호출자의 @Transactional 을 제거한다 (architecture.md 규율)");
        }
    }

    /**
     * 🔴 <b>기여 가능한 저장소인지 단언한다</b> — FR-4 · S-5.
     *
     * <p>{@code boolean} 이 아니라 예외인 것이 설계다. 「RepositoryPolicy 없이 구현 단계로
     * 넘어가지 못하게 <b>막기</b>」가 요구이고, 반환값은 무시할 수 있지만 예외는 무시하기 어렵다.
     *
     * <p>트랜잭션 안에서 조회한다 — {@code OssRepository.policy} 가 LAZY 라 트랜잭션 밖에서
     * 건드리면 {@code LazyInitializationException} 이 난다. 게이트가 그런 이유로 죽으면
     * 호출자가 그것을 {@code catch} 해 넘길 위험이 생긴다.
     *
     * <p>호출자는 #11 · #24 다. 이 PR 은 단언을 제공하고 강제는 그쪽에서 일어난다.
     */
    @Transactional(readOnly = true)
    public void assertContributionAllowed(Long repositoryId) {
        // 🔴 판정 로직을 두 벌 두지 않는다. 둘이 각자 진화하면 한쪽에만 새 규칙이
        //    들어가고, S-5 게이트가 부르는 문에 따라 다르게 판정하게 된다 (#24)
        //
        // ⚠️ self-invocation 이라 clearanceFor 의 @Transactional 은 적용되지 않는다.
        //    지금은 무해하다 — 이 메서드가 같은 설정(readOnly=true · REQUIRED)으로 이미
        //    트랜잭션을 열었고, 안쪽은 그것을 그대로 쓴다.
        //    🔴 clearanceFor 의 전파·readOnly 를 바꾸는 사람은 이 경로에 그것이 먹지 않는다는
        //    것을 알아야 한다. 증상은 예외가 아니라 「설정이 조용히 무시된다」라 눈에 안 띈다 —
        //    #11·#16 이 같은 함정에서 「저장이 사라진다」를 겪었다. 바꿔야 하면 별도 빈으로 뺀다
        clearanceFor(repositoryId);
    }

    /**
     * 🔴 구현 단계 통행증을 발급한다 — S-5 · #24.
     *
     * <p>{@code ContributionCandidate.startImplementing} 이 이것을 <b>인자로 요구</b>하므로,
     * 정책을 확인하지 않고 구현 단계로 넘어가는 것이 <b>컴파일되지 않는다.</b>
     * #12 가 건 에스컬레이션 조건(「정책 확인 없이 부르면 블로킹」)을 문서가 아니라
     * 타입으로 옮긴 것이다.
     *
     * <p>🔴 <b>{@code Optional} 을 돌려주지 않는다.</b> 그러면 {@code .orElse(null)} 한 줄로
     * <b>무시할 수 있는 게이트</b>가 된다 — 위 {@link ContributionNotAllowedException} 의
     * javadoc 이 「{@code boolean} 이 아니라 예외인 것이 설계다. 반환값은 무시할 수 있지만
     * 예외는 무시하기 어렵다」로 못 박아 둔 그 함정이고, <b>같은 클래스의 두 문이 다른 기준을
     * 쓰지 않는다.</b>
     *
     * <p>⚠️ <b>정책 행이 없는 경우는 여기서만 판정할 수 있다.</b>
     * {@code RepositoryPolicy.clearance()} 는 엔티티의 메서드라 행이 없으면 부를 대상이
     * 없다 — {@code NOT_ANALYZED} 는 이 자리의 몫이다.
     *
     * <p>⚠️ 트랜잭션 안에서 조회한다 — {@code RepositoryPolicy.repository} 가 LAZY 라
     * 밖에서 {@code clearance()} 를 부르면 {@code LazyInitializationException} 이 난다.
     * <b>게이트가 그런 이유로 죽으면 호출자가 그것을 {@code catch} 해 넘길 위험이 생긴다.</b>
     *
     * <p>⚠️ 돌려주는 통행증은 <b>스냅샷</b>이다. 같은 트랜잭션 안에서 쓰는 것을 전제한다 —
     * {@code PolicyClearance} javadoc.
     *
     * @throws ContributionNotAllowedException 행 없음({@code NOT_ANALYZED}) · 보류 · 금지
     */
    @Transactional(readOnly = true)
    public PolicyClearance clearanceFor(Long repositoryId) {
        RepositoryPolicy policy = policies.findByRepositoryId(repositoryId)
                .orElseThrow(() -> new ContributionNotAllowedException(
                        repositoryId, ContributionNotAllowedException.Reason.NOT_ANALYZED));

        // 보류·금지 판정은 엔티티가 한다 — 여기서 다시 쓰면 두 벌이 된다
        return policy.clearance();
    }

    /**
     * 규약 제약을 <b>값으로</b> 내보낸다 — 이슈 #16 FR-6.
     *
     * <h2>🔴 이것은 게이트가 아니다</h2>
     * {@link #assertContributionAllowed} 와 <b>거부 조건이 다른 것이 정상</b>이다.
     *
     * <table>
     *   <tr><th></th><th>{@code assertContributionAllowed}</th><th>이 메서드</th></tr>
     *   <tr><td>뜻</td><td><b>「해도 된다」</b> — 권한</td><td><b>「이렇게 해라」</b> — 데이터</td></tr>
     *   <tr><td>못 받으면</td><td>🔴 진행 불가 (S-5)</td><td>기본값으로 진행 가능</td></tr>
     *   <tr><td>보류·금지일 때</td><td>예외</td><td><b>값을 돌려준다</b></td></tr>
     * </table>
     *
     * <p>⚠️ <b>둘을 합치고 싶어지는 자리다.</b> 「제약을 줄 때 허용 여부도 함께 주면 조회가
     * 한 번」으로 보이지만, 그러면 <b>빌드 명령을 알고 싶어 부른 호출이 통행증을 부산물로</b>
     * 쥐여 준다. 게이트가 부산물이 되는 순간 「정책을 확인하지 않고 구현 단계로 가는 것이
     * 불가능하다」가 성립하지 않는다. 판정 필드를 {@link ContributionConstraints} 에 싣지 않는
     * 이유도 같다.
     *
     * <p>🔴 <b>여기의 {@code @Transactional} 은 알리바이가 아니다</b> — 아래 {@code load} 와
     * 다르다. 이 메서드는 <b>다른 빈</b>({@code PlanImplementationUseCase})이 부르므로
     * 프록시를 탄다. 같은 클래스에서 부르기 시작하면 그 순간 무의미해진다.
     *
     * @return 정책이 아직 없으면 {@link ContributionConstraints#unknown()}.
     *         「제약이 없다」가 아니라 <b>「우리가 아는 제약이 없다」</b>는 뜻이다
     */
    @Transactional(readOnly = true)
    public ContributionConstraints constraintsOf(Long repositoryId) {
        if (repositoryId == null) {
            throw new IllegalArgumentException("저장소 식별자는 필수다");
        }
        return policies.findByRepositoryId(repositoryId)
                .map(policy -> new ContributionConstraints(
                        policy.getJavaVersion(),
                        policy.getBuildCommand(),
                        policy.getTestCommand(),
                        policy.isTestsRequired(),
                        policy.isIssueReferenceRequired(),
                        policy.isSignoffRequired()))
                .orElseGet(ContributionConstraints::unknown);
    }

    /**
     * ⚠️ <b>{@code @Transactional} 을 붙이지 않는다 — 붙여도 적용되지 않는다.</b>
     *
     * <p>{@code analyze}·{@code analyzeIfAbsent} 가 {@code this} 로 부르는 self-invocation
     * 이라 프록시를 타지 않는다. 애노테이션을 달아 두면 <b>「트랜잭션 안에서 읽는다」는
     * 알리바이</b>만 남고 실제로는 동작하지 않는다 — {@code ScanProperties} 가 경계한
     * 「영원히 false 인 필드」와 같은 유형이다.
     *
     * <p>없어도 되는 이유 — 두 번의 독립적인 조회이고 각 Spring Data 메서드가 자기
     * 트랜잭션을 연다. 🔴 <b>{@code repository.getPolicy()} 를 쓰지 않는 것이 핵심이다</b>
     * (LAZY 라 트랜잭션 밖에서 건드리면 터진다). 정책은 {@code policies.findByRepositoryId}
     * 로 따로 읽는다.
     *
     * <p>⚠️ 공개 메서드로 올리게 되면 그때 트랜잭션 경계를 다시 판단한다 —
     * {@code RepositoryPolicyWriter} 가 같은 이유로 분리된 선례다.
     */
    protected Snapshot load(Long repositoryId) {
        OssRepository repository = repositories.findById(repositoryId)
                .orElseThrow(() -> new RepositoryNotFoundException(repositoryId));
        return new Snapshot(
                repository,
                new RepositoryCoordinates(repository.getOwner(), repository.getName()),
                policies.findByRepositoryId(repositoryId).orElse(null));
    }



    /** 트랜잭션 밖으로 들고 나가는 값. 엔티티를 LAZY 인 채로 끌고 다니지 않는다. */
    protected record Snapshot(OssRepository repository, RepositoryCoordinates coordinates,
            RepositoryPolicy policy) {

        boolean blocksReanalysis() {
            return policy != null
                    && (policy.isAiContributionUndetermined() || policy.isAiContributionForbidden());
        }
    }
}
