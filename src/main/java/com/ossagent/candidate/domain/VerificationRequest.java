package com.ossagent.candidate.domain;

import com.ossagent.repository.domain.ContributionConstraints;
import com.ossagent.repository.domain.RepositoryCoordinates;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 검증 한 바퀴에 필요한 입력 — #19.
 *
 * <h2>🔴 {@code workspacePath} 가 {@code Path} 인 이유 — 규율 ①</h2>
 *
 * <p>{@code SandboxWorkspace}(#17)를 여기 들이지 않는다. 그 타입은 생성 시점에
 * {@code sandbox.workspace-root} 하위임을 단언하는데(S-3), <b>그 루트는 설정값</b>이라
 * {@code candidate/domain} 이 알 이유가 없다. 변환은 어댑터가 한다 —
 * 그러면 S-3 단언이 <b>어댑터 한 곳</b>에 남고, 도메인은 「어느 디렉토리인가」만 말한다.
 *
 * <h2>{@code plannedPaths} 가 {@code Set} 인 이유</h2>
 *
 * <p>{@code DIFF} 단계의 판정이 <b>포함 여부</b>뿐이다({@link DiffInspection}).
 * {@code List} 로 두면 순서·중복에 의미가 있는 것처럼 읽히고, 그러면 다음 사람이
 * 「계획 순서대로 고쳤는가」 같은 것을 여기에 얹으려 한다.
 *
 * <p>⚠️ <b>비어 있을 수 있다.</b> 그때 {@code DIFF} 는 <b>모든 변경을 범위 밖으로</b>
 * 본다 — 「계획이 없으니 아무거나 고쳐도 된다」가 아니라 「계획이 없으면 고칠 근거가
 * 없다」다. 이 방향이 되돌릴 수 없는 쪽을 피한다({@code external-deps.md}).
 *
 * @param candidateId  후보 식별자. 로그 MDC 와 {@code AgentRun} 기록에 쓴다
 * @param coordinates  대상 저장소 좌표. 🔴 <b>의존성 캐시 볼륨이 저장소별</b>이라 필요하다 —
 *                     좌표 없이 만들면 전역 공유 볼륨이 되고, 그러면 한 저장소의 워밍이
 *                     남긴 것이 다른 저장소의 실행을 오염시킨다 (S-3 · Q-4)
 * @param attempt      Q-6 의 사이클 번호. {@code CODE→VERIFY→REVIEW} 한 바퀴가 1이다
 * @param workspacePath 워크스페이스 디렉토리 (호스트 경로)
 * @param constraints  대상 저장소 규약에서 뽑은 빌드·테스트 명령 (#16 · S-5)
 * @param plannedPaths 계획이 손대기로 한 저장소 상대 경로
 */
public record VerificationRequest(
        Long candidateId,
        RepositoryCoordinates coordinates,
        int attempt,
        Path workspacePath,
        ContributionConstraints constraints,
        Set<String> plannedPaths) {

    public VerificationRequest {
        if (candidateId == null) {
            throw new IllegalArgumentException("검증 대상 후보가 없다");
        }
        if (coordinates == null) {
            throw new IllegalArgumentException("대상 저장소 좌표는 필수다 candidateId=" + candidateId);
        }
        if (attempt < 1) {
            // 🔴 0 을 허용하면 AgentRun.attempt 가 Q-6 의 사이클 번호와 어긋난다
            throw new IllegalArgumentException("시도 번호는 1 이상이다 attempt=" + attempt);
        }
        if (workspacePath == null) {
            throw new IllegalArgumentException("워크스페이스 경로는 필수다 candidateId=" + candidateId);
        }
        // 🔴 「규약을 모른다」와 「제약이 없다」는 다르다 — unknown() 이 그 구분을 들고 있다
        constraints = constraints == null ? ContributionConstraints.unknown() : constraints;
        plannedPaths = plannedPaths == null ? Set.of() : Set.copyOf(plannedPaths);
    }

    public static VerificationRequest of(Long candidateId, RepositoryCoordinates coordinates,
            int attempt, Path workspacePath, ContributionConstraints constraints,
            List<PlannedFile> plannedFiles) {
        Set<String> paths = plannedFiles == null
                ? Set.of()
                : plannedFiles.stream()
                        .map(PlannedFile::path).collect(Collectors.toUnmodifiableSet());
        return new VerificationRequest(
                candidateId, coordinates, attempt, workspacePath, constraints, paths);
    }

    /**
     * 🔴 <b>명령 문자열을 찍지 않는다.</b> {@code buildCommand}·{@code testCommand} 는
     * 대상 저장소 문서에서 LLM 이 뽑은 자유 문자열이라 로그 인젝션 경로가 된다 —
     * {@code ContributionConstraints.toString()} 이 같은 이유로 값을 가린다.
     */
    @Override
    public String toString() {
        return "VerificationRequest[candidateId=%d, repo=%s, attempt=%d, plannedPaths=%d, constraints=%s]"
                .formatted(candidateId, coordinates, attempt, plannedPaths.size(), constraints);
    }
}
