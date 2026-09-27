package com.ossagent.support.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * 🔴 <b>actuator 가 앱 포트에 없고 관리 포트에만 있다</b> — #74. <b>실제 서버를 띄워</b> 본다.
 *
 * <h2>왜 이 클래스가 따로 있나</h2>
 *
 * <p>{@link ActuatorManagementPortTest} 는 <b>설정</b>을 읽는다 — 「그렇게 적혀 있는가」.
 * 여기서는 <b>그 설정이 실제로 그렇게 동작하는가</b>를 본다. 둘은 다른 질문이고,
 * 설정만 보는 가드는 Boot 가 그 키를 무시하게 되어도 초록이다.
 *
 * <p>{@link ActuatorMetricsExposureTest} 는 MOCK 환경이라 웹 서버가 없어
 * {@code /actuator/**} 를 <b>칠 수가 없다.</b> #25 가 「우리 미터가 노출된다」로 보던
 * HTTP 단언이 이리로 옮겨 왔다.
 *
 * <h2>🔴 두 단언이 함께 서야 한다</h2>
 *
 * <table border="1">
 *   <caption>포트별 기대</caption>
 *   <tr><th>포트</th><th>{@code /actuator/metrics}</th><th>무엇을 증명하나</th></tr>
 *   <tr><td>앱 포트</td><td><b>404</b></td><td>분리가 실제로 일어났다 — #74</td></tr>
 *   <tr><td>관리 포트</td><td><b>200</b></td><td>계측이 여전히 보인다 — #25 FR-1</td></tr>
 * </table>
 *
 * <p>둘 중 하나만 있으면 반대쪽으로 고장 난다. 404 만 보면 <b>actuator 를 통째로
 * 꺼도</b> 초록이고(계측이 사라진 것을 「닫혔다」로 읽는다), 200 만 보면
 * <b>분리가 풀려도</b> 초록이다.
 *
 * <h2>⚠️ 포트 번호는 덮는다 — 분리는 덮지 않는다</h2>
 *
 * <p>{@code management.server.port=0} 으로 <b>임의 포트</b>를 받는다. 운영값 9090 을
 * 그대로 쓰면 병렬 실행·개발 머신의 점유와 충돌한다.
 *
 * <p>🔴 <b>덮은 것은 「어느 번호인가」이지 「갈렸는가」가 아니다.</b> {@code 0} 은
 * {@code ManagementPortType} 에서 여전히 {@code DIFFERENT} 다.
 * 다만 그래서 <b>이 클래스만으로는 운영 설정에서 포트 줄이 지워진 것을 잡지 못한다</b> —
 * 내 오버라이드가 그것을 덮기 때문이다. 그 축은 {@link ActuatorManagementPortTest} 가
 * <b>아무것도 덮지 않고</b> 본다. 두 클래스가 있는 이유가 이것이다.
 *
 * <p>ℹ️ {@code address} 는 덮지 않는다 — {@code application.yml} 의 {@code 127.0.0.1} 이
 * 그대로 적용되고, 테스트도 루프백으로 접속하므로 닿는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
@ActiveProfiles("fakes")
class ActuatorManagementPortIsolationTest {

    /**
     * 🔴 <b>허용집합이다 — 거부목록이 아니다.</b>
     *
     * <p>「{@code env} 가 열렸는가」를 묻는 거부목록은 <b>목록에 없는 엔드포인트마다 구멍이
     * 새로 난다</b>({@code testing-philosophy.md} 「거부목록으로 방어하지 않는다」).
     * 뒤집어서 <b>열려도 되는 것</b>을 세면 무엇이 늘어나든 걸린다.
     *
     * <p>⚠️ {@code include: "*"} 도 이 단언에 걸린다 — 그때 링크가 수십 개로 늘기 때문이다.
     * 설정 문자열을 파싱하지 않고 <b>실제로 열린 것</b>을 읽는 이유가 그것이다.
     *
     * <p>ℹ️ {@code self} 와 {@code *-path}·{@code *-requiredMetricName} 류는 엔드포인트가
     * 늘어난 것이 아니라 {@code /actuator} 자신과 각 엔드포인트의 <b>경로 변수 링크</b>다.
     */
    private static final Set<String> ALLOWED_ENDPOINTS = Set.of(
            "self", "health", "health-path", "info", "metrics", "metrics-requiredMetricName");

    @LocalServerPort
    private int appPort;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("🔴 앱 포트에는 actuator 가 없다 — 포트가 실제로 갈렸다 #74")
    void 앱_포트에는_actuator가_없다() {
        assertThat(managementPort)
                .as("관리 포트와 앱 포트가 같다 — 분리가 일어나지 않았다 (#74)")
                .isNotEqualTo(appPort);

        ResponseEntity<String> response =
                restTemplate.getForEntity(url(appPort, "/actuator/metrics"), String.class);

        assertThat(response.getStatusCode())
                .as("""
                        애플리케이션 포트에서 actuator 가 응답했다.
                        이 저장소에는 시큐리티가 없으므로, 앱 포트에 열려 있다는 것은
                        운영 지표가 인증 없이 읽힌다는 뜻이다 — #74""")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("관리 포트에서는 우리 미터가 보인다 — #25 FR-1 이 여전히 성립한다")
    void 관리_포트에서는_우리_미터가_보인다() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(url(managementPort, "/actuator/metrics"), String.class);

        assertThat(response.getStatusCode())
                .as("""
                        관리 포트에서도 actuator 가 안 보인다.
                        포트를 옮긴 것이 아니라 계측을 꺼 버린 것이다 — #25 가 만든 것이 사라졌다""")
                .isEqualTo(HttpStatus.OK);

        assertThat(response.getBody())
                .as("계측을 만들고 안 보여주면 #25 가 하는 일이 없다")
                .contains(MetricNames.CANDIDATE_COUNT);
    }

    @Test
    @DisplayName("🔴 열린 엔드포인트가 허용집합 안이다 — 넓히면 빨개진다 #74")
    void 열린_엔드포인트가_허용집합_안이다() {
        ResponseEntity<JsonNode> response =
                restTemplate.getForEntity(url(managementPort, "/actuator"), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<String> exposed = new ArrayList<>();
        response.getBody().get("_links").fieldNames().forEachRemaining(exposed::add);

        assertThat(exposed)
                .as("/actuator 가 링크를 하나도 안 돌려줬다 — 0건을 검사하고 초록이 된 것이다")
                .isNotEmpty();

        assertThat(exposed)
                .as("""
                        허용집합 밖의 actuator 엔드포인트가 열렸다.
                        이 저장소에는 시큐리티가 없으므로 늘어난 것은 그대로 인증 없이 읽힌다 —
                        env·configprops 는 설정값을(시크릿 포함) 내보내고
                        heapdump·threaddump 는 메모리를 통째로 준다. S-4 와 같은 방향이다.
                        의도한 추가라면 ALLOWED_ENDPOINTS 에 함께 넣는다 —
                        그 diff 가 리뷰에 보이는 것이 요점이다""")
                .isSubsetOf(ALLOWED_ENDPOINTS);
    }

    private String url(int port, String path) {
        // 🔴 management.server.address 가 127.0.0.1 이라 localhost 로 접속해야 닿는다
        return "http://127.0.0.1:" + port + path;
    }
}
