package com.ossagent.candidate.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ossagent.candidate.application.CandidateQuery;
import com.ossagent.candidate.application.CandidateSummaryView;
import com.ossagent.candidate.application.FindCandidatesUseCase;
import com.ossagent.candidate.domain.CandidateStatus;
import com.ossagent.support.testing.AgentIntegrationTest;
import com.ossagent.support.testing.CapturingStatementInspector;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.domain.Page;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 조회 API 통합 — 실제 DB(H2 + Flyway)와 실제 MVC 로 본다.
 *
 * <p>대외 의존(GitHub·LLM·샌드박스)은 {@code fakes} 프로파일이 차단한다 —
 * {@code @AgentIntegrationTest}. 이 API 는 애초에 대외 호출이 없다.
 *
 * <p>픽스처를 SQL 로 넣는 이유는 {@code candidate-fixtures.sql} 머리말 참조.
 */
@AgentIntegrationTest
@AutoConfigureMockMvc
@Sql("/sql/candidate-fixtures.sql")
@TestPropertySource(properties =
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "com.ossagent.support.testing.CapturingStatementInspector")
class CandidateQueryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FindCandidatesUseCase findCandidates;

    @BeforeEach
    void clearStatements() {
        CapturingStatementInspector.clear();
    }

    // ── 목록 필터 ────────────────────────────────────────────────────

    @Test
    @DisplayName("조건 없으면 전체를 id 내림차순으로 준다")
    void 조건이_없으면_전체다() {
        Page<CandidateSummaryView> page =
                findCandidates.findAll(CandidateQuery.all(), 0, CandidateQuery.DEFAULT_SIZE);

        assertThat(page.getTotalElements()).isEqualTo(4);
        assertThat(page.getContent()).extracting(CandidateSummaryView::id)
                .as("정렬은 계약이다 — ORDER BY id DESC 고정")
                .containsExactly(104L, 103L, 102L, 101L);
    }

    @Test
    @DisplayName("confidence 가 NULL 인 후보도 목록에 나온다 — primitive 였다면 500 이다")
    void confidence_가_NULL_이어도_터지지_않는다() {
        Page<CandidateSummaryView> page =
                findCandidates.findAll(CandidateQuery.all(), 0, CandidateQuery.DEFAULT_SIZE);

        assertThat(page.getContent())
                .filteredOn(v -> v.id() == 104L)
                .singleElement()
                .satisfies(v -> assertThat(v.confidence())
                        .as("discover() 가 채우지 않으므로 DISCOVERED·ANALYZING 후보는 전부 NULL 이다")
                        .isNull());
    }

    @Test
    @DisplayName("상태·난이도·최소 신뢰도로 거른다")
    void 필터가_각각_동작한다() {
        assertThat(ids(new CandidateQuery(CandidateStatus.ANALYZED, null, null)))
                .containsExactly(102L, 101L);

        assertThat(ids(new CandidateQuery(null, "EASY", null)))
                .containsExactly(103L, 101L);

        assertThat(ids(new CandidateQuery(null, null, new BigDecimal("0.75"))))
                .as("이상(≥)이다 — 0.75 가 포함돼야 한다")
                .containsExactly(103L, 101L);
    }

    @Test
    @DisplayName("필터를 조합하면 교집합이다")
    void 필터를_조합한다() {
        assertThat(ids(new CandidateQuery(CandidateStatus.ANALYZED, "EASY", new BigDecimal("0.80"))))
                .containsExactly(101L);

        assertThat(ids(new CandidateQuery(CandidateStatus.SELECTED, "MEDIUM", null)))
                .as("교집합이 비면 빈 목록이다 — 404 가 아니다")
                .isEmpty();
    }

    @Test
    @DisplayName("없는 난이도는 400 이 아니라 빈 목록이다 — DB 에 CHECK 가 없다")
    void 없는_난이도는_빈_목록이다() {
        assertThat(ids(new CandidateQuery(null, "존재하지않는난이도", null))).isEmpty();
    }

    @Test
    @DisplayName("페이지 경계가 맞는다")
    void 페이지네이션이_동작한다() {
        Page<CandidateSummaryView> first = findCandidates.findAll(CandidateQuery.all(), 0, 3);
        Page<CandidateSummaryView> second = findCandidates.findAll(CandidateQuery.all(), 1, 3);

        assertThat(first.getContent()).extracting(CandidateSummaryView::id)
                .containsExactly(104L, 103L, 102L);
        assertThat(second.getContent()).extracting(CandidateSummaryView::id)
                .containsExactly(101L);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.getTotalElements()).isEqualTo(4);
    }

    // ── NFR-1 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("목록 SQL 이 analysis 컬럼을 읽지 않는다")
    void 목록은_TEXT_컬럼을_읽지_않는다() {
        findCandidates.findAll(CandidateQuery.all(), 0, CandidateQuery.DEFAULT_SIZE);

        List<String> selects = CapturingStatementInspector.selects();

        assertThat(selects)
                .as("SQL 을 한 건도 못 잡았으면 inspector 배선이 깨진 것이다 — 조용히 통과시키지 않는다")
                .isNotEmpty();
        assertThat(selects)
                .as("""
                        목록이 analysis 를 읽고 있다. TEXT 는 행마다 수십 KB 이고 20건이면 20배다.
                        프로젝션(constructor expression)이 선택한 컬럼만 SELECT 해야 한다.""")
                .noneSatisfy(sql -> assertThat(sql.toLowerCase()).contains("analysis"));
    }

    // ── HTTP ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/candidates — 200 · 페이지 메타를 준다")
    void 목록_엔드포인트가_동작한다() throws Exception {
        mockMvc.perform(get("/api/candidates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(4))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.items[0].id").value(104));
    }

    @Test
    @DisplayName("GET /api/candidates/{id} — 상세에 diff 본문이 없다")
    void 상세_응답에_diff_본문이_없다_S4() throws Exception {
        mockMvc.perform(get("/api/candidates/101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(101))
                .andExpect(jsonPath("$.runs.length()").value(2))
                .andExpect(jsonPath("$.totalChanges").value(2))
                .andExpect(jsonPath("$.latestChange.commitSha").value("bbb222"))
                .andExpect(jsonPath("$.latestChange.diffSize").value(greaterThan(0)))
                .andExpect(jsonPath("$.latestChange.hasTestResult").value(true))
                .andExpect(jsonPath("$.latestChange.hasReviewResult").value(false))
                // 🔴 본문이 응답에 존재하지 않는다 — 크기·해시·존재 여부만
                .andExpect(jsonPath("$.latestChange.diff").doesNotExist())
                .andExpect(jsonPath("$.latestChange.testResult").doesNotExist())
                .andExpect(jsonPath("$.latestChange.reviewResult").doesNotExist());
    }

    @Test
    @DisplayName("상세는 최신 변경분 1건만 요약한다")
    void 상세는_최신_변경분만_요약한다() throws Exception {
        mockMvc.perform(get("/api/candidates/101"))
                .andExpect(jsonPath("$.latestChange.branchName").value("oss-agent/issue-1-b"))
                .andExpect(jsonPath("$.totalChanges").value(2));
    }

    @Test
    @DisplayName("상세에 Draft PR 메타데이터가 실린다 — status 는 DRAFT 하나뿐이다")
    void 상세에_PR_메타데이터가_실린다_S2() throws Exception {
        mockMvc.perform(get("/api/candidates/101"))
                .andExpect(jsonPath("$.pullRequest.githubPrNumber").value(7))
                .andExpect(jsonPath("$.pullRequest.branchName").value("oss-agent/issue-1-b"))
                .andExpect(jsonPath("$.pullRequest.status")
                        .value("DRAFT"));
    }

    @Test
    @DisplayName("목록 응답에는 analysis 조차 없다")
    void 목록_응답에_외부텍스트가_없다_S4() throws Exception {
        mockMvc.perform(get("/api/candidates"))
                .andExpect(jsonPath("$.items[0].analysis").doesNotExist());
    }

    @Test
    @DisplayName("없는 후보는 404")
    void 없는_후보는_404_다() throws Exception {
        mockMvc.perform(get("/api/candidates/99999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("size 상한을 넘으면 400")
    void size_상한을_넘으면_400_이다() throws Exception {
        mockMvc.perform(get("/api/candidates").param("size", "101"))
                .andExpect(status().isBadRequest());
    }

    private List<Long> ids(CandidateQuery query) {
        return findCandidates.findAll(query, 0, CandidateQuery.DEFAULT_SIZE)
                .getContent().stream().map(CandidateSummaryView::id).toList();
    }
}
