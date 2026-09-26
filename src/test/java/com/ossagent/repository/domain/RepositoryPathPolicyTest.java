package com.ossagent.repository.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 대상 저장소가 준 경로가 우리 호출에 들어가도 되는가 — 이슈 #15.
 *
 * <p>#15 는 <b>대상 저장소가 통제하는 문자열이 우리 URL 경로로 들어가는 첫 단계</b>다.
 * #7 은 고정 경로 목록만 읽어 이 문제가 없었다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RepositoryPathPolicyTest {

    private static RepositoryTreeEntry blob(String path) {
        return new RepositoryTreeEntry(path, RepositoryTreeEntry.EntryType.BLOB, 0);
    }

    @Test
    void 평범한_소스_경로는_통과한다() {
        assertThat(RepositoryPathPolicy.isSafe("src/main/java/org/x/Foo.java")).isTrue();
        assertThat(RepositoryPathPolicy.isSelectableSource(blob("src/main/java/org/x/Foo.java")))
                .isTrue();
    }

    @Test
    void 상위로_거슬러_올라가는_세그먼트를_막는다() {
        assertThat(RepositoryPathPolicy.isSafe("src/../../etc/passwd")).isFalse();
        assertThat(RepositoryPathPolicy.isSafe("./Foo.java")).isFalse();
    }

    @Test
    void 점_두_개가_이름_안에_있는_것은_막지_않는다() {
        assertThat(RepositoryPathPolicy.isSafe("src/foo..bar/Baz.java"))
                .as("문자열 검사는 무고한 이름을 막는다 — 세그먼트로 본다")
                .isTrue();
    }

    @Test
    void 절대경로와_스킴을_막는다() {
        assertThat(RepositoryPathPolicy.isSafe("/etc/passwd")).isFalse();
        assertThat(RepositoryPathPolicy.isSafe("https://evil.test/x.java")).isFalse();
    }

    @Test
    void 제어문자와_줄바꿈을_막는다() {
        assertThat(RepositoryPathPolicy.isSafe("src/main/Foo.java\nGET /admin"))
                .as("줄바꿈은 URL 조립과 로그 인젝션 양쪽에서 문제를 만든다")
                .isFalse();
        assertThat(RepositoryPathPolicy.isSafe("src/main/Foo .java")).isFalse();
    }

    @Test
    void 빈_값은_배제한다() {
        assertThat(RepositoryPathPolicy.isSafe(null)).isFalse();
        assertThat(RepositoryPathPolicy.isSafe("  ")).isFalse();
        assertThat(RepositoryPathPolicy.isSafe("src//Foo.java")).isFalse();
    }

    @Test
    void 바이너리는_소스가_아니다() {
        assertThat(RepositoryPathPolicy.isSelectableSource(blob("docs/logo.png")))
                .as("허용 목록이다 — 막을 것을 열거하면 열거에 없는 형태마다 구멍이 난다")
                .isFalse();
        assertThat(RepositoryPathPolicy.isSelectableSource(blob("libs/thing.jar"))).isFalse();
    }

    @Test
    void 빌드_산출물_디렉터리는_후보가_아니다() {
        assertThat(RepositoryPathPolicy.isSelectableSource(blob("build/classes/org/x/Foo.java")))
                .as("생성물을 고쳐 봐야 다음 빌드에 지워진다")
                .isFalse();
        assertThat(RepositoryPathPolicy.isSelectableSource(blob("node_modules/a/b.json"))).isFalse();
    }

    @Test
    void blob_이_아니면_후보가_아니다() {
        assertThat(RepositoryPathPolicy.isSelectableSource(
                new RepositoryTreeEntry("src/main/java/A.java",
                        RepositoryTreeEntry.EntryType.OTHER, 0)))
                .as("서브모듈·심볼릭링크를 후보로 올리면 예산만 태운다")
                .isFalse();
    }
}
