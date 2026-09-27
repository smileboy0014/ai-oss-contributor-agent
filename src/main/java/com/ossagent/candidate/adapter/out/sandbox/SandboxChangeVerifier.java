package com.ossagent.candidate.adapter.out.sandbox;

import com.ossagent.agent.adapter.out.sandbox.SandboxProperties;
import com.ossagent.agent.domain.BuildTool;
import com.ossagent.agent.domain.CodeSandbox;
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
    private static final List<String> DIFF_NUMSTAT = List.of("git", "diff", "--numstat");

    /** 🔴 우리 명령. {@code --unified=0} 으로 문맥 줄을 빼 출력을 줄인다. */
    private static final List<String> DIFF_PATCH = List.of("git", "diff", "--unified=0");

    private static final List<String> GRADLE_LAUNCHERS = List.of("./gradlew", "gradlew", "gradle");

    private final CodeSandbox sandbox;
    private final SandboxProperties properties;

    public SandboxChangeVerifier(CodeSandbox sandbox, SandboxProperties properties) {
        if (sandbox == null || properties == null) {
            throw new IllegalArgumentException("샌드박스와 설정은 필수다");
        }
        this.sandbox = sandbox;
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

    private VerificationReport runStages(VerificationRequest request) {
        ContributionConstraints constraints = request.constraints();
        // 🔴 빌드 명령이 없으면 시작하지 않는다. 「명령을 못 읽었다」를 「검증할 것이 없다」로
        //    번역하지 않는다 — S-5 의 축이고, 여기서 접으면 검증 없는 PR 이 나간다
        if (!constraints.hasBuildCommand()) {
            throw new VerificationSetupException(
                    "규약에서 빌드 명령을 읽지 못했다 — 검증을 시작할 수 없다 (S-5)"
                            + " candidateId=" + request.candidateId());
        }

        List<String> buildArgv = CommandLine.parse(constraints.buildCommand());
        BuildTool buildTool = resolveBuildTool(buildArgv);
        SandboxWorkspace workspace =
                SandboxWorkspace.under(request.workspacePath(), properties.workspaceRoot());
        SandboxCacheVolume cacheVolume = SandboxCacheVolume.forRepository(
                request.coordinates().owner(), request.coordinates().name());

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
                    null, java.time.Duration.ZERO, false,
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

        SandboxResult numstat = execute(context, DIFF_NUMSTAT);
        if (!numstat.succeeded()) {
            // 🔴 diff 를 못 얻은 것은 「위반 없음」이 아니다. 코드 탓도 아니므로 FAILED 도 아니다
            return undetermined(numstat, "diff 목록을 얻지 못했다 exitCode=" + numstat.exitCode());
        }
        if (!numstat.outputIsComplete()) {
            return undetermined(numstat, "diff 목록이 잘렸다 — 범위 밖 변경이 있는지 말할 수 없다");
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
