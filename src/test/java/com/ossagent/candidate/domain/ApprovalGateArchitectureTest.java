package com.ossagent.candidate.domain;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;

import com.ossagent.repository.domain.PolicyClearance;
import com.ossagent.repository.domain.RepositoryCoordinates;
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

    // ────────── ① 스케줄러·이벤트는 승인 게이트에 닿지 못한다 (S-6) ──────────

    private static ArchRule 자동_진입점은_승인_게이트를_부르지_못한다() {
        return noClasses()
                .that().resideInAnyPackage("..adapter.in.scheduler..", "..adapter.in.event..")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("com.ossagent.candidate.application.SelectCandidateUseCase")
                .as("스케줄러·이벤트가 승인 게이트를 부르면 「사람이 고른다」가 무의미해진다 (S-6)")
                .allowEmptyShould(true);
    }

    @Test
    void 스케줄러와_이벤트는_승인_게이트를_부르지_못한다_S6() {
        자동_진입점은_승인_게이트를_부르지_못한다().check(PRODUCTION);
    }

    @Test
    void 그_규칙이_실제로_무는지_확인한다_양성_대조() {
        assertThatThrownBy(() -> 자동_진입점은_승인_게이트를_부르지_못한다().check(PROBES))
                .as("미끼(AutoSelectProbe)를 놓치면 규칙이 고장 난 것이다 — "
                        + "운영 코드의 scheduler 는 게이트를 안 부르므로 규칙이 위반 0건으로 초록이다")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("AutoSelectProbe");
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

    @Test
    void 통행증은_adapter_in_에_나타나지_않는다_S5() {
        noClasses()
                .that().resideInAPackage("..adapter.in..")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("com.ossagent.repository.domain.PolicyClearance")
                .as("🔴 컨트롤러 파라미터·요청 바디로 받는 순간 외부가 통행증을 주입할 수 있고, "
                        + "그러면 S-5 게이트가 껍데기가 된다")
                .check(PRODUCTION);
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
                                        PolicyClearance.class, RepositoryCoordinates.class))))
                .as("값 타입(PolicyClearance · RepositoryCoordinates)만 예외다 — "
                        + "엔티티가 넘어오면 컴파일 결합이 생겨 떼어낼 때 코드를 고쳐야 한다")
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
}
