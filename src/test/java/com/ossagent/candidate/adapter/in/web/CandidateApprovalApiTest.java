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
 *
 * <h2>⚠️ PR 생성의 <b>성공 경로</b>는 여기서 보지 않는다 (#23)</h2>
 *
 * <p>{@code READY_FOR_PR} 후보를 만들려면 저장소·정책·이슈·변경분 행이 모두 있어야 하고,
 * 대역 넷({@code RepositorySource}·{@code ForkPublisher}·{@code DraftPrPublisher}·정책)을
 * 이 클래스에서 조립하게 된다. 그러면 <b>업무 규칙이 HTTP 테스트로 새어</b> 이 클래스의
 * 경계(「상태 코드와 열려 있지 않은 문」)가 무너진다.
 *
 * <p>성공 경로는 {@code CreateDraftPrUseCaseTest} 가 페이크로 본다.
 * 여기서 보는 것은 <b>문이 열렸다는 것</b>(404 가 아니라 409)과 거부의 상태 코드다.
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
    @DisplayName("PR 생성 엔드포인트는 열렸지만 READY_FOR_PR 이 아니면 409 다 — S-6 · S-2")
    void PR생성은_READY_FOR_PR_이_아니면_409_다_S6() throws Exception {
        // 🔴 404 가 아니라 409 라는 것이 「문이 열렸다」의 증거다.
        //    103 은 SELECTED 라 아직 PR 을 낼 수 없다 — 전이표에 그 전이가 없다
        //
        // ⚠️ 여기 있던 404 회귀 둘(착수·PR 생성)은 **수명이 끝나 지웠다.**
        //    #18 이 착수를, #23 이 PR 생성을 **각자 실행기와 함께** 열었고,
        //    그 강제가 바로 이 테스트였다. 열린 문에 404 를 계속 요구하면
        //    회귀가 아니라 **거짓말**이 된다 — 착수 쪽 게이트는 아래 403·409 들이 본다
        mockMvc.perform(post("/api/candidates/103/pull-request"))
                .andExpect(status().isConflict());

        // 분석만 끝난 후보도 마찬가지다. 「사람이 골랐다」만으로는 PR 이 나가지 않는다
        mockMvc.perform(post("/api/candidates/101/pull-request"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("없는 후보의 PR 생성은 404 다")
    void 없는_후보의_PR생성은_404_다() throws Exception {
        mockMvc.perform(post("/api/candidates/999999/pull-request"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("실행기가 없으면 착수를 시작하지 않는다 — 503 · 후보가 살아남는다")
    void 실행기가_없으면_착수를_시작하지_않는다_S6() throws Exception {
        // 🔴 이 PR 은 C(코딩)·D(산출)가 미완이다. 그때 「일단 전이하고 FAILED 로
        //    떨어뜨린다」를 택하면 **사람이 버튼 한 번으로 후보를 영구히 죽인다** —
        //    IMPLEMENTING 에서 나갈 길이 TESTING·FAILED 뿐이고 FAILED 는 종단이다.
        //
        //    그래서 전이 **전에** 막는다. 503 은 「요청이 틀렸다」가 아니라
        //    「지금 할 수 없다」이고, 배선 뒤에는 같은 요청이 성공한다.
        mockMvc.perform(post("/api/candidates/103/implement"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("🔴 막힌 뒤에도 후보는 그대로 고른 상태다 — 아무것도 태우지 않았다")
    void 막힌_뒤에도_후보는_그대로_고른_상태다_S6() throws Exception {
        mockMvc.perform(post("/api/candidates/103/implement"))
                .andExpect(status().isServiceUnavailable());

        // 🔴 이것이 이 설계의 전부다. 상태가 바뀌었다면 되돌릴 수 없는 일이 일어난 것이다.
        //    「503 을 받았다」만 보고 통과시키면 그 사실이 검증되지 않는다
        mockMvc.perform(post("/api/candidates/103/reject"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("SELECTED"));
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
