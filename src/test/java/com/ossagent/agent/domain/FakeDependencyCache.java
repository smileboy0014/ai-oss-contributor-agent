package com.ossagent.agent.domain;

import com.ossagent.repository.domain.RepositoryCoordinates;
import com.ossagent.support.testing.FakeAdapter;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link DependencyCache} 대역 — Q-9 「능력 대역」 층.
 *
 * <p>🔴 <b>실패 모드를 재현할 수 있어야 한다.</b> 준비 실패(워밍·씨딩)가 호출자에게
 * 어떻게 보이는지가 이 능력의 요점이다 — 그것을 삼키면 빈 캐시로 오프라인 실행하게 된다.
 */
@FakeAdapter
public class FakeDependencyCache implements DependencyCache {

    private final List<RepositoryCoordinates> prepared = new ArrayList<>();

    private RuntimeException nextFailure;

    public void reset() {
        prepared.clear();
        nextFailure = null;
    }

    /** 준비 요청을 받은 좌표들. <b>실행보다 먼저 불렸는가</b>를 단언할 때 쓴다 */
    public List<RepositoryCoordinates> prepared() {
        return List.copyOf(prepared);
    }

    /** 준비 자체가 실패한 경우 — 그때는 실행으로 넘어가면 안 된다 */
    public FakeDependencyCache thenFailWith(RuntimeException failure) {
        this.nextFailure = failure;
        return this;
    }

    @Override
    public SandboxCacheVolume ensurePrepared(SandboxWorkspace workspace,
            RepositoryCoordinates coordinates, BuildTool buildTool, String javaVersion) {
        prepared.add(coordinates);
        if (nextFailure != null) {
            throw nextFailure;
        }
        return SandboxCacheVolume.forRepository(coordinates.owner(), coordinates.name());
    }
}
