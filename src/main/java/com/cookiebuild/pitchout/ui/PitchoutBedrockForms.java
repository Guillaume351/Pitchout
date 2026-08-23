package com.cookiebuild.pitchout.ui;

import java.util.List;
import java.util.function.Consumer;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.SimpleForm;

import com.cookiebuild.cookiedough.ui.BedrockButtonText;
import com.cookiebuild.cookiedough.ui.BedrockFormImages;
import com.cookiebuild.cookiedough.ui.BedrockFormSupport;
import com.cookiebuild.cookiedough.ui.BedrockMenuSessionRegistry;
import com.cookiebuild.cookiedough.ui.MainThreadPlayerAction;
import com.cookiebuild.pitchout.Pitchout;
import com.cookiebuild.pitchout.map.MapManager;

/** Bedrock presentation over the same guarded map-vote and replay actions as chat/hotbar. */
public final class PitchoutBedrockForms {
    static final String MAP_IMAGE = "modes/pitchout";
    static final String REPLAY_IMAGE = "actions/join";
    static final String CLOSE_IMAGE = "actions/close";
    private static final BedrockMenuSessionRegistry SESSIONS = new BedrockMenuSessionRegistry();

    private PitchoutBedrockForms() { }

    public static boolean openMapVote(Player player, List<String> maps, Consumer<String> voteHandler) {
        if (!BedrockFormSupport.isBedrock(player)) return false;
        Pitchout plugin = Pitchout.getInstance();
        if (plugin == null || !plugin.isEnabled()) return false;
        List<String> options = List.copyOf(maps);
        String scope = "pitchout:map_vote:" + options.hashCode();
        UUID nonce = SESSIONS.issue(player.getUniqueId(), scope);
        SimpleForm.Builder form = SimpleForm.builder()
                .title("§l§6" + Pitchout.message(player, "pitchout.vote.form.title"))
                .content(Pitchout.message(player, "pitchout.vote.form.content"));
        options.forEach(map -> BedrockFormImages.button(form,
                BedrockButtonText.format(MapManager.getDisplayName(map)),
                MAP_IMAGE));
        BedrockFormImages.button(form, BedrockButtonText.format(
                Pitchout.message(player, "pitchout.form.close")), CLOSE_IMAGE);
        form.validResultHandler(response -> {
            int index = response.getClickedButtonId();
            MainThreadPlayerAction.dispatch(plugin, player, () -> {
                if (SESSIONS.consume(player.getUniqueId(), nonce, scope)
                        && index >= 0 && index < options.size()) voteHandler.accept(options.get(index));
            });
        });
        form.closedOrInvalidResultHandler(() -> SESSIONS.invalidate(player.getUniqueId(), nonce, scope));
        boolean sent = BedrockFormSupport.send(player, form.build());
        if (!sent) SESSIONS.invalidate(player.getUniqueId(), nonce, scope);
        return sent;
    }

    public static boolean openReplay(Player player, Runnable replayHandler) {
        if (!BedrockFormSupport.isBedrock(player)) return false;
        Pitchout plugin = Pitchout.getInstance();
        if (plugin == null || !plugin.isEnabled()) return false;
        String scope = "pitchout:replay";
        UUID nonce = SESSIONS.issue(player.getUniqueId(), scope);
        SimpleForm.Builder form = SimpleForm.builder()
                .title("§l§6" + Pitchout.message(player, "pitchout.replay.ready"))
                .content(Pitchout.message(player, "pitchout.replay.hover"));
        BedrockFormImages.button(form, BedrockButtonText.format(
                        Pitchout.message(player, "pitchout.replay.action")),
                REPLAY_IMAGE);
        BedrockFormImages.button(form, BedrockButtonText.format(
                Pitchout.message(player, "pitchout.form.close")), CLOSE_IMAGE);
        form.validResultHandler(response -> {
            int index = response.getClickedButtonId();
            MainThreadPlayerAction.dispatch(plugin, player, () -> {
                if (SESSIONS.consume(player.getUniqueId(), nonce, scope) && index == 0) replayHandler.run();
            });
        });
        form.closedOrInvalidResultHandler(() -> SESSIONS.invalidate(player.getUniqueId(), nonce, scope));
        boolean sent = BedrockFormSupport.send(player, form.build());
        if (!sent) SESSIONS.invalidate(player.getUniqueId(), nonce, scope);
        return sent;
    }

    public static void invalidate(Player player) {
        SESSIONS.invalidate(player.getUniqueId());
    }
}
