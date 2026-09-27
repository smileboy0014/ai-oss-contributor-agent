package com.ossagent.candidate.domain;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;

import com.ossagent.candidate.application.CreateDraftPrUseCase;
import com.ossagent.candidate.application.SelectCandidateUseCase;
import com.ossagent.repository.domain.ContributionConstraints;
import com.ossagent.repository.domain.PolicyClearance;
import com.ossagent.repository.domain.RepositoryCoordinates;
import com.ossagent.repository.domain.RepositoryContext;
import com.ossagent.repository.domain.SelectedFile;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 승인 게이트를 <b>구조로</b> 고정한다 — S-6 · 규율 ④.
 *
 * <p>여기 있는 것은 전부 「단위 테스트로는 못 잡는 것」이다. 단위 테스트는 <b>부른 코드</b>를
 * 검증하지만, S-6 이 막으려는 것은 <b>누군가 나중에 부르게 되는 것</b>이다.
 *
 * <h2>🔴 0건 검사로 초록이 되지 않게 한다</h2>
 *
 * <p>「스케줄러는 게이트에 닿지 못한다」는 규칙은 <b>위반이 0건이면 규칙이 통째로 잘못
 * 쓰여 있어도 초록</b>이다. #24 를 쓸 당시 이 저장소에는 {@code adapter/in/scheduler} 가
 * 하나도 없어 정확히 그 상태였고, 그 뒤 {@code ScanScheduler}(#14)가 들어왔지만
 * <b>그것이 마침 게이트를 안 부를 뿐</b>이라 사정은 같다 — 규칙이 무는지는 여전히 증명되지 않는다.
 *
 * <p>그래서 규칙마다 <b>양성 대조</b>를 둔다 — 운영 코드에는 없지만 미끼 패키지
 * ({@code support.testing.probe})에는 있는 위반을 <b>같은 규칙이 무는지</b> 확인한다.
 * 실제 판정에서는 그 패키지를 {@link ImportOption} 으로 <b>빼</b>므로, 미끼가 운영 판정을
 * 오염시키지 않는다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ApprovalGateArchitectureTest {

    private static final String PROBE_PACKAGE = "com.ossagent.support.testing.probe";

    /**
     * 운영 판정용.
     *
     * <p>🔴 <b>테스트 클래스를 뺀다.</b> 빼지 않으면 아래 규율 ④ 규칙이
     * {@code SelectCandidateUseCaseTest} 같은 <b>테스트</b>를 위반으로 잡는다 —
     * 테스트는 남의 애그리거트를 정당하게 조립한다(픽스처를 세우려면 그래야 한다).
     * 규율 ④가 막는 것은 <b>운영 코드의 컴파일 결합</b>이다.
     *
     * <p>미끼 패키지도 함께 뺀다. 경로 규칙({@code DO_NOT_INCLUDE_TESTS})이 이미 걸러내지만,
     * 그 규칙은 <b>Gradle 의 출력 경로 관례</b>에 기대므로 의도를 한 번 더 못 박는다.
     *
     * <p>🔴 <b>그 「한 번 더」가 공짜가 아니다.</b> 문자열로 경로를 거르는 람다를 더한 것은
     * <b>매칭 실패점을 하나 늘린 것</b>이기도 하다.
     *
     * <p><b>실측</b> — 그 람다가 넓게 물어 {@code com/ossagent/repository/application} 이
     * 빠지면, 모수 단언이 없을 때 <b>다섯 규칙이 전부 초록</b>이었다. 하필 ①을
     * 허용목록으로 뒤집은 이유가 <b>바로 그 패키지의 자동 실행자</b>
     * ({@code ScanExecutor}·{@code LaunchScanUseCase})를 덮으려던 것이다 —
     * 가드가 자기가 덮으려던 대상을 통째로 놓치면서 초록이 된다.
     *
     * <p>⚠️ {@code repository} 를 <b>통째로</b> 떨어뜨리는 더 넓은 경우는 모수 단언이
     * 없어도 잡혔다. 다만 그것은 규율 ④ 역방향 규칙의 {@code that()} 이 비면서
     * <b>ArchUnit 자체의 {@code failOnEmptyShould}</b> 가 발화한 것이지 우리 설계가 아니다 —
     * 그 규칙에 누군가 {@code allowEmptyShould(true)} 를 붙이면 사라지는 <b>우연한 보호</b>다.
     *
     * <p>그래서 아래 {@code 판정_대상이_임포트에_실재한다_모수} 가 필요하다 —
     * {@code testing-philosophy.md} 요구 1(모수)·4(입력 도달).
     */
    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(location -> !location.contains(PROBE_PACKAGE.replace('.', '/')))
            .importPackages("com.ossagent");

    /** 양성 대조용 — 미끼만. 같은 규칙이 여기서는 <b>반드시 실패</b>해야 한다. */
    private static final JavaClasses PROBES = new ClassFileImporter()
            .importPackages(PROBE_PACKAGE);

    // ────────── ① 승인 게이트를 부를 수 있는 곳은 web 어댑터뿐이다 (S-6) ──────────

    /**
     * 🔴 <b>허용목록이다 — 거부목록이 아니다.</b>
     *
     * <p>처음에는 「{@code ..adapter.in.scheduler..}·{@code ..adapter.in.event..} 는 부르지
     * 못한다」로 썼다. <b>그 축이 틀렸다.</b> 이 저장소의 진짜 자동 실행자는 그 패키지에
     * 살지 않는다 — {@code ScanExecutor}({@code @Async})와 {@code LaunchScanUseCase} 는
     * {@code repository.application} 에 있다. 열거한 형태만 막으면 <b>열거에 없는 형태마다
     * 구멍이 새로 난다.</b>
     *
     * <p>그래서 「어디가 부르면 안 되나」가 아니라 <b>「어디만 부를 수 있나」</b>로 뒤집었다.
     * 사람이 누르는 문은 web 어댑터 하나이므로, 새 진입점이 어떤 이름·어떤 패키지로
     * 생기든 <b>기본이 차단</b>이다.
     */
    /**
     * 🔴 <b>게이트 목록은 #23 에서 둘이 됐다.</b> 새 승인 지점이 생기면 여기 더한다 —
     * 더하지 않으면 그 게이트는 <b>아무 데서나 불릴 수 있다.</b>
     *
     * <p>⚠️ 목록에 더하는 것만으로는 부족하다. 아래 양성 대조가 <b>게이트마다</b> 미끼를
     * 갖고 있어야 한다 — 기존 미끼 하나만으로 초록이면 새 타입을 빠뜨려도 드러나지 않는다.
     */
    private static final Class<?>[] APPROVAL_GATES = {
            SelectCandidateUseCase.class, CreateDraftPrUseCase.class};

    /**
     * {@code markPrCreated} 를 실제로 부르는 유일한 자리 — package-private 이라 클래스
     * 리터럴로 지목할 수 없다. 🔴 <b>이 문자열이 실재하는지는 모수 단언이 본다</b>
     * ({@code 판정_대상이_임포트에_실재한다_모수}).
     */
    private static final String PR_WRITER = "com.ossagent.candidate.application.CandidatePrWriter";

    private static ArchRule 승인_게이트는_web_어댑터만_부른다() {
        return noClasses()
                .that().resideOutsideOfPackage("com.ossagent.candidate.adapter.in.web..")
                .and().doNotBelongToAnyOf(APPROVAL_GATES)
                .should().dependOnClassesThat().belongToAnyOf(APPROVAL_GATES)
                .as("승인 게이트를 부르는 것은 사람이 누르는 문(web) 하나여야 한다 (S-6)")
                .allowEmptyShould(true);
    }

    @Test
    void 승인_게이트를_부르는_것은_web_어댑터뿐이다_S6() {
        승인_게이트는_web_어댑터만_부른다().check(PRODUCTION);
    }

    @Test
    void 그_규칙이_실제로_무는지_확인한다_양성_대조() {
        // 🔴 게이트마다 미끼를 확인한다. 「AutoSelectProbe 를 문다」만 보면 새 게이트를
        //    목록에 빠뜨려도 이 테스트가 초록이다 — #23 검토에서 잡힌 구멍이다
        assertThatThrownBy(() -> 승인_게이트는_web_어댑터만_부른다().check(PROBES))
                .as("미끼(AutoSelectProbe)를 놓치면 규칙이 고장 난 것이다 — "
                        + "운영 코드에는 위반이 0건이라 규칙이 잘못 쓰여 있어도 초록이다")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("AutoSelectProbe")
                .as("PR 생성 게이트(#23)를 자동으로 부르는 미끼도 함께 물어야 한다 — "
                        + "게이트가 둘인데 미끼가 하나면 둘째는 검사되지 않는다")
                .hasMessageContaining("AutoPrProbe");
    }

    // ───────── ①b UseCase 를 우회해 엔티티를 직접 부르지 못한다 (S-6) ─────────

    /**
     * 🔴 규칙 ①만으로는 <b>얇다.</b> 그것은 {@code SelectCandidateUseCase} 라는 타입을
     * 지목하므로, 자동 실행자가 후보를 직접 꺼내
     * {@code candidate.cancelSelection(clock)} 을 부르면 <b>UseCase 를 거치지 않아
     * 걸리지 않는다.</b>
     *
     * <p>{@code selectByHuman} 쪽은 규칙 ②({@code selectedAt} 쓰기)가 패키지와 무관하게
     * 전역으로 덮어 이중 방어가 된다. 그런데 <b>{@code cancelSelection} 에는 대응하는 필드
     * 가드가 없다</b> — 취소는 {@code status}·{@code updatedAt} 만 건드린다.
     * Q-5 확정 ②가 「자동 취소 경로를 만들지 않는다」를 명시적으로 걸어 둔 것을 생각하면
     * 그 비대칭을 남길 이유가 없다.
     */
    private static ArchRule 사람_전이_메서드는_승인_UseCase_만_부른다() {
        return noClasses()
                // 🔴 면제는 「승인 경로」다. #23 이 CandidatePrWriter 를 더했다 —
                //    CreateDraftPrUseCase 가 트랜잭션 때문에 쓰기를 그쪽에 위임하므로
                //    실제로 markPrCreated 를 부르는 것은 writer 다 (self-invocation 회피).
                //    ⚠️ 그 클래스는 package-private 이라 클래스 리터럴로 지목할 수 없다.
                //       FQN 문자열의 위험(오타·이동이 조용히 통과)은 아래 모수 단언이 막는다
                .that().doNotBelongToAnyOf(SelectCandidateUseCase.class)
                .and().doNotHaveFullyQualifiedName(PR_WRITER)
                .should(사람_전이_메서드를_부른다())
                .as("「사람이 골랐다·물렸다·PR 을 냈다」를 만드는 메서드는 승인 경로를 통해서만 불린다 (S-6)")
                .allowEmptyShould(true);
    }

    @Test
    void 사람_전이_메서드를_직접_부르지_못한다_S6() {
        사람_전이_메서드는_승인_UseCase_만_부른다().check(PRODUCTION);
    }

    @Test
    void 엔티티_직접_호출_우회도_잡는다_양성_대조() {
        assertThatThrownBy(() -> 사람_전이_메서드는_승인_UseCase_만_부른다().check(PROBES))
                .as("미끼(AutoCancelProbe)는 UseCase 를 거치지 않고 cancelSelection 을 부른다 — "
                        + "규칙 ①은 이것을 놓친다")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("AutoCancelProbe")
                // 🔴 PR 생성 전이도 같은 우회가 가능하다 — AutoPrProbe 가 markPrCreated 를
                //    UseCase 없이 직접 부른다. 전이 목록에서 그것을 빼면 여기가 빨개진다
                .as("markPrCreated 가 전이 목록에 없으면 승인 없이 종단으로 보내는 경로가 무방비다")
                .hasMessageContaining("AutoPrProbe");
    }

    /**
     * 🔴 {@code markPrCreated} 가 #23 에서 더해졌다.
     *
     * <p>「사람이 골랐다」(`selectByHuman`)·「물렸다」(`cancelSelection`)와 같은 축이다 —
     * <b>사람의 승인이 있어야만 일어나야 하는 전이</b>. PR 생성은 S-6 의 세 번째 게이트이고,
     * 종단({@code PR_CREATED})으로 가는 전이라 잘못 불리면 되돌릴 수 없다.
     *
     * <p>⚠️ 규칙 ①이 이것을 대신하지 못한다. ①은 <b>타입</b>({@code CreateDraftPrUseCase})을
     * 지목하므로, 자동 실행자가 후보를 직접 꺼내 이 메서드를 부르면 걸리지 않는다.
     */
    private static final Set<String> 사람이_부르는_전이 =
            Set.of("selectByHuman", "cancelSelection", "markPrCreated");

    /**
     * ⚠️ <b>호출과 메서드 참조를 함께 본다.</b> ArchUnit 은 {@code Type::method} 를
     * {@code JavaMethodReference} 로 <b>따로 모델링</b>해서 {@code getMethodCallsFromSelf()} 에
     * 넣지 않는다. 호출만 보면 「람다를 메서드 참조로 정리한다」는 흔한 리팩토링 한 번에
     * 규칙이 조용히 통과시킨다 — 이 UseCase 가 이미 {@code BiFunction} 모양을 쓰고 있어
     * 가설이 아니다.
     *
     * <p>⚠️ <b>엔티티 자신도 면제하지 않는다.</b> 면제하면 엔티티 안에
     * {@code autoCancel() -> cancelSelection()} 같은 래퍼를 두는 것으로 이 규칙과
     * 규칙 ①을 <b>둘 다</b> 우회할 수 있다. 지금 엔티티 안에서 이 둘을 부르는 곳이
     * 없으므로 면제할 이유도 없다.
     *
     * <p>🕳 <b>남는 구멍</b> — 리플렉션은 못 본다. 그리고 {@code isAssignableTo} 라
     * 하위 타입은 잡지만 <b>상위 타입은 못 잡는다</b>: 나중에 이 메서드들을 인터페이스로
     * 추출하면 그 인터페이스 타입으로 부르는 경로가 빠져나간다.
     */
    private static ArchCondition<JavaClass> 사람_전이_메서드를_부른다() {
        return new ArchCondition<>("사람 전이 메서드를 직접 부르거나 참조한다") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                Stream.concat(item.getMethodCallsFromSelf().stream(),
                                item.getMethodReferencesFromSelf().stream())
                        .filter(access -> access.getTargetOwner()
                                .isAssignableTo(ContributionCandidate.class))
                        .filter(access -> 사람이_부르는_전이.contains(access.getName()))
                        .forEach(access -> events.add(
                                SimpleConditionEvent.satisfied(access, access.getDescription())));
            }
        };
    }

    // ────────── ①c markPrCreated 를 부르는 곳이 0개다 — 🔴 S-2 (#21) ──────────

    /**
     * 🔴 <b>허용목록이 아니라 여집합이다.</b>
     *
     * <p>위 ①b 를 그대로 재사용하면 <b>「{@code SelectCandidateUseCase} 만 PR 을 만들 수
     * 있다」</b>가 되어 의미가 뒤집힌다. {@code markPrCreated} 의 정당한 호출자는
     * <b>#23 의 PR 생성기</b>이고, <b>지금은 아무도 아니다.</b>
     *
     * <p>그래서 「0개」를 단언한다. #21 의 재시도 루프는 성공해도 {@code READY_FOR_PR}
     * 에서 멈추고, 그 다음은 <b>세 번째 승인 게이트</b>다 (S-2 · S-6).
     *
     * <h2>#23 이 여는 날 이 테스트가 함께 빨개진다 — 그것이 장치다</h2>
     *
     * <p>{@code CandidateApprovalApiTest} 가 「착수·PR 생성 엔드포인트는 404」를 회귀로
     * 고정해 <b>실행기와 같은 PR 에서 열라</b>고 강제하는 것과 같은 모양이다.
     * 🔴 <b>테스트를 지우고 열지 않는다</b> — 허용 호출자를 명시적으로 더한다.
     *
     * <p>🕳 <b>남는 구멍</b>은 ①b 와 같다 — 리플렉션은 못 보고, 나중에 이 메서드를
     * 인터페이스로 추출하면 그 타입으로 부르는 경로가 빠져나간다.
     */
    private static ArchRule PR_생성_전이를_부르는_곳이_없다() {
        return noClasses()
                .should(PR_생성_전이를_부른다())
                .as("READY_FOR_PR → PR_CREATED 는 세 번째 승인 게이트다 — "
                        + "자동화가 스스로 밟지 않는다 (S-2). 여는 주체는 #23 이고, "
                        + "그때 이 규칙에 허용 호출자를 명시한다")
                // 🔴 true 로 두지 않는다 — 0건 통과가 바로 이 규칙이 경계하는 상태다.
                //    다만 ArchUnit 은 「대상이 0개」일 때만 이것을 보므로, 실제 방어는
                //    아래 양성 대조가 한다
                .allowEmptyShould(true);
    }

    @Test
    void 루프는_PR_생성_전이를_부르지_않는다_S2() {
        PR_생성_전이를_부르는_곳이_없다().check(PRODUCTION);
    }

    /**
     * 🔴 <b>이 단언이 위 규칙을 「있다」에서 「문다」로 바꾼다.</b>
     *
     * <p>운영 코드에 위반이 0건이라 위 테스트는 <b>판정기가 고장 나도 초록</b>이다.
     * 미끼를 무는지 확인하는 것 말고는 그것을 가를 방법이 없다.
     */
    @Test
    void 그_규칙이_실제로_무는지_확인한다_양성_대조_S2() {
        assertThatThrownBy(() -> PR_생성_전이를_부르는_곳이_없다().check(PROBES))
                .as("미끼(AutoPrCreateProbe)를 놓치면 규칙이 아무것도 막지 않는 것이다 — "
                        + "운영 코드에 호출자가 0개라 그 사실이 초록에 가려진다")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("AutoPrCreateProbe");
    }

    /** ①b 와 같은 축이다 — 호출과 <b>메서드 참조</b>를 함께 본다(#73 이 잡은 구멍). */
    private static ArchCondition<JavaClass> PR_생성_전이를_부른다() {
        return new ArchCondition<>("markPrCreated 를 직접 부르거나 참조한다") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                Stream.concat(item.getMethodCallsFromSelf().stream(),
                                item.getMethodReferencesFromSelf().stream())
                        .filter(access -> access.getTargetOwner()
                                .isAssignableTo(ContributionCandidate.class))
                        .filter(access -> "markPrCreated".equals(access.getName()))
                        .forEach(access -> events.add(
                                SimpleConditionEvent.satisfied(access, access.getDescription())));
            }
        };
    }

    // ────────── ② selectedAt 을 쓰는 곳은 selectByHuman 하나다 (S-6) ──────────

    @Test
    void selectedAt_은_selectByHuman_만_채운다_S6() {
        fields()
                .that().areDeclaredIn(ContributionCandidate.class)
                .and().haveName("selectedAt")
                .should(오직_selectByHuman_만_쓴다())
                .as("「사람이 골랐다」의 증거는 사람이 부르는 메서드 하나에서만 만들어진다")
                .check(PRODUCTION);
    }

    @Test
    void 그_필드에_실제로_쓰기가_있다_양성_대조() {
        // 🔴 규칙 ②는 쓰기가 0건이어도 초록이다. 필드가 이름을 바꾸거나 대입이 사라지면
        //    「아무도 안 쓴다」가 되어 규칙이 조용히 무의미해진다
        List<JavaFieldAccess> writes = selectedAtWrites();

        assertThat(writes)
                .as("selectedAt 에 대한 쓰기 접근이 하나도 없다면 규칙이 지키는 대상이 없는 것이다")
                .isNotEmpty();
        assertThat(writes).allSatisfy(access ->
                assertThat(access.getOrigin().getName()).isEqualTo("selectByHuman"));
    }

    private static List<JavaFieldAccess> selectedAtWrites() {
        return PRODUCTION.get(ContributionCandidate.class).getFields().stream()
                .filter(f -> f.getName().equals("selectedAt"))
                .flatMap(f -> f.getAccessesToSelf().stream())
                .filter(a -> a.getAccessType() == JavaFieldAccess.AccessType.SET)
                .toList();
    }

    private static ArchCondition<JavaField> 오직_selectByHuman_만_쓴다() {
        return new ArchCondition<>("오직 selectByHuman 에서만 대입된다") {
            @Override
            public void check(JavaField field, ConditionEvents events) {
                field.getAccessesToSelf().stream()
                        .filter(a -> a.getAccessType() == JavaFieldAccess.AccessType.SET)
                        .forEach(access -> events.add(new SimpleConditionEvent(
                                access,
                                access.getOrigin().getName().equals("selectByHuman"),
                                access.getDescription())));
            }
        };
    }

    // ────────── ②b PR 행을 만드는 곳은 승인 경로 하나다 (S-2 · S-6 · #23) ──────────

    /**
     * 🔴 <b>PR 생성 게이트의 「증거」는 {@code selectedAt} 이 아니라 {@code PullRequest} 행이다.</b>
     *
     * <p>선정은 {@code selectedAt} 필드가 「사람이 골랐다」를 증명하고, 규칙 ②가 그 필드에
     * 쓰는 곳을 하나로 묶는다. PR 생성에는 대응하는 필드가 없다 — 대신 <b>행의 존재</b>가
     * 증거다. 그런데 행은 <b>어느 경로로 만들어졌는지를 스스로 말하지 않는다.</b>
     *
     * <p>그래서 같은 형식으로 묶는다 — {@code PullRequest.draftFor} 는 <b>유일한 생성
     * 경로</b>이고, 그것을 부르는 운영 코드가 승인 경로 하나뿐임을 여기서 고정한다.
     * 이것이 없으면 아무 컴포넌트나 PR 행을 만들어 붙일 수 있고, 그 순간
     * 「사람이 한 번 승인했다」를 사후에 셀 수 없다.
     */
    @Test
    void PR_행을_만드는_것은_승인_경로뿐이다_S6() {
        Set<String> callers = PRODUCTION.stream()
                .filter(type -> Stream.concat(type.getMethodCallsFromSelf().stream(),
                                type.getMethodReferencesFromSelf().stream())
                        .anyMatch(access -> access.getTargetOwner()
                                .isAssignableTo(PullRequest.class)
                                && access.getName().equals("draftFor")))
                .map(JavaClass::getName)
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));

        assertThat(callers)
                .as("PullRequest.draftFor 가 유일한 생성 경로다. 그것을 부르는 곳이 늘면 "
                        + "승인 없이 PR 행이 생기고, 그러면 「사람이 승인했다」가 증거로 무의미해진다")
                .containsExactly(PR_WRITER);
    }

    // ────────── ③ 통행증은 adapter/in 경계를 넘지 않는다 (S-5) ──────────

    /**
     * ⚠️ 대상을 <b>문자열이 아니라 클래스 리터럴</b>로 지목한다. FQN 문자열로 쓰면
     * 패키지를 옮기거나 오타가 나도 컴파일이 통과하고, 그 규칙은 <b>영원히 초록</b>이 된다.
     */
    private static ArchRule 통행증은_adapter_in_을_넘지_않는다() {
        return noClasses()
                .that().resideInAPackage("..adapter.in..")
                .should().dependOnClassesThat().belongToAnyOf(PolicyClearance.class)
                .as("🔴 컨트롤러 파라미터·요청 바디로 받는 순간 외부가 통행증을 주입할 수 있고, "
                        + "그러면 S-5 게이트가 껍데기가 된다")
                .allowEmptyShould(true);
    }

    @Test
    void 통행증은_adapter_in_에_나타나지_않는다_S5() {
        통행증은_adapter_in_을_넘지_않는다().check(PRODUCTION);
    }

    @Test
    void 통행증_주입도_잡는다_양성_대조() {
        // 🔴 이 규칙도 지금 위반 0건이다 — 어떤 컨트롤러도 통행증을 모른다.
        //    규칙 ①·②·④에는 양성 대조가 있는데 여기만 없으면, 대상을 잘못 지목해도 초록이다
        assertThatThrownBy(() -> 통행증은_adapter_in_을_넘지_않는다().check(PROBES))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ClearanceInjectionProbe");
    }

    // ────────── ④ candidate 가 repository 애그리거트를 넘보지 않는다 (규율 ④) ──────────

    @Test
    void candidate_는_repository_의_어댑터를_모른다() {
        noClasses()
                .that().resideInAPackage("com.ossagent.candidate..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.ossagent.repository.adapter..")
                .as("남의 Spring Data 인터페이스·컨트롤러를 직접 import 하지 않는다 — "
                        + "자기 Repository 직접 주입(완화 ②)은 자기 도메인에만 해당한다")
                .check(PRODUCTION);
    }

    @Test
    void 남의_UseCase_는_application_에서만_부른다() {
        // ⚠️ 규율 ④는 남의 UseCase 호출을 「막는」 것이 아니라 그것 「만」 허용한다 —
        //    AnalyzeIssuesUseCase → AnalyzeRepositoryPolicyUseCase 가 정당한 경로다.
        //    막아야 하는 것은 그 호출이 domain·adapter 로 내려가는 것이다(규율 ①)
        noClasses()
                .that().resideInAPackage("com.ossagent.candidate..")
                .and().resideOutsideOfPackage("com.ossagent.candidate.application..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.ossagent.repository.application..")
                .as("domain 이 남의 UseCase 를 부르면 의존이 바깥으로 뒤집히고, "
                        + "adapter/in 이 부르면 트랜잭션 경계를 건너뛴다")
                .check(PRODUCTION);
    }

    @Test
    void candidate_가_repository_domain_에서_보는_것은_값_타입뿐이다() {
        noClasses()
                .that().resideInAPackage("com.ossagent.candidate..")
                .should().dependOnClassesThat(
                        resideInAPackage("com.ossagent.repository.domain..")
                                .and(not(belongToAnyOf(
                                        PolicyClearance.class, RepositoryCoordinates.class,
                                        // ── #16 구현 계획이 쓰는 값들 ───────────────────
                                        // 전부 record 다. @Entity 가 아니므로 떼어낼 때
                                        // 고칠 것이 없다 — 규칙이 허용하려던 바로 그 종류.
                                        //
                                        // ⚠ 허용 목록이라 모르는 타입은 기본이 차단이다.
                                        //    여기 줄을 더하는 것이 곧 「값임을 확인했다」는
                                        //    선언이고, 그 확인이 리뷰에 보인다.
                                        //
                                        // ⚠ 🔴 직접 의존하는 것만 올린다. RepositoryContext 가
                                        //    품는 ContextBudget 등은 candidate 가 이름을 부르지
                                        //    않아 컴파일 결합이 없다 — 없는 결합을 목록에 올리면
                                        //    「있는 결합」처럼 읽히고, dependOnClassesThat 이
                                        //    직접 의존만 보므로 죽은 줄이 된다.
                                        //    나중에 candidate 가 실제로 부르기 시작하면 그때
                                        //    빌드가 빨개진다 — 목록은 그렇게 자기유지된다
                                        ContributionConstraints.class,
                                        RepositoryContext.class,
                                        // 🔴 SelectedFile 은 대상 저장소 「파일 내용」을 든다.
                                        //    값이라 규율 ④ 예외인 것과, 스크럽된 값이라 건너가도
                                        //    안전한 것은 다른 보증이다. 후자는 #15 가 세웠다 —
                                        //    compact 생성자가 TokenRedactor 를 강제하고, String 을
                                        //    그대로 받는 생성 경로가 없다. 둘 다 성립해서 올린다
                                        SelectedFile.class))))
                .as("값 타입만 예외다 — 엔티티가 넘어오면 컴파일 결합이 생겨 떼어낼 때 코드를 고쳐야 한다. "
                        + "새 타입을 더할 때는 그것이 정말 값(record·enum)인지 먼저 본다")
                .check(PRODUCTION);
    }

    @Test
    void 그_예외가_실제로_쓰이고_있다_양성_대조() {
        // 🔴 위 규칙은 candidate 가 repository.domain 을 아예 안 보면 0건 검사로 초록이다.
        //    그러면 「값 타입만 예외」라는 문장이 무엇도 지키지 않는다
        assertThat(PRODUCTION.get(ContributionCandidate.class).getDirectDependenciesFromSelf())
                .as("startImplementing 이 PolicyClearance 를 받는다 — 그 예외가 살아 있어야 한다")
                .anySatisfy(dependency -> assertThat(dependency.getTargetClass().getName())
                        .isEqualTo(PolicyClearance.class.getName()));
    }

    @Test
    void repository_도_candidate_애그리거트를_모른다() {
        // ⚠️ 규율 ④는 방향이 없다. 위 규칙들이 candidate → repository 만 보고 있어서
        //    반대 방향이 열려 있었다 — repository 쪽 자동 실행자(ScanExecutor 등)가
        //    후보 엔티티나 그 Repository 를 직접 꺼내는 경로가 그리로 난다
        noClasses()
                .that().resideInAPackage("com.ossagent.repository..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "com.ossagent.candidate.domain..",
                        "com.ossagent.candidate.adapter..")
                .as("남의 애그리거트 엔티티·Spring Data 인터페이스를 직접 import 하지 않는다. "
                        + "필요하면 candidate 의 UseCase 또는 값 타입으로 받는다")
                .check(PRODUCTION);
    }

    // ────────── 모수 — 규칙들이 실제로 무엇을 훑었는가 ──────────

    @Test
    void 판정_대상이_임포트에_실재한다_모수() {
        // 🔴 규칙 ①·①b·③ 에는 미끼(물림)만 있고 모수가 없었다.
        //    PRODUCTION 이 통째로 비면 ②·④ 의 PRODUCTION.get(...) 이 터져 잡히지만,
        //    **부분 누락**은 아무도 못 잡는다 — 미끼 테스트는 PROBES 를 따로 임포트하므로
        //    그대로 초록이고, ②·④ 는 candidate 패키지만 본다.
        //    여기서 각 규칙이 「무엇을 훑었는가」를 이름으로 못 박는다.

        assertThat(PRODUCTION.contain(SelectCandidateUseCase.class))
                .as("규칙 ① 이 지목하는 타입이 임포트에 없으면 그 규칙은 공허하다")
                .isTrue();
        assertThat(PRODUCTION.contain(CreateDraftPrUseCase.class))
                .as("규칙 ① 의 두 번째 게이트(#23). 목록에만 있고 임포트에 없으면 공허하다")
                .isTrue();
        // 🔴 FQN 문자열로 지목하는 것이 둘 있다(①b 면제 · ②b 기대값). 문자열은 오타나
        //    패키지 이동이 컴파일에 잡히지 않으므로, 실재를 여기서 확인한다 —
        //    이 단언이 없으면 「면제 대상이 없어서 통과」와 「위반이 없어서 통과」가 같아진다
        assertThat(PRODUCTION.contain(PR_WRITER))
                .as("CandidatePrWriter 가 임포트에 없다 — ①b 면제와 ②b 기대값이 둘 다 공허해진다")
                .isTrue();
        assertThat(PRODUCTION.contain(PullRequest.class))
                .as("규칙 ②b 가 호출을 추적하는 타입")
                .isTrue();
        assertThat(PRODUCTION.contain(ContributionCandidate.class))
                .as("규칙 ①b 가 호출을 추적하는 타입")
                .isTrue();
        assertThat(PRODUCTION.contain(PolicyClearance.class))
                .as("규칙 ③ 이 adapter/in 밖으로 막는 타입")
                .isTrue();

        // ⚠️ 가장 중요한 모수다. 규칙 ①을 거부목록에서 허용목록으로 뒤집은 이유가
        //    「진짜 자동 실행자가 ..adapter.in.scheduler.. 밖에 있다」였고,
        //    그 밖이란 곧 repository.application 이다. 그 패키지가 임포트에서 빠지면
        //    ①은 정확히 자기가 덮으려던 것을 안 보면서 초록이 된다
        assertThat(PRODUCTION.stream()
                .filter(c -> c.getPackageName().startsWith("com.ossagent.repository.application"))
                .count())
                .as("규칙 ①이 덮으려는 자동 실행자(ScanExecutor·LaunchScanUseCase)가 사는 패키지")
                .isGreaterThan(0);

        // 🔴 미끼는 운영 판정에서 빠져야 한다 — 빠지지 않으면 규칙들이 항상 빨갛다.
        //    모수를 세면서 이것도 함께 본다(둘은 같은 ImportOption 체인이 결정한다)
        assertThat(PRODUCTION.stream()
                .filter(c -> c.getPackageName().startsWith(PROBE_PACKAGE))
                .count())
                .as("미끼가 운영 판정에 섞이면 안 된다")
                .isZero();
    }
}
