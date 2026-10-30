package com.cobbleraids.radar;

import com.cobbleraids.charges.RaidChargeAccount;
import com.cobbleraids.charges.RaidChargeStore;
import com.cobbleraids.item.RaidRadarItem;
import com.cobbleraids.presentation.CommandFormat;
import java.util.Arrays;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/** Vanilla inventory UI: no client mod, custom screen or player-authoritative charge packets. */
public final class RaidRadarMenu extends ChestMenu {
    private final SimpleContainer display;
    private final ServerPlayer owner;
    private final InteractionHand hand;
    private int refreshTicks;
    public static void open(ServerPlayer player, InteractionHand hand) {
        player.openMenu(new SimpleMenuProvider((id,inventory,ignored) -> new RaidRadarMenu(id,player,hand), Component.literal("Raid Radar - "+RaidChargeStore.status(player).available()+"/3")));
    }
    private RaidRadarMenu(int id,ServerPlayer player,InteractionHand hand) {
        this(id,player,hand,new SimpleContainer(27));
    }
    private RaidRadarMenu(int id,ServerPlayer player,InteractionHand hand,SimpleContainer display) {
        super(MenuType.GENERIC_9x3,id,player.getInventory(),display,3);
        this.display=display;this.owner=player;this.hand=hand;
        for(int i=0;i<27;i++) display.setItem(i,icon(Items.GRAY_STAINED_GLASS_PANE," ",ChatFormatting.DARK_GRAY));
        refresh();
    }
    private void refresh() {
        long now=System.currentTimeMillis();
        var account=RaidChargeStore.get(owner.server).status(owner.getUUID(),now);
        display.setItem(4,icon(account.available()>0?Items.EMERALD:Items.REDSTONE,
                "Raid charges: "+account.available()+" / "+RaidChargeAccount.CAPACITY,
                account.available()>0?ChatFormatting.GREEN:ChatFormatting.RED,
                account.available()>0?"Ready to join a raid":"No charges remaining",
                "One charge is used when your battle starts."));
        for(int i=0;i<RaidChargeAccount.CAPACITY;i++) display.setItem(10+i,
                icon(i<account.available()?Items.AMETHYST_SHARD:Items.GRAY_DYE,"Charge "+(i+1)+": "+(i<account.available()?"Ready":"Recharging"),
                        i<account.available()?ChatFormatting.AQUA:ChatFormatting.GRAY));
        display.setItem(14,icon(Items.CLOCK,account.available()==RaidChargeAccount.CAPACITY?"Fully charged":"Next charge in "+CommandFormat.duration(account.secondsToNext(now)),
                ChatFormatting.GOLD,account.available()==RaidChargeAccount.CAPACITY?"You have all 3 raid charges.":"Full in "+CommandFormat.duration(account.secondsToFull(now)),
                "Refills 1 charge per hour, up to 3.","Refills while you are offline, too."));
        display.setItem(16,icon(Items.BOOK,"How raid charges work",ChatFormatting.AQUA,
                "Charges belong to your player UUID.","Crafting another radar does not reset them.",
                "Joining a lobby costs nothing.","Cancelled lobbies cost nothing.","Failed battle starts are refunded.","A started battle costs 1 charge, win or lose.","Owned encounters use their own entry rules."));
        boolean open=RaidRadarItem.isOpen(owner.getItemInHand(hand));
        display.setItem(22,icon(Items.COMPASS,open?"Close hologram / map":"Open hologram / map",ChatFormatting.AQUA,
                "3D with the optional companion.","Flat radar map without it."));
        display.setItem(24,icon(Items.SPECTRAL_ARROW,"Next raid",ChatFormatting.GOLD,"Cycle the radar's target.","Opens the radar if it is closed."));
        display.setItem(26,icon(Items.BARRIER,"Close menu",ChatFormatting.RED));
    }
    private static ItemStack icon(Item item,String title,ChatFormatting color,String... lore) {
        ItemStack stack=new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME,Component.literal(title).withStyle(color).withStyle(s -> s.withItalic(false)));
        if(lore.length>0) stack.set(DataComponents.LORE,new ItemLore(Arrays.stream(lore)
                .map(line -> (Component)Component.literal(line).withStyle(ChatFormatting.GRAY).withStyle(s -> s.withItalic(false))).toList()));
        return stack;
    }
    @Override public void broadcastChanges() {
        if(++refreshTicks>=20) {refreshTicks=0;refresh();}
        super.broadcastChanges();
    }
    @Override public boolean stillValid(Player player) { return player==owner && owner.isAlive() && owner.getItemInHand(hand).is(RaidRadarItem.INSTANCE); }
    @Override public ItemStack quickMoveStack(Player player,int slot) { return ItemStack.EMPTY; }
    @Override public void clicked(int slot,int button,ClickType type,Player player) {
        // No calls to super: menu icons and player inventory cannot be moved, thrown or cloned.
        if(!stillValid(player) || type!=ClickType.PICKUP || (button!=0 && button!=1)) return;
        if(slot==22) { owner.closeContainer(); RaidRadarItem.toggle(owner,hand); }
        else if(slot==24) {
            owner.closeContainer();
            if(!RaidRadarItem.isOpen(owner.getItemInHand(hand))) RaidRadarItem.toggle(owner,hand);
            RaidRadarService.nextTarget(owner);
        } else if(slot==26) owner.closeContainer();
    }
}
