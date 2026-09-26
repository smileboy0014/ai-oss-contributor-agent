package com.ossagent.repository.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ossagent.repository.adapter.out.persistence.OssRepositoryRepository;
import com.ossagent.repository.adapter.out.persistence.RepositoryPolicyRepository;
import com.ossagent.repository.domain.OssRepository;
import com.ossagent.repository.domain.RepositoryPolicy;
import com.ossagent.repository.domain.RuleReading;
import com.ossagent.repository.domain.ScrubbedRules;
import com.ossagent.support.testing.AgentIntegrationTest;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 승인 게이트 ②의 HTTP 표면 — S-5 · Q-8.
 *
 * <p>업무 규칙은 {@code ResolvePolicyPendingUseCaseTest} 가 본다. 여기서는
 * <b>상태 코드</b>와 <b>요청 검증</b>만 본다 — 특히 「필드를 빠뜨린 요청이 조용히 금지가
 * 되지 않는가」다.
 */
@AgentIntegrationTest
@AutoConfigureMockMvc
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PolicyResolutionApiTest {

    private static final String PATH = "/api/repositories/%d/policy/resolution";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private OssRepositoryRepository repositories;
    @Autowired
    private RepositoryPolicyRepository policies;
    @Autowired
    private Clock clock;

    private Long repositoryId;

    @BeforeEach
    void setUp() {
        policies.deleteAll();
        String name = "spring-kafka-" + System.nanoTime();
        repositoryId = repositories.save(new OssRepository(
                "spring-projects", name,
                "https://github.com/spring-projects/" + name)).getId();
    }

    private void givenPending() {
        policies.save(RepositoryPolicy.pending(
                repositories.findById(repositoryId).orElseThrow(), "AGENTS.md=UNKNOWN", clock));
    }

    private void givenForbidden() {
        policies.save(RepositoryPolicy.analyzed(
                repositories.findById(repositoryId).orElseThrow(),
                new RuleReading(false, null, null, null, false, false, false, ScrubbedRules.none()),
                clock));
    }

    @Test
    void 해소는_200_과_방향을_돌려준다_Q8() throws Exception {
        givenPending();

        mockMvc.perform(post(PATH.formatted(repositoryId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowed\":true,\"note\":\"adoc 을 직접 열었다\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositoryId").value(repositoryId))
                .andExpect(jsonPath("$.aiContributionAllowed")
                        .value(true))
                .andExpect(jsonPath("$.resolvedAt").isNotEmpty());
    }

    @Test
    void 해소_응답은_저장된_사유를_되돌려주지_않는다_S4() throws Exception {
        givenPending();

        mockMvc.perform(post(PATH.formatted(repositoryId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowed\":true,\"note\":\"직접 확인했다\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolutionNote").doesNotExist())
                .andExpect(jsonPath("$.note").doesNotExist());
    }

    @Test
    void 정책_행이_없으면_404_다_S5() throws Exception {
        mockMvc.perform(post(PATH.formatted(repositoryId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowed\":true,\"note\":\"그냥 허용하자\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 금지_판정_해소는_409_다_S5() throws Exception {
        givenForbidden();

        mockMvc.perform(post(PATH.formatted(repositoryId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowed\":true,\"note\":\"메인테이너가 괜찮다고 했다\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void allowed_를_빠뜨리면_400_이다_S5() throws Exception {
        givenPending();

        // 🔴 boolean 원시타입이었으면 조용히 false 가 된다. 「안전한 기본값」처럼 보이지만
        //    사람이 판단하지 않은 금지이고, 근거 없는 판정이 resolvedAt 과 함께 박힌다
        mockMvc.perform(post(PATH.formatted(repositoryId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"확인했다\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 근거를_빠뜨리면_400_이다() throws Exception {
        givenPending();

        mockMvc.perform(post(PATH.formatted(repositoryId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowed\":true,\"note\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 정책을_통째로_덮어쓰는_문은_열려_있지_않다_S5() throws Exception {
        givenPending();

        // PUT /policy 로 뒀으면 aiContributionAllowed 를 보류를 거치지 않고 바꿀 수 있다.
        // 여는 것은 정책이 아니라 「보류를 해소한다」는 행위 하나다
        mockMvc.perform(post("/api/repositories/%d/policy".formatted(repositoryId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"aiContributionAllowed\":true}"))
                .andExpect(status().isNotFound());
    }
}
