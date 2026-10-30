# Optional 3D Raid Radar companion

Server build: `CobbleRaids-0.8.194-raid-charges.jar`.
Client build: `CobbleRaids-Radar-Client-0.4.2+1.21.1.jar`.
Target: Minecraft 1.21.1, Java 21, Fabric API 0.116.6+1.21.1.

## Installation and controls

- Install the server JAR on the server, replacing the earlier CobbleRaids JAR.
- Players who want 3D install the separate client JAR alongside their existing Fabric/Cobblemon setup. Do not install the server CobbleRaids JAR on clients or the companion on the server.
- Players without the companion retain the flat radar map. The companion does not register duplicate server items.
- Craft the same radar or use `/give @s cobbleraids:raid_radar` as an operator.
- Right-click opens the server-native raid status menu: charge balance, hourly refill countdown and Open/Close Hologram/Map buttons. Sneak + use cycles raids while open. **H** toggles the companion's 3D display and flat map, with a configurable binding under CobbleRaids Radar in Controls.

## What is actually 3D

In first person, an open radar with a matching server snapshot displays an armored projector floating in front of the player. Its base remains horizontal in the world instead of being tilted into a held-map pose. A cyan wireframe terrain hologram tilts 12 degrees toward the viewer above it with a shortened projection lift and a clear air gap, contrasting translucent surfaces and projection rays converging on the compass emitter. The triangular grid and terrace edges are actual geometry, not painted depth. An arrow shows the player's facing, an orange beacon marks the raid, and dotted guidance floats above the terrain. The radar can be activated from either hand. While the floating projection is open, held arms/items are hidden; H restores the normal flat-map and hand rendering. Inventory and third-person item appearances keep the existing server-supplied representation.

The terrain uses a bounded 32x32 field with at most twelve elevation terraces, cropped to the circular bezel. Missing samples create holes instead of invented terrain. The elevated model and projection cone use the normal translucent entity pipeline; bloom remains dependent on the selected shader pack. Geometry is cached and rebuilt only when a full terrain snapshot changes; position/target updates do not rebuild it. Terrain emerges over 300 ms when the first known surface arrives. Height is normalized to the local sampled terrain, not presented as an accurate altitude scale.

It renders during Fabric's world-rendering event with camera-relative positioning and Minecraft's standard entity geometry pipeline with a bundled white texture and vertex colors. Thin wires use the emissive layer; the cone and faint surfaces use ordinary translucency to control bloom. No resource-pack core shader is overridden and no custom Complementary fork is required. Full-bright vertex lighting is supplied; bloom, transparency treatment and exact colors depend on the active shader pack. Iris compatibility must be verified with actual rendering rather than inferred from compilation alone.

The device has a layered octagonal metal chassis, brushed surface texture, cooling fins, captive fasteners, concentric optics and restrained illuminated inlays. The circular collar joins the octagonal armor continuously; the recessed liner and beveled inner lip have inward-facing geometry and normalized lighting normals. A separate front instrument display shows the raid species, distance, tier, tracking status and compass bearing against a dark background. Device geometry is cached once; the denser terrain uses the existing server samples and adds no terrain queries or network traffic.

## Server and network boundaries

The base server JAR contains no client rendering classes or client entrypoint. A shared value/codec protocol is compiled into both JARs. The server sends the display-only `cobbleraids:radar_hologram_v1` payload only after Fabric reports that this player can receive it. There is no new C2S raid action or client authority over rewards, boss selection, participation or spawn rates.

A full 33x33 height snapshot uses fixed signed-short samples (about 2.2 KB), sent on first capability detection and completed terrain refreshes. Intervening updates omit heights and carry only the view/target information. The existing flat map remains current so H can switch immediately. Terrain gathering retains the global 64-samples-per-tick budget, loaded-chunk-only policy and 10-second refresh interval. Closing/stowing stops updates; disconnect releases server and client state.

## Validation

- Both server and companion build via `gradlew build`; the root build depends on the companion build.
- Full repository validation passed. Unit tests: 741 server/common tests plus eight client geometry tests, all passing.
- Mesh tests verify raised geometry, bounded height/vertex counts, outward face winding and unknown-data holes. Protocol tests verify real codec round trips, bounded snapshots, omitted terrain in light updates and rejected malformed data.
- The live isolated server probe passed nine checks, including exact crafting, map fallback, offhand use, no custom packets to non-companion clients and a full snapshot followed by lighter packets after capability advertisement.
- `validation/radar/validate_hologram.py` inspects both real JARs to enforce the physical-side boundary, absence of required client/server cross-dependencies, and exclusion of the graphical probe and core shader overrides.

The isolated production-JAR graphical probe passed with local copies of Iris 1.8.8, Sodium 0.6.13 and Complementary Unbound r5.1.1, with an active shader pipeline. It captured main-hand 3D, the H-key flat-map fallback and offhand 3D. The geometry was visually inspected, and first-person placement was refined to keep the device visible. No multiplayer load or client FPS benchmark is claimed.

```text
gradlew :radar-client:remapSmokeJar -PradarSmoke
python validation/radar/graphical_smoke.py --server-dir <isolated-test-rig> --output-dir <graphical-results>
```

The runner uses existing local shader/mod files and Minecraft's normal production namespace, and writes client configuration only under the ignored `radar-client/run-hologram-smoke` directory. The test-only source set is enabled by `-PradarSmoke` and packaged separately; it is excluded from the companion JAR. The test terrain is explicitly prepared in the isolated world, with its temporary chunk tickets removed afterward. Production radar sampling still never loads chunks.

## Natural-biome graphical test

The same v0.4.2 client passed the production-JAR probe in a normally generated meadow biome with Iris 1.8.8 and Complementary Unbound r5.1.1. At the captured position (-104, 114, -125), the live snapshot contained 625 known samples from Y=62 to Y=149, producing ten distinct elevation bands. Main-hand 3D, H-key flat fallback and offhand 3D were captured and inspected. This is a functional/visual check, not a client FPS benchmark.

```text
python validation/radar/graphical_smoke.py --biome minecraft:meadow --server-dir <isolated-test-rig> --output-dir <biome-results>
```

The biome option uses a separate `radar-natural-biome-world` with normal generation and seed 20261030. It locates a real biome, places the player on the existing surface and spawns a test raid without building terrain. The isolated client and server use a ten-chunk view distance for this capture. Temporary chunk tickets are removed and the original server.properties bytes restored afterward. The probe records biome, coordinates, source-height range and distinct mesh elevation bands in `biome-terrain.json`. The shipped renderer and sampling policy are unchanged.

## Personal charges and status menu

Every player UUID starts with three charges and refills one per wall-clock hour, including offline time, up to three. The first spend starts the next-refill deadline; later spends do not postpone it. The refill calculation is lazy and constant-time, so there is no per-player background recharge task. The vanilla 27-slot status menu refreshes its countdown once per second only while open and prevents moving or extracting its display items. The optional companion does not supply balances or decide entry eligibility.

Ordinary raid lobbies refuse zero-charge players both when joining and when locking their roster. RaidFactory reserves the full roster atomically before creating an ordinary battle, persists the reservation through an atomic file replacement, and commits after session activation. Exceptions before commit restore each original account and refill deadline. Charge spending applies to started battles regardless of outcome; cancelling an unstarted lobby costs nothing. Encounters owned by another mod retain their own entry rules.

`validation/charges/smoke_test.py` passed against the real server and a vanilla-protocol client: menu item safety, menu-to-map activation, free lobby cancellation, three real raid starts, fourth-entry refusal, countdown updates, compass replacement, relog/restart durability and offline catch-up capped at three. The offline scenarios age only the test UUID's saved next-refill timestamp while the isolated server is stopped. Deterministic unit tests cover exact hourly boundaries, timer preservation, clock rollback, full-pool reset, invalid state and NBT round trips.
