package com.ossagent.pullrequest.domain;

/**
 * 커밋의 author·committer 이자 <b>DCO sign-off 의 서명자</b>.
 *
 * <h2>왜 설정값인가</h2>
 *
 * <p>{@code GET /user} 로 받아올 수도 있지만 그 경로는 쓰지 않는다 —
 * {@code public_repo} 스코프로는 <b>비공개 이메일을 받지 못하고</b>, 그러면 sign-off 가
 * {@code noreply} 주소로 나가 대상 저장소의 DCO 검사에서 걸린다. 무엇으로 서명할지는
 * <b>사람이 정하는 값</b>이라 설정으로 받는다.
 *
 * <p>⚠️ <b>시크릿이 아니다.</b> 커밋에 실려 공개 저장소에 영구히 남는 값이므로
 * 마스킹 대상이 아니고, 반대로 <b>그만큼 신중하게 정해야 하는 값</b>이다.
 */
public record CommitIdentity(String name, String email) {

    public CommitIdentity {
        name = require(name, "name");
        email = require(email, "email");
        // 🔴 sign-off 트레일러는 `Signed-off-by: {name} <{email}>` 한 줄이다.
        //    줄바꿈이 섞이면 트레일러가 쪼개져 「서명이 있는데 형식이 깨진」 커밋이 된다 —
        //    DCO 봇은 그것을 서명 없음으로 읽는다. 꺾쇠는 주소 경계를 밀어낸다.
        rejectStructural(name, "name");
        rejectStructural(email, "email");
        if (!email.contains("@")) {
            throw new IllegalArgumentException("커밋 서명 이메일 형식이 아닙니다");
        }
    }

    /** {@code Signed-off-by:} 트레일러 한 줄 — DCO. */
    public String signoffLine() {
        return "Signed-off-by: %s <%s>".formatted(name, email);
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("커밋 서명의 " + field + " 가 비어 있습니다");
        }
        return value.trim();
    }

    private static void rejectStructural(String value, String field) {
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0
                || value.indexOf('<') >= 0 || value.indexOf('>') >= 0) {
            throw new IllegalArgumentException(
                    "커밋 서명의 " + field + " 에 줄바꿈·꺾쇠를 쓸 수 없습니다");
        }
    }
}
