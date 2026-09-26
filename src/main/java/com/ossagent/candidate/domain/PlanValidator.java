package com.ossagent.candidate.domain;

import com.ossagent.repository.domain.ContributionConstraints;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 계획을 <b>실행하기 전에</b> 거른다 — 이슈 #16 FR-2~FR-4. <b>순수 함수다.</b>
 *
 * <h2>이 클래스가 이 이슈의 가치 전부다</h2>
 * LLM 계획을 그대로 #18 에 넘기면 <b>엉뚱한 파일을 고치는 데 샌드박스 수 분</b>을 태운다.
 * 여기서 거르면 LLM 호출 한 번이고, 못 거르면 컨테이너 30분이다.
 * 제품의 품질 축은 「좋은 코드를 쓰는가」가 아니라 <b>「나쁜 결과를 걸러내는가」</b>다(PRD §30).
 *
 * <h2>🔴 검사는 셋뿐이고, 전부 「사실 대조」이거나 「숫자 상한」이다</h2>
 * <ol>
 *   <li><b>실재</b> — 고치겠다는 파일이 저장소에 있는가 (사실)</li>
 *   <li><b>규약</b> — {@code testsRequired} 인데 테스트 전략이 없는가 (사실 · S-5)</li>
 *   <li><b>범위</b> — 파일 수·라인 수가 상한을 넘는가 (숫자)</li>
 * </ol>
 *
 * <p>⚠️ <b>「계획이 좋은가」를 판정하지 않는다.</b> 그런 검사를 넣으면 정상 계획이
 * 취향으로 거부되고, 후보가 재생성 상한을 태우다 실패한다. 판정은 <b>반박 가능해야</b> 한다 —
 * 「이 경로가 트리에 없다」는 반박 가능하지만 「계획이 부실하다」는 그렇지 않다.
 */
public final class PlanValidator {

    private PlanValidator() {
    }

    /**
     * @param plan         검증 대상
     * @param shownPaths   🔴 <b>컨텍스트가 모델에게 보여준</b> 경로 집합 — {@code RepositoryContext}
     *                     가 고른 파일들이다. <b>저장소 전체 트리가 아니다</b>
     * @param contextPartial 컨텍스트가 잘렸는가. 거부 사유 문구를 바꾼다 (무고한 거부일 수 있다)
     * @param constraints  대상 저장소 규약 (S-5)
     * @param limits       범위 눈금 2종 (D-6)
     */
    public static PlanVerdict validate(ImplementationPlan plan, Set<String> shownPaths,
            boolean contextPartial, ContributionConstraints constraints, ScopeLimits limits) {
        if (plan == null) {
            throw new IllegalArgumentException("검증할 계획이 없다");
        }
        if (limits == null) {
            throw new IllegalArgumentException("범위 눈금이 없다 — 상한 없는 검증은 검증이 아니다");
        }
        List<String> violations = new ArrayList<>();
        checkFilesExist(plan, shownPaths, contextPartial, violations);
        checkRepositoryRules(plan, constraints, violations);
        checkScope(plan, limits, violations);
        return violations.isEmpty() ? PlanVerdict.passed() : PlanVerdict.rejected(violations);
    }

    /**
     * FR-2 — 고치겠다는 <b>기존</b> 파일이 실재하는가.
     *
     * <p>🔴 {@code CREATE} 는 검사 대상이 <b>아니다.</b> 새로 만들 파일이 아직 없는 것은
     * 당연하고, 여기서 막으면 테스트 파일 추가가 영영 불가능해진다.
     *
     * <p>⚠️ 반대로 <b>이미 있는 파일을 {@code CREATE} 로 지목</b>하는 것은 막는다.
     * #18 이 그대로 실행하면 남의 파일을 <b>덮어쓴다.</b>
     *
     * <p>🔴 <b>기준은 「저장소에 있는가」가 아니라 「우리가 보여줬는가」다.</b>
     * {@code RepositoryContext} 는 트리를 들고 있지 않다 — 선별된 파일 목록뿐이다.
     * 그리고 그것이 옳다: <b>모델에게 준 것이 그 목록</b>이므로, 보여주지 않은 파일을
     * 고치겠다는 계획은 근거가 없다.
     *
     * <p>🕳 <b>한계</b> — 컨텍스트가 잘렸으면({@code isPartial}) 실재하는 파일을 「없다」고
     * 판정할 수 있다. <b>무고한 거부</b>다. 그래서 그 경우 사유 문구에 사실을 함께 적어,
     * 재생성이 「목록 안에서 고르라」로 읽히게 한다.
     */
    private static void checkFilesExist(ImplementationPlan plan, Set<String> shownPaths,
            boolean contextPartial, List<String> violations) {
        Set<String> known = shownPaths == null ? Set.of() : shownPaths;
        String partialNote = contextPartial
                ? " (컨텍스트가 상한에서 잘렸다 — 보여준 목록 안에서 고른다)"
                : "";
        for (PlannedFile file : plan.modifiedFiles()) {
            if (!known.contains(file.path())) {
                violations.add(("수정 대상 `%s` 는 저장소 컨텍스트에 없다. "
                        + "컨텍스트로 제시된 파일만 MODIFY 로 지목한다%s")
                        .formatted(file.path(), partialNote));
            }
        }
        for (PlannedFile file : plan.createdFiles()) {
            if (known.contains(file.path())) {
                violations.add(("신규 생성으로 지목한 `%s` 가 이미 있다. "
                        + "기존 파일은 MODIFY 로 지목한다 — CREATE 는 덮어쓴다")
                        .formatted(file.path()));
            }
        }
    }

    /**
     * FR-3 — 대상 저장소 규약을 지키는가. 🔴 <b>S-5</b>.
     *
     * <p>규약이 테스트를 요구하는데 계획에 테스트 전략이 없으면, 그 PR 은 <b>읽히지 않고
     * 닫힌다.</b> 그것을 코딩·검증 30분을 태운 뒤에 알게 되는 것이 이 검사가 막는 일이다.
     */
    private static void checkRepositoryRules(ImplementationPlan plan,
            ContributionConstraints constraints, List<String> violations) {
        if (constraints == null) {
            return;
        }
        if (constraints.testsRequired() && !plan.hasTestStrategy()) {
            violations.add("이 저장소는 테스트를 필수로 요구한다(규약). "
                    + "testStrategy 에 무엇을 어떻게 검증할지 적는다");
        }
        if (constraints.testsRequired() && plan.files().stream().noneMatch(PlanValidator::looksLikeTest)) {
            violations.add("이 저장소는 테스트를 필수로 요구한다(규약). "
                    + "고칠 파일 목록에 테스트 파일이 하나도 없다 — 테스트를 추가하거나 수정한다");
        }
    }

    /**
     * FR-4 — 범위가 이슈를 넘지 않는가.
     *
     * <p>계획이 20개 파일을 고치겠다고 하면 그것은 이슈 하나의 기여가 아니라 리팩토링이고,
     * 메인테이너의 리뷰 큐에서 닫힌다. PR 컨벤션의 「&lt; 400줄」과 같은 눈금이다.
     */
    private static void checkScope(ImplementationPlan plan, ScopeLimits limits,
            List<String> violations) {
        // ① 절대 천장 — 파이프라인이 폭주하지 않게
        if (plan.fileCount() > limits.maxFiles()) {
            violations.add("고칠 파일이 %d개로 상한 %d개를 넘는다. 이슈 하나의 범위로 좁힌다"
                    .formatted(plan.fileCount(), limits.maxFiles()));
        }
        if (plan.estimatedLoc() > limits.maxLoc()) {
            violations.add("변경 예상이 %d줄로 상한 %d줄을 넘는다. 이슈 하나의 범위로 좁힌다"
                    .formatted(plan.estimatedLoc(), limits.maxLoc()));
        }
        // ② 이슈별 허용 — 「이 이슈가 그만한 일이 아니다」. 고정 상한만으로는 못 잡는다.
        //    추정이 없으면(0) 건너뛴다 — 모르는 것을 최강 제약으로 번역하지 않는다
        if (limits.checksIssueFiles() && plan.fileCount() > limits.allowedFiles()) {
            violations.add(("고칠 파일이 %d개인데 이 이슈의 분석 추정은 %d개다(허용 %d개). "
                    + "이슈가 요구하지 않은 변경이 섞여 있는지 본다")
                    .formatted(plan.fileCount(), limits.issueEstimatedFiles(),
                            limits.allowedFiles()));
        }
        if (limits.checksIssueLoc() && plan.estimatedLoc() > limits.allowedLoc()) {
            violations.add(("변경 예상이 %d줄인데 이 이슈의 분석 추정은 %d줄이다(허용 %d줄). "
                    + "이슈가 요구하지 않은 변경이 섞여 있는지 본다")
                    .formatted(plan.estimatedLoc(), limits.issueEstimatedLoc(),
                            limits.allowedLoc()));
        }
    }

    /**
     * 테스트 파일처럼 보이는가 — <b>경로만</b> 본다.
     *
     * <p>⚠️ 휴리스틱이다. 저장소마다 배치가 달라 완벽할 수 없고, <b>틀리는 방향이
     * 무고한 거부</b>(테스트가 있는데 못 알아봄)라는 것을 적어 둔다. 그 경우 모델이
     * 재생성하며 더 표준적인 경로에 테스트를 두게 되므로 손해가 작다.
     */
    private static boolean looksLikeTest(PlannedFile file) {
        String path = file.path().toLowerCase(java.util.Locale.ROOT);
        return path.contains("/test/") || path.contains("/tests/")
                || path.endsWith("test.java") || path.endsWith("tests.java")
                || path.endsWith("spec.groovy") || path.endsWith("it.java");
    }
}
