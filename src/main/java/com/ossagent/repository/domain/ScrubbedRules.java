package com.ossagent.repository.domain;

import com.ossagent.support.ExternalText;
import com.ossagent.support.secret.TokenRedactor;

/**
 * 영속화해도 되는 규약 요약. <b>스크럽을 거쳐야만 만들 수 있다</b> — S-4.
 *
 * <h2>왜 값 타입으로 감싸나</h2>
 *
 * <p>{@code repository_policy.contribution_rules} 는 {@code @ExternalText(TARGET_REPOSITORY)} 로
 * <b>표시만</b> 돼 있다. 애노테이션은 스크럽을 실행하지 않는다.
 *
 * <p>#10 의 {@code PromptScrubber} 는 <b>LLM 송신 경로 전용</b>이다. 대상 저장소 원문을
 * 이 컬럼에 그대로 넣으면 스크럽을 <b>한 번도 타지 않고</b> DB 에 앉는다. 그리고 이 컬럼은
 * PR 본문 조립(#23)의 입력이 될 가능성이 높아, <b>유출 종착지가 대상 저장소의 공개 PR</b> 이 된다.
 *
 * <p>{@code String} 을 받는 setter 를 열어 두면 「이번엔 괜찮겠지」가 언젠가 들어온다.
 * {@link RepositoryPolicy} 가 이 타입만 받게 해서 <b>스크럽을 우회할 경로를 없앤다</b> —
 * {@code AnthropicLanguageModel} 이 생성자로 스크러버를 강제한 것과 같은 수법이다.
 *
 * <h2>무엇을 담나</h2>
 *
 * <p>LLM 판정의 <b>정규화 결과</b>다 — sign-off · 이슈 참조 · CLA · 테스트 필수 · PR 본문 형식
 * 요약과, <b>판정 근거가 된 경로 목록</b>. <b>원문을 담지 않는다.</b>
 * 원문이 필요하면 경로로 다시 읽으면 된다.
 */
public record ScrubbedRules(@ExternalText(ExternalText.Source.LLM_RESPONSE) String value) {

    /**
     * 유일한 생성 경로. 반드시 스크럽을 통과한다.
     *
     * <p>{@code TokenRedactor} 에 위임한다 — #6 이 가져온 그 구현이고, #10 의
     * {@code TokenRedactingPromptScrubber} 도 같은 것을 쓴다. <b>스크럽 규칙이 한 벌</b>이어야
     * 한쪽만 갱신되는 순간 생기는 구멍이 없다.
     */
    public static ScrubbedRules of(String raw) {
        return new ScrubbedRules(raw == null ? null : TokenRedactor.redact(raw));
    }

    /** 규약 요약이 없는 경우 — 보류이거나 읽을 문서가 없었다. */
    public static ScrubbedRules none() {
        return new ScrubbedRules(null);
    }

    public boolean isPresent() {
        return value != null && !value.isBlank();
    }

    /** 🔴 내용을 찍지 않는다. 스크럽했더라도 대상 저장소에서 온 텍스트다. */
    @Override
    public String toString() {
        return "ScrubbedRules[size=%d]".formatted(value == null ? 0 : value.length());
    }
}
