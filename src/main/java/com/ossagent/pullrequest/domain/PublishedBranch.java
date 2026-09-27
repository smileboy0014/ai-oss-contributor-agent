package com.ossagent.pullrequest.domain;

/**
 * Fork 에 올라간 결과 — 브랜치와 커밋.
 *
 * <p>#23 이 이 값으로 Draft PR 의 {@code head} 를 만든다. 🔴 <b>PR 을 여기서 만들지 않는다</b> —
 * 그것은 S-6 의 세 번째 승인 게이트({@code POST /api/candidates/{id}/pull-request}) 뒤에
 * 있어야 한다.
 *
 * @param fork       올라간 Fork
 * @param branchName 브랜치
 * @param commitSha  만들어진 커밋. {@code GeneratedChange.commitSha} 에 그대로 들어간다
 * @param updated    기존 브랜치를 <b>덮어썼는가.</b> 재시도 경로에서만 {@code true} 다
 */
public record PublishedBranch(ForkRef fork, BranchName branchName, String commitSha,
                              boolean updated) {

    public PublishedBranch {
        if (fork == null) {
            throw new IllegalArgumentException("Fork 가 없습니다");
        }
        if (branchName == null) {
            throw new IllegalArgumentException("브랜치가 없습니다");
        }
        if (commitSha == null || commitSha.isBlank()) {
            throw new IllegalArgumentException("커밋 SHA 가 없습니다");
        }
        commitSha = commitSha.trim();
    }

    /** {@code owner:branch} — GitHub 이 교차 저장소 PR 의 {@code head} 에 쓰는 표기. */
    public String headRef() {
        return fork.owner() + ":" + branchName.value();
    }
}
