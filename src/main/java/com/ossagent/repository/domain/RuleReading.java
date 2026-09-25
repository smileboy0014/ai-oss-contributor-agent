package com.ossagent.repository.domain;

/**
 * 규약 문서를 읽고 내린 판정. <b>값이고 엔티티가 아니다.</b>
 *
 * <p>🔴 {@code aiContributionAllowed} 가 {@code Boolean} 이고 {@code null} 을 허용하는 것이 핵심이다 —
 * S-5. {@code null} 은 <b>판정 불가 = 보류</b>이고 「허용」이 아니다. 원시 {@code boolean} 으로
 * 바꾸면 판정 실패가 {@code false} 로 뭉개지고, 기본값 {@code true} 로 두면 금지 저장소를
 * 기본 허용해 버린다. <b>어느 쪽이든 기본값이 곧 위반</b>이 된다.
 *
 * <p>나머지 필드는 <b>모르면 {@code null}</b> 이다. AI 허용 여부와 달리 이것들은 게이트가 아니라
 * 규약 정보라, 모른다고 저장소를 보류시키지 않는다.
 *
 * <p>⚠️ {@code javaVersion}·{@code buildCommand}·{@code testCommand} 는 문서 본문에서만 온다.
 * 빌드 설정 파싱은 #15 이므로 <b>이 단계에서는 대체로 {@code null}</b> 이다.
 *
 * @param aiContributionAllowed 🔴 {@code null} = 보류
 * @param rules                 스크럽된 규약 요약. 영속화 대상
 */
public record RuleReading(
        Boolean aiContributionAllowed,
        String javaVersion,
        String buildCommand,
        String testCommand,
        boolean issueReferenceRequired,
        boolean signoffRequired,
        boolean testsRequired,
        ScrubbedRules rules) {

    public RuleReading {
        rules = rules == null ? ScrubbedRules.none() : rules;
    }

    /**
     * 판정이 서지 않았다 — LLM 이 답하지 못했거나 응답을 파싱할 수 없었다.
     *
     * <p>모델이 애매하게 말한 것을 「허용」으로 읽지 않는다. 이 제품의 품질 축은
     * 「좋은 코드를 쓰는가」가 아니라 <b>「나쁜 결과를 걸러내는가」</b>다.
     */
    public static RuleReading undetermined() {
        return new RuleReading(null, null, null, null, false, false, false, ScrubbedRules.none());
    }

    /** 읽을 문서가 하나도 없었다 — 금지 표기가 존재할 수 없으므로 허용이다 (Q-8 확정 ①). */
    public static RuleReading allowedByAbsence() {
        return new RuleReading(true, null, null, null, false, false, false, ScrubbedRules.none());
    }

    public boolean isUndetermined() {
        return aiContributionAllowed == null;
    }

    public boolean isForbidden() {
        return Boolean.FALSE.equals(aiContributionAllowed);
    }
}
