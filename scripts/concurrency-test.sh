#!/usr/bin/env bash
# 동시성 / 중복 이벤트 재현 스크립트
# 사용법: ./scripts/concurrency-test.sh [동시 요청 수(기본 20)]
set -uo pipefail

BASE=${BASE:-http://localhost:8080/api}
N=${1:-20}

SID=$(curl -s -X POST "$BASE/sessions" -H 'Content-Type: application/json' \
  -d '{"participantA":"alice","participantB":"bob"}' | sed -E 's/.*"sessionId":"([^"]+)".*/\1/')
echo "세션: $SID"
echo

echo "== 1) 서로 다른 이벤트 ${N}개를 동시에 전송 (기대: 전부 201) =="
seq 1 "$N" | xargs -P "$N" -I{} curl -s -o /dev/null -w "%{http_code}\n" \
  -X POST "$BASE/sessions/$SID/events" -H 'Content-Type: application/json' \
  -d '{"clientEventId":"c-{}","type":"MESSAGE_SENT","userId":"alice","payload":{"text":"m{}"}}' \
  | sort | uniq -c
echo

echo "== 2) 같은 이벤트(same-1)를 ${N}번 동시에 전송 (기대: 201 1개 + 200 나머지) =="
seq 1 "$N" | xargs -P "$N" -I{} curl -s -o /dev/null -w "%{http_code}\n" \
  -X POST "$BASE/sessions/$SID/events" -H 'Content-Type: application/json' \
  -d '{"clientEventId":"same-1","type":"MESSAGE_SENT","userId":"bob","payload":{"text":"hi"}}' \
  | sort | uniq -c
echo

echo "== 3) 저장된 seq 목록 (기대: 1부터 빈틈·중복 없이 $((N + 2))까지) =="
curl -s "$BASE/sessions/$SID/events?afterSeq=0&size=500" \
  | grep -o '"seq":[0-9]*' | cut -d: -f2 | tr '\n' ' '
echo
