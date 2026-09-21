#!/bin/bash
# PostToolUse hook: 대용량 출력 자동 축소
# Bash, Grep, Glob 출력이 50K자를 초과하면 중간을 생략하고 앞뒤만 유지

MAX_CHARS=50000
KEEP_HEAD=10000
KEEP_TAIL=10000

input=$(cat)
length=${#input}

if [ "$length" -gt "$MAX_CHARS" ]; then
  head_part="${input:0:$KEEP_HEAD}"
  tail_part="${input: -$KEEP_TAIL}"
  omitted=$(( length - KEEP_HEAD - KEEP_TAIL ))
  echo "$head_part"
  echo ""
  echo "... [${omitted}자 생략됨 — 전체 ${length}자 중 앞 ${KEEP_HEAD}자 + 뒤 ${KEEP_TAIL}자만 표시] ..."
  echo ""
  echo "$tail_part"
else
  echo "$input"
fi
