# Re-install the Showdown JS patch at SERVER_STARTED, not only at unbundle time

Our raid logic lives partly in `raid-patch.js`, hooked into Cobblemon's unbundled Showdown
`index.js` at the moment Cobblemon first unbundles it. Other mods that also patch Showdown's
unbundled files (e.g. `mega_showdown`, which targets the same four Cobblemon classes we mixin into)
can run after us and overwrite or wholesale-replace `index.js`, non-deterministically, depending on
Fabric's mod/mixin load order — so patching only at unbundle time meant our hook could silently be
missing by the time the server was actually up, with boss healing broken and no exception anywhere.

We chose to make the install idempotent and register a second call site
(`ShowdownIntegrationInstaller.ensureInstalled()`) on `ServerLifecycleEvents.SERVER_STARTED`, after
every other mod's unbundle-time writes are guaranteed done, rather than trying to control or detect
load order. The alternative — asserting a specific load order via Fabric's dependency metadata — was
rejected because it would require the other mod's cooperation and breaks the moment either mod
updates.

## Consequences

This does not fully close the gap: a mod that rewrites `index.js` *after* `SERVER_STARTED`, or
between our re-check and Cobblemon building its simulator from the file, is still a live risk.
`"Post-startup Showdown integration re-check failed"` in server logs is the canary to watch for.
