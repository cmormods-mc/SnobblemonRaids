# Validation scripts

Everything here runs in CI before and after the Gradle build, so `gradlew build` passing locally
proves nothing about them. Run the lot before pushing any change that moves, renames or deletes Java
code:

```sh
bash validation/ci_local.sh
```

`ci_local.sh` is `.github/workflows/build.yml` minus the GitHub-specific steps: the source
validators, `gradlew clean build`, then the validators again over the produced JAR, with the
version read out of `build.gradle` so a bump cannot skip the JAR pass. **Add any new workflow
step here too** — a stale copy is a false green. Run it by hand, or install the hook that runs
it on every push:

```sh
bash validation/hooks/install.sh     # once per clone
```

That hook is currently the only gate: GitHub Actions is disabled account-wide, so the last
remote run in this repo's history is from 2026-09-10. `git push --no-verify` or
`SKIP_LOCAL_CI=1 git push` bypasses it for one push.

## The reward-economy manifest

`economy/manifest.json` is the answer to "does `cobblemoncharms:bug_charm` actually exist",
generated from the modpack rather than asserted here:

```sh
python validation/economy/build_manifest.py --pack <modpack.zip or mods/ dir>   # by hand, needs the pack
python validation/economy/validate_economy_manifest.py                          # in CI, needs nothing
```

The split is the point. Generating needs a 658 MB zip nobody wants in CI; checking needs only
the committed manifest and the tree, so it runs in the ordinary sequence and fails a push the
moment the reward data names something the pack does not have. Regenerating is deterministic --
two runs against one pack produce byte-identical output -- so a mod update shows up as a diff
rather than as a mystery.

Two decisions inside it worth keeping:

- **Item model paths, not language files.** The charms mod ships a single
  `item.cobblemoncharms.type_charm` key covering eighteen separate items, so a lang-based check
  reports eighteen items missing that are all present. `assets/<ns>/models/item/*.json` tracks
  the registry; lang does not.
- **Mega Stone ids are derived by rule, not listed.** Four of the twenty-three (`charizarditex`
  and friends) register as `charizardite_x`. The generator tries the id, then the X/Y
  normalisation, then **fails** if neither resolves -- so the next renamed pair breaks the build
  instead of shipping a broken reward.

The detector has been proven to bite on all five of its failure modes: an item that does not
exist, a banned Utilities+ item becoming reachable, a hand-edited manifest, a stale boss count,
and an unknown namespace.

The steps individually, if you want to run just one:

```sh
bash validation/validate_phase31.sh
for n in 32 36 37 38 39 40 41; do python validation/validate_phase$n.py; done
python validation/validate_logging.py
python validation/validate_callback_guards.py
gradlew clean build
python validation/validate_mixin_guards.py
for n in 31 32 36 37 38 39 40 41; do python validation/validate_phase$n.py build/libs/CobbleRaids-<version>.jar; done
python validation/validate_mixin_guards.py build/libs/CobbleRaids-<version>.jar
```

## Two kinds of check, and only one of them is worth writing

**Property checks — write these.** They re-derive their subject from the tree on every run and assert
something that stays true no matter how the code is spelled:

- `validate_mixin_guards.py` — finds every `@Inject`/`@Redirect` in the mixin sources, then asserts
  each one's *compiled bytecode* carries an exception table. Rename a handler, add a mixin, move the
  package: it keeps working, and it covers new code the day it lands.
- `validate_logging.py` — no `System.out`, no `printStackTrace`, no logger outside `RaidLog`.
- `validate_physical_side_boundary` in phases 37/38/40 — walks every Java file looking for
  client-only imports in server code.
- Asset, JSON, jar-content and dependency checks — 130 raid definitions parse, textures ship,
  `fabric.mod.json` declares what it should.

**Source-text checks — avoid, and delete on sight when something better exists.** Asserting that a
particular identifier appears in a particular file couples the check to spelling rather than
behaviour. It cannot catch a bug — code can contain the right words and do the wrong thing — and it
fails on refactors that break nothing. That is the expensive failure mode: a red CI on a correct
change teaches people to edit the check instead of believing it.

This repo learned that twice. `validate_phase32` asserted `ActiveSpawn` was still a record, then that
it was still a class in `RaidSpawnScheduler`; both times CI went red for a change that broke nothing.
`validate_phase31` did the same over `announceNaturalSpawn`, `sendSpawnInfo` and `testWild` when the
spawn package was split.

## What replaced them

- **Unit tests** for rules. `ActiveRaidSpawnTrackerTest` covers the despawn logic those phase-32
  assertions were groping at, including the re-entrant discard behind `f261da0`, and it fails on
  wrong behaviour rather than on a rename.
- **The live smoke test** (`validation/smoke/`) for wiring. Running `cobbleraids spawn` against a
  real server is a far stronger statement than "the string `RaidTierSelector.select` appears in
  RaidSpawnScheduler.java".

What is left in the phase scripts is what neither of those can see: that callbacks are registered
with Fabric, that the tracking key is not an entity reference, that assets and datapack files ship.

## Adding a check

Ask what breaks it. If the answer is "renaming a method", write a unit test instead. If the answer is
"deleting the behaviour", it belongs here.
