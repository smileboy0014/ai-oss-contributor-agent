package com.ossagent.support.testing.probe;

/**
 * 검사기의 <b>미끼</b>다 — {@code fakes} 프로필 없이 컨텍스트를 띄우는 클래스를
 * {@code IntegrationTestProfileTest} 가 실제로 잡아내는지 증명한다.
 *
 * <p>미끼가 없으면 「프로필 없는 테스트가 0건이다」는 <b>검사기가 아무것도 못 잡아도</b>
 * 똑같이 초록이다. 지금 저장소에 위반이 0건이라 <b>음성만 관찰되기 때문</b>에,
 * 물림을 회귀로 고정하려면 상시 양성 표본이 하나 필요하다.
 *
 * <p>⚠ <b>이름이 {@code Test} 로 끝나지 않고 {@code @Test} 메서드도 없다.</b>
 * JUnit 이 실행 대상으로 잡지 않으므로 <b>이 컨텍스트는 실제로 뜨지 않는다.</b>
 * 클래스패스 스캔에만 잡히는 표본이다 — 미끼 때문에 실어댑터가 올라오면 본말전도다.
 *
 * <p>⚠ 이 클래스는 {@code probe} 패키지에 있고, 검사기의 <b>본 단언은 이 패키지를 제외</b>한다.
 * 제외하지 않으면 미끼 자신이 위반으로 잡혀 가드가 영구 RED 가 된다.
 */
@BypassProbe
public final class ProbeUnprofiledBootstrap {

    private ProbeUnprofiledBootstrap() {
    }
}
