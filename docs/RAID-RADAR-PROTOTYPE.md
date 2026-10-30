# Raid Radar prototype

Minecraft 1.21.1 Fabric / Polymer 0.9.19. Current server build: `0.8.194-raid-charges`.

## Player controls

Craft one `cobbleraids:raid_radar` in a crafting table:

| Netherite Ingot | Shulker Shell | Netherite Ingot |
| --- | --- | --- |
| Cobblemon Upgrade | Compass | Cobblemon Upgrade |
| Netherite Ingot | Shulker Shell | Netherite Ingot |

One ingot in each corner: **four total**. Recipe-book discovery occurs when acquiring a compass.
Operators can test with `/give @s cobbleraids:raid_radar`.

- Right-click opens the charge/status menu. Its compass button opens or closes the radar. Closed appearance is a vanilla compass; the server-only open appearance uses the vanilla held-map pose.
- Sneak + use while open to cycle raids, initially choosing the nearest available target.
- Either hand works; opening a second radar closes the other held radar.
- White arrow: your position and facing. Orange diamond: selected raid. Cyan dots: direct bearing.
- Compass stays north-up. Distant raid markers clamp to the edge. Action bar shows species, distance, rarity tier and natural-spawn lifetime remaining.
- Targets are in the same dimension. Full/closing lobbies, battling bosses, owned encounters and natural bosses too close to expiry to recruit are excluded. Unloaded natural bosses use recorded spawn positions and are labelled **last known position**.

## Display and performance boundary

The circular compass and illustrated elevation are drawn into an opaque **128x128 vanilla map texture**. The result suggests stacked contours, but does not create actual 3D terrain geometry, a physical compass housing, bloom, transparency or view-dependent parallax. No custom core shader, extra client mod, dynamic item-model texture or resource-pack dependency is added for this display. Actual rendering with the user's Iris/Complementary versions remains an in-game visual check.

Terrain spans approximately 512 blocks, sampled every 16 blocks on a 33x33 grid. It uses only `ServerChunkCache.getChunkNow` and loaded chunk heightmaps; no chunk loads/generation, pathfinding or world entity scans. Missing samples stay blank. The server shares a budget of 64 height samples per tick across all active viewers, round-robin. More viewers increase initial scan latency rather than the sampling budget.

Terrain refresh starts 10 seconds after a completed scan. The previous background remains visible while refreshing. The view recentres after moving 96 blocks from its sampled centre. It uses a surface heightmap, so caves are not mapped and Nether roof topography is not a useful underground navigation aid.

Player and target overlays update at most twice per second. Unchanged frames send nothing; changed frames send their smallest enclosing rectangle. Stowing or closing the item stops rendering; disconnect and shutdown release viewer state. Respawn/dimension changes force a fresh view. Each radar reserves one ordinary map ID when first opened; it does not create a saved vanilla map-data file.

## Original flat-prototype verification

Current charge-menu, natural-biome and optional 3D verification is recorded in [RAID-RADAR-HOLOGRAM.md](RAID-RADAR-HOLOGRAM.md).

- `validation/ci_local.sh`: complete existing source, test, bytecode, JAR and Showdown checks.
- JUnit: 728 passing tests overall, including 10 radar tests covering projection directions, target-edge bearing, unknown terrain, synthetic contours, background immutability and map-patch reconstruction (including edge pixels).
- `validation/radar/RenderRadarPreview.java`: uses the actual renderer and vanilla map palette with synthetic hills. The generated preview is not an in-game screenshot. A warmed 200-render sample on this machine measured 0.202 ms median / 0.344 ms p95; this is a microbenchmark, not a server tick-time or client FPS measurement.
- `validation/radar/smoke_test.py`: a dedicated server and vanilla-protocol client exercise real crafting and map packets. All seven live checks passed on an isolated localhost Fabric/Cobblemon server. See `validation/radar/README.md` for invocation and results.

Manual visual acceptance still needs main/offhand inspection with the exact player modpack, GUI scale/FOV, Iris/Complementary release/settings, and a real-world terrain sample. Multi-viewer server load and client FPS are not benchmarked.

The raid allowance discussed earlier is a separate gameplay change and is not part of this prototype. Spawn rates are not changed.
