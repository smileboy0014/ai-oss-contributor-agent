package com.ossagent.agent.adapter.out.llm;

import com.ossagent.agent.domain.PromptScrubber;
import com.ossagent.support.secret.TokenRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 프롬프트의 토큰 패턴을 {@link TokenRedactor} 로 지운다 — S-4.
 *
 * <p><b>패턴을 여기서 새로 만들지 않는다.</b> {@code TokenRedactor} 가 이미 같은 집합
 * ({@code gh[pousr]_} · {@code github_pat_} · {@code sk-ant-} · {@code AKIA} · {@code xox} ·
 * {@code Authorization} 헤더)을 갖고 있다. 두 벌을 두면 한쪽만 갱신되는 순간 구멍이 생기고,
 * 그 구멍이 <b>모델 제공자로 나가는 쪽</b>일 수 있다.
 *
 * <p>적중해도 <b>중단하지 않고 치환 후 진행</b>한다. 중단하면 파이프라인이 멈추는데,
 * 대상 저장소가 시크릿을 커밋해 둔 것은 흔한 일이라 멈춤이 상시화된다.
 *
 * <p>⚠️ 로그에 <b>무엇이 걸렸는지 남기지 않는다.</b> 적중 사실과 개수만 남긴다 —
 * 걸린 값을 찍으면 스크럽을 한 의미가 사라진다.
 *
 * <p>파일 단위 배제({@code .env} · {@code *.pem} · {@code credentials} 류)는 이 클래스의 몫이
 * 아니다. 저장소 컨텍스트를 모으는 단계에서 걸러야 하고 #28 이 맡는다. 여기는 <b>송신 직전
 * 마지막 그물</b>이다.
 */
public class TokenRedactingPromptScrubber implements PromptScrubber {

    private static final Logger log = LoggerFactory.getLogger(TokenRedactingPromptScrubber.class);

    @Override
    public String scrub(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String redacted = TokenRedactor.redact(text);
        if (!redacted.equals(text)) {
            log.warn("프롬프트에서 시크릿 패턴을 치환했다 — 송신 전 차단됨 (원문 길이={})", text.length());
        }
        return redacted;
    }
}
