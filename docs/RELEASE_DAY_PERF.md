# Release-day performance runbook (0.8.184-perf-telemetry)

Target: 20-30 concurrent players. Everything below is read from one file and one command.

## What is recorded

| Where | What |
|---|---|
| `logs/cobbleraids-perf.csv` | Every 5 min while anyone is online (and once at shutdown): one row per timed section plus gauges (`players`, `mspt`, `heap_mb`, `active_raids`, `open_lobbies`). Long format, header `time,kind,name,count,total_ms,avg_ms,max_ms,value`. |
| Server log, `[CobbleRaids] [perf] last 5 min: ...` | The same window as one line: gauges plus the three costliest sections. |
| Server log, `[CobbleRaids] [perf] <section> took N ms in a single run` | Immediately, for any single run over the slow threshold. Rate-limited to one per section per minute. |
| `/cobbleraids perf` (op) | Live gauges and the 12 costliest sections since boot. |
| `/cobbleraids perf log` (op) | Writes the current window to the CSV now. Run it just before and just after a busy moment so the file brackets it. |

Sections: nine per-tick subsystems (`lobby`, `spawning`, `rewards`, `capture-sessions`, `reconnect-grace`,
`combat-timer`, `boss-glow`, `web-sounds`, `consistency-audit`) plus `perf-report`; `spawn:attempt`;
`mixin:selectActions` (every battle choice); `flush:rewards`, `flush:player-records`,
`flush:boss-snapshots`, `flush:capture-sessions` (synchronous disk writes); `web:<channel>` (every
page event); `web:icon-first-load`, `web:lang-first-load` (one-off jar reads on first page open);
`consistency-audit:run`.

Overhead: two `System.nanoTime()` calls and one map lookup per measurement, no allocation on the tick path.

## Reading it afterwards

    python validation/perf_summary.py logs/cobbleraids-perf.csv

Per-section totals and worst single run, then the five busiest windows beside players / raids / MSPT / heap.

Healthy: every `tick` subsystem well under 1 ms mean; `ms/tick` under ~5; no section's worst run near 50 ms.

## What to look at first (hypotheses, not findings)

1. **`flush:*` max and total.** `rewards`, `player-records` and `boss-snapshots` call the global
   `DataStorage.save()`, which writes every dirty SavedData of every mod, synchronously. Cost scales with
   players-ever-seen, not concurrent players, so it should be small on day one. If `flush:rewards` max
   climbs past ~20 ms, switch these three to the targeted `store.flush()` the capture store already uses
   (`RaidCaptureSessionStore.flush`). Not changed pre-release because it alters durability for
   reward/dupe-sensitive data, and the raid history recorded just before a grant currently rides along in
   that same write (`RaidRewardService.grant`).
2. **`web:icon-first-load` / `web:lang-first-load`.** The first player to open each shop page pays for
   reading icons and Cobblemon's language file from the jar on the server thread. Expect a handful of
   spikes in the first minutes, then silence. If they are over ~30 ms, pre-warm at `SERVER_STARTED`.
3. **`mixin:selectActions` max.** Runs on every battle choice in a raid. Should be microseconds.
4. **`heap_mb` trend** across windows with no players change: a steady climb means a leak.

## Not verified

- The telemetry has unit tests (accounting, window reset, CSV shape) and passed the full local CI gate,
  but has **not been run on a live server**. Run `/cobbleraids perf` once after boot on Friday.
- No load test was run for this change. `validation/smoke/load_test.py` exists; note its known
  `pokegive` bug (use `pokegiveother <player> <species> level=N`).
- The Showdown battle sim is single-threaded; many concurrent raids serialise on it. The gauges
  `active_raids` against `mspt` will show whether that bites.

## Audit result in one table

| Finding | Status |
|---|---|
| No performance instrumentation | Added (this change) |
| Leaderboard sort allocated a lowercased string per comparison | Fixed: key computed once per player |
| `hello` debug page/command/channel left from phase 0 (unthrottled log of client text) | Removed |
| Global `save()` in three `persistNow` paths | Instrumented, not changed (see above) |
| WebGUI page events possibly off the server thread | Refuted: WebGUI hops to the server thread and rate-limits to 20/s before our handler |
| Obfuscated/hidden code | None found: no network calls, no process spawning, no Base64 payloads (only PNG data URLs), reflection only for optional-mod bridges |
| Dead code | One unreferenced class, `api/points/CobbleRaidsPoints`, is a deliberate public API for other mods; kept |
| Per-tick work | All nine subsystems early-exit on empty state; scans are once per second and O(bosses x players) |
