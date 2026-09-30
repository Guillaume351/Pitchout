package com.cookiebuild.pitchout.ui;

import java.util.List;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.cookiebuild.pitchout.Pitchout;
import com.cookiebuild.pitchout.map.MapManager;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/** Java presentation of the next-arena vote: one chest slot per eligible map plus a close button. */
public final class PitchoutJavaVoteMenu implements InventoryHolder {
    private static final int MAX_SIZE = 54;
    private final List<String> options;
    private final Consumer<String> voteHandler;
    private Inventory inventory;

    private PitchoutJavaVoteMenu(List<String> options, Consumer<String> voteHandler) {
        this.options = List.copyOf(options);
        this.voteHandler = voteHandler;
    }

    public static void open(Player player, List<String> maps, Consumer<String> voteHandler) {
        PitchoutJavaVoteMenu menu = new PitchoutJavaVoteMenu(maps, voteHandler);
        int size = sizeFor(menu.options.size());
        menu.inventory = Bukkit.createInventory(menu, size,
                Component.text(Pitchout.message(player, "pitchout.vote.form.title")));
        for (int slot = 0; slot < size - 1 && slot < menu.options.size(); slot++) {
            menu.inventory.setItem(slot, button(Material.MAP,
                    MapManager.getDisplayName(menu.options.get(slot), player.locale()), NamedTextColor.AQUA));
        }
        menu.inventory.setItem(size - 1, button(Material.BARRIER,
                Pitchout.message(player, "pitchout.form.close"), NamedTextColor.RED));
        player.openInventory(menu.inventory);
    }

    /** Handles a click in the menu's own inventory; every click in the menu view is cancelled by the caller. */
    public void click(Player player, int rawSlot) {
        String map = optionAt(options, rawSlot, inventory.getSize());
        player.closeInventory();
        if (map != null) voteHandler.accept(map);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    static int sizeFor(int optionCount) {
        int slots = Math.max(1, optionCount) + 1;
        return Math.min(MAX_SIZE, ((slots + 8) / 9) * 9);
    }

    static String optionAt(List<String> options, int rawSlot, int size) {
        return rawSlot >= 0 && rawSlot < size - 1 && rawSlot < options.size() ? options.get(rawSlot) : null;
    }

    private static ItemStack button(Material material, String label, NamedTextColor color) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(label, color).decoration(TextDecoration.ITALIC, false));
        stack.setItemMeta(meta);
        return stack;
    }
}
