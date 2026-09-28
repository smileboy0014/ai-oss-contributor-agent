package com.ossagent.pullrequest.domain;

import com.ossagent.repository.domain.ContributionConstraints;

/**
 * 대상 저장소에 나가는 커밋 메시지 — <b>S-5</b>.
 *
 * <h2>🔴 우리 커밋 컨벤션을 쓰지 않는다</h2>
 *
 * <p>{@code commit-convention.md} 의 {@code type(scope): subject} 는 <b>이 저장소 안에서만</b>
 * 유효하다. 대상 저장소에 나가는 산출물은 그쪽 규약을 따른다 — 우리 형식을 남의 저장소에
 * 강요하는 코드가 있으면 반려다.
 *
 * <p>여기서 조립하는 것은 {@link ContributionConstraints} 가 말해 준 것뿐이다.
 *
 * <table border="1">
 *   <caption>규약 → 산출물</caption>
 *   <tr><th>규약</th><th>결과</th></tr>
 *   <tr><td>{@code issueReferenceRequired}</td><td>본문 끝에 {@code #{issueNumber}} 한 줄</td></tr>
 *   <tr><td>{@code signoffRequired}</td><td>{@code Signed-off-by: …} 트레일러</td></tr>
 *   <tr><td>둘 다 아님</td><td>subject + body 만</td></tr>
 * </table>
 *
 * <h2>⚠️ 알고 있는 한계 — 참조 「형식」을 모른다</h2>
 *
 * <p>{@code ContributionConstraints.issueReferenceRequired} 는 <b>boolean 뿐</b>이라
 * 「참조가 필요하다」는 알지만 「어떤 형식으로」는 모른다. 그래서 {@code #123} 한 줄만 붙인다.
 * {@code spring-*} 이 쓰는 {@code Fixes gh-123} 같은 형식은 <b>표현할 수 없고</b>,
 * 그런 저장소에서는 이 메시지가 규약 미달이다. 고칠 곳은 {@code RepositoryPolicy} 에
 * 형식 필드를 더하고 #7 의 LLM 추출이 그것을 채우는 것이다.
 *
 * <p>🔴 <b>자동 닫기 키워드({@code Fixes}·{@code Closes}·{@code Resolves})를 우리가 임의로
 * 붙이지 않는다.</b> 머지되면 <b>남의 이슈가 닫히는</b> 부수효과이고, 규약이 요구하지 않는 한
 * 우리가 정할 일이 아니다. 「참조」와 「닫기」는 다른 행위다.
 */
public record CommitMessage(String text) {

    public CommitMessage {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("커밋 메시지가 비어 있습니다");
        }
    }

    /**
     * 규약대로 조립한다.
     *
     * @param subject     요약 한 줄. 외부(LLM·이슈 제목)에서 온 값이라 줄바꿈을 접는다
     * @param body        본문. 없으면 {@code null}
     * @param constraints 대상 저장소 규약. {@link ContributionConstraints#unknown()} 이면 아무것도 붙지 않는다
     * @param issueNumber 대상 저장소 이슈 번호
     * @param identity    서명자. {@code signoffRequired} 가 아니면 {@code null} 이어도 된다
     * @throws IllegalArgumentException sign-off 가 필요한데 서명자가 없다
     */
    public static CommitMessage from(String subject, String body,
                                     ContributionConstraints constraints,
                                     int issueNumber, CommitIdentity identity) {
        if (constraints == null) {
            throw new IllegalArgumentException("기여 규약이 없습니다");
        }
        String head = singleLine(subject);
        if (head.isEmpty()) {
            throw new IllegalArgumentException("커밋 메시지 요약이 비어 있습니다");
        }

        StringBuilder out = new StringBuilder(head);
        if (body != null && !body.isBlank()) {
            out.append("\n\n").append(body.strip());
        }
        if (constraints.issueReferenceRequired()) {
            if (issueNumber < 1) {
                throw new IllegalArgumentException(
                        "이슈 참조가 필수인 저장소인데 이슈 번호가 없습니다: " + issueNumber);
            }
            out.append("\n\n#").append(issueNumber);
        }
        if (constraints.signoffRequired()) {
            // 🔴 조용히 건너뛰지 않는다. sign-off 가 필수인 저장소에 서명 없는 커밋을 보내면
            //    PR 이 DCO 검사에서 막히고, 그것은 「규약을 읽고도 안 지킨」 모양이 된다 — S-5.
            if (identity == null) {
                throw new IllegalArgumentException(
                        "sign-off 가 필수인 저장소인데 서명자가 설정되지 않았습니다"
                                + " (github.fork.author-name · github.fork.author-email)");
            }
            out.append("\n\n").append(identity.signoffLine());
        }
        return new CommitMessage(out.toString());
    }

    private static String singleLine(String raw) {
        return raw == null ? "" : raw.replaceAll("\\s+", " ").strip();
    }

    /**
     * 🔴 <b>본문을 노출하지 않는다.</b> 이슈 제목·LLM 출력이 섞인 값이라 로그에 포맷 문자열로
     * 들어가면 로그 인젝션 경로가 된다 — {@code logging.md}. 전문이 필요한 곳은
     * {@link #text()} 를 명시적으로 부른다.
     */
    @Override
    public String toString() {
        return "CommitMessage[length=%d]".formatted(text.length());
    }
}
