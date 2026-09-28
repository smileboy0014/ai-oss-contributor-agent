package com.ossagent.candidate.adapter.out.sandbox;

import com.ossagent.agent.adapter.out.sandbox.SandboxProperties;
import com.ossagent.agent.domain.BuildTool;
import com.ossagent.agent.domain.CodeSandbox;
import com.ossagent.agent.domain.DependencyCache;
import com.ossagent.agent.domain.ExecuteCommand;
import com.ossagent.agent.domain.SandboxCacheVolume;
import com.ossagent.agent.domain.SandboxResult;
import com.ossagent.agent.domain.SandboxWorkspace;
import com.ossagent.candidate.domain.ChangeVerifier;
import com.ossagent.candidate.domain.CommandLine;
import com.ossagent.candidate.domain.DiffInspection;
import com.ossagent.candidate.domain.StageOutcome;
import com.ossagent.candidate.domain.StageResult;
import com.ossagent.candidate.domain.VerificationReport;
import com.ossagent.candidate.domain.VerificationRequest;
import com.ossagent.candidate.domain.VerificationSetupException;
import com.ossagent.candidate.domain.VerificationStage;
import com.ossagent.repository.domain.ContributionConstraints;
import com.ossagent.repository.domain.RepositoryCoordinates;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * {@link ChangeVerifier} 의 샌드박스 구현 — #19 · 🔴 S-3.
 *
 * <h2>하는 일 하나: 단계를 순서대로 돌리고 첫 실패에서 멈춘다</h2>
 *
 * <pre>
 * COMPILE  buildCommand  → 종료코드
 * TEST     testCommand   → 종료코드
 * DIFF     git diff      → 파싱  ← 🔴 절단이 판정에 영향을 주는 유일한 단계
 * </pre>
 *
 * <h2>🔴 실행은 {@code ExecuteCommand} 로만 한다</h2>
 *
 * <p>{@code SandboxCommand} 세 갈래 중 <b>대상 저장소 명령을 담을 수 있는 것은
 * {@code ExecuteCommand} 뿐</b>이고, 그것은 타입상 <b>네트워크가 없다</b>(S-3 · #17).
 * 「검증이니까 네트워크가 조금 필요하다」는 자리가 <b>표현 불가능</b>하다.
 *
 * <p>⚠️ <b>워밍·씨딩은 이 클래스의 일이 아니다.</b> 워크스페이스를 채우고 의존성 캐시를
 * 씨딩하는 것은 <b>#18</b>(착수)의 몫이다(Q-4 의 종결 조건이 그쪽에 인계돼 있다).
 * 씨딩되지 않은 상태로 여기 오면 {@code --offline} 이 의존성을 못 찾아
 * {@code COMPILE} 이 {@code FAILED} 가 된다 — <b>그것은 이 클래스의 버그가 아니라
 * 선후가 어긋난 것</b>이고, 실패 요약에 그대로 드러난다.
 *
 * <h2>{@link SandboxProperties} 를 가져다 쓰는 이유</h2>
 *
 * <p>{@code agent} 의 <b>어댑터 패키지</b>를 import 한다. 규율상 곱지 않지만,
 * 대안(여기 별도 {@code @ConfigurationProperties} 를 두는 것)이 <b>S-3 상한을 설정
 * 두 곳에 두는 것</b>이라 더 나쁘다 — 한쪽만 느슨해지면 아무도 모른다. 엔티티가 아니라
 * 설정값이므로 규율 ④(애그리거트를 넘는 참조)에는 닿지 않는다.
 */
public class SandboxChangeVerifier implements ChangeVerifier {

    private static final Logger log = LoggerFactory.getLogger(SandboxChangeVerifier.class);

    /**
     * 🔴 <b>우리 명령이다</b> — 대상 저장소 문자열이 섞이지 않는다.
     * {@code --numstat} 은 파일당 한 줄이라 출력이 작고, 그래서 절단에 거의 걸리지 않는다.
     * 가장 중요한 판정(계획 범위 밖)을 여기 둔 이유다 — {@link DiffInspection}.
     */
    private static final List<String> DIFF_NUMSTAT = List.of("git", "diff", "--cached", "--numstat");

    /** 🔴 우리 명령. {@code --unified=0} 으로 문맥 줄을 빼 출력을 줄인다. */
    private static final List<String> DIFF_PATCH = List.of("git", "diff", "--cached", "--unified=0");

    /**
     * 🔴 diff 전에 <b>전부 스테이징</b>한다 — 새 파일이 보이게 (#100).
     *
     * <p>{@code git diff}(인덱스 대 작업 트리)는 <b>untracked 파일을 빼고 센다.</b> 아무도 {@code git add}
     * 를 하지 않았으므로 CREATE 전용 계획은 diff 0건 → {@code UNDETERMINED} → 종단이었고, 혼합
     * 계획은 새 파일이 범위·잔재 검사를 <b>조용히 건너뛰었다.</b> {@code -A} 는 {@code .gitignore} 를
     * 지키므로 {@code build/}·{@code .gradle/} 은 들어오지 않는다 — 호스트 JGit diff 와 같은 규칙이다.
     */
    private static final List<String> DIFF_STAGE_ALL = List.of("git", "add", "-A");

    /**
     * 스테이징을 되돌린다 — 인덱스를 HEAD 로. <b>판정 결과와 무관하게 항상</b> 돈다.
     * 다음 바퀴의 호스트 diff 가 이 바퀴의 스테이징을 기준점으로 삼지 않게 한다.
     */
    private static final List<String> DIFF_UNSTAGE = List.of("git", "reset", "-q");

    private static final List<String> GRADLE_LAUNCHERS = List.of("./gradlew", "gradlew", "gradle");

    private final CodeSandbox sandbox;
    private final DependencyCache dependencyCache;
    private final SandboxProperties properties;

    /**
     * 🔴 {@link DependencyCache} 가 <b>선택이 아니다</b> — #18 배선.
     *
     * <p>실행 단계는 {@code network=none} 이고 캐시 볼륨을 읽기전용으로 문다. 아무도
     * 채우지 않은 볼륨으로 돌리면 <b>의존성 해석 실패가 「빌드 실패」로 보고</b>되어
     * 후보의 코드가 멀쩡한데 {@code FAILED} 가 된다. 없으면 <b>기동에서 막는다</b> —
     * 그 고장은 런타임에 「테스트가 실패했다」로만 보여 발견이 늦다.
     */
    public SandboxChangeVerifier(CodeSandbox sandbox, DependencyCache dependencyCache,
            SandboxProperties properties) {
        if (sandbox == null || dependencyCache == null || properties == null) {
            throw new IllegalArgumentException("샌드박스·의존성 캐시·설정은 필수다");
        }
        this.sandbox = sandbox;
        this.dependencyCache = dependencyCache;
        this.properties = properties;
    }

    @Override
    public VerificationReport verify(VerificationRequest request) {
        if (request == null) {
            throw new VerificationSetupException("검증 요청이 없다");
        }
        // 🔴 덮기 전에 챙기고 finally 에서 **복원**한다 — remove 로 끝내면 복원이 아니라
        //    삭제이고, 바깥(#18 의 착수 흐름)이 넣어 둔 값이 이 호출 이후 사라진다.
        //    식별자가 가장 필요한 줄이 그 뒤의 실패 로그다 — logging.md
        String previousCandidateId = MDC.get("candidateId");
        String previousAttempt = MDC.get("attempt");
        String previousStage = MDC.get("stage");
        MDC.put("candidateId", String.valueOf(request.candidateId()));
        MDC.put("attempt", String.valueOf(request.attempt()));
        try {
            return runStages(request);
        } finally {
            // ⚠️ 헬퍼로 묶지 않는다 — 그러면 키가 변수가 되고 MdcLogPatternTest 의 소스
            //    스캐너가 정적으로 알 수 없어 실패로 본다. 가드를 느슨하게 하느니
            //    호출부가 장황한 편이 낫다 — logging.md
            if (previousCandidateId == null) {
                MDC.remove("candidateId");
            } else {
                MDC.put("candidateId", previousCandidateId);
            }
            if (previousAttempt == null) {
                MDC.remove("attempt");
            } else {
                MDC.put("attempt", previousAttempt);
            }
            if (previousStage == null) {
                MDC.remove("stage");
            } else {
                MDC.put("stage", previousStage);
            }
        }
    }

    /**
     * 🔴 코딩 <b>전</b>에 불린다 — 원본 clone 으로 워밍·씨딩한다 (#99).
     *
     * <p>{@link #verify} 도 같은 {@code ensurePrepared} 를 부르지만 그것은 <b>안전망</b>이다
     * (저장소당 1회 메모). 첫 워밍이 verify 안에서 일어나면 생성 코드가 {@code testClasses} 에 섞여,
     * 컴파일 실패가 종료코드(재시도 대상)가 아니라 {@code SandboxPermanentException}(종단)으로 나왔다.
     */
    @Override
    public void prepare(Long candidateId, RepositoryCoordinates coordinates, Path workspacePath,
            ContributionConstraints constraints) {
        if (candidateId == null || coordinates == null || workspacePath == null) {
            throw new VerificationSetupException("준비 요청의 필수 값이 비었다");
        }
        Preflight preflight = preflight(candidateId, workspacePath,
                constraints == null ? ContributionConstraints.unknown() : constraints);
        dependencyCache.ensurePrepared(preflight.workspace(), coordinates,
                preflight.buildTool(), preflight.constraints().javaVersion());
        log.info("검증 준비 완료 candidateId={} buildTool={}", candidateId, preflight.buildTool());
    }

    /**
     * 검증·준비가 공유하는 <b>시작 전 판정</b> — 명령 유무 · 빌드 도구 · 워크스페이스 경로.
     * 어느 쪽이 먼저 불리든 같은 것을 같은 순서로 본다.
     */
    private Preflight preflight(Long candidateId, Path workspacePath,
            ContributionConstraints constraints) {
        // 🔴 빌드 명령이 없으면 시작하지 않는다. 「명령을 못 읽었다」를 「검증할 것이 없다」로
        //    번역하지 않는다 — S-5 의 축이고, 여기서 접으면 검증 없는 PR 이 나간다
        if (!constraints.hasBuildCommand()) {
            throw new VerificationSetupException(
                    "규약에서 빌드 명령을 읽지 못했다 — 검증을 시작할 수 없다 (S-5)"
                            + " candidateId=" + candidateId);
        }

        List<String> buildArgv = CommandLine.parse(constraints.buildCommand());
        BuildTool buildTool = resolveBuildTool(buildArgv);
        // 🔴 테스트 명령의 실행기도 **시작 전에** 본다. 안 보면 「빌드는 Gradle, 테스트는
        //    Maven」이 컴파일을 통과한 뒤 TEST 단계에서 Gradle 배선(`GRADLE_RO_DEP_CACHE`·
        //    `--offline` 미부여)으로 돌아 **network=none 때문에** 실패한다 — fail-closed 이긴
        //    하나 「코드가 틀렸다」로 보고되어 재시도 예산을 태운다. 여기서 막으면
        //    「사람이 고칠 일」로 정확히 분류된다.
        if (constraints.hasTestCommand()) {
            resolveBuildTool(CommandLine.parse(constraints.testCommand())).requireSupported();
        }
        SandboxWorkspace workspace =
                SandboxWorkspace.under(workspacePath, properties.workspaceRoot());
        return new Preflight(constraints, buildArgv, buildTool, workspace);
    }

    private record Preflight(ContributionConstraints constraints, List<String> buildArgv,
            BuildTool buildTool, SandboxWorkspace workspace) {
    }

    private VerificationReport runStages(VerificationRequest request) {
        Preflight preflight = preflight(request.candidateId(), request.workspacePath(),
                request.constraints());
        ContributionConstraints constraints = preflight.constraints();
        List<String> buildArgv = preflight.buildArgv();
        BuildTool buildTool = preflight.buildTool();
        SandboxWorkspace workspace = preflight.workspace();
        // 🔴 실행 **전에** 캐시를 준비한다 (Q-4 의 워밍 → 씨딩). 볼륨 이름을 여기서
        //    따로 만들지 않고 **준비한 쪽이 돌려준 것**을 쓴다 — 따로 만들면
        //    「A 를 준비하고 B 로 실행」이 가능해지고, 그것은 준비를 하고도 빈 캐시로
        //    오프라인 실행하는 것과 같다.
        //    ⚠ 첫 워밍은 여기가 아니라 prepare() 다 (#99). 여기는 메모된 볼륨을 돌려받는 자리다
        SandboxCacheVolume cacheVolume = dependencyCache.ensurePrepared(
                workspace, request.coordinates(), buildTool, constraints.javaVersion());

        Context context = new Context(request, workspace, cacheVolume, buildTool);
        List<StageResult> stages = new ArrayList<>();

        StageResult compile = runCommandStage(context, VerificationStage.COMPILE, buildArgv);
        stages.add(compile);

        if (!compile.outcome().allowsNextStage()) {
            stages.add(StageResult.skipped(VerificationStage.TEST));
            stages.add(StageResult.skipped(VerificationStage.DIFF));
            return report(request, stages);
        }

        StageResult test = runTestStage(context, constraints);
        stages.add(test);

        if (!test.outcome().allowsNextStage()) {
            stages.add(StageResult.skipped(VerificationStage.DIFF));
            return report(request, stages);
        }

        stages.add(runDiffStage(context));
        return report(request, stages);
    }

    // ── 단계 ────────────────────────────────────────────────────────────────

    private StageResult runTestStage(Context context, ContributionConstraints constraints) {
        if (!constraints.hasTestCommand()) {
            // 🔴 「테스트 명령이 없다」를 「테스트가 통과했다」로 적지 않는다.
            //    SKIPPED 도 아니다 — SKIPPED 는 「앞이 멈춰서 안 돌렸다」이고 여기는
            //    「돌릴 근거가 없다」다. UNDETERMINED 로 두면 재시도가 아니라 사람에게 간다
            //    (VerificationReport.hasUndetermined 의 계약)
            return new StageResult(VerificationStage.TEST, StageOutcome.UNDETERMINED,
                    null, Duration.ZERO, false,
                    "규약에서 테스트 명령을 읽지 못했다 — 테스트 통과를 주장할 근거가 없다 (S-5)");
        }
        return runCommandStage(context, VerificationStage.TEST,
                CommandLine.parse(constraints.testCommand()));
    }

    /** 🔴 종료코드로만 판정한다. 절단은 <b>판정에 영향을 주지 않는다</b> — 요약 품질만 떨어진다. */
    private StageResult runCommandStage(Context context, VerificationStage stage,
            List<String> argv) {
        MDC.put("stage", stage.name());
        SandboxResult result = execute(context, argv);
        StageOutcome outcome = result.succeeded() ? StageOutcome.PASSED : StageOutcome.FAILED;
        log.info("검증 단계 종료 stage={} outcome={} exitCode={} timedOut={} truncated={} cleanedUp={}",
                stage, outcome, result.exitCode(), result.timedOut(), result.truncated(),
                result.cleanedUp());
        if (!result.cleanedUp()) {
            // 정리 실패를 조용히 넘기지 않는다 — 쌓이면 시간이 지나야 드러나는 고장이다 (S-3)
            log.warn("샌드박스 정리 실패 stage={}", stage);
        }
        return new StageResult(stage, outcome, result.exitCode(), result.duration(),
                result.truncated(), result.output());
    }

    /**
     * 🔴 <b>이 단계만 출력을 파싱한다</b> — 그래서 {@link StageOutcome#UNDETERMINED} 가
     * 나올 수 있다.
     *
     * <p>순서가 설계다. 값싸고 <b>거의 잘리지 않는</b> 범위 검사를 먼저 통과시킨 뒤에만
     * 본문을 받으므로, 통과한 시점에는 변경이 계획 파일 안에 있고 파일당
     * {@value DiffInspection#MAX_CHANGED_LINES_PER_FILE} 줄 이하다 —
     * <b>본문 절단이 일어날 여지가 그만큼 줄어든다.</b>
     */
    private StageResult runDiffStage(Context context) {
        MDC.put("stage", VerificationStage.DIFF.name());

        SandboxResult staged = execute(context, DIFF_STAGE_ALL);
        if (!staged.succeeded()) {
            // 🔴 스테이징을 못 했으면 새 파일이 안 보인다 — 「위반 없음」을 말할 수 없다
            return undetermined(staged, "변경분을 스테이징하지 못했다 exitCode=" + staged.exitCode());
        }
        try {
            return inspectStagedDiff(context);
        } finally {
            // 🔴 판정과 무관하게 인덱스를 되돌린다. 남겨 두면 다음 바퀴의 호스트 diff 가
            //    이 바퀴의 스테이징을 기준점으로 삼아 「이번 바퀴에 바뀐 것」만 보게 된다
            SandboxResult unstaged = execute(context, DIFF_UNSTAGE);
            if (!unstaged.succeeded()) {
                log.warn("스테이징을 되돌리지 못했다 exitCode={} — 호스트 diff 가 HEAD 기준으로 다시 잡는다",
                        unstaged.exitCode());
            }
        }
    }

    private StageResult inspectStagedDiff(Context context) {
        SandboxResult numstat = execute(context, DIFF_NUMSTAT);
        if (!numstat.succeeded()) {
            // 🔴 diff 를 못 얻은 것은 「위반 없음」이 아니다. 코드 탓도 아니므로 FAILED 도 아니다
            return undetermined(numstat, "diff 목록을 얻지 못했다 exitCode=" + numstat.exitCode());
        }
        if (!numstat.outputIsComplete()) {
            return undetermined(numstat, "diff 목록이 잘렸다 — 범위 밖 변경이 있는지 말할 수 없다");
        }
        if (numstat.output() == null || numstat.output().isBlank()) {
            // 🔴 **모수 0 을 통과로 접지 않는다.** 검증은 언제나 코드를 고친 뒤에 돈다 —
            //    변경이 0건이라는 것은 「깨끗하다」가 아니라 **「우리가 엉뚱한 것을 보고 있다」**다.
            //    `git add -A` 뒤라 새 파일도 세어졌다 (#100). 그래도 0건이면 코딩 단계가 아무것도
            //    바꾸지 않았거나 워크스페이스가 엉뚱한 것이다. 그대로 두면 계획 범위 검사가
            //    **아무것도 검사하지 않고 초록**이 된다 — 「0건을 검사하고 초록」의 런타임판
            return undetermined(numstat,
                    "diff 가 0건이다 — 스테이징 뒤에도 검사할 변경이 없다. 코딩 단계가 파일을 바꾸지 않았거나 워크스페이스가 다르다");
        }

        List<DiffInspection.Finding> scope =
                DiffInspection.inspectScope(numstat.output(), context.request().plannedPaths());
        if (!scope.isEmpty()) {
            return failed(numstat, DiffInspection.describe(scope));
        }

        SandboxResult patch = execute(context, DIFF_PATCH);
        if (!patch.succeeded()) {
            return undetermined(patch, "diff 본문을 얻지 못했다 exitCode=" + patch.exitCode());
        }
        if (!patch.outputIsComplete()) {
            return undetermined(patch, "diff 본문이 잘렸다 — 디버그 잔재가 있는지 말할 수 없다");
        }

        List<DiffInspection.Finding> residue = DiffInspection.inspectPatch(patch.output());
        if (!residue.isEmpty()) {
            return failed(patch, DiffInspection.describe(residue));
        }
        log.info("검증 단계 종료 stage=DIFF outcome=PASSED");
        return new StageResult(VerificationStage.DIFF, StageOutcome.PASSED, patch.exitCode(),
                patch.duration(), false, "");
    }

    // ── 조립 ────────────────────────────────────────────────────────────────

    private SandboxResult execute(Context context, List<String> argv) {
        return sandbox.run(ExecuteCommand.of(
                context.workspace(),
                context.cacheVolume(),
                context.buildTool(),
                context.request().constraints().javaVersion(),
                properties.defaultImage(),
                argv,
                properties.executeLimits()));
    }

    /**
     * 빌드 도구를 <b>명령의 실행기</b>로 가른다.
     *
     * <p>🔴 Gradle 이 아니면 {@code MAVEN} 으로 접고, {@code ExecuteCommand} 가
     * {@code requireSupported()} 로 <b>거부</b>한다(Q-4). 「모르면 Gradle 로 해 본다」는
     * 하지 않는다 — Maven 은 로컬 저장소를 읽기전용으로 쓸 수 없어, 통과시키면
     * <b>조용히 네트워크를 여는 쪽</b>으로 흐른다.
     */
    private static BuildTool resolveBuildTool(List<String> argv) {
        String launcher = argv.get(0).trim().toLowerCase(Locale.ROOT);
        return GRADLE_LAUNCHERS.contains(launcher) ? BuildTool.GRADLE : BuildTool.MAVEN;
    }

    private static StageResult undetermined(SandboxResult result, String reason) {
        log.warn("검증 단계 판정 불가 stage=DIFF");
        return new StageResult(VerificationStage.DIFF, StageOutcome.UNDETERMINED,
                result.exitCode(), result.duration(), result.truncated(), reason);
    }

    private static StageResult failed(SandboxResult result, String summary) {
        log.info("검증 단계 종료 stage=DIFF outcome=FAILED");
        return new StageResult(VerificationStage.DIFF, StageOutcome.FAILED,
                result.exitCode(), result.duration(), result.truncated(), summary);
    }

    private VerificationReport report(VerificationRequest request, List<StageResult> stages) {
        VerificationReport report = new VerificationReport(stages);
        log.info("검증 종료 candidateId={} passed={} undetermined={}",
                request.candidateId(), report.passed(), report.hasUndetermined());
        return report;
    }

    private record Context(
            VerificationRequest request,
            SandboxWorkspace workspace,
            SandboxCacheVolume cacheVolume,
            BuildTool buildTool) {
    }
}
