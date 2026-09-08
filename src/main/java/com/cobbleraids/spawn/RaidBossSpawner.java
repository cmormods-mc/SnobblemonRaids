package com.cobbleraids.spawn;

import com.cobbleraids.config.CobbleRaidsConfig;
import com.cobbleraids.config.CobbleRaidsConfigManager;
import com.cobbleraids.config.RaidBossTraits;
import com.cobbleraids.config.RaidDefinition;
import com.cobbleraids.presentation.RaidBossGlowService;
import com.cobbleraids.presentation.RaidTierPresentation;
import com.cobbleraids.showdown.ShowdownIntegrationInstaller;
import com.cobblemon.mod.common.api.moves.Move;
import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.moves.Moves;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.pokemon.stats.Stat;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.EVs;
import com.cobblemon.mod.common.pokemon.Gender;
import com.cobblemon.mod.common.pokemon.IVs;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import com.cobblemon.mod.common.pokemon.properties.UncatchableProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import kotlin.Unit;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/** Creates a real Cobblemon PokemonEntity in the normal world for a raid definition. */
public final class RaidBossSpawner {
    private RaidBossSpawner() {}

    public static PokemonEntity spawnAt(ServerLevel level, Vec3 position, RaidDefinition definition) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(definition, "definition");
        // A boss whose Showdown edits never installed would recruit players and then break at the
        // first turn. Refuse at the single choke point both the scheduler and /cobbleraids spawn use.
        if (!ShowdownIntegrationInstaller.isReady()) {
            throw new IllegalStateException("CobbleRaids' Showdown integration is not installed, so raid battles"
                    + " cannot run. See '[CobbleRaids] Showdown integration FAILED' in the server log.");
        }

        Species species = PokemonSpecies.getByIdentifier(definition.species());
        if (species == null) throw new IllegalArgumentException("Unknown Cobblemon species: " + definition.species());

        Pokemon pokemon = new Pokemon();
        pokemon.setSpecies(species);
        pokemon.setLevel(definition.level());
        pokemon.initializeMoveset(false);
        applyFixedMoveset(pokemon, definition);
        // Strictly before the health line below: IVs, EVs and nature all change getMaxHealth(), so
        // applying them afterwards would spawn every boss already damaged.
        applyTraits(pokemon, definition);
        pokemon.setCurrentHealth(pokemon.getMaxHealth());
        UncatchableProperty.INSTANCE.uncatchable().apply(pokemon);

        PokemonEntity entity = pokemon.sendOut(level, position, null, spawned -> {
            RaidBossEntityMarker.mark(spawned, definition.id());
            RaidBossEntityMarker.markSpawnTime(spawned, level.getGameTime());
            spawned.setPersistenceRequired();
            spawned.setCountsTowardsSpawnCap(false);
            spawned.setInvulnerable(true);
            spawned.setCustomName(RaidTierPresentation.styledName(definition.rarityTier(), spawned.getPokemon().getSpecies().getTranslatedName()));
            spawned.setCustomNameVisible(true);
            return Unit.INSTANCE;
        });
        if (entity == null) throw new IllegalStateException("Cobblemon did not create a PokemonEntity for " + definition.id());
        applyMovementLock(entity);
        RaidBossGlowService.register(entity, level);
        return entity;
    }

    /**
     * Replaces the species' level-up moves with the definition's hand-picked set.
     *
     * <p>Runs after initializeMoveset rather than instead of it: that call also seeds PP and the
     * benched moves Cobblemon expects a Pokemon to carry, and leaving it in means a definition with
     * no "moves" list keeps exactly the behaviour it had before this existed.
     *
     * <p>An unrecognised move id is skipped with a warning rather than aborting the spawn. The
     * alternative is a raid that refuses to appear because of one typo in one datapack entry, which
     * is a far worse failure for a live server than a boss that is one move short. Everything else
     * about the boss is already valid at this point.
     */
    private static void applyFixedMoveset(Pokemon pokemon, RaidDefinition definition) {
        if (definition.moves().isEmpty()) return;
        List<Move> resolved = new ArrayList<>();
        for (String moveId : definition.moves()) {
            MoveTemplate template = Moves.getByName(moveId);
            if (template == null) {
                System.err.println("[CobbleRaids] " + definition.id() + " lists unknown move '" + moveId
                        + "'; skipping it. Check the id against Cobblemon's move list.");
                continue;
            }
            resolved.add(template.create());
        }
        if (resolved.isEmpty()) return;
        pokemon.getMoveSet().clear();
        for (Move move : resolved) pokemon.getMoveSet().add(move);
    }

    /**
     * Applies the definition's optional traits, plus the server-wide shiny roll.
     *
     * <p>Routed through PokemonProperties rather than Pokemon's own setters because the ones that
     * matter here -- setIvs, setEvs, setAbility, setHeldItem -- are Kotlin-internal ($common) and
     * are not API. PokemonProperties is what Cobblemon's own /pokegive and /pokeedit use, so this
     * gets its parsing, its validation and its behaviour for free, including quietly ignoring an
     * id that does not resolve.
     *
     * <p>A definition with no traits block still reaches this for the shiny roll, and otherwise
     * leaves the Pokemon exactly as Cobblemon built it.
     */
    private static void applyTraits(Pokemon pokemon, RaidDefinition definition) {
        CobbleRaidsConfig.BossTraits config = CobbleRaidsConfigManager.get().bossTraits();
        RaidBossTraits traits = definition.traits();

        PokemonProperties properties = new PokemonProperties();
        boolean any = false;

        if (traits.nature() != null) { properties.setNature(traits.nature()); any = true; }
        if (traits.ability() != null) { properties.setAbility(traits.ability()); any = true; }
        if (traits.form() != null) { properties.setForm(traits.form()); any = true; }
        if (traits.teraType() != null) { properties.setTeraType(traits.teraType()); any = true; }
        if (traits.heldItem() != null) { properties.setHeldItem(traits.heldItem()); any = true; }
        if (traits.gender() != null) {
            Gender gender = parseGender(traits.gender(), definition);
            if (gender != null) { properties.setGender(gender); any = true; }
        }

        if (!traits.ivs().isEmpty()) {
            IVs ivs = new IVs();
            // Jitter is applied only to values a definition actually pinned. An unset stat stays on
            // Cobblemon's own random roll, which is what keeps an empty traits block a no-op.
            traits.ivs().forEach((stat, value) ->
                    ivs.set(statFor(stat), RaidBossTraits.jitterIv(value, config.ivJitter(), ThreadLocalRandom.current())));
            properties.setIvs(ivs);
            any = true;
        }
        if (!traits.evs().isEmpty()) {
            EVs evs = new EVs();
            traits.evs().forEach((stat, value) -> evs.set(statFor(stat), value));
            properties.setEvs(evs);
            any = true;
        }

        if (config.shinyChance() > 0.0 && ThreadLocalRandom.current().nextDouble() < config.shinyChance()) {
            properties.setShiny(Boolean.TRUE);
            any = true;
        }

        if (any) properties.apply(pokemon);
    }

    private static Stat statFor(String name) {
        return switch (name) {
            case "hp" -> Stats.HP;
            case "attack" -> Stats.ATTACK;
            case "defence" -> Stats.DEFENCE;
            case "special_attack" -> Stats.SPECIAL_ATTACK;
            case "special_defence" -> Stats.SPECIAL_DEFENCE;
            default -> Stats.SPEED;
        };
    }

    private static Gender parseGender(String value, RaidDefinition definition) {
        return switch (value) {
            case "male" -> Gender.MALE;
            case "female" -> Gender.FEMALE;
            case "genderless", "none" -> Gender.GENDERLESS;
            default -> {
                System.err.println("[CobbleRaids] " + definition.id() + " traits: unknown gender '" + value
                        + "'; expected male, female or genderless.");
                yield null;
            }
        };
    }

    /**
     * Roots the boss and makes it unshovable. Applied here because this is the one path every boss
     * takes, natural or admin-spawned.
     *
     * <p>The slowness is infinite rather than refreshed on a timer: MobEffectInstance supports
     * INFINITE_DURATION, it is saved in entity NBT, and so it survives chunk unloads and restarts
     * with no per-tick cost at all. Particles and the HUD icon are suppressed the same way
     * RaidBossGlowService suppresses them for the glow, so the boss does not trail swirls.
     *
     * <p>Knockback resistance is the vanilla mechanism -- LivingEntity#knockback multiplies by
     * (1 - KNOCKBACK_RESISTANCE), so 1.0 zeroes melee and projectile knockback outright, with no
     * mixin needed. It does NOT cover explosions, entity collisions or fishing rods, which reach
     * the entity through other paths; RaidBossPushImmunityMixin and RaidBossFishingImmunityMixin
     * close those.
     */
    private static void applyMovementLock(PokemonEntity boss) {
        CobbleRaidsConfig.BossMovement config = CobbleRaidsConfigManager.get().bossMovement();
        if (config.slownessEnabled()) {
            boss.addEffect(new MobEffectInstance(
                    MobEffects.MOVEMENT_SLOWDOWN,
                    MobEffectInstance.INFINITE_DURATION,
                    config.slownessAmplifier(),
                    false,
                    false,
                    false));
        }
        if (config.preventKnockback()) {
            AttributeInstance knockbackResistance = boss.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
            if (knockbackResistance != null) knockbackResistance.setBaseValue(1.0);
        }
    }
}
