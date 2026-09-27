package com.ossagent.pullrequest.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fork 의 한 브랜치에 커밋 하나를 올리는 요청.
 *
 * <p>🔴 <b>쓰기 대상이 {@link SyncedFork} 다.</b> {@code RepositoryCoordinates} 도
 * {@link ForkRef} 도 직접 받지 않는다.
 *
 * <ul>
 *   <li>{@code RepositoryCoordinates} 를 받으면 <b>upstream 좌표를 담은 요청을 만들 수 있게</b>
 *       되고, 어설션이 요청 조립 시점이 아니라 전송 시점으로 밀린다 — S-1</li>
 *   <li>{@link ForkRef} 만 받으면 <b>동기화를 보지 않고 publish 하는 경로</b>가 남는다.
 *       {@link SyncedFork} 를 요구하면 그것이 표현 불가능해진다 — {@code PolicyClearance} 선례</li>
 * </ul>
 *
 * <p>잘못된 요청은 <b>애초에 존재할 수 없는 편</b>이 낫다.
 *
 * @param fork        쓰기 대상 + 동기화 결과 — S-1 · D-4b
 * @param baseBranch  이 커밋이 올라갈 기준 브랜치 — 경로에 조립되므로 값 타입이다
 * @param branchName  만들거나 갱신할 브랜치
 * @param message     대상 저장소 규약을 따른 커밋 메시지 — S-5
 * @param changes     올릴 변경. 비어 있을 수 없다
 * @param allowUpdate 🔴 이미 있는 브랜치를 <b>덮어쓸 것인가.</b> 기본은 {@code false} 다 —
 *                    {@code true} 는 재시도 경로에서만 <b>명시적으로</b> 준다
 */
public record PublishRequest(SyncedFork fork, BaseBranch baseBranch, BranchName branchName,
                             CommitMessage message, List<FileChange> changes,
                             boolean allowUpdate) {

    /**
     * 한 커밋에 실을 수 있는 파일 수.
     *
     * <p>계획 단계의 {@code agent.plan.max-planned-files} 가 8 이므로 그보다 넉넉하되
     * 상한은 둔다 — 상한이 없으면 「모델이 저장소를 통째로 다시 썼다」가 <b>그대로 전송</b>된다.
     * 파일 하나당 blob 요청 하나라 레이트리밋도 여기 걸린다.
     */
    public static final int MAX_FILES = 32;

    /** 한 커밋의 총 바이트. 메모리에 통째로 올라오므로 상한이 필요하다. */
    public static final int MAX_TOTAL_BYTES = 2 * 1024 * 1024;

    public PublishRequest {
        if (fork == null) {
            throw new UpstreamWriteAttemptException("쓰기 대상 Fork 가 없습니다");
        }
        if (baseBranch == null) {
            throw new IllegalArgumentException("기준 브랜치가 없습니다");
        }
        if (branchName == null) {
            throw new IllegalArgumentException("브랜치 이름이 없습니다");
        }
        if (message == null) {
            throw new IllegalArgumentException("커밋 메시지가 없습니다");
        }
        changes = List.copyOf(requireChanges(changes));
    }

    public int totalBytes() {
        return changes.stream().mapToInt(FileChange::byteLength).sum();
    }

    private static List<FileChange> requireChanges(List<FileChange> changes) {
        if (changes == null || changes.isEmpty()) {
            // 빈 커밋은 「아무것도 안 바뀌었다」이고, 그것을 push 하면 PR 이 빈 채로 열린다
            throw new IllegalArgumentException("올릴 변경이 없습니다");
        }
        if (changes.size() > MAX_FILES) {
            throw new IllegalArgumentException(
                    "한 커밋의 파일 수 상한(%d)을 넘었습니다: %d".formatted(MAX_FILES, changes.size()));
        }
        Set<String> seen = new HashSet<>();
        int total = 0;
        for (FileChange change : changes) {
            if (change == null) {
                throw new IllegalArgumentException("변경 목록에 빈 항목이 있습니다");
            }
            // 🔴 같은 경로가 두 번 실리면 git tree 에서 뒤엣것이 이기는데, 그 「뒤」가
            //    목록 순서에 달려 있어 결과가 불안정하다. 조용한 손실이라 여기서 막는다.
            if (!seen.add(change.path())) {
                throw new IllegalArgumentException("같은 경로가 두 번 실렸습니다: " + change.path());
            }
            total += change.byteLength();
        }
        if (total > MAX_TOTAL_BYTES) {
            throw new IllegalArgumentException(
                    "한 커밋의 총 바이트 상한(%d)을 넘었습니다: %d".formatted(MAX_TOTAL_BYTES, total));
        }
        return changes;
    }

    /** 쓰기 대상 좌표 — 어댑터가 owner 를 다시 단언할 때 쓴다. */
    public ForkRef forkRef() {
        return fork.fork();
    }

    /** 🔴 변경 내용을 노출하지 않는다 (S-4). */
    @Override
    public String toString() {
        return "PublishRequest[fork=%s, branch=%s, sync=%s, files=%d, bytes=%d, allowUpdate=%s]"
                .formatted(fork.fullName(), branchName, fork.outcome(),
                        changes.size(), totalBytes(), allowUpdate);
    }
}
