package com.ossagent.agent.domain;

/**
 * 대상 저장소의 빌드 도구 — #17.
 *
 * <p>이미지·워밍 명령·오프라인 플래그가 전부 이 값으로 갈린다.
 *
 * <p>⚠ <b>탐지하지 않는다.</b> 워크스페이스를 들여다봐 {@code pom.xml} 을 찾는 식으로
 * 판별하면 이 도메인이 호스트 파일시스템을 읽는 책임을 갖게 된다. <b>값으로 받는다</b> —
 * 판별은 호출자(#18)의 몫이고, 근거는 {@code RepositoryPolicy} 에 이미 있다.
 */
public enum BuildTool {

    GRADLE,

    /**
     * ⚠ <b>지원하지 않는다</b> — {@link #requireSupported()} 가 거부한다.
     *
     * <p>Maven 로컬 저장소는 읽기전용으로 쓸 수 없다({@code _remote.repositories} 를 쓴다).
     * Gradle 의 {@code GRADLE_RO_DEP_CACHE} 같은 공유 읽기전용 캐시 기능이 없어
     * 2단계 실행(Q-4)이 성립하지 않는다.
     *
     * <p>🔴 <b>지원하지 않는 것을 「네트워크를 열어 실행」으로 대신하지 않는다.</b>
     * 그것이 최악이다 — 신뢰할 수 없는 코드를 네트워크가 열린 채로 돌리게 된다.
     * 값을 남겨 두는 이유는 <b>실패시킬 대상이 필요해서</b>다.
     */
    MAVEN;

    public boolean isSupported() {
        return this == GRADLE;
    }

    /**
     * 지원 대상인지 확인한다.
     *
     * @throws SandboxPermanentException 지원하지 않는 빌드 도구. <b>재시도로 풀리지 않는다</b>
     */
    public BuildTool requireSupported() {
        if (!isSupported()) {
            throw new SandboxPermanentException(
                    "지원하지 않는 빌드 도구다 — 읽기전용 의존성 캐시 동등물이 없다 (Q-4): " + name());
        }
        return this;
    }
}
