package com.ossagent.candidate.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ossagent.support.testing.AgentIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 승인 게이트 ①의 HTTP 표면 — S-6.
 *
 * <p>UseCase 단언({@code SelectCandidateUseCaseTest})과 겹치지 않는 것만 본다:
 * <b>상태 코드</b>와 <b>열려 있지 않은 문</b>이다. 업무 규칙은 그쪽이 본다.
 *
 * <p>픽스처: 101·102 = {@code ANALYZED} · 103 = {@code SELECTED} · 104 = {@code DISCOVERED}.
 *
 * <p>🔴 #18 분은 <b>별도 파일</b>이다({@code candidate-implement-fixtures.sql}) — 공유 픽스처에
 * 후보를 더했더니 {@code CandidateQueryIntegrationTest} 의 개수·페이지 경계 단언이 깨졌다.
 * 조회 테스트는 「전체가 몇 건인가」를, 이쪽은 「게이트가 막는가」를 본다.
 */
@AgentIntegrationTest
@AutoConfigureMockMvc
@Sql({"/sql/candidate-fixtures.sql", "/sql/candidate-implement-fixtures.sql"})
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class CandidateApprovalApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 선정은_200_과_전이의_양끝을_돌려준다_S6() throws Exception {
        mockMvc.perform(post("/api/candidates/101/select"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidateId").value(101))
                .andExpect(jsonPath("$.from").value("ANALYZED"))
                .andExpect(jsonPath("$.to").value("SELECTED"))
                .andExpect(jsonPath("$.terminal").value(false));
    }

    @Test
    void 두_번째_선정은_409_다_S6() throws Exception {
        // 🔴 200 으로 뭉개면 「사람이 한 번 골랐다」를 사후에 셀 수 없다
        mockMvc.perform(post("/api/candidates/103/select"))
                .andExpect(status().isConflict());
    }

    @Test
    void 선정하지_않은_후보의_취소는_409_다_Q5() throws Exception {
        mockMvc.perform(post("/api/candidates/102/reject"))
                .andExpect(status().isConflict());
    }

    @Test
    void 취소는_200_이고_종단임을_알려준다_Q5() throws Exception {
        mockMvc.perform(post("/api/candidates/103/reject"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.to").value("REJECTED"))
                .andExpect(jsonPath("$.terminal")
                        .value(true));
    }

    @Test
    void 없는_후보는_404_다() throws Exception {
        mockMvc.perform(post("/api/candidates/999999/select"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PR 생성 엔드포인트는 아직 존재하지 않는다 — S-2 · S-6")
    void PR생성_엔드포인트는_없다_S6() throws Exception {
        // 🔴 「아직 안 만들었다」를 테스트로 고정한다. 지금 열면 **PR 없이 종단
        //    PR_CREATED** 가 만들어지고 S-2 까지 닿는다. 실행기와 함께 연다 — #23.
        //
        //    ⚠ 착수(implement)는 #18 이 **실행기와 함께** 열었으므로 여기서 빠졌다.
        //    테스트를 지운 것이 아니라 **대상이 하나 줄어든 것**이다 —
        //    지우면 pull-request 까지 함께 열려도 아무것도 빨개지지 않는다
        mockMvc.perform(post("/api/candidates/103/pull-request"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("착수는 열려 있고 전이의 양끝을 돌려준다 — S-6 두 번째 게이트")
    void 착수는_200_과_전이의_양끝을_돌려준다_S6() throws Exception {
        // 🔴 #24 가 이 문을 열지 않은 이유는 「IMPLEMENTING 에서 나갈 트리거가 없다」였다.
        //    #18 이 실행기와 함께 열었고, 검증기가 아직 배선되지 않았더라도 후보는
        //    FAILED 로 떨어진다 — 갇히지 않는다
        mockMvc.perform(post("/api/candidates/103/implement"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidateId").value(103))
                .andExpect(jsonPath("$.from").value("SELECTED"))
                .andExpect(jsonPath("$.to").value("IMPLEMENTING"));
    }

    @Test
    @DisplayName("고르지 않은 후보는 착수할 수 없다 — S-6")
    void 고르지_않은_후보는_착수할_수_없다_S6() throws Exception {
        // 🔴 사람이 고른 적 없는 후보가 구현 단계로 가면 첫 번째 게이트가 무의미해진다.
        //    102 는 ANALYZED 라 selectedAt 이 비어 있다
        mockMvc.perform(post("/api/candidates/102/implement"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("보류 저장소의 후보는 착수할 수 없다 — 403 · S-5")
    void 보류_저장소의_후보는_착수할_수_없다_S5() throws Exception {
        // 🔴 105 는 **사람이 골랐다**(SELECTED). 그래도 막혀야 한다 —
        //    「사람이 골랐으니 통과」가 되면 S-5 가 S-6 에 먹힌다.
        //
        //    보류는 NULL 이고 「허용」이 아니다. 시간·재시도로 풀리지 않고
        //    POST /api/repositories/{id}/policy/resolution 으로 사람이 해소한다 (Q-8)
        mockMvc.perform(post("/api/candidates/105/implement"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AI 기여를 금지한 저장소의 후보는 착수할 수 없다 — 403 · S-5")
    void 금지_저장소의_후보는_착수할_수_없다_S5() throws Exception {
        mockMvc.perform(post("/api/candidates/106/implement"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 취소는_DELETE_로_열려_있지_않다_Q5() throws Exception {
        // 지우는 것이 아니라 종단으로 전이시키는 행위다. DELETE 로 열면
        // 「선정을 되돌린다 → ANALYZED 복귀」로 읽히는데 실제로는 되돌릴 수 없다
        mockMvc.perform(delete("/api/candidates/103/select"))
                .andExpect(status().isMethodNotAllowed());
    }
}
