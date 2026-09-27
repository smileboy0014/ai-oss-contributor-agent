package com.ossagent.agent.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 의존성 캐시 볼륨의 이름 — #17 · Q-4.
 *
 * <h2>🔴 왜 값 타입인가 — 볼륨 이름도 외부 입력이다</h2>
 *
 * <p>이름이 대상 저장소 좌표({@code owner/name})에서 나온다. 그런데
 * <b>Docker 는 마운트 소스가 경로 형태({@code /} 로 시작하거나 {@code .} 로 시작)면
 * 볼륨이 아니라 호스트 경로 바인드로 해석한다.</b> 검증하지 않으면
 * {@code owner = "/"} 같은 값이 <b>호스트 경로를 컨테이너에 RW 로 꽂는다</b> —
 * {@link SandboxWorkspace} 가 막는 것과 같은 사고가 다른 문으로 들어온다.
 *
 * <p>{@link SandboxImages} 의 이미지 이름 주입과 <b>정확히 같은 계열</b>이다.
 * 한쪽만 막으면 막지 않은 것과 같다.
 *
 * <h2>저장소별인 이유</h2>
 *
 * <p>전역 캐시면 저장소 A 의 워밍이 B 의 판정 입력을 바꾼다 — 「무엇이 캐시에 있는가」가
 * 달라지므로 재현성이 깨진다. 「같은 입력에 같은 결과」가 이 제품의 게이트를 떠받친다.
 */
public record SandboxCacheVolume(String name) {

    private static final String PREFIX = "oss-agent-cache-";

    /** Docker 볼륨 이름 규칙. 경로로 해석될 여지가 없는 문자만. */
    private static final Pattern VALID = Pattern.compile("[a-z0-9][a-z0-9_.-]{1,126}");

    public SandboxCacheVolume {
        if (name == null || !VALID.matcher(name).matches()) {
            throw new SandboxPermanentException(
                    "캐시 볼륨 이름이 규칙에 맞지 않는다 — 경로로 해석될 수 있다 (S-3)");
        }
    }

    /**
     * 대상 저장소 좌표로 볼륨 이름을 만든다.
     *
     * <p>⚠ <b>좌표를 그대로 잇지 않는다.</b> 허용 문자만 남기고 나머지는 {@code -} 로 접는다.
     * 접은 결과가 비면 거부한다 — 「전부 걸러져서 접두사만 남은」 이름은 모든 저장소가
     * 같은 볼륨을 공유하게 만든다.
     *
     * <h2>🔴 접는 것만으로는 <b>다른 저장소가 같은 볼륨</b>을 쓴다 (2026-09-27 · #18 리뷰)</h2>
     *
     * <p>소문자화 + 비영숫자를 {@code -} 로 접기 + {@code -} 로 잇기이므로 <b>서로 다른
     * 좌표가 같은 이름</b>이 된다.
     *
     * <table border="1">
     *   <caption>실제로 충돌하는 짝</caption>
     *   <tr><th>좌표 A</th><th>좌표 B</th><th>접은 결과</th></tr>
     *   <tr><td>{@code a-b/c}</td><td>{@code a/b-c}</td><td>{@code a-b-c}</td></tr>
     *   <tr><td>{@code Foo/Bar}</td><td>{@code foo/bar}</td><td>{@code foo-bar}</td></tr>
     * </table>
     *
     * <p>🔴 <b>「볼륨 이름이 겹친다」로 끝나지 않는다.</b> {@code SandboxPipeline} 이
     * 「이 저장소는 준비됐다」를 <b>볼륨 이름으로</b> 기억하므로, B 가 A 의 워밍으로
     * 준비됐다고 표시되어 <b>A 의 캐시로 오프라인 실행</b>을 한다. 그 결과가 바로
     * 그 파이프라인이 막으려던 <b>「코드는 멀쩡한데 테스트 실패」</b>다.
     *
     * <p>그래서 <b>원래 좌표의 지문</b>을 붙인다. 읽을 수 있는 슬러그는 남기되,
     * 같고 다름은 지문이 판정한다 — 접는 규칙이 무엇을 뭉개든 무관해진다.
     */
    public static SandboxCacheVolume forRepository(String owner, String name) {
        String slug = sanitize(owner) + "-" + sanitize(name);
        if (slug.replace("-", "").isEmpty()) {
            throw new SandboxPermanentException(
                    "저장소 좌표에서 볼륨 이름을 만들 수 없다 — 전역 공유가 되어선 안 된다");
        }
        if (slug.length() > MAX_SLUG_LENGTH) {
            slug = slug.substring(0, MAX_SLUG_LENGTH);
        }
        return new SandboxCacheVolume(PREFIX + slug + "-" + fingerprintOf(owner, name));
    }

    /** 슬러그 상한. 접두사 + 슬러그 + {@code -} + 지문이 볼륨 이름 규칙(127자) 안에 들어가야 한다 */
    private static final int MAX_SLUG_LENGTH = 80;

    /** 지문 길이(16진). 충돌 확률이 저장소 수 규모에서 무시할 만하다 */
    private static final int FINGERPRINT_LENGTH = 12;

    /**
     * 🔴 <b>접기 전의 좌표</b>로 만든다 — 접은 뒤 만들면 충돌을 그대로 물려받는다.
     *
     * <p>구분자로 {@code /} 를 쓴다. 좌표에 {@code /} 가 들어올 수 없으므로
     * ({@code RepositoryCoordinates} 가 막는다) {@code a-b/c} 와 {@code a/b-c} 의
     * 입력 문자열이 서로 달라진다.
     */
    private static String fingerprintOf(String owner, String name) {
        String canonical = (owner == null ? "" : owner) + "/" + (name == null ? "" : name);
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(FINGERPRINT_LENGTH);
            for (int i = 0; hex.length() < FINGERPRINT_LENGTH; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.substring(0, FINGERPRINT_LENGTH);
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 은 모든 JVM 이 제공한다. 없으면 「대충 이름을 짓는다」가 아니라 멈춘다 —
            // 여기서 물러서면 충돌 가능한 이름이 조용히 돌아간다
            throw new SandboxPermanentException("볼륨 지문을 만들 수 없다");
        }
    }

    /** 컨테이너 안에서의 마운트 지점. 🔴 볼륨 루트가 곧 {@code modules-2} 의 부모다. */
    public String containerPath() {
        return "/dependency-cache";
    }

    private static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            out.append(Character.isLetterOrDigit(c) ? c : '-');
        }
        return out.toString();
    }
}
