package com.ossagent.repository.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 지문 묶음 — <b>「없다」와 「달라졌다」를 섞지 않는 것</b>이 이 타입의 전부다 (이슈 #68).
 *
 * <p>둘을 뭉치면 배포 순간 모든 저장소가 일괄 보류로 떨어지거나(오탐), 변경이 영영
 * 탐지되지 않는다(미탐). 아래 테스트가 그 두 방향을 각각 고정한다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PolicyDocumentFingerprintsTest {

    private static final String REQUIRED = "CONTRIBUTING.md";
    private static final String OTHER_REQUIRED = "AGENTS.md";
    private static final String SUPPLEMENTARY = "README.md";

    @Test
    void 직렬화_왕복() {
        // absent 도 값이다 — 왕복에서 살아남아야 「없던 파일이 생겼다」를 탐지할 수 있다
        PolicyDocumentFingerprints prints = PolicyDocumentFingerprints.parse(
                REQUIRED + "=" + DocumentFingerprint.of("가").value()
                        + ";" + OTHER_REQUIRED + "=" + DocumentFingerprint.ABSENT_VALUE);

        assertThat(PolicyDocumentFingerprints.parse(prints.serialize())).isEqualTo(prints);
        assertThat(prints.get(OTHER_REQUIRED)).contains(DocumentFingerprint.ABSENT);
    }

    @Test
    void 비어_있으면_기준이_없다() {
        assertThat(PolicyDocumentFingerprints.parse(null).isEmpty()).isTrue();
        assertThat(PolicyDocumentFingerprints.parse("  ").isEmpty()).isTrue();
    }

    @Test
    void 형식이_깨진_항목은_거부한다() {
        // 🔴 조용히 건너뛰면 우리 직렬화의 버그가 「변경이 탐지되지 않는다」로 위장한다.
        //    이 컬럼에 쓰는 곳은 serialize() 하나뿐이라 깨졌다는 것은 곧 우리 버그다
        assertThatThrownBy(() -> PolicyDocumentFingerprints.parse("CONTRIBUTING.md"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PolicyDocumentFingerprints.parse("CONTRIBUTING.md=nothex"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 지문이_다르면_바뀐_것이다() {
        assertThat(of(REQUIRED, "가", null, null).changedRequiredPaths(of(REQUIRED, "나", null, null)))
                .containsExactly(REQUIRED);
    }

    @Test
    void 한쪽에만_있으면_바뀌었다고_주장하지_않는다() {
        // 🔴 이것이 NFR-4 다 — 기준이 없는 기존 행을 「바뀌었다」로 읽으면 일괄 강등된다
        assertThat(PolicyDocumentFingerprints.none()
                .changedRequiredPaths(of(REQUIRED, "가", null, null))).isEmpty();
        assertThat(of(REQUIRED, "가", null, null)
                .changedRequiredPaths(PolicyDocumentFingerprints.none())).isEmpty();
    }

    @Test
    void 기준에_없던_필수_경로를_가려낸다() {
        // 「바뀌었다」도 「그대로다」도 아니다. 이것을 세지 않으면 새로 읽힌 문서의
        // 금지 문구를 한 바퀴 놓친다
        assertThat(of(REQUIRED, "가", null, null)
                .unseenRequiredPaths(of(REQUIRED, "가", OTHER_REQUIRED, "나")))
                .containsExactly(OTHER_REQUIRED);
    }

    @Test
    void 기준에_있었는데_사라진_필수_경로를_가려낸다() {
        // 🔴 1MB 초과 형태 — 지문조차 못 구한다. 이것을 세지 않으면 이슈 #68 의 절반이 남는다
        assertThat(of(REQUIRED, "가", OTHER_REQUIRED, "나")
                .lostRequiredPaths(of(REQUIRED, "가", null, null)))
                .containsExactly(OTHER_REQUIRED);
    }

    @Test
    void 부가_경로_변경은_세지_않는다() {
        PolicyDocumentFingerprints before = PolicyDocumentFingerprints.parse(
                SUPPLEMENTARY + "=" + DocumentFingerprint.of("가").value());
        PolicyDocumentFingerprints after = PolicyDocumentFingerprints.parse(
                SUPPLEMENTARY + "=" + DocumentFingerprint.of("나").value());

        assertThat(before.changedRequiredPaths(after))
                .as("README 가 바뀌었다고 AI 기여 허용 판정이 낡는 것은 아니다 — 세면 거의 매번 강등된다")
                .isEmpty();
    }

    @Test
    void 지문이_없는_문서는_담기지_않는다() {
        RepositoryDocuments documents = new RepositoryDocuments(List.of(
                FetchedDocument.read(path(REQUIRED), "가"),
                FetchedDocument.unreadable(path(OTHER_REQUIRED), UnreadableReason.SERVER_ERROR)));

        assertThat(PolicyDocumentFingerprints.of(documents).get(OTHER_REQUIRED))
                .as("응답을 못 받았다. absent 로 채우면 「파일이 사라졌다」가 되어 S-5 오역이다")
                .isEmpty();
    }

    @Test
    void 후보_경로_전체를_직렬화해도_컬럼_상한_안에_든다() {
        // 🔴 V9 의 VARCHAR(4000). 경로를 늘리면 여기가 먼저 빨개져야 한다 —
        //    잘린 지문은 다음 비교에서 「달라졌다」로 읽혀 오탐 강등을 만든다
        List<FetchedDocument> all = PolicyDocumentPath.all().stream()
                .map(p -> FetchedDocument.read(p, "본문 " + p.path()))
                .toList();

        assertThat(PolicyDocumentFingerprints.of(new RepositoryDocuments(all)).serialize().length())
                .isLessThanOrEqualTo(4000);
    }

    private static PolicyDocumentFingerprints of(String pathA, String contentA,
            String pathB, String contentB) {
        StringBuilder serialized = new StringBuilder();
        if (pathA != null) {
            serialized.append(pathA).append('=').append(DocumentFingerprint.of(contentA).value());
        }
        if (pathB != null) {
            if (!serialized.isEmpty()) {
                serialized.append(';');
            }
            serialized.append(pathB).append('=').append(DocumentFingerprint.of(contentB).value());
        }
        return PolicyDocumentFingerprints.parse(serialized.toString());
    }

    private static PolicyDocumentPath path(String path) {
        return new PolicyDocumentPath(path, PolicyDocumentPath.Role.REQUIRED);
    }
}
