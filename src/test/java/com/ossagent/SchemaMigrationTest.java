package com.ossagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.ossagent.support.testing.AgentIntegrationTest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 마이그레이션을 <b>실제 PostgreSQL</b> 에서 검증한다.
 *
 * <p>기존 {@code contextLoads} 는 H2 에서 돈다. H2 는 PostgreSQL 모드 흉내일 뿐이라
 * 「H2 에서 됐다」가 거짓 신호가 될 수 있다 — Q-2b. 이 테스트가 그 간극을 메운다.
 *
 * <p>컨텍스트가 뜨는 것 자체가 두 가지를 증명한다 — Flyway 가 V1·V2 를 적용했고,
 * Hibernate {@code ddl-auto: validate} 가 엔티티와 스키마의 정합을 통과했다는 것.
 * 그 위에 멱등키·안전 경계 제약이 실제로 걸렸는지를 직접 확인한다.
 *
 * <p>⚠ <b>Docker 가 없으면 이 테스트는 실패한다. 건너뛰지 않는다.</b>
 * 조건부 skip 을 걸면 「빌드 SUCCESS + 실제로는 0건 실행」이라는 거짓 신호가 나온다
 * (실제로 한 번 겪었다 — 5건 전부 조용히 skip 됐다). 검증하지 않은 것을 통과라고
 * 말하지 않는 것이 이 저장소의 규율이다 — {@code .claude/rules/conventions/testing-philosophy.md}.
 *
 * <p>Docker 는 이 프로젝트의 전제다. 로컬 DB(`docker compose`)도, 샌드박스(S-3)도 Docker 를 쓴다.
 *
 * <p><b>실 DB 를 쓰지만 진입점은 다른 테스트와 같다</b>({@code @AgentIntegrationTest}) — #43.
 * #4 초기에는 그 애노테이션이 페이크 조립을 {@code @Import} 하는 구조여서 「실 의존 검증에
 * 페이크를 묶지 말자」는 이유로 raw {@code @SpringBootTest} 를 썼다. 지금은 프로필만 켜므로
 * 묶일 것이 없고, 오히려 raw 사용이 <b>실제 GitHub 어댑터를 이 컨텍스트에 올리고</b> 있었다.
 * 스키마 검증에 그것이 필요할 이유가 없다.
 *
 * <p>DB 는 차단 대상이 아니므로 Testcontainers 는 그대로다 — 막는 것은 <b>샌드박스</b>
 * 컨테이너이지 인프라 컨테이너가 아니다.
 */
@AgentIntegrationTest
@Testcontainers
class SchemaMigrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private DataSource dataSource;

    @Test
    void 마이그레이션이_ERD_7테이블을_만든다() throws Exception {
        assertThat(tableNames()).contains(
                "oss_repository",
                "repository_policy",
                "issue",
                "contribution_candidate",
                "agent_run",
                "generated_change",
                "pull_request");
    }

    @Test
    void 멱등키_UNIQUE_제약이_걸려_있다() throws Exception {
        // 없으면 재실행이 중복을 만든다 — 이슈 중복 적재 · 후보 이중 실행 · 대상 저장소에 중복 PR
        assertThat(constraintNames("UNIQUE")).contains(
                "uk_issue_repository_number",
                "uk_contribution_candidate_issue",
                "uk_pull_request_candidate",
                "uk_pull_request_fork_branch",
                "uk_oss_repository_url");
    }

    @Test
    void 판정_실패를_허용으로_읽지_않도록_ai_contribution_allowed_는_NULL_을_허용한다_S5() throws Exception {
        // NOT NULL DEFAULT TRUE 로 두면 「AI 기여 금지」 저장소를 기본 허용해 버린다.
        // 기본값이 곧 S-5 위반이 되는 자리다
        assertThat(isNullable("repository_policy", "ai_contribution_allowed")).isTrue();
    }

    @Test
    void 사람이_고른_시각이_비어_있을_수_있어야_한다_S6() throws Exception {
        // selected_at NULL = 아직 사람이 고르지 않음 = 구현 단계로 갈 수 없음.
        // NOT NULL 이면 「사람이 골랐다」를 표현할 수 없게 된다
        assertThat(isNullable("contribution_candidate", "selected_at")).isTrue();
    }

    @Test
    void PR_의_Fork_URL_은_비어_있을_수_없다_S1() throws Exception {
        // 쓰기 대상이 Fork 임을 데이터로 고정한다
        assertThat(isNullable("pull_request", "fork_url")).isFalse();
    }

    private List<String> tableNames() throws Exception {
        try (Connection c = dataSource.getConnection();
             ResultSet rs = c.getMetaData().getTables(null, "public", "%", new String[]{"TABLE"})) {
            List<String> names = new ArrayList<>();
            while (rs.next()) {
                names.add(rs.getString("TABLE_NAME"));
            }
            return names;
        }
    }

    private List<String> constraintNames(String type) throws Exception {
        try (Connection c = dataSource.getConnection();
             var ps = c.prepareStatement("""
                     SELECT constraint_name
                     FROM information_schema.table_constraints
                     WHERE table_schema = 'public' AND constraint_type = ?
                     """)) {
            ps.setString(1, type);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> names = new ArrayList<>();
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
                return names;
            }
        }
    }

    private boolean isNullable(String table, String column) throws Exception {
        try (Connection c = dataSource.getConnection();
             var ps = c.prepareStatement("""
                     SELECT is_nullable
                     FROM information_schema.columns
                     WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                     """)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("%s.%s 컬럼이 존재해야 한다", table, column).isTrue();
                return "YES".equals(rs.getString(1));
            }
        }
    }
}
