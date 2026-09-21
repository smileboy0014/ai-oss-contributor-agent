package com.ossagent.issue.domain;

import java.time.Instant;
import java.util.List;

/**
 * 대상 저장소의 이슈 한 건을 수집 시점 기준으로 찍은 값.
 *
 * <p>JPA 엔티티가 아니다. 영속화 대상인 {@code Issue} 엔티티와는 별개이고,
 * 이것은 <b>대외에서 방금 읽어 온 원본</b>이다. 필터(이슈 #9)와 후보 판정(이슈 #11)이
 * 이 값을 입력으로 쓴다.
 *
 * @param number       이슈 번호
 * @param title        제목
 * @param body         본문. 없을 수 있다(빈 문자열로 정규화한다)
 * @param labels       라벨 이름. {@code good first issue} 같은 값이 후보 판정에 쓰인다
 * @param author       작성자 로그인. <b>이메일 등 개인정보는 담지 않는다</b>
 * @param commentCount 코멘트 수. 논의가 길수록 요구가 불명확할 확률이 높다(이슈 #9)
 * @param createdAt    생성 시각
 * @param updatedAt    갱신 시각. 증분 수집 커서의 기준이다(이슈 #8)
 * @param pullRequest  <b>이 항목이 실은 PR 인가.</b> 아래 설명 참조
 */
public record IssueSnapshot(
        int number,
        String title,
        String body,
        List<String> labels,
        String author,
        int commentCount,
        Instant createdAt,
        Instant updatedAt,
        boolean pullRequest) {

    public IssueSnapshot {
        if (number <= 0) {
            throw new IllegalArgumentException("이슈 번호는 1 이상이어야 합니다: " + number);
        }
        title = title == null ? "" : title;
        body = body == null ? "" : body;
        labels = labels == null ? List.of() : List.copyOf(labels);
    }

    /**
     * 기여 후보가 될 수 있는 항목인가.
     *
     * <p>⚠️ GitHub 의 <b>이슈 목록 API 는 Pull Request 도 함께 돌려준다.</b> 걸러내지 않으면
     * 남의 PR 을 「이슈」로 알고 분석·구현 파이프라인에 태운다. 이 판정을 어댑터가 아니라
     * 도메인 값에 두는 이유는, 걸러낼지 세어 볼지를 호출자가 정할 수 있어야 하기 때문이다.
     */
    public boolean isIssue() {
        return !pullRequest;
    }

    public boolean hasLabel(String label) {
        return labels.stream().anyMatch(it -> it.equalsIgnoreCase(label));
    }

    /**
     * 🔴 본문을 포함하지 않는다. 이슈 본문은 대상 저장소 사용자가 쓴 <b>임의 텍스트</b>이고,
     * 로그에 그대로 실으면 로그 인젝션과 시크릿 유출 경로가 된다 —
     * {@code .claude/rules/conventions/logging.md}.
     */
    @Override
    public String toString() {
        return "IssueSnapshot[number=%d, pullRequest=%s, updatedAt=%s, bodyLength=%d]"
                .formatted(number, pullRequest, updatedAt, body.length());
    }
}
