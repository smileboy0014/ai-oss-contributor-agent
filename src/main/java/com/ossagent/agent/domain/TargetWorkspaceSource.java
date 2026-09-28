package com.ossagent.agent.domain;

import com.ossagent.repository.domain.RepositoryCoordinates;

/**
 * 대상 저장소를 <b>호스트 워크스페이스로 가져오는</b> 능력 — #18 · S-3.
 *
 * <h2>🔴 이 능력이 없으면 샌드박스가 성립하지 않는다</h2>
 *
 * <p>{@link CodeSandbox} 의 명령 셋({@link WarmCommand} · {@link SeedCacheCommand} ·
 * {@link ExecuteCommand})은 <b>전부 이미 채워진 워크스페이스를 전제</b>한다.
 * {@link SandboxWorkspace#under} 도 {@code Files.isDirectory} 를 요구한다 —
 * <b>누군가 먼저 디렉토리를 만들고 채워야 한다.</b>
 *
 * <p>Q-4 가 「워크스페이스를 채우는 주체(#18)가 있어야 가능하다」로 이 이슈에 인계한 빈칸이다.
 *
 * <h2>🔴 여기에 push 가 없다 — S-1</h2>
 *
 * <p><b>이 단계는 원격에 쓰지 않는다.</b> Fork 생성·브랜치 push 는 <b>#22</b> 의 몫이고,
 * 거기에 owner 어설션이 붙는다. 이 인터페이스에 push 자리를 만들면 그 어설션 <b>앞에</b>
 * 쓰기 경로가 하나 더 생긴다.
 *
 * <p>⚠️ <b>표면이 좁은 것을 방어로 세지 않는다.</b> JGit 이 클래스패스에 있으므로
 * {@code git.push()} 는 아무 데서나 API 한 줄 거리다 — S-1 이
 * 「쓰기 메서드를 만들지 않았으니 안전하다」를 방어로 치지 않는 이유와 같다.
 * <b>부재는 {@code JGitPushAbsenceTest} 가 ArchUnit 으로 고정한다.</b>
 *
 * <h2>구현</h2>
 *
 * <p>{@code agent/adapter/out/git/JGitWorkspaceSource} — 규율 ③(능력은 능력 이름, 구현은 기술 이름).
 */
public interface TargetWorkspaceSource {

    /**
     * 대상 저장소를 워크스페이스로 가져오고 작업 브랜치를 만든다.
     *
     * <p>🔴 <b>익명으로 clone 한다.</b> 자격증명을 URL 에 실으면 토큰이
     * {@code .git/config} 에 앉고, 그 워크스페이스를 샌드박스가 <b>RW 로 바인드</b>해
     * <b>신뢰할 수 없는 빌드 스크립트가 읽는다.</b> 대상은 공개 저장소이므로 토큰이 필요 없다 (S-4).
     *
     * @param coordinates 대상 저장소 {@code owner/name}
     * @param branchName  PRD §14 의 {@code oss-agent/issue-{번호}-{설명}}
     * @throws WorkspaceException 가져오지 못했다 — 네트워크·권한·경로
     */
    SandboxWorkspace fetch(RepositoryCoordinates coordinates, String branchName);

    /**
     * 워크스페이스의 <b>변경분</b>을 통합 diff 로 만든다.
     *
     * <p>🔴 이 결과가 「계획 밖 파일을 건드렸는가」 판정의 <b>입력</b>이다 —
     * LLM 출력 검증은 조기 차단이고, <b>샌드박스에서 돈 포맷터가 건드린 것</b>은
     * 여기서만 보인다.
     *
     * @throws WorkspaceException diff 를 만들지 못했다
     */
    WorkspaceDiff diff(SandboxWorkspace workspace);

    /**
     * 저장된 통합 diff 를 <b>새로 가져온</b> 워크스페이스에 다시 입힌다 — PR 게이트(#23)의 입력.
     *
     * <p>착수(#18)와 PR 생성(#23)은 <b>다른 HTTP 요청</b>이고 그 사이에 재기동·같은 저장소의
     * 다른 후보 착수가 끼어들 수 있다. 착수 때의 워크스페이스 디렉토리를 다시 읽는 것은
     * 그래서 근거가 아니다. 정본은 DB 의 {@code GeneratedChange.diff} 이고, PR 시점에는
     * {@link #fetch} 로 upstream 을 다시 받아 그 diff 를 입힌다.
     *
     * <p>🔴 <b>적용 실패는 예외다.</b> upstream 이 그 사이 움직여 변경분이 더는 맞지 않는다는
     * 뜻이고, 그것을 「부분 적용」으로 넘기면 반쪽 변경이 Fork 에 올라간다. 사람이 다시 착수한다.
     *
     * <p>🔴 <b>바뀐 경로를 여기서 돌려준다</b> — 적용 뒤 {@link #diff} 로 다시 세지 않는다 (#95).
     * 구현이 인덱스까지 갱신하면 「인덱스 대 작업 트리」 diff 가 비어 <b>입혔는데 바뀐 것이
     * 없다</b>로 읽힌다. 실제로 그렇게 PR 게이트가 항상 실패했다.
     *
     * @return 패치가 건드린 저장소 상대경로 집합 — 추가·수정·삭제 전부. 비어 있지 않다
     * @throws WorkspaceException diff 형식이 깨졌거나 적용되지 않는다
     */
    java.util.Set<String> apply(SandboxWorkspace workspace, String unifiedDiff);
}
