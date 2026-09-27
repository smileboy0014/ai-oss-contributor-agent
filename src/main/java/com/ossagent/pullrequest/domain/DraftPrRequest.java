package com.ossagent.pullrequest.domain;

import com.ossagent.repository.domain.RepositoryCoordinates;

/**
 * upstream 에 Draft PR 을 여는 요청.
 *
 * <h2>🔴 {@code draft} 파라미터가 없다 — S-2</h2>
 *
 * <p>{@code boolean draft} 를 두면 「언젠가 {@code false} 로 부르는 호출」이 생긴다.
 * {@code safety-boundaries.md} 가 「플래그를 두면 언젠가 켜진다」고 못 박은 그것이다.
 * draft 고정은 이 타입에 <b>자리를 만들지 않는 것</b>으로 표현하고, 전송 payload 쪽에서
 * 리터럴 {@code true} 로 한 번 더 고정한다.
 *
 * <h2>🔴 이 타입은 upstream 좌표를 든다 — {@link PublishRequest} 와 정반대다</h2>
 *
 * <p>저쪽은 {@link SyncedFork} 를 요구해 <b>upstream 좌표를 담은 요청을 표현 불가능</b>하게
 * 만들었다. 여기는 그럴 수 없다 — PR 은 <b>원리적으로</b> upstream 좌표로 간다
 * ({@code POST /repos/{upstream}/pulls}).
 *
 * <p>그래서 방어의 축이 다르다.
 *
 * <table border="1">
 *   <caption>무엇이 막나</caption>
 *   <tr><td>{@link PublishRequest}</td><td>타입이 막는다 — upstream 을 지목할 수 없다</td></tr>
 *   <tr><td><b>이 타입</b></td>
 *       <td>① upstream 히스토리를 바꾸지 않는 호출이라는 것 ② <b>사람 승인 게이트 뒤</b>에만
 *           놓인다는 것. 후자가 본체이고 그것은 {@code ApprovalGateArchitectureTest} 가 지킨다</td></tr>
 * </table>
 *
 * @param upstream 원본 좌표. 🔴 <b>PR 을 여는 것 말고는 아무것도 하지 않는다</b>
 * @param head     Fork 에 올라간 브랜치 — {@code owner:branch} 로 조립된다
 * @param base     upstream 의 기준 브랜치
 * @param title    제목
 * @param body     본문. 이미 스크럽된 값이다 (S-4)
 */
public record DraftPrRequest(RepositoryCoordinates upstream, PublishedBranch head,
                             BaseBranch base, PrTitle title, PrBody body) {

    public DraftPrRequest {
        if (upstream == null) {
            throw new DraftPrException("원본 저장소 좌표가 없습니다");
        }
        if (head == null) {
            throw new DraftPrException("PR 의 head 가 없습니다 — 먼저 Fork 에 push 해야 합니다");
        }
        if (base == null) {
            throw new DraftPrException("기준 브랜치가 없습니다");
        }
        if (title == null) {
            throw new DraftPrException("PR 제목이 없습니다");
        }
        if (body == null) {
            throw new DraftPrException("PR 본문이 없습니다");
        }
        // 🔴 head 가 upstream 자신이면 「우리 브랜치가 upstream 에 올라갔다」는 뜻이다.
        //    그 시점에 이미 S-1 이 깨진 것이고, 여기서 PR 을 열면 그 사실이 남의 저장소에
        //    드러나기까지 한다. 진행하지 않는다.
        if (head.fork().coordinates().equals(upstream)) {
            throw new UpstreamWriteAttemptException(
                    "PR 의 head 가 원본 저장소입니다 — Fork 가 아닌 곳에 브랜치가 올라갔습니다: "
                            + upstream.fullName());
        }
    }

    /** {@code owner:branch} — GitHub 이 교차 저장소 PR 의 {@code head} 에 쓰는 표기. */
    public String headRef() {
        return head.headRef();
    }

    /** 🔴 본문·제목을 노출하지 않는다 (S-4). */
    @Override
    public String toString() {
        return "DraftPrRequest[upstream=%s, head=%s, base=%s, body=%s]"
                .formatted(upstream.fullName(), headRef(), base, body);
    }
}
