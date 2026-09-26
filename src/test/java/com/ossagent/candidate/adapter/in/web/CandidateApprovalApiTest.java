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
 */
@AgentIntegrationTest
@AutoConfigureMockMvc
@Sql("/sql/candidate-fixtures.sql")
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
    @DisplayName("착수·PR 생성 엔드포인트는 존재하지 않는다 — S-6 · S-2")
    void 착수와_PR생성_엔드포인트는_없다_S6() throws Exception {
        // 🔴 「아직 안 만들었다」를 테스트로 고정한다. 둘 다 지금 열면 후보가 빠져나올 수 없는
        //    상태에 갇히고(IMPLEMENTING 탈출 트리거 없음 · PR 없는 종단 PR_CREATED),
        //    pull-request 는 S-2 까지 닿는다. 실행기와 함께 연다 — #18 · #23
        mockMvc.perform(post("/api/candidates/103/implement"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/candidates/103/pull-request"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 취소는_DELETE_로_열려_있지_않다_Q5() throws Exception {
        // 지우는 것이 아니라 종단으로 전이시키는 행위다. DELETE 로 열면
        // 「선정을 되돌린다 → ANALYZED 복귀」로 읽히는데 실제로는 되돌릴 수 없다
        mockMvc.perform(delete("/api/candidates/103/select"))
                .andExpect(status().isMethodNotAllowed());
    }
}
