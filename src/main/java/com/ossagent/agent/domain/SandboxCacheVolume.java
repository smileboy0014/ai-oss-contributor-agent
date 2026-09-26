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
     */
    public static SandboxCacheVolume forRepository(String owner, String name) {
        String slug = sanitize(owner) + "-" + sanitize(name);
        if (slug.replace("-", "").isEmpty()) {
            throw new SandboxPermanentException(
                    "저장소 좌표에서 볼륨 이름을 만들 수 없다 — 전역 공유가 되어선 안 된다");
        }
        return new SandboxCacheVolume(PREFIX + slug);
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
