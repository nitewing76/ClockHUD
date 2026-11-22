package com.sgd.clockhud;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBedLeaveEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.event.world.TimeSkipEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class HudManager implements Listener {

    public enum Corner {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT;

        public boolean isTop() {
            return this == TOP_LEFT || this == TOP_RIGHT;
        }

        public boolean isLeft() {
            return this == TOP_LEFT || this == BOTTOM_LEFT;
        }

        public static Corner fromString(String raw) {
            if (raw == null || raw.isBlank()) {
                return TOP_LEFT;
            }
            try {
                return Corner.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return TOP_LEFT;
            }
        }
    }

    private static final Key HUD_FONT = Key.key("clockhud", "hud");
    private static final Key TEXT_FONT = Key.key("clockhud", "text");

    private static final long TICKS_PER_DAY = 24000L;
    private static final long TICKS_PER_FRAME = 375L;          // 24000 / 64 clock frames
    private static final long FIRST_FRAME_BOUNDARY = 6188L;    // where computeClockFrame's rounding flips
    private static final long SLEEP_POLL_TICKS = 4L;           // keeps up with CMI's gradual night skip
    private static final long ACTION_BAR_RESEND_TICKS = 30L;   // well inside the action bar's display time

    private final ClockHUDPlugin plugin;
    private final Set<UUID> sleepingPlayers = new HashSet<>();
    private final Set<UUID> packReadyPlayers = new HashSet<>();
    private final Set<UUID> barViewers = new HashSet<>();
    private final BossBar hudBar = BossBar.bossBar(Component.empty(), 1.0f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);

    private HudSettings settings;
    private BukkitTask checkTask;
    private long lastDay = Long.MIN_VALUE;
    private int lastClockFrame = -1;

    public HudManager(ClockHUDPlugin plugin) {
        this.plugin = plugin;
    }

    public void start(HudSettings settings) {
        stop();
        this.settings = settings;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isSleeping()) {
                this.sleepingPlayers.add(player.getUniqueId());
            }
            plugin.getPackService().send(player, settings);
        }
        if (!this.packReadyPlayers.isEmpty()) {
            refreshAllPlayers(true);
            scheduleNextCheck();
        }
    }

    public void stop() {
        cancelTask();
        for (UUID id : this.barViewers) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                player.hideBossBar(this.hudBar);
            }
        }
        this.barViewers.clear();
        if (this.settings != null && !this.settings.corner().isTop()) {
            for (UUID id : this.packReadyPlayers) {
                Player player = Bukkit.getPlayer(id);
                if (player != null) {
                    player.sendActionBar(Component.empty());
                }
            }
        }
        this.sleepingPlayers.clear();
        this.lastDay = Long.MIN_VALUE;
        this.lastClockFrame = -1;
        // packReadyPlayers is kept: the pack stays loaded on those clients across reloads/toggles.
    }

    private boolean isActive() {
        return this.settings != null && plugin.isPluginEnabled() && !this.packReadyPlayers.isEmpty();
    }

    private void cancelTask() {
        if (this.checkTask != null) {
            this.checkTask.cancel();
            this.checkTask = null;
        }
    }

    private void scheduleNextCheck() {
        World world = getSourceWorld();
        scheduleNextCheck(world != null ? world.getFullTime() : 0L);
    }

    private void scheduleNextCheck(long fullTime) {
        cancelTask();
        if (!isActive()) {
            return;
        }
        long delay;
        if (!this.sleepingPlayers.isEmpty()) {
            delay = SLEEP_POLL_TICKS;
        } else if (!this.settings.corner().isTop()) {
            delay = ACTION_BAR_RESEND_TICKS;
        } else {
            // Wake exactly when the clock frame or the day number next changes.
            long dayTime = Math.floorMod(fullTime, TICKS_PER_DAY);
            long toNextFrame = TICKS_PER_FRAME - Math.floorMod(dayTime - FIRST_FRAME_BOUNDARY, TICKS_PER_FRAME);
            long toMidnight = TICKS_PER_DAY - dayTime;
            delay = Math.max(1L, Math.min(toNextFrame, toMidnight));
        }

        this.checkTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            this.checkTask = null;
            if (!isActive()) {
                return;
            }
            this.sleepingPlayers.removeIf(id -> {
                Player p = Bukkit.getPlayer(id);
                return p == null || !p.isSleeping();
            });
            refreshAllPlayers(!this.settings.corner().isTop());
            scheduleNextCheck();
        }, delay);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerBedEnter(PlayerBedEnterEvent event) {
        if (!plugin.isPluginEnabled() || event.getBedEnterResult() != PlayerBedEnterEvent.BedEnterResult.OK) {
            return;
        }
        this.sleepingPlayers.add(event.getPlayer().getUniqueId());
        scheduleNextCheck();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTimeSkip(TimeSkipEvent event) {
        if (!isActive()) {
            return;
        }
        World sourceWorld = getSourceWorld();
        if (sourceWorld == null || !event.getWorld().equals(sourceWorld)) {
            return;
        }
        long postSkipFullTime = event.getWorld().getFullTime() + event.getSkipAmount();
        refreshAllPlayersWithTime(postSkipFullTime, true);
        scheduleNextCheck(postSkipFullTime);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerBedLeave(PlayerBedLeaveEvent event) {
        this.sleepingPlayers.remove(event.getPlayer().getUniqueId());
        if (!plugin.isPluginEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (isActive()) {
                refreshAllPlayers(false);
                scheduleNextCheck();
            }
        }, 1L);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (plugin.isPluginEnabled() && this.settings != null) {
            plugin.getPackService().send(event.getPlayer(), this.settings);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPackStatus(PlayerResourcePackStatusEvent event) {
        if (!ResourcePackService.PACK_ID.equals(event.getID())) {
            return;
        }
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED -> {
                this.packReadyPlayers.add(uuid);
                if (plugin.isPluginEnabled() && this.settings != null && player.isOnline()) {
                    updatePlayer(player);
                    scheduleNextCheck();
                }
            }
            case ACCEPTED, DOWNLOADED -> {
                // still in progress
            }
            default -> {
                // declined or failed: never show glyphs the client can't render
                this.packReadyPlayers.remove(uuid);
                plugin.getPackService().forget(uuid);
                clearPlayerHud(player);
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        this.sleepingPlayers.remove(uuid);
        this.packReadyPlayers.remove(uuid);
        plugin.getPackService().forget(uuid);
        if (this.barViewers.remove(uuid)) {
            event.getPlayer().hideBossBar(this.hudBar);
        }
        if (this.packReadyPlayers.isEmpty()) {
            cancelTask();
        }
    }

    public void refreshAllPlayers(boolean forceRender) {
        if (!isActive()) {
            return;
        }
        World world = getSourceWorld();
        refreshAllPlayersWithTime(world != null ? world.getFullTime() : 0L, forceRender);
    }

    private void refreshAllPlayersWithTime(long fullTime, boolean forceRender) {
        long currentDay = Math.floorDiv(fullTime, TICKS_PER_DAY);
        int currentFrame = computeClockFrame(fullTime);

        if (!forceRender && currentDay == this.lastDay && currentFrame == this.lastClockFrame) {
            return;
        }
        this.lastDay = currentDay;
        this.lastClockFrame = currentFrame;

        Component hudComponent = buildHudComponent(currentDay, currentFrame);
        if (this.settings.corner().isTop()) {
            this.hudBar.name(hudComponent); // one update reaches every viewer
        }
        for (UUID id : this.packReadyPlayers) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                applyHud(player, hudComponent);
            }
        }
    }

    private void updatePlayer(Player player) {
        World world = getSourceWorld();
        long fullTime = world != null ? world.getFullTime() : 0L;
        long currentDay = Math.floorDiv(fullTime, TICKS_PER_DAY);
        int currentFrame = computeClockFrame(fullTime);
        this.lastDay = currentDay;
        this.lastClockFrame = currentFrame;

        Component hudComponent = buildHudComponent(currentDay, currentFrame);
        if (this.settings.corner().isTop()) {
            this.hudBar.name(hudComponent);
        }
        applyHud(player, hudComponent);
    }

    private int computeClockFrame(long fullTime) {
        long dayTime = Math.floorMod(fullTime, TICKS_PER_DAY);
        return Math.floorMod((int) Math.round(((dayTime - 6000L) / 24000.0) * 64.0), 64);
    }

    private void applyHud(Player player, Component hudComponent) {
        if (this.settings.corner().isTop()) {
            if (this.barViewers.add(player.getUniqueId())) {
                player.showBossBar(this.hudBar);
            }
        } else {
            player.sendActionBar(hudComponent);
        }
    }

    private void clearPlayerHud(Player player) {
        if (this.barViewers.remove(player.getUniqueId())) {
            player.hideBossBar(this.hudBar);
        }
        player.sendActionBar(Component.empty());
    }

    private World getSourceWorld() {
        String worldName = this.settings != null ? this.settings.worldName() : "world";
        if (worldName != null && !worldName.isBlank()) {
            World configured = Bukkit.getWorld(worldName);
            if (configured != null) {
                return configured;
            }
        }
        return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().getFirst();
    }

    private Component buildHudComponent(long dayNumber, int clockFrame) {
        HudSettings s = this.settings;
        Component content = Component.empty()
                .append(Component.text(String.valueOf((char) (0xE000 + clockFrame))).font(HUD_FONT))
                .append(s.gapSpacer().isEmpty() ? Component.empty() : Component.text(s.gapSpacer()).font(HUD_FONT))
                .append(ClockHUDPlugin.AMPERSAND.deserialize(s.dayLabel()).font(TEXT_FONT))
                .append(ClockHUDPlugin.AMPERSAND.deserialize(
                        s.dayNumberTemplate().replace("{day}", String.valueOf(dayNumber))).font(TEXT_FONT));

        if (s.horizontalSpacer().isEmpty()) {
            return content;
        }
        Component spacer = Component.text(s.horizontalSpacer()).font(HUD_FONT);
        return s.corner().isLeft()
                ? Component.empty().append(content).append(spacer)
                : Component.empty().append(spacer).append(content);
    }
}
