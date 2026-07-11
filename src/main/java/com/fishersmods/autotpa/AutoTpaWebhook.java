package com.fishersmods.autotpa;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class AutoTpaWebhook {
    private static final Gson GSON = new Gson();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private AutoTpaWebhook() {
    }

    public static void sendChat(AutoTpaConfig config, String content) {
        send(config, config == null ? "" : config.chatWebhookUrl, content);
    }

    public static void sendRemote(AutoTpaConfig config, String content) {
        send(config, config == null ? "" : config.remoteWebhookUrl, content);
    }

    public static boolean isDiscordWebhookUrl(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }

        try {
            URI uri = URI.create(value.trim());
            String host = uri.getHost();
            String path = uri.getPath();
            return "https".equalsIgnoreCase(uri.getScheme())
                    && host != null
                    && (host.equalsIgnoreCase("discord.com") || host.equalsIgnoreCase("discordapp.com"))
                    && path != null
                    && path.startsWith("/api/webhooks/");
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static void send(AutoTpaConfig config, String webhookUrl, String content) {
        if (config == null || !config.webhookEnabled || webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }

        if (!isDiscordWebhookUrl(webhookUrl)) {
            return;
        }

        String trimmed = content == null ? "" : content.trim();
        if (trimmed.isBlank()) {
            return;
        }

        JsonObject payload = new JsonObject();
        String username = getWebhookUsername(config);
        payload.addProperty("username", username);
        payload.addProperty("avatar_url", "https://mc-heads.net/avatar/" + username + "/128.png");
        payload.addProperty("content", limit(trimmed, 1900));

        HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                .build();

        CLIENT.sendAsync(request, HttpResponse.BodyHandlers.discarding());
    }

    private static String getWebhookUsername(AutoTpaConfig config) {
        Minecraft client = Minecraft.getInstance();
        if (client != null && client.getUser() != null && !client.getUser().getName().isBlank()) {
            return client.getUser().getName();
        }
        if (config.webhookUsername != null && !config.webhookUsername.isBlank()) {
            return config.webhookUsername;
        }
        return "AutoTPA";
    }

    private static String limit(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 3) + "...";
    }
}
