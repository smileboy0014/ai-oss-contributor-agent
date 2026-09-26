package com.ossagent.candidate.domain;

import com.ossagent.support.ExternalText;
import com.ossagent.support.secret.TokenRedactor;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * LLM 이 판정한 기여 가능성 — PRD §11 의 산출물.
 *
 * <h2>🔴 이 타입은 두 가지를 강제한다</h2>
 *
 * <ol>
 *   <li><b>스키마</b> — 생성자를 통과했다는 것이 곧 「쓸 수 있는 판정」이라는 뜻이다.
 *       모르는 {@code difficulty}, 범위 밖 {@code confidence} 는 여기서 걸린다</li>
 *   <li><b>스크럽</b> — {@link #summary} 는 <b>스크럽을 거쳐야만 만들어진다</b> (S-4).
 *       {@code repository} 도메인의 {@code ScrubbedRules} 와 같은 수법이다</li>
 * </ol>
 *
 * <p>{@code summary} 가 앉을 자리인 {@code contribution_candidate.analysis} 는
 * {@code repository_policy.contribution_rules} 와 <b>조건이 똑같다</b> — {@code @ExternalText}
 * 가 붙은 TEXT 컬럼이고, LLM 응답 원문이며, 하류(#23 PR 본문) 입력 후보다.
 * 같은 조건에 다른 방어를 적용할 이유가 없다.
 *
 * <p>⚠️ {@code String} 을 그대로 받는 경로를 열지 않는다 — 「이번엔 괜찮겠지」가 언젠가 들어온다.
 *
 * <h2>판정은 이 타입이 하지 않는다</h2>
 *
 * <p>{@code REJECTED} 인지 아닌지는 <b>여기 없다.</b> 이것은 모델이 보고한 <b>관찰값</b>이고,
 * 임계를 적용해 후보를 거르는 것은 UseCase 다. 판정을 값 타입에 넣으면 임계가 도메인에
 * 굳어 설정으로 바꿀 수 없게 된다.
 *
 * @param category               {@code bug}·{@code enhancement}·{@code documentation} 등 자유 문자열
 * @param difficulty             3분 enum
 * @param implementationFeasible 🔴 {@code Boolean} 이 아니라 {@code boolean} 이다 — {@code null}
 *                               을 {@code false} 로 읽지 않기 위해 생성 전에 걸러낸다
 * @param estimatedFiles         수정 예상 파일 수
 * @param estimatedLoc           수정 예상 라인 수
 * @param testRequired           테스트 필요 여부. ⚠️ <b>대응 컬럼이 없다</b> — {@code summary} 에
 *                               남기고 컬럼은 소비자(#16~#19)가 생기는 이슈에서 만든다
 * @param breakingChange         호환성 파괴 여부
 * @param confidence             {@code 0.00 ~ 1.00}. 컬럼이 {@code NUMERIC(3,2)} 라 scale 2 로 고정
 * @param summary                사람이 읽을 판정 요약. <b>스크럽된 값</b>이다
 */
public record IssueAnalysis(
        String category,
        Difficulty difficulty,
        boolean implementationFeasible,
        int estimatedFiles,
        int estimatedLoc,
        boolean testRequired,
        boolean breakingChange,
        BigDecimal confidence,
        @ExternalText(ExternalText.Source.LLM_RESPONSE) String summary) {

    /** {@code category} 컬럼이 {@code VARCHAR(255)} 다. 넘치면 적재 시점에 터진다. */
    private static final int MAX_CATEGORY_LENGTH = 255;

    /** {@code NUMERIC(3,2)} — 소수점 아래 2자리. */
    private static final int CONFIDENCE_SCALE = 2;

    private static final BigDecimal MIN_CONFIDENCE = BigDecimal.ZERO;
    private static final BigDecimal MAX_CONFIDENCE = BigDecimal.ONE;

    /** 난이도. DB 에는 {@link #name()} 으로 앉는다. */
    public enum Difficulty {
        EASY,
        MEDIUM,
        HARD
    }

    public IssueAnalysis {
        category = requireText(category, "category");
        if (category.length() > MAX_CATEGORY_LENGTH) {
            throw new AnalysisRejectedException(
                    "category 가 너무 깁니다 length=" + category.length()
                            + " max=" + MAX_CATEGORY_LENGTH);
        }
        if (difficulty == null) {
            throw new AnalysisRejectedException("difficulty 가 없습니다 — 모르는 값을 DB 에 넣지 않습니다");
        }
        if (estimatedFiles < 0 || estimatedLoc < 0) {
            throw new AnalysisRejectedException(
                    "추정치가 음수입니다 estimatedFiles=" + estimatedFiles
                            + " estimatedLoc=" + estimatedLoc);
        }
        if (confidence == null) {
            throw new AnalysisRejectedException("confidence 가 없습니다");
        }
        if (confidence.compareTo(MIN_CONFIDENCE) < 0 || confidence.compareTo(MAX_CONFIDENCE) > 0) {
            // 🔴 여기서 막지 않으면 NUMERIC(3,2) 적재에서 터진다 — 그때는 트랜잭션이
            //    롤백되며 후보가 ANALYZING 에 박히고, 원인이 「DB 오류」로 보인다
            throw new AnalysisRejectedException("confidence 가 0.00~1.00 밖입니다: " + confidence);
        }
        confidence = confidence.setScale(CONFIDENCE_SCALE, RoundingMode.HALF_UP);

        // 🔴 유일한 생성 경로가 스크럽을 탄다 — S-4. 모델이 프롬프트의 토큰을 되뱉을 수 있다
        summary = TokenRedactor.redact(requireText(summary, "summary"));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new AnalysisRejectedException(field + " 가 비어 있습니다");
        }
        return value.trim();
    }

    /**
     * 🔴 <b>요약 본문을 찍지 않는다.</b> 스크럽했더라도 대상 저장소에서 온 텍스트가 섞여 있고,
     * 로그 인젝션 경로이기도 하다 — {@code logging.md}.
     */
    @Override
    public String toString() {
        return "IssueAnalysis[category=%s, difficulty=%s, feasible=%s, confidence=%s, summarySize=%d]"
                .formatted(category, difficulty, implementationFeasible, confidence, summary.length());
    }
}
