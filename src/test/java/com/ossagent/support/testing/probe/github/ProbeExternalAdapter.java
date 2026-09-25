package com.ossagent.support.testing.probe.github;

/**
 * 가드의 <b>자기 검증용 미끼</b>다. 기능이 없다 — 존재 자체가 검사 대상이다.
 *
 * <p>오늘 {@code main} 에는 대외 어댑터가 <b>하나도 없다</b>(#6 이 미머지).
 * 그래서 「컨텍스트에 대외 어댑터 빈이 없다」는 단언이 <b>0건을 검사하고 초록</b>이 된다.
 * 이 저장소는 그 거짓 신호에 이미 한 번 당했다 — 조건부 skip 때문에 5건이 조용히
 * 건너뛰어졌고 빌드는 SUCCESS 였다({@code SchemaMigrationTest} 주석).
 *
 * <p>그래서 <b>판정기가 실제로 물어뜯는지</b>를 따로 증명한다. 이 클래스는 패키지에
 * {@code github} 세그먼트를 갖고 있으므로 {@code ExternalAdapterPackages} 가 반드시
 * 대외 어댑터로 판정해야 한다. 판정기가 고장 나면 이 미끼를 놓치고 테스트가 실패한다.
 *
 * <p>⚠ <b>스프링 스테레오타입 애노테이션을 붙이지 않는다.</b> Gradle 의 test 런타임
 * 클래스패스에는 test 클래스 디렉토리가 포함되므로, {@code @Component} 를 달면
 * {@code com.ossagent} 컴포넌트 스캔에 실제로 잡혀 <b>가드가 스스로를 잡는</b> 상황이 된다.
 */
public final class ProbeExternalAdapter {

    private ProbeExternalAdapter() {
    }
}
