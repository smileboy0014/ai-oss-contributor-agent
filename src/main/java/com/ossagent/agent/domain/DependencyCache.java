package com.ossagent.agent.domain;

import com.ossagent.repository.domain.RepositoryCoordinates;

/**
 * 오프라인 실행이 쓸 의존성 캐시를 <b>준비된 상태로</b> 만든다 — #18 · Q-4 · S-3.
 *
 * <h2>🔴 이 능력이 없으면 검증이 거짓말을 한다</h2>
 *
 * <p>실행 단계는 {@code network=none} 이고 캐시 볼륨을 <b>읽기전용</b>으로 문다.
 * 그 볼륨을 <b>아무도 채우지 않으면</b> 의존성 해석이 실패하는데, 그 실패는
 * 「빌드 실패」로 나타난다 — 즉 <b>후보의 코드는 멀쩡한데 「테스트 실패」로 기록</b>되고
 * 사람은 「AI 가 못 고쳤다」로 읽는다.
 *
 * <p>그래서 검증을 시작하는 쪽이 <b>반드시 먼저 부른다.</b>
 *
 * <h2>왜 능력으로 뒤집었나</h2>
 *
 * <p>준비를 수행하는 것은 {@code agent/application} 의 조율자이고, 부르는 것은
 * {@code candidate} 의 검증 어댑터다. 구현 클래스를 직접 import 하면 <b>도메인이
 * 남의 도메인 application 에 묶인다.</b> {@code AgentRunRecorder} 가 반대 방향으로
 * 같은 일을 한 것과 같은 수법이다 — 선언은 능력 쪽에, 구현은 수행하는 쪽에.
 */
public interface DependencyCache {

    /**
     * 이 저장소의 캐시를 준비하고 <b>어느 볼륨에 준비했는지</b> 돌려준다.
     *
     * <p>🔴 <b>볼륨을 돌려주는 것이 계약의 핵심이다.</b> 호출자가 볼륨 이름을 따로
     * 계산하면 <b>A 를 준비하고 B 로 실행</b>하는 경로가 생긴다 — 그러면 이 능력이
     * 막으려던 바로 그 증상(빈 캐시로 오프라인 실행)이 준비를 하고도 발생한다.
     *
     * <p>⚠️ <b>여러 번 불러도 된다.</b> 저장소당 한 번만 실제로 준비한다.
     *
     * @throws SandboxTransientException 준비가 시간 상한을 넘었다 — 다시 하면 될 수 있다
     * @throws SandboxPermanentException 준비가 0 아닌 종료코드로 끝났다 — 같은 입력에 같은 결과다
     */
    SandboxCacheVolume ensurePrepared(SandboxWorkspace workspace,
            RepositoryCoordinates coordinates, BuildTool buildTool, String javaVersion);
}
