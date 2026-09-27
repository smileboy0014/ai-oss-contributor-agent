package com.ossagent.pullrequest.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ossagent.repository.domain.ContributionConstraints;
import com.ossagent.repository.domain.RepositoryCoordinates;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 올릴 변경의 상한과 통행증 요구. */
class PublishRequestTest {

    private static final String FORK_OWNER = "smileboy0014";

    @Test
    @DisplayName("동기화를 거치지 않은 Fork 로는 요청을 만들 수 없다 — 타입이 막는다")
    void 동기화_통행증을_요구한다() {
        // 🔴 이 테스트는 컴파일되는 것 자체가 증거다. PublishRequest 가 ForkRef 를 받으면
        //    「동기화를 보지 않고 publish」가 표현 가능해지고, 그때 이 줄이 컴파일되지 않는다.
        SyncedFork synced = new SyncedFork(fork(), SyncOutcome.MERGED);

        PublishRequest request = request(synced, List.of(FileChange.modified("a.java", "x")));

        assertThat(request.fork().outcome()).isEqualTo(SyncOutcome.MERGED);
        assertThat(request.forkRef()).isEqualTo(fork());
    }

    @Test
    @DisplayName("CONFLICT 여도 요청은 만들어진다 — 강제하는 것은 호출이지 판단이 아니다")
    void 갈라진_Fork도_요청은_만들어진다() {
        SyncedFork conflicted = new SyncedFork(fork(), SyncOutcome.CONFLICT);

        PublishRequest request = request(conflicted, List.of(FileChange.modified("a.java", "x")));

        assertThat(request.fork().outcome())
                .as("진행 여부는 PR 을 만드는 주체가 정한다. 여기서 막으면 그 판단을 뺏는 것이다")
                .isEqualTo(SyncOutcome.CONFLICT);
    }

    @Test
    @DisplayName("빈 변경은 거부한다")
    void 빈_변경을_거부한다() {
        assertThatThrownBy(() -> request(synced(), List.of()))
                .as("빈 커밋을 push 하면 PR 이 빈 채로 열린다")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("같은 경로가 두 번 실리면 거부한다")
    void 중복_경로를_거부한다() {
        List<FileChange> duplicated = List.of(
                FileChange.modified("a.java", "1"),
                FileChange.modified("a.java", "2"));

        assertThatThrownBy(() -> request(synced(), duplicated))
                .as("git tree 에서 뒤엣것이 이기는데 그 「뒤」가 목록 순서에 달려 결과가 불안정하다")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a.java");
    }

    @Test
    @DisplayName("파일 수 상한을 넘으면 거부한다")
    void 파일_수_상한() {
        List<FileChange> tooMany = new ArrayList<>();
        for (int i = 0; i <= PublishRequest.MAX_FILES; i++) {
            tooMany.add(FileChange.modified("f" + i + ".java", "x"));
        }

        assertThatThrownBy(() -> request(synced(), tooMany))
                .as("상한이 없으면 「모델이 저장소를 통째로 다시 썼다」가 그대로 전송된다")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("총 바이트 상한을 넘으면 거부한다")
    void 총_바이트_상한() {
        String big = "x".repeat(PublishRequest.MAX_TOTAL_BYTES / 2 + 1);
        List<FileChange> heavy = List.of(
                FileChange.modified("a.java", big),
                FileChange.modified("b.java", big));

        assertThatThrownBy(() -> request(synced(), heavy))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("allowUpdate 기본은 false 다 — 덮어쓰기는 명시해야 한다")
    void 덮어쓰기는_명시해야_한다() {
        PublishRequest request = request(synced(), List.of(FileChange.modified("a.java", "x")));

        assertThat(request.allowUpdate()).isFalse();
    }

    @Test
    @DisplayName("toString 이 파일 내용을 노출하지 않는다")
    void toString은_내용을_노출하지_않는다_S4() {
        PublishRequest request = request(synced(),
                List.of(FileChange.modified("a.java", "이 내용은 공개 Fork 로 나간다")));

        assertThat(request.toString())
                .doesNotContain("이 내용은 공개 Fork 로 나간다")
                .contains("files=1");
    }

    private static ForkRef fork() {
        return ForkRef.of(new RepositoryCoordinates(FORK_OWNER, "spring-kafka"), FORK_OWNER);
    }

    private static SyncedFork synced() {
        return new SyncedFork(fork(), SyncOutcome.MERGED);
    }

    private static PublishRequest request(SyncedFork fork, List<FileChange> changes) {
        return new PublishRequest(fork, "main", BranchName.of(1, "x"),
                CommitMessage.from("Fix it", null, ContributionConstraints.unknown(), 1, null),
                changes, false);
    }
}
