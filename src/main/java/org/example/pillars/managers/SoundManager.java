package org.example.pillars.managers;

import org.bukkit.Sound;
import org.bukkit.entity.Player;

public class SoundManager {

    private static final float DEFAULT_VOLUME = 0.3f;
    private static final float POSITIVE_PITCH = 1.2f;

    public void playItemGivenSound(Player player) {
        player.playSound(
                player.getLocation(),
                Sound.ENTITY_ITEM_PICKUP,
                DEFAULT_VOLUME,
                POSITIVE_PITCH
        );
    }

    public void playErrorSound(Player player) {
        player.playSound(
                player.getLocation(),
                Sound.BLOCK_NOTE_BLOCK_BASS,
                DEFAULT_VOLUME,
                0.8f
        );
    }

    public void playSuccessSound(Player player) {
        player.playSound(
                player.getLocation(),
                Sound.UI_TOAST_CHALLENGE_COMPLETE,
                DEFAULT_VOLUME,
                1.0f
        );
    }
}
