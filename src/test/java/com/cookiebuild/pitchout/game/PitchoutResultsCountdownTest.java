package com.cookiebuild.pitchout.game;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.cookiebuild.cookiedough.player.CookiePlayer;
import com.cookiebuild.cookiedough.utils.LocaleManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class PitchoutResultsCountdownTest {
    @Test
    void countdownPreservesOutcomeTitlesAndIncludesExternalSpectators() {
        CookiePlayer participant = viewer(true);
        CookiePlayer spectator = viewer(true);
        PitchoutGame game = game(List.of(participant), List.of(spectator));

        game.showReturnCountdown(10);

        Component countdown = Component.text(LocaleManager.getMessage(
                "game.teleport_countdown", Locale.ENGLISH, 10), NamedTextColor.YELLOW);
        for (CookiePlayer viewer : List.of(participant, spectator)) {
            verify(viewer.getPlayer()).sendActionBar(countdown);
            verify(viewer.getPlayer(), never()).showTitle(any(Title.class));
        }
    }

    @Test
    void leavingTheResultsStopsFurtherCountdownMessages() {
        CookiePlayer participant = viewer(true);
        PitchoutGame game = game(List.of(participant), List.of());

        game.showReturnCountdown(10);
        doReturn(List.of()).when(game).getPlayers();
        game.showReturnCountdown(9);

        verify(participant.getPlayer(), times(1)).sendActionBar(any(Component.class));
    }

    @Test
    void offlineViewersAreSkippedAndDuplicateRosterEntriesReceiveOnlyOneMessage() {
        CookiePlayer participant = viewer(true);
        CookiePlayer offline = viewer(false);
        PitchoutGame game = game(List.of(participant, offline), List.of(participant));

        game.showReturnCountdown(3);

        verify(participant.getPlayer(), times(1)).sendActionBar(any(Component.class));
        verify(offline.getPlayer(), never()).sendActionBar(any(Component.class));
    }

    private static PitchoutGame game(List<CookiePlayer> participants, List<CookiePlayer> spectators) {
        PitchoutGame game = mock(PitchoutGame.class, CALLS_REAL_METHODS);
        doReturn(participants).when(game).getPlayers();
        doReturn(spectators).when(game).getSpectators();
        return game;
    }

    private static CookiePlayer viewer(boolean online) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(online);
        when(player.locale()).thenReturn(Locale.ENGLISH);
        CookiePlayer cookiePlayer = mock(CookiePlayer.class);
        when(cookiePlayer.getPlayer()).thenReturn(player);
        return cookiePlayer;
    }
}
