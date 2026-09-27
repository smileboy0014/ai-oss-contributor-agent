package com.ossagent.agent.adapter.out.git;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 🔴 이 단계에 <b>원격 쓰기 경로가 없다</b>는 것을 고정한다 — S-1 · #18.
 *
 * <h2>왜 이 테스트가 필요해졌나</h2>
 *
 * <p>이 PR 이 <b>JGit 을 들여온다.</b> 그 순간 {@code git.push()} 는 아무 클래스에서나
 * <b>API 한 줄 거리</b>가 된다. owner 어설션은 <b>#22</b> 에 있고 여기에는 없으므로,
 * 이 단계에서 push 가 생기면 <b>어설션 앞에 쓰기 경로가 하나 더</b> 생긴다.
 *
 * <p>S-1 이 「쓰기 메서드를 만들지 않았으니 안전하다」를 방어로 치지 않는 이유와 같다 —
 * <b>좁은 표면은 의도 표기이지 강제력이 아니다.</b> 강제는 이 테스트다.
 *
 * <h2>🔴 증거 등급 — 문자열이 아니라 ArchUnit</h2>
 *
 * <p>{@code safety-boundary-check.sh} 는 소스 <b>문자열</b>을 본다. 그것으로는
 * {@code git.push()} 를 잡을 수 없다 — 변수명·정적 임포트·메서드 참조로 모양이 바뀐다.
 * ArchUnit 은 <b>바이트코드의 호출</b>을 보므로 그 변형에 흔들리지 않는다.
 *
 * <p>⚠️ 그래도 <b>리플렉션은 못 본다.</b> 한계를 먼저 적는다 —
 * {@code testing-philosophy.md} 「한계는 오탐이 아니라 우회를 적는다」.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class JGitPushAbsenceTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.ossagent");

    @Test
    @DisplayName("운영 코드에 JGit push 호출이 없다 — S-1")
    void 운영_코드에_JGit_push_호출이_없다_S1() {
        noClasses()
                .should().callMethodWhere(
                        com.tngtech.archunit.core.domain.JavaCall.Predicates.target(
                                com.tngtech.archunit.core.domain.properties.HasOwner.Predicates
                                        .With.owner(
                                                com.tngtech.archunit.core.domain.JavaClass.Predicates
                                                        .assignableTo(org.eclipse.jgit.api.Git.class)))
                                .and(com.tngtech.archunit.core.domain.properties.HasName.Predicates
                                        .name("push")))
                .as("이 단계는 원격에 쓰지 않는다 — Fork push 는 #22 의 owner 어설션 뒤에서만 (S-1)")
                .check(PRODUCTION);
    }

    /**
     * ⚠️ <b>0건 검사로 통과하지 않게 한다.</b>
     *
     * <p>임포트 경로가 틀려 <b>아무 클래스도 못 읽으면</b> 위 규칙은 항상 초록이다 —
     * 이 저장소에서 가장 많이 반복된 실패다({@code testing-philosophy.md} 요구 1).
     * 모수를 함께 단언한다.
     */
    @Test
    @DisplayName("모수 — 검사기가 JGit 을 쓰는 실제 클래스를 훑었다")
    void 모수_JGit_사용처를_실제로_훑었다() {
        assertThat(PRODUCTION.contain(JGitWorkspaceSource.class.getName()))
                .as("JGit 을 실제로 쓰는 클래스가 임포트에 없다 — 위 규칙은 0건을 검사하고 초록이 된다")
                .isTrue();
    }
}
