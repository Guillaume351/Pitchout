package com.cookiebuild.pitchout;

import java.text.MessageFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import com.cookiebuild.cookiedough.game.Game;
import com.cookiebuild.cookiedough.game.GameManager;
import com.cookiebuild.cookiedough.game.GameState;
import com.cookiebuild.cookiedough.game.BukkitArenaPreparationScheduler;
import com.cookiebuild.cookiedough.game.StandbyArenaService;
import com.cookiebuild.cookiedough.game.StandbyRefillPolicy;
import com.cookiebuild.cookiedough.player.CookiePlayer;
import com.cookiebuild.cookiedough.player.PlayerManager;
import com.cookiebuild.cookiedough.player.PlayerState;
import com.cookiebuild.cookiedough.utils.LocaleManager;
import com.cookiebuild.pitchout.game.PitchoutGame;
import com.cookiebuild.pitchout.listeners.InGamePlayerListener;
import com.cookiebuild.pitchout.map.MapManager;
import com.cookiebuild.pitchout.ui.PitchoutBedrockForms;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import com.cookiebuild.cookiedough.ui.MenuLore;
import net.kyori.adventure.text.format.TextDecoration;

public final class Pitchout extends JavaPlugin {
    private static final Map<String, String> ENGLISH_FALLBACKS = Map.ofEntries(
            Map.entry("pitchout.vote.prompt", "Vote for the next arena: {0}"),
            Map.entry("pitchout.vote.recorded", "Your vote for {0} will help choose the next Pitchout arena."),
            Map.entry("pitchout.vote.invalid_map", "Unknown Pitchout map. Available maps: {0}"),
            Map.entry("pitchout.vote.unavailable", "Map voting is only available while waiting for or playing Pitchout."),
            Map.entry("pitchout.vote.form.title", "Next Pitchout arena"),
            Map.entry("pitchout.vote.form.content", "Choose one arena. Your latest vote replaces the previous one."),
            Map.entry("pitchout.form.close", "Close"),
            Map.entry("pitchout.vote.option_hover", "Vote for {0}"),
            Map.entry("pitchout.vote.winner", "Next arena selected: {0}"),
            Map.entry("pitchout.replay.ready", "Ready for another round?"),
            Map.entry("pitchout.replay.action", "Play Pitchout again"),
            Map.entry("pitchout.replay.hover", "Join the next Pitchout match"),
            Map.entry("pitchout.progress", "Level {0} • {1}/{2} XP"),
            Map.entry("pitchout.contribution", "Your match: {0} eliminations • {1} knockbacks • best combo {2}"),
            Map.entry("pitchout.feedback.action", "Share feedback"),
            Map.entry("pitchout.feedback.hover", "Tell us what would make Pitchout better"),
            Map.entry("pitchout.join.preparing", "Pitchout is preparing a new arena. Try again in a moment."),
            Map.entry("pitchout.join.return_lobby", "Return to the lobby before joining another game."));
    private static Pitchout instance;
    private NamespacedKey replayHookKey;
    private StandbyArenaService<MapManager.PreparedMap, PitchoutGame> arenas;
    private boolean shuttingDown;

    public static Pitchout getInstance() {
        return instance;
    }

    @Override
    public void onLoad() {
        instance = this;
    }

    public static boolean registerNewGame() {
        return instance != null && !instance.shuttingDown && instance.arenas.request(0L);
    }

    public static void activateNextGame() {
        if (instance == null || instance.shuttingDown) return;
        instance.arenas.activateNext();
    }

    public static void requestStandbyRefill() {
        if (instance != null && !instance.shuttingDown) {
            instance.arenas.request(StandbyRefillPolicy.RUNTIME_DELAY_TICKS);
        }
    }

    private MapManager.PreparedMap planArena() {
        try {
            return MapManager.plan(UUID.randomUUID(), MapManager.selectMapForNextGame());
        } catch (java.io.IOException error) {
            throw new CompletionException(error);
        }
    }

    private static MapManager.PreparedMap prepareArenaIo(MapManager.PreparedMap plan) {
        try {
            return MapManager.prepareIo(plan);
        } catch (java.io.IOException error) {
            throw new CompletionException(error);
        }
    }

    private static PitchoutGame loadArena(MapManager.PreparedMap prepared) {
        try {
            var map = MapManager.loadPrepared(prepared);
            try {
                PitchoutGame game = new PitchoutGame(prepared.gameId(), map);
                if (prepared.selectedByVote()) announceMapVoteWinner(prepared.mapName());
                return game;
            } catch (RuntimeException error) {
                if (!MapManager.discardLoadedWorld(prepared.gameId())) {
                    Pitchout.getInstance().getLogger().warning(
                            "Could not unload partially constructed Pitchout arena " + prepared.gameId());
                }
                throw error;
            }
        } catch (java.io.IOException error) {
            throw new CompletionException(error);
        }
    }

    @Override
    public void onEnable() {
        instance = this;
        replayHookKey = new NamespacedKey(this, "quick_play");

        // Initialize maps
        this.getLogger().info("Pitchout plugin enabled!");

        saveDefaultConfig();

        MapManager.loadMapTemplates();
        MapManager.inGamePlayerListener = new InGamePlayerListener();
        arenas = new StandbyArenaService<>(
                "Pitchout", new BukkitArenaPreparationScheduler(this), this::planArena,
                Pitchout::prepareArenaIo, Pitchout::loadArena, MapManager::discardPrepared,
                () -> GameManager.getGames().stream().filter(PitchoutGame.class::isInstance)
                        .anyMatch(game -> game.getState() == GameState.OPEN),
                GameManager::addGame, PitchoutGame::shutdown, getLogger(), false);
        if (!registerNewGame()) {
            getLogger().warning("No Pitchout game registered; selectors remain fail-closed.");
        }

        Bukkit.getPluginManager().registerEvents(MapManager.inGamePlayerListener, this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("pitchout") || !(sender instanceof Player player)) {
            return false;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("vote")) {
            if (args.length < 2) {
                sendMapVotePrompt(player);
            } else {
                voteForNextMap(player, args[1]);
            }
            return true;
        }
        joinOpenGame(player);
        return true;
    }

    public static boolean joinOpenGame(Player player) {
        CookiePlayer cookiePlayer = PlayerManager.getPlayer(player);
        if (cookiePlayer == null || cookiePlayer.getState() != PlayerState.LOBBY) {
            player.sendMessage(Component.text(message(player, "pitchout.join.return_lobby"),
                    NamedTextColor.YELLOW));
            return false;
        }

        Game game = GameManager.getOpenGameByName("Pitchout");
        if (game == null) {
            player.sendMessage(Component.text(message(player, "pitchout.join.preparing"),
                    NamedTextColor.YELLOW));
            return false;
        }
        return game.addPlayerToAvailableTeam(cookiePlayer);
    }

    public static void giveReplayHook(Player player) {
        if (instance == null || instance.replayHookKey == null || !player.isOnline()) {
            return;
        }

        ItemStack replayItem = new ItemStack(Material.COMPASS);
        ItemMeta meta = replayItem.getItemMeta();
        meta.displayName(Component.text(message(player, "pitchout.replay.action"),
                NamedTextColor.GOLD, TextDecoration.BOLD));
        meta.lore(List.of(MenuLore.detail(message(player, "pitchout.replay.hover"))));
        meta.getPersistentDataContainer().set(instance.replayHookKey, PersistentDataType.BYTE, (byte) 1);
        replayItem.setItemMeta(meta);
        player.getInventory().setItem(4, replayItem);

        Component quickPlay = Component.text("[" + message(player, "pitchout.replay.action") + "]",
                NamedTextColor.GREEN, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/pitchout play"))
                .hoverEvent(HoverEvent.showText(Component.text(message(player, "pitchout.replay.hover"))));
        Component feedback = Component.text("[" + message(player, "pitchout.feedback.action") + "]",
                NamedTextColor.AQUA)
                .clickEvent(ClickEvent.openUrl("https://www.cookie-build.com"))
                .hoverEvent(HoverEvent.showText(Component.text(message(player, "pitchout.feedback.hover"))));
        player.sendMessage(Component.text(message(player, "pitchout.replay.ready") + " ", NamedTextColor.GRAY)
                .append(quickPlay)
                .append(Component.text("  •  ", NamedTextColor.DARK_GRAY))
                .append(feedback));
    }

    public static boolean isReplayHook(ItemStack item) {
        return instance != null
                && instance.replayHookKey != null
                && item != null
                && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(
                        instance.replayHookKey, PersistentDataType.BYTE);
    }

    public static void sendMapVotePrompt(Player player) {
        List<String> maps = MapManager.getEligibleNextMapNames();
        if (maps.isEmpty()) {
            return;
        }
        if (PitchoutBedrockForms.openMapVote(player, maps, map -> voteForNextMap(player, map))) return;
        Component prompt = Component.text(
                message(player, "pitchout.vote.prompt", maps.stream()
                        .map(map -> MapManager.getDisplayName(map, player.locale()))
                        .collect(java.util.stream.Collectors.joining(", "))) + " ",
                NamedTextColor.YELLOW);
        for (String mapName : maps) {
            String displayName = MapManager.getDisplayName(mapName, player.locale());
            prompt = prompt.append(Component.text("[" + displayName + "] ", NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.runCommand("/pitchout vote " + mapName))
                    .hoverEvent(HoverEvent.showText(Component.text(
                            message(player, "pitchout.vote.option_hover", displayName)))));
        }
        player.sendMessage(prompt);
    }

    public static void announceMapVoteWinner(String mapName) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            CookiePlayer cookiePlayer = PlayerManager.getPlayer(player);
            if (cookiePlayer != null && GameManager.getGameOfPlayer(cookiePlayer) instanceof PitchoutGame) {
                player.sendMessage(Component.text(
                        message(player, "pitchout.vote.winner",
                                MapManager.getDisplayName(mapName, player.locale())), NamedTextColor.GREEN));
            }
        }
    }

    private static void voteForNextMap(Player player, String requestedMap) {
        CookiePlayer cookiePlayer = PlayerManager.getPlayer(player);
        if (cookiePlayer == null || !(GameManager.getGameOfPlayer(cookiePlayer) instanceof PitchoutGame)) {
            player.sendMessage(Component.text(message(player, "pitchout.vote.unavailable"), NamedTextColor.YELLOW));
            return;
        }
        String selectedMap = MapManager.recordNextMapVote(player.getUniqueId(), requestedMap);
        if (selectedMap == null) {
            player.sendMessage(Component.text(message(
                    player,
                    "pitchout.vote.invalid_map",
                    MapManager.getEligibleNextMapNames().stream()
                            .map(map -> MapManager.getDisplayName(map, player.locale()))
                            .collect(java.util.stream.Collectors.joining(", "))), NamedTextColor.RED));
            return;
        }
        player.sendMessage(Component.text(
                message(player, "pitchout.vote.recorded",
                        MapManager.getDisplayName(selectedMap, player.locale())), NamedTextColor.GREEN));
    }

    public static boolean openReplayForm(Player player) {
        return PitchoutBedrockForms.openReplay(player, () -> joinOpenGame(player));
    }

    public static String message(Player player, String key, Object... arguments) {
        String localized = LocaleManager.getMessage(key, player.locale(), arguments);
        if (!localized.equals(key)) {
            return localized;
        }
        return MessageFormat.format(ENGLISH_FALLBACKS.getOrDefault(key, key), arguments);
    }

    @Override
    public void onDisable() {
        shuttingDown = true;
        if (arenas != null) arenas.shutdown();
        for (Game game : new java.util.ArrayList<>(GameManager.getGames())) {
            if (game instanceof PitchoutGame pitchoutGame) {
                pitchoutGame.shutdown();
            }
        }
        if (!MapManager.unloadAllMaps()) {
            this.getLogger().warning("Some Pitchout map directories could not be cleaned up during shutdown");
        }
        this.getLogger().info("Pitchout plugin disabled!");
        instance = null;
    }
}
