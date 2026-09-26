package com.ossagent.repository.domain;

/**
 * 「이 저장소의 기여 규약을 읽었고 <b>AI 기여가 허용</b>이었다」는 통행증 — #24 · S-5.
 *
 * <h2>🔴 왜 값 타입인가 — javadoc 은 게이트가 아니다</h2>
 *
 * <p>{@code ContributionCandidate.startImplementing} 이 S-5 의 문이다 —
 * 「{@code RepositoryPolicy} 없이 구현 단계로 넘어가지 않는다」를 지키는 지점이 거기다.
 * 그런데 정책 데이터는 {@code repository} 애그리거트에 있어 {@code candidate} 도메인에서
 * 읽으면 규율 ④ 위반이고, 그래서 #12 까지의 강제는 <b>javadoc 한 줄</b>뿐이었다.
 *
 * <p>이 타입을 인자로 요구하면 <b>확인 없이 호출하는 것이 컴파일되지 않는다.</b>
 * 의무가 문서에서 컴파일러로 옮겨간다.
 *
 * <h2>만드는 곳이 하나다</h2>
 *
 * <p>생성자가 <b>패키지 가시성</b>이라 {@code com.ossagent.repository.domain} 밖에서는
 * 만들 수 없고, 그 안에서 부르는 것은 {@link RepositoryPolicy#clearance()} 뿐이다.
 * 그쪽이 {@code allowsContribution()} 이 참일 때만 발급한다.
 *
 * <p>바깥으로 나가는 유일한 경로는
 * {@code AnalyzeRepositoryPolicyUseCase.clearanceFor(repositoryId)} 다. 그래서 받은
 * 통행증의 {@link #repositoryId()} 는 <b>항상 조회한 그 id</b> 이고,
 * 「다른 저장소의 통행증을 들고 왔다」를 호출자가 따로 검증할 필요가 없다.
 *
 * <p>⚠️ <b>「설계상 발생 불가」가 아니다.</b> 패키지 가시성은 봉인이 아니다 —
 * 이 패키지에 클래스를 하나 추가하면 누구나 생성자를 부를 수 있다(JPMS 를 쓰지 않는다).
 * 테스트 픽스처에는 오히려 유용하다. 다만 <b>과장된 안전 표현은 다음 사람이 확인을
 * 건너뛰게 하므로</b> 정확히 적는다 — S-1 이 「쓰기 메서드를 만들지 않았으니 안전하다」를
 * 방어로 세지 않기로 한 것과 같은 이유다.
 *
 * <h2>⚠️ 두 가지를 하지 않는다</h2>
 *
 * <ol>
 *   <li>🔴 <b>{@code adapter/in} 경계를 넘지 않는다.</b> 컨트롤러 파라미터나 요청 바디로
 *       받는 순간 <b>외부가 통행증을 주입</b>할 수 있고, 그러면 게이트가 껍데기가 된다</li>
 *   <li><b>오래 들고 다니지 않는다.</b> 이것은 <b>스냅샷</b>이라 발급과 사용 사이에 정책이
 *       바뀔 수 있다(TOCTOU). 같은 UseCase·같은 트랜잭션 안에서 쓰는 것을 전제한다</li>
 * </ol>
 */
public final class PolicyClearance {

    private final Long repositoryId;

    /**
     * 🔴 <b>패키지 가시성.</b> {@link RepositoryPolicy#clearance()} 만 부른다.
     *
     * <p>{@code record} 로 두지 않은 이유 — record 의 정본 생성자는 <b>record 자신의 접근
     * 수준 이상</b>이어야 한다. {@code public record} 면 생성자도 반드시 {@code public} 이고,
     * 그러면 {@code new PolicyClearance(42L)} 이 어디서든 컴파일돼 이 클래스의 존재 이유가
     * 사라진다.
     */
    PolicyClearance(Long repositoryId) {
        if (repositoryId == null) {
            throw new IllegalArgumentException("통행증에는 저장소 식별자가 필요하다");
        }
        this.repositoryId = repositoryId;
    }

    public Long repositoryId() {
        return repositoryId;
    }

    @Override
    public String toString() {
        return "PolicyClearance[repositoryId=%d]".formatted(repositoryId);
    }
}
