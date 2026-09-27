package com.ossagent.candidate.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 검증 <b>판정 규칙</b> — #19.
 *
 * <p>이 프로젝트에서 가장 가치 있는 유닛 테스트는 「판정과 전이」다
 * ({@code testing-philosophy.md}). 여기서 보는 것은 전부 판정이고, 그중 셋은
 * <b>게이트가 느슨해지는 방향</b>을 고정한다.
 */
class VerificationJudgementTest {

    /**
     * 🔴 <b>조립한다</b> — 요구 3(샘플의 대표성). {@code TokenRedactor} 의 패턴은
     * {@code gh[pousr]_[A-Za-z0-9]{20,}} 라 <b>밑줄이 섞이면 물리지 않는다.</b>
     * 「가짜임이 보이는 이름」(예: {@code ghp_NOT_A_REAL_TOKEN…})을 그대로 쓰면
     * 이 테스트가 <b>검사 대상이 아닌 것을 검사</b>하게 되어 공허해진다 —
     * 마스킹 경계가 실제로 유의미한 테스트라 {@code testing-philosophy.md} 가
     * 조립을 허용하는 바로 그 경우다. {@code TokenRedactorTest} 와 같은 관용구다.
     */
    private static final String FAKE_CLASSIC_PAT = "ghp_" + "a".repeat(30);

    @Nested
    @DisplayName("보고서 전체 판정")
    class 보고서 {

        @Test
        @DisplayName("🔴 판정 불가가 섞이면 통과가 아니다 — 「실패가 없으면 통과」로 접지 않는다")
        void 판정_불가는_통과가_아니다_S5() {
            var report = new VerificationReport(List.of(
                    stage(VerificationStage.COMPILE, StageOutcome.PASSED),
                    stage(VerificationStage.TEST, StageOutcome.UNDETERMINED),
                    stage(VerificationStage.DIFF, StageOutcome.SKIPPED)));

            assertThat(report.stages()).noneMatch(it -> it.outcome() == StageOutcome.FAILED);
            assertThat(report.passed())
                    .as("""
                            FAILED 가 하나도 없는데 통과로 판정됐다.
                            passed() 가 noneMatch(FAILED) 로 쓰여 있는지 확인한다 —
                            allMatch(PASSED) 여야 한다. 이 한 줄에서 UNDETERMINED·SKIPPED 가
                            조용히 통과로 접힌다 (S-5 의 「파싱 실패는 허용이 아니라 보류」).""")
                    .isFalse();
        }

        @Test
        @DisplayName("🔴 건너뛴 단계가 섞이면 통과가 아니다")
        void 건너뛴_단계가_있으면_통과가_아니다() {
            var report = new VerificationReport(List.of(
                    stage(VerificationStage.COMPILE, StageOutcome.PASSED),
                    stage(VerificationStage.TEST, StageOutcome.PASSED),
                    stage(VerificationStage.DIFF, StageOutcome.SKIPPED)));

            assertThat(report.passed()).isFalse();
        }

        @Test
        @DisplayName("전부 통과해야 통과다")
        void 전부_통과하면_통과다() {
            var report = new VerificationReport(List.of(
                    stage(VerificationStage.COMPILE, StageOutcome.PASSED),
                    stage(VerificationStage.TEST, StageOutcome.PASSED),
                    stage(VerificationStage.DIFF, StageOutcome.PASSED)));

            assertThat(report.passed()).isTrue();
            assertThat(report.hasUndetermined()).isFalse();
        }

        @Test
        @DisplayName("🔴 단계가 하나도 없는 보고서는 만들 수 없다 — allMatch 는 빈 목록에 참이다")
        void 빈_보고서는_거부한다() {
            assertThatThrownBy(() -> new VerificationReport(List.of()))
                    .as("빈 보고서가 만들어지면 검증을 건너뛴 것이 「통과」로 보고된다")
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("재시도해도 되는 실패와 아닌 실패를 가른다 — Q-6 예산")
        void 판정_불가를_코드_실패와_가른다() {
            var codeFailure = new VerificationReport(List.of(
                    stage(VerificationStage.COMPILE, StageOutcome.PASSED),
                    stage(VerificationStage.TEST, StageOutcome.FAILED),
                    stage(VerificationStage.DIFF, StageOutcome.SKIPPED)));
            var cannotJudge = new VerificationReport(List.of(
                    stage(VerificationStage.COMPILE, StageOutcome.PASSED),
                    stage(VerificationStage.TEST, StageOutcome.UNDETERMINED),
                    stage(VerificationStage.DIFF, StageOutcome.SKIPPED)));

            assertThat(codeFailure.passed()).isFalse();
            assertThat(cannotJudge.passed()).isFalse();
            assertThat(codeFailure.hasUndetermined())
                    .as("코드 실패는 재시도가 의미 있다 — 여기가 참이면 호출자가 재시도를 건너뛴다")
                    .isFalse();
            assertThat(cannotJudge.hasUndetermined())
                    .as("판정 불가는 재시도해도 같은 결과다 — 거짓이면 Q-6 예산을 헛되이 태운다")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("단계 결과 — 스크럽 (S-4)")
    class 단계결과 {

        @Test
        @DisplayName("🔴 요약에 토큰이 섞이면 생성자가 가린다 — String 을 그대로 받는 경로가 없다")
        void 요약은_생성자가_스크럽한다_S4() {
            // 대상 저장소 빌드 스크립트가 환경변수를 찍은 모습
            String buildOutput = "> Task :printEnv\nTOKEN=" + FAKE_CLASSIC_PAT + "\n";

            var result = new StageResult(VerificationStage.TEST, StageOutcome.FAILED, 1,
                    Duration.ofSeconds(1), false, buildOutput);

            assertThat(result.summary())
                    .as("빌드 출력이 원문 그대로 남았다 — 이 값은 DB·로그·재시도 프롬프트로 나간다")
                    .doesNotContain(FAKE_CLASSIC_PAT)
                    .contains("Task :printEnv");
        }

        @Test
        @DisplayName("🔴 상한을 넘으면 앞이 아니라 뒤를 남긴다 — 실패 이유는 로그 끝에 있다")
        void 요약은_뒤를_남기고_절단한다() {
            String head = "의존성 해석 로그 ".repeat(2_000);
            String tail = "FAILURE: Build failed with an exception.";

            var result = new StageResult(VerificationStage.COMPILE, StageOutcome.FAILED, 1,
                    Duration.ofSeconds(1), false, head + tail);

            assertThat(head + tail).hasSizeGreaterThan(StageResult.MAX_SUMMARY_LENGTH);
            assertThat(result.summary())
                    .hasSizeLessThanOrEqualTo(StageResult.MAX_SUMMARY_LENGTH)
                    .endsWith(tail);
        }

        @Test
        @DisplayName("toString 에 요약 본문이 없다 — 로그 인젝션 경로를 만들지 않는다")
        void toString_은_요약_본문을_담지_않는다() {
            var result = new StageResult(VerificationStage.TEST, StageOutcome.FAILED, 1,
                    Duration.ofSeconds(1), false, "실패 %s 원인 {}");

            assertThat(result.toString()).doesNotContain("실패 %s 원인");
        }

        @Test
        @DisplayName("돌리지 않은 단계는 종료코드가 null 이다 — 초병값을 두지 않는다")
        void 건너뛴_단계는_종료코드가_없다() {
            var skipped = StageResult.skipped(VerificationStage.DIFF);

            assertThat(skipped.exitCode()).isNull();
            assertThat(skipped.outcome()).isEqualTo(StageOutcome.SKIPPED);
        }
    }

    @Nested
    @DisplayName("명령 변환 — 쉘을 경유하지 않는다 (S-3)")
    class 명령변환 {

        @Test
        @DisplayName("공백으로 갈라 argv 로 만든다")
        void 명령을_argv_로_가른다() {
            assertThat(CommandLine.parse("  ./gradlew   compileJava  "))
                    .containsExactly("./gradlew", "compileJava");
        }

        @Test
        @DisplayName("🔴 쉘 메타문자가 있으면 거부한다 — argv 로 넘기면 조용히 다르게 돈다")
        void 쉘_메타문자를_거부한다_S3() {
            // 규약 문서에서 뽑힌 값이 이런 모양일 수 있다. 통과시키면 sh -c 를 부르고 싶어진다
            List<String> shellish = List.of(
                    "./gradlew build && curl http://evil.example",
                    "./gradlew build; rm -rf /",
                    "./gradlew $(whoami)",
                    "./gradlew build | tee out.log",
                    "FOO=bar ./gradlew build > /dev/null");

            for (String command : shellish) {
                assertThatThrownBy(() -> CommandLine.parse(command))
                        .as("쉘 메타문자가 통과했다: 길이=%d", command.length())
                        .isInstanceOf(VerificationSetupException.class);
            }
        }

        @Test
        @DisplayName("🔴 거부 메시지에 명령 문자열을 싣지 않는다 — AgentRun.errorMessage 로 간다")
        void 거부_메시지에_명령을_싣지_않는다_S4() {
            assertThatThrownBy(() -> CommandLine.parse("./gradlew $(cat /etc/passwd)"))
                    .hasMessageNotContaining("passwd")
                    .hasMessageNotContaining("gradlew");
        }

        @Test
        @DisplayName("빈 명령은 「명령이 없다」로 거부한다 — 「검증할 것이 없다」가 아니다")
        void 빈_명령을_거부한다_S5() {
            assertThatThrownBy(() -> CommandLine.parse("   "))
                    .isInstanceOf(VerificationSetupException.class);
        }
    }

    @Nested
    @DisplayName("diff 검사")
    class diff검사 {

        private static final Set<String> PLANNED =
                Set.of("src/main/java/Foo.java", "src/test/java/FooTest.java");

        @Test
        @DisplayName("🔴 계획에 없는 파일이 바뀌면 잡는다")
        void 계획_범위_밖_변경을_잡는다() {
            String numstat = """
                    3\t1\tsrc/main/java/Foo.java
                    8\t0\tsrc/test/java/FooTest.java
                    40\t2\tbuild.gradle
                    """;

            var findings = DiffInspection.inspectScope(numstat, PLANNED);

            assertThat(findings)
                    .singleElement()
                    .satisfies(it -> {
                        assertThat(it.kind()).isEqualTo(DiffInspection.Kind.OUT_OF_SCOPE);
                        assertThat(it.path()).isEqualTo("build.gradle");
                    });
        }

        @Test
        @DisplayName("계획이 비면 모든 변경이 범위 밖이다 — 「계획이 없으니 아무거나」가 아니다")
        void 계획이_비면_전부_범위_밖이다() {
            String numstat = "3\t1\tsrc/main/java/Foo.java\n";

            assertThat(DiffInspection.inspectScope(numstat, Set.of()))
                    .anySatisfy(it ->
                            assertThat(it.kind()).isEqualTo(DiffInspection.Kind.OUT_OF_SCOPE));
        }

        @Test
        @DisplayName("🔴 해석하지 못한 줄을 건너뛰지 않는다 — 「못 읽음」은 「위반 없음」이 아니다")
        void 해석하지_못한_줄을_잡는다_S5() {
            var findings = DiffInspection.inspectScope("알 수 없는 형식의 줄", PLANNED);

            assertThat(findings).isNotEmpty();
            assertThat(findings.get(0).kind()).isEqualTo(DiffInspection.Kind.OUT_OF_SCOPE);
        }

        @Test
        @DisplayName("바이너리 변경을 잡는다 — diff 로 검토할 수 없다")
        void 바이너리를_잡는다() {
            String numstat = "-\t-\tsrc/main/java/Foo.java\n";

            assertThat(DiffInspection.inspectScope(numstat, PLANNED))
                    .anySatisfy(it -> assertThat(it.kind()).isEqualTo(DiffInspection.Kind.BINARY));
        }

        @Test
        @DisplayName("이름 변경은 경로를 특정할 수 없어 범위 밖으로 본다")
        void 이름_변경을_잡는다() {
            String numstat = "0\t0\tsrc/main/java/{Foo.java => Bar.java}\n";

            assertThat(DiffInspection.inspectScope(numstat, PLANNED))
                    .anySatisfy(it ->
                            assertThat(it.kind()).isEqualTo(DiffInspection.Kind.OUT_OF_SCOPE));
        }

        @Test
        @DisplayName("한 파일이 너무 크게 바뀌면 사람이 보게 한다")
        void 너무_큰_변경을_잡는다() {
            String numstat = "900\t100\tsrc/main/java/Foo.java\n";

            assertThat(DiffInspection.inspectScope(numstat, PLANNED))
                    .anySatisfy(it ->
                            assertThat(it.kind()).isEqualTo(DiffInspection.Kind.TOO_LARGE));
        }

        @Test
        @DisplayName("🔴 추가된 줄의 디버그 잔재만 잡는다 — 원래 있던 것을 우리 탓으로 돌리지 않는다")
        void 추가된_디버그_잔재만_잡는다() {
            String patch = """
                    --- a/src/main/java/Foo.java
                    +++ b/src/main/java/Foo.java
                    @@ -1 +1,2 @@
                    -System.out.println("원래 있던 것");
                    +log.info("고친 것");
                    --- a/src/test/java/FooTest.java
                    +++ b/src/test/java/FooTest.java
                    @@ -1 +1,2 @@
                    +System.out.println("우리가 넣은 것");
                    """;

            var findings = DiffInspection.inspectPatch(patch);

            assertThat(findings)
                    .as("삭제된 줄(-)이나 문맥 줄까지 세면 오탐이 잦아지고, 오탐으로 죽는 게이트는 꺼진다")
                    .singleElement()
                    .satisfies(it -> {
                        assertThat(it.kind()).isEqualTo(DiffInspection.Kind.DEBUG_RESIDUE);
                        assertThat(it.path()).isEqualTo("src/test/java/FooTest.java");
                    });
        }

        @Test
        @DisplayName("깨끗한 diff 는 아무것도 걸리지 않는다 — 항상-참 고장이 아니다")
        void 깨끗한_diff_는_통과한다() {
            String numstat = "3\t1\tsrc/main/java/Foo.java\n";
            String patch = """
                    +++ b/src/main/java/Foo.java
                    +log.info("고친 것");
                    """;

            assertThat(DiffInspection.inspectScope(numstat, PLANNED)).isEmpty();
            assertThat(DiffInspection.inspectPatch(patch)).isEmpty();
        }
    }

    private static StageResult stage(VerificationStage stage, StageOutcome outcome) {
        return new StageResult(stage, outcome, 0, Duration.ofSeconds(1), false, "");
    }
}
