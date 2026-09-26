package com.ossagent.agent.domain;

import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java 버전으로 컨테이너 이미지를 고른다 — #17 FR-7.
 *
 * <h2>🔴 왜 화이트리스트인가 — 이슈 본문에 없는 S-4 접촉</h2>
 *
 * <p>{@code RepositoryPolicy.javaVersion} 은 <b>LLM 이 대상 저장소의 {@code CONTRIBUTING.md}
 * 를 읽어 만든 값</b>이다(#7). 즉 <b>대상 저장소가 통제하는 텍스트</b>가 우리 코드의 입력으로
 * 들어온다.
 *
 * <p>그 값을 이미지 좌표로 조립하면 — {@code "21-jdk"} 가 아니라
 * {@code "evil.example.com/backdoor:latest"} 가 오면 — <b>공격자 레지스트리의 이미지를
 * 우리가 받아 실행</b>하게 된다. 컨테이너 격리가 아무리 단단해도 소용이 없다.
 * 실행할 이미지를 공격자가 고르는 순간 게임이 끝나기 때문이다.
 *
 * <p><b>문자열을 조립하는 경로를 만들지 않는다.</b> 아는 값이면 아는 이미지를 주고,
 * 모르면 기본 이미지로 간다.
 *
 * <p>⚠ 모르는 값을 <b>실패시키지 않는 이유</b> — 실패 방향이 가역적이다. 기본 이미지로
 * 돌린 빌드는 Java 버전이 안 맞으면 컴파일 에러로 드러나고, 그것은 되돌릴 수 있다.
 * 반면 규약 판정(S-5)처럼 되돌릴 수 없는 판단은 fail-closed 여야 한다 —
 * {@code external-deps.md} 의 「모를 때는 되돌릴 수 없는 쪽을 피한다」.
 * <b>대신 조용히 넘어가지 않는다</b> — WARN 을 남긴다.
 */
public final class SandboxImages {

    private static final Logger log = LoggerFactory.getLogger(SandboxImages.class);

    /**
     * 아는 Java 버전 → 이미지. <b>이 표 밖의 값은 이미지가 되지 않는다.</b>
     *
     * <p>{@code eclipse-temurin} 을 쓰는 것은 {@code .env.example} 의 기본값과 같다.
     * 늘리려면 이 표를 고쳐야 하고, 그것이 리뷰에 보인다.
     */
    private static final Map<String, String> BY_JAVA_VERSION = Map.of(
            "8", "eclipse-temurin:8-jdk",
            "11", "eclipse-temurin:11-jdk",
            "17", "eclipse-temurin:17-jdk",
            "21", "eclipse-temurin:21-jdk",
            "24", "eclipse-temurin:24-jdk",
            "25", "eclipse-temurin:25-jdk");

    private SandboxImages() {
    }

    /**
     * @param javaVersion 대상 저장소에서 온 <b>신뢰할 수 없는</b> 문자열. {@code null} 가능
     * @param defaultImage 설정된 기본 이미지 ({@code SANDBOX_DOCKER_IMAGE})
     */
    public static String of(String javaVersion, String defaultImage) {
        if (defaultImage == null || defaultImage.isBlank()) {
            throw new SandboxPermanentException("기본 이미지가 설정되지 않았다");
        }
        String key = normalize(javaVersion);
        String image = BY_JAVA_VERSION.get(key);
        if (image == null) {
            // 대상 저장소 문자열을 포맷 인자로만 넘긴다 — 포맷 문자열로 쓰면 로그 인젝션이다.
            // 길이를 제한해 로그를 통째로 채우는 것도 막는다
            log.warn("알 수 없는 java 버전이라 기본 이미지로 간다 javaVersion={} default={}",
                    abbreviate(javaVersion), defaultImage);
            return defaultImage;
        }
        return image;
    }

    /** {@code "1.8"}·{@code "21.0.2"}·{@code "Java 17"} 같은 표기를 메이저 버전으로 줄인다. */
    private static String normalize(String javaVersion) {
        if (javaVersion == null) {
            return "";
        }
        String value = javaVersion.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("1.")) {
            value = value.substring(2);   // 1.8 → 8
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isDigit(c)) {
                digits.append(c);
            } else if (!digits.isEmpty()) {
                break;    // 첫 숫자 뭉치만 — 21.0.2 → 21
            }
        }
        return digits.toString();
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "(null)";
        }
        String trimmed = value.strip();
        return trimmed.length() <= 32 ? trimmed : trimmed.substring(0, 32) + "…";
    }
}
