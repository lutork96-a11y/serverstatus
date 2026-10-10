package de.serverstatus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ServerStatusWeb extends JavaPlugin {

    private HttpServer http;
    private ExecutorService executor;
    private volatile byte[] jsonSnapshot = "{\"online\":0,\"max\":0,\"players\":[]}".getBytes(StandardCharsets.UTF_8);
    private byte[] page;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        int port = getConfig().getInt("port", 8080);
        String title = getConfig().getString("title", "Minecraft-Server");

        try (InputStream in = getResource("index.html")) {
            String html = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("%TITLE%", escape(title));
            page = html.getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            getLogger().severe("index.html konnte nicht geladen werden: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Spielerdaten alle 5 Sekunden auf dem Haupt-Thread einsammeln (sicher für Bukkit)
        Bukkit.getScheduler().runTaskTimer(this, this::updateSnapshot, 0L, 100L);

        try {
            http = HttpServer.create(new InetSocketAddress(port), 0);
            executor = Executors.newFixedThreadPool(2);
            http.setExecutor(executor);
            http.createContext("/api/players", ex -> send(ex, "application/json; charset=utf-8", jsonSnapshot));
            http.createContext("/", ex -> {
                if (!ex.getRequestURI().getPath().equals("/")) {
                    ex.sendResponseHeaders(404, -1);
                    ex.close();
                    return;
                }
                send(ex, "text/html; charset=utf-8", page);
            });
            http.start();
            getLogger().info("Webseite läuft auf Port " + port);
        } catch (IOException e) {
            getLogger().severe("Webserver konnte nicht starten (Port " + port + " belegt?): " + e.getMessage());
        }
    }

    @Override
    public void onDisable() {
        if (http != null) http.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    private void updateSnapshot() {
        Collection<? extends Player> players = Bukkit.getOnlinePlayers();
        StringBuilder sb = new StringBuilder();
        sb.append("{\"online\":").append(players.size())
          .append(",\"max\":").append(Bukkit.getMaxPlayers())
          .append(",\"tps\":").append(String.format(java.util.Locale.ROOT, "%.1f", Math.min(20.0, Bukkit.getTPS()[0])))
          .append(",\"players\":[");
        boolean first = true;
        for (Player p : players) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"name\":\"").append(escape(p.getName())).append("\",\"uuid\":\"")
              .append(p.getUniqueId()).append("\"}");
        }
        sb.append("]}");
        jsonSnapshot = sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange ex, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*"); // erlaubt Einbinden von anderen Seiten
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, body.length);
        try (var os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("<", "&lt;").replace(">", "&gt;");
    }
}
