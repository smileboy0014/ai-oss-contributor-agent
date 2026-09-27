package com.ossagent.pullrequest.domain;

import com.ossagent.repository.domain.RepositoryCoordinates;
import java.util.Locale;

/**
 * <b>쓰기가 허용된</b> 저장소 좌표 — S-1.
 *
 * <h2>이 타입은 「막는 것」이 아니라 「일찍 드러내는 것」이다</h2>
 *
 * <p>🔴 <b>이것을 방어로 세지 않는다.</b> {@code safety-boundaries.md} S-1 이 못 박았듯
 * {@code spring-boot-starter-web} 이 {@code RestClient.Builder} 를 자동설정 빈으로 올리므로
 * <b>아무 컴포넌트나</b> {@code builder.build().post(...)} 를 할 수 있다. 「좁은 표면」은
 * 의도 표기이지 강제력이 아니다.
 *
 * <p><b>유일한 방어는 쓰기 직전 owner 어설션</b>이고 그것은 {@code GitHubWriteClient} 에 있다.
 * 이 타입의 값은 그 어설션을 <b>호출부로 앞당기는</b> 데 있다 — 좌표가 잘못됐다는 사실이
 * HTTP 를 타기 전에, 스택이 얕을 때 드러난다.
 *
 * <p>⚠️ 그래서 <b>이 타입이 있으니 어댑터 어설션은 생략해도 된다</b>는 판단은 정확히 반대다.
 * 둘 중 지워야 한다면 이쪽이지 저쪽이 아니다.
 *
 * <h2>왜 대소문자를 무시하는가</h2>
 *
 * <p>GitHub 로그인·저장소명은 <b>표기는 보존하되 비교는 대소문자를 무시</b>한다.
 * {@code GITHUB_FORK_OWNER=SmileBoy0014} 로 적어 두고 API 응답이 {@code smileboy0014} 로
 * 와도 <b>같은 계정</b>이다. 엄격 비교로 두면 정당한 push 가 막히는데, 그 막힘은
 * <b>안전을 사지 못한다</b> — 대소문자가 다른 upstream 같은 것은 존재하지 않기 때문이다.
 *
 * <p>🔴 {@link Locale#ROOT} 를 명시한다. 터키어 로케일에서 {@code "I".toLowerCase()} 는
 * {@code "ı"} 가 되어 <b>같은 계정이 다르게 판정된다</b>. 기본 로케일에 맡기면 어설션 결과가
 * 배포 환경에 따라 달라진다.
 */
public record ForkRef(RepositoryCoordinates coordinates) {

    public ForkRef {
        if (coordinates == null) {
            throw new UpstreamWriteAttemptException("쓰기 대상 좌표가 없습니다");
        }
    }

    /**
     * <b>유일한 생성 경로.</b> owner 가 Fork owner 와 같을 때만 값이 만들어진다.
     *
     * @param coordinates 쓰기 대상 후보. upstream 좌표가 들어오는 것이 <b>막으려는 상황</b>이다
     * @param forkOwner   {@code github.fork-owner} ({@code GITHUB_FORK_OWNER})
     * @throws UpstreamWriteAttemptException owner 불일치 · Fork owner 미설정
     */
    public static ForkRef of(RepositoryCoordinates coordinates, String forkOwner) {
        if (coordinates == null) {
            throw new UpstreamWriteAttemptException("쓰기 대상 좌표가 없습니다");
        }
        // 🔴 비어 있으면 「비교할 것이 없으니 통과」가 되어 어설션이 통째로 무력해진다.
        //    설정 누락은 「제한 없음」이 아니라 「판정 불가」다 — 판정 불가는 막는 쪽이다.
        //    여기서 막아서 잃는 것(push 실패)은 되돌릴 수 있고,
        //    통과시켜서 잃는 것(upstream 오염)은 되돌릴 수 없다.
        if (forkOwner == null || forkOwner.isBlank()) {
            throw new UpstreamWriteAttemptException(
                    "Fork owner 가 설정되지 않아 쓰기 대상을 판정할 수 없습니다 (github.fork-owner)");
        }
        if (!sameOwner(coordinates.owner(), forkOwner)) {
            throw new UpstreamWriteAttemptException(
                    "쓰기 대상이 Fork 가 아닙니다: 대상 owner=%s · Fork owner=%s"
                            .formatted(coordinates.owner(), forkOwner.trim()));
        }
        return new ForkRef(coordinates);
    }

    /** 이 좌표가 Fork owner 의 것인가. 어댑터 어설션이 같은 규칙을 쓰도록 여기 둔다. */
    public static boolean sameOwner(String owner, String forkOwner) {
        if (owner == null || forkOwner == null) {
            return false;
        }
        return owner.trim().toLowerCase(Locale.ROOT)
                .equals(forkOwner.trim().toLowerCase(Locale.ROOT));
    }

    public String owner() {
        return coordinates.owner();
    }

    public String name() {
        return coordinates.name();
    }

    public String fullName() {
        return coordinates.fullName();
    }

    @Override
    public String toString() {
        return fullName();
    }
}
