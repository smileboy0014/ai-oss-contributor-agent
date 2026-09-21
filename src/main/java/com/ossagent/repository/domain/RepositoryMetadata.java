package com.ossagent.repository.domain;

/**
 * 대상 저장소의 메타데이터 스냅샷.
 *
 * <p>기여 가능 여부를 가르는 값이 여기 들어 있다 — {@code archived} 저장소는 PR 을 받지 않고,
 * {@code fork} 는 보통 upstream 이 따로 있다. 이슈를 수집하기 <b>전에</b> 걸러야 하는 조건이다.
 *
 * @param coordinates    저장소 좌표
 * @param defaultBranch  기본 브랜치. Fork 브랜치의 기준점이 된다(이슈 #22)
 * @param language       주 언어. 이 제품은 Java/Spring 만 다룬다(PRD §3)
 * @param archived       보관됨 — <b>기여를 받지 않는다</b>
 * @param fork           이 저장소 자체가 포크인가
 * @param openIssueCount GitHub 이 세는 open 이슈 수. <b>PR 을 포함한다</b>
 */
public record RepositoryMetadata(
        RepositoryCoordinates coordinates,
        String defaultBranch,
        String language,
        boolean archived,
        boolean fork,
        int openIssueCount) {

    public RepositoryMetadata {
        if (coordinates == null) {
            throw new IllegalArgumentException("저장소 좌표가 없습니다");
        }
    }

    /**
     * 기여 대상이 될 수 있는 상태인가.
     *
     * <p>보관된 저장소는 PR 을 열 수 없다. 이 판정을 하지 않으면 파이프라인 전체를 돌린 뒤
     * 마지막 PR 생성에서야 실패한다.
     */
    public boolean acceptsContributions() {
        return !archived;
    }
}
