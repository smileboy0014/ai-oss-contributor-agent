package com.ossagent.support.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.ossagent.support.testing.AgentIntegrationTest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.core.env.Environment;

/**
 * 🔴 <b>관리 엔드포인트가 앱 포트에서 떨어져 루프백에만 묶여 있는가</b> — #74.
 *
 * <h2>이 저장소에 시큐리티가 없다</h2>
 *
 * <p>{@code spring-boot-starter-security} 가 없으므로 {@code /actuator/**} 는
 * <b>인증 없이</b> 열린다. 자격증명이 새는 것은 아니다 —
 * {@link ActuatorMetricsExposureTest} 가 태그에 식별자가 박히지 않는 것을 고정한다.
 * 그러나 운영 지표(LLM 토큰 소비량 · 안전 게이트 차단 횟수 · 후보 수)는 그대로 읽힌다.
 *
 * <p>🔴 <b>이슈가 세워진 진짜 이유는 노출량이 아니라 「결정이 주석에만 있었다」는 것</b>이다.
 * {@code application.yml} 이 「배포할 때 결정할 것」을 주석으로 적어 두고 있었고,
 * 주석은 배포 시점에 잊힌다. 이 테스트가 그 결정을 <b>값으로</b> 붙든다.
 *
 * <h2>왜 리터럴이 아니라 {@link ManagementPortType} 인가</h2>
 *
 * <p>「{@code port} 가 9090 인가」를 묻지 않는다. 그것은 <b>열거</b>라, 포트를 9091 로
 * 바꾸면 무고하게 빨개지면서 정작 <b>분리가 풀리는 것</b>은 못 잡을 수 있다.
 *
 * <p>{@code ManagementPortType.get} 은 <b>Boot 자신이 관리 컨텍스트를 가를 때 쓰는
 * 바로 그 판정</b>이다. 같은 축을 쓰면 가드가 기구와 어긋날 수 없다 —
 * 이 값이 {@code DIFFERENT} 라는 것과 「actuator 가 별도 컨텍스트로 간다」는 것이
 * 같은 말이다.
 *
 * <h2>🔴 물림 — 무엇을 빼면 무엇이 빨개지나</h2>
 *
 * <table border="1">
 *   <caption>돌연변이</caption>
 *   <tr><th>제거</th><th>결과</th></tr>
 *   <tr><td>{@code management.server.port}</td>
 *       <td>{@code SAME} — 첫 번째가 빨개진다</td></tr>
 *   <tr><td>{@code management.server.address} 를 {@code 0.0.0.0} 으로</td>
 *       <td>루프백이 아니다 — 두 번째가 빨개진다</td></tr>
 *   <tr><td>{@code show-details}</td>
 *       <td>{@code null} — 세 번째가 빨개진다</td></tr>
 * </table>
 *
 * <p>🔴 <b>그리고 둘은 짝이다.</b> Boot 는 「포트가 갈리지 않았는데 관리 주소를 줬다」를
 * <b>기동에서 거부</b>한다. 그래서 위 표의 첫 줄(포트만 제거)은 실제로는
 * <b>테스트가 빨개지기 전에 컨텍스트가 뜨지 않는다</b> — 실측으로 확인했다.
 * 조용히 앱 포트로 돌아가는 경로가 <b>없다</b>는 뜻이고, 이 가드보다 강한 성질이다.
 *
 * <p>⚠️ <b>이 테스트는 프로퍼티를 덮지 않는다.</b> 덮는 순간 「운영 설정이 무엇인가」가
 * 아니라 「내가 넣은 값이 무엇인가」를 묻게 된다. {@link ActuatorMetricsExposureTest}
 * 쪽은 반대로 덮어야 하는데, 그 이유는 그쪽 javadoc 에 적었다.
 *
 * <h2>🕳 여기서 증명하지 <b>못하는</b> 것</h2>
 *
 * <p>「실제로 9090 에만 바인드됐는가」는 <b>보지 않는다.</b> 통합 테스트는 MOCK 환경이라
 * 서버를 띄우지 않으므로 소켓이 없다. 이 테스트가 붙드는 것은 <b>설정이 그렇게 되어
 * 있다는 것</b>까지이고, 바인드 자체는 Boot 의 몫이다.
 *
 * <p>그리고 <b>인증은 여전히 없다.</b> 루프백 밖에서 못 닿을 뿐, 같은 호스트에 들어온
 * 것은 무엇이든 읽을 수 있다. 시큐리티를 들일지는 배포 형태가 정해져야 서는 판단이고,
 * 배포 형태는 Q-3(프로필 분리)에서 아직 미결이다.
 */
@AgentIntegrationTest
class ActuatorManagementPortTest {

    @Autowired
    private Environment environment;

    @Test
    @DisplayName("🔴 관리 포트가 앱 포트와 분리돼 있다 — #74")
    void 관리_포트가_앱_포트와_분리돼_있다() {
        ManagementPortType portType = ManagementPortType.get(environment);

        assertThat(portType)
                .as("""
                        관리 엔드포인트가 애플리케이션 포트에 그대로 열려 있다.
                        이 저장소에는 시큐리티가 없으므로 /actuator/** 가 인증 없이 앱 포트에 노출된다.
                        application.yml 의 management.server.port 를 확인한다 — #74""")
                .isEqualTo(ManagementPortType.DIFFERENT);
    }

    @Test
    @DisplayName("🔴 관리 주소가 루프백이다 — 포트 분리만으로는 닫히지 않는다 #74")
    void 관리_주소가_루프백이다() throws UnknownHostException {
        String address = environment.getProperty("management.server.address");

        assertThat(address)
                .as("""
                        management.server.address 가 없다.
                        포트만 바꾸고 주소를 비워 두면 0.0.0.0 에 묶여
                        「인증 없이 열린다」는 사실이 그대로 남는다 — #74""")
                .isNotBlank();

        // ⚠ 호스트명을 적으면 여기서 DNS 를 탄다 — 테스트가 네트워크를 타는 유일한 경로다.
        //   운영 설정이 IP 리터럴(127.0.0.1)이라 지금은 해석이 일어나지 않는다.
        //   호스트명으로 바꾸려는 사람은 이 줄을 함께 본다
        assertThat(InetAddress.getByName(address).isLoopbackAddress())
                .as("관리 엔드포인트가 루프백 밖(%s)에 묶여 있다 — 외부에서 인증 없이 읽힌다", address)
                .isTrue();
    }

    @Test
    @DisplayName("health 상세가 나가지 않는다 — show-details: never")
    void health_상세가_나가지_않는다() {
        assertThat(environment.getProperty("management.endpoint.health.show-details"))
                .as("""
                        Boot 기본값과 같더라도 값으로 적어 둔다.
                        적혀 있지 않으면 「지금 무엇이 나가는가」가 코드에 없고,
                        when-authorized 로 바꾸는 순간 인증이 없어 always 처럼 동작한다 — #74""")
                // ⚠ 대소문자를 가리지 않는다. Boot 의 느슨한 바인딩은 NEVER·Never 를 모두 받는데
                //   여기서만 빨개지면 그것은 오탐이고, 오탐으로 죽는 게이트는 반드시 꺼진다
                .isEqualToIgnoringCase("never");
    }
}
