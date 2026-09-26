package com.ossagent.repository.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ossagent.repository.adapter.out.persistence.OssRepositoryRepository;
import com.ossagent.repository.adapter.out.persistence.RepositoryPolicyRepository;
import com.ossagent.repository.application.ScanExecutionRegistry;
import com.ossagent.repository.application.ScanExecutionState;
import com.ossagent.repository.application.ScanPipelineResult;
import com.ossagent.repository.application.ScanTarget;
import com.ossagent.repository.domain.OssRepository;
import com.ossagent.repository.domain.RepositoryPolicy;
import com.ossagent.repository.domain.RuleReading;
import com.ossagent.repository.domain.ScrubbedRules;
import com.ossagent.support.testing.AgentIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 스캔 트리거 API — {@code 202} · {@code 409} · 진행 조회 (FR-3 · FR-4).
 *
 * <p>🔴 <b>비동기 완료를 기다리지 않는다.</b> 레지스트리를 직접 조작해 상태를 만들고
 * HTTP 계약만 본다 — 타이밍에 의존하면 CI 에서 느리거나 깨진다.
 *
 * <p>대외 의존은 {@code fakes} 프로파일이 차단한다. 정책을 <b>금지</b>로 심어 두어
 * 혹시 파이프라인이 돌더라도 수집 전에 끊기게 한다 — 테스트가 서로를 오염시키지 않는다.
 */
@AgentIntegrationTest
@AutoConfigureMockMvc
class ScanTriggerIntegrationTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneOffset.UTC);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OssRepositoryRepository repositories;

    @Autowired
    private RepositoryPolicyRepository policies;

    @Autowired
    private ScanExecutionRegistry registry;

    private Long repositoryId;

    @BeforeEach
    void setUp() {
        String name = "spring-kafka-" + System.nanoTime();
        OssRepository repository = repositories.save(new OssRepository(
                "spring-projects", name, "https://github.com/spring-projects/" + name));
        repositoryId = repository.getId();
        // 금지로 심어 둔다 — 파이프라인이 돌아도 수집 전에 끊긴다
        policies.save(RepositoryPolicy.analyzed(repository,
                new RuleReading(false, null, null, null, false, false, false,
                        ScrubbedRules.none()),
                CLOCK));
        registry.release(repositoryId);
    }

    @Test
    @DisplayName("스캔 요청은 202 와 진행 조회 경로를 돌려준다 — 기다리지 않는다 FR-3")
    void 스캔_요청은_202_다() throws Exception {
        mockMvc.perform(post("/api/repositories/{id}/scan", repositoryId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.repositoryId").value(repositoryId))
                .andExpect(jsonPath("$.status").value("SCAN_ACCEPTED"))
                .andExpect(jsonPath("$.statusUrl")
                        .value("/api/repositories/" + repositoryId + "/scan"))
                .andExpect(jsonPath("$.acceptedAt").exists());
    }

    @Test
    @DisplayName("🔴 진행 중이면 409 — 같은 저장소를 두 번 돌리지 않는다 FR-4")
    void 진행_중이면_409_다() throws Exception {
        // 레지스트리를 직접 점유한다 — 비동기 타이밍에 기대지 않는다
        assertThat(registry.tryStart(repositoryId)).isTrue();

        mockMvc.perform(post("/api/repositories/{id}/scan", repositoryId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("ALREADY_RUNNING"));
    }

    @Test
    @DisplayName("한 번도 안 돌았으면 IDLE 이다 — 404 가 아니다")
    void 안_돌았으면_IDLE_이다() throws Exception {
        mockMvc.perform(get("/api/repositories/{id}/scan", repositoryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("IDLE"))
                .andExpect(jsonPath("$.repositoryId").value(repositoryId));
    }

    @Test
    @DisplayName("진행 조회가 단계별 수치와 hasMore 를 보여준다 FR-7")
    void 진행_조회가_수치를_보여준다() throws Exception {
        registry.tryStart(repositoryId);
        registry.markRunning(repositoryId);
        registry.markSucceeded(repositoryId, new ScanPipelineResult(
                12, 12, 3, 1, 0, 0, true, null, null));

        mockMvc.perform(get("/api/repositories/{id}/scan", repositoryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("SUCCEEDED"))
                .andExpect(jsonPath("$.issuesSaved").value(12))
                .andExpect(jsonPath("$.candidatesAnalyzed").value(3))
                .andExpect(jsonPath("$.candidatesRejected").value(1))
                .andExpect(jsonPath("$.hasMore")
                        .value(true));
    }

    @Test
    @DisplayName("건너뛴 실행은 SKIPPED 와 사유를 보여준다 — 실패가 아니다")
    void 건너뛴_실행은_SKIPPED_다() throws Exception {
        registry.tryStart(repositoryId);
        registry.markSkipped(repositoryId,
                ScanPipelineResult.skipped(ScanTarget.SkipReason.CONTRIBUTION_FORBIDDEN));

        mockMvc.perform(get("/api/repositories/{id}/scan", repositoryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("SKIPPED"))
                .andExpect(jsonPath("$.skipReason").value("CONTRIBUTION_FORBIDDEN"))
                .andExpect(jsonPath("$.failureType").doesNotExist());
    }

    @Test
    @DisplayName("🔴 실패 응답에 예외 원문이 실리지 않는다 S4")
    void 진행_조회에_예외_원문이_실리지_않는다_S4() throws Exception {
        registry.tryStart(repositoryId);
        registry.markFailed(repositoryId, ScanExecutionState.Stage.SCAN,
                "GitHubApiException", null);

        mockMvc.perform(get("/api/repositories/{id}/scan", repositoryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("FAILED"))
                .andExpect(jsonPath("$.failureStage").value("SCAN"))
                .andExpect(jsonPath("$.failureType").value("GitHubApiException"))
                // 메시지·스택을 담는 필드가 응답 스키마에 아예 없다
                .andExpect(jsonPath("$.failureMessage").doesNotExist())
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }
}
