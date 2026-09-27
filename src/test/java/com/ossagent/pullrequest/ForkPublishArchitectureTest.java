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
import java.util.List;
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

    /**
     * 양성 대조용 — 미끼만. 운영 판정({@link #PRODUCTION})에서는
     * {@code DO_NOT_INCLUDE_TESTS} 가 이것을 빼므로 오염되지 않는다.
     *
     * <p>{@code ApprovalGateArchitectureTest} 와 같은 수법이다.
     */
    private static final JavaClasses PROBES = new ClassFileImporter()
            .importPackages("com.ossagent.support.testing.probe");

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

    /**
     * 🔴 <b>면제가 둘이 된 것은 #23 이다.</b> 「하나뿐」에서 「정확히 둘」로 바꾸면서
     * 이름과 단언 메시지를 함께 고쳤다 — 이름이 사실과 어긋나면 다음 사람의 판단 근거가
     * 오염된다.
     *
     * <p>두 면제의 판정 근거는 {@code GitHubWriteClient} 의 javadoc 표에 있고,
     * 셋째 행({@code 되돌릴 수 있나})이 <b>다르다.</b> PR 은 닫을 수 있어도 메일 알림이
     * 회수되지 않는다. 그래서 {@code createDraftPullRequest} 의 실질 방어는 면제 근거가
     * 아니라 <b>호출 위치</b>이고, 그것은 {@code ApprovalGateArchitectureTest} 가 본다.
     */
    @Test
    @DisplayName("owner 어설션을 거치지 않고 전송하는 메서드는 정확히 둘이다")
    void 어설션_우회는_fork_생성과_PR_생성뿐이다_S1() {
        JavaClass writeClient = PRODUCTION.get(GitHubWriteClient.class);

        Set<String> bypassing = writeClient.getMethods().stream()
                .filter(method -> calls(method, "send"))
                .filter(method -> !calls(method, "assertForkOwner"))
                .map(JavaMethod::getName)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(bypassing)
                .as("fork 생성과 Draft PR 생성만 upstream 좌표로 간다. "
                        + "둘 다 upstream 히스토리를 바꾸지 않는다. "
                        + "이 목록이 셋이 되면 S-1 이 무너진다 — 새 면제는 "
                        + "GitHubWriteClient 의 판정표 세 행을 모두 통과해야 한다")
                .containsExactly("createDraftPullRequest", "createFork");
    }

    /**
     * 🔴 이 규칙이 세는 것은 <b>횟수가 아니라 「upstream 좌표를 밖에서 받는 쓰기」</b>다.
     * 그 집합이 둘이 되는 것이 S-1 약화의 <b>본체</b>이고, #23 계획 초안은 이 규칙을
     * 언급조차 하지 않았다 — 검토에서 잡혔다.
     *
     * <p>{@code createDraftPullRequest} 는 {@code String} 둘이 아니라
     * {@code RepositoryCoordinates} 값 타입을 받아 좌표 면을 좁혔다({@code [A-Za-z0-9._-]+}).
     * <b>그것이 면제를 정당화하지는 않는다</b> — 좁혔을 뿐이다.
     */
    @Test
    @DisplayName("upstream 좌표를 밖에서 받는 쓰기는 fork 생성과 PR 생성 둘뿐이다")
    void upstream_쓰기는_fork_와_PR_뿐이다_S1() {
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
                .containsExactly("createDraftPullRequest", "createFork");
    }

    // ── S-2 ─────────────────────────────────────────────────────────────

    /**
     * 🔴 <b>쓰기가 닿는 GitHub 엔드포인트를 화이트리스트로 고정한다</b> — S-2.
     *
     * <p>⚠️ 초안은 {@code mergePullRequest}·{@code readyForReview}·{@code requestReviewers} 라는
     * <b>우리가 만든 적 없는 메서드 이름</b>을 찾았다. 실제 S-2 위반의 모양은
     * {@code writeClient.post(fork, "pulls", body, UNSAFE)} 이고 <b>그 가드에 걸리지 않는다</b> —
     * 위반 표본이 0건이라 영구 초록이었고, 「막혔다」가 아니라 <b>검사 축이 실제 입력 공간에
     * 닿지 않는</b> 것이었다({@code testing-philosophy.md} 가드 요구 4).
     *
     * <p>축을 <b>subPath 리터럴</b>로 옮겼다. 새 엔드포인트를 쓰면 여기 등록해야 하고,
     * 등록하는 순간 <b>리뷰가 그것을 본다.</b>
     */
    private static final Set<String> ALLOWED_SUB_PATHS = Set.of(
            "forks", "merge-upstream",
            "git/blobs", "git/trees", "git/commits", "git/refs", "git/refs/heads/");

    /** {@code writeClient.post(fork, "git/refs", …)} 의 두 번째 인자 리터럴을 뽑는다. */
    private static final java.util.regex.Pattern SUB_PATH_LITERAL = java.util.regex.Pattern.compile(
            "(?:writeClient|client)\\.(?:post|patch|delete)\\([^,;]+,\\s*\"([^\"]*)\"");

    /**
     * 🔴 파일 하나도, 디렉터리 하나도 아니라 <b>운영 소스 전체</b>다 — 아래 javadoc 참조.
     */
    private static final java.nio.file.Path PRODUCTION_SOURCE_ROOT =
            java.nio.file.Path.of("src/main/java/com/ossagent");

    /**
     * 🔴 <b>#23 검토가 찾은 구멍 — 가드의 입력 공간이 파일 하나였다</b>
     * ({@code testing-philosophy.md} 가드 요구 4「입력 도달」).
     *
     * <p>원래 이 테스트는 {@code Path.of(".../GitHubForkPublisher.java")} 로 <b>파일 경로를
     * 하드코딩</b>하고 있었다. 그래서 새 어댑터({@code GitHubDraftPrPublisher})는
     * <b>가드가 보는 입력 밖</b>이었고, 거기에
     * {@code post(fork, "issues/12/comments", …)} 를 써도 <b>아무것도 빨개지지 않았다.</b>
     * 그것이 S-2 의 「대상 저장소에 글을 남기는 모든 경로」를 지키는 <b>유일한</b> 장치였다.
     *
     * <p>「위반이 0건이라 초록」이 아니라 <b>「검사 대상이 아니라서 초록」</b>이었고,
     * 둘은 구분되지 않는다.
     *
     * <h2>🔴 디렉터리로도 부족했다 — 안전 리뷰가 한 겹 더 짚었다</h2>
     *
     * <p>처음 고칠 때는 {@code pullrequest/adapter/out/github} <b>디렉터리</b>로 넓혔다.
     * 그런데 {@link GitHubWriteClient} 도 {@code ForkRef} 도 <b>public</b> 이라,
     * 다른 패키지(예: {@code issue/adapter/out/github})의 새 어댑터가
     * {@code writeClient.post(fork, "issues/12/comments", …)} 를 쓰면 <b>여전히 안 잡힌다.</b>
     *
     * <p>⚠️ {@link #쓰기_표면은_한_곳이다_S1} 도 그것을 잡지 못한다 — 그쪽 판정축은
     * {@code RestClient}/{@code HttpMethod} <b>직접</b> 사용이고,
     * {@code GitHubWriteClient} 는 {@code ExternalAdapters.NETWORK_CLIENTS} 에 없다.
     * 즉 「한 패키지 안」이라는 전제가 <b>어디에도 강제돼 있지 않았다.</b>
     *
     * <p>그래서 스캔을 <b>운영 소스 전체</b>로 넓혔다. 여집합이다 — 어느 패키지에
     * 무슨 이름으로 어댑터가 생기든 기본이 검사 대상이다.
     *
     * <p>🔵 <b>비용은 작다.</b> 텍스트 읽기 수백 건이고 정규식은 줄 단위가 아니라 파일
     * 단위 한 번이다. 실측으로 수백 ms 다.
     *
     * <p>⚠️ <b>{@code build.gradle.kts} 의 입력 선언은 넓히지 않는다</b>(요구 0).
     * 「가드가 돌기는 하는가」를 위해 쓰기 어댑터 디렉터리만 선언해 뒀는데, 그것으로
     * 충분한 이유는 <b>새 {@code writeClient.post(...)} 호출은 반드시 바이트코드를 바꾸기</b>
     * 때문이다 — 그러면 {@code compileJava} 가 돌고 {@code :test} 도 함께 돈다.
     * 놓치는 것은 「주석·공백만 바뀐 경우」뿐이고 그것으로는 새 호출이 생기지 않는다.
     * 게다가 CI 는 fresh checkout 이라 이 문제 자체가 없다.
     *
     * <p>⚠️ <b>{@code forks}·{@code pulls} 는 이 화이트리스트의 죽은 줄이다.</b>
     * 둘 다 {@code GitHubWriteClient} <b>내부 상수</b>라 호출부 리터럴로 나타나지 않는다.
     * 즉 <b>어설션 면제 경로는 이 가드에 원리적으로 잡히지 않는다</b> — 그쪽은 위
     * {@code 어설션_우회는…} 두 규칙이 센다. 이 가드가 덮는 것은
     * <b>{@code ForkRef} 를 통한 일반 쓰기</b>다. 목록에서 {@code forks} 를 지우지 않는 것은
     * 「여기에 나타나면 그때는 위반」이라는 표기이기 때문이다.
     */
    @Test
    @DisplayName("쓰기가 닿는 엔드포인트가 화이트리스트를 벗어나지 않는다")
    void 쓰기_엔드포인트가_화이트리스트_안이다_S2() throws Exception {
        List<java.nio.file.Path> sources;
        try (var files = java.nio.file.Files.walk(PRODUCTION_SOURCE_ROOT)) {
            sources = files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }

        // 🔴 모수 ① — 스캔 경로가 틀리면 파일 0개를 훑고 초록이 된다.
        //    ⚠ 「0 이 아니다」로는 약하다. 운영 소스 전체를 훑는다고 주장하므로 그 규모를
        //      단언한다 — 경로가 하위 디렉터리 하나로 좁아져도 드러나야 한다
        assertThat(sources)
                .as("운영 소스에서 java 파일을 충분히 찾지 못했다 — 스캔 경로가 좁아졌다")
                .hasSizeGreaterThan(100);
        assertThat(sources)
                .as("쓰기 클라이언트와 두 어댑터가 스캔에 실재해야 한다")
                .anyMatch(path -> path.endsWith("GitHubWriteClient.java"))
                .anyMatch(path -> path.endsWith("GitHubForkPublisher.java"))
                .anyMatch(path -> path.endsWith("GitHubDraftPrPublisher.java"));

        Set<String> used = new TreeSet<>();
        for (java.nio.file.Path source : sources) {
            var matcher = SUB_PATH_LITERAL.matcher(java.nio.file.Files.readString(source));
            while (matcher.find()) {
                used.add(matcher.group(1));
            }
        }

        // 🔴 모수 ② — 하나도 못 찾았으면 정규식이 구현과 어긋난 것이고, 그 상태에서
        //    「위반 없음」은 아무 의미가 없다
        assertThat(used)
                .as("subPath 리터럴을 하나도 찾지 못했다 — 이 가드가 구현을 보고 있지 않다")
                .isNotEmpty();
        assertThat(used)
                .as("대상 저장소에 글을 남기는 경로는 Draft PR 하나뿐이다 (S-2). "
                        + "이슈 코멘트·리뷰 코멘트·라벨은 존재 자체가 반려다")
                .allSatisfy(subPath -> assertThat(ALLOWED_SUB_PATHS).contains(subPath));
    }

    /**
     * 🔴 <b>물림</b> — 위 가드가 실제로 무는지 확인한다
     * ({@code testing-philosophy.md} 가드 요구 2).
     *
     * <p>위반이 0건인 상태에서는 정규식이 아무것도 못 잡아도 초록이다. 표본을 직접
     * 먹여서 <b>판정기가 고장 나지 않았다</b>는 것을 본다.
     *
     * <p>⚠️ 표본이 <b>대표성</b>을 가져야 한다(요구 3) — 실제 S-2 위반의 모양은
     * {@code writeClient.post(fork, "issues/12/comments", body, UNSAFE)} 이지
     * {@code mergePullRequest()} 같은 메서드 이름이 아니다. 초안의 가드가 정확히 그
     * 착각으로 영구 초록이었다.
     */
    @Test
    @DisplayName("그 가드가 이슈 코멘트 경로를 실제로 잡는다 — 물림")
    void 화이트리스트가_이슈_코멘트를_문다_S2() {
        String violation = """
                writeClient.post(fork, "issues/12/comments",
                        new CommentRequest(body), Idempotency.UNSAFE);""";

        Set<String> used = new TreeSet<>();
        var matcher = SUB_PATH_LITERAL.matcher(violation);
        while (matcher.find()) {
            used.add(matcher.group(1));
        }

        assertThat(used)
                .as("표본에서 subPath 를 못 뽑으면 이 물림 단언이 공허하다")
                .containsExactly("issues/12/comments");
        assertThat(ALLOWED_SUB_PATHS)
                .as("이슈 코멘트가 화이트리스트를 통과하면 S-2 가 뚫린다")
                .doesNotContain("issues/12/comments");
    }

    /**
     * 🔴 <b>「draft 아닌 PR」이 표현 불가능한가</b> — S-2 · #23.
     *
     * <p>{@code safety-boundaries.md} 가 「설정으로도 끌 수 없게 한다 — 플래그를 두면
     * 언젠가 켜진다」로 못 박은 조항의 <b>구조적 근거</b>다. {@code draft} 를 담을
     * <b>자리</b>(필드·생성자 파라미터·세터)가 하나도 없어야 한다.
     *
     * <p>⚠️ <b>이것은 전형적인 0건 부재 검사다.</b> 위반 표본이 원래 0건이라 판정기가 항상
     * {@code false} 를 돌려줘도 초록이 된다. 그래서 <b>모수</b>(접근자를 실제로 찾았다)와
     * <b>물림</b>(자리가 있는 표본을 문다)을 함께 단언한다
     * — {@code testing-philosophy.md} 가드 요구 1·2.
     *
     * <p>⚠️ 직렬화 결과에 {@code "draft":true} 가 실제로 실리는지는 <b>여기서 볼 수 없다</b>.
     * 그것은 {@code GitHubDraftPrPublisherTest} 가 {@code ObjectMapper} 로 본다.
     * 이 가드는 <b>「담을 자리가 없다」</b>만 본다 — 둘은 다른 성질이고 서로를 대신하지 않는다.
     */
    @Test
    @DisplayName("draft 를 false 로 만들 자리가 없다")
    void draft_는_담을_자리가_없다_S2() {
        JavaClass payload = PRODUCTION.get(
                "com.ossagent.pullrequest.adapter.out.github.GitDataPayloads$DraftPullRequestRequest");

        // 🔴 모수 — draft 접근자를 실제로 찾았는가. 이름이 바뀌면 아래 단언이 공허해진다
        assertThat(hasDraftHolder(payload))
                .as("draft 접근자를 찾지 못했다 — 이 가드가 payload 를 보고 있지 않다")
                .isFalse();
        assertThat(payload.getMethods().stream().map(JavaMethod::getName))
                .as("draft 값을 내보내는 접근자가 있어야 GitHub 에 실린다")
                .contains("isDraft");

        // 🔴 본체 — 담을 자리가 하나도 없다
        assertThat(payload.getFields().stream().map(f -> f.getName().toLowerCase(java.util.Locale.ROOT)))
                .as("draft 가 필드면 false 인 인스턴스가 표현 가능해진다")
                .noneMatch(name -> name.contains("draft"));
        assertThat(payload.getConstructors().stream()
                .flatMap(constructor -> constructor.getRawParameterTypes().stream())
                .map(JavaClass::getName))
                .as("draft 가 생성자 파라미터면 호출부가 false 를 넘길 수 있다")
                .doesNotContain("boolean");
        assertThat(payload.getMethods().stream().map(JavaMethod::getName))
                .as("세터가 있으면 만든 뒤에 뒤집을 수 있다")
                .noneMatch(name -> name.startsWith("set"));
    }

    @Test
    @DisplayName("그 가드가 draft 자리를 가진 표본을 문다 — 물림")
    void draft_자리_판정이_표본을_문다_S2() {
        // 🔴 대표성 — 실제 위반의 모양은 「draft 를 컴포넌트로 가진 record」다.
        //    미끼를 운영 패키지에 심지 않고 여기서 판정기만 먹인다
        JavaClass withDraftField = PROBES.get(
                "com.ossagent.support.testing.probe.pullrequest.DraftFlagProbe");

        assertThat(hasDraftHolder(withDraftField))
                .as("draft 를 담을 자리가 있는 표본을 물지 못하면 위 규칙은 아무것도 막지 않는다")
                .isTrue();
    }

    /** {@code draft} 를 <b>담을 수 있는</b> 자리가 있는가 — 필드 또는 boolean 생성자 파라미터. */
    private static boolean hasDraftHolder(JavaClass type) {
        boolean field = type.getFields().stream()
                .anyMatch(f -> f.getName().toLowerCase(java.util.Locale.ROOT).contains("draft"));
        boolean booleanParameter = type.getConstructors().stream()
                .flatMap(constructor -> constructor.getRawParameterTypes().stream())
                .anyMatch(parameter -> parameter.getName().equals("boolean"));
        return field || booleanParameter;
    }

    @Test
    @DisplayName("PR 생성·머지·ready 전환을 부르는 타입이 없다")
    void PR_호출이_없다_S2() {
        Set<String> offenders = PRODUCTION.stream()
                .filter(type -> type.getPackageName().startsWith(BASE_PACKAGE + ".pullrequest"))
                .filter(ForkPublishArchitectureTest::mentionsPullRequestApi)
                .map(JavaClass::getName)
                .collect(Collectors.toCollection(TreeSet::new));

        // ⚠️ 이것만으로는 부족하다 — 위 화이트리스트가 본체다. 여기는 라이브러리 기반
        //    PR 생성(hub4j 류)이 나중에 들어올 때를 위한 보조다
        assertThat(offenders).isEmpty();
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
