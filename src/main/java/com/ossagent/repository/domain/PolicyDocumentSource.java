package com.ossagent.repository.domain;

import java.util.List;

/**
 * 규약 후보 문서를 모아 오는 <b>능력</b>. 🔴 <b>수집 실패로 예외를 던지지 않는다.</b>
 *
 * <h2>왜 {@code RepositorySource} 를 직접 쓰지 않나</h2>
 *
 * <p>{@code RepositorySource.fetchFile} 은 읽기 실패를 <b>예외로</b> 전파한다 — 그것이 #6 의
 * 계약이고 옳다. 하지만 규약 판정은 「어느 경로를 못 읽었는가」를 <b>값으로</b> 알아야 한다.
 * 한 경로가 5xx 라고 나머지 12경로 수집을 포기할 수 없고, 무엇이 실패했는지가 곧
 * 보류 사유이기 때문이다.
 *
 * <p>예외를 UseCase 가 직접 잡으면 <b>application 이 GitHub 예외 타입에 묶인다.</b>
 * 그래서 번역을 어댑터로 내린다 — #10 에서 SDK 예외를 {@code LlmFailureReason} 으로
 * 옮긴 것과 같은 모양이다. {@code RepositorySource} 계약은 <b>건드리지 않고 위에 얹는다.</b>
 *
 * <h2>구현이 반드시 지키는 것</h2>
 *
 * <ol>
 *   <li>🔴 <b>fail-closed</b> — 분류하지 못하는 예외도 {@link UnreadableReason#UNKNOWN} 으로
 *       {@link DocumentFetchOutcome#UNREADABLE} 이 된다. 타입을 쫓아가는 방식은 새 예외마다
 *       구멍이 난다. S-5 에서 가르는 선은 「무슨 오류인가」가 아니라 <b>「읽었는가」</b>다</li>
 *   <li><b>{@code Optional.empty()} 만 {@link DocumentFetchOutcome#ABSENT}</b> 다 —
 *       #6 계약상 그것은 404 하나뿐이다. 다른 실패를 여기로 넣으면 「규약 없음 → 허용」 오역이 된다</li>
 *   <li><b>상한을 넘으면 절단하지 않고</b> {@link UnreadableReason#TRUNCATED} 로 둔다 —
 *       잘린 뒷부분에 금지 문구가 있었는지 판정할 방법이 없다</li>
 *   <li>🔴 <b>문서 내용을 로그에 남기지 않는다</b> — 대상 저장소가 시크릿을 커밋해 뒀을 수 있다</li>
 * </ol>
 *
 * <p><b>트랜잭션 밖에서 호출한다.</b> 경로 수만큼 대외 호출이 난다.
 */
public interface PolicyDocumentSource {

    /**
     * 후보 경로를 모두 시도하고 각각의 결과를 돌려준다.
     *
     * @return 경로 수만큼의 {@link FetchedDocument}. <b>실패해도 예외가 아니라 값</b>이다
     */
    RepositoryDocuments collect(RepositoryCoordinates coordinates, List<PolicyDocumentPath> paths);
}
