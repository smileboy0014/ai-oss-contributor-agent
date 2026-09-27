package com.ossagent.pullrequest.domain;

import java.util.regex.Pattern;

/**
 * Fork 의 <b>기준 브랜치</b> — 우리가 만드는 브랜치가 갈라져 나오는 자리 (보통 {@code main}).
 *
 * <h2>왜 값 타입인가 — 같은 전제를 공유하는데 방어만 빠져 있었다</h2>
 *
 * <p>이 값은 <b>요청 경로에 그대로 조립</b>된다.
 *
 * <pre>
 * GET /repos/{owner}/{name}/git/ref/heads/{baseBranch}
 * </pre>
 *
 * <p>{@code RepositoryCoordinates} 는 <b>정확히 이 이유로</b> owner·name 을
 * {@code [A-Za-z0-9._-]+} 로 제한해 뒀다 — 「{@code ..} 가 섞이면 경로가 다르게 정규화되어,
 * <b>우리가 어느 URL 을 부르는지 통제하고 있다</b>는 전제가 깨진다」. 초안은 이 값만 그 규율에서
 * 빠져 {@code blank} 검사만 했고, 안전 리뷰가 그 불일치를 짚었다.
 *
 * <p>⚠️ <b>여기는 읽기 경로라 S-1 위반은 아니다.</b> 그래도 막는 이유는 두 가지다 —
 * ① 같은 전제 위에 선 값들의 방어가 갈려 있으면 다음 사람이 어느 쪽이 규율인지 모른다
 * ② 잘못된 기준 커밋 위에 쌓은 커밋은 <b>쓰기 경로로 흘러간다.</b>
 *
 * <p>🔴 {@code /} 를 허용한다 — {@code release/3.2.x} 같은 브랜치가 정상이다. 다만
 * 세그먼트마다 검사하므로 {@code ..} 는 들어올 수 없다.
 */
public record BaseBranch(String value) {

    private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9._~-]+");

    private static final int MAX_LENGTH = 255;

    public BaseBranch {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("기준 브랜치가 비어 있습니다");
        }
        value = value.trim();
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("기준 브랜치 이름이 %d 자를 넘습니다".formatted(MAX_LENGTH));
        }
        if (value.startsWith("/") || value.endsWith("/")) {
            throw new IllegalArgumentException("기준 브랜치가 / 로 시작하거나 끝날 수 없습니다: " + value);
        }
        for (String segment : value.split("/", -1)) {
            if (!SAFE_SEGMENT.matcher(segment).matches() || segment.equals(".")
                    || segment.equals("..")) {
                throw new IllegalArgumentException(
                        "기준 브랜치 세그먼트가 허용 문자를 벗어났습니다: " + value);
            }
        }
    }

    /** 대부분의 대상 저장소 기본값. */
    public static BaseBranch main() {
        return new BaseBranch("main");
    }

    @Override
    public String toString() {
        return value;
    }
}
