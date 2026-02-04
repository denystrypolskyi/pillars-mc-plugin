package org.example.pillars.managers;

import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;

public class HudManager {

    private static final int FADE_IN = 5;
    private static final int FADE_OUT = 5;

    private static final int SHORT_STAY = 25;
    private static final int MEDIUM_STAY = 30;
    private static final int LONG_STAY = 50;

    public void updateScoreboard(Player player, int activePlayers, int maxPlayers, String arenaName, int kills, int wins) {

        ScoreboardManager manager = player.getServer().getScoreboardManager();
        Scoreboard board = manager.getNewScoreboard();

        Objective obj = board.registerNewObjective("pillarshud", "dummy", "§6§lСТОЛБЫ");
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        int score = 9;
        obj.getScore("§7").setScore(score--);
        obj.getScore("§b§lИНФОРМАЦИЯ").setScore(score--);
        obj.getScore("§a👤 §fИгрок: §a" + player.getName()).setScore(score--);
        obj.getScore("§a⬤ §fОнлайн: §a" + activePlayers + "§7/§a" + maxPlayers).setScore(score--);
        obj.getScore("§a⚔ §fЛобби: §a" + arenaName).setScore(score--);
        obj.getScore("§8").setScore(score--);
        obj.getScore("§b§lСТАТИСТИКА").setScore(score--);
        obj.getScore("§c☠ §fУбийства: §c" + kills).setScore(score--);
        obj.getScore("§a★ §fПобеды: §a" + wins).setScore(score--);
        obj.getScore("§9").setScore(score--);

        player.setScoreboard(board);
    }

    public void resetScoreboard(Player player) {
        player.setScoreboard(player.getServer().getScoreboardManager().getNewScoreboard());
    }

    public void sendReturnToLobbyTitle(Player player, int seconds) {
        player.sendTitle("§eВозвращение в лобби", "§fчерез §a" + seconds + " §fсек.", 0, MEDIUM_STAY, 0);
    }

    public void sendWinnerTitle(Player player, String winnerName) {
        player.sendTitle("§6§lПОБЕДИТЕЛЬ", "§e" + winnerName + " §7одержал победу!", FADE_IN, LONG_STAY, FADE_OUT);
    }

    public void sendCountdownTitle(Player player, int secondsLeft) {
        player.sendTitle("§6§l" + secondsLeft, "§fДо начала игры", FADE_IN, MEDIUM_STAY, FADE_OUT);
    }

    public void sendGameStartTitle(Player player) {
        player.sendTitle("§aИгра началась!", "§fУдачи!", FADE_IN, LONG_STAY, FADE_OUT);
    }

    public void sendArenaResettingTitle(Player player) {
        player.sendTitle("§6§lАРЕНА", "§eПерезагружается... §7Подожди немного 🙂", FADE_IN, MEDIUM_STAY, FADE_OUT);
    }

    public void sendGameAlreadyStartedTitle(Player player) {
        player.sendTitle("§c§lОШИБКА", "§fИгра уже началась!", FADE_IN, SHORT_STAY, FADE_OUT);
    }

    public void sendItemCooldown(Player player, int secondsLeft) {
        player.sendActionBar("§eВыдача предмета через §a" + secondsLeft + "§e секунд");
    }

    public void sendNotEnoughPlayersTitle(Player player) {
        player.sendTitle("§cНедостаточно игроков!", "§fИгра остановлена.", FADE_IN, MEDIUM_STAY, FADE_OUT);
    }

    public void sendSpectatorTitle(Player player) {
        player.sendTitle("§cВы проиграли", "§7Вы выбыли из игры", FADE_IN, MEDIUM_STAY, FADE_OUT);
    }

    public void sendArenaNotFound(Player player) {
        player.sendMessage("§cАрена не найдена!");
    }

    public void sendLeftArena(Player player) {
        player.sendMessage("§eВы покинули арену.");
    }

    public void sendNotInGame(Player player) {
        player.sendMessage("§cВы не находитесь в игре.");
    }

    public void sendGameAlreadyRunning(Player player) {
        player.sendMessage("§cИгра уже запущена.");
    }

    public void sendForceStartSuccess(Player player) {
        player.sendMessage("§aИгра принудительно запущена!");
    }

    public void sendNoWinnerTitle(Player player) {
        player.sendTitle(
                "§c§lНЕТ ПОБЕДИТЕЛЯ",
                "§7Все игроки выбыли из игры",
                FADE_IN,
                LONG_STAY,
                FADE_OUT
        );
    }
}
