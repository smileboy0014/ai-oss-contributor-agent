package com.ossagent.candidate.domain;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;

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
    private static ArchRule 승인_게이트는_web_어댑터만_부른다() {
        return noClasses()
                .that().resideOutsideOfPackage("com.ossagent.candidate.adapter.in.web..")
                .and().doNotBelongToAnyOf(SelectCandidateUseCase.class)
                .should().dependOnClassesThat().belongToAnyOf(SelectCandidateUseCase.class)
                .as("승인 게이트를 부르는 것은 사람이 누르는 문(web) 하나여야 한다 (S-6)")
                .allowEmptyShould(true);
    }

    @Test
    void 승인_게이트를_부르는_것은_web_어댑터뿐이다_S6() {
        승인_게이트는_web_어댑터만_부른다().check(PRODUCTION);
    }

    @Test
    void 그_규칙이_실제로_무는지_확인한다_양성_대조() {
        assertThatThrownBy(() -> 승인_게이트는_web_어댑터만_부른다().check(PROBES))
                .as("미끼(AutoSelectProbe)를 놓치면 규칙이 고장 난 것이다 — "
                        + "운영 코드에는 위반이 0건이라 규칙이 잘못 쓰여 있어도 초록이다")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("AutoSelectProbe");
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
                .that().doNotBelongToAnyOf(SelectCandidateUseCase.class)
                .should(사람_전이_메서드를_부른다())
                .as("「사람이 골랐다·물렸다」를 만드는 메서드는 승인 UseCase 를 통해서만 불린다 (S-6)")
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
                .hasMessageContaining("AutoCancelProbe");
    }

    private static final Set<String> 사람이_부르는_전이 = Set.of("selectByHuman", "cancelSelection");

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
}

