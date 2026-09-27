package com.ossagent.pullrequest.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ossagent.support.secret.TokenRedactor;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * PR 본문 — <b>밖으로 나가는 텍스트</b>라 S-4 의 마지막 그물이 여기 있다.
 *
 * <p>재료 셋({@code template} · {@code verificationSummary} · {@code reviewSummary})이
 * 전부 <b>상류에 스크럽 강제 지점이 없는</b> 외부 텍스트다. 하나라도 새면 남의 저장소에
 * 영구히 남는다 — 지워도 메일 알림·포크·캐시에 남는다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PrBodyTest {

    /**
     * 🔴 <b>런타임에 조립한다.</b> 스크럽이 실제로 무는지를 보는 테스트라 <b>토큰의 문자셋이
     * 유의미</b>하다 — 패턴이 {@code gh[pousr]_[A-Za-z0-9]{20,}} 이라
     * {@code ghp_NOT_A_REAL_TOKEN_FOR_TESTS_ONLY} 는 <b>밑줄 때문에 걸리지 않는다.</b>
     * 그것을 쓰면 「스크럽이 됐다」가 아니라 「애초에 대상이 아니었다」를 재는 것이 된다
     * ({@code testing-philosophy.md} 「샘플의 대표성」).
     *
     * <p>소스에 리터럴로 두지 않는 것은 {@code secret-scan.sh} 때문이다 —
     * {@code DiffReviewTest}·{@code TokenRedactorTest} 와 같은 관용구다.
     */
    private static final String FAKE_TOKEN = "ghp_" + "b".repeat(30);

    // ── S-4 ─────────────────────────────────────────────────────────────

    @Test
    void 세_재료_모두의_토큰이_본문에_남지_않는다_S4() {
        // 🔴 대표성 — 이 표본이 정말 스크럽 규칙에 물리는 모양인가.
        //    이것이 없으면 아래 단언은 「애초에 대상이 아닌 것」을 재고도 초록이 된다
        assertThat(TokenRedactor.redact(FAKE_TOKEN))
                .as("표본이 규칙에 물리지 않으면 이 테스트 전체가 공허하다")
                .isEqualTo(TokenRedactor.MASK);

        PrBody body = PrBody.compose(new PrBodyMaterials(
                "## Checklist\n- [ ] token: " + FAKE_TOKEN,          // 대상 저장소 템플릿
                "버그를 고쳤다",                                        // 우리 어휘
                "Related to #12",
                "COMPILE PASSED\nexport GITHUB_TOKEN=" + FAKE_TOKEN,  // 샌드박스 빌드 출력
                "리뷰 의견: 설정에 " + FAKE_TOKEN + " 이 보입니다"));      // LLM 응답

        assertThat(body.value())
                .as("셋 중 하나라도 새면 남의 저장소에 영구히 남는다 — 회수 불가")
                .doesNotContain(FAKE_TOKEN);
        // 🔴 「사라졌다」가 「절이 통째로 빠졌다」일 수도 있다. 세 자리 모두에서
        //    실제로 치환이 일어났는지를 개수로 확인한다
        assertThat(countOccurrences(body.value(), TokenRedactor.MASK))
                .as("재료 셋 전부가 스크럽을 탔는가 — 하나만 타도 두 개는 샌 것이다")
                .isGreaterThanOrEqualTo(3);
    }

    @Test
    void toString_이_본문을_노출하지_않는다_S4() {
        PrBody body = new PrBody("비밀이 섞였을 수 있는 본문 " + FAKE_TOKEN);

        assertThat(body.toString())
                .as("로그·예외 메시지가 이것을 부른다")
                .doesNotContain(FAKE_TOKEN)
                .doesNotContain("비밀이")
                .contains("length=");
    }

    // ── S-5 : 대상 저장소 규약이 우리 규약보다 우선한다 ─────────────────

    @Test
    void 템플릿이_맨_위에_오고_체크박스는_그대로_보존된다_S5() {
        String template = "## Description\n\n- [ ] I signed the CLA\n- [x] tests added";

        PrBody body = PrBody.compose(new PrBodyMaterials(
                template, "요약", "Related to #12", "COMPILE PASSED", "리뷰 통과"));

        assertThat(body.value())
                .as("우리 절을 위에 두면 메인테이너가 자기 템플릿을 스크롤해서 찾아야 한다")
                .startsWith(template);
        assertThat(body.value())
                .as("모르는 항목을 우리가 채우면 거짓 진술이 남의 저장소에 나간다 — 사람이 채운다")
                .contains("- [ ] I signed the CLA")
                .contains("- [x] tests added");
    }

    @Test
    void 템플릿이_없으면_우리_절부터_시작한다_S5() {
        PrBody body = PrBody.compose(new PrBodyMaterials(
                null, "요약", "Related to #12", "COMPILE PASSED", "리뷰 통과"));

        assertThat(body.value())
                .as("템플릿이 없는 저장소(reactor-core 등)가 실제로 있다")
                .startsWith("### Summary");
    }

    // ── 고지 ────────────────────────────────────────────────────────────

    @Test
    void AI_생성_고지가_항상_들어간다() {
        // 재료가 하나도 없어도, 전부 있어도 들어간다 — 설정도 파라미터도 타지 않는다
        PrBody bare = PrBody.compose(new PrBodyMaterials(null, null, null, null, null));
        PrBody full = PrBody.compose(new PrBodyMaterials(
                "## T", "요약", "Related to #12", "COMPILE PASSED", "리뷰 통과"));

        for (PrBody body : new PrBody[] {bare, full}) {
            assertThat(body.value())
                    .as("숨기면 커뮤니티 신뢰를 잃는다")
                    .contains("### Automated contribution")
                    .contains("automated agent")
                    .contains("draft");
        }
    }

    @Test
    void 고지는_사람이_이미_검토했다고_말하지_않는다() {
        PrBody body = PrBody.compose(new PrBodyMaterials(null, "요약", null, null, null));

        // 🔴 이 본문이 만들어지는 시점에 사람은 아직 읽지 않았다. 읽었다고 적으면 거짓이다
        assertThat(body.value())
                .as("과거형 진술은 남의 저장소에 나가는 거짓말이 된다")
                .doesNotContain("reviewed by a human before")
                .contains("A human reviews it before");
    }

    // ── 없는 재료 ───────────────────────────────────────────────────────

    @Test
    void 없는_재료의_절은_통째로_빠진다() {
        PrBody body = PrBody.compose(new PrBodyMaterials(
                null, "요약", null, "   ", null));

        assertThat(body.value())
                .as("절 제목만 남으면 읽는 사람이 「비었다」와 「없다」를 구분할 수 없다")
                .doesNotContain("### Related issue")
                .doesNotContain("### Verification")
                .doesNotContain("### AI review")
                .contains("### Summary");
        // 🔴 모르는 것을 「알 수 없음」으로 채우지 않는다 — 그것은 우리가 지어낸 진술이다
        assertThat(body.value())
                .doesNotContain("알 수 없음")
                .doesNotContain("N/A")
                .doesNotContain("unknown")
                .doesNotContain("없음");
    }

    @Test
    void 있는_재료는_절_제목과_함께_실린다() {
        // 과차단 대조 — 위 테스트가 「전부 빠진다」로 고장 나도 초록이 되지 않게 한다
        PrBody body = PrBody.compose(new PrBodyMaterials(
                null, "요약", "Related to #12", "COMPILE PASSED", "CHANGES_REQUESTED"));

        assertThat(body.value())
                .contains("### Summary")
                .contains("### Related issue")
                .contains("Related to #12")
                .contains("### Verification")
                .contains("COMPILE PASSED")
                .contains("### AI review")
                .contains("CHANGES_REQUESTED");
    }

    @Test
    void 자동_닫기_키워드를_임의로_붙이지_않는다_S5() {
        PrBody body = PrBody.compose(new PrBodyMaterials(
                null, "요약", "Related to #12", null, null));

        // 언제 닫을지는 메인테이너가 정한다. 우리 판단으로 닫아 두면 그쪽 트리아지를 침범한다
        assertThat(body.value()).doesNotContain("Fixes #").doesNotContain("Closes #");
    }

    // ── 거부 ────────────────────────────────────────────────────────────

    @Test
    void 빈_본문은_거부한다() {
        assertThatThrownBy(() -> new PrBody(null)).isInstanceOf(DraftPrException.class);
        assertThatThrownBy(() -> new PrBody("")).isInstanceOf(DraftPrException.class);
        assertThatThrownBy(() -> new PrBody("   \n  ")).isInstanceOf(DraftPrException.class);
        assertThatThrownBy(() -> PrBody.compose(null))
                .as("재료가 없는 것과 본문이 빈 것을 같은 예외로 다룬다 — 둘 다 PR 을 못 연다")
                .isInstanceOf(DraftPrException.class);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0;
                at = haystack.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }
}
