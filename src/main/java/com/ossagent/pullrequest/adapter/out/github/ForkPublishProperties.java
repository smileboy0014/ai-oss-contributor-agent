package com.ossagent.pullrequest.adapter.out.github;

import com.ossagent.pullrequest.domain.CommitIdentity;
import java.time.Duration;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Fork 쓰기 설정 — S-1 · S-5.
 *
 * <p>🔴 {@link #owner} 가 <b>비어 있으면 모든 쓰기가 거부된다.</b> 「비교할 것이 없으니 통과」가
 * 되면 S-1 어설션이 통째로 무력해지므로, 판정 불가는 막는 쪽으로 간다 — 막아서 잃는 것은
 * push 실패(되돌릴 수 있다)이고 통과시켜 잃는 것은 upstream 오염(되돌릴 수 없다)이다.
 *
 * <p>⚠️ <b>기동은 실패시키지 않는다.</b> 쓰기를 한 번도 하지 않는 배포(읽기 전용 스캔만)가
 * 정상 시나리오이고, 거기서 기동을 막으면 설정되지 않은 값 때문에 되는 일까지 못 하게 된다.
 * 쓰기를 <b>시도하는 순간</b> 거부된다.
 *
 * <h2>왜 커밋 서명을 설정으로 받나</h2>
 *
 * <p>{@code GET /user} 로 받아올 수도 있지만 {@code public_repo} 스코프로는 <b>비공개 이메일을
 * 받지 못한다.</b> 그러면 DCO sign-off 가 {@code noreply} 주소로 나가 대상 저장소의 검사에서
 * 걸린다 — 무엇으로 서명할지는 사람이 정하는 값이다.
 *
 * @param owner            {@code GITHUB_FORK_OWNER}. 🔴 쓰기 대상 판정의 유일한 기준
 * @param readyTimeout     Fork 생성 후 준비될 때까지 기다리는 상한. {@code POST /forks} 는 202 라
 *                         저장소가 아직 없을 수 있다 — 무한 대기를 만들지 않는다
 * @param readyPollInterval 준비 확인 간격
 * @param authorName       커밋 author·committer 이름. sign-off 서명자
 * @param authorEmail      〃 이메일
 */
@ConfigurationProperties("github.fork")
public record ForkPublishProperties(
        String owner,
        Duration readyTimeout,
        Duration readyPollInterval,
        String authorName,
        String authorEmail) {

    // 🔴 GitHub 문서는 「5분 넘게 걸리면 지원에 문의하라」고 적는다 — 30초는 spring-kafka 첫 fork 에 모자랐다 (#107)
    private static final Duration DEFAULT_READY_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration DEFAULT_READY_POLL_INTERVAL = Duration.ofSeconds(5);

    public ForkPublishProperties {
        owner = owner == null ? "" : owner.trim();
        authorName = authorName == null ? "" : authorName.trim();
        authorEmail = authorEmail == null ? "" : authorEmail.trim();
        readyTimeout = readyTimeout == null ? DEFAULT_READY_TIMEOUT : readyTimeout;
        readyPollInterval = readyPollInterval == null ? DEFAULT_READY_POLL_INTERVAL
                : readyPollInterval;
        if (readyTimeout.isNegative() || readyTimeout.isZero()) {
            throw new IllegalArgumentException("github.fork.ready-timeout 은 0 보다 커야 합니다");
        }
        if (readyPollInterval.isNegative() || readyPollInterval.isZero()) {
            throw new IllegalArgumentException("github.fork.ready-poll-interval 은 0 보다 커야 합니다");
        }
        if (readyPollInterval.compareTo(readyTimeout) > 0) {
            // 간격이 상한보다 크면 한 번도 확인하지 못하고 끝난다
            throw new IllegalArgumentException(
                    "github.fork.ready-poll-interval 이 ready-timeout 보다 클 수 없습니다");
        }
    }

    /** 기본값으로만 채운 설정 — 테스트·기본 조립. */
    public static ForkPublishProperties defaults() {
        return new ForkPublishProperties(null, null, null, null, null);
    }

    /**
     * 서명자. 둘 중 하나라도 비어 있으면 <b>비어 있는 것으로</b> 본다.
     *
     * <p>🔴 반쪽짜리 서명을 만들지 않는다. 이름만 있고 이메일이 없으면 sign-off 형식이 깨지는데,
     * DCO 봇은 깨진 트레일러를 <b>서명 없음</b>으로 읽는다 — 「서명했는데 안 한 것으로 처리되는」
     * 상태가 가장 진단하기 어렵다. {@code CommitMessage} 가 필요할 때 명시적으로 던지게 한다.
     */
    public Optional<CommitIdentity> commitIdentity() {
        if (authorName.isBlank() || authorEmail.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new CommitIdentity(authorName, authorEmail));
    }
}
