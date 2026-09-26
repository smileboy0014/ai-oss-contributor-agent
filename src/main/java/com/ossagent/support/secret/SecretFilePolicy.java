package com.ossagent.support.secret;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 대상 저장소의 파일을 LLM 프롬프트에 싣기 전, <b>경로만 보고</b> 배제할지 판정한다 — S-4.
 *
 * <p>{@link TokenRedactor} 와 하는 일이 다르다. 저쪽은 <b>내용</b>에서 알려진 패턴을 가리고,
 * 여기는 <b>애초에 열지 않는다.</b> 키 파일에는 우리가 모르는 형식의 자격증명이 얼마든지
 * 들어 있어, 패턴 매칭만으로는 「가렸다」고 말할 수 없다. 둘은 겹치는 방어가 아니라
 * 순서가 다른 방어다.
 *
 * <h2>⚠️ 아직 소비자가 없다 — 계약은 #15 가 확정한다</h2>
 * 지금 대상 저장소 파일을 프롬프트에 넣는 경로는 <b>없다.</b> #7 은 고정 경로 목록
 * ({@code CONTRIBUTING.md} 등)만 읽어 배제가 필요 없고, 키워드 코드 검색이 <b>#15</b> 다.
 * 그런데도 지금 만드는 이유는 목록의 정확성을 소비자 없이도 테스트로 고정할 수 있고,
 * #15 가 급할 때 「배제 목록을 어디 두지」부터 고민하면 그때 빠지기 때문이다.
 *
 * <p>소비자가 없으므로 <b>시그니처가 추측이다.</b> 그래서 메서드를 하나만 두고 정책 객체·
 * 설정 주입으로 키우지 않았다 — 쓰이지 않는 API 를 다형적으로 만들면 #15 가 그 모양에
 * 맞추느라 계약을 잘못 잡는다. 확장이 필요하면 <b>#15 가 실제 호출부를 보고</b> 정한다.
 *
 * <h2>판정은 fail-closed 다</h2>
 * 애매하면 <b>배제</b>한다. {@code external-deps.md} 가 「모를 때는 되돌릴 수 없는 쪽을
 * 피한다」로 정리해 둔 그 기준이다 — 파일 하나를 덜 보내면 분석 품질이 조금 떨어지지만
 * (되돌릴 수 있다), 키를 한 번 보내면 회수가 폐기·재발급뿐이다(되돌릴 수 없다).
 *
 * <p>그래서 대소문자를 가리지 않고, {@code secrets/} 는 깊이를 가리지 않으며,
 * {@code .env.example} 도 배제한다. 예시 파일이라도 대상 저장소가 실값을 적어 뒀을 수 있다.
 */
public final class SecretFilePolicy {

    /** 확장자 — 자격증명 컨테이너. 내용 형식이 제각각이라 패턴 스크럽으로 덮이지 않는다. */
    private static final Set<String> SECRET_EXTENSIONS = Set.of("pem", "key", "p12");

    /** 디렉토리 이름 — 경로 어디에 있어도 배제한다. */
    private static final Set<String> SECRET_DIRECTORIES = Set.of("secrets");

    /**
     * 🔴 <b>점 파일 접두어 — 구분자 없이 그대로 문다</b>({@code .env*}).
     *
     * <p>{@code .envrc}(direnv)가 대표적이다. {@code export AWS_SECRET_ACCESS_KEY=…} 가
     * 그대로 들어 있는데, 구분자를 요구하면 <b>{@code .env} 와 {@code .env.local} 은 막고
     * {@code .envrc} 는 통과시킨다.</b> 이슈 #28 의 완료 조건도 {@code .env*} 다.
     *
     * <p>점으로 시작하는 이름공간이라 오탐 위험이 거의 없다 — 소스 파일이
     * {@code .env} 로 시작하지 않는다.
     */
    private static final List<String> DOTFILE_PREFIXES = List.of(".env");

    /**
     * 단어 접두어 — <b>구분자를 요구한다.</b>
     *
     * <p>{@code credentials.json} · {@code credentials-prod.yml} 은 물되
     * <b>{@code CredentialsProvider.java} 는 통과시켜야 한다.</b> 실재하는 클래스 이름이고
     * (`org.apache.http.client.CredentialsProvider`), 인증 버그를 고칠 때 LLM 이 봐야 하는
     * 바로 그 파일이다. 여기서 구분자를 빼면 대상 저장소의 <b>소스</b>가 배제된다.
     *
     * <p>{@link #DOTFILE_PREFIXES} 와 규칙이 다른 것은 일관성 부족이 아니라,
     * 점 파일은 이름공간이고 단어는 클래스 이름에 나타나기 때문이다.
     */
    private static final List<String> SECRET_NAME_PREFIXES = List.of("credentials");

    private static final List<String> NAME_SEPARATORS = List.of(".", "-", "_");

    private SecretFilePolicy() {
    }

    /**
     * 이 경로의 파일을 프롬프트에 실으면 안 되는가.
     *
     * @param repoRelativePath 대상 저장소 루트 기준 상대 경로 (예: {@code src/main/app.java}).
     *                         {@code null}·공백이면 <b>배제</b>한다 — 경로를 모르는 파일을
     *                         보내는 것보다 안 보내는 쪽의 손해가 작다
     */
    public static boolean isSecretPath(String repoRelativePath) {
        if (repoRelativePath == null || repoRelativePath.isBlank()) {
            return true;
        }

        String normalized = repoRelativePath.replace('\\', '/').toLowerCase(Locale.ROOT);
        // ⚠ limit -1 이어야 후행 빈 세그먼트가 남는다. 기본 split 은 그것을 버려서
        //   "src/" 의 파일명이 "src" 가 되고, 아래 빈 파일명 가드가 죽는다
        String[] segments = normalized.split("/", -1);

        for (int i = 0; i < segments.length - 1; i++) {
            if (SECRET_DIRECTORIES.contains(segments[i])) {
                return true;
            }
        }

        String fileName = segments[segments.length - 1];
        if (fileName.isEmpty()) {
            return true;
        }

        for (String prefix : DOTFILE_PREFIXES) {
            if (fileName.startsWith(prefix)) {
                return true;
            }
        }

        for (String prefix : SECRET_NAME_PREFIXES) {
            if (fileName.equals(prefix)) {
                return true;
            }
            for (String separator : NAME_SEPARATORS) {
                if (fileName.startsWith(prefix + separator)) {
                    return true;
                }
            }
        }

        int dot = fileName.lastIndexOf('.');
        return dot >= 0 && SECRET_EXTENSIONS.contains(fileName.substring(dot + 1));
    }
}
