package com.ossagent.candidate.domain;

/**
 * 착수가 <b>일시 장애로 미뤄졌다</b> — 후보는 {@code SELECTED} 로 되돌아갔다 (#98 · S-6).
 *
 * <p>{@code FAILED} 가 아니다. 이미지 없음·데몬 다운·LLM 5xx·clone 끊김은 후보의 코드와 무관하고
 * 준비되면 같은 요청이 성공한다. 웹 층은 이것을 <b>503 + {@code Retry-After}</b> 로 번역한다 —
 * 레이트리밋과 같은 취급이다: 실패가 아니라 <b>지연</b>.
 *
 * <p>⚠️ 메시지는 우리 어휘뿐이다. 원인 예외의 본문(URL·헤더·빌드 출력)을 싣지 않는다 (S-4).
 */
public class ImplementationDeferredException extends RuntimeException {

    private final Long candidateId;

    public ImplementationDeferredException(Long candidateId, String reason) {
        super("착수를 미뤘습니다 candidateId=" + candidateId + " — " + reason
                + ". 후보는 SELECTED 로 되돌아갔습니다. 준비되면 다시 착수하세요");
        this.candidateId = candidateId;
    }

    public Long candidateId() {
        return candidateId;
    }
}
