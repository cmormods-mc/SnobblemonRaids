# Every active Pokemon in a raid is adjacent to every other

Cobblemon's `Targetable.getAdjacent()` lays active Pokemon on a line and mirrors sides for
single-target move legality, which assumes two symmetric teams. A raid is one boss against up to
four independent player sides, and under that model players in slots 3 and 4 could not legally
target the boss at all — their moves were rejected forever and the whole raid stalled. Retuning
`BattleType`'s width was tried first and cannot work: the boss occupies one mirrored position, so a
width that reaches players 1-2 cannot also reach players 3-4, and the reverse. `raid-patch.js`
already treats every active Pokemon as mutually adjacent on the Showdown side, so leaving the Java
adjacency model mismatched with it was the actual bug.

We overrode adjacency for raids specifically (`RaidActiveBattlePokemonMixin`) so every active
Pokemon — every player and the boss — is adjacent to every other, matching what the JS side already
assumes. This is a raid-only override with no equivalent lever in ordinary Cobblemon battles, and
undoing it means re-deriving this exact reasoning from scratch, which is why it is worth recording
rather than looking like an arbitrary mixin the next time someone finds it.
