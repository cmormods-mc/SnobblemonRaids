# Mixin the concrete Cobblemon class actually used at runtime, never a vanilla ancestor on faith

A mixin's `@Inject` compiles and Mixin applies it without complaint even when the target method is
never reached for any real object, because a concrete subclass between the target and the actual
runtime type overrides that method without calling super. This has happened twice, independently,
and both times nothing threw: `RaidBossNameplateIconMixin` targeted vanilla
`EntityRenderer.renderNameTag` through two versions and never fired, because `PokemonRenderer`
hardcodes `shouldShowName` to false and draws through its own, unrelated override of the same method
name. `BattleClonePersistenceMixin` targeted vanilla `Entity.shouldBeSaved` on the unverified claim
that `PokemonEntity` did not override it; `PokemonEntity` does, completely, and the mixin was dead
code for every Pokemon in the game -- including the exact battle-clone duplication bug it existed to
fix.

We now target the concrete Cobblemon class a mixin actually needs, verified by decompiling the real
dependency jar rather than assumed from the vanilla API shape, and `validation/validate_mixin_target_shadowing.py`
checks every existing mixin target against Cobblemon's own class hierarchy for exactly this shadowing
shape on every push. The check is advisory, not a hard proof of correctness -- it flags a candidate
override that does not call up to the target and needs a human to look, the same way both real
instances were actually found -- because a mixin that turns out to need a vanilla ancestor's guarantee
regardless of any subclass is a legitimate, if rarer, choice we do not want to forbid outright.
