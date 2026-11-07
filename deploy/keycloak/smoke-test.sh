#!/usr/bin/env bash
# End-to-end check of the API against a REAL Keycloak. Creates two temporary users (random passwords, never printed), temporarily
# enables the password grant on the client, runs the assertions, then restores the client and deletes the users.
# Needs: curl, python3. Usage: KC_ADMIN_PASSWORD=... KC_URL=http://localhost:18180 APP_URL=http://localhost:8080 ./smoke-test.sh
set -uo pipefail
KC_URL=${KC_URL:?KC_URL required}; APP_URL=${APP_URL:-http://localhost:8080}
REALM=${REALM:-dpi}; CLIENT=${CLIENT:-dpi-log-analysis}; ADMIN=${KC_ADMIN_USER:-admin}; : "${KC_ADMIN_PASSWORD:?KC_ADMIN_PASSWORD required}"
fail=0; client_uuid=""; SUFFIX=$RANDOM
j() { python3 -I -c "import sys,json; d=json.load(sys.stdin); print($1)"; }
pw() { python3 -I -c "import secrets; print(secrets.token_urlsafe(18))"; }
check() { # name expected actual
  if [ "$2" = "$3" ]; then echo "PASS  $1 ($3)"; else echo "FAIL  $1 expected $2 got $3"; fail=1; fi; }
code() { curl -s -o /dev/null -w "%{http_code}" -m 15 "$@"; }

admin_token() { curl -s -m 15 -d "client_id=admin-cli" -d "username=$ADMIN" --data-urlencode "password=$KC_ADMIN_PASSWORD" -d grant_type=password \
  "$KC_URL/realms/master/protocol/openid-connect/token" | j "d['access_token']"; }
AT=$(admin_token) || { echo "cannot obtain admin token"; exit 2; }
H=(-H "Authorization: Bearer $AT")
client_uuid=$(curl -s "${H[@]}" "$KC_URL/admin/realms/$REALM/clients?clientId=$CLIENT" | j "d[0]['id']")
set_grant() { curl -s "${H[@]}" "$KC_URL/admin/realms/$REALM/clients/$client_uuid" | python3 -I -c "import sys,json; d=json.load(sys.stdin); d['directAccessGrantsEnabled']=$1; print(json.dumps(d))" \
  | curl -s -o /dev/null -X PUT "${H[@]}" -H 'Content-Type: application/json' -d @- "$KC_URL/admin/realms/$REALM/clients/$client_uuid"; }
cleanup() { # fresh admin token (the first one expires after ~60 s); delete by prefix because make_user runs in a subshell
  AT=$(admin_token) && H=(-H "Authorization: Bearer $AT") || return
  set_grant False
  curl -s "${H[@]}" "$KC_URL/admin/realms/$REALM/users?username=smoke-&max=100" | python3 -I -c "import sys,json; [print(u['id']) for u in json.load(sys.stdin) if u['username'].startswith('smoke-') and u['username'].endswith('-$SUFFIX')]" \
    | while read -r id; do curl -s -o /dev/null -X DELETE "${H[@]}" "$KC_URL/admin/realms/$REALM/users/$id"; done
}
trap cleanup EXIT
set_grant True

make_user() { # name role -> prints password; appends id
  local name=$1 role=$2 p; p=$(pw)
  curl -s -o /dev/null -X POST "${H[@]}" -H 'Content-Type: application/json' "$KC_URL/admin/realms/$REALM/users" \
    -d "{\"username\":\"$name\",\"enabled\":true,\"emailVerified\":true,\"firstName\":\"Smoke\",\"lastName\":\"Test\",\"email\":\"$name@example.invalid\",\"credentials\":[{\"type\":\"password\",\"value\":\"$p\",\"temporary\":false}]}"
  local id; id=$(curl -s "${H[@]}" "$KC_URL/admin/realms/$REALM/users?username=$name&exact=true" | j "d[0]['id']")
  local role_json; role_json=$(curl -s "${H[@]}" "$KC_URL/admin/realms/$REALM/roles/$role")
  curl -s -o /dev/null -X POST "${H[@]}" -H 'Content-Type: application/json' -d "[$role_json]" "$KC_URL/admin/realms/$REALM/users/$id/role-mappings/realm"
  echo "$p"
}
user_token() { curl -s -m 15 -d "client_id=$CLIENT" -d "username=$1" --data-urlencode "password=$2" -d grant_type=password \
  "$KC_URL/realms/$REALM/protocol/openid-connect/token" | j "d['access_token']"; }

AP=$(make_user "smoke-analyst-$SUFFIX" ANALYST); RP=$(make_user "smoke-reviewer-$SUFFIX" REVIEWER)
AN=$(user_token "smoke-analyst-$SUFFIX" "$AP") || { echo "analyst token failed"; exit 2; }
RV=$(user_token "smoke-reviewer-$SUFFIX" "$RP") || { echo "reviewer token failed"; exit 2; }
echo "analyst token claims: $(echo "$AN" | cut -d. -f2 | python3 -I -c "import sys,base64,json; s=sys.stdin.read().strip(); d=json.loads(base64.urlsafe_b64decode(s+'='*(-len(s)%4))); print({k:d.get(k) for k in ('iss','aud','azp')}, d.get('realm_access'))")"

ID=00000000-0000-0000-0000-000000000001; JSON=(-H 'Content-Type: application/json')
check "no token -> 401"                 401 "$(code -X POST "$APP_URL/api/v1/analyses" "${JSON[@]}" -d '{"question":"x"}')"
check "garbage token -> 401"            401 "$(code -H 'Authorization: Bearer abc.def.ghi' "$APP_URL/api/v1/analyses/$ID")"
check "analyst can read (unknown id 404)" 404 "$(code -H "Authorization: Bearer $AN" "$APP_URL/api/v1/analyses/$ID")"
check "analyst cannot review -> 403"    403 "$(code -X POST -H "Authorization: Bearer $AN" "${JSON[@]}" -d '{"decision":"APPROVED"}' "$APP_URL/api/v1/analyses/$ID/review")"
check "analyst cannot ingest knowledge -> 403" 403 "$(code -X POST -H "Authorization: Bearer $AN" "${JSON[@]}" -d '{}' "$APP_URL/api/v1/knowledge")"
check "reviewer may review (unknown id 404)" 404 "$(code -X POST -H "Authorization: Bearer $RV" "${JSON[@]}" -d '{"decision":"APPROVED"}' "$APP_URL/api/v1/analyses/$ID/review")"
check "reviewer may validate knowledge (unknown 404)" 404 "$(code -X POST -H "Authorization: Bearer $RV" "$APP_URL/api/v1/knowledge/nope/versions/1/validate")"
check "token from another realm (master admin) -> 401" 401 "$(code -H "Authorization: Bearer $AT" "$APP_URL/api/v1/analyses/$ID")"
# Flip a character in the middle of the signature (the last base64url char of an RS256 signature carries only padding bits).
tamper_sig() { python3 -I -c "import sys; h,p,s=sys.argv[1].split('.'); i=len(s)//2; print('.'.join([h,p,s[:i]+('B' if s[i]=='A' else 'A')+s[i+1:]]))" "$1"; }
# Re-use a valid signature with a different payload (claims swapped to the reviewer's subject).
swap_payload() { python3 -I -c "import sys; a=sys.argv[1].split('.'); b=sys.argv[2].split('.'); print('.'.join([a[0],b[1],a[2]]))" "$1" "$2"; }
check "tampered signature -> 401"       401 "$(code -H "Authorization: Bearer $(tamper_sig "$AN")" "$APP_URL/api/v1/analyses/$ID")"
check "payload swapped (privilege escalation attempt) -> 401" 401 "$(code -H "Authorization: Bearer $(swap_payload "$AN" "$RV")" "$APP_URL/api/v1/analyses/$ID")"
[ $fail -eq 0 ] && echo "ALL PASSED" || echo "SOME CHECKS FAILED"
exit $fail
