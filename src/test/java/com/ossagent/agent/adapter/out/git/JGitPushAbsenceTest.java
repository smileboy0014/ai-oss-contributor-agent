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
     * 🔴 <b>{@code Git.push()} 만 막는 것으로는 좁다</b> — 우회 경로가 셋 더 있다.
     *
     * <p>위 규칙은 {@code Git} 타입의 {@code push} 라는 <b>이름</b>만 본다. 그런데 JGit 에는
     * 같은 일을 하는 다른 문이 있다.
     *
     * <table border="1">
     *   <caption>우회 경로</caption>
     *   <tr><th>경로</th><th>위 규칙에</th></tr>
     *   <tr><td>{@code new PushCommand(repo).call()}</td><td>❌ 걸리지 않는다 — {@code Git} 을 안 거친다</td></tr>
     *   <tr><td>{@code Transport.open(...).push(...)}</td><td>❌ 소유 타입이 {@code Transport} 다</td></tr>
     *   <tr><td>{@code PushConnection} 직접 사용</td><td>❌ 〃</td></tr>
     * </table>
     *
     * <p>그래서 <b>타입 의존</b>으로 막는다 — 이름이 아니라 <b>그 타입을 쓰는가</b>를 본다.
     * 위 규칙(호출 기준)과 함께 두 겹이다.
     *
     * <p>🕳 <b>한계</b> — 리플렉션은 여전히 못 본다. 그리고 {@code Transport} 는 fetch 에도
     * 쓰이는 타입이라, 나중에 우리가 fetch 를 직접 다루게 되면 <b>이 규칙이 오탐을 낸다.</b>
     * 그때는 규칙을 지우지 말고 <b>메서드 단위로 좁힌다</b> — 지우면 push 도 함께 열린다.
     */
    @Test
    @DisplayName("운영 코드가 JGit push 계열 타입을 쓰지 않는다 — S-1 우회 차단")
    void 운영_코드가_JGit_push_계열_타입을_쓰지_않는다_S1() {
        // ⚠ 정규식은 **완전 일치**다. org.eclipse.jgit.transport.TransportException 같은
        //   이웃 타입은 걸리지 않는다 — 넓히면 오탐이 나고, 오탐이 나면 규칙이 지워진다
        noClasses()
                .should().dependOnClassesThat()
                .haveNameMatching(
                        "org\\.eclipse\\.jgit\\.(api\\.PushCommand"
                                + "|transport\\.Transport|transport\\.PushConnection)")
                .as("Git.push() 를 우회하는 문이 셋 있다 — 타입 의존으로 함께 막는다 (S-1)")
                .check(PRODUCTION);
    }

    /**
     * 🔴 <b>익명 clone 을 강제한다</b> — S-4.
     *
     * <p>자격증명을 URL·프로바이더로 실으면 <b>토큰이 {@code .git/config} 에 파일로 앉고</b>,
     * 그 워크스페이스를 샌드박스가 <b>RW 로 바인드</b>해 신뢰할 수 없는 빌드 스크립트가 읽는다.
     *
     * <p>⚠️ <b>가드 없이는 한 줄로 뚫린다.</b> {@code .setCredentialsProvider(...)} 를 더하면
     * 기존 가드가 <b>전부 초록으로 통과</b>한다 —
     * {@code safety-boundary-check.sh} 는 clone 자격증명을 보지 않고,
     * 위 push 규칙은 {@code push} 라는 이름만 본다.
     *
     * <p>S-1 이 「좁은 표면은 의도 표기이지 강제력이 아니다」를 못 박은 것과 같은 구조다.
     * javadoc 이 「🔴 익명」이라고 단언하고 있으므로 <b>그 단언을 강제로 만든다.</b>
     *
     * <p>🕳 <b>한계</b> — 타입 의존을 보므로 리플렉션은 못 본다. 그리고 URL 에
     * {@code user:pass@} 를 문자열로 박는 경로는 <b>이 규칙이 아니라</b>
     * 「워크스페이스에 시크릿이 없다」 쪽 검사가 봐야 한다(D 에서 넣는다).
     */
    @Test
    @DisplayName("운영 코드가 JGit 자격증명 프로바이더를 쓰지 않는다 — S-4")
    void 운영_코드가_JGit_자격증명_프로바이더를_쓰지_않는다_S4() {
        noClasses()
                .should().dependOnClassesThat()
                .areAssignableTo(org.eclipse.jgit.transport.CredentialsProvider.class)
                .as("clone 은 익명이어야 한다 — 토큰이 .git/config 에 앉으면 샌드박스가 읽는다 (S-4)")
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
