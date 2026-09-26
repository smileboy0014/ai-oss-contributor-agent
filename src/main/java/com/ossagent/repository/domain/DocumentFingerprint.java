package com.ossagent.repository.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * 규약 문서 한 개를 <b>관측한 결과의 지문</b> — 이슈 #68.
 *
 * <p>「내용」이 아니라 「관측」의 지문인 것이 요점이다. 파일이 <b>없다</b>는 것도 안정적인
 * 관측이므로 {@link #ABSENT} 라는 지문을 갖는다. 없던 {@code AGENTS.md} 가 생기면
 * {@code absent} → 해시로 바뀌고, 그것이 곧 「규약이 바뀌었다」는 증거다.
 *
 * <h2>🔴 왜 domain 에 있나</h2>
 *
 * <p>계산을 어댑터에 두면 페이크({@code FakePolicyDocumentSource})가 <b>다른 계산</b>을 하게
 * 되고, 그러면 테스트가 검증하는 지문과 운영이 저장하는 지문이 다른 물건이 된다.
 * 규율 ③ 이 막는 「기술이 domain 에 들어오는 것」에 해당하지 않는다 — {@code MessageDigest} 는
 * {@code Clock}·{@code Base64} 와 같은 JDK 표준이고, GitHub·LLM 같은 <b>경계 밖 기술</b>이 아니다.
 *
 * <h2>🔴 S-4 — 원문이 새지 않는다</h2>
 *
 * <p>SHA-256 은 단방향이라 지문에서 대상 저장소 원문을 복원할 수 없다. 그래서 이 값은
 * {@code @ExternalText} 대상이 아니고 {@code ExternalTextScrubRegistryTest} 등록 행도 필요 없다.
 *
 * @param value {@code absent} 또는 소문자 hex 64자
 */
public record DocumentFingerprint(String value) {

    /** 「그 경로에 파일이 없다」는 관측. 🔴 「지문을 못 구했다」와 <b>다른 것</b>이다. */
    public static final String ABSENT_VALUE = "absent";

    public static final DocumentFingerprint ABSENT = new DocumentFingerprint(ABSENT_VALUE);

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    public DocumentFingerprint {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("지문이 비어 있습니다");
        }
        if (!ABSENT_VALUE.equals(value) && !SHA256_HEX.matcher(value).matches()) {
            // 🔴 형식을 느슨하게 받으면 「경로=사유」 같은 다른 문자열이 지문 자리에 들어와도
            //    조용히 비교 대상이 된다. 그러면 「바뀌었다」가 아무 때나 참이 되고,
            //    강등(markUnverifiable)이 오탐으로 돈다
            throw new IllegalArgumentException("지문 형식이 아닙니다: " + value);
        }
    }

    /** 읽은 내용의 지문. 같은 내용이면 항상 같은 값이다. */
    public static DocumentFingerprint of(String content) {
        if (content == null) {
            throw new IllegalArgumentException("내용이 null 이면 지문을 만들 수 없습니다");
        }
        return new DocumentFingerprint(sha256Hex(content));
    }

    public boolean isAbsent() {
        return ABSENT_VALUE.equals(value);
    }

    private static String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 은 JDK 가 보장하는 알고리즘이다. 여기 오면 런타임이 망가진 것이다
            throw new IllegalStateException("SHA-256 을 쓸 수 없습니다", e);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
