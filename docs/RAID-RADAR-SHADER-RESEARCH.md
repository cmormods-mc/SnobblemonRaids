# Raid Radar shader research

Research date: 2026-10-29. Target supplied by user: Minecraft Java 1.21.1, Fabric, Polymer; players use Iris with shader packs such as Complementary.

This is a feasibility report, not a tested rendering implementation. The intended project was located at L:\SnobblemonRaids after correcting the stale C: working-directory path.

## Decision

Use a server-rendered held map as the baseline: a circular compass, cyan topographic contours rendered with a fixed perspective to suggest stacked elevation, an orange raid marker, and a dotted bearing line. This avoids relying on a core-shader override, though exact appearance and performance must still be tested with the user's Iris/Complementary versions. It does not provide real terrain geometry or view-dependent parallax.

A resource-pack core shader can be an optional experiment for clients without an active Iris shader pack. It cannot be the universal implementation for the stated player setup.

## Confirmed findings

- The Iris **1.21.1 branch** explicitly documents that custom shaders from mods/resource packs are ignored when an Iris shader pack is loaded. Iris recommends alternate rendering paths without custom shaders. The confirmed condition is an **active shader pack**, not simply having Iris installed. Therefore, the proposed core-shader hologram cannot be relied upon with Complementary active. [Iris 1.21.1 compatibility documentation](https://raw.githubusercontent.com/IrisShaders/Iris/1.21.1/docs/development/compatibility/core-shaders.md)
- Map Canvas API provides server-side drawing on runtime maps, with explicit viewer management. Its documentation permits drawing work off the main thread; that does not imply Minecraft world/chunk access is safe off-thread. Exact dependency compatibility for 1.21.1 must be pinned separately. [Map Canvas API](https://github.com/Patbox/map-canvas-api)
- Polymer's resource-pack API can register assets and check whether a player has the pack. Pack acceptance is not proof that a custom shader actually runs. These are current API docs, so exact method names must be checked against the project's installed Polymer version. [Polymer resource-pack basics](https://polymer.pb4.eu/latest/polymer-resource-pack/basics/)
- NMinimap demonstrates a server-driven map with metadata pixels recognized by a core shader. Its implementation uses packet-only frames and shader positioning; it is evidence for a technique, not a Fabric dependency recommendation or evidence of working 3D topography. Its current documented targets are newer Minecraft versions and Bukkit-family servers. [NMinimap implementation explanation](https://github.com/NezuShin/NMinimap#how-does-it-work)

## Proposed implementation

### Baseline: baked depth, no custom core shader

1. Maintain a bounded cache of coarse surface-height samples gathered from already-loaded chunks. Unknown areas stay unknown. Do not generate or load chunks just to draw the radar.
2. Copy necessary height data safely, then build contour lines and a fixed-perspective elevation image from those snapshots. Use elevation separation, darker lower contours, and bright upper edges to suggest stacked terrain.
3. Send rendered map pixels to the player only while the radar is held/open. Refresh the terrain when the sampled area changes; refresh player/raid overlays separately, initially around twice per second.
4. Keep the compass ring and markers legible in Minecraft's map palette and resolution. A bright cyan appearance is feasible as artwork; actual bloom, light emission, transparency, and pack-specific color response are not guaranteed.
5. Use a real raid registry position for the target. The dotted line is a direct bearing, not terrain pathfinding.

These are design proposals, not benchmarked guarantees. The held screen stays a flat map surface. A custom item model can provide a predefined device body, but a resource pack does not automatically turn changing terrain samples into changing model geometry.

### Optional research prototype: shader depth

A possible experiment is to encode a coarse heightfield plus marker metadata into legal map colors, identify only that radar texture, and reconstruct a small bounded stack of height slices in the fragment shader. The map palette requires an explicit encoding/decoding scheme; it is not an arbitrary RGB data texture. Shader input, held-map transforms, clipping bounds, depth, transparency, and unintended effects on normal maps/text must all be proven in Minecraft 1.21.1.

This architecture is an inference from the existing metadata-map technique and normal shader methods. No inspected source demonstrates this complete handheld radar. Shader rendering moves visual work to the player's GPU; it does not eliminate server terrain sampling or network updates. A Complementary-specific shader fork would require a separate integration/distribution and testing effort, rather than solving compatibility through the ordinary server resource pack.

## Prototype acceptance checks

- Start with synthetic hills and known marker positions before connecting live terrain.
- Test vanilla rendering, Iris with its shader pack disabled, and Iris with the exact Complementary release/settings enabled. Add the user's other renderer/optimization mods to the test matrix.
- Verify main hand, offhand, one/two-handed map poses, item switching, inventory view, and unchanged ordinary maps/text.
- For the baseline, verify compass orientation and that terrain/marker transforms agree. Clearly identify unknown terrain and targets outside range.
- Measure frame time with radar shown/hidden, server tick time with increasing simultaneous viewers, packet rate, cache bounds, and cleanup after switching items/disconnecting. Do not claim negligible cost before measurement.
- Keep a usable non-shader display selectable. Pack-status detection alone cannot automatically establish shader compatibility.

## Held-device composition limitation

A dynamic vanilla map texture cannot simply be assigned as an arbitrary resource-pack item-model texture. A predefined 3D compass housing and a changing map face are separate rendering concerns. Their composition in first person, main/offhand transforms, and any attempt to make terrain visibly extend above the housing require an in-game prototype on 1.21.1. This report does not assert that a server-only 3D shell with an embedded live map is already supported or straightforward.

| Mechanism | Evidence status |
| --- | --- |
| Server-generated map imagery | Documented by Map Canvas API; 1.21.1 dependency selection remains to be checked |
| Core-shader override ignored with an active Iris shader pack | Explicitly documented in Iris's 1.21.1 branch |
| Metadata pixels interpreted by a resource-pack shader | Demonstrated by NMinimap on its own documented targets |
| Fixed-perspective contour art in a held map | Proposed baseline; requires implementation and visual/performance validation |
| Dynamic heightfield reconstructed as stacked shader layers | Experimental proposal; not validated for this radar |
| 3D compass housing integrated with a dynamic held map | Unproven composition; requires an in-game prototype |

## Verified project context

The parent agent located the intended checkout at `L:\SnobblemonRaids` and read its build configuration: Minecraft 1.21.1, Fabric Loader 0.17.2, Fabric API 0.116.6+1.21.1, Polymer core/resource-pack/common 0.9.19+1.21.1, Cobblemon 1.8.2, Java 21, and mod version 0.8.191-world-boss. No Map Canvas API dependency is currently listed. The initial configured `C:\Users\Facel\SnobblemonRaids` path is missing; this explains the terminal launch failure from that working directory. The report is also retained in the chat artifact directory.
