package com.ossagent.repository.adapter.out.persistence;

import com.ossagent.repository.domain.ScanExecution;
import com.ossagent.repository.domain.ScanPhase;
import java.time.Instant;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 스캔 실행 상태의 영속 어댑터 — 🔴 <b>자리 잡기의 원자성이 여기 있다</b> (#26 FR-3).
 *
 * <h2>왜 조건부 UPDATE 한 방인가</h2>
 *
 * <p>「읽고 → 판단하고 → 쓴다」로 짜면 두 인스턴스가 그 사이를 통과해 <b>둘 다 스캔을
 * 시작한다.</b> {@code InMemoryScanExecutionRegistry} 가 {@code containsKey} 후 {@code put}
 * 에 대해 경고한 것과 <b>같은 실수의 DB 판</b>이다. 그래서 검사와 기록을 SQL 한 문장으로
 * 합치고 {@code affectedRows} 로 판정한다.
 *
 * <p>🔴 <b>{@code FOR UPDATE SKIP LOCKED} 를 쓰지 않는다.</b> H2 지원이 갈리고, 조건부
 * UPDATE 가 같은 것을 벤더 중립으로 준다 — Q-2b-1.
 *
 * <p>🔴 <b>upsert 를 쓰지 않는다.</b> {@code ON CONFLICT}(PostgreSQL)·{@code MERGE}(H2)가
 * 벤더 고유 문법이다. 대신 행이 <b>항상 존재하게</b> 만든다 — V10 이 기존 저장소를
 * 백필하고, 신규 등록은 저장소와 같은 트랜잭션에서 만든다.
 */
public interface ScanExecutionJpaRepository extends JpaRepository<ScanExecution, Long> {

    /**
     * 자리를 잡는다 — 🔴 <b>검사와 기록이 한 문장이다.</b>
     *
     * <h2>활성 판정이 국면만 보지 않는 이유</h2>
     *
     * <p>인스턴스가 {@code kill -9} 되면 {@code RUNNING} 행이 남는다. 국면만 보면 그
     * 저장소는 <b>영원히 409</b> 이고 재기동해도 풀리지 않는다 — 방어가 스스로를 잠근다.
     * 그래서 <b>리스가 지났으면 뺏는다.</b>
     *
     * <p>⚠️ {@code leaseExpiresAt IS NULL} 도 뺏을 수 있는 쪽에 넣는다. 우리 코드가
     * 자리를 잡을 때는 반드시 리스를 함께 쓰므로 그런 행은 생기지 않지만, <b>생겼다면</b>
     * 그것은 아무도 놓아줄 수 없는 행이다. 모르는 상태에서 되돌릴 수 없는 쪽을 피한다.
     *
     * <p>⚠️ {@code ownerToken} 은 <b>판정에 쓰지 않는다.</b> 「내가 잡은 것만 내가 놓을 수
     * 있다」로 만들면 죽은 인스턴스의 행을 아무도 놓지 못한다.
     *
     * @return 1 이면 내가 잡았다. 0 이면 남이 잡고 있거나 <b>행이 없다</b> —
     *         둘을 호출자가 {@link #existsById} 로 가른다
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ScanExecution e
               SET e.phase = :queued,
                   e.startedAt = :now,
                   e.finishedAt = NULL,
                   e.leaseExpiresAt = :leaseUntil,
                   e.ownerToken = :ownerToken,
                   e.failureStage = NULL,
                   e.failureType = NULL
             WHERE e.repositoryId = :repositoryId
               AND (e.phase NOT IN :activePhases
                    OR e.leaseExpiresAt IS NULL
                    OR e.leaseExpiresAt <= :now)
            """)
    int acquire(@Param("repositoryId") Long repositoryId,
            @Param("queued") ScanPhase queued,
            @Param("activePhases") Collection<ScanPhase> activePhases,
            @Param("now") Instant now,
            @Param("leaseUntil") Instant leaseUntil,
            @Param("ownerToken") String ownerToken);
}
