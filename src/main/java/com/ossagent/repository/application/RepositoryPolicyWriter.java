package com.ossagent.repository.application;

import com.ossagent.repository.adapter.out.persistence.RepositoryPolicyRepository;
import com.ossagent.repository.domain.OssRepository;
import com.ossagent.repository.domain.RepositoryPolicy;
import com.ossagent.repository.domain.RuleReading;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 규약 정책의 <b>쓰기 트랜잭션 경계</b>.
 *
 * <h2>🔴 왜 별도 컴포넌트인가 — 같은 클래스 안에 두면 트랜잭션이 안 걸린다</h2>
 *
 * <p>원래 이 메서드들은 {@code AnalyzeRepositoryPolicyUseCase} 의 {@code protected} 메서드였다.
 * <b>두 가지가 겹쳐 트랜잭션이 아예 시작되지 않았다.</b>
 *
 * <ol>
 *   <li><b>자기호출</b> — {@code this.saveAnalyzed()} 는 프록시를 타지 않는다</li>
 *   <li><b>{@code protected}</b> — Spring 의 {@code AnnotationTransactionAttributeSource} 는 기본이
 *       {@code publicMethodsOnly = true} 라, {@code protected} 메서드의 {@code @Transactional} 은
 *       외부에서 불러도 무시된다</li>
 * </ol>
 *
 * <p>그래서 {@code findById()} 가 자기 트랜잭션에서 돌고 즉시 커밋됐고, {@code open-in-view: false}
 * 이므로 반환된 엔티티는 <b>detached</b> 였다. 결과적으로 {@code reanalyze()} 의 변경이
 * <b>flush 되지 않고 사라졌다.</b>
 *
 * <p>🔴 <b>증상이 「조용한 성공」이라 더 위험했다.</b> 저장소가 나중에 AI 기여를 금지하면
 * 로그에는 {@code aiAllowed=false} 가 찍히고 반환 객체도 금지인데 <b>DB 는 허용 그대로</b>다.
 * 실제 게이트({@code assertContributionAllowed})는 DB 를 다시 읽으므로 <b>통과시킨다</b> —
 * 「AI 기여 금지 저장소를 후보에서 제외」(FR-2 · S-5)가 무너진다.
 *
 * <p>공개 메서드로 올려 프록시를 실제로 타게 한다. {@code AnalyzeRepositoryPolicyUseCase} 는
 * 이것을 <b>주입받아</b> 호출한다 — 자기호출이 아니다.
 */
@Component
public class RepositoryPolicyWriter {

    private static final Logger log = LoggerFactory.getLogger(RepositoryPolicyWriter.class);

    private final RepositoryPolicyRepository policies;
    private final Clock clock;

    public RepositoryPolicyWriter(RepositoryPolicyRepository policies, Clock clock) {
        this.policies = policies;
        this.clock = clock;
    }

    /**
     * 보류를 기록한다.
     *
     * <p>🔴 <b>이미 판정이 선 정책을 보류로 되돌리지 않는다.</b> {@code pending()} 은 새 엔티티를
     * 만드는데 {@code UNIQUE(repository_id)} 가 있어 제약 위반이 나고, 무엇보다 한 번 확인한
     * 판정을 지우면 사람이 풀어야 하는 상태가 된다(#24 미구현).
     */
    @Transactional
    public RepositoryPolicy savePending(OssRepository repository, RepositoryPolicy existing,
            String reason) {
        if (existing != null) {
            log.warn("규약을 다시 읽지 못했다 repo={} reason={} — 기존 판정을 유지한다",
                    repository.getUrl(), reason);
            return existing;
        }
        log.info("규약 판정 보류 repo={} reason={} — 사람이 해소한다 (#24)",
                repository.getUrl(), reason);
        return policies.save(RepositoryPolicy.pending(repository, reason, clock));
    }

    /**
     * 판정을 저장하거나 갱신한다.
     *
     * <p>갱신은 <b>영속 컨텍스트 안에서</b> 다시 읽어 수행한다. 밖에서 들고 온 엔티티를 고치면
     * detached 라 변경이 사라진다 — 이 클래스가 존재하는 이유가 그것이다.
     *
     * <p>보류·금지 상태라면 {@link RepositoryPolicy#reanalyze} 가 거부한다. 호출자가 이미
     * 걸러내지만, <b>엔티티 불변식이 최종 방어</b>다.
     */
    @Transactional
    public RepositoryPolicy saveAnalyzed(OssRepository repository, Long existingPolicyId,
            RuleReading reading) {
        log.info("규약 판정 완료 repo={} aiAllowed={}",
                repository.getUrl(), reading.aiContributionAllowed());
        if (existingPolicyId == null) {
            return policies.save(RepositoryPolicy.analyzed(repository, reading, clock));
        }
        RepositoryPolicy managed = policies.findById(existingPolicyId).orElseThrow(
                () -> new IllegalStateException("정책이 사라졌다: id=" + existingPolicyId));
        managed.reanalyze(reading, clock);
        // dirty checking 에 기대지 않고 명시적으로 저장한다 — 경계가 바뀌어도 안 깨진다
        return policies.save(managed);
    }
}
