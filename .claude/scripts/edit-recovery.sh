#!/bin/bash
# PostToolUseFailure hook: Edit 도구 실패 시 패턴별 복구 가이드 제공
# Edit|Write matcher 로 등록된다. 실패 원인을 문자열로 분기해 다음 시도를 안내한다.

input=$(cat)

if echo "$input" | grep -qi "not found in file\|old_string.*not found\|no match"; then
  echo "[Edit 복구 가이드] old_string이 파일에 없습니다."
  echo "→ Read 도구로 파일을 다시 읽고, 정확한 문자열(공백/들여쓰기 포함)을 복사하세요."
  echo "→ 줄번호 접두사를 old_string에 포함하지 마세요."

elif echo "$input" | grep -qi "multiple matches\|not unique\|ambiguous"; then
  echo "[Edit 복구 가이드] old_string이 파일에 여러 번 등장합니다."
  echo "→ 더 많은 주변 컨텍스트를 포함하여 고유하게 만들거나, replace_all: true를 사용하세요."

elif echo "$input" | grep -qi "same as new\|old_string.*equals.*new_string\|must be different"; then
  echo "[Edit 복구 가이드] old_string과 new_string이 동일합니다."
  echo "→ 실제로 변경할 내용이 있는지 확인하세요."

elif echo "$input" | grep -qi "file not found\|no such file\|does not exist"; then
  echo "[Edit 복구 가이드] 파일이 존재하지 않습니다."
  echo "→ Glob 도구로 정확한 파일 경로를 확인하세요."
  echo "→ 새 파일이라면 Write 도구를 사용하세요."

elif echo "$input" | grep -qi "read.*first\|must read"; then
  echo "[Edit 복구 가이드] 파일을 먼저 읽어야 합니다."
  echo "→ Read 도구로 파일을 한 번 읽은 후 Edit를 시도하세요."

else
  echo "[Edit 복구 가이드] Edit 실패. Read 도구로 파일을 다시 확인하고 재시도하세요."
fi
