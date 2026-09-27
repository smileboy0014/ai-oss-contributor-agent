package com.ossagent.repository.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 한 저장소의 스캔 실행 상태 — <b>진행 상태와 중복 방어를 함께 맡는다</b> (#26).
 *
 * <h2>🔴 프로세스 메모리에서 DB 로 옮기면서 사라진 안전장치</h2>
 *
 * <p>{@code InMemoryScanExecutionRegistry} 에는 아무도 적어 두지 않은 장치가 있었다 —
 * <b>재기동이 상태를 지운다.</b> 그 javadoc 의 「재기동 외에 복구 수단이 없다」는 경고는
 * 뒤집으면 <b>「재기동하면 복구된다」</b>는 뜻이다.
 *
 * <p>DB 로 옮기면 그것이 사라진다. 인스턴스가 {@code kill -9} 되면 {@code RUNNING} 행이
 * 남고 그 저장소는 <b>영원히 409</b> 가 된다. 그래서 {@link #leaseExpiresAt} 이 있다 —
 * 활성 판정은 <b>국면과 리스를 함께</b> 본다.
 *
 * <h2>⚠️ 자리 잡기({@code tryStart})는 이 엔티티를 거치지 않는다</h2>
 *
 * <p>🔴 <b>의도다.</b> 「읽고 → 판단하고 → 쓴다」로 짜면 두 인스턴스가 그 사이를 통과해
 * <b>둘 다 스캔을 시작한다.</b> 원자성이 필요한 그 한 전이만
 * {@code ScanExecutionJpaRepository} 의 <b>조건부 UPDATE 한 방</b>으로 한다.
 *
 * <p>여기 있는 전이 메서드들은 <b>이미 자리를 잡은 뒤</b>의 것이라 경쟁이 없다.
 *
 * <h2>애그리거트</h2>
 *
 * <p>{@code repository} 애그리거트의 <b>멤버</b>다 — 저장소당 1행이고 덮어쓴다.
 * {@code AgentRun}·{@code GeneratedChange} 가 멤버가 아닌 이유(무한정 자란다)가
 * 여기엔 없다. PK 가 곧 {@code oss_repository} 로의 FK 다.
 *
 * <p>⚠️ 루트로의 {@code @ManyToOne} 을 걸지 않는다. 쓰기 경로가 조건부 UPDATE 라
 * 연관을 걸어도 쓰이지 않고, 걸면 상태를 한 줄 읽을 때마다 루트가 따라온다.
 */
@Entity
@Table(name = "scan_execution")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class ScanExecution {

    /** 🔴 저장소당 1행. 생성 전략이 없다 — 값은 저장소 식별자다 */
    @Id
    @Column(name = "repository_id")
    private Long repositoryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScanPhase phase;

    private Instant startedAt;

    private Instant finishedAt;

    /**
     * 🔴 <b>이 자리를 언제까지 붙들 수 있는가.</b> 지나면 다른 인스턴스가 뺏어 간다.
     *
     * <p>뺏김의 최악은 <b>중복 스캔 1회</b>이고 이슈 수집이 멱등(upsert)이라 되돌릴 수 있다.
     * 리스가 없을 때의 최악은 <b>영구 잠금</b>이고 되돌릴 수단이 없다.
     * 가르는 것은 보수성이 아니라 실패의 방향이 되돌릴 수 있는가다.
     */
    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    /**
     * 누가 잡았나 — 🔴 <b>진단용이다. 판정에 쓰지 않는다.</b>
     *
     * <p>판정에 쓰면 「내가 잡은 것만 내가 놓을 수 있다」가 되어, 죽은 인스턴스의 토큰이
     * 남은 행을 <b>아무도 놓지 못한다.</b> 그것이 리스가 막으려는 영구 잠금 그 자체다.
     */
    @Column(length = 64)
    private String ownerToken;

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private ScanStage failureStage;

    /** 🔴 예외 <b>클래스 이름</b>만. 메시지를 넣지 않는다 (S-4) */
    @Column(length = 255)
    private String failureType;

    private int issuesSaved;

    private int issuesJudged;

    private int candidatesAnalyzed;

    private int candidatesRejected;

    private int candidatesFailed;

    private int candidatesSkipped;

    private boolean hasMore;

    /** 🔴 실패가 아니라 <b>지연</b>이다 (레이트리밋) */
    private Instant delayedUntil;

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private ScanSkipReason skipReason;

    /**
     * 🔴 「한 번도 돌지 않았다」와 「돌았는데 집계가 0 이다」를 가른다.
     *
     * <p>이것이 없으면 수치가 전부 0 인 행을 보고 어느 쪽인지 알 수 없고, 진행 조회가
     * 「아직 안 돌았다」로 표시해 사람이 <b>스캔이 기동조차 안 된 줄</b> 안다.
     */
    private boolean hasResult;

    /** 등록 시점에 만든다 — 🔴 행이 <b>항상 존재</b>해야 조건부 UPDATE 가 성립한다. */
    public static ScanExecution idleFor(Long repositoryId) {
        if (repositoryId == null) {
            throw new IllegalArgumentException("저장소 식별자는 필수다");
        }
        ScanExecution execution = new ScanExecution();
        execution.repositoryId = repositoryId;
        execution.phase = ScanPhase.IDLE;
        return execution;
    }

    /**
     * 스레드를 잡았다 — {@code QUEUED} → {@code RUNNING}.
     *
     * <p>🔴 <b>리스를 여기서 한 번 더 민다.</b> 자리 잡기 시점에만 걸면 큐에서 기다린 시간이
     * 리스를 갉아먹어, 정작 도는 동안 리스가 만료돼 <b>돌고 있는 스캔을 남이 뺏는다.</b>
     * 이것은 heartbeat 가 아니다 — 스레드를 더 만들지 않고 <b>자연스러운 전이 한 번</b>에
     * 얹는다.
     */
    public void markRunning(Instant now, Instant leaseUntil) {
        this.phase = ScanPhase.RUNNING;
        this.leaseExpiresAt = leaseUntil;
        if (this.startedAt == null) {
            this.startedAt = now;
        }
    }

    /**
     * 자리를 되돌린다 — 잡았으나 실행에 들어가지 못했다.
     *
     * <p>🔴 <b>직전 집계를 지우지 않는다.</b> 제출이 거절된 것은 이번 시도의 일이고,
     * 지난번에 무엇을 했는지는 여전히 사람이 봐야 할 정보다.
     */
    public void release() {
        this.phase = ScanPhase.IDLE;
        this.startedAt = null;
        this.finishedAt = null;
        this.leaseExpiresAt = null;
        this.ownerToken = null;
    }

    /** 끝났다 — 🔴 리스를 반드시 비운다. 남기면 IDLE 이 아닌데도 만료를 기다리게 된다. */
    public void finish(ScanPhase terminal, Instant now, ScanOutcome outcome,
            ScanStage failureStage, String failureType) {
        if (terminal == null || terminal.isActive()) {
            throw new IllegalArgumentException("마감 국면이 아니다: " + terminal);
        }
        this.phase = terminal;
        this.finishedAt = now;
        this.leaseExpiresAt = null;
        this.ownerToken = null;
        this.failureStage = failureStage;
        this.failureType = failureType;
        applyOutcome(outcome);
    }

    /**
     * 집계가 없으면 <b>직전 집계를 남긴다</b> — 0 으로 덮지 않는다.
     *
     * <p>실패로 끝난 실행은 집계가 없을 수 있다. 그때 0 으로 덮으면 지난번 성과까지
     * 사라져 「이 저장소는 아무것도 수집한 적이 없다」로 보인다.
     */
    private void applyOutcome(ScanOutcome outcome) {
        if (outcome == null) {
            return;
        }
        this.issuesSaved = outcome.issuesSaved();
        this.issuesJudged = outcome.issuesJudged();
        this.candidatesAnalyzed = outcome.candidatesAnalyzed();
        this.candidatesRejected = outcome.candidatesRejected();
        this.candidatesFailed = outcome.candidatesFailed();
        this.candidatesSkipped = outcome.candidatesSkipped();
        this.hasMore = outcome.hasMore();
        this.delayedUntil = outcome.delayedUntil();
        this.skipReason = outcome.skipReason();
        this.hasResult = true;
    }

    /** 직전 집계. 한 번도 안 돌았으면 {@code null} — {@link #hasResult} 가 그것을 가른다. */
    public ScanOutcome outcome() {
        if (!hasResult) {
            return null;
        }
        return new ScanOutcome(issuesSaved, issuesJudged, candidatesAnalyzed, candidatesRejected,
                candidatesFailed, candidatesSkipped, hasMore, delayedUntil, skipReason);
    }
}
