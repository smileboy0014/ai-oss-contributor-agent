package com.ossagent.issue.domain;

import com.ossagent.support.secret.TokenRedactor;
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

    /**
     * 🔴 <b>여기가 시크릿 스크럽의 강제 지점이다</b> — S-4 · 이슈 #28.
     *
     * <p>이슈 본문·제목은 대상 저장소 사용자가 쓴 <b>임의 텍스트</b>다. 로그를 붙여넣으며
     * 토큰이 섞이는 일이 흔하고, 그 값은 DB({@code issue.body})로도 가고 LLM 프롬프트로도
     * 간다. 호출자마다 스크럽을 기억하게 하면 언젠가 빠진다 — 실제로
     * {@code Issue.applySnapshot} 이 원문을 그대로 대입하고 있었다.
     *
     * <p>모든 이슈 본문이 이 생성자를 통과하므로, 여기서 가리면 <b>스크럽되지 않은 본문이
     * 존재할 수 없다.</b> {@code AgentRun.fail} · {@code ScrubbedRules} 와 같은 수법이다 —
     * 이 저장소가 시크릿을 막는 방식은 「잊지 않고 부른다」가 아니라 <b>「부를 수밖에 없는
     * 자리에 둔다」</b>이다.
     *
     * <p>{@code labels} 는 가리지 않는다. 라벨 이름은 메인테이너가 정한 짧은 식별자이지
     * 자유 텍스트가 아니고, 후보 판정이 {@code good first issue} 같은 값을 그대로 비교한다.
     *
     * <p>{@code title} 에 {@code @ExternalText} 마커가 없는 것은 {@code TEXT} 컬럼이
     * 아니기 때문이지 외부 텍스트가 아니어서가 아니다 — <b>컬럼 길이는 보안 경계가 아니다.</b>
     *
     * <p>⚠️ 스크럽은 <b>멱등</b>이라 재수집으로 같은 값이 여러 번 지나도 이중 마스킹이
     * 생기지 않는다({@code TokenRedactorTest} 가 고정한다). 대신 <b>되돌릴 수 없다</b> —
     * 원문이 필요한 소비자를 만들지 않는다.
     *
     * <p>{@code support.secret.TokenRedactor} 는 순수 문자열 유틸이라 규율 ①(도메인에 기술
     * import 금지)에 걸리지 않는다. 도메인 엔티티 {@code AgentRun} 도 같은 것을 쓴다.
     */
    public IssueSnapshot {
        if (number <= 0) {
            throw new IllegalArgumentException("이슈 번호는 1 이상이어야 합니다: " + number);
        }
        title = TokenRedactor.redact(title == null ? "" : title);
        body = TokenRedactor.redact(body == null ? "" : body);
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
