package com.ossagent.support.testing.probe.adapter.out.github;

/**
 * 판정기 <b>신호 1(패키지)</b>의 미끼다. 기능이 없다 — 존재하는 위치가 곧 검사 대상이다.
 *
 * <p>패키지가 {@code …adapter.out.github} 로 끝나므로 {@code ExternalAdapters} 가 반드시
 * 대외 어댑터로 판정해야 한다. 판정기가 고장 나면 이 미끼를 놓치고 테스트가 실패한다.
 *
 * <p>미끼가 필요한 이유 — 「컨텍스트에 대외 어댑터 빈이 없다」는 판정기가 <b>항상
 * {@code false} 를 돌려줘도</b> 똑같이 초록이다. 이 저장소는 그 종류의 거짓 신호에 이미
 * 당한 적이 있다(조건부 skip 으로 5건이 조용히 건너뛰어졌고 빌드는 SUCCESS 였다 —
 * {@code SchemaMigrationTest} 주석).
 *
 * <p>⚠ <b>스프링 스테레오타입 애노테이션을 붙이지 않는다.</b> Gradle 의 test 런타임
 * 클래스패스에는 test 클래스 디렉토리가 포함되므로, {@code @Component} 를 달면
 * {@code com.ossagent} 컴포넌트 스캔에 실제로 잡혀 <b>가드가 스스로를 잡는</b> 상황이 된다.
 */
public final class ProbePackageAdapter {

    private ProbePackageAdapter() {
    }
}
