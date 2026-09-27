package com.ossagent.pullrequest;

import static org.assertj.core.api.Assertions.assertThat;

import com.ossagent.pullrequest.adapter.out.github.GitHubWriteClient;
import com.ossagent.support.testing.ExternalAdapters;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S-1 · S-2 를 <b>구조</b>로 고정한다.
 *
 * <h2>🔴 열거가 아니라 여집합으로 쓴다</h2>
 *
 * <p>「{@code GitHubWriteClient} 안에서 어설션을 안 거치는 메서드가 하나뿐」만 검사하면
 * <b>한 타입 안</b>만 세는 열거다. 그런데 S-1 이 못 박은 위협 모형은 정확히 그 바깥이다 —
 * {@code spring-boot-starter-web} 이 {@code RestClient.Builder} 를 자동설정 빈으로 올려
 * <b>아무 컴포넌트나</b> {@code builder.build().post(...)} 를 할 수 있다.
 *
 * <p>그래서 덮개를 먼저 친다 — <b>「쓰기 HTTP 호출을 하는 타입이 정확히 하나」</b>.
 * 그 안의 예외를 ③b 가 센다.
 *
 * <h2>🔴 모수를 단언한다</h2>
 *
 * <p>「쓰기 호출」을 메서드 이름으로만 판정하면, 구현이 {@code method(HttpMethod.POST)} 로
 * 가는 순간 대상이 <b>0건</b>이 되고 규칙은 조용히 초록이다. 그 상태에서는
 * <b>검사하려던 본체를 못 찾았다는 사실조차 드러나지 않는다.</b> 그래서 「정확히 1개」를
 * 단언하고, 판정 축에 {@code HttpMethod} 상수 참조를 더한다.
 */
class ForkPublishArchitectureTest {

    private static final String BASE_PACKAGE = "com.ossagent";

    /** 쓰기 동사. {@code get}·{@code head} 는 없다 — 읽기는 이 가드의 대상이 아니다. */
    private static final Set<String> WRITE_VERBS = Set.of("post", "put", "patch", "delete");

    /** {@code restClient.method(HttpMethod.POST)} 처럼 동사를 값으로 넘기는 형태. */
    private static final Set<String> WRITE_METHOD_CONSTANTS =
            Set.of("POST", "PUT", "PATCH", "DELETE");

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE_PACKAGE);

    // ── S-1 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("쓰기 HTTP 호출을 하는 타입은 GitHubWriteClient 하나뿐이다")
    void 쓰기_표면은_한_곳이다_S1() {
        Set<String> writers = PRODUCTION.stream()
                .filter(ForkPublishArchitectureTest::performsHttpWrite)
                .map(JavaClass::getName)
                .collect(Collectors.toCollection(TreeSet::new));

        // 🔴 모수 단언 — 0건이면 판정 축이 실제 구현을 못 보고 있다는 뜻이다
        assertThat(writers)
                .as("쓰기 표면이 늘면 owner 어설션(S-1)을 거치지 않는 경로가 생긴다. "
                        + "0건이면 이 가드가 아무것도 검사하지 못하는 상태다")
                .containsExactly(GitHubWriteClient.class.getName());
    }

    @Test
    @DisplayName("owner 어설션을 거치지 않고 전송하는 메서드는 createFork 하나뿐이다")
    void 어설션_우회는_fork_생성뿐이다_S1() {
        JavaClass writeClient = PRODUCTION.get(GitHubWriteClient.class);

        Set<String> bypassing = writeClient.getMethods().stream()
                .filter(method -> calls(method, "send"))
                .filter(method -> !calls(method, "assertForkOwner"))
                .map(JavaMethod::getName)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(bypassing)
                .as("fork 생성은 upstream 히스토리를 바꾸지 않아 예외로 뒀다. "
                        + "그 예외가 늘어나면 S-1 이 무너진다")
                .containsExactly("createFork");
    }

    @Test
    @DisplayName("upstream 으로 가는 쓰기 경로는 fork 생성 리터럴 하나뿐이다")
    void upstream_쓰기는_forks_뿐이다_S1() {
        JavaClass writeClient = PRODUCTION.get(GitHubWriteClient.class);

        // ForkRef 를 받지 않는 공개 쓰기 메서드 = 좌표를 밖에서 받는 메서드
        Set<String> takingRawOwner = writeClient.getMethods().stream()
                .filter(method -> method.getModifiers().toString().contains("PUBLIC"))
                .filter(method -> method.getRawParameterTypes().stream()
                        .noneMatch(type -> type.getName().endsWith(".ForkRef")))
                .filter(method -> calls(method, "send"))
                .map(JavaMethod::getName)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(takingRawOwner)
                .as("ForkRef 를 요구하지 않는 쓰기 메서드는 upstream 좌표를 받을 수 있다")
                .containsExactly("createFork");
    }

    // ── S-2 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PR 생성·머지·ready 전환 경로가 존재하지 않는다")
    void PR_경로가_없다_S2() {
        Set<String> offenders = PRODUCTION.stream()
                .filter(type -> type.getPackageName().startsWith(BASE_PACKAGE + ".pullrequest"))
                .filter(ForkPublishArchitectureTest::mentionsPullRequestApi)
                .map(JavaClass::getName)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(offenders)
                .as("Draft PR 생성은 세 번째 승인 게이트 뒤에 있어야 한다 — #23. "
                        + "여기서 만들면 게이트가 사라지고 S-2 까지 뚫린다")
                .isEmpty();
    }

    // ── 트랜잭션 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("이 도메인은 트랜잭션을 열지 않는다")
    void 트랜잭션_안에서_대외_호출하지_않는다() {
        Set<String> transactional = PRODUCTION.stream()
                .filter(type -> type.getPackageName().startsWith(BASE_PACKAGE + ".pullrequest"))
                .filter(type -> type.isAnnotatedWith("org.springframework.transaction.annotation.Transactional")
                        || type.getMethods().stream().anyMatch(method -> method.isAnnotatedWith(
                                "org.springframework.transaction.annotation.Transactional")))
                .map(JavaClass::getName)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(transactional)
                .as("GitHub 호출이 트랜잭션에 들어가면 지연이 DB 커넥션·락 점유로 번진다")
                .isEmpty();
    }

    // ── 판정 ────────────────────────────────────────────────────────────

    /**
     * 「쓰기 HTTP 호출」의 판정 축은 <b>둘의 합집합</b>이다.
     *
     * <ol>
     *   <li>네트워크 클라이언트 타입의 {@code post}·{@code put}·{@code patch}·{@code delete} 호출</li>
     *   <li>{@code HttpMethod.POST} 류 <b>상수 참조</b> — 동사를 값으로 넘기는 형태</li>
     * </ol>
     *
     * <p>①만 두면 {@code method(HttpMethod.POST)} 를 못 보고, ②만 두면 직접 호출을 못 본다.
     */
    private static boolean performsHttpWrite(JavaClass type) {
        boolean callsWriteVerb = type.getMethodCallsFromSelf().stream()
                .anyMatch(call -> WRITE_VERBS.contains(call.getName())
                        && ExternalAdapters.networkClientTypes()
                                .contains(call.getTargetOwner().getName()));
        boolean referencesWriteConstant = type.getFieldAccessesFromSelf().stream()
                .anyMatch(access -> "org.springframework.http.HttpMethod"
                        .equals(access.getTargetOwner().getName())
                        && WRITE_METHOD_CONSTANTS.contains(access.getName()));
        return callsWriteVerb || referencesWriteConstant;
    }

    /**
     * {@code pulls}·{@code merge}·{@code ready_for_review} 는 이 PR 에 존재해서는 안 되는 경로다.
     *
     * <p>⚠️ 아래 이름들은 <b>금지 대상을 적은 것</b>이지 호출이 아니다. {@code safety-boundary-check.sh}
     * 의 S-2 패턴이 문자열만 보므로 여기서 발화하는데, <b>보호할 값이 애초에 없는 오탐</b>이다 —
     * 실제 머지·리뷰 요청 호출은 이 저장소 어디에도 없고 그것을 <b>검사하는 것</b>이 이 메서드다.
     */
    private static boolean mentionsPullRequestApi(JavaClass type) {
        return type.getMethodCallsFromSelf().stream()
                .map(JavaMethodCall::getName)
                // safety-ok: 금지 목록 리터럴이다. 호출이 아니라 「이런 호출이 없다」를 검사하는 쪽이다
                .anyMatch(name -> name.equals("mergePullRequest") || name.equals("readyForReview")
                        // safety-ok: 위와 같다 — 검사 대상 이름이지 호출이 아니다
                        || name.equals("requestReviewers"));
    }

    private static boolean calls(JavaMethod method, String targetName) {
        return method.getMethodCallsFromSelf().stream()
                .anyMatch(call -> call.getName().equals(targetName));
    }
}
