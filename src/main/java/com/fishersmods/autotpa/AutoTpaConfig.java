package com.fishersmods.autotpa;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class AutoTpaConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<String> DEFAULT_ALLOWED_SENDERS = List.of();
    private static final List<String> DEFAULT_REMOTE_ADMINS = List.of();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("auto-tpa-accept.json");

    public boolean enabled = true;
    public String acceptCommand = "tpaccept";
    public boolean requireAllowedSender = true;
    public boolean useSenderArgument = false;
    public boolean remoteControlEnabled = false;
    public String remotePrefix = "!autotpa";
    public String remoteReplyCommand = "msg {sender} {message}";
    public boolean webhookEnabled = false;
    public String discordWebhookUrl = "";
    public String remoteWebhookUrl = "";
    public String chatWebhookUrl = "";
    public String webhookUsername = "AutoTPA Alt";
    public boolean autoReconnectEnabled = false;
    public int autoReconnectDelayTicks = 600;
    public int delayTicks = 5;
    public int cooldownTicks = 60;
    public List<String> allowedSenders = new ArrayList<>(DEFAULT_ALLOWED_SENDERS);
    public List<String> remoteAdminSenders = new ArrayList<>(DEFAULT_REMOTE_ADMINS);
    public List<String> triggers = new ArrayList<>(List.of(
            "has requested to teleport to you",
            "wants to teleport to you",
            "requested to teleport to you",
            "has sent you a teleport request",
            "teleport request",
            "tpa request"
    ));

    public static AutoTpaConfig load() {
        if (Files.exists(CONFIG_PATH)) {
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
                AutoTpaConfig loaded = GSON.fromJson(reader, AutoTpaConfig.class);
                if (loaded != null) {
                    loaded.fillMissingDefaults();
                    loaded.save();
                    return loaded;
                }
            } catch (IOException ignored) {
            }
        }

        AutoTpaConfig config = new AutoTpaConfig();
        config.save();
        return config;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException ignored) {
        }
    }

    private void fillMissingDefaults() {
        if (acceptCommand == null || acceptCommand.isBlank()) {
            acceptCommand = "tpaccept";
        }
        if (remotePrefix == null || remotePrefix.isBlank()) {
            remotePrefix = "!autotpa";
        }
        if (remoteReplyCommand == null || remoteReplyCommand.isBlank()) {
            remoteReplyCommand = "msg {sender} {message}";
        }
        if (discordWebhookUrl == null) {
            discordWebhookUrl = "";
        }
        if (remoteWebhookUrl == null) {
            remoteWebhookUrl = "";
        }
        if (chatWebhookUrl == null) {
            chatWebhookUrl = "";
        }
        if (!discordWebhookUrl.isBlank()) {
            if (remoteWebhookUrl.isBlank()) {
                remoteWebhookUrl = discordWebhookUrl;
            }
            if (chatWebhookUrl.isBlank()) {
                chatWebhookUrl = discordWebhookUrl;
            }
        }
        if (webhookUsername == null || webhookUsername.isBlank()) {
            webhookUsername = "AutoTPA Alt";
        }
        autoReconnectDelayTicks = Math.max(autoReconnectDelayTicks, 100);
        requireAllowedSender = true;
        if (allowedSenders == null) {
            allowedSenders = new ArrayList<>(DEFAULT_ALLOWED_SENDERS);
        }
        if (remoteAdminSenders == null) {
            remoteAdminSenders = new ArrayList<>(DEFAULT_REMOTE_ADMINS);
        }
        if (triggers == null || triggers.isEmpty()) {
            triggers = new AutoTpaConfig().triggers;
        }
        delayTicks = Math.max(delayTicks, 0);
        cooldownTicks = Math.max(cooldownTicks, 1);
    }
}
