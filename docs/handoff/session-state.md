# Session state: SnobblemonRaids (CobbleRaids)

Branch `cmor/amazing-hopper-1r6071`. Latest work: World Boss, version `0.8.191-world-boss`.
Target: Minecraft 1.21.1, Fabric (loader 0.17.2, Fabric API 0.116.6+1.21.1), Cobblemon 1.8.2. Server-side only (Polymer items, WebGUI pages), so vanilla-resourcepack clients can join.
Commit and push to the branch once tested; no PRs unless asked. Glossary: `CONTEXT.md`. Decisions: `docs/adr/`.

## Where things are
* Build and test: `./gradlew build`. Publish for sibling mods (CobbleTowers needs it): `./gradlew publishToMavenLocal`.
* World Boss design: `docs/WORLD-BOSS-DESIGN.md`. Wiki handoff: `docs/WIKI_HANDOFF.md`. Perf notes: `docs/RELEASE_DAY_PERF.md`.
* Shop, renown and reward economy follow the raid-points ADR (`docs/adr/0001`).

## Recent state (newest first)
* World Boss arena: spawn and entrance marker blocks, warp and return (0.8.191).
* World Boss can only be started from another mod (0.8.191); ships on with the Halloween 2026 window (0.8.190).
* World Boss: shared-pool event boss fought as solo duels, music, glass dome, night-only spawn, haunted boss bar.
* Commands simplified: `/cr` alias, bare `/cr` hub, `/cr shop`. Personal rotating shop (0.8.188).

## Latest verification
* World Boss load test, 2026-10-28: 40 bots duelled one boss at once, defeated once, all 40 paid, about 10 ms average tick. Details and rig in `docs/WORLD-BOSS-DESIGN.md` (last section). Driver: `validation/smoke/worldboss_duel_load.js`.

## Known gaps
* Untested: real clients at 40, real damage per player-minute (default 6000 is a guess), a 40-duel-sized arena, restart at 40 players.
* Update this list at the end of each session.
