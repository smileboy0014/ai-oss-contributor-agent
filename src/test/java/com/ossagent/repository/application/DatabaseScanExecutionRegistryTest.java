package com.ossagent.repository.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ossagent.repository.adapter.out.persistence.OssRepositoryRepository;
import com.ossagent.repository.adapter.out.persistence.ScanExecutionJpaRepository;
import com.ossagent.repository.domain.OssRepository;
import com.ossagent.repository.domain.ScanPhase;
import com.ossagent.repository.domain.ScanSkipReason;
import com.ossagent.repository.domain.ScanStage;
import com.ossagent.support.testing.AgentIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 🔴 <b>중복 스캔 차단이 프로세스 경계를 넘는가</b> — #26 FR-3·FR-4.
 *
 * <h2>왜 실 DB 인가</h2>
 *
 * <p>능력 대역({@code FakeScanExecutionRegistry})으로는 <b>증명되지 않는다.</b> 거기 원자성은
 * {@code ConcurrentHashMap.compute} 가 주는 것이고, 운영이 막아야 하는 것은
 * <b>다른 JVM 의 경쟁</b>이다. 그 경쟁을 막는 것은 조건부 UPDATE 의 {@code affectedRows} 이고
 * 그것은 실 DB 가 아니면 돌지 않는다 — {@code testing-philosophy.md} 「트랜잭션 경계·동시성은
 * 실 DB 가 아니면 검증되지 않는다」.
 *
 * <p>PostgreSQL 을 쓴다. H2 는 PostgreSQL 모드 흉내일 뿐이고, 여기서 보는 것이 바로
 * <b>동시 UPDATE 의 잠금 동작</b>이라 흉내로는 부족하다.
 */
@AgentIntegrationTest
@Testcontainers
class DatabaseScanExecutionRegistryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private DatabaseScanExecutionRegistry registry;

    @Autowired
    private OssRepositoryRepository repositories;

    @Autowired
    private ScanExecutionJpaRepository executions;

    @Autowired
    private DataSource dataSource;

    private Long repositoryId;

    @BeforeEach
    void setUp() {
        String name = "spring-kafka-" + System.nanoTime();
        OssRepository saved = repositories.save(new OssRepository(
                "spring-projects", name, "https://github.com/spring-projects/" + name));
        repositoryId = saved.getId();
    }

    // ── 0b. 내가 잰 것이 게이트가 도는 그것인가 ─────────────────────────

    @Test
    @DisplayName("🔴 이 테스트가 실제로 PostgreSQL 에서 돈다 — H2 로 조용히 떨어져도 초록이 된다")
    void 실제로_PostgreSQL_에서_돈다() throws Exception {
        String product;
        try (java.sql.Connection connection = dataSource.getConnection()) {
            product = connection.getMetaData().getDatabaseProductName();
        }
        assertThat(product)
                .as("🔴 @ServiceConnection 이 안 걸리면 H2 로 떨어지고, 그러면 여기서 재는 "
                        + "「동시 UPDATE 의 잠금 동작」이 흉내가 된다 — 초록의 의미가 달라진다")
                .isEqualTo("PostgreSQL");
    }

    // ── 행의 존재 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 저장소를 만들면 실행 행이 함께 생긴다 — 조건부 UPDATE 의 전제다")
    void 저장소를_만들면_실행_행이_함께_생긴다() {
        assertThat(executions.findById(repositoryId))
                .as("🔴 행이 없으면 자리 잡기가 영영 0행이고 그 저장소는 스캔되지 않는다")
                .isPresent()
                .get()
                .extracting(execution -> execution.getPhase())
                .isEqualTo(ScanPhase.IDLE);
    }

    @Test
    @DisplayName("🔴 행이 없으면 「잠겨 있다」가 아니라 예외다 — 등록 버그가 영구 409 로 위장되지 않게")
    void 행이_없으면_예외다() {
        executions.deleteById(repositoryId);
        executions.flush();

        assertThatThrownBy(() -> registry.tryStart(repositoryId))
                .as("🔴 false 로 번역하면 아무리 기다려도 안 풀리는 상태가 「누가 스캔 중」으로 보인다")
                .isInstanceOf(ScanExecutionNotRegisteredException.class);
    }

    // ── FR-3 동시성 ────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 동시에 N개가 달려들어도 정확히 하나만 자리를 잡는다 — FR-3")
    void 동시_요청_중_하나만_자리를_잡는다() throws Exception {
        int threads = 8;
        // ⚠ 커넥션 풀이 스레드보다 작으면 직렬화되어 「초록이 공허해진다」.
        //   풀 크기를 먼저 단언한다 — 요구 3(샘플의 대표성)
        assertThat(maxPoolSize())
                .as("🔴 풀이 스레드보다 작으면 이 테스트는 동시성을 재지 못한다")
                .isGreaterThanOrEqualTo(threads);

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger entered = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Boolean>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> {
                    entered.incrementAndGet();
                    start.await(5, TimeUnit.SECONDS);
                    return registry.tryStart(repositoryId);
                });
            }
            List<Future<Boolean>> futures = new java.util.ArrayList<>();
            for (Callable<Boolean> task : tasks) {
                futures.add(pool.submit(task));
            }
            start.countDown();

            int acquired = 0;
            for (Future<Boolean> future : futures) {
                if (Boolean.TRUE.equals(future.get(30, TimeUnit.SECONDS))) {
                    acquired++;
                }
            }

            assertThat(entered.get())
                    .as("🔴 스레드가 실제로 다 진입했는가 — 아니면 동시성을 재지 않은 것이다")
                    .isEqualTo(threads);
            assertThat(acquired)
                    .as("🔴 둘 이상이 잡으면 같은 저장소를 동시에 스캔한다 — "
                            + "SELECT 후 UPDATE 로 짜면 여기가 빨개진다")
                    .isEqualTo(1);
        } finally {
            // 🔴 finally 에서 거둔다 — 마지막 줄의 정리는 거기 도달했을 때만 돈다 (#19)
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("이미 잡혀 있으면 두 번째는 false 다")
    void 이미_잡혀_있으면_거절한다() {
        assertThat(registry.tryStart(repositoryId)).isTrue();
        assertThat(registry.tryStart(repositoryId)).isFalse();
    }

    @Test
    @DisplayName("release 하면 다시 잡을 수 있다 — 자리를 남기면 리스가 만료될 때까지 막힌다")
    void release_하면_다시_잡을_수_있다() {
        assertThat(registry.tryStart(repositoryId)).isTrue();

        registry.release(repositoryId);

        assertThat(registry.tryStart(repositoryId)).isTrue();
    }

    // ── FR-4 리스 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 리스가 지난 RUNNING 행은 뺏을 수 있다 — 없으면 kill -9 한 번에 저장소가 영구히 잠긴다")
    void 리스가_만료되면_뺏는다() {
        assertThat(registry.tryStart(repositoryId)).isTrue();
        registry.markRunning(repositoryId);
        expireLease();

        assertThat(registry.tryStart(repositoryId))
                .as("🔴 방어가 스스로를 잠그는 구조를 만들지 않는다 — 되돌릴 수 없는 쪽을 피한다")
                .isTrue();
    }

    @Test
    @DisplayName("🔴 리스가 안 지났으면 못 뺏는다 — 양방향이라야 리스가 방어다")
    void 리스가_살아_있으면_못_뺏는다() {
        assertThat(registry.tryStart(repositoryId)).isTrue();
        registry.markRunning(repositoryId);

        assertThat(registry.tryStart(repositoryId))
                .as("🔴 이쪽이 빠지면 「언제나 뺏을 수 있다」와 구분되지 않아 FR-3 이 사라진다")
                .isFalse();
    }

    @Test
    @DisplayName("markRunning 이 리스를 다시 민다 — 큐 대기가 리스를 갉아먹지 않게")
    void markRunning_이_리스를_민다() {
        registry.tryStart(repositoryId);
        Instant afterAcquire = leaseExpiresAt();
        // 자리 잡기 직후의 리스를 절반쯤 흘려보낸 상태를 만든다
        shiftLease(Duration.ofMinutes(-30));

        registry.markRunning(repositoryId);

        assertThat(leaseExpiresAt())
                .as("전이 시점 기준으로 다시 걸려야 도는 동안 만료되지 않는다")
                .isAfterOrEqualTo(afterAcquire.minusSeconds(1));
    }

    // ── 마감 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("성공 마감은 집계를 남기고 리스를 비운다")
    void 성공_마감은_집계를_남긴다() {
        registry.tryStart(repositoryId);
        registry.markRunning(repositoryId);

        registry.markSucceeded(repositoryId,
                new ScanPipelineResult(3, 3, 1, 0, 0, 0, false, null, null));

        ScanExecutionState state = registry.stateOf(repositoryId).orElseThrow();
        assertThat(state.phase()).isEqualTo(ScanPhase.SUCCEEDED);
        assertThat(state.lastResult().issuesSaved()).isEqualTo(3);
        assertThat(state.isActive()).isFalse();
        assertThat(leaseExpiresAt())
                .as("🔴 마감했는데 리스가 남으면 IDLE 이 아닌데도 만료를 기다리게 된다")
                .isNull();
    }

    @Test
    @DisplayName("🔴 건너뜀은 실패가 아니다 — 사유가 남는다")
    void 건너뜀은_사유를_남긴다() {
        registry.tryStart(repositoryId);

        registry.markSkipped(repositoryId,
                ScanPipelineResult.skipped(ScanSkipReason.CONTRIBUTION_FORBIDDEN));

        ScanExecutionState state = registry.stateOf(repositoryId).orElseThrow();
        assertThat(state.phase()).isEqualTo(ScanPhase.SKIPPED);
        assertThat(state.lastResult().skipReason())
                .isEqualTo(ScanSkipReason.CONTRIBUTION_FORBIDDEN);
    }

    @Test
    @DisplayName("🔴 실패는 단계와 예외 타입만 남긴다 — 메시지는 S-4 로 막혀 있다")
    void 실패는_타입만_남긴다() {
        registry.tryStart(repositoryId);

        registry.markFailed(repositoryId, ScanStage.SCAN, "GitHubApiException", null);

        ScanExecutionState state = registry.stateOf(repositoryId).orElseThrow();
        assertThat(state.phase()).isEqualTo(ScanPhase.FAILED);
        assertThat(state.failureStage()).isEqualTo(ScanStage.SCAN);
        assertThat(state.failureType()).isEqualTo("GitHubApiException");
    }

    @Test
    @DisplayName("집계 없는 실패가 직전 집계를 0 으로 덮지 않는다")
    void 집계_없는_실패가_직전_집계를_지우지_않는다() {
        registry.tryStart(repositoryId);
        registry.markSucceeded(repositoryId,
                new ScanPipelineResult(7, 7, 2, 0, 0, 0, false, null, null));

        registry.tryStart(repositoryId);
        registry.markFailed(repositoryId, ScanStage.ANALYZE, "LlmException", null);

        assertThat(registry.stateOf(repositoryId).orElseThrow().lastResult().issuesSaved())
                .as("0 으로 덮으면 「이 저장소는 아무것도 수집한 적이 없다」로 보인다")
                .isEqualTo(7);
    }

    @Test
    @DisplayName("한 번도 안 돌았으면 집계가 없다 — 「돌았는데 0」과 다르다")
    void 한_번도_안_돌았으면_집계가_없다() {
        assertThat(registry.stateOf(repositoryId).orElseThrow().lastResult()).isNull();
    }

    // ── 도우미 ─────────────────────────────────────────────────────────

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private int maxPoolSize() {
        if (dataSource instanceof com.zaxxer.hikari.HikariDataSource hikari) {
            return hikari.getMaximumPoolSize();
        }
        throw new IllegalStateException("풀 크기를 확인할 수 없다: " + dataSource.getClass());
    }

    private Instant leaseExpiresAt() {
        java.sql.Timestamp value = jdbc().queryForObject(
                "SELECT lease_expires_at FROM scan_execution WHERE repository_id = ?",
                java.sql.Timestamp.class, repositoryId);
        return value == null ? null : value.toInstant();
    }

    private void expireLease() {
        jdbc().update("UPDATE scan_execution SET lease_expires_at = ? WHERE repository_id = ?",
                java.sql.Timestamp.from(Instant.now(Clock.systemUTC()).minus(Duration.ofDays(1))),
                repositoryId);
    }

    private void shiftLease(Duration delta) {
        jdbc().update("UPDATE scan_execution SET lease_expires_at = ? WHERE repository_id = ?",
                java.sql.Timestamp.from(leaseExpiresAt().plus(delta)), repositoryId);
    }
}
