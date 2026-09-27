package com.cobbleraids.pokemon;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.server.level.ServerPlayer;

/**
 * Party-then-PC delivery, shared by every feature that hands a player a Pokemon outside a battle.
 *
 * <p>Three call sites duplicated this exact shape before it was pulled out here: check for room
 * before spending anything, prefer the party, fall back to the PC, and treat "both full" -- a race
 * between that check and the add itself, since {@link #isFull} is only ever checked beforehand as a
 * guard against spending on a roll or a purchase nobody could keep -- as its own outcome rather than
 * a silently dropped Pokemon.
 */
public final class PartyPcDelivery {
    private PartyPcDelivery() {}

    public enum Outcome { DELIVERED_PARTY, DELIVERED_PC, NO_ROOM }

    /** True when neither the party nor the PC currently has a free slot. Check before spending. */
    public static boolean isFull(ServerPlayer player) {
        var storage = Cobblemon.INSTANCE.getStorage();
        return storage.getParty(player).getFirstAvailablePosition() == null
                && storage.getPC(player).getFirstAvailablePosition() == null;
    }

    /**
     * Delivers {@code pokemon} to the player's party, falling back to the PC. Callers that already
     * checked {@link #isFull} beforehand only ever see {@link Outcome#NO_ROOM} from the residual race
     * between that check and this call -- not from an unchecked caller skipping the guard.
     */
    public static Outcome tryDeliver(Pokemon pokemon, ServerPlayer player) {
        var storage = Cobblemon.INSTANCE.getStorage();
        PlayerPartyStore party = storage.getParty(player);
        boolean partyHasRoom = party.getFirstAvailablePosition() != null;
        if (partyHasRoom && party.add(pokemon)) return Outcome.DELIVERED_PARTY;
        if (storage.getPC(player).add(pokemon)) return Outcome.DELIVERED_PC;
        return Outcome.NO_ROOM;
    }
}
