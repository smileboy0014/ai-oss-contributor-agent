package com.ossagent.repository.adapter.out.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ossagent.agent.domain.AgentRunContext;
import com.ossagent.agent.domain.LanguageModel;
import com.ossagent.agent.domain.LlmCallSite;
import com.ossagent.agent.domain.LlmPermanentException;
import com.ossagent.agent.domain.LlmRequest;
import com.ossagent.agent.domain.LlmResponse;
import com.ossagent.repository.domain.ContributionRuleInterpreter;
import com.ossagent.repository.domain.FetchedDocument;
import com.ossagent.repository.domain.RepositoryCoordinates;
import com.ossagent.repository.domain.RepositoryDocuments;
import com.ossagent.repository.domain.RuleReading;
import com.ossagent.repository.domain.ScrubbedRules;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LLM 으로 기여 규약을 판정한다.
 *
 * <h2>🔴 모델에게 「허용인가」를 묻지 않는다</h2>
 *
 * <p>Boolean 을 물으면 모델이 <b>「언급 없음」과 「불명확」을 뭉갠다.</b> 그런데 Q-8 이 가르는
 * 선이 정확히 거기다 — 침묵은 <b>허용</b>이고 불명확은 <b>보류</b>다.
 *
 * <p>그래서 3분 enum 을 묻는다. 「금지 문구를 찾았는가」라는 <b>관찰</b>을 묻고,
 * 그것을 허용/금지/보류로 <b>번역하는 것은 우리</b>다. 모델의 판단을 그대로 정책으로
 * 승격시키지 않는다.
 *
 * <table border="1">
 *   <caption>응답 → 판정</caption>
 *   <tr><th>모델 응답</th><th>판정</th><th>근거</th></tr>
 *   <tr><td>{@code FORBIDDEN}</td><td>금지 (FALSE)</td><td>명시적 금지 문구를 찾았다</td></tr>
 *   <tr><td>{@code NOT_MENTIONED}</td><td>허용 (TRUE)</td><td>Q-8 확정 ① — 침묵은 허용이다</td></tr>
 *   <tr><td>{@code UNCLEAR}</td><td>🔴 보류 (NULL)</td><td>판정이 서지 않았다</td></tr>
 *   <tr><td>그 외 · 파싱 실패</td><td>🔴 보류 (NULL)</td><td>못 믿을 응답을 허용으로 읽지 않는다</td></tr>
 * </table>
 *
 * <p>「명시적 허용이 있어야 허용」으로 두지 않는 이유 — Q-8 이 대상 저장소 7곳을 실측한 결과
 * <b>명시적 허용을 적은 곳이 0개</b>였다. 그렇게 두면 Phase 1 대상이 즉시 보류돼
 * 파이프라인이 한 번도 돌지 않는다.
 *
 * <p>스크럽은 {@code AnthropicLanguageModel} 이 생성자 필수 인자로 강제하므로 여기서
 * 따로 하지 않는다 (S-4). 영속화되는 요약만 {@link ScrubbedRules} 로 한 번 더 거른다.
 */
public class LlmContributionRuleInterpreter implements ContributionRuleInterpreter {

    private static final Logger log = LoggerFactory.getLogger(LlmContributionRuleInterpreter.class);

    private static final String SYSTEM = """
            너는 오픈소스 저장소의 기여 규약 문서를 읽고 사실만 보고하는 도구다.
            추측하지 않는다. 문서에 없는 것은 없다고 답한다.

            반드시 JSON 객체 하나만 출력한다. 설명·코드펜스·머리말을 붙이지 않는다.

            {
              "aiContribution": "FORBIDDEN | NOT_MENTIONED | UNCLEAR",
              "evidence": "판단 근거가 된 문장 (최대 200자, 없으면 빈 문자열)",
              "javaVersion": "문서에 적힌 Java 버전 또는 null",
              "buildCommand": "빌드 명령 또는 null",
              "testCommand": "테스트 명령 또는 null",
              "issueReferenceRequired": true | false,
              "signoffRequired": true | false,
              "testsRequired": true | false,
              "prTemplateSummary": "PR 본문 형식 요약 (최대 300자) 또는 null"
            }

            aiContribution 판정 기준 — 이것만 지키면 된다.
            - FORBIDDEN: AI·LLM·생성형 도구로 만든 기여를 금지/거부한다고 문서가 명시한다
            - NOT_MENTIONED: 문서를 다 읽었고 AI 기여에 대한 금지 언급이 없다
            - UNCLEAR: 언급이 있으나 금지인지 아닌지 판단할 수 없다

            ⚠ "명시적 허용이 없으니 UNCLEAR" 로 답하지 않는다. 금지 언급이 없으면
              NOT_MENTIONED 다. 대부분의 저장소는 AI 기여를 아예 언급하지 않는다.
            """;

    private final LanguageModel languageModel;
    private final PolicyAnalysisProperties properties;
    private final ObjectMapper objectMapper;

    public LlmContributionRuleInterpreter(LanguageModel languageModel,
            PolicyAnalysisProperties properties, ObjectMapper objectMapper) {
        this.languageModel = languageModel;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public RuleReading interpret(RepositoryCoordinates coordinates, RepositoryDocuments documents) {
        List<FetchedDocument> read = documents.readDocuments();
        if (read.isEmpty()) {
            // 호출자가 이미 걸러야 하지만, 여기서도 토큰을 쓰지 않는다
            return RuleReading.undetermined();
        }

        LlmRequest request = new LlmRequest(SYSTEM, userPrompt(coordinates, read),
                properties.maxOutputTokens());

        LlmResponse response;
        try {
            // 🔴 후보가 없다 — 저장소 단위 호출이다
            response = languageModel.complete(
                    AgentRunContext.forRepository(LlmCallSite.POLICY), request);
        } catch (LlmPermanentException e) {
            // 거부·절단·잘못된 요청 — 재시도해도 같다. 보류로 굳힌다
            log.warn("규약 판정 실패 repo={} reason={} — 보류", coordinates.fullName(), e.reason());
            return RuleReading.undetermined();
        }
        // ⚠ LlmTransientException 은 잡지 않고 전파한다. 호출자가 아무것도 쓰지 않고
        //   중단해야 한다 — 보류로 만들면 저절로 풀렸을 일이 영구 보류가 된다 (#24 미구현)

        return parse(coordinates, response, read);
    }

    private String userPrompt(RepositoryCoordinates coordinates, List<FetchedDocument> read) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("저장소: ").append(coordinates.fullName()).append("\n\n");
        for (FetchedDocument doc : read) {
            sb.append("===== ").append(doc.path().path()).append(" =====\n")
                    .append(doc.content()).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 응답을 우리 값으로. <b>여기서 파싱이 끝난다.</b>
     *
     * <p>못 믿을 응답은 전부 보류다 — 파싱 실패 · 알 수 없는 enum · 필드 누락.
     * 「모델이 대충 말한 것」을 허용으로 읽지 않는다.
     */
    private RuleReading parse(RepositoryCoordinates coordinates, LlmResponse response,
            List<FetchedDocument> read) {
        JsonNode root;
        try {
            root = objectMapper.readTree(stripFence(response.text()));
        } catch (Exception e) {
            // ⚠ 응답 본문을 로그에 남기지 않는다 — 대상 저장소 텍스트가 인용돼 있을 수 있다
            log.warn("규약 판정 응답을 파싱하지 못했다 repo={} size={} — 보류",
                    coordinates.fullName(), response.text().length());
            return RuleReading.undetermined();
        }

        Boolean allowed = switch (text(root, "aiContribution")) {
            case "FORBIDDEN" -> Boolean.FALSE;
            // Q-8 확정 ① — 침묵은 허용이다
            case "NOT_MENTIONED" -> Boolean.TRUE;
            // UNCLEAR · null · 오타 · 알 수 없는 값 전부
            case null, default -> null;
        };

        if (allowed == null) {
            log.info("규약 판정이 서지 않았다 repo={} — 보류", coordinates.fullName());
            return RuleReading.undetermined();
        }

        return new RuleReading(
                allowed,
                text(root, "javaVersion"),
                text(root, "buildCommand"),
                text(root, "testCommand"),
                bool(root, "issueReferenceRequired"),
                bool(root, "signoffRequired"),
                bool(root, "testsRequired"),
                summarize(root, read));
    }

    /**
     * 영속화할 요약. <b>원문을 담지 않는다</b> — 정규화 결과와 근거 경로만 (S-4).
     *
     * <p>{@link ScrubbedRules#of} 가 토큰 패턴을 한 번 더 거른다. {@code evidence} 는 모델이
     * 문서에서 인용한 문장이라 여기 시크릿이 실려 올 수 있다.
     */
    private ScrubbedRules summarize(JsonNode root, List<FetchedDocument> read) {
        var summary = objectMapper.createObjectNode();
        summary.put("evidence", text(root, "evidence"));
        summary.put("prTemplateSummary", text(root, "prTemplateSummary"));
        var paths = summary.putArray("readPaths");
        read.forEach(d -> paths.add(d.path().path()));
        return ScrubbedRules.of(summary.toString());
    }

    /** 모델이 코드펜스로 감싸는 일이 흔하다. 그것 때문에 보류로 떨어뜨릴 이유는 없다. */
    private static String stripFence(String raw) {
        String t = raw.strip();
        if (!t.startsWith("```")) {
            return t;
        }
        int firstNewline = t.indexOf('\n');
        int lastFence = t.lastIndexOf("```");
        if (firstNewline < 0 || lastFence <= firstNewline) {
            return t;
        }
        return t.substring(firstNewline + 1, lastFence).strip();
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static boolean bool(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node != null && node.asBoolean(false);
    }
}
