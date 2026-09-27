package com.ossagent.candidate.adapter.out.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ossagent.agent.domain.AgentRunContext;
import com.ossagent.agent.domain.LanguageModel;
import com.ossagent.agent.domain.LlmCallSite;
import com.ossagent.agent.domain.LlmRequest;
import com.ossagent.agent.domain.LlmResponse;
import com.ossagent.candidate.domain.CodingAgent;
import com.ossagent.candidate.domain.CodingFeedback;
import com.ossagent.candidate.domain.CodingInput;
import com.ossagent.candidate.application.CodingProperties;
import com.ossagent.candidate.domain.CodingOutOfPlanException;
import com.ossagent.candidate.domain.CodingRejectedException;
import com.ossagent.candidate.domain.GeneratedFile;
import com.ossagent.candidate.domain.PlannedFile;
import com.ossagent.repository.domain.SelectedFile;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 구현 계획대로 파일 내용을 만든다 — #18 · PRD §14.
 *
 * <p>{@code LanguageModel}(1층) 위의 2층이다 — {@code LlmImplementationPlanner}(#16)와 같은 자리.
 *
 * <h2>🔴 계획 밖 경로는 그 자리에서 중단한다</h2>
 *
 * <p>「나중에 거르겠다」는 경로를 만들지 않는다. 다만 이것은 <b>조기 차단</b>이고
 * <b>게이트가 아니다</b> — 샌드박스에서 도는 포맷터가 건드린 것은 모델 출력에
 * 나타나지 않으므로 <b>diff 의 경로 집합</b>이 최종 판정이다.
 *
 * <h2>⚠️ 프롬프트에 들어가는 것은 이미 걸러진 값뿐이다 — S-4</h2>
 *
 * <p>{@code SelectedFile} 은 {@code SecretFilePolicy} 가 <b>경로를 걸렀고</b>
 * compact 생성자가 <b>내용을 스크럽</b>했다(#15). 여기서 다시 조립하지 않는다 —
 * 조립하는 순간 두 보증이 함께 사라진다.
 *
 * <p>🔴 그리고 <b>송신 직전에 {@code PromptScrubber} 가 한 번 더</b> 돈다 —
 * 노출되는 {@code LanguageModel} 빈이 그 데코레이터뿐이라 건너뛸 경로가 없다.
 */
public class LlmCodingAgent implements CodingAgent {

    private static final Logger log = LoggerFactory.getLogger(LlmCodingAgent.class);

    private static final String SYSTEM = """
            너는 오픈소스 이슈 하나를 고치는 도구다. 주어진 구현 계획대로 파일 내용을 만든다.
            반드시 JSON 객체 하나만 출력한다. 설명·코드펜스·머리말을 붙이지 않는다.
            {
              "files": [
                {"path": "저장소 기준 경로", "content": "그 파일의 **전체** 최종 내용"}
              ]
            }
            규칙 — 이것만 지키면 된다.
            - 🔴 「구현 계획」에 있는 경로만 쓴다. 다른 경로를 쓰면 작업 전체가 버려진다
            - content 는 **파일 전체**다. 조각·diff·생략 표시(...)를 쓰지 않는다
            - 계획의 파일을 전부 채운다. 필요 없다고 판단한 파일이 있으면 그 경로를 빼지 말고
              원래 내용 그대로 돌려준다
            - 「저장소 컨텍스트」에 없는 API·클래스를 지어내지 않는다
            - 이슈가 요구한 것만 고친다. 눈에 띄는 다른 문제는 건드리지 않는다
            ⚠ 확신이 없으면 범위를 좁힌다. 넓히지 않는다.
            """;

    private final LanguageModel languageModel;
    private final CodingProperties properties;
    private final ObjectMapper objectMapper;

    public LlmCodingAgent(LanguageModel languageModel, CodingProperties properties,
            ObjectMapper objectMapper) {
        this.languageModel = languageModel;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<GeneratedFile> write(Long candidateId, int attempt, CodingInput input) {
        if (candidateId == null) {
            throw new IllegalArgumentException("candidateId 는 필수다 — 비용 기록을 붙일 대상이 없다");
        }
        if (attempt < 1) {
            throw new IllegalArgumentException("사이클 번호는 1 이상이다: " + attempt);
        }
        if (input == null) {
            throw new IllegalArgumentException("코딩 입력은 필수다");
        }

        LlmRequest request =
                new LlmRequest(SYSTEM, userPrompt(input), properties.maxOutputTokens());
        // 🔴 attempt 를 그대로 넘긴다 — AgentRun 불변식이 「같은 사이클의 행이 같은 attempt」다(Q-6).
        //    여기서 1 로 고정하면 CODE 행과 VERIFY 행이 갈려 사이클을 이어 붙일 수 없다
        LlmResponse response = languageModel.complete(
                new AgentRunContext(candidateId, LlmCallSite.CODE, attempt), request);

        return parse(candidateId, response, input.allowedPaths());
    }

    private String userPrompt(CodingInput input) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("## 구현 계획\n");
        for (PlannedFile file : input.plan().files()) {
            prompt.append("- ").append(file.path())
                    .append(" [").append(file.change()).append("] ")
                    .append(nullToEmpty(file.intent())).append('\n');
        }
        prompt.append("\n요약: ").append(nullToEmpty(input.plan().summary())).append('\n');
        prompt.append("검증 전략: ").append(nullToEmpty(input.plan().testStrategy())).append('\n');

        if (input.testsRequired()) {
            // 🔴 대상 저장소 규약이 테스트를 요구한다 — S-5. 계획에 테스트 파일이 있어야 하고,
            //    없으면 계획 단계(#16)가 걸렀어야 한다. 여기서는 사실만 전한다
            prompt.append("\n⚠ 이 저장소는 기여에 테스트를 요구한다. 계획의 테스트 파일을 반드시 채운다.\n");
        }

        appendFeedback(prompt, input);

        prompt.append("\n## 저장소 컨텍스트\n");
        for (SelectedFile file : input.context().files()) {
            // ⚠ 내용은 이미 스크럽됐고 경로는 SecretFilePolicy 를 통과했다 —
            //   여기서 재조립하지 않는 이유가 그것이다(#15)
            prompt.append("\n### ").append(file.path()).append('\n')
                    .append(file.content()).append('\n');
        }
        return prompt.toString();
    }

    /**
     * 🔴 직전 바퀴가 왜 실패했는지 되먹인다 — #21 · FR-3 「구분해 다른 프롬프트로」.
     *
     * <p>첫 바퀴면 아무것도 붙지 않는다. 붙는 경우 <b>종류마다 다른 지시</b>가 간다 —
     * 컴파일 오류와 리뷰 지적은 고치는 방식이 다르고, 같은 문장으로 보내면
     * 모델이 엉뚱한 것을 고친다.
     *
     * <p>⚠️ <b>내용은 이미 스크럽됐다</b>({@code StageResult}·{@code DiffReview} 의
     * compact 생성자). 여기서 다시 스크럽하지 않는 이유는 {@link CodingFeedback} javadoc 에
     * 있다 — 다만 송신 직전 {@code PromptScrubber} 가 한 번 더 돈다.
     *
     * <p>🔴 <b>{@code switch} 에 {@code default} 를 두지 않는다.</b>
     * {@link CodingFeedback.Kind} 에 값이 추가되면 <b>컴파일이 깨진다</b> — 그것이 목적이다.
     * {@code default} 를 두면 새 종류가 조용히 아무 지시로나 나간다.
     */
    private static void appendFeedback(StringBuilder prompt, CodingInput input) {
        if (!input.hasFeedback()) {
            return;
        }
        CodingFeedback feedback = input.feedback();
        prompt.append("\n## 🔴 직전 시도가 실패했다 — 아래를 고쳐 다시 만든다\n");
        prompt.append(switch (feedback.kind()) {
            case COMPILE -> "컴파일이 깨졌다. 아래 오류를 해소한다. "
                    + "계획의 경로는 그대로 두고 내용만 고친다.\n";
            case TEST -> "테스트가 실패했다. 아래를 보고 구현을 고친다. "
                    + "⚠ 테스트를 통과시키려고 테스트를 무력화하지 않는다.\n";
            case DIFF -> "계획에 없는 파일이 바뀌었다. 계획의 경로 밖을 건드리지 않는다.\n";
            case REVIEW -> "AI 리뷰가 변경을 요구했다. 아래 지적을 반영한다.\n";
        });
        for (String point : feedback.points()) {
            prompt.append("- ").append(point).append('\n');
        }
    }

    /**
     * 🔴 계획 밖 경로를 <b>모아서 한 번에</b> 던진다.
     *
     * <p>첫 위반에서 바로 던지면 사람이 <b>하나씩 고치며 여러 바퀴</b>를 돈다.
     * 전부 모으면 한 번에 보인다 — 재시도 예산(Q-6)이 3바퀴뿐이라 그 차이가 크다.
     */
    private List<GeneratedFile> parse(Long candidateId, LlmResponse response,
            Set<String> allowedPaths) {

        JsonNode root;
        try {
            root = objectMapper.readTree(response.text());
        } catch (Exception e) {
            // ⚠ 응답 본문을 메시지에 넣지 않는다 — 대상 저장소 텍스트가 섞여 있다
            throw new CodingRejectedException("코딩 응답이 JSON 이 아니다 length=" + length(response));
        }
        JsonNode filesNode = root.path("files");
        if (!filesNode.isArray() || filesNode.isEmpty()) {
            throw new CodingRejectedException("코딩 응답에 files 배열이 없다");
        }

        List<GeneratedFile> files = new ArrayList<>(filesNode.size());
        Set<String> outside = new LinkedHashSet<>();
        for (JsonNode node : filesNode) {
            String path = text(node, "path");
            if (path == null || !allowedPaths.contains(path)) {
                outside.add(String.valueOf(path));
                continue;
            }
            files.add(new GeneratedFile(path, text(node, "content")));
        }

        if (!outside.isEmpty()) {
            throw new CodingOutOfPlanException(candidateId, outside);
        }
        if (files.isEmpty()) {
            throw new CodingRejectedException("코딩 응답에 쓸 수 있는 파일이 없다");
        }
        // 🔴 경로만 남긴다 — 내용은 대상 저장소 텍스트다 (logging.md · PromptBoundaryTest)
        log.debug("코딩 산출 파싱 완료 files={}", files.size());
        return List.copyOf(files);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static int length(LlmResponse response) {
        return response.text() == null ? 0 : response.text().length();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
