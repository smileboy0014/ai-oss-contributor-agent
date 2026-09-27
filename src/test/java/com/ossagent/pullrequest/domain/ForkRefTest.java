package com.ossagent.pullrequest.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ossagent.repository.domain.RepositoryCoordinates;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S-1 — 쓰기 대상은 사용자 Fork 뿐이다.
 *
 * <p>⚠ <b>이 타입이 S-1 의 방어라고 읽지 않는다.</b> 유일한 방어는 쓰기 직전 어설션
 * ({@code GitHubWriteClientTest} 가 본다)이고, 여기는 <b>호출부에서 일찍 드러내는</b> 층이다.
 * 두 테스트가 같은 규칙을 두 층에서 보는 것은 중복이 아니다 — 한쪽을 지워도 다른 쪽이
 * 남아야 하는 관계다.
 */
class ForkRefTest {

    private static final String FORK_OWNER = "smileboy0014";

    @Test
    @DisplayName("push 대상이 Fork 가 아니면 중단한다")
    void push_대상이_Fork가_아니면_중단한다_S1() {
        RepositoryCoordinates upstream = RepositoryCoordinates.parse("spring-projects/spring-kafka");

        assertThatThrownBy(() -> ForkRef.of(upstream, FORK_OWNER))
                .as("남의 저장소 히스토리 오염은 되돌릴 수 없다. 권한이 있었다면 진짜로 push 된다")
                .isInstanceOf(UpstreamWriteAttemptException.class)
                .hasMessageContaining("spring-projects");
    }

    @Test
    @DisplayName("Fork owner 가 비어 있으면 「비교할 것이 없으니 통과」가 아니라 중단이다")
    void Fork_owner가_비면_중단한다_S1() {
        RepositoryCoordinates anything = RepositoryCoordinates.parse("spring-projects/spring-kafka");

        for (String unset : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> ForkRef.of(anything, unset))
                    .as("설정 누락은 「제한 없음」이 아니라 「판정 불가」다. "
                            + "막아서 잃는 것은 push 실패(되돌릴 수 있다)이고, "
                            + "통과시켜 잃는 것은 upstream 오염(되돌릴 수 없다)이다")
                    .isInstanceOf(UpstreamWriteAttemptException.class);
        }
    }

    @Test
    @DisplayName("owner 가 같으면 대소문자가 달라도 통과한다 — 같은 계정이다")
    void 대소문자가_달라도_같은_owner다() {
        RepositoryCoordinates fork = new RepositoryCoordinates("SmileBoy0014", "spring-kafka");

        assertThatCode(() -> ForkRef.of(fork, "smileboy0014"))
                .as("GitHub 로그인은 표기를 보존하되 비교는 대소문자를 무시한다. "
                        + "엄격 비교는 정당한 push 만 막고 안전을 사지 못한다 — "
                        + "대소문자가 다른 upstream 같은 것은 존재하지 않는다")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("owner 가 다르면 앞뒤 공백을 지워도 여전히 다르다")
    void 공백을_지워도_다른_owner는_막힌다_S1() {
        RepositoryCoordinates upstream = new RepositoryCoordinates("spring-projects", "spring-kafka");

        assertThatThrownBy(() -> ForkRef.of(upstream, "  " + FORK_OWNER + "  "))
                .isInstanceOf(UpstreamWriteAttemptException.class);
    }

    @Test
    @DisplayName("Fork 좌표면 값이 만들어지고 좌표를 그대로 노출한다")
    void Fork_좌표는_통과한다() {
        RepositoryCoordinates fork = new RepositoryCoordinates(FORK_OWNER, "spring-kafka");

        ForkRef ref = ForkRef.of(fork, FORK_OWNER);

        assertThat(ref.owner()).isEqualTo(FORK_OWNER);
        assertThat(ref.name()).isEqualTo("spring-kafka");
        assertThat(ref.fullName()).isEqualTo(FORK_OWNER + "/spring-kafka");
    }

    @Test
    @DisplayName("sameOwner 는 어느 쪽이 null 이어도 같다고 하지 않는다")
    void null은_같지_않다_S1() {
        assertThat(ForkRef.sameOwner(null, FORK_OWNER)).isFalse();
        assertThat(ForkRef.sameOwner(FORK_OWNER, null)).isFalse();
        assertThat(ForkRef.sameOwner(null, null))
                .as("둘 다 모르는 것을 「같다」로 읽으면 어설션이 모르는 값에 대해 항상 통과한다")
                .isFalse();
    }
}
