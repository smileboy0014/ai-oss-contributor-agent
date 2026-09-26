package com.ossagent.candidate.adapter.out.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ossagent.agent.domain.AgentRunContext;
import com.ossagent.agent.domain.LanguageModel;
import com.ossagent.agent.domain.LlmCallSite;
import com.ossagent.agent.domain.LlmRequest;
import com.ossagent.agent.domain.LlmResponse;
import com.ossagent.candidate.application.IssueAnalysisProperties;
import com.ossagent.candidate.domain.AnalysisRejectedException;
import com.ossagent.candidate.domain.IssueAnalysis;
import com.ossagent.candidate.domain.IssueAnalyst;
import com.ossagent.issue.domain.AnalyzableIssue;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LLM 으로 이슈의 기여 가능성을 판정한다 — PRD §11.
 *
 * <h2>🔴 모델에게 「후보로 삼을까」를 묻지 않는다</h2>
 *
 * <p>물어보는 것은 <b>관찰값</b> 7개뿐이고, 그것을 {@code ANALYZED}/{@code REJECTED} 로
 * 번역하는 것은 UseCase 다. 판정을 모델에게 맡기면 <b>임계 정책이 모델 안으로 들어가</b>
 * 우리가 설정으로 바꿀 수 없게 된다. #7 의 {@code LlmContributionRuleInterpreter} 가
 * 세운 원칙과 같다.
 *
 * <h2>🔴 #7 과 실패 처리가 다르다 — 의도적이다</h2>
 *
 * <p>#7 은 파싱 실패를 <b>보류</b>로 굳힌다. 저장소 단위 판정이고 보류가 S-5 의 안전한 쪽이기
 * 때문이다. 여기는 <b>후보 단위</b>이고 Q-6 이 「{@code ANALYZE} 실패는 즉시 {@code FAILED}」로
 * 확정했다. 그래서 {@link AnalysisRejectedException} 을 던져 <b>호출자가 후보를
 * {@code FAILED} 로 떨어뜨리게</b> 한다 — 「파싱 실패를 성공으로 처리하지 않는다」.
 *
 * <p>⚠️ {@code LlmException}(타임아웃·절단·5xx)은 <b>잡지 않고 전파</b>한다. 전송 계층
 * 재시도가 이미 소진된 뒤이고, 잘린 JSON 을 파서에 넣지 않기 위해서다.
 *
 * <p>스크럽은 {@code AnthropicLanguageModel} 이 생성자 필수 인자로 강제하므로 여기서
 * 따로 하지 않는다 (S-4). 영속화되는 요약은 {@link IssueAnalysis} 가 한 번 더 거른다.
 */
public class LlmIssueAnalyst implements IssueAnalyst {

    private static final Logger log = LoggerFactory.getLogger(LlmIssueAnalyst.class);

    private static final String SYSTEM = """
            너는 오픈소스 이슈를 읽고 기여 난이도를 사실에 근거해 평가하는 도구다.
            추측으로 채우지 않는다. 이슈에 없는 정보는 없다고 판단한다.

            반드시 JSON 객체 하나만 출력한다. 설명·코드펜스·머리말을 붙이지 않는다.

            {
              "category": "bug | enhancement | documentation | test | refactor | question",
              "difficulty": "EASY | MEDIUM | HARD",
              "implementationFeasible": true | false,
              "estimatedFiles": 0 이상의 정수,
              "estimatedLoc": 0 이상의 정수,
              "testRequired": true | false,
              "breakingChange": true | false,
              "confidence": 0.0 이상 1.0 이하의 소수,
              "summary": "판정 근거 요약 (최대 500자)"
            }

            판정 기준 — 이것만 지키면 된다.
            - implementationFeasible: 이슈 본문만으로 무엇을 고쳐야 하는지 알 수 있으면 true.
              재현 방법도 기대 동작도 적혀 있지 않으면 false 다
            - difficulty: EASY = 한두 파일의 국소 수정 / MEDIUM = 여러 파일 + 테스트 추가 /
              HARD = 설계 변경·공개 API 변경·광범위한 영향
            - confidence: 위 판단들에 대한 스스로의 확신도다. 정보가 부족하면 낮춘다
            - summary: 사람이 읽고 판단을 이어갈 수 있게 쓴다. 이슈 본문을 그대로 옮기지 않는다

            ⚠ 값을 모르겠다고 필드를 빼지 않는다. 모든 필드를 채운다.
            ⚠ 확신이 없으면 confidence 를 낮출 것이지, 다른 필드를 꾸며내지 않는다.
            """;

    private final LanguageModel languageModel;
    private final IssueAnalysisProperties properties;
    private final ObjectMapper objectMapper;

    public LlmIssueAnalyst(LanguageModel languageModel, IssueAnalysisProperties properties,
            ObjectMapper objectMapper) {
        this.languageModel = languageModel;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public IssueAnalysis analyze(Long candidateId, AnalyzableIssue issue) {
        if (candidateId == null) {
            throw new IllegalArgumentException("candidateId 는 필수다 — 비용 기록을 붙일 대상이 없다");
        }
        LlmRequest request =
                new LlmRequest(SYSTEM, userPrompt(issue), properties.maxOutputTokens());

        // 🔴 attempt 는 항상 1 이다 — ANALYZE 는 파이프라인 재시도 카운터 밖이다 (Q-6).
        //    RecordingLanguageModel 이 AgentRun(stage·attempt·토큰)을 자동으로 남긴다 (FR-6)
        LlmResponse response = languageModel.complete(
                AgentRunContext.firstAttempt(candidateId, LlmCallSite.ANALYZE), request);

        return parse(candidateId, response);
    }

    /**
     * 프롬프트를 조립한다.
     *
     * <p>⚠️ <b>본문을 절단하면 절단했다고 알린다.</b> 말하지 않으면 모델이 「전문을 봤다」고
     * 전제하고 {@code confidence} 를 높게 준다 — 잘린 정보로 높은 확신이 나오는 것이
     * 가장 나쁜 조합이다.
     *
     * <p>⚠️ {@code UNDECIDED} 사실도 알린다. 규칙 필터가 판단을 보류했다는 것은
     * 「본문이 짧다 · 논의가 길다」 같은 신호이고, 그 자체가 하류에 전달돼야 할 정보다
     * ({@code FilterOutcome} javadoc).
     */
    private String userPrompt(AnalyzableIssue issue) {
        String body = issue.body() == null ? "" : issue.body();
        boolean truncated = body.length() > properties.maxBodyChars();
        if (truncated) {
            body = body.substring(0, properties.maxBodyChars());
        }

        StringBuilder sb = new StringBuilder(1024);
        sb.append("이슈 번호: #").append(issue.githubIssueNumber()).append('\n');
        sb.append("제목: ").append(issue.title() == null ? "" : issue.title()).append('\n');
        sb.append("라벨: ").append(String.join(", ", issue.labels())).append('\n');
        if (issue.isUndecidedByRules()) {
            sb.append("참고: 규칙 필터가 이 이슈를 판정하지 못했다(정보가 부족하거나 논의가 길다). ")
                    .append("그 사실을 감안해 confidence 를 정한다.\n");
        }
        sb.append('\n').append("===== 본문 =====\n").append(body).append('\n');
        if (truncated) {
            sb.append("\n[본문이 여기서 잘렸다. 뒷부분을 보지 못했다는 것을 confidence 에 반영한다]\n");
        }
        return sb.toString();
    }

    /**
     * 응답을 우리 값으로. <b>여기서 파싱이 끝난다.</b>
     *
     * <p>못 믿을 응답은 전부 {@link AnalysisRejectedException} 이다. 스키마 불변식 자체는
     * {@link IssueAnalysis} 생성자가 들고 있으므로, 여기서는 <b>JSON → 타입</b> 변환만 하고
     * 값 검증은 그쪽에 맡긴다 — 검증이 두 곳에 흩어지면 한쪽만 갱신된다.
     */
    private IssueAnalysis parse(Long candidateId, LlmResponse response) {
        JsonNode root;
        try {
            root = objectMapper.readTree(stripFence(response.text()));
        } catch (Exception e) {
            // ⚠ 응답 본문을 로그에 남기지 않는다 — 대상 저장소 텍스트가 인용돼 있을 수 있다
            log.warn("분석 응답을 파싱하지 못했다 candidateId={} size={}",
                    candidateId, response.text().length());
            throw new AnalysisRejectedException(
                    "분석 응답이 JSON 이 아닙니다 candidateId=" + candidateId);
        }
        if (!root.isObject()) {
            throw new AnalysisRejectedException(
                    "분석 응답이 JSON 객체가 아닙니다 candidateId=" + candidateId);
        }

        return new IssueAnalysis(
                text(root, "category"),
                difficulty(root),
                requiredBool(root, "implementationFeasible"),
                requiredInt(root, "estimatedFiles"),
                requiredInt(root, "estimatedLoc"),
                requiredBool(root, "testRequired"),
                requiredBool(root, "breakingChange"),
                confidence(root),
                text(root, "summary"));
    }

    private static IssueAnalysis.Difficulty difficulty(JsonNode root) {
        String raw = text(root, "difficulty");
        if (raw == null) {
            throw new AnalysisRejectedException("difficulty 가 없습니다");
        }
        try {
            return IssueAnalysis.Difficulty.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // 🔴 모르는 값을 MEDIUM 같은 기본값으로 떨어뜨리지 않는다 — 그러면 모델의
            //    실패가 「보통 난이도 후보」로 둔갑해 사람이 그것을 믿게 된다
            throw new AnalysisRejectedException("difficulty 가 알 수 없는 값입니다: " + raw);
        }
    }

    private static BigDecimal confidence(JsonNode root) {
        JsonNode node = root.get("confidence");
        if (node == null || node.isNull() || !node.isNumber()) {
            throw new AnalysisRejectedException(
                    "confidence 가 숫자가 아닙니다: " + (node == null ? "없음" : node.getNodeType()));
        }
        return node.decimalValue();
    }

    /**
     * 🔴 {@code asBoolean(false)} 를 쓰지 않는다 — <b>필드 누락이 {@code false} 로 둔갑</b>한다.
     * {@code implementationFeasible} 에서 그 둔갑은 「구현 불가」 판정이 되어 후보가 조용히
     * {@code REJECTED} 된다.
     */
    private static boolean requiredBool(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isBoolean()) {
            throw new AnalysisRejectedException(field + " 가 boolean 이 아닙니다");
        }
        return node.booleanValue();
    }

    private static int requiredInt(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isIntegralNumber()) {
            throw new AnalysisRejectedException(field + " 가 정수가 아닙니다");
        }
        return node.intValue();
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    /** 모델이 코드펜스로 감싸는 일이 흔하다. 그것 때문에 후보를 FAILED 로 떨어뜨릴 이유는 없다. */
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
}
