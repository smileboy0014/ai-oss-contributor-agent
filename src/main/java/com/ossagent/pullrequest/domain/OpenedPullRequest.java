package com.ossagent.pullrequest.domain;

/**
 * upstream 에 열린 Draft PR — <b>자동화의 종착점</b>.
 *
 * <h2>🔴 {@code draft} 플래그가 없는 것이 의도다</h2>
 *
 * <p>「draft 인가」를 값으로 들면 <b>{@code false} 인 인스턴스가 표현 가능</b>해지고, 그 순간
 * 소비자가 「draft 면 …, 아니면 …」을 쓰게 된다. S-2 는 분기가 아니라 <b>단일값</b>으로
 * 지켜야 한다 — {@code PullRequest.Status} 가 {@code DRAFT} 하나뿐인 것과 같은 수법이다.
 *
 * <p>⚠️ 그렇다고 「응답이 draft 인지 확인하지 않는다」는 뜻은 아니다. 확인은
 * {@code GitHubDraftPrPublisher} 가 <b>응답을 값으로 바꾸기 전에</b> 하고, 아니면 던진다.
 * 여기까지 온 것은 이미 draft 다.
 *
 * @param number  upstream PR 번호
 * @param url     사람이 열어 볼 주소({@code html_url})
 * @param headRef {@code owner:branch} — 어느 Fork 브랜치를 가리키는가
 * @param forkUrl 🔴 <b>GitHub 이 기록한</b> head 저장소 주소({@code head.repo.html_url}).
 *                {@code PullRequest.forkUrl} 에 그대로 들어가 <b>「어디에 썼는가」의 증거</b>가
 *                된다 — S-1.
 *                <p>⚠️ 우리가 {@code "https://github.com/" + fullName} 으로 <b>조립하지
 *                않는다.</b> 호스트를 도메인에 박는 것도 문제지만, 더 중요한 것은 그렇게
 *                만든 값이 <b>우리의 믿음</b>이지 관측이 아니라는 점이다. 증거는 관측이어야
 *                한다 — fork 이름이 {@code {name}-1} 로 만들어지는 경우가 실제로 있다
 */
public record OpenedPullRequest(int number, String url, String headRef, String forkUrl) {

    public OpenedPullRequest {
        if (number < 1) {
            throw new DraftPrException("PR 번호가 유효하지 않습니다: " + number);
        }
        if (url == null || url.isBlank()) {
            // 🔴 URL 이 없으면 사람이 그 PR 에 도달할 수 없다. 「자동화가 끝나고 사람이
            //    이어받는다」는 제품 정의가 이 값 하나에 걸려 있다
            throw new DraftPrException("PR 주소가 비어 있습니다 prNumber=" + number);
        }
        if (headRef == null || headRef.isBlank()) {
            throw new DraftPrException("PR 의 head 가 비어 있습니다 prNumber=" + number);
        }
        if (forkUrl == null || forkUrl.isBlank()) {
            // 🔴 「어디에 push 했는가」가 비면 S-1 의 사후 증명이 불가능해진다
            throw new DraftPrException(
                    "PR 의 head 저장소 주소가 비어 있습니다 prNumber=" + number + " — S-1 의 증거다");
        }
        url = url.trim();
        headRef = headRef.trim();
        forkUrl = forkUrl.trim();
    }
}
