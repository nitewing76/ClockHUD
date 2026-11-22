package com.sgd.clockhud;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Immutable snapshot of every config-derived value the HUD and the generated resource pack need.
 * Built once on enable/reload so the render loop never touches the config.
 */
public record HudSettings(
        HudManager.Corner corner,
        String worldName,
        int clockSize,
        int clockAscent,
        int textHeight,
        int textAscent,
        String gapSpacer,
        String horizontalSpacer,
        String dayLabel,
        String dayNumberTemplate,
        String packHost,
        int packPort,
        String packPrompt
) {

    public static final int MIN_CLOCK_SIZE = 8;
    public static final int MAX_CLOCK_SIZE = 32;
    public static final int MIN_VERTICAL_OFFSET = -60;

    public static HudSettings from(FileConfiguration cfg) {
        HudManager.Corner corner = HudManager.Corner.fromString(cfg.getString("corner", "TOP_LEFT"));

        // Clock: the clock's vertical center sits 3px above the line, matching the original 16px layout
        // (ascent 11 at size 16). Minecraft rejects ascent > height, which sets the upper offset limit.
        int clockSize = Math.clamp(cfg.getInt("clock-size", 12), MIN_CLOCK_SIZE, MAX_CLOCK_SIZE);
        int baseAscent = (int) Math.round(3 + clockSize / 2.0);
        int verticalOffset = Math.clamp(cfg.getInt("vertical-offset", -5), MIN_VERTICAL_OFFSET, clockSize - baseAscent);
        int clockAscent = baseAscent + verticalOffset;

        // Text: same maths as before, generalized from the fixed 16px clock to clock-size.
        double fontScale = Math.clamp(cfg.getDouble("font-scale", 1.00), 0.50, 2.50);
        int textHeight = (int) Math.round(8.0 * fontScale);
        int textAscent = (int) Math.round((clockAscent - clockSize / 2.0) + (7.0 / 16.0) * textHeight);
        textAscent = Math.min(textHeight, textAscent);

        int clockTextOffset = cfg.getInt("clock-text-offset", 4);
        int horizontalOffset = Math.max(0, cfg.getInt("horizontal-offset", 890));

        return new HudSettings(
                corner,
                cfg.getString("world", "world"),
                clockSize,
                clockAscent,
                textHeight,
                textAscent,
                clockTextOffset == 0 ? "" : buildPixelSpacer(clockTextOffset),
                horizontalOffset == 0 ? "" : buildPixelSpacer(horizontalOffset),
                cfg.getString("display.day-label", "&eDay:    "),
                cfg.getString("display.day-number", "&f{day}"),
                cfg.getString("resource-pack.host", ""),
                Math.clamp(cfg.getInt("resource-pack.port", 8164), 1, 65535),
                cfg.getString("resource-pack.prompt", "&eClockHUD needs a small resource pack to show the clock.")
        );
    }

    /** Encodes a pixel advance using the space glyphs defined in the clockhud:hud font. */
    private static String buildPixelSpacer(int pixels) {
        StringBuilder sb = new StringBuilder();
        if (pixels < 0) {
            sb.repeat('', -pixels);
            return sb.toString();
        }
        int remaining = pixels;
        while (remaining >= 512) {
            sb.append('');
            remaining -= 512;
        }
        int[] values = {256, 128, 64, 32, 16, 8, 4, 2, 1};
        char[] glyphs = {'', '', '', '', '', '', '', '', ''};
        for (int i = 0; i < values.length; i++) {
            if (remaining >= values[i]) {
                sb.append(glyphs[i]);
                remaining -= values[i];
            }
        }
        return sb.toString();
    }
}
