package com.sgd.clockhud;

import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds a minimal resource pack for the current settings, serves it over a tiny built-in HTTP server,
 * and sends it to players. Only two fonts are generated (clock + text), so the client loads it almost instantly.
 */
public final class ResourcePackService {

    public static final UUID PACK_ID = UUID.nameUUIDFromBytes("ClockHUD:resource-pack".getBytes(StandardCharsets.UTF_8));
    private static final String PACK_PATH = "/clockhud.zip";
    private static final long FIXED_ZIP_TIME = 315532800000L; // keeps the zip (and its SHA-1) identical for identical settings

    private static final String SPACE_PROVIDER = "{\"type\":\"space\",\"advances\":{"
            + "\"\\uf801\":1,\"\\uf802\":2,\"\\uf804\":4,\"\\uf808\":8,\"\\uf810\":16,\"\\uf820\":32,"
            + "\"\\uf840\":64,\"\\uf880\":128,\"\\uf890\":256,\"\\uf8a0\":512,\"\\uf800\":-1}}";

    private final ClockHUDPlugin plugin;
    private final Map<UUID, String> sentHashes = new HashMap<>();

    private HttpServer server;
    private int serverPort = -1;
    private volatile byte[] packBytes;
    private String packHash;
    private URI packUri;

    public ResourcePackService(ClockHUDPlugin plugin) {
        this.plugin = plugin;
    }

    /** Rebuilds the pack from settings and (re)starts the HTTP server if needed. Returns true if the pack content changed. */
    public boolean apply(HudSettings settings) {
        String previousHash = this.packHash;
        try {
            byte[] bytes = buildPack(settings);
            this.packBytes = bytes;
            this.packHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (IOException | NoSuchAlgorithmException ex) {
            plugin.getLogger().log(Level.SEVERE, "Failed to build the ClockHUD resource pack", ex);
            return false;
        }

        if (this.server == null || this.serverPort != settings.packPort()) {
            stopServer();
            try {
                HttpServer created = HttpServer.create(new InetSocketAddress(settings.packPort()), 0);
                created.createContext(PACK_PATH, exchange -> {
                    try (exchange) {
                        byte[] body = this.packBytes;
                        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) || body == null) {
                            exchange.sendResponseHeaders(405, -1);
                            return;
                        }
                        exchange.getResponseHeaders().set("Content-Type", "application/zip");
                        exchange.sendResponseHeaders(200, body.length);
                        try (OutputStream out = exchange.getResponseBody()) {
                            out.write(body);
                        }
                    }
                });
                created.start();
                this.server = created;
                this.serverPort = settings.packPort();
            } catch (IOException ex) {
                plugin.getLogger().log(Level.SEVERE, "Could not start the ClockHUD resource pack server on port "
                        + settings.packPort() + ". Players will not receive the pack.", ex);
                return false;
            }
        }

        String host = settings.packHost().isBlank() ? Bukkit.getIp() : settings.packHost().trim();
        if (host == null || host.isBlank()) {
            host = "127.0.0.1";
            plugin.getLogger().warning("resource-pack.host is blank and server-ip is not set; using 127.0.0.1. "
                    + "Remote players will not be able to download the ClockHUD pack until resource-pack.host is set.");
        }
        this.packUri = URI.create("http://" + host + ":" + this.serverPort + PACK_PATH + "?v=" + this.packHash);
        plugin.getLogger().info("ClockHUD resource pack ready at " + this.packUri);
        return !this.packHash.equals(previousHash);
    }

    /** Sends the pack unless this player already received the current version. */
    public void send(Player player, HudSettings settings) {
        if (this.packUri == null || this.packHash == null) {
            return;
        }
        if (this.packHash.equals(this.sentHashes.get(player.getUniqueId()))) {
            return;
        }
        this.sentHashes.put(player.getUniqueId(), this.packHash);
        player.sendResourcePacks(ResourcePackRequest.resourcePackRequest()
                .packs(ResourcePackInfo.resourcePackInfo(PACK_ID, this.packUri, this.packHash))
                .required(false)
                .replace(false)
                .prompt(ClockHUDPlugin.AMPERSAND.deserialize(settings.packPrompt()))
                .build());
    }

    /** Forget what was sent to this player (quit or failed download) so the next attempt re-sends. */
    public void forget(UUID playerId) {
        this.sentHashes.remove(playerId);
    }

    public void shutdown() {
        stopServer();
        this.sentHashes.clear();
        this.packBytes = null;
        this.packHash = null;
        this.packUri = null;
    }

    private void stopServer() {
        if (this.server != null) {
            this.server.stop(0);
            this.server = null;
            this.serverPort = -1;
        }
    }

    private byte[] buildPack(HudSettings settings) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            putResource(zip, "pack.mcmeta", "pack/pack.mcmeta");
            String sprites = "assets/minecraft/textures/gui/sprites/boss_bar/";
            for (String name : new String[]{"white_background.png", "white_background.png.mcmeta",
                    "white_progress.png", "white_progress.png.mcmeta"}) {
                putResource(zip, sprites + name, "pack/" + name);
            }
            put(zip, "assets/clockhud/font/hud.json", buildClockFont(settings).getBytes(StandardCharsets.UTF_8));
            String text = readResource("pack/text_template.json")
                    .replace("%ASCENT%", Integer.toString(settings.textAscent()))
                    .replace("%HEIGHT%", Integer.toString(settings.textHeight()));
            put(zip, "assets/clockhud/font/text.json", text.getBytes(StandardCharsets.UTF_8));
        }
        return buffer.toByteArray();
    }

    private static String buildClockFont(HudSettings settings) {
        StringBuilder sb = new StringBuilder("{\"providers\":[").append(SPACE_PROVIDER);
        for (int frame = 0; frame < 64; frame++) {
            sb.append(",{\"type\":\"bitmap\",\"file\":\"minecraft:item/clock_")
                    .append(String.format("%02d", frame))
                    .append(".png\",\"ascent\":").append(settings.clockAscent())
                    .append(",\"height\":").append(settings.clockSize())
                    .append(",\"chars\":[\"\\u").append(String.format("%04x", 0xE000 + frame)).append("\"]}");
        }
        return sb.append("]}").toString();
    }

    private void putResource(ZipOutputStream zip, String entryName, String resourcePath) throws IOException {
        try (InputStream in = plugin.getResource(resourcePath)) {
            if (in == null) {
                throw new IOException("Missing bundled resource " + resourcePath);
            }
            put(zip, entryName, in.readAllBytes());
        }
    }

    private String readResource(String resourcePath) throws IOException {
        try (InputStream in = plugin.getResource(resourcePath)) {
            if (in == null) {
                throw new IOException("Missing bundled resource " + resourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(FIXED_ZIP_TIME);
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }
}
