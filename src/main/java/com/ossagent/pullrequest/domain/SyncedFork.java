package com.ossagent.pullrequest.domain;

/**
 * 「이 Fork 의 기준 브랜치를 upstream 과 맞춰 보았고, 결과가 이것이다」는 <b>통행증</b>.
 *
 * <h2>🔴 왜 값이 아니라 통행증인가</h2>
 *
 * <p>초안은 {@code syncWithUpstream} 이 {@link SyncOutcome} 을 <b>돌려주기만</b> 하고
 * 「호출자가 알아서 본다」고 적었다. 그 근거로 <b>「enum 이라 {@code switch} 가 누락을
 * 드러낸다」</b>를 들었는데, <b>그것은 거짓이다.</b>
 *
 * <p>exhaustive 검사는 호출자가 {@code switch} 를 <b>쓸 때만</b> 작동한다. 실제 위험은
 * {@code publisher.syncWithUpstream(fork, base);} <b>한 줄로 반환값을 버리는 것</b>이고,
 * 컴파일러도 ArchUnit 도 그것을 보지 않는다. 그러면 {@link SyncOutcome#CONFLICT} 위에 커밋이
 * 쌓이고 <b>머지 불가능한 PR 이 남의 저장소에 열린다.</b>
 *
 * <p><b>선례가 있다</b> — {@code PolicyClearance} 를 {@code startImplementing} 이 인자로 요구해
 * S-5 의무를 javadoc 에서 컴파일러로 옮긴 수법(#24). 여기도 같다:
 * {@link PublishRequest} 가 이 타입을 요구하므로 <b>동기화를 보지 않고 publish 하는 것이
 * 표현 불가능</b>하다.
 *
 * <h2>⚠️ 강제하는 것은 「호출」이지 「판단」이 아니다</h2>
 *
 * <p>{@code CONFLICT} 여도 publish 는 가능하다. 진행 여부는 PR 을 만드는 주체가 정할 일이고
 * 이 도메인은 PR 을 만들지 않는다. 이 타입이 없애는 것은 <b>보지 않고 지나가는 경로</b>뿐이고,
 * 그 차이가 전부다. <b>더 강하게 적지 않는다</b> — 과장하면 다음 사람이 여기 기대어
 * #23 에서 판단을 생략한다.
 *
 * <p>🔴 <b>생성자를 공개하지만 발급처는 {@code ForkPublisher.syncWithUpstream} 하나여야 한다.</b>
 * 테스트 대역이 값을 만들어야 하므로 {@code sealed} 로 막지는 않았다. 운영 코드에서
 * 이 타입을 직접 만드는 곳이 생기면 <b>그 순간 통행증이 껍데기가 된다</b> —
 * {@code PolicyClearance} 가 {@code adapter/in} 경계를 넘지 않는 것과 같은 이유이고,
 * ArchUnit 이 그것을 고정한다.
 *
 * @param fork    맞춰 본 Fork
 * @param outcome 그 결과. {@link SyncOutcome#isAligned()} 가 아니면 호출자가 판단한다
 */
public record SyncedFork(ForkRef fork, SyncOutcome outcome) {

    public SyncedFork {
        if (fork == null) {
            throw new UpstreamWriteAttemptException("쓰기 대상 Fork 가 없습니다");
        }
        if (outcome == null) {
            throw new IllegalArgumentException("동기화 결과가 없습니다");
        }
    }

    public String fullName() {
        return fork.fullName();
    }
}
