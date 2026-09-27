package com.ossagent.candidate.domain;

/**
 * 🔴 실행기가 준비되지 않아 <b>착수를 시작하지 않았다</b> — #18 · S-6.
 *
 * <h2>왜 전이 <b>전에</b> 막는가</h2>
 *
 * <p>상태머신이 {@code IMPLEMENTING.allowedNext = {TESTING, FAILED}} 이고
 * <b>{@code FAILED} 는 종단</b>이다. 실행기가 없는 채로 전이하면 그 후보는
 * <b>영구히 죽는다</b> — 다시 고르려면 재분석이 필요하고, 사람이 버튼 한 번으로
 * 되돌릴 수 없는 일을 하게 된다.
 *
 * <p>그래서 「일단 전이하고 실패로 떨어뜨린다」가 아니라 <b>시작하지 않는다.</b>
 * 후보는 {@code SELECTED} 그대로 남고, 실행기가 들어오면 같은 버튼이 정상 동작한다.
 *
 * <h2>⚠️ 이것과 검증기의 실패 보고 는 다른 층이다</h2>
 *
 * <table border="1">
 *   <caption>둘의 차이</caption>
 *   <tr><th></th><th>언제</th><th>후보에 일어나는 일</th></tr>
 *   <tr><td><b>이 예외</b></td><td>착수 <b>시작 전</b></td>
 *       <td>아무 일도 없다 — {@code SELECTED} 유지</td></tr>
 *   <tr><td>검증기의 실패 보고</td><td>코드를 만든 <b>뒤</b></td>
 *       <td>{@code FAILED} — 되돌릴 수 없지만 <b>작업은 실제로 있었다</b></td></tr>
 * </table>
 *
 * <p>둘 다 fail-closed 지만 <b>비용이 다르다.</b> 아무것도 안 했으면 아무것도 태우지 않는다.
 *
 * <p>HTTP 로는 <b>503</b> 이다 — 요청이 틀린 것이 아니라 <b>지금 할 수 없는 것</b>이다.
 */
public class ImplementationNotReadyException extends RuntimeException {

    public ImplementationNotReadyException(String missing) {
        super("착수 실행기가 준비되지 않아 시작하지 않았습니다 — " + missing
                + ". 후보는 그대로 남아 있으니 배선이 끝난 뒤 다시 시도하세요.");
    }
}
