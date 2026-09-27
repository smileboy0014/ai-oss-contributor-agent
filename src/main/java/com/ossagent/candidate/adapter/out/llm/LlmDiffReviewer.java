package com.ossagent.candidate.adapter.out.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ossagent.agent.domain.AgentRunContext;
import com.ossagent.agent.domain.LanguageModel;
import com.ossagent.agent.domain.LlmRequest;
import com.ossagent.agent.domain.LlmResponse;
import com.ossagent.candidate.application.DiffReviewProperties;
import com.ossagent.candidate.domain.DiffReview;
import com.ossagent.candidate.domain.DiffReviewRejectedException;
import com.ossagent.candidate.domain.DiffReviewRequest;
import com.ossagent.candidate.domain.DiffReviewer;
import com.ossagent.candidate.domain.ReviewVerdict;
import com.ossagent.repository.domain.ContributionConstraints;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * diff 리뷰의 LLM 구현 — 이슈 #20. {@code LanguageModel} 위에 얹히는 <b>2층</b>이다.
 *
 * <p>물어보는 것은 <b>관찰값</b>(판정 + 4축 + 요약 + 지적)뿐이고, 그것을 「되돌릴 것인가」로
 * 접는 것은 호출자(#21)다. 임계를 여기 두면 <b>게이트를 어댑터 한 장으로 바꿀 수 있게</b> 된다 —
 * {@code LlmIssueAnalyst} 가 같은 이유로 관찰값만 돌려준다.
 *
 * <p>🔴 <b>스크럽을 여기서 하지 않는다.</b> 송신은 {@code AnthropicLanguageModel} 이 생성자
 * 필수 인자로 강제하고(S-4), 수신은 {@link DiffReview} 생성자가 강제한다. 두 벌을 두지 않는다.
 *
 * <p>⚠️ {@code LlmException}(타임아웃·절단·5xx)은 <b>잡지 않고 전파</b>한다 — 전송 계층
 * 재시도는 별개 축이고(`agent.llm.max-retries`), 여기서 삼키면 그 축이 보지 못한다.
 */
public class LlmDiffReviewer implements DiffReviewer {

    private static final Logger log = LoggerFactory.getLogger(LlmDiffReviewer.class);

    private static final String SYSTEM = """
            당신은 오픈소스 기여 diff 를 검토하는 리뷰어다.
            빌드와 테스트는 이미 통과했다. 그것이 못 잡는 것만 본다.

            JSON 객체 하나만 답한다. 설명·코드펜스를 덧붙이지 않는다.

            {
              "verdict": "PASS" | "CHANGES_REQUESTED" | "UNDETERMINED",
              "satisfiesIssue":     true | false | null,
              "withinScope":        true | false | null,
              "followsConventions": true | false | null,
              "testsAdequate":      true | false | null,
              "summary": "사람이 읽을 한 문단",
              "findings": ["고쳐야 할 것", ...]
            }

            규칙:
            - 판정할 근거가 없으면 그 축을 null 로 둔다. 추측해서 true 로 두지 않는다.
            - 저장소 규약이 주어지지 않았으면 followsConventions 는 반드시 null 이다.
            - 네 축을 모두 판정할 수 없으면 verdict 는 UNDETERMINED 이고,
              summary 에 왜 판정할 수 없었는지를 적는다.
            - verdict 가 PASS 이면 어떤 축도 false 일 수 없다.
            - verdict 가 CHANGES_REQUESTED 이면 findings 가 비어 있을 수 없다.
            - diff 에 시크릿처럼 보이는 문자열이 있으면 그 값을 인용하지 말고
              위치만 지적한다.
            """;

    private final LanguageModel languageModel;
    private final DiffReviewProperties properties;
    private final ObjectMapper objectMapper;

    public LlmDiffReviewer(LanguageModel languageModel, DiffReviewProperties properties,
            ObjectMapper objectMapper) {
        this.languageModel = languageModel;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public DiffReview review(AgentRunContext context, DiffReviewRequest request) {
        if (context == null || request == null) {
            throw new IllegalArgumentException("context 와 request 는 필수입니다");
        }
        // 🔴 자르지 않는다. 잘린 diff 를 리뷰하면 「안 본 부분에 문제가 있었을 수 있다」가
        //    되고, 그것은 리뷰가 아니라 리뷰의 시늉이다 — #7 이 규약 문서에서 내린 판단과 같다.
        //    ⚠ 이 예외는 재시도 대상이 아니다(Reason.retryable() == false) — 같은 diff 는
        //      매 바퀴 같은 크기라, 재시도하면 Q-6 예산 3바퀴를 통째로 태운다
        if (request.diff().length() > properties.maxDiffChars()) {
            log.warn("diff 가 리뷰 상한을 넘었다 candidateId={} size={} cap={}",
                    context.candidateId(), request.diff().length(), properties.maxDiffChars());
            throw new DiffReviewRejectedException(DiffReviewRejectedException.Reason.TOO_LARGE,
                    "diff 가 리뷰 상한을 넘었습니다 size=" + request.diff().length()
                            + " cap=" + properties.maxDiffChars());
        }

        LlmResponse response = languageModel.complete(context,
                new LlmRequest(SYSTEM, userPrompt(request), properties.maxOutputTokens()));

        // ⚠ 스칼라만 찍는다 — PromptBoundaryTest 가 본문성 값을 잡는다 (logging.md)
        log.info("diff 리뷰 완료 candidateId={} diffSize={} responseSize={}",
                context.candidateId(), request.diff().length(), response.text().length());

        return parse(context.candidateId(), request, response);
    }

    /**
     * ⚠️ 규약을 모르면 <b>그 사실을 알린다.</b> 조용히 빼면 모델이 자기 상식으로 관습을
     * 판정하고, 그 결과가 「위반 없음」으로 들어온다 — 「모른다」가 「문제 없음」이 되는 자리다.
     */
    private static String userPrompt(DiffReviewRequest request) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("## 이슈\n")
                .append("#").append(request.issue().githubIssueNumber()).append(' ')
                .append(request.issue().title()).append("\n\n")
                .append(request.issue().body()).append("\n\n");

        prompt.append("## 저장소 규약\n");
        if (request.hasKnownConventions()) {
            ContributionConstraints c = request.constraints();
            prompt.append("- java: ").append(orUnknown(c.javaVersion())).append('\n')
                    .append("- build: ").append(orUnknown(c.buildCommand())).append('\n')
                    .append("- test: ").append(orUnknown(c.testCommand())).append('\n')
                    .append("- 테스트 동반 필수: ").append(c.testsRequired()).append('\n')
                    .append("- 이슈 참조 필수: ").append(c.issueReferenceRequired()).append('\n')
                    .append("- sign-off 필수: ").append(c.signoffRequired()).append('\n');
        } else {
            prompt.append("규약을 모른다. followsConventions 는 반드시 null 로 답한다.\n");
        }

        prompt.append("\n## diff\n").append(request.diff()).append('\n');
        return prompt.toString();
    }

    private static String orUnknown(String value) {
        return value == null || value.isBlank() ? "모름" : value;
    }

    private DiffReview parse(Long candidateId, DiffReviewRequest request, LlmResponse response) {
        JsonNode root;
        try {
            root = objectMapper.readTree(stripFence(response.text()));
        } catch (Exception e) {
            // ⚠ 응답 본문을 로그에 남기지 않는다 — diff 가 인용돼 있을 수 있다 (S-4)
            log.warn("리뷰 응답을 파싱하지 못했다 candidateId={} size={}",
                    candidateId, response.text().length());
            throw new DiffReviewRejectedException(DiffReviewRejectedException.Reason.SCHEMA,
                    "리뷰 응답이 JSON 이 아닙니다 candidateId=" + candidateId);
        }
        if (!root.isObject()) {
            throw new DiffReviewRejectedException(DiffReviewRejectedException.Reason.SCHEMA,
                    "리뷰 응답이 JSON 객체가 아닙니다 candidateId=" + candidateId);
        }

        // 🔴 규약을 모르면 모델이 뭐라 답했든 null 로 고정한다. 프롬프트로 부탁만 하고
        //    믿으면, 모델이 상식으로 판정한 것이 「위반 없음」으로 DB 에 앉는다 —
        //    「출력은 신뢰하지 않는다」(external-deps)가 이 자리다
        Boolean conventions = request.hasKnownConventions()
                ? nullableBool(root, "followsConventions")
                : null;

        return new DiffReview(
                verdict(root),
                nullableBool(root, "satisfiesIssue"),
                nullableBool(root, "withinScope"),
                conventions,
                nullableBool(root, "testsAdequate"),
                text(root, "summary"),
                findings(root));
    }

    private static ReviewVerdict verdict(JsonNode root) {
        String raw = text(root, "verdict");
        if (raw == null) {
            throw new DiffReviewRejectedException(DiffReviewRejectedException.Reason.SCHEMA,
                    "verdict 가 없습니다");
        }
        try {
            return ReviewVerdict.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // 🔴 모르는 값을 UNDETERMINED 로 떨어뜨리지 않는다. 그러면 모델의 오타가
            //    「판정 불가」로 둔갑해 재시도 없이 조용히 넘어간다 — 그것은 응답 오류다
            throw new DiffReviewRejectedException(DiffReviewRejectedException.Reason.SCHEMA,
                    "verdict 가 알 수 없는 값입니다");
        }
    }

    /**
     * 🔴 {@code asBoolean(false)} 를 쓰지 않는다 — <b>필드 누락이 {@code false} 로 둔갑</b>한다.
     * 여기서 {@code false} 는 「위반했다」라 누락과 전혀 다른 뜻이고,
     * {@code null}(판정 불가)과도 다르다. 세 상태를 그대로 옮긴다.
     */
    private static Boolean nullableBool(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isBoolean()) {
            throw new DiffReviewRejectedException(DiffReviewRejectedException.Reason.SCHEMA,
                    field + " 가 boolean 이 아닙니다: " + node.getNodeType());
        }
        return node.booleanValue();
    }

    private static List<String> findings(JsonNode root) {
        JsonNode node = root.get("findings");
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new DiffReviewRejectedException(DiffReviewRejectedException.Reason.SCHEMA,
                    "findings 가 배열이 아닙니다: " + node.getNodeType());
        }
        List<String> findings = new ArrayList<>(node.size());
        node.forEach(element -> {
            // ⚠ asText() 로 뭉뚱그리면 객체·배열 원소가 ""가 되고, 그것을 DiffReview 가
            //   「빈 지적」으로 거부한다 — 막히기는 하지만 사유가 오도된다.
            //   막는 것과 「왜 막혔는지 정확히 말하는 것」은 다른 일이다
            if (!element.isTextual()) {
                throw new DiffReviewRejectedException(
                        DiffReviewRejectedException.Reason.SCHEMA,
                        "findings 원소가 문자열이 아닙니다: " + element.getNodeType());
            }
            findings.add(element.textValue());
        });
        return findings;
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    /** 모델이 코드펜스를 붙이는 일이 잦다. 붙였다는 이유로 거부하지 않는다. */
    private static String stripFence(String raw) {
        String trimmed = raw.strip();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        if (firstNewline < 0 || lastFence <= firstNewline) {
            return trimmed;
        }
        return trimmed.substring(firstNewline + 1, lastFence).strip();
    }
}
