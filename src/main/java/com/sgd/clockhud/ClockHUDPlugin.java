package com.sgd.clockhud;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class ClockHUDPlugin extends JavaPlugin {

    public static final LegacyComponentSerializer AMPERSAND = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();

    private HudManager hudManager;
    private ResourcePackService packService;
    private HudSettings settings;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.settings = HudSettings.from(getConfig());
        this.packService = new ResourcePackService(this);
        this.packService.apply(this.settings);

        this.hudManager = new HudManager(this);
        getServer().getPluginManager().registerEvents(this.hudManager, this);

        PluginCommand command = getCommand("clockhud");
        if (command != null) {
            ClockHudCommand executor = new ClockHudCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        if (isPluginEnabled()) {
            this.hudManager.start(this.settings);
        }
    }

    @Override
    public void onDisable() {
        if (this.hudManager != null) {
            this.hudManager.stop();
        }
        if (this.packService != null) {
            this.packService.shutdown();
        }
    }

    public boolean isPluginEnabled() {
        return getConfig().getBoolean("enabled", true);
    }

    public ResourcePackService getPackService() {
        return this.packService;
    }

    public void togglePluginState() {
        boolean nextState = !isPluginEnabled();
        getConfig().set("enabled", nextState);
        saveConfig();

        if (nextState) {
            this.hudManager.start(this.settings);
        } else {
            this.hudManager.stop();
        }
    }

    public void reloadPlugin() {
        reloadConfig();
        this.hudManager.stop();
        this.settings = HudSettings.from(getConfig());
        this.packService.apply(this.settings); // players get the new pack only if its content changed
        if (isPluginEnabled()) {
            this.hudManager.start(this.settings);
        }
    }
}
