package com.ossagent.candidate.adapter.out.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ossagent.agent.domain.AgentRunContext;
import com.ossagent.agent.domain.LanguageModel;
import com.ossagent.agent.domain.LlmCallSite;
import com.ossagent.agent.domain.LlmRequest;
import com.ossagent.agent.domain.LlmResponse;
import com.ossagent.candidate.application.ImplementationPlanProperties;
import com.ossagent.candidate.domain.ImplementationPlan;
import com.ossagent.candidate.domain.ImplementationPlanner;
import com.ossagent.candidate.domain.PlanRejectedException;
import com.ossagent.candidate.domain.PlannedFile;
import com.ossagent.candidate.domain.PlanningInput;
import com.ossagent.repository.domain.ContributionConstraints;
import com.ossagent.repository.domain.RepositoryContext;
import com.ossagent.repository.domain.SelectedFile;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ImplementationPlanner} 의 LLM 구현 — {@code LanguageModel} 위에 얹히는 <b>2층</b>.
 *
 * <p>{@code LlmIssueAnalyst}(#11)와 같은 구조다. 기술 이름은 여기에만 있다 — 규율 ③.
 *
 * <h2>🔴 프롬프트가 지는 의무 셋</h2>
 * <ol>
 *   <li><b>컨텍스트로 준 파일 목록을 명시</b>한다 — 검증이 그 목록으로 실재를 판정하므로
 *       (D-5), 모델이 목록을 모르면 무고하게 거부된다</li>
 *   <li><b>규약 제약을 싣는다</b> (FR-6 · S-5) — 테스트 필수 여부가 계획을 좌우한다</li>
 *   <li><b>재생성이면 거부 사유를 싣는다</b> (FR-5) — 싣지 않으면 같은 실수를 반복하고
 *       예산만 태운다</li>
 * </ol>
 *
 * <p>⚠️ 프롬프트에 대상 저장소 <b>파일 내용</b>이 실려 나간다. 스크럽은 송신 직전
 * {@code PromptScrubber} 가 강제하고(어댑터 계약), 내용 자체는 이미 {@code SelectedFile} 이
 * 한 번 걸렀다 (#15 S-4).
 */
public class LlmImplementationPlanner implements ImplementationPlanner {

    private static final Logger log = LoggerFactory.getLogger(LlmImplementationPlanner.class);

    private static final String SYSTEM = """
            너는 오픈소스 이슈 하나를 고치는 구현 계획을 세우는 도구다.
            아래 「저장소 컨텍스트」로 제시된 파일만 근거로 삼는다. 없는 파일을 지어내지 않는다.
            반드시 JSON 객체 하나만 출력한다. 설명·코드펜스·머리말을 붙이지 않는다.
            {
              "files": [
                {"path": "저장소 기준 경로", "change": "MODIFY | CREATE", "intent": "이 파일에서 무엇을 왜 바꾸는가"}
              ],
              "summary": "계획 요약",
              "testStrategy": "무엇을 어떻게 검증하는가. 테스트가 필요 없다고 판단하면 그 근거",
              "estimatedLoc": 0 이상의 정수
            }
            규칙 — 이것만 지키면 된다.
            - MODIFY 는 「저장소 컨텍스트」에 제시된 경로만 쓴다. 제시되지 않은 파일은 고칠 수 없다
            - CREATE 는 아직 없는 파일에만 쓴다. 이미 제시된 파일을 CREATE 로 쓰면 덮어쓰게 된다
            - files 는 최소 1개다. 같은 경로를 두 번 넣지 않는다
            - 이슈가 요구한 것만 고친다. 눈에 띄는 다른 문제는 이 계획에 넣지 않는다
            - estimatedLoc 은 추가·삭제를 합친 대략의 줄 수다
            ⚠ 확신이 없으면 범위를 좁힌다. 넓히지 않는다.
            """;

    private final LanguageModel languageModel;
    private final ImplementationPlanProperties properties;
    private final ObjectMapper objectMapper;

    public LlmImplementationPlanner(LanguageModel languageModel,
            ImplementationPlanProperties properties, ObjectMapper objectMapper) {
        this.languageModel = languageModel;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public ImplementationPlan plan(Long candidateId, PlanningInput input) {
        if (candidateId == null) {
            throw new IllegalArgumentException("candidateId 는 필수다 — 비용 기록을 붙일 대상이 없다");
        }
        if (input == null) {
            throw new IllegalArgumentException("계획 입력은 필수다");
        }
        LlmRequest request =
                new LlmRequest(SYSTEM, userPrompt(input), properties.maxOutputTokens());
        // 🔴 attempt 는 항상 1 이다 — PLAN 은 파이프라인 루프 카운터 밖이다 (Q-6).
        //    계획 재생성은 별개 축이고, 그 회차는 AgentRun 행 수로 드러난다
        LlmResponse response = languageModel.complete(
                AgentRunContext.firstAttempt(candidateId, LlmCallSite.PLAN), request);
        return parse(response);
    }

    private String userPrompt(PlanningInput input) {
        StringBuilder prompt = new StringBuilder();
        if (input.isRetry()) {
            // 🔴 맨 앞에 둔다. 뒤에 묻히면 모델이 같은 계획을 다시 낸다
            prompt.append(input.feedback().asFeedback()).append('\n');
        }
        prompt.append("# 이슈 #").append(input.issue().githubIssueNumber()).append('\n')
                .append("제목: ").append(nullToEmpty(input.issue().title())).append('\n')
                .append("본문:\n").append(nullToEmpty(input.issue().body())).append("\n\n");

        appendConstraints(prompt, input.constraints());
        appendContext(prompt, input.context());
        return prompt.toString();
    }

    /** FR-6 · S-5 — 규약을 모델이 보게 한다. 검증(FR-3)이 같은 것을 다시 본다 */
    private void appendConstraints(StringBuilder prompt, ContributionConstraints constraints) {
        prompt.append("# 대상 저장소 규약\n");
        prompt.append("- 테스트 필수: ").append(constraints.testsRequired() ? "예" : "아니오")
                .append('\n');
        if (constraints.javaVersion() != null) {
            prompt.append("- Java 버전: ").append(constraints.javaVersion()).append('\n');
        }
        if (constraints.hasTestCommand()) {
            prompt.append("- 테스트 명령: ").append(constraints.testCommand()).append('\n');
        }
        prompt.append('\n');
    }

    /**
     * 🔴 <b>제시한 파일 목록을 모델이 알아야 한다.</b> 검증이 이 목록으로 실재를 판정하므로
     * (D-5), 목록을 안 보여주면 모델은 추측할 수밖에 없고 무고하게 거부된다.
     */
    private void appendContext(StringBuilder prompt, RepositoryContext context) {
        prompt.append("# 저장소 컨텍스트 — MODIFY 는 아래 경로에서만 고른다\n");
        if (context.isPartial()) {
            prompt.append("(상한에서 잘린 목록이다. 그래도 아래에서만 고른다)\n");
        }
        for (SelectedFile file : context.files()) {
            prompt.append("\n## ").append(file.path())
                    .append("  [").append(file.reason()).append("]\n")
                    .append("```\n").append(file.content()).append("\n```\n");
        }
        if (context.files().isEmpty()) {
            prompt.append("(관련 파일을 찾지 못했다. 신규 파일 생성만 계획할 수 있다)\n");
        }
    }

    private ImplementationPlan parse(LlmResponse response) {
        JsonNode root;
        try {
            root = objectMapper.readTree(response.text());
        } catch (Exception e) {
            // ⚠ 응답 본문을 메시지에 넣지 않는다 — 대상 저장소 텍스트가 섞여 있다
            throw new PlanRejectedException("계획 응답이 JSON 이 아닙니다 length=" + length(response), e);
        }
        JsonNode filesNode = root.path("files");
        if (!filesNode.isArray() || filesNode.isEmpty()) {
            throw new PlanRejectedException("계획 응답에 files 배열이 없습니다");
        }
        List<PlannedFile> files = new ArrayList<>(filesNode.size());
        for (JsonNode node : filesNode) {
            files.add(new PlannedFile(
                    text(node, "path"),
                    PlannedFile.ChangeKind.from(text(node, "change")),
                    text(node, "intent")));
        }
        ImplementationPlan plan = new ImplementationPlan(
                files, text(root, "summary"), text(root, "testStrategy"),
                root.path("estimatedLoc").asInt(0));
        // 🔴 경로는 남기고 본문은 남기지 않는다 — D-2 로 영속화하지 않으므로 로그가 유일한 기록이다
        log.debug("계획 파싱 완료 files={} loc={}", plan.fileCount(), plan.estimatedLoc());
        return plan;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static int length(LlmResponse response) {
        return response == null || response.text() == null ? 0 : response.text().length();
    }
}
