package com.ossagent.support.secret;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * S-4 — 토큰이 문자열을 타고 나가지 않는지.
 *
 * <p>⚠ 픽스처의 토큰 유사 문자열은 <b>런타임에 조립</b>한다. 소스에 리터럴로 두면
 * {@code .claude/scripts/secret-scan.sh} 가 커밋을 막고, 무엇보다 저장소에 토큰 모양의
 * 문자열을 남기지 않는 것이 원칙이다.
 */
class TokenRedactorTest {

    private static final String FAKE_CLASSIC_PAT = "ghp_" + "a".repeat(30);
    private static final String FAKE_OAUTH_TOKEN = "gho_" + "b".repeat(30);
    private static final String FAKE_FINE_GRAINED = "github" + "_pat_" + "c".repeat(30);
    private static final String FAKE_ANTHROPIC_KEY = "sk-" + "ant-" + "d".repeat(30);
    private static final String FAKE_AWS_KEY = "AKIA" + "EFGHIJKLMNOPQRST";
    private static final String FAKE_SLACK_TOKEN = "xox" + "b-" + "1".repeat(20);

    /**
     * 개인키 <b>본문</b>은 조립하지 않는다 — 가짜임이 한눈에 보이는 고정 문자열이다.
     * 헤더·푸터는 실물 그대로여야 패턴이 물리는지 증명된다. 이 줄들은 들여쓰기 때문에
     * {@code secret-scan.sh} 의 줄 앵커({@code ^-+BEGIN …})에 걸리지 않는다.
     */
    private static final String FAKE_KEY_BODY = "NOTAREALKEYFORTESTSONLY";

    /**
     * 실제 PEM·PGP 본문 줄 길이(64~70자)에 맞춘 것.
     *
     * <p>⚠️ 진입 문턱이 {@code MIN_KEY_BODY_LENGTH} 자라, <b>짧은 샘플로는 문턱 자체를
     * 검증할 수 없다.</b> 「샘플이 먼저 대표여야 한다」 — testing-philosophy.md.
     */
    private static final String LONG_KEY_BODY = FAKE_KEY_BODY.repeat(3);

    private static final String FAKE_PEM_BLOCK = String.join("\n",
            "-----BEGIN RSA PRIVATE KEY-----",
            LONG_KEY_BODY,
            LONG_KEY_BODY,
            "-----END RSA PRIVATE KEY-----");

    @Test
    @DisplayName("GitHub classic PAT 을 마스킹한다")
    void classic_PAT을_마스킹한다_S4() {
        String redacted = TokenRedactor.redact("요청 실패 token=" + FAKE_CLASSIC_PAT);

        assertThat(redacted)
                .as("원문 토큰이 남아 있으면 로그·예외로 그대로 유출된다")
                .doesNotContain(FAKE_CLASSIC_PAT)
                .contains(TokenRedactor.MASK);
    }

    @Test
    @DisplayName("우리가 쓰지 않는 토큰 종류도 전부 마스킹한다")
    void 모든_토큰_패턴을_마스킹한다_S4() {
        String text = String.join(" ", FAKE_OAUTH_TOKEN, FAKE_FINE_GRAINED, FAKE_ANTHROPIC_KEY,
                FAKE_AWS_KEY, FAKE_SLACK_TOKEN);

        String redacted = TokenRedactor.redact(text);

        assertThat(redacted)
                .as("실수로 주입된 다른 종류의 시크릿도 걸러야 한다")
                .doesNotContain(FAKE_OAUTH_TOKEN)
                .doesNotContain(FAKE_FINE_GRAINED)
                .doesNotContain(FAKE_ANTHROPIC_KEY)
                .doesNotContain(FAKE_AWS_KEY)
                .doesNotContain(FAKE_SLACK_TOKEN);
    }

    @Test
    @DisplayName("Authorization 헤더 형태로 실린 값은 토큰 형식과 무관하게 가린다")
    void Authorization_헤더_값을_가린다_S4() {
        String redacted = TokenRedactor.redact("Authorization: Bearer 사내프록시토큰값1234567890");

        assertThat(redacted)
                .as("토큰 형식을 모르는 자격증명도 헤더 자리면 가려야 한다")
                .doesNotContain("사내프록시토큰값1234567890")
                .contains("Bearer " + TokenRedactor.MASK);
    }

    @Test
    @DisplayName("토큰이 없는 메시지는 훼손하지 않는다")
    void 토큰이_없으면_원문을_유지한다() {
        String message = "GitHub 호출 실패 path=/repos/spring-projects/spring-kafka status=500";

        assertThat(TokenRedactor.redact(message))
                .as("정상 메시지를 훼손하면 스크럽을 끄고 싶어진다")
                .isEqualTo(message);
    }

    @Test
    @DisplayName("null 과 빈 문자열을 그대로 돌려준다")
    void null과_빈문자열을_견딘다() {
        assertThat(TokenRedactor.redact(null)).isNull();
        assertThat(TokenRedactor.redact("")).isEmpty();
    }

    @Test
    @DisplayName("텍스트 한가운데 낀 PEM 개인키 블록을 통째로 가린다")
    void PEM_개인키_블록을_통째로_가린다_S4() {
        // 대상 저장소 파일을 프롬프트에 싣는 현실 케이스 — 키가 파일 시작이 아니라 중간에 있다
        String fileContent = "# 배포 메모\n앞부분 설명\n" + FAKE_PEM_BLOCK + "\n뒷부분 설명\n";

        String redacted = TokenRedactor.redact(fileContent);

        assertThat(redacted)
                .as("헤더만 가리고 키 본문을 흘려보내면 스크럽한 의미가 없다")
                .doesNotContain(LONG_KEY_BODY)
                .doesNotContain("BEGIN RSA PRIVATE KEY")
                .contains(TokenRedactor.MASK);
        assertThat(redacted)
                .as("키가 아닌 본문까지 삼키면 프롬프트가 망가진다")
                .contains("앞부분 설명")
                .contains("뒷부분 설명");
    }

    @Test
    @DisplayName("스크럽은 멱등이다 — 다시 돌려도 같다")
    void 스크럽은_멱등이다_S4() {
        // 이슈 재수집처럼 같은 값이 여러 번 통과하는 경로가 있다. 이중 마스킹이 생기면 안 된다
        String text = String.join(" ", FAKE_CLASSIC_PAT, FAKE_AWS_KEY) + "\n" + FAKE_PEM_BLOCK;

        String once = TokenRedactor.redact(text);

        assertThat(TokenRedactor.redact(once))
                .as("두 번 스크럽한 결과가 달라지면 저장된 값과 새로 읽은 값이 어긋난다")
                .isEqualTo(once);
    }

    @Test
    @DisplayName("마스킹은 되돌릴 수 없다 — 원문 조각이 남지 않는다")
    void 마스킹은_되돌릴_수_없다_S4() {
        String redacted = TokenRedactor.redact("token=" + FAKE_CLASSIC_PAT);

        assertThat(redacted)
                .as("마스킹은 암호화가 아니다. 복원 단서를 남기면 유출 경로가 그대로 남는다")
                .isEqualTo("token=" + TokenRedactor.MASK)
                .doesNotContain("a".repeat(4));
    }

    @Test
    @DisplayName("PGP 개인키 블록을 가린다 — PRIVATE KEY 뒤에 BLOCK 이 낀다")
    void PGP_개인키_블록을_가린다_S4() {
        // 🔴 이 PR 이전에는 런타임·커밋 차단 양쪽이 이것을 놓쳤다.
        //    PRIVATE KEY 직후에 대시를 요구했는데 PGP 는 사이에 「 BLOCK」이 낀다
        String text = "키 첨부합니다\n-----BEGIN PGP PRIVATE KEY BLOCK-----\n"
                + LONG_KEY_BODY + "\n-----END PGP PRIVATE KEY BLOCK-----\n확인 부탁드려요";

        assertThat(TokenRedactor.redact(text))
                .as("가장 잘 빠져나가는 형식이다 — 형식 하나가 빠지면 그 키는 통째로 나간다")
                .doesNotContain(LONG_KEY_BODY)
                .contains(TokenRedactor.MASK)
                .startsWith("키 첨부합니다")
                .endsWith("확인 부탁드려요");
    }

    @Test
    @DisplayName("SSH2·소문자 헤더 형식도 가린다")
    void 다른_표기_형식도_가린다_S4() {
        String ssh2 = "---- BEGIN SSH2 ENCRYPTED PRIVATE KEY ----\n" + LONG_KEY_BODY;
        String lowercase = "-----begin rsa private key-----\n" + LONG_KEY_BODY;

        assertThat(TokenRedactor.redact(ssh2))
                .as("RFC4716 은 대시와 BEGIN 사이에 공백을 둔다")
                .doesNotContain(LONG_KEY_BODY);
        assertThat(TokenRedactor.redact(lowercase))
                .as("대소문자로 스크럽을 피할 수 있으면 안 된다")
                .doesNotContain(LONG_KEY_BODY);
    }

    @Test
    @DisplayName("암호화된 PEM 의 머리말과 빈 줄을 건너뛰고 본문까지 가린다")
    void 암호화된_PEM_의_본문까지_가린다_S4() {
        // RFC 1421 — Proc-Type 머리말 뒤에 빈 줄이 오고 그 다음이 본문이다.
        // 빈 줄에서 멈추면 정작 키 본문이 그대로 나간다
        String encrypted = String.join("\n",
                "-----BEGIN RSA PRIVATE KEY-----",
                "Proc-Type: 4,ENCRYPTED",
                "DEK-Info: AES-128-CBC,0123456789ABCDEF",
                "",
                LONG_KEY_BODY,
                "-----END RSA PRIVATE KEY-----");

        assertThat(TokenRedactor.redact(encrypted))
                .as("머리말·빈 줄에서 멈추면 그 아래 키 본문이 통째로 나간다")
                .doesNotContain(LONG_KEY_BODY)
                .doesNotContain("DEK-Info");
    }

    @Test
    @DisplayName("END 가 없어도 키처럼 생긴 줄까지만 가리고 뒤의 본문은 남긴다")
    void END_가_없으면_키_모양_줄까지만_가린다_S4() {
        // 원래는 입력 끝까지 가렸다. 그래서 「이 헤더를 커밋하지 마세요」라고 적은 이슈 본문이
        // 그 지점부터 통째로 잘린 채 DB 에 영속됐다 — 원문 복구 경로가 없다
        String text = String.join("\n",
                "재현 절차:",
                "note: -----BEGIN RSA PRIVATE KEY----- 를 커밋하지 마세요",
                "그러면 CI 가 실패합니다",
                "로그를 첨부합니다");

        String redacted = TokenRedactor.redact(text);

        assertThat(redacted)
                .as("과차단은 「안전한 방향」이 아니다 — 본문을 망가뜨리면 스크럽을 끄고 싶어진다")
                .contains("재현 절차:")
                .contains("그러면 CI 가 실패합니다")
                .contains("로그를 첨부합니다");
        assertThat(redacted)
                .as("그래도 헤더 자체는 가려져야 한다")
                .doesNotContain("BEGIN RSA PRIVATE KEY");
    }

    @Test
    @DisplayName("END 가 없는 진짜 키 본문은 끝까지 가린다")
    void END_가_없는_진짜_키는_본문을_가린다_S4() {
        String truncated = "앞부분 설명\n-----BEGIN EC PRIVATE KEY-----\n"
                + LONG_KEY_BODY + "\n" + LONG_KEY_BODY;

        assertThat(TokenRedactor.redact(truncated))
                .as("잘린 파일이라는 이유로 키 본문이 나가면 안 된다")
                .doesNotContain(LONG_KEY_BODY)
                .startsWith("앞부분 설명");
    }

    @Test
    @DisplayName("연속된 블록을 모두 가린다")
    void 연속된_블록을_모두_가린다_S4() {
        String two = "-----BEGIN RSA PRIVATE KEY-----\n" + LONG_KEY_BODY
                + "\n-----END RSA PRIVATE KEY-----\n사이 설명\n"
                + "-----BEGIN EC PRIVATE KEY-----\n" + LONG_KEY_BODY
                + "\n-----END EC PRIVATE KEY-----";

        assertThat(TokenRedactor.redact(two))
                .as("비탐욕 매칭에서 두 번째 블록이 새는 일이 흔하다")
                .doesNotContain(LONG_KEY_BODY)
                .contains("사이 설명");
    }

    @Test
    @DisplayName("URL 에 박힌 자격증명을 가린다")
    void URL_자격증명을_가린다_S4() {
        String message = "clone 실패: https://ci-bot:s3cr3tPassw0rd@git.example.com/x.git";

        assertThat(TokenRedactor.redact(message))
                .as("이 클래스가 존재하는 이유로 든 것이 바로 「예외에 실려 나오는 요청 URL」이다")
                .doesNotContain("s3cr3tPassw0rd")
                .contains("git.example.com");
    }

    @Test
    @DisplayName("자격증명이 없는 URL 은 훼손하지 않는다")
    void 자격증명이_없는_URL_은_유지한다() {
        String message = "GET https://api.github.com/repos/spring-projects/spring-kafka";

        assertThat(TokenRedactor.redact(message)).isEqualTo(message);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("장식이_붙은_키_본문")
    @DisplayName("장식이 붙어 있어도 키 본문을 가린다")
    void 장식이_붙은_키_본문도_가린다_S4(String 형태, String text) {
        // 🔴 키가 저장소에 나타나는 형태는 맨몸 PEM 파일이 아니다. 들여쓰기 한 칸에
        //    본문이 새는데 겉보기에는 ***REDACTED*** 가 찍혀 있어 막혔다고 착각하게 된다 —
        //    「가려진 것처럼 보이는데 키는 나가는」 것이 가장 나쁜 모양이다
        assertThat(TokenRedactor.redact(text))
                .as("%s — 헤더만 가려지고 본문이 나가면 스크럽한 의미가 없다", 형태)
                .doesNotContain(LONG_KEY_BODY);
    }

    static Stream<Arguments> 장식이_붙은_키_본문() {
        String header = "-----BEGIN RSA PRIVATE KEY-----";
        String footer = "-----END RSA PRIVATE KEY-----";
        return Stream.of(
                // k8s Secret · GitHub Actions · Ansible · application.yml — 실전 1순위
                Arguments.of("YAML 2칸 들여쓰기",
                        "key: |\n  " + header + "\n  " + LONG_KEY_BODY + "\n  " + footer),
                Arguments.of("YAML 4칸 들여쓰기",
                        "key: |\n    " + header + "\n    " + LONG_KEY_BODY + "\n    " + footer),
                Arguments.of("탭 들여쓰기",
                        "key: |\n\t" + header + "\n\t" + LONG_KEY_BODY + "\n\t" + footer),
                // diff 는 LLM 프롬프트의 본체다
                Arguments.of("diff 문맥 줄",
                        " " + header + "\n " + LONG_KEY_BODY + "\n " + footer),
                Arguments.of("diff 추가 줄",
                        "+" + header + "\n+" + LONG_KEY_BODY + "\n+" + footer),
                Arguments.of("diff 삭제 줄",
                        "-" + header + "\n-" + LONG_KEY_BODY + "\n-" + footer),
                Arguments.of("마크다운 인용",
                        "> " + header + "\n> " + LONG_KEY_BODY + "\n> " + footer),
                Arguments.of("자바 문자열 연결",
                        "\"" + header + "\\n\" +\n\"" + LONG_KEY_BODY + "\\n\" +\n\"" + footer + "\""),
                Arguments.of("본문 뒤 주석",
                        header + "\n" + LONG_KEY_BODY + "   # 운영 키\n" + footer),
                Arguments.of("base64url 본문",
                        header + "\n" + LONG_KEY_BODY + "-_\n" + footer));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("헤더_언급_뒤의_정상_텍스트")
    @DisplayName("산문에 적힌 헤더 언급이 뒤 텍스트를 삼키지 않는다")
    void 헤더_언급이_정상_텍스트를_삼키지_않는다(String 형태, String 살아야_할_텍스트) {
        // 🔴 내 과차단 테스트의 샘플이 전부 「공백이 든 한국어 문장」이라 대표가 아니었다.
        //    영문 한 단어 줄·마크다운 목록·YAML 문서 구분자는 그대로 먹히고 있었고,
        //    그 값이 DB 에 영속된다. SecretPatternDriftTest 에 「샘플이 먼저 대표여야
        //    한다」고 적어 놓고 정작 여기에는 적용하지 않았다
        String text = "note: -----BEGIN RSA PRIVATE KEY----- 를 커밋하지 마세요\n"
                + 살아야_할_텍스트;

        assertThat(TokenRedactor.redact(text))
                .as("%s — 과차단은 되돌릴 수 없다. 잘린 값이 그대로 저장된다", 형태)
                .contains(살아야_할_텍스트);
    }

    static Stream<Arguments> 헤더_언급_뒤의_정상_텍스트() {
        return Stream.of(
                Arguments.of("영문 한 단어 줄", "NORMAL-2"),
                Arguments.of("대문자 한 단어", "TODO"),
                Arguments.of("마크다운 목록", "- deleteThisKey"),
                Arguments.of("YAML 문서 구분자", "---\ntitle: hello"),
                Arguments.of("마크다운 구분선", "----------\nNextSection"),
                Arguments.of("변경 이력", "Unreleased\nFixed: stuff"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("장식이_더_붙은_키_본문")
    @DisplayName("열거하지 않은 장식이 붙어도 키 본문을 가린다")
    void 열거하지_않은_장식도_뚫리지_않는다_S4(String 형태, String prefix, String suffix) {
        // 🔴 장식을 「벗길 문자 목록」으로 두면 목록에 없는 형태마다 구멍이 새로 난다.
        //    세 번 연속 그렇게 깨졌다. 이제는 여집합 + 장식 길이 예산으로 본다 —
        //    장식의 모양을 몰라도 양으로 판정한다
        String body = LONG_KEY_BODY;
        String text = prefix + "-----BEGIN RSA PRIVATE KEY-----" + suffix + "\n"
                + prefix + body + suffix + "\n"
                + prefix + "-----END RSA PRIVATE KEY-----" + suffix;

        assertThat(TokenRedactor.redact(text))
                .as("%s — 목록에 없던 형태다. 원리적으로 닫혀야 한다", 형태)
                .doesNotContain(body);
    }

    static Stream<Arguments> 장식이_더_붙은_키_본문() {
        return Stream.of(
                Arguments.of("Javadoc·C 블록 주석", " * ", ""),
                Arguments.of("셸·YAML 주석", "# ", ""),
                Arguments.of("번호 목록", "1. ", ""),
                Arguments.of("줄 끝 세미콜론", "", ";"),
                Arguments.of("줄 끝 쉼표", "", ","),
                Arguments.of("8칸 들여쓰기", "        ", ""),
                Arguments.of("줄이음 역슬래시", "", " \\"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("진입_문턱을_비껴가는_키")
    @DisplayName("첫 줄이 자격에 못 미쳐도 블록 전체가 새지 않는다")
    void 진입_문턱이_블록을_통째로_흘리지_않는다_S4(String 형태, String text) {
        // 🔴 진입 판정을 「바로 다음 한 줄」에만 걸었더니, 그 줄이 자격에 못 미치는 순간
        //    블록 전체가 샜다. 한 줄 판정이 틀리면 키가 통째로 나가는 고레버리지 실패다.
        //    gpg 2.1+ 는 Version: 을 생략해 헤더 다음이 빈 줄이다 — 지금 GPG export 의 표준 모양
        assertThat(TokenRedactor.redact(text))
                .as("%s — 첫 줄 하나로 블록 전체의 운명이 갈리면 안 된다", 형태)
                .doesNotContain(LONG_KEY_BODY);
    }

    static Stream<Arguments> 진입_문턱을_비껴가는_키() {
        String pgp = "-----BEGIN PGP PRIVATE KEY BLOCK-----";
        String pem = "-----BEGIN RSA PRIVATE KEY-----";
        return Stream.of(
                Arguments.of("PGP Version 머리말", pgp + "\nVersion: GnuPG v2\n\n" + LONG_KEY_BODY),
                Arguments.of("헤더 직후 빈 줄 (gpg 2.1+)", pgp + "\n\n" + LONG_KEY_BODY),
                Arguments.of("PEM 헤더 직후 빈 줄", pem + "\n\n" + LONG_KEY_BODY),
                Arguments.of("첫 줄이 12자", pem + "\nMIIEowIBAAKC\n" + LONG_KEY_BODY),
                Arguments.of("첫 줄이 19자", pem + "\nMIIEowIBAAKCAQEAsec\n" + LONG_KEY_BODY),
                Arguments.of("RFC4880 Hash 머리말", pgp + "\nHash: SHA256\n\n" + LONG_KEY_BODY));
    }

    @ParameterizedTest(name = "[{index}] {0}칸 들여쓰기")
    @ValueSource(ints = {4, 8, 9, 10, 12, 16})
    @DisplayName("깊은 들여쓰기에서도 키 본문을 가린다")
    void 깊은_들여쓰기에서도_가린다_S4(int 들여쓰기) {
        // 🔴 장식 예산을 상수 8 로 두었더니 9칸부터 샜다. k8s Secret·Helm·Actions 는
        //    8~12칸이 일상이고 그 파일의 diff 는 접두어가 붙어 1이 더 늘어난다.
        //    이제 예산을 헤더 줄에서 유도한다 — 본문은 헤더와 같은 장식을 달고 있다
        String pad = " ".repeat(들여쓰기);
        String text = "tls.key: |\n" + pad + "-----BEGIN RSA PRIVATE KEY-----\n"
                + pad + LONG_KEY_BODY + "\n" + pad + "-----END RSA PRIVATE KEY-----";

        assertThat(TokenRedactor.redact(text))
                .as("%d칸 — 매직넘버가 아니라 헤더가 달고 있는 만큼을 따라가야 한다", 들여쓰기)
                .doesNotContain(LONG_KEY_BODY);
    }

    @Test
    @DisplayName("YAML diff 처럼 장식이 겹쳐도 가린다")
    void 겹친_장식에서도_가린다_S4() {
        String pad = "+        ";   // diff 추가 줄 + 8칸 = 9자
        String text = pad + "-----BEGIN RSA PRIVATE KEY-----\n"
                + pad + LONG_KEY_BODY + "\n" + pad + "-----END RSA PRIVATE KEY-----";

        assertThat(TokenRedactor.redact(text))
                .as("k8s Secret 을 고치는 diff 가 정확히 이 모양이다")
                .doesNotContain(LONG_KEY_BODY);
    }

    @Test
    @DisplayName("공백만 긴 줄에서도 선형으로 돈다")
    void 공백_런에서도_선형이다_S4() {
        // 🔴 세 번째 2차식 지점. 꼬리 주석 판정을 \s+(?:#|//) 로 뒀더니 공백 런에서
        //    폭발했다 — 실측 공백 128,000개에 1분 45초. -+ 를 고치자 같은 실패가
        //    \s+ 로 옮겨간 것뿐이었다. 그래서 그 경로에서 greedy 수량자를 아예 없앴다
        String header = "-----BEGIN RSA PRIVATE KEY-----\n";

        assertLinear("공백 런", header + " ".repeat(4_000), header + " ".repeat(32_000));
    }

    @Test
    @DisplayName("END 없는 헤더가 많아도 입력 길이에 선형으로 돈다")
    void 스크럽이_입력_길이에_선형이다_S4() {
        // 🔴 회귀 방지. 원래 BEGIN…[\s\S]*?…END 는 END 없는 BEGIN 마다 입력 끝까지
        //    재스캔해 O(n²) 였다 — 실측 131KB 에 76초. GitHub 이슈 본문 상한이 65,536자이고
        //    이 코드는 수집되는 모든 이슈가 지나는 자리다(IssueSnapshot).
        String unit = "-----BEGIN RSA PRIVATE KEY-----\n" + LONG_KEY_BODY + "\n";

        assertLinear("END 없는 헤더 반복", unit.repeat(200), unit.repeat(1600));
    }

    @Test
    @DisplayName("대시만 길게 이어진 입력에서도 선형으로 돈다")
    void 대시_런에서도_선형이다_S4() {
        // 🔴 별개의 2차식 지점이었다. 헤더·푸터 정규식 선두의 -+ 가 대시 런에서
        //    시작 위치마다 끝까지 먹고 되돌아온다 — 실측 대시 200,000개에 3분 18초.
        //    마크다운 구분선 한 줄로 재현된다. 앞의 테스트는 대시가 5자뿐이라 이것을 못 잰다
        assertLinear("순수 대시 런", "-".repeat(4_000), "-".repeat(32_000));
    }

    @Test
    @DisplayName("본문 줄이 긴 대시 런이어도 선형으로 돈다")
    void 본문이_대시_런이어도_선형이다_S4() {
        // 🔴 또 다른 지점 — 푸터 정규식을 블록 안의 「줄마다」 돌린다. 그 줄이 긴 대시 런이면
        //    줄 길이에 대해 다시 2차식이 된다. 헤더만 고치면 이 경로로 그대로 재현된다.
        //    이 입력은 비용을 다 치르고 정작 아무것도 가리지 못했다 — 느리고 안 막혔다
        String header = "-----BEGIN RSA PRIVATE KEY-----\n";

        assertLinear("본문이 대시 런", header + "-".repeat(4_000), header + "-".repeat(32_000));
    }

    /** 길이 8배에 시간이 상한 배수를 넘으면 선형이 아니다 — 2차식이면 64배가 된다. */
    private static void assertLinear(String 형태, String small, String large) {
        long smallNanos = timeRedact(small);
        long largeNanos = timeRedact(large);

        assertThat(largeNanos)
                .as("""
                        %s — 길이 8배에 시간이 8배를 크게 넘었다. 2차식이면 64배가 된다.
                        느슨한 상한(25배)을 쓰는 것은 JIT·GC 흔들림 때문이고,
                        2차식 폭발은 그 잡음보다 훨씬 크게 벌어진다.
                        small=%d ns, large=%d ns""", 형태, smallNanos, largeNanos)
                .isLessThan(Math.max(smallNanos, 1_000_000L) * 25);
    }

    private static long timeRedact(String text) {
        TokenRedactor.redact(text);   // 워밍 — 첫 호출의 클래스 로딩·JIT 를 재지 않는다
        long startedAt = System.nanoTime();
        TokenRedactor.redact(text);
        return System.nanoTime() - startedAt;
    }

    @Test
    @DisplayName("mask 는 앞 4자만 남긴다")
    void mask는_앞_4자만_남긴다_S4() {
        assertThat(TokenRedactor.mask(FAKE_CLASSIC_PAT))
                .startsWith("ghp_")
                .doesNotContain("a".repeat(30))
                .endsWith(TokenRedactor.MASK);
        assertThat(TokenRedactor.mask("")).isEqualTo("(none)");
        assertThat(TokenRedactor.mask(null)).isEqualTo("(none)");
    }
}
