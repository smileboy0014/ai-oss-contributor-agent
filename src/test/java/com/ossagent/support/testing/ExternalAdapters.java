package com.ossagent.support.testing;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「이 클래스는 <b>대외 기술 어댑터</b>인가」를 판정한다 — 자동 스위트가 네트워크·컨테이너를
 * 타지 않게 하는 가드의 판정기다.
 *
 * <p><b>고정 목록이 아니라 규칙으로 판정한다.</b> 「지금 있는 어댑터 N개가 컨텍스트에 없는가」를
 * 보면 나중에 추가되는 어댑터를 영영 잡지 못한다. {@code ExternalTextMarkerTest} 가 같은 이유로
 * 규칙 검사를 택했다.
 *
 * <h2>두 신호 중 하나라도 걸리면 대외 어댑터다</h2>
 *
 * <table border="1">
 *   <caption>판정 신호</caption>
 *   <tr><th>#</th><th>신호</th><th>왜</th></tr>
 *   <tr><td>1</td><td>패키지가 {@code adapter.out.{github|llm|sandbox}}</td>
 *       <td>규율 ③ 이 기술 어댑터를 <b>기술 이름</b>의 패키지에 두게 한다</td></tr>
 *   <tr><td>2</td><td>필드로 <b>네트워크 클라이언트를 (전이적으로) 보유</b></td>
 *       <td>이름이 아니라 <b>능력</b>으로 판정한다. 패키지를 옮기거나 이름을 바꿔도 걸린다</td></tr>
 * </table>
 *
 * <p>신호 2 가 중요한 이유 — 신호 1 만 두면 <b>패키지 이름을 바꾸는 것만으로 가드를
 * 통과</b>할 수 있다. 가드가 「이름 규칙」이 아니라 「실제로 네트워크를 탈 수 있는가」를
 * 보게 해야 무력화가 어렵다. 반대로 신호 2 만 두면 우리가 모르는 클라이언트를 쓰는
 * 어댑터를 놓치므로, 둘을 함께 쓴다.
 *
 * <pre>
 * GitHubApiClient         support.github    · RestClient 보유    → 대외 ✅ (신호 2)
 * GitHubIssueSource       adapter.out.github · ApiClient 보유    → 대외 ✅ (신호 1·2)
 * DockerCodeSandbox       adapter.out.sandbox                   → 대외 ✅ (신호 1)
 *
 * GitHubErrorTranslator   support.github    · Clock 만 보유      → 허용 (순수 변환)
 * GitHubProperties        support.github    · 설정값 record      → 허용
 * StaticTokenCredentials  support.github    · 토큰 문자열만      → 허용 (호출하지 않는다)
 * OssRepositoryRepository adapter.out.persistence               → 허용 (아래)
 * </pre>
 *
 * <p><b>{@code persistence} 는 일부러 금지 목록에 없다.</b> DB 는 대역 대상이 아니다 —
 * PostgreSQL 은 Testcontainers 로 실제로 띄운다(Q-2b). 트랜잭션 경계·동시성은 실 DB 가
 * 아니면 검증되지 않으므로, 영속 어댑터가 컨텍스트에 올라오는 것은 <b>의도된 것</b>이다.
 *
 * <p>⚠ <b>패키지 이름을 바꿔 이 가드를 통과시키는 것은 위반이다.</b> 신호 2 가 그것을
 * 대부분 막지만, 새 클라이언트 타입을 쓰면 빠져나갈 수 있다. 그때는
 * {@link #NETWORK_CLIENTS} 에 타입을 <b>추가</b>하는 것이 옳은 조치다.
 */
public final class ExternalAdapters {

    /** 느리거나 비결정적이라 자동 스위트에서 대역으로 갈음하는 대외 기술 — Q-9. */
    private static final Set<String> EXTERNAL_TECHNOLOGIES = Set.of("github", "llm", "sandbox");

    /**
     * 실제로 네트워크·데몬을 탈 수 있는 클라이언트 타입.
     *
     * <p>문자열로 둔 이유 — {@code DockerClient} 등은 아직 의존성에 없다. 클래스 참조로 쓰면
     * 그 라이브러리를 먼저 들여야 하고, 판정기가 의존성에 끌려다니게 된다.
     */
    private static final Set<String> NETWORK_CLIENTS = Set.of(
            "org.springframework.web.client.RestClient",
            "org.springframework.web.client.RestTemplate",
            "org.springframework.web.reactive.function.client.WebClient",
            "java.net.http.HttpClient",
            "com.github.dockerjava.api.DockerClient",
            "okhttp3.OkHttpClient");

    private static final String BASE_PACKAGE = "com.ossagent";

    private ExternalAdapters() {
    }

    /** 두 신호 중 하나라도 걸리면 대외 어댑터다. */
    public static boolean isExternalAdapter(Class<?> type) {
        if (type == null || !isOurs(type.getName())) {
            return false;
        }
        return isExternalAdapterPackage(type.getName())
                || holdsNetworkClient(type, new HashSet<>());
    }

    /**
     * 신호 1 만 본다 — 클래스를 로드하지 않고 <b>이름만으로</b> 판정한다.
     *
     * <p>아직 존재하지 않는 어댑터 이름을 테스트로 고정할 때 쓴다.
     */
    public static boolean isExternalAdapterPackage(String className) {
        if (!isOurs(className)) {
            return false;
        }
        int lastDot = className.lastIndexOf('.');
        String packageName = lastDot < 0 ? "" : className.substring(0, lastDot);

        List<String> segments = List.of(packageName.split("\\."));
        for (int i = 0; i < segments.size(); i++) {
            // adapter → out → {기술} 의 연속된 3칸일 때만 인정한다.
            // 「패키지에 github 가 있으면」으로 두면 support.github 의 순수 변환기·설정값까지 잡는다
            if ("adapter".equals(segments.get(i))
                    && i + 2 < segments.size()
                    && "out".equals(segments.get(i + 1))
                    && EXTERNAL_TECHNOLOGIES.contains(segments.get(i + 2))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 신호 2 — 필드를 따라 내려가며 네트워크 클라이언트 보유를 본다.
     *
     * <p>우리 타입은 <b>전이적으로</b> 따라간다. {@code GitHubIssueSource} 는 {@code RestClient} 를
     * 직접 들지 않고 {@code GitHubApiClient} 를 들기 때문이다.
     */
    private static boolean holdsNetworkClient(Class<?> type, Set<Class<?>> visited) {
        if (type == null || !visited.add(type)) {
            return false;   // 순환 참조에서 무한 재귀를 막는다
        }
        for (Class<?> current = type; current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                Class<?> fieldType = field.getType();
                if (NETWORK_CLIENTS.contains(fieldType.getName())) {
                    return true;
                }
                if (isOurs(fieldType.getName()) && holdsNetworkClient(fieldType, visited)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isOurs(String className) {
        return className != null && className.startsWith(BASE_PACKAGE + ".");
    }

    /** 실패 메시지에 「무엇이 금지인지」를 실어 보내기 위해 노출한다. */
    public static Set<String> externalTechnologies() {
        return EXTERNAL_TECHNOLOGIES;
    }
}
