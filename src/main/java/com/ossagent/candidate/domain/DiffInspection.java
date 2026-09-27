package com.ossagent.candidate.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 생성된 diff 의 판정 규칙 — #19.
 *
 * <h2>🔴 검사를 둘로 가른다 — 싼 것이 먼저, 그리고 <b>잘리지 않는 것이</b> 먼저</h2>
 *
 * <p>{@code DIFF} 는 <b>출력을 파싱해서 판정하는 유일한 단계</b>이고, 그래서
 * {@link StageOutcome#UNDETERMINED} 가 나올 수 있는 유일한 단계다
 * ({@code sandbox.max-output-chars} = 200,000).
 *
 * <p>그 위험을 <b>검사마다 다르게</b> 진다.
 *
 * <table border="1">
 *   <caption>두 검사</caption>
 *   <tr><th></th><th>명령</th><th>출력 크기</th><th>잘리면</th></tr>
 *   <tr><td>{@link #inspectScope}</td><td>{@code git diff --numstat}</td>
 *       <td>파일당 한 줄 — <b>수십 줄</b></td>
 *       <td>사실상 불가능. 그래도 나면 {@code UNDETERMINED}</td></tr>
 *   <tr><td>{@link #inspectPatch}</td><td>{@code git diff --unified=0}</td>
 *       <td>변경 줄 전부 — <b>수만 줄이 될 수 있다</b></td>
 *       <td>🔴 실제로 난다</td></tr>
 * </table>
 *
 * <p>가른 이유: <b>가장 중요한 판정(계획 범위 밖 파일)이 절단에 인질로 잡히지 않게</b> 하는
 * 것이다. 한 덩어리로 봤으면 큰 diff 하나가 범위 검사까지 통째로 {@code UNDETERMINED} 로
 * 만들었을 것이고, 그러면 <b>가장 자주 쓰이는 게이트가 가장 자주 무력해진다.</b>
 *
 * <h2>🕳 한계 — 조용히 통과하는 것부터</h2>
 *
 * <ol>
 *   <li>🔴 {@link #inspectPatch} 의 디버그 잔재 목록은 <b>열거</b>다. 목록에 없는 형태
 *       ({@code e.printStackTrace()} 의 변형 · 다른 로깅 파사드)는 통과한다.
 *       여집합으로 뒤집을 수 없는 종류의 판정이라 <b>열거로 남긴다</b> —
 *       대신 이것이 유일한 방어가 아니다({@code AI Review} · 사람 검토가 뒤에 있다)</li>
 *   <li>{@code numstat} 의 <b>이름 변경</b> 줄({@code old => new})은 경로를 하나로
 *       특정할 수 없다. 🔴 <b>범위 밖으로 본다</b> — 통과시키는 쪽으로 틀리면
 *       계획에 없는 파일 이동이 조용히 지나간다</li>
 *   <li>서브모듈·심링크 변경은 {@code numstat} 에서 평범한 경로로 보인다.
 *       계획에 있으면 통과한다</li>
 * </ol>
 */
public final class DiffInspection {

    /**
     * 한 파일의 변경 줄 수 상한.
     *
     * <p>이슈 하나를 고치는 변경이 이보다 크면 <b>계획을 벗어난 것</b>이거나 생성 파일이다.
     * 사람이 보게 만든다 — S-2 의 「검증되지 않은 코드가 리뷰 큐로」를 막는 쪽이다.
     */
    public static final int MAX_CHANGED_LINES_PER_FILE = 800;

    /**
     * 디버그 잔재로 보는 <b>추가된</b> 줄의 조각들.
     *
     * <p>⚠️ 열거다 — 위 한계 ①. {@code pr-convention.md} 의 자체 리뷰 체크리스트
     * (「디버그 잔재 없음 — {@code System.out.println}, 주석 처리된 코드」)를 그대로 옮겼다.
     */
    private static final List<String> DEBUG_RESIDUE = List.of(
            "System.out.print",
            "System.err.print",
            "printStackTrace(",
            "TODO: remove",
            "FIXME: remove",
            ".only(",
            "@Disabled",
            "@Ignore");

    /** 무엇이 걸렸는가. */
    public enum Kind {
        /** 🔴 계획에 없는 파일을 고쳤다 — 가장 중요한 판정이다. */
        OUT_OF_SCOPE,
        /** 바이너리가 들어왔다. diff 로 검토할 수 없는 것을 PR 에 싣지 않는다. */
        BINARY,
        /** 한 파일이 너무 크게 바뀌었다. */
        TOO_LARGE,
        /** 디버그 잔재가 추가됐다. */
        DEBUG_RESIDUE
    }

    /**
     * 걸린 것 1건.
     *
     * <p>⚠️ {@code path} 는 대상 저장소에서 온 문자열이다 — 로그에 포맷 문자열로 쓰지 않는다.
     * 이 값이 {@link StageResult#summary} 로 들어가면 거기서 스크럽된다.
     */
    public record Finding(Kind kind, String path, String detail) {
    }

    private DiffInspection() {
    }

    /**
     * {@code git diff --numstat} 출력으로 <b>범위 · 바이너리 · 크기</b>를 본다.
     *
     * <p>형식: {@code <추가>\t<삭제>\t<경로>}. 바이너리는 앞 둘이 {@code -} 다.
     *
     * @param numstat      명령 출력
     * @param plannedPaths 계획이 손대기로 한 경로. 🔴 <b>비어 있으면 전부 범위 밖</b>이다
     */
    public static List<Finding> inspectScope(String numstat, Set<String> plannedPaths) {
        List<Finding> findings = new ArrayList<>();
        if (numstat == null || numstat.isBlank()) {
            return findings;
        }
        for (String line : numstat.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("\t", 3);
            if (parts.length < 3) {
                // 🔴 해석하지 못한 줄을 건너뛰지 않는다 — 「못 읽음」을 「위반 없음」으로
                //    번역하는 바로 그 접기다 (S-5 의 축)
                findings.add(new Finding(Kind.OUT_OF_SCOPE, "?",
                        "diff 목록의 줄을 해석하지 못했다"));
                continue;
            }
            String path = parts[2].strip();
            if (path.contains("=>")) {
                // 한계 ② — 이름 변경은 경로를 특정할 수 없다. 통과시키지 않는다
                findings.add(new Finding(Kind.OUT_OF_SCOPE, path, "계획에 없는 파일 이름 변경"));
                continue;
            }
            if (!plannedPaths.contains(path)) {
                findings.add(new Finding(Kind.OUT_OF_SCOPE, path, "계획에 없는 파일이 바뀌었다"));
            }
            if ("-".equals(parts[0]) && "-".equals(parts[1])) {
                findings.add(new Finding(Kind.BINARY, path, "바이너리 변경은 diff 로 검토할 수 없다"));
                continue;
            }
            int changed = parseCount(parts[0]) + parseCount(parts[1]);
            if (changed > MAX_CHANGED_LINES_PER_FILE) {
                findings.add(new Finding(Kind.TOO_LARGE, path, "변경 줄 수=" + changed));
            }
        }
        return findings;
    }

    /**
     * {@code git diff --unified=0} 출력으로 <b>디버그 잔재</b>를 본다.
     *
     * <p>🔴 <b>추가된 줄({@code +})만</b> 본다. 원래 저장소에 있던 {@code System.out} 을
     * 우리가 넣은 것으로 보고하면, 오탐이 잦은 게이트가 되고 <b>오탐으로 죽는 게이트는
     * 반드시 꺼진다.</b>
     */
    public static List<Finding> inspectPatch(String patch) {
        List<Finding> findings = new ArrayList<>();
        if (patch == null || patch.isBlank()) {
            return findings;
        }
        String currentPath = "?";
        for (String line : patch.split("\\R")) {
            if (line.startsWith("+++ ")) {
                currentPath = stripDiffPrefix(line.substring(4).strip());
                continue;
            }
            if (!line.startsWith("+") || line.startsWith("+++")) {
                continue;
            }
            String added = line.substring(1);
            for (String needle : DEBUG_RESIDUE) {
                if (added.contains(needle)) {
                    findings.add(new Finding(Kind.DEBUG_RESIDUE, currentPath, "추가된 줄: " + needle));
                    break;
                }
            }
        }
        return findings;
    }

    private static String stripDiffPrefix(String path) {
        String value = path;
        if (value.startsWith("b/") || value.startsWith("a/")) {
            value = value.substring(2);
        }
        return value;
    }

    private static int parseCount(String raw) {
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 사람이 읽을 한 줄로 접는다. 🔴 {@link StageResult} 가 이것을 다시 스크럽한다. */
    public static String describe(List<Finding> findings) {
        StringBuilder sb = new StringBuilder("diff 검사에서 ")
                .append(findings.size()).append("건이 걸렸다");
        for (Finding finding : findings) {
            sb.append(System.lineSeparator())
                    .append("- [").append(finding.kind().name().toLowerCase(Locale.ROOT)).append("] ")
                    .append(finding.path()).append(" — ").append(finding.detail());
        }
        return sb.toString();
    }
}
