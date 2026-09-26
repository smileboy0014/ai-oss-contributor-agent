package com.ossagent.agent.domain;

/**
 * 대상 저장소 코드를 <b>격리된 컨테이너 안에서만</b> 실행하는 능력 — #17 · S-3.
 *
 * <p>우리가 clone·build·test 하는 것은 <b>신뢰할 수 없는 코드</b>다. Gradle/Maven 빌드
 * 스크립트는 임의 코드 실행이고, 대상 저장소는 우리가 통제하지 않는다.
 * <b>이 인터페이스를 거치지 않는 실행 경로를 만들지 않는다.</b>
 *
 * <h2>구현이 반드시 지키는 것</h2>
 * <ol>
 *   <li><b>격리 설정을 한 자리에서만 만든다</b> — 흩어지면 「어느 경로로는 네트워크가
 *       열린다」가 생긴다. 한 곳에 모아야 Docker 없이 전수 검증할 수 있다</li>
 *   <li><b>컨테이너는 실행마다 새로 만들고 끝나면 지운다</b> — 상태를 재사용하면 앞 실행의
 *       산출물이 다음 판정을 오염시킨다. 타임아웃·예외 경로에서도 지운다</li>
 *   <li><b>정리 실패를 조용히 넘기지 않는다</b> — {@link SandboxResult#cleanedUp()} 로
 *       알리고 로그를 남긴다. 넘기면 컨테이너가 쌓인다</li>
 *   <li><b>호스트 환경변수를 컨테이너에 전달하지 않는다</b> — S-3 · S-4.
 *       {@link SandboxCommand} 에 그것을 받을 자리가 없다</li>
 *   <li><b>트랜잭션 안에서 부르지 않는다</b> — 실행이 최대 30분이다. 커넥션이 30분 잡힌다</li>
 * </ol>
 *
 * <h2>⚠ 실패의 두 종류를 섞지 않는다</h2>
 *
 * <p>빌드가 실패한 것은 <b>결과</b>({@link SandboxResult#exitCode()})이고 예외가 아니다 —
 * 그것이 이 제품의 게이트가 작동한 모습이다. 예외는 <b>실행 자체를 못 한 경우</b>에만 난다.
 *
 * <p>구현체는 {@code adapter/out/sandbox} 에 <b>기술 이름</b>으로 둔다 —
 * {@code DockerCodeSandbox}. 규율 ③.
 */
public interface CodeSandbox {

    /**
     * 한 번 실행하고 결과를 받는다.
     *
     * @param command 세 가지 중 하나 — {@link WarmCommand} · {@link SeedCacheCommand}
     *                · {@link ExecuteCommand}. <b>각각이 격리 설정 하나에 대응한다</b>
     * @throws SandboxTransientException 데몬 부재·일시 장애·이미지 없음 — 다시 보낼 가치가 있다
     * @throws SandboxPermanentException 지원하지 않는 요청·안전 경계 위반 시도 — 재시도로 풀리지 않는다
     */
    SandboxResult run(SandboxCommand command);
}
