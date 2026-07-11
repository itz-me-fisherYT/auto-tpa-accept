package com.fishersmods.autotpa;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

public class AutoTpaConfigScreen extends Screen {
    private final Screen parent;
    private final AutoTpaConfig config;

    private EditBox acceptCommand;
    private EditBox delaySeconds;
    private EditBox reconnectDelaySeconds;
    private EditBox remotePrefix;
    private EditBox remoteReplyCommand;
    private EditBox allowedSenders;
    private EditBox chatWebhookUrl;
    private EditBox remoteWebhookUrl;

    protected AutoTpaConfigScreen(Screen parent) {
        super(Component.literal("Auto TPA Accept"));
        this.parent = parent;
        this.config = AutoTpaAcceptClient.getConfig();
    }

    @Override
    protected void init() {
        int center = this.width / 2;
        int left = Math.max(12, center - 154);
        int right = center + 4;
        int y = 28;

        addToggle(left, y, "Auto accept", config.enabled, value -> config.enabled = value);
        addToggle(right, y, "Remote control", config.remoteControlEnabled, value -> config.remoteControlEnabled = value);
        y += 26;

        addToggle(left, y, "Require sender", config.requireAllowedSender, value -> config.requireAllowedSender = value);
        addToggle(right, y, "Use sender arg", config.useSenderArgument, value -> config.useSenderArgument = value);
        y += 26;

        addToggle(left, y, "Auto reconnect", config.autoReconnectEnabled, value -> config.autoReconnectEnabled = value);
        addToggle(right, y, "Webhooks", config.webhookEnabled, value -> config.webhookEnabled = value);
        y += 34;

        acceptCommand = addField(left, y, 150, "Accept command", config.acceptCommand);
        delaySeconds = addField(right, y, 150, "Accept delay seconds", Integer.toString(Math.max(0, config.delayTicks / 20)));
        y += 30;

        reconnectDelaySeconds = addField(left, y, 150, "Reconnect delay seconds", Integer.toString(Math.max(5, config.autoReconnectDelayTicks / 20)));
        remotePrefix = addField(right, y, 150, "Remote prefix", config.remotePrefix);
        y += 30;

        remoteReplyCommand = addField(left, y, 308, "Reply command: msg {sender} {message}", config.remoteReplyCommand);
        y += 30;

        allowedSenders = addField(left, y, 308, "Allowed senders, comma separated", String.join(", ", config.allowedSenders));
        y += 30;

        chatWebhookUrl = addField(left, y, 308, "Chat webhook URL", config.chatWebhookUrl);
        y += 30;

        remoteWebhookUrl = addField(left, y, 308, "Remote webhook URL", config.remoteWebhookUrl);
        y += 30;

        this.addRenderableWidget(Button.builder(Component.literal("Save"), button -> {
            saveFromFields();
            config.save();
            this.minecraft.setScreen(parent);
        }).bounds(center - 154, y, 98, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Save & Stay"), button -> {
            saveFromFields();
            config.save();
        }).bounds(center - 50, y, 100, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> this.minecraft.setScreen(parent))
                .bounds(center + 56, y, 98, 20)
                .build());
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    private void addToggle(int x, int y, String label, boolean selected, ToggleSetter setter) {
        this.addRenderableWidget(Checkbox.builder(Component.literal(label), this.font)
                .pos(x, y)
                .maxWidth(150)
                .selected(selected)
                .onValueChange((checkbox, value) -> setter.set(value))
                .build());
    }

    private EditBox addField(int x, int y, int width, String hint, String value) {
        EditBox field = new EditBox(this.font, x, y, width, 20, Component.literal(hint));
        field.setMaxLength(512);
        field.setHint(Component.literal(hint));
        field.setValue(value == null ? "" : value);
        this.addRenderableWidget(field);
        return field;
    }

    private void saveFromFields() {
        config.acceptCommand = stripSlash(acceptCommand.getValue().trim());
        if (config.acceptCommand.isBlank()) {
            config.acceptCommand = "tpaccept";
        }

        config.delayTicks = parseSeconds(delaySeconds.getValue(), 0, 10, 0) * 20;
        config.autoReconnectDelayTicks = parseSeconds(reconnectDelaySeconds.getValue(), 5, 3600, 30) * 20;

        config.remotePrefix = remotePrefix.getValue().trim();
        if (config.remotePrefix.isBlank()) {
            config.remotePrefix = "!autotpa";
        }

        config.remoteReplyCommand = stripSlash(remoteReplyCommand.getValue().trim());
        if (config.remoteReplyCommand.isBlank()) {
            config.remoteReplyCommand = "msg {sender} {message}";
        }

        config.allowedSenders = Arrays.stream(allowedSenders.getValue().split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .collect(Collectors.toCollection(java.util.ArrayList::new));

        config.chatWebhookUrl = parseWebhookUrl(chatWebhookUrl.getValue());
        config.remoteWebhookUrl = parseWebhookUrl(remoteWebhookUrl.getValue());
        config.webhookEnabled = !config.chatWebhookUrl.isBlank() || !config.remoteWebhookUrl.isBlank();
    }

    private static String parseWebhookUrl(String value) {
        String trimmed = value == null ? "" : value.trim();
        return AutoTpaWebhook.isDiscordWebhookUrl(trimmed) ? trimmed : "";
    }

    private static int parseSeconds(String raw, int min, int max, int fallback) {
        try {
            int value = Integer.parseInt(raw.trim().toLowerCase(Locale.ROOT).replace("s", ""));
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static String stripSlash(String command) {
        while (command.startsWith("/")) {
            command = command.substring(1);
        }
        return command;
    }

    @FunctionalInterface
    private interface ToggleSetter {
        void set(boolean value);
    }
}
