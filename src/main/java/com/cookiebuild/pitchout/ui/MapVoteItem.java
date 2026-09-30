package com.cookiebuild.pitchout.ui;

import java.util.List;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import com.cookiebuild.pitchout.Pitchout;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Waiting-area hotbar item that opens the next-arena vote (Bedrock form or Java chest menu).
 * It only exists while waiting: the match start resets the inventory before giving weapons.
 */
public final class MapVoteItem {
    public static final int SLOT = 4;
    static final String MARKER_KEY = "map_vote_item";
    static final long OPEN_COOLDOWN_MILLIS = 1_000L;

    private MapVoteItem() { }

    public static void give(Player player) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(name(player), NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text(Pitchout.message(player, "pitchout.vote.item.lore"), NamedTextColor.GRAY),
                Component.text(Pitchout.message(player, "pitchout.vote.item.action"), NamedTextColor.YELLOW)));
        meta.getPersistentDataContainer().set(markerKey(), PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        player.getInventory().setItem(SLOT, stack);
        player.getInventory().setHeldItemSlot(SLOT);
    }

    public static String name(Player player) {
        return Pitchout.message(player, "pitchout.vote.item.name");
    }

    public static boolean isVoteItem(ItemStack stack) {
        Pitchout plugin = Pitchout.getInstance();
        return plugin != null && stack != null && stack.hasItemMeta()
                && stack.getItemMeta().getPersistentDataContainer().has(markerKey(), PersistentDataType.BYTE);
    }

    private static NamespacedKey markerKey() {
        return new NamespacedKey(Pitchout.getInstance(), MARKER_KEY);
    }

    /** Any click opens the vote: Bedrock touch taps arrive as left clicks, long presses as right clicks. */
    public static boolean opensVote(Action action) {
        return action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK
                || action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;
    }

    /** The item must never leave its slot through the cursor, a hotbar-number swap, or shift-click. */
    public static boolean shouldCancelInventoryClick(boolean currentIsVoteItem, boolean cursorIsVoteItem,
            boolean hotbarButtonIsVoteItem) {
        return currentIsVoteItem || cursorIsVoteItem || hotbarButtonIsVoteItem;
    }

    /** Bedrock can emit both a swing and a use for one tap; open at most one form per second. */
    public static boolean canOpen(Long lastOpenedAtMillis, long nowMillis) {
        return lastOpenedAtMillis == null || nowMillis - lastOpenedAtMillis >= OPEN_COOLDOWN_MILLIS
                || nowMillis < lastOpenedAtMillis;
    }
}
