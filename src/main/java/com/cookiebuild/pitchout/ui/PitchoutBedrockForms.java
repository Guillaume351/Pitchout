package com.cookiebuild.pitchout.ui;

import java.util.List;
import java.util.function.Consumer;

import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.SimpleForm;

import com.cookiebuild.cookiedough.ui.BedrockFormImages;
import com.cookiebuild.cookiedough.ui.BedrockFormSupport;
import com.cookiebuild.cookiedough.ui.MainThreadPlayerAction;
import com.cookiebuild.pitchout.Pitchout;
import com.cookiebuild.pitchout.map.MapManager;

/** Bedrock presentation over the same guarded map-vote and replay actions as chat/hotbar. */
public final class PitchoutBedrockForms {
    private PitchoutBedrockForms() { }

    public static boolean openMapVote(Player player, List<String> maps, Consumer<String> voteHandler) {
        if (!BedrockFormSupport.isBedrock(player)) return false;
        Pitchout plugin = Pitchout.getInstance();
        if (plugin == null || !plugin.isEnabled()) return false;
        List<String> options = List.copyOf(maps);
        SimpleForm.Builder form = SimpleForm.builder()
                .title("§l§6" + Pitchout.message(player, "pitchout.vote.form.title"))
                .content("§7" + Pitchout.message(player, "pitchout.vote.form.content"));
        options.forEach(map -> BedrockFormImages.button(form, "§f§l" + MapManager.getDisplayName(map),
                "modes/pitchout"));
        BedrockFormImages.button(form, "§c§l" + Pitchout.message(player, "pitchout.form.close"), "actions/close");
        form.validResultHandler(response -> {
            int index = response.getClickedButtonId();
            MainThreadPlayerAction.dispatch(plugin, player, () -> {
                if (index >= 0 && index < options.size()) voteHandler.accept(options.get(index));
            });
        });
        return BedrockFormSupport.send(player, form.build());
    }

    public static boolean openReplay(Player player, Runnable replayHandler) {
        if (!BedrockFormSupport.isBedrock(player)) return false;
        Pitchout plugin = Pitchout.getInstance();
        if (plugin == null || !plugin.isEnabled()) return false;
        SimpleForm.Builder form = SimpleForm.builder()
                .title("§l§6" + Pitchout.message(player, "pitchout.replay.ready"))
                .content("§7" + Pitchout.message(player, "pitchout.replay.hover"));
        BedrockFormImages.button(form, "§a§l" + Pitchout.message(player, "pitchout.replay.action"),
                "actions/join");
        BedrockFormImages.button(form, "§c§l" + Pitchout.message(player, "pitchout.form.close"), "actions/close");
        form.validResultHandler(response -> {
            int index = response.getClickedButtonId();
            MainThreadPlayerAction.dispatch(plugin, player, () -> {
                if (index == 0) replayHandler.run();
            });
        });
        return BedrockFormSupport.send(player, form.build());
    }
}
