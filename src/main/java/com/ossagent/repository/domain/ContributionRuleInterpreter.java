package com.ossagent.repository.domain;

/**
 * 수집된 규약 문서를 읽고 판정하는 <b>능력</b>.
 *
 * <h2>왜 {@code LanguageModel} 을 직접 쓰지 않나</h2>
 *
 * <p>{@code LanguageModel} 은 {@code agent} 의 능력이다. {@code repository/domain} 이 그것을
 * import 하면 <b>도메인이 다른 도메인의 기술 관심사</b>(프롬프트 조립·토큰·재시도·모델 선택)에
 * 묶인다. {@code repository} 가 알아야 하는 것은 「규약 문서를 주면 판정을 돌려준다」뿐이다.
 *
 * <p>그래서 자기 말로 능력을 선언하고 어댑터가 갈아 끼운다 — #10 이 {@code AgentRunRecorder} 를
 * 뒤집은 것과 같은 모양이다. 규칙 기반 판정으로 바꾸거나 LLM 을 빼도 {@code repository} 는 그대로다.
 *
 * <h2>구현이 반드시 지키는 것</h2>
 *
 * <ol>
 *   <li><b>판정하지 못하면 {@link RuleReading#undetermined()}</b> — 예외를 던지거나 임의로
 *       허용하지 않는다. 판정 불가는 <b>보류</b>이고 「허용」이 아니다 (S-5)</li>
 *   <li><b>모델 응답을 그대로 진실로 쓰지 않는다</b> — 파싱과 검증이 어댑터에서 끝난다.
 *       파싱 실패는 보류다</li>
 *   <li><b>영속화할 요약은 {@link ScrubbedRules} 로만</b> 만든다 — 원문이 DB 로 새지 않게 (S-4)</li>
 * </ol>
 *
 * <p>구현체는 {@code adapter/out/llm} 에 <b>기술 이름</b>으로 둔다 —
 * {@code LlmContributionRuleInterpreter}. 규율 ③.
 *
 * <p><b>트랜잭션 밖에서 호출한다.</b> LLM 호출이다.
 */
public interface ContributionRuleInterpreter {

    /**
     * 읽은 문서들로 규약을 판정한다.
     *
     * <h2>🔴 실패 두 가지를 가른다</h2>
     *
     * <table border="1">
     *   <caption>실패 처리</caption>
     *   <tr><th>무엇</th><th>어떻게</th><th>왜</th></tr>
     *   <tr><td><b>판정 불가</b> — 모델이 답했지만 못 믿겠다(파싱 실패 · 스키마 위반 · UNCLEAR)</td>
     *       <td>{@link RuleReading#undetermined()} <b>반환</b></td>
     *       <td>재시도해도 같다. 보류로 굳히고 사람이 본다</td></tr>
     *   <tr><td><b>일시적 실패</b> — 타임아웃 · 레이트리밋 · 5xx</td>
     *       <td>{@code LlmTransientException} <b>전파</b></td>
     *       <td>재시도하면 달라진다. 호출자가 <b>아무것도 쓰지 않고 중단</b>해야 한다 —
     *           보류로 만들면 저절로 풀렸을 일이 영구 보류가 된다(#24 미구현)</td></tr>
     * </table>
     *
     * <p>문서 수집({@link PolicyDocumentSource})이 같은 원리로 갈라 둔 것과 짝이다.
     *
     * @param documents 수집 결과. 구현은 {@link RepositoryDocuments#readDocuments()} 만 본다
     * @return 판정. 판정이 서지 않으면 {@link RuleReading#undetermined()}
     */
    RuleReading interpret(RepositoryCoordinates coordinates, RepositoryDocuments documents);
}
