#!/usr/bin/env bash
# Checks the Population Engine port (rathena-nostalre feat/population-engine) on host servers with their own
# database (out/pop/db) and logs (out/pop/logs), driven by the network crate's session probe on the test account:
#   off  engine switched off (the default): nothing loads, @populate refused, NPC count = before + arena.txt
#   on   population_engine_enable: 1: the 9 yml load, @populate 20 prontera makes 20 shells, nothing of theirs
#        reaches SQLite, no SQL errors, a whisper that only contains "pt" changes no party, the player's own save
#        survives a restart
#   s2   phone tuning: shells only where the player is, released after the grace window (20 s here), density
#        scales the crowd, wander skips maps nobody is on; Prontera's shell vendors: shops up, none by an NPC,
#        buying logs no shell row, the stalls turn over as their shifts end
#   s3   the app's world settings (ServerConf.battleFile keys) reach the server: shell cap in the boot line,
#        mob_count_rate in the monsters the boot loads
#   levels shells suit their map: on a low, a mid and a high map and a town, each shell's level falls in the range
#        its map and class allow, stats are ones that level could afford, gear is worn, no first class on Glast
#        Heim Church (monster level 77) or above; off keeps the 99s
# Needs: map/char/login servers built from the engine branch (POP_BIN, default out/pop/bin:
#   RATHENA_DIR=<rathena-nostalre> with build-server.sh, or any host build) and the client checkout (the probe).
#   party shells as party companions: a whisper or an invite joins, the party list shows it, it fights for the player
#        and follows across maps, killed it stays in the party and gets up next to the player on their next map, it
#        leaves on "bye", on logout and when the engine stops, it shares EXP like a member (Even Share within
#        party_share_level, Each Take beyond it), and only the player's own rows reach SQLite
#   formation three companions around a player who walks and stops in Prontera: at each stop every one stands on a
#        cell of its own, not the player's, at most 5 cells away (@where)
#   personality builds and temperaments (db/population_personality.yml): on four maps, following one shell for a
#        minute (shells only fight near a player), @populate levels every 15 s shows each Priest, Wizard, Knight and Hunter with a build of its class (battle priests
#        with maces, spear knights with spears), every shell a temperament with reaction and precision in its
#        ranges, some idle pauses taken (none by a reckless one), the file loads clean, and Wizards chose a basic attack at
#        most 15% of the time; then the same
#        tour with population_engine_scored_skills off, for comparison (POP_TOUR_MAPS="<maps>" tours fewer maps,
#        POP_SKIP_OFF=1 skips the second tour)
#   shops the app's AI shop settings (@populate vendors): Prontera alone / main towns / all towns, Shops per town 50%,
#        Shop prices 200% on a Red Potion, supplies only, loot only with cards (1-2 in dungeon shops only), and AI shops
#        off leaving no shop merchants behind
# Usage: scripts/test-population.sh [off|on|s2|s3|party|levels|formation|personality|shops|all]   (RATHENA_DIR=<server checkout>, POP_BIN=<bin dir>)
set -euo pipefail
source "$(dirname "$0")/common.sh"

what="${1:-all}"
bin="${POP_BIN:-$OUT/pop/bin}"
work="$OUT/pop"
db="$work/db/ragnarok.db"
logs="$work/logs"
conf="$RATHENA/conf/import/battle_conf.txt"
char=PopTester
npcs_before=24240 # map.log "Done loading '24240' NPCs" on nostalre 59dc4d8, before the port
mkdir -p "$logs"

sql() { sqlite3 -batch -noheader "$db" "$1"; }
fails=0
check() { # check <name> <ok?>
	if [ "$2" = 0 ]; then echo "ok    $1"; else echo "FAIL  $1"; fails=$((fails + 1)); fi
}
yes_no() { if eval "$1"; then echo 0; else echo 1; fi; } # yes_no '<shell condition>' → 0 when it holds

for exe in login-server char-server map-server; do
	[ -x "$bin/$exe" ] || { echo "no $bin/$exe (POP_BIN)" >&2; exit 1; }
done
server_imports "$RATHENA"

# the import conf the app writes at each start: kept, then restored
saved="$(mktemp)"
cp "$conf" "$saved"
pids=""
stop_servers() {
	local t=0 pid
	[ -n "$pids" ] || return 0
	kill $pids 2>/dev/null || true
	# a server spinning in its shutdown must fail the run, not stall it
	for pid in $pids; do
		while kill -0 "$pid" 2>/dev/null && [ "$t" -lt "${POP_STOP_WAIT:-60}" ]; do sleep 1; t=$((t + 1)); done
		if kill -0 "$pid" 2>/dev/null; then
			echo "FAIL  server $pid did not stop in ${POP_STOP_WAIT:-60} s; last map log line: $(tail -1 "$logs/map.log" | cut -c1-80)"
			fails=$((fails + 1))
			kill -9 "$pid" 2>/dev/null || true
		fi
	done
	wait $pids 2>/dev/null || true
	pids=""
}
trap 'stop_servers; cp "$saved" "$conf"; rm -f "$saved"' EXIT

start_servers() { # start_servers <enable 0|1> [extra battle conf line...]
	stop_servers
	{ cat "$saved"; echo "population_engine_enable: $1"; shift; printf '%s\n' "$@"; } > "$conf"
	rm -f "$logs"/{login,char,map}.log
	(
		cd "$RATHENA"
		export RA_SQLITE_DIR="$(dirname "$db")" PATH="$bin:$PATH"
		exec login-server > "$logs/login.log" 2>&1
	) & pids="$pids $!"
	sleep 1
	( cd "$RATHENA"; export RA_SQLITE_DIR="$(dirname "$db")" PATH="$bin:$PATH"; exec char-server > "$logs/char.log" 2>&1 ) &
	pids="$pids $!"
	sleep 1
	( cd "$RATHENA"; export RA_SQLITE_DIR="$(dirname "$db")" PATH="$bin:$PATH"; exec map-server > "$logs/map.log" 2>&1 ) &
	pids="$pids $!"
	timeout 240 bash -c "until grep -aq 'Map-server connected to char-server' '$logs/map.log' 2>/dev/null; do sleep 1; done" \
		|| { echo "map-server did not come up (see $logs/map.log)" >&2; exit 1; }
}

# probe <out file> <probe args...>: a session on tester as $char; packets with Playerchat in their name printed
probe() {
	local out=$1
	shift
	( cd "$CLIENT" && cargo run -q -p nostalre-network --example session_probe -- \
		--user tester --pass tester --dump Playerchat "$@" ) > "$out" 2>&1 || true
}
# the GM command made it: rAthena logs only the commands that succeed
logged() { [ "$(sql "SELECT COUNT(*) FROM atcommandlog WHERE command LIKE '$1%'")" -gt 0 ]; }
pop_lines() { grep -ac 'Population engine' "$logs/map.log" || true; }
npc_count() { grep -aoP "Done loading '\K\d+(?=' NPCs)" "$logs/map.log" | tail -1 || true; }
arena_npcs() { grep -acP '^\w+,\d+,\d+,\d+\t(script|duplicate\([^)]*\)|shop|warp)\t' "$RATHENA/npc/custom/population/arena.txt" 2>/dev/null || echo 0; }
tmp="$(mktemp -d)"
inter_saved="" # the party stage's copy of conf/import/inter_conf.txt while it changes party_share_level
trap 'stop_servers; cp "$saved" "$conf"; rm -f "$saved"; rm -rf "$tmp"
	[ -z "$inter_saved" ] || { cp "$inter_saved" "$RATHENA/conf/import/inter_conf.txt"; rm -f "$inter_saved"; }' EXIT
[ -f "$conf" ] || : > "$conf"

RA_DB="$db" "$ROOT/scripts/make-db.sh" --force --test-account > /dev/null

# --- off ---------------------------------------------------------------------
if [ "$what" = off ] || [ "$what" = all ]; then
	echo "== engine off"
	start_servers 0
	probe "$tmp/create.txt" --create "$char" --seconds 1
	probe "$tmp/off.txt" --chat "@populate 5" --seconds 5
	check "AC2 no engine lines in map log ($(pop_lines))" "$(yes_no '[ "$(pop_lines)" = 0 ]')"
	check "AC2 @populate refused" "$(yes_no '! logged @populate')"
	arena=$(arena_npcs)
	n=$(npc_count)
	check "AC3 NPCs $n = $npcs_before + $arena (arena.txt)" \
		"$(yes_no '[ "${n:-0}" = $((npcs_before + arena)) ] && [ "$arena" -gt 0 ]')"
	# test-custom-npcs.sh step 2 on this run's log: the custom NPCs and arena.txt load cleanly
	errors=$(grep -aiE 'error|warning' "$logs/map.log" | grep -aE 'npc/custom/(jobmaster|healer|warper|resetnpc|stylist|population/)' || true)
	check "AC3 custom NPCs + arena.txt load without errors" "$(yes_no '[ -z "$errors" ]')"
	[ -z "$errors" ] || echo "$errors" | head -5
fi

# --- on ----------------------------------------------------------------------
if [ "$what" = on ] || [ "$what" = all ]; then
	echo "== engine on"
	start_servers 1
	[ -n "$(sql "SELECT char_id FROM \`char\` WHERE name = '$char'")" ] || probe "$tmp/create.txt" --create "$char" --seconds 1
	id=$(sql "SELECT char_id FROM \`char\` WHERE name = '$char'")
	[ -n "$id" ] || { echo "FAIL  no character $char"; exit 1; }
	parties=$(sql "SELECT COUNT(*) FROM party")
	sql "UPDATE \`char\` SET zeny = 1000, party_id = 0 WHERE char_id = $id"

	load_errors=$(grep -aE 'Population engine: .*(missing or invalid|failed|not found)' "$logs/map.log" | grep -av 'itemdb' || true)
	missing_items=$(grep -ac 'Population engine: .* not found in itemdb' "$logs/map.log" || true)
	# the port's boot line (population_engine.cpp, nostalRE): proof the engine ran its load at all
	check "AC4 engine boot line 'Population engine: on'" "$(yes_no 'grep -aq "Population engine: on" "$logs/map.log"')"
	check "AC4 9 yml load without errors (missing items: $missing_items)" "$(yes_no '[ -z "$load_errors" ]')"
	[ -z "$load_errors" ] || echo "$load_errors" | head -5

	# @populate <n> needs the autosummon stopped first (it refuses while the engine runs)
	probe "$tmp/on.txt" --warp prontera --chat "@populate stop" --chat "@populate 20 prontera" --chat "@zeny 777" \
		--social wait:8 --social "say:@populate stats" --social "say:@who" --social wait:2 --seconds 3
	active=$(grep -aoP 'Active / created / errors : \K\d+' "$tmp/on.txt" | tail -1 || true)
	check "AC5 @populate stats: ${active:-?} active, want 20" "$(yes_no '[ "${active:-0}" = 20 ]')"

	# a shell's name from @who: any listed name but ours
	shell=$(grep -aoP 'message: "Name: \K[^|()"]+?(?= *(\(|\||"))' "$tmp/on.txt" | grep -vxF "$char" | head -1 || true)
	if [ -n "$shell" ]; then
		# "empty" holds the keyword "pt" but is not a request (the "party" stage below covers real ones)
		probe "$tmp/party.txt" --social "whisper:$shell:empty" --social wait:5 --seconds 2
		check "AC8 '$shell' took the whisper and did not join" \
			"$(yes_no 'grep -aq "social: whisper:" "$tmp/party.txt" && ! grep -aq "lead the way" "$tmp/party.txt"')"
		check "AC8 no party row (parties $parties -> $(sql "SELECT COUNT(*) FROM party"))" \
			"$(yes_no '[ "$(sql "SELECT COUNT(*) FROM party")" = "$parties" ] && [ "$(sql "SELECT party_id FROM \`char\` WHERE char_id = $id")" = 0 ]')"
	else
		check "AC8 a shell listed by @who to whisper" 1
	fi

	echo "   5 min with the shells on ..."
	probe "$tmp/stay.txt" --warp prontera --seconds 300
	# sql_sqlite.cpp ShowSQL: "DB error - ..." (connect lines carry the [SQL] tag too)
	check "AC7 no SQL errors in map log" "$(yes_no '! grep -aqE "\[SQL\]: (DB error|Failed)" "$logs/map.log"')"

	start_servers 1
	fake=0
	for t in "\`char\`:char_id" "login:account_id" "inventory:char_id" "vendings:char_id" "party:leader_char"; do
		n=$(sql "SELECT COUNT(*) FROM ${t%%:*} WHERE ${t#*:} >= 95000000")
		[ "$n" = 0 ] || { echo "   ${t%%:*}: $n rows of shells"; fake=1; }
	done
	check "AC6 no shell rows in char, login, inventory, vending, party" $fake

	zeny=$(sql "SELECT zeny FROM \`char\` WHERE char_id = $id")
	map=$(sql "SELECT last_map FROM \`char\` WHERE char_id = $id")
	check "AC9 player save after restart: zeny $zeny (want 1777), map $map (want prontera)" \
		"$(yes_no '[ "$zeny" = 1777 ] && [ "$map" = prontera ]')"
fi

# --- s2: phone tuning --------------------------------------------------------
# found <file> <map> <n>: the player count of the n-th "@whomap <map>" reply (shells count as players)
found() {
	grep -aoP "message: \"\K(No player|\d+ players?) found in map '$2'" "$1" | sed -n "${3}p" \
		| sed -E 's/^No player.*/0/; s/^([0-9]+).*/\1/'
}
if [ "$what" = s2 ] || [ "$what" = all ]; then
	echo "== s2 phone tuning"
	[ -n "$(sql "SELECT char_id FROM \`char\` WHERE name = '$char'")" ] || {
		start_servers 0; probe "$tmp/create.txt" --create "$char" --seconds 1; }
	id=$(sql "SELECT char_id FROM \`char\` WHERE name = '$char'")
	grace="population_engine_demand_grace_ms: 20000"
	full_density="population_engine_density_pct: 100" # the counts below are relative to 100 (nostalRE ships 200)
	# shops do not follow density (population_engine_vendor_pct): out of the crowd counts below
	no_shops="population_engine_vending_enable: 0"
	at_prontera() { sql "UPDATE \`char\` SET last_map = 'prontera', last_x = 156, last_y = 191, zeny = 1000000 WHERE char_id = $id"; }

	at_prontera
	start_servers 1 "$grace" "$full_density" "$no_shops"
	sleep 30 # nobody on: demand spawn makes no shell
	probe "$tmp/s2.txt" --social wait:10 --social "say:@who" --social "say:@whomap prontera" \
		--social "say:@warp prt_fild08" --social wait:3 --social "say:@whomap prontera" \
		--social "say:@populate timingreset" --social wait:4 --social "say:@populate timing" \
		--social "say:@whomap prt_fild08" --social wait:22 \
		--social "say:@whomap prontera" --social "say:@whomap prt_fild08" --seconds 1
	total=$(grep -aoP 'message: "\K\d+(?= players? found\.)' "$tmp/s2.txt" | head -1 || true)
	p1=$(found "$tmp/s2.txt" prontera 1); p2=$(found "$tmp/s2.txt" prontera 2); p3=$(found "$tmp/s2.txt" prontera 3)
	f1=$(found "$tmp/s2.txt" prt_fild08 1); f2=$(found "$tmp/s2.txt" prt_fild08 2)
	examined=$(grep -aoP 'Avg shells examined/walked *: \K[0-9.]+' "$tmp/s2.txt" | head -1 || true)
	# @who comes first, @whomap a moment later: a shell may spawn, die or be released in between (+-2)
	check "AC1 all shells on the player's map: @who ${total:-?} ~ prontera ${p1:-?} (+-2), more than the player" \
		"$(yes_no '[ -n "$total" ] && [ -n "$p1" ] && [ "$total" -le $((p1 + 2)) ] && [ "$total" -gt 1 ]')"
	check "AC3 prontera keeps its shells 3 s after leaving (grace): ${p2:-?}" "$(yes_no '[ "${p2:-0}" -gt 0 ]')"
	check "AC6 wander examines only the player's map: avg ${examined:-?} <= prt_fild08 shells $(( ${f1:-1} - 1 ))" \
		"$(yes_no '[ -n "$examined" ] && awk "BEGIN { exit !($examined <= ${f1:-1} - 1) }"')"
	check "AC2 after the grace window: prontera ${p3:-?} = 0, prt_fild08 ${f2:-?} > 1" \
		"$(yes_no '[ "${p3:-x}" = 0 ] && [ "${f2:-0}" -gt 1 ]')"

	at_prontera
	start_servers 1 "$grace" "population_engine_density_pct: 50" "$no_shops"
	probe "$tmp/s2-half.txt" --social wait:10 --social "say:@whomap prontera" --seconds 1
	h=$(found "$tmp/s2-half.txt" prontera 1)
	# shells only: the player is one of the counted
	full=$(( ${p1:-1} - 1 )) half=$(( ${h:-1} - 1 ))
	check "AC4 density 50: prontera $half shells vs $full at 100 (want 40-60%, +-1)" \
		"$(yes_no '[ "$full" -gt 0 ] && [ $((half * 100)) -ge $(( (full * 40) - 100 )) ] && [ $((half * 100)) -le $(( (full * 60) + 100 )) ]')"

	# shell vendors in Prontera (db/population_vendors.yml): a shop in sight and one item bought, under the shipped
	# shifts (minutes) so it stays open between the sessions; default grace, so the vendors outlive the gaps too
	at_prontera
	start_servers 1
	probe "$tmp/s2-board.txt" --dump StoreEntry --social wait:12 --seconds 1
	vendor=$(grep -aoP 'VendingBoardShown \{ aid: \K\d+' "$tmp/s2-board.txt" | head -1 || true)
	if [ -n "$vendor" ]; then
		before=$(sql "SELECT COUNT(*) FROM picklog")
		# @refresh re-sends the units in sight: the shop sign sent at login comes before the steps listen
		probe "$tmp/s2-buy.txt" --social "say:@refresh" --social "vend-buy:$vendor" --seconds 1
		bought=$(grep -ac 'social: vend-buy.*bought item' "$tmp/s2-buy.txt" || true)
		shell_rows=$(sql "SELECT COUNT(*) FROM picklog WHERE char_id >= 95000000")
		check "AC5 bought from shell vendor $vendor ($bought), picklog shell rows $shell_rows (rows $before -> $(sql "SELECT COUNT(*) FROM picklog"))" \
			"$(yes_no '[ "$bought" -gt 0 ] && [ "$shell_rows" = 0 ]')"
	else
		check "AC5 a shell vendor's shop sign in sight in prontera" 1
	fi

	# all of Prontera's shops up, none by an NPC, and with short shifts the stalls turn over within the stage
	start_servers 1 "population_engine_vendor_shift_min_ms: 15000" "population_engine_vendor_shift_max_ms: 20000"
	probe "$tmp/s2-shops.txt" --social wait:12 --social "say:@populate vendors" --seconds 1
	shops=$(grep -aoP 'message: "vendors \K\d+(?= of)' "$tmp/s2-shops.txt" | tail -1 || true)
	target=$(grep -aoP 'message: "vendors \d+ of \K\d+' "$tmp/s2-shops.txt" | tail -1 || true)
	by_npc=$(grep -ac 'message: "vendor aid .* clear no' "$tmp/s2-shops.txt" || true)
	first=$(grep -aoP 'message: "vendor aid \K\d+' "$tmp/s2-shops.txt" | sort -u || true)
	# a few slots may find no free cell this tick (spacing, NPCs); the next tick fills them
	check "AC7 prontera has its shops: ${shops:-?} of ${target:-?} (want 90%+)" \
		"$(yes_no '[ -n "$shops" ] && [ -n "$target" ] && [ "$target" -gt 0 ] && [ $((shops * 10)) -ge $((target * 9)) ]')"
	check "AC8 no shop within 3 cells of an NPC or warp ($by_npc)" "$(yes_no '[ -n "$shops" ] && [ "$by_npc" = 0 ]')"
	# every first-wave shift (15-20 s) is over by now: those stalls closed and new ones opened elsewhere
	sleep 30
	probe "$tmp/s2-later.txt" --social wait:3 --social "say:@populate vendors" --seconds 1
	later=$(grep -aoP 'message: "vendor aid \K\d+' "$tmp/s2-later.txt" | sort -u || true)
	kept=$(comm -12 <(echo "$first") <(echo "$later") | grep -c . || true)
	check "AC9 shops turn over: $(echo "$first" | grep -c .) then $(echo "$later" | grep -c .) shops, $kept kept" \
		"$(yes_no '[ -n "$first" ] && [ -n "$later" ] && [ "$kept" = 0 ]')"
	grep -aq 'could not open its shop' "$logs/map.log" && echo "note  map.log: $(grep -ac 'could not open its shop' "$logs/map.log") vendors could not open a shop" || true
	sql "UPDATE \`char\` SET last_map = 'prontera', last_x = 156, last_y = 191 WHERE char_id = $id"
fi

if [ "$what" = s3 ] || [ "$what" = all ]; then
	echo "== s3 app world settings"
	# the lines ServerConf.battleFile writes (ServerConfTest checks the app side)
	start_servers 1 "population_engine_max_count: 50" "population_engine_density_pct: 50"
	check "AC5 max 50 in the boot line" "$(yes_no 'grep -aq "Population engine: on (max 50 shells)" "$logs/map.log"')"

	# the boot's monster count: npc.cpp scales each spawn line of more than one by mob_count_rate
	cached() { grep -aoP "'\K\d+(?=' Mobs Cached)" "$logs/map.log" | tail -1 || true; }
	start_servers 0 "mob_count_rate: 100"; m1=$(cached)
	start_servers 0 "mob_count_rate: 200"; m2=$(cached)
	check "AC5 mob_count_rate 200: ${m2:-?} monsters vs ${m1:-?} at 100 (want 1.9-2x)" \
		"$(yes_no '[ -n "$m1" ] && [ -n "$m2" ] && [ $((m2 * 100)) -ge $((m1 * 190)) ] && [ $((m2 * 100)) -le $((m1 * 200)) ]')"
fi

# --- party: shells as companions ------------------------------------------------
if [ "$what" = party ] || [ "$what" = all ]; then
	echo "== party companions"
	[ -n "$(sql "SELECT char_id FROM \`char\` WHERE name = '$char'")" ] || {
		start_servers 0; probe "$tmp/create.txt" --create "$char" --seconds 1; }
	id=$(sql "SELECT char_id FROM \`char\` WHERE name = '$char'")
	sql "DELETE FROM party WHERE leader_char = $id"
	sql "UPDATE \`char\` SET party_id = 0, last_map = 'prt_fild08', last_x = 170, last_y = 375, base_level = 1, base_exp = 0, job_exp = 0 WHERE char_id = $id"
	level=$(sql "SELECT base_level FROM \`char\` WHERE char_id = $id")
	# party_share_level 0 for this session: any companion is out of Even Share range, whatever level map levels
	# give it, so its kills stay its own (AC7). The next session raises it. Both read conf/import/inter_conf.txt.
	inter_import="$RATHENA/conf/import/inter_conf.txt"
	inter_saved="$(mktemp)"
	cp "$inter_import" "$inter_saved"
	echo "party_share_level: 0" >> "$inter_import"
	start_servers 1

	# two field shells from @who; they outlive the gap between sessions (the demand grace, 3 min)
	probe "$tmp/p-who.txt" --social wait:10 --social "say:@who" --seconds 1
	names=$(grep -aoP 'message: "Name: \K[^|()"]+?(?= *(\(|\||"))' "$tmp/p-who.txt" | grep -vxF "$char" || true)
	shell=$(echo "$names" | sed -n 1p)
	shell2=$(echo "$names" | sed -n 2p)
	check "AC1 two shells to party with: '${shell:-?}', '${shell2:-?}'" "$(yes_no '[ -n "$shell" ] && [ -n "$shell2" ]')"

	if [ -n "$shell" ] && [ -n "$shell2" ]; then
		# whisper with no party: it makes one and joins. Then Porings next to a player who never attacks, the companion
		# killed, a map change, "bye", an invite by name, and the engine stopping under a companion.
		probe "$tmp/p-main.txt" --social "whisper:$shell:party" --social wait:3 \
			--social "party-option:1,1,1" --social wait:1 --social "say:@monster poring 12" --social wait:45 \
			--social "say:#kill \"$shell\"" --social wait:4 \
			--social "say:@warp prt_fild07" --social wait:4 --social "say:@whomap prt_fild07" \
			--social "whisper:$shell:bye" --social wait:2 \
			--social "say:@warp prt_fild08" --social wait:4 \
			--social "invite:$shell2" --social wait:3 \
			--social "say:@populate stop" --social wait:3 --seconds 1

		check "AC2 whisper answered: joined" "$(yes_no 'grep -aq "lead the way" "$tmp/p-main.txt"')"
		check "AC2 party list shows '$shell' next to the player" \
			"$(yes_no 'grep -aF "PartyMemberList" "$tmp/p-main.txt" | grep -aF "$shell" | grep -aqF "$char"')"
		# between the kill and the warp: dead, but still a member
		killed=$(awk '/say:#kill/{on=1} /say:@warp prt_fild07/{on=0} on' "$tmp/p-main.txt")
		check "AC3 killed, it stays in the party" \
			"$(yes_no 'grep -aqF "say:#kill" "$tmp/p-main.txt" && ! echo "$killed" | grep -aq "PartyMemberRemoved"')"
		check "AC3 it got up and moved with the player: party list has it on prt_fild07" \
			"$(yes_no 'grep -aF "PartyMemberList" "$tmp/p-main.txt" | grep -aF "$shell" | grep -aq "prt_fild07"')"
		check "AC4 'bye' takes it out of the list" "$(yes_no 'grep -aq "PartyMemberRemoved" "$tmp/p-main.txt"')"
		check "AC5 invite by name: accepted" "$(yes_no 'grep -aF "PartyInviteResult" "$tmp/p-main.txt" | grep -aF "$shell2" | grep -aq "answer: 2"')"
		removed=$(grep -ac "PartyMemberRemoved" "$tmp/p-main.txt" || true)
		check "AC6 the engine stopping takes the last one out too ($removed removals, want 2)" "$(yes_no '[ "$removed" -ge 2 ]')"
		check "AC6 map server still up after the engine stopped" "$(yes_no 'kill -0 $(echo $pids | cut -d" " -f3) 2>/dev/null')"

		# the player only stood there: beyond party_share_level Even Share is Each Take, so the companion's kills gave
		# them nothing
		probe "$tmp/p-after.txt" --seconds 2
		now_exp=$(sql "SELECT base_exp FROM \`char\` WHERE char_id = $id")
		now_level=$(sql "SELECT base_level FROM \`char\` WHERE char_id = $id")
		check "AC7 level gap: a player who never attacked gained nothing from its kills: exp $now_exp, level $level -> $now_level" \
			"$(yes_no '[ "${now_exp:-0}" = 0 ] && [ "${now_level:-0}" = "$level" ]')"
	fi

	# within party_share_level (raised for this run) Even Share pools the companion's EXP with the player's
	cp "$inter_saved" "$inter_import"
	echo "party_share_level: 255" >> "$inter_import"
	sql "DELETE FROM party WHERE leader_char = $id"
	sql "UPDATE \`char\` SET party_id = 0, last_map = 'prt_fild08', last_x = 170, last_y = 375, base_level = 1, base_exp = 0, job_exp = 0 WHERE char_id = $id"
	start_servers 1
	probe "$tmp/p-share-who.txt" --social wait:10 --social "say:@who" --seconds 1
	shell3=$(grep -aoP 'message: "Name: \K[^|()"]+?(?= *(\(|\||"))' "$tmp/p-share-who.txt" | grep -vxF "$char" | head -1 || true)
	if [ -n "$shell3" ]; then
		probe "$tmp/p-share.txt" --social "whisper:$shell3:party" --social wait:3 \
			--social "say:@monster poring 12" --social wait:45 --seconds 1
		probe "$tmp/p-share-after.txt" --seconds 2
	fi
	cp "$inter_saved" "$inter_import"
	rm -f "$inter_saved"
	inter_saved=""
	share_exp=$(sql "SELECT base_exp FROM \`char\` WHERE char_id = $id")
	share_level=$(sql "SELECT base_level FROM \`char\` WHERE char_id = $id")
	check "AC7 Even Share in range: '${shell3:-?}' joined a party made with Even Share" \
		"$(yes_no '[ -n "$shell3" ] && grep -aq "lead the way" "$tmp/p-share.txt" && grep -aF "PartyConfigChanged" "$tmp/p-share.txt" | grep -aq "exp_option: 1"')"
	check "AC7 Even Share in range: the player gained from its kills: exp $share_exp, level $level -> $share_level" \
		"$(yes_no '[ "${share_exp:-0}" -gt 0 ] || [ "${share_level:-0}" -gt "$level" ]')"

	party_errors=$(grep -aE '\[Error\].*[Pp]arty|party_[a-z_]+:' "$logs/map.log" "$logs/char.log" || true)
	check "AC8 no party errors in the server logs" "$(yes_no '[ -z "$party_errors" ]')"
	[ -z "$party_errors" ] || echo "$party_errors" | head -5
	check "AC8 no SQL errors in the logs" "$(yes_no '! grep -aqE "\[SQL\]: (DB error|Failed)" "$logs/map.log" "$logs/char.log"')"

	# the real test: what the save holds, and what a restart gives back
	start_servers 1
	pid=$(sql "SELECT party_id FROM \`char\` WHERE char_id = $id")
	check "AC9 the player's party survives: party_id ${pid:-?}, leader is the player" \
		"$(yes_no '[ "${pid:-0}" -gt 0 ] && [ "$(sql "SELECT leader_char FROM party WHERE party_id = $pid")" = "$id" ]')"
	members=$(sql "SELECT COUNT(*) FROM \`char\` WHERE party_id = ${pid:-0}")
	check "AC9 $members party member row (the player alone)" "$(yes_no '[ "$members" = 1 ]')"
	fake=0
	for t in "\`char\`:char_id" "party:leader_char" "inventory:char_id" "cart_inventory:char_id" "picklog:char_id" "zenylog:char_id"; do
		n=$(sql "SELECT COUNT(*) FROM ${t%%:*} WHERE ${t#*:} >= 95000000")
		[ "$n" = 0 ] || { echo "   ${t%%:*}: $n rows of shells"; fake=1; }
	done
	check "AC9 no shell rows in char, party, inventory, cart, picklog, zenylog" $fake
	check "AC9 no fake party id saved" "$(yes_no '[ "$(sql "SELECT COUNT(*) FROM \`char\` WHERE party_id >= 1879048192")" = 0 ]')"
	# @who names each character's party; the party list itself comes at login, before the probe prints events
	probe "$tmp/p-restart.txt" --social wait:6 --social "say:@who" --social wait:1 --seconds 1
	in_party=$(grep -aoP "message: \"Name: \K[^|(]+(?=.*Party: '$char Party')" "$tmp/p-restart.txt" | sort -u | tr '\n' ',' || true)
	check "AC9 after a restart the party holds the player only: ${in_party:-nobody}" "$(yes_no '[ "$in_party" = "$char," ]')"
fi

# --- levels: shell levels suit the map -------------------------------------------
# levels_check <probe output> <map> <monster level, 0 = town>: every shell of the map in `@populate levels`
levels_check() {
	python3 -c "$levels_py" "$@" "$RATHENA/db/re/statpoint.yml" >&2
}
levels_py=$(cat <<'PY'
import re, sys
path, name, map_level, statpoints = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]
FIRST = {"Swordman", "Swordsman", "Mage", "Magician", "Archer", "Acolyte", "Merchant", "Thief", "Taekwon"}
# transcendent first classes are first classes too (the shop merchants in towns are High Merchants among others)
FIRST |= {"High " + j for j in FIRST}
NO_NEXT_CLASS = {"Gunslinger", "Ninja"}
FIST = {"Taekwon", "Star Gladiator", "Soul Linker"} # kick and punch: their gear sets hold no weapon
def floor_cap(job): # population_class_level_range
	return (1, 15) if job == "Novice" else (10, 70) if job in FIRST else (10, 99) if job in NO_NEXT_CLASS \
		else (60, 99) if job == "Assassin Cross" else (45, 99)
def window(job): # population_roll_map_levels' range for this map and class
	floor, cap = floor_cap(job)
	spread = max(3, map_level // 10) if map_level else 12
	lo, hi = (map_level - spread, map_level + spread) if map_level else (30, 85)
	hi = min(max(hi, floor + spread), cap)
	return max(min(lo, hi - spread), floor), hi
table = dict(re.findall(r'Level: (\d+)\s+Points: (\d+)', open(statpoints).read()))
def budget(level):
	return int(table[str(level)])
def cost(stat):
	return sum((i - 1) // 10 + 2 for i in range(1, stat))
text = open(path, errors="replace").read()
# this map's part of the probe's output: from its @warp up to the next one
part = text.split("say:@warp " + name)[-1].split("say:@warp ")[0]
rows = re.findall(r'shell job ([^"(]+?)(?:\(from ([^)]+)\))? lv (\d+)/(\d+) stats (\d+)/(\d+)/(\d+)/(\d+)/(\d+)/(\d+) weapon (\d+) armor (\d+) shoes (\d+)', part)
bad, jobs, levels, ratios, no_weapon, no_armor, stepped = [], {}, [], [], 0, 0, 0
for job, spawn_job, base, jl, *rest in rows:
	if spawn_job:
		stepped += 1
	# population_fit_job_to_map: on a field, no class whose floor is more than 5 above the band
	if map_level and floor_cap(job)[0] > map_level + max(3, map_level // 10) + 5:
		bad.append(f"{job} (from {spawn_job or job}) did not step down: floor {floor_cap(job)[0]}")
	# population_promote_job_for_map: from monster level 60 a first class moves up to a second class
	if map_level >= 60 and job in FIRST:
		bad.append(f"{job} {base} on a level {map_level} map did not step up")
	base, jl = int(base), int(jl)
	stats = [int(x) for x in rest[:6]]
	weapon, armor = int(rest[6]), int(rest[7])
	lo, hi = window(job)
	levels.append(base)
	jobs[job] = jobs.get(job, 0) + 1
	ratios.append(sum(cost(s) for s in stats) / budget(base))
	if not lo <= base <= hi:
		bad.append(f"{job} {base} outside {lo}-{hi}")
	if not 1 <= jl <= 70:
		bad.append(f"{job} job level {jl}")
	if max(stats) > 99:
		bad.append(f"{job} stats {stats}")
	if ratios[-1] > 1:
		bad.append(f"{job} {base} stats {stats} cost more than the level has")
	if armor == 0:
		no_armor += 1
	if weapon == 0 and job not in FIST:
		no_weapon += 1
		bad.append(f"{job} {base} has no weapon")
if not rows:
	bad.append("no shells listed")
print(f"      {name}: {len(rows)} shells, levels {min(levels, default=0)}-{max(levels, default=0)}, "
	f"stat points vs budget {min(ratios, default=0):.2f}-{max(ratios, default=0):.2f}, "
	f"no weapon {no_weapon}, no armor {no_armor}, stepped down {stepped}, jobs {sorted(jobs)}")
for line in sorted(set(bad))[:15]:
	print("      ! " + line)
sys.exit(1 if bad else 0)
PY
)
if [ "$what" = levels ] || [ "$what" = all ]; then
	echo "== map levels"
	[ -n "$(sql "SELECT char_id FROM \`char\` WHERE name = '$char'")" ] || {
		start_servers 0; probe "$tmp/create.txt" --create "$char" --seconds 1; }
	grace="population_engine_demand_grace_ms: 20000"
	full_density="population_engine_density_pct: 100"
	# low (Knights only, then first classes too), mid, high, a town, and a field with no monsters at all
	visit=()
	for m in prt_fild08 gef_fild00 gef_fild10 tur_dun04 gl_church prontera pay_fild05; do
		visit+=(--social "say:@warp $m" --social wait:14 --social "say:@populate levels" --social wait:1)
	done

	start_servers 1 "$grace" "$full_density"
	probe "$tmp/levels.txt" "${visit[@]}" --seconds 1
	map_level() { grep -aoP "map '$1' has monster level \K\d+" "$logs/map.log" | head -1 || true; }
	low=$(map_level prt_fild08); low2=$(map_level gef_fild00); mid=$(map_level gef_fild10); high=$(map_level tur_dun04)
	check "AC1 monster levels from moblist: prt_fild08 ${low:-?} < 10, gef_fild10 ${mid:-?} in 30-60, tur_dun04 ${high:-?} >= 85" \
		"$(yes_no '[ "${low:-99}" -lt 10 ] && [ "${mid:-0}" -ge 30 ] && [ "${mid:-0}" -le 60 ] && [ "${high:-0}" -ge 85 ]')"
	check "AC1 prt_fild08 shells fit" "$(yes_no 'levels_check "$tmp/levels.txt" prt_fild08 "${low:-0}"')"
	check "AC1 gef_fild00 shells fit" "$(yes_no 'levels_check "$tmp/levels.txt" gef_fild00 "${low2:-0}"')"
	check "AC1 gef_fild10 shells fit" "$(yes_no 'levels_check "$tmp/levels.txt" gef_fild10 "${mid:-0}"')"
	check "AC1 tur_dun04 shells fit" "$(yes_no 'levels_check "$tmp/levels.txt" tur_dun04 "${high:-0}"')"
	gl=$(map_level gl_church)
	check "AC5 gl_church (monster level ${gl:-?}) shells fit, no first class" "$(yes_no 'levels_check "$tmp/levels.txt" gl_church "${gl:-0}"')"
	check "AC2 prontera (town) shells fit" "$(yes_no 'levels_check "$tmp/levels.txt" prontera 0')"
	check "AC2 pay_fild05 (no monsters) shells fit" "$(yes_no 'levels_check "$tmp/levels.txt" pay_fild05 0')"

	start_servers 1 "$grace" "$full_density" "population_engine_map_levels: 0"
	probe "$tmp/levels-off.txt" --social "say:@warp gef_fild10" --social wait:14 --social "say:@populate levels" --seconds 1
	top=$(grep -aoP 'shell job .* lv \K99(?=/70 )' "$tmp/levels-off.txt" | wc -l || true)
	check "AC4 switch off: the old 99/70 shells on gef_fild10 ($top), no map level computed" \
		"$(yes_no '[ "$top" -gt 0 ] && ! grep -aq "has monster level" "$logs/map.log"')"
fi

# --- formation: companions spread around their idle owner --------------------------
# formation_check <probe output> <marker>: the @where lines after <marker> (the owner's first): each companion on its
# own cell, not the owner's, at most 5 cells away
formation_py=$(cat <<'PY'
import re, sys
path, marker, owner = sys.argv[1], sys.argv[2], sys.argv[3]
part = open(path, errors="replace").read().split(marker)[-1].split("social: say:@where " + owner)
where = lambda t: re.findall(r'message: "(\S[^"]*?) (\w+) (\d+) (\d+)"', t)
rows = where(part[1].split("social: walk")[0]) if len(part) > 1 else []
if not rows:
	print("      no @where answers"); sys.exit(1)
(_, m, ox, oy), mates = rows[0], rows[1:]
ox, oy = int(ox), int(oy)
cells = [(int(x), int(y)) for _, _, x, y in mates]
print(f"      owner {ox},{oy}; companions " + ", ".join(f"{n} {x},{y} ({max(abs(int(x) - ox), abs(int(y) - oy))})" for n, _, x, y in mates))
bad = len(mates) < 3 or len(set(cells)) != len(cells) or any(c == (ox, oy) or max(abs(c[0] - ox), abs(c[1] - oy)) > 5 for c in cells)
sys.exit(1 if bad else 0)
PY
)
formation_check() { python3 -c "$formation_py" "$@" >&2; }
if [ "$what" = formation ] || [ "$what" = all ]; then
	echo "== companion formation"
	[ -n "$(sql "SELECT char_id FROM \`char\` WHERE name = '$char'")" ] || {
		start_servers 0; probe "$tmp/create.txt" --create "$char" --seconds 1; }
	id=$(sql "SELECT char_id FROM \`char\` WHERE name = '$char'")
	sql "DELETE FROM party WHERE leader_char = $id"
	sql "UPDATE \`char\` SET party_id = 0, last_map = 'prt_fild08', last_x = 170, last_y = 375 WHERE char_id = $id"
	start_servers 1
	probe "$tmp/f-who.txt" --social wait:10 --social "say:@who" --seconds 1
	names=$(grep -aoP 'message: "Name: \K[^|()"]+?(?= *(\(|\||"))' "$tmp/f-who.txt" | grep -vxF "$char" | head -3 || true)
	s1=$(echo "$names" | sed -n 1p); s2=$(echo "$names" | sed -n 2p); s3=$(echo "$names" | sed -n 3p)
	check "AC1 three shells to party with: '$s1', '$s2', '$s3'" "$(yes_no '[ -n "$s3" ]')"
	if [ -n "$s3" ]; then
		where_args=(--social "say:@where $char" --social "say:@where $s1" --social "say:@where $s2" --social "say:@where $s3")
		probe "$tmp/f-main.txt" --social "whisper:$s1:party" --social wait:3 --social "invite:$s2" --social wait:2 \
			--social "invite:$s3" --social wait:2 --social "say:@warp prontera 156 191" --social wait:10 \
			--social "walk:156,180" --social wait:8 \
			"${where_args[@]}" --social wait:1 --social "walk:150,170" --social wait:8 \
			"${where_args[@]}" --social wait:1 --social "walk:156,160" --social wait:8 "${where_args[@]}" --seconds 2
		check "AC1 all three joined" "$(yes_no '[ "$(grep -ac "lead the way" "$tmp/f-main.txt")" -ge 3 ]')"
		for stop in 156,180 150,170 156,160; do
			check "AC2 owner stopped at $stop: companions on their own cells around them" \
				"$(yes_no 'formation_check "$tmp/f-main.txt" "social: walk:$stop" "$char"')"
		done
	fi
fi

# --- personality: builds × temperaments, the scored picker -----------------------
personality_py=$(cat <<'PY'
import re, sys
path, mode, items_path, pers_path = sys.argv[1:5]
full = open(path, errors="replace").read()
# a snapshot every 15 s per map; each map's last one counts
text = "".join(part.split("social: say:@populate levels")[-1] for part in full.split("social: say:@warp ")[1:])
rows = re.findall(r'shell job ([^"(]+?)(?:\(from [^)]+\))? lv \d+/\d+ stats [\d/]+ weapon (\d+) armor \d+ shoes \d+ hp \d+/\d+ \w+ (\S+)/(\S+) react (\d+) prec (\d+) acts (\d+)/(\d+) pauses (\d+)', text)
ids = {}
for m in re.finditer(r'- Id: (\d+)\n\s+AegisName: (\S+)', open(items_path).read()):
    ids[m.group(2)] = int(m.group(1))
pers = open(pers_path).read()
temps = {}
for m in re.finditer(r'- Temperament: (\w+)\n(?:\s+\w+:.*\n)*?\s+ReactionMs: \[(\d+), (\d+)\]\n\s+Precision: \[(\d+), (\d+)\]', pers):
    temps[m.group(1)] = tuple(int(x) for x in m.groups()[1:])
FAMILY = {"Priest": "priest", "High Priest": "priest", "Wizard": "wizard", "High Wizard": "wizard",
          "Knight": "knight", "Lord Knight": "knight", "Hunter": "hunter", "Sniper": "hunter"}
MACES = {ids[n] for n in ("Mace", "Morning_Star", "Chain", "Stunner", "Sword_Mace")}
SPEARS = {ids[n] for n in ("Pike", "Guisarme", "Glaive", "Partizan", "Trident")}
bad, builds, acts = [], {}, {}
paused = 0
for job, weapon, build, temp, react, prec, skill, melee, pauses in rows:
    weapon, react, prec, skill, melee, pauses = int(weapon), int(react), int(prec), int(skill), int(melee), int(pauses)
    paused += pauses
    if pauses and temp == "reckless":
        bad.append(f"{job} reckless (no Pauses) took {pauses} pauses")
    fam = FAMILY.get(job)
    builds[f"{job}:{build}"] = builds.get(f"{job}:{build}", 0) + 1
    if fam and not build.startswith(fam + "_"):
        bad.append(f"{job} has build {build}")
    if not fam and build != "-":
        bad.append(f"{job} has build {build}, its job has none")
    if mode == "on":
        if temp not in temps:
            bad.append(f"{job} temperament {temp}")
        else:
            rlo, rhi, plo, phi = temps[temp]
            if not (rlo <= react <= rhi and plo <= prec <= phi):
                bad.append(f"{job} {temp} react {react} prec {prec} outside {temps[temp]}")
    if build == "priest_battle" and weapon and weapon not in MACES:
        bad.append(f"priest_battle with weapon {weapon}")
    if build == "knight_spear" and weapon and weapon not in SPEARS:
        bad.append(f"knight_spear with weapon {weapon}")
    key = fam or "other"
    a = acts.setdefault(key, [0, 0])
    a[0] += skill; a[1] += melee
print(f"      {len(rows)} shells; builds " + ", ".join(f"{k} {v}" for k, v in sorted(builds.items()) if FAMILY.get(k.split(':')[0])))
print(f"      {paused} idle pauses taken")
print("      choices skill/basic: " + ", ".join(f"{k} {s}/{m}" for k, (s, m) in sorted(acts.items())))
for line in sorted(set(bad))[:15]:
    print("      ! " + line)
wiz = acts.get("wizard", [0, 0])
if mode == "on" and sum(wiz) and wiz[1] > 0.15 * sum(wiz):
    print(f"      ! wizards chose a basic attack {wiz[1]} times in {sum(wiz)}")
    bad.append("wizard melee")
if mode == "on" and not paused:
    bad.append("no idle pause taken")
if not rows or (mode == "on" and not sum(sum(v) for v in acts.values())):
    bad.append("no shells or no choices")
sys.exit(1 if bad else 0)
PY
)
personality_check() { python3 -c "$personality_py" "$@" "$RATHENA/db/re/item_db_equip.yml" "$RATHENA/db/population_personality.yml" >&2; }
if [ "$what" = personality ] || [ "$what" = all ]; then
	echo "== personalities"
	[ -n "$(sql "SELECT char_id FROM \`char\` WHERE name = '$char'")" ] || {
		start_servers 0; probe "$tmp/create.txt" --create "$char" --seconds 1; }
	# Shells only fight within sight of a player (pop_combat_tick_per_real_pc), so the player goes to them: a
	# shell from @whomap, followed (@jumpto) for a minute, a snapshot every 15 s. A shell left behind stops, and
	# a pause that comes due while nobody is near cannot start. The map keeps its shells between the two
	# sessions (the 20 s demand grace).
	personality_tour() { # personality_tour <out file>
		local m names
		: > "$1"
		for m in ${POP_TOUR_MAPS:-gef_dun01 iz_dun03 gl_prison gef_dun02}; do
			probe "$tmp/who-$m.txt" --social "say:@warp $m" --social wait:6 --social "say:@whomap $m" --seconds 1
			names=$(grep -aoP 'message: "Name: \K[^|()"]+?(?= *(\(|\||"))' "$tmp/who-$m.txt" | grep -vxF "$char" | head -1 || true)
			local visit=(--social "say:@warp $m") _
			for _ in 1 2 3 4; do
				[ -n "$names" ] && visit+=(--social "say:@jumpto $names")
				visit+=(--social wait:15 --social "say:@populate levels")
			done
			probe "$tmp/visit-$m.txt" "${visit[@]}" --social wait:1 --seconds 1
			cat "$tmp/visit-$m.txt" >> "$1"
		done
	}
	start_servers 1 "population_engine_demand_grace_ms: 20000" "population_engine_density_pct: 100"
	grep -aE "population_personality|population_skill_db.*(unknown|Kind)" "$logs/map.log" | head -5 >&2 || true
	check "AC1 population_personality.yml loads without warnings" \
		"$(yes_no '! grep -aE "^\[(Error|Warning)\]" "$logs/map.log" | grep -aqiE "personality|unknown (Kind|gear set|temperament)|Missing node"')"
	personality_tour "$tmp/pers-on.txt"
	check "AC2 builds fit their class, temperaments their ranges, wizards hardly melee" \
		"$(yes_no 'personality_check "$tmp/pers-on.txt" on')"
	if [ -z "${POP_SKIP_OFF:-}" ]; then
		start_servers 1 "population_engine_demand_grace_ms: 20000" "population_engine_density_pct: 100" "population_engine_scored_skills: 0"
		personality_tour "$tmp/pers-off.txt"
		echo "      scored_skills off (for comparison):" >&2
		personality_check "$tmp/pers-off.txt" off || true
	fi
fi

if [ "$what" = shops ] || [ "$what" = all ]; then
	echo "== shops: the app's AI shop settings"
	[ -n "$(sql "SELECT char_id FROM \`char\` WHERE name = '$char'")" ] || {
		start_servers 0; probe "$tmp/create.txt" --create "$char" --seconds 1; }
	id=$(sql "SELECT char_id FROM \`char\` WHERE name = '$char'")
	sql "UPDATE \`char\` SET last_map = 'prontera', last_x = 156, last_y = 191 WHERE char_id = $id"
	# shops_on <out> <map>...: on each map in turn, after the autosummon tick, the @populate vendors report
	shops_on() {
		local out=$1 steps=() m
		shift
		for m in "$@"; do
			steps+=(--social "say:@warp $m" --social wait:13 --social "say:@populate vendors")
		done
		probe "$out" "${steps[@]}" --seconds 1
	}
	# shop_counts <out>: "<shops> of <target>" for each map, in order
	shop_counts() { grep -aoP 'message: "vendors \K\d+ of \d+' "$1" || true; }
	nth() { shop_counts "$1" | sed -n "${2}p"; }
	keys() { grep -aoP 'message: "vendor aid .* key \K\S+' "$1" | sort -u | tr '\n' ' ' || true; }
	# other_keys <keys> <allowed|...>: how many of the keys are not allowed (empty if grep fails: a FAIL)
	other_keys() { echo "$1" | tr ' ' '\n' | sed '/^$/d' | grep -cvxE "$2" || true; }
	potion_price() { grep -aoP 'message: "vendor aid .* key potions .* first 501@\K\d+' "$1" | head -1 || true; }
	full() { local c; c=$(nth "$1" "$2"); [ -n "$c" ] && set -- ${c/ of / } && [ "$2" -gt 0 ] && [ $(($1 * 10)) -ge $(($2 * 9)) ]; }

	# Prontera alone: shops in Prontera, none in Geffen
	start_servers 1 "population_engine_vendor_towns: 0"
	shops_on "$tmp/shops-a.txt" prontera geffen
	p100=$(potion_price "$tmp/shops-a.txt")
	check "SH1 Shops in Prontera: prontera $(nth "$tmp/shops-a.txt" 1), geffen $(nth "$tmp/shops-a.txt" 2)" \
		"$(yes_no 'full "$tmp/shops-a.txt" 1 && [ "$(nth "$tmp/shops-a.txt" 2)" = "0 of 0" ]')"

	# main towns, half the shops at twice the price: Geffen gets them, Amatsu (minor) does not
	start_servers 1 "population_engine_vendor_towns: 1" "population_engine_vendor_pct: 50" "population_engine_vendor_price_pct: 200"
	shops_on "$tmp/shops-b.txt" prontera geffen amatsu
	p200=$(potion_price "$tmp/shops-b.txt")
	check "SH2 Shops per town 50%: prontera $(nth "$tmp/shops-b.txt" 1) (want 15)" \
		"$(yes_no 'full "$tmp/shops-b.txt" 1 && [ "$(nth "$tmp/shops-b.txt" 1 | cut -d" " -f3)" = 15 ]')"
	check "SH3 main towns: geffen $(nth "$tmp/shops-b.txt" 2), amatsu $(nth "$tmp/shops-b.txt" 3)" \
		"$(yes_no 'full "$tmp/shops-b.txt" 2 && [ "$(nth "$tmp/shops-b.txt" 3)" = "0 of 0" ]')"
	check "SH4 Shop prices 200%: a Red Potion ${p100:-?} -> ${p200:-?}" \
		"$(yes_no '[ -n "$p100" ] && [ -n "$p200" ] && [ "$p200" = $((p100 * 2)) ]')"

	# all towns, supplies only: Amatsu gets shops, and every shop is a supply shop
	start_servers 1 "population_engine_vendor_towns: 2" "population_engine_vendor_loot: 0" "population_engine_vendor_gear: 0"
	shops_on "$tmp/shops-c.txt" amatsu prontera
	k=$(keys "$tmp/shops-c.txt")
	check "SH5 all towns: amatsu $(nth "$tmp/shops-c.txt" 1)" "$(yes_no 'full "$tmp/shops-c.txt" 1')"
	check "SH6 supplies only: keys $k" \
		"$(yes_no '[ -n "$k" ] && [ "$(other_keys "$k" "potions|buff_shop|ammo_shop")" = 0 ]')"

	# loot only, with cards: loot shops alone, and the dungeon ones carry a card or two, the field ones none
	start_servers 1 "population_engine_vendor_supplies: 0" "population_engine_vendor_gear: 0" "population_engine_vendor_cards: 1"
	shops_on "$tmp/shops-d.txt" prontera
	k=$(keys "$tmp/shops-d.txt")
	carded=$(grep -acP 'key dungeon_drops .* cards [12] ' "$tmp/shops-d.txt" || true)
	dungeon=$(grep -ac 'key dungeon_drops ' "$tmp/shops-d.txt" || true)
	stray=$(grep -acP 'key (?!dungeon_drops )\S+ .* cards [1-9]|cards [3-9]' "$tmp/shops-d.txt" || true)
	check "SH7 loot only: keys $k" \
		"$(yes_no '[ -n "$k" ] && [ "$(other_keys "$k" "field_drops|dungeon_drops")" = 0 ]')"
	check "SH8 cards: $carded of $dungeon dungeon shops carry 1-2, $stray others or more" \
		"$(yes_no '[ "$dungeon" -gt 0 ] && [ "$carded" -gt 0 ] && [ "$stray" = 0 ]')"

	# AI shops off: no shops, and no shop merchants sitting in their place
	start_servers 1 "population_engine_vending_enable: 0"
	probe "$tmp/shops-e.txt" --social wait:13 --social "say:@populate vendors" --social "say:@whomap prontera" --seconds 1
	on_map=$(found "$tmp/shops-e.txt" prontera 1)
	check "SH9 AI shops off: $(nth "$tmp/shops-e.txt" 1), prontera ${on_map:-?} on the map (no sitters filling the cap)" \
		"$(yes_no '[ "$(nth "$tmp/shops-e.txt" 1 | cut -d" " -f1)" = 0 ] && [ -n "$on_map" ] && [ "$on_map" -lt 100 ]')"
	grep -aq 'could not open its shop' "$logs/map.log" && echo "note  map.log: $(grep -ac 'could not open its shop' "$logs/map.log") vendors could not open a shop" || true
fi

echo "logs: $logs  probe output: kept only on failure"
[ "$fails" = 0 ] || { cp -r "$tmp" "$work/probe-$(date +%Y%m%d-%H%M%S)"; exit 1; }
