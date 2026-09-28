package com.ossagent.candidate.domain;

/**
 * 생성된 변경분에 <b>시크릿 패턴</b>이 있어 기록을 거부했다 — #96 · S-4.
 *
 * <h2>🔴 가려서 저장하지 않고 거부한다</h2>
 *
 * <p>{@code GeneratedChange.diff} 는 <b>정본 패치</b>다 — PR 게이트가 upstream 을 새로 받아 그것을
 * 다시 입힌다. 가린 채 저장하면 컨텍스트 줄이 바뀌어 hunk 가 맞지 않거나, PEM 블록 스크럽이
 * 여러 줄을 한 토큰으로 접어 <b>패치 구조 자체가 깨진다.</b> 그러면 후보가
 * {@code READY_FOR_PR} 에 영구히 고착된다.
 *
 * <p>가리지 않고 저장하는 것은 S-4 위반이다. 남는 길은 하나 — <b>그 diff 는 기록하지 않는다.</b>
 * 모델이 토큰 모양의 문자열을 코드에 넣었다는 뜻이고, 그것은 사람이 봐야 할 일이다.
 * {@code RetryPolicy} 는 이 예외를 화이트리스트 밖으로 두어 {@code Stop} 으로 보낸다.
 *
 * <p>⚠️ 메시지에 <b>어디가 걸렸는지를 싣지 않는다.</b> 그 조각이 곧 시크릿이다.
 */
public class DiffContainsSecretException extends RuntimeException {

    public DiffContainsSecretException(Long candidateId) {
        super("생성된 변경분에 시크릿 패턴이 있어 기록하지 않는다 candidateId=" + candidateId
                + " — 가려서 저장하면 패치가 깨지고, 가리지 않으면 S-4 위반이다");
    }
}
