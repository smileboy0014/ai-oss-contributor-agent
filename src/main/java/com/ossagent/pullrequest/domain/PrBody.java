package com.ossagent.pullrequest.domain;

import com.ossagent.support.ExternalText;
import com.ossagent.support.secret.TokenRedactor;

/**
 * Draft PR 의 본문 — <b>S-4 에서 가장 위험한 경로 중 하나</b>.
 *
 * <h2>🔴 재료 셋이 전부 외부에서 온다</h2>
 *
 * <table border="1">
 *   <caption>무엇이 흘러드는가</caption>
 *   <tr><th>재료</th><th>출처</th><th>상류에 스크럽 강제가 있나</th></tr>
 *   <tr><td>PR 템플릿</td><td>대상 저장소 파일</td>
 *       <td>❌ — 저장소가 시크릿을 커밋해 뒀을 수 있다</td></tr>
 *   <tr><td>검증 출력</td><td>{@code GeneratedChange.testResult} (샌드박스 빌드 출력)</td>
 *       <td>❌ — {@code ExternalTextScrubRegistryTest} 에 <b>{@code PENDING}</b> 으로 올라 있다</td></tr>
 *   <tr><td>리뷰 요약</td><td>{@code GeneratedChange.reviewResult} (LLM 응답)</td>
 *       <td>❌ — 같은 표에서 <b>{@code PENDING}</b></td></tr>
 * </table>
 *
 * <p>즉 <b>상류에 강제 지점이 없는 값들이 여기 모인다.</b> 그리고 여기서 나가면
 * <b>남의 저장소에 영구히 남는다</b> — 지워도 메일 알림·포크·캐시에 남는다.
 * 그래서 compact 생성자가 {@link TokenRedactor} 를 <b>무조건</b> 태운다.
 *
 * <p>⚠️ 「상류가 언젠가 스크럽할 테니 여기는 생략해도 된다」는 정확히 반대다.
 * {@code glossary.md} 가 못 박았듯 <b>경로 배제와 내용 스크럽은 서로를 대신하지 않고</b>,
 * 여기는 <b>밖으로 나가기 직전</b>이라 마지막 그물이다.
 *
 * <h2>템플릿의 체크박스를 우리가 채우지 않는다 — S-5</h2>
 *
 * <p>대상 저장소의 {@code PULL_REQUEST_TEMPLATE.md} 는 <b>그대로</b> 얹는다. 항목을 우리가
 * 판단해 체크하면 <b>거짓 진술</b>이 남의 저장소에 나간다. 사람이 제출 전에 채운다 —
 * 그것이 이 제품이 Draft 에서 멈추는 이유다.
 */
public record PrBody(@ExternalText(ExternalText.Source.TARGET_REPOSITORY) String value) {

    /** GitHub 의 PR 본문 상한. 넘으면 API 가 422 를 준다. */
    public static final int MAX_LENGTH = 65_536;

    /**
     * 🔴 <b>AI 생성 고지.</b> 리터럴이고 설정을 타지 않는다 — 숨기면 커뮤니티 신뢰를 잃는다.
     *
     * <p>「사람이 검토했다」가 아니라 <b>「제출 전 사람이 검토한다」</b>로 쓴다. 이 본문이
     * 만들어지는 시점에 사람은 아직 읽지 않았고, 읽었다고 적으면 그것이 거짓이 된다.
     */
    static final String AI_DISCLOSURE = """
            ### Automated contribution

            This pull request was prepared by an automated agent (ai-oss-contributor-agent) and is
            opened as a **draft**. A human reviews it before it is marked ready for review, so it is
            not yet submitted for maintainer review. The verification section above lists what was
            actually executed.""";

    private static final String TRUNCATION_NOTICE =
            "\n\n_(본문이 길어 잘렸습니다 — 전체 내용은 브랜치의 변경분을 보세요.)_";

    public PrBody {
        if (value == null || value.isBlank()) {
            throw new DraftPrException("PR 본문이 비어 있습니다");
        }
        // 🔴 유일한 생성 경로가 스크럽을 탄다 — S-4. String 을 그대로 받는 우회 경로가 없다.
        //    자르기보다 먼저 한다: 잘린 뒤에 스크럽하면 토큰이 경계에서 반쪽만 남아
        //    패턴에 걸리지 않는다
        value = TokenRedactor.redact(value);
        if (value.length() > MAX_LENGTH) {
            value = value.substring(0, MAX_LENGTH - TRUNCATION_NOTICE.length()) + TRUNCATION_NOTICE;
        }
    }

    /**
     * 재료를 이어 붙여 본문을 만든다.
     *
     * <p>순서가 의도다 — <b>대상 저장소의 템플릿이 맨 위</b>다. 우리 절을 위에 두면 메인테이너가
     * 자기 템플릿을 스크롤해서 찾아야 한다.
     */
    public static PrBody compose(PrBodyMaterials materials) {
        if (materials == null) {
            throw new DraftPrException("PR 본문 재료가 없습니다");
        }
        StringBuilder body = new StringBuilder();

        appendSection(body, null, materials.template());
        appendSection(body, "### Summary", materials.changeSummary());

        // 🔴 규약이 이슈 참조를 요구하면 호출자가 반드시 채워 넣는다 — S-5.
        //    ⚠ 자동 닫기 키워드(Fixes·Closes)를 붙이지 않는다. 언제 닫을지는 메인테이너가
        //      정하고, 우리 판단으로 닫아 두면 그쪽 트리아지를 침범한다 — #22 가 커밋
        //      메시지에서 같은 판단을 했다
        appendSection(body, "### Related issue", materials.issueReference());

        appendSection(body, "### Verification", materials.verificationSummary());
        appendSection(body, "### AI review", materials.reviewSummary());
        appendSection(body, null, AI_DISCLOSURE);

        return new PrBody(body.toString().strip());
    }

    private static void appendSection(StringBuilder body, String heading, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        if (!body.isEmpty()) {
            body.append("\n\n");
        }
        if (heading != null) {
            body.append(heading).append("\n\n");
        }
        body.append(content.strip());
    }

    /** 🔴 본문을 노출하지 않는다 (S-4). 로그·예외 메시지가 이것을 부른다. */
    @Override
    public String toString() {
        return "PrBody[length=%d]".formatted(value.length());
    }
}
