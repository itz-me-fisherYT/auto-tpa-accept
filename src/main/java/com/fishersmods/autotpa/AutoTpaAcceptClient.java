package com.fishersmods.autotpa;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.regex.Pattern;

public class AutoTpaAcceptClient implements ClientModInitializer {
    public static final String MOD_ID = "auto-tpa-accept";

    private static AutoTpaConfig config;
    private static int cooldownTicks;
    private static int reconnectTicks = -1;
    private static int reconnectAttempts;
    private static ServerData lastServerInfo;
    private static final Queue<PendingAccept> pendingAccepts = new ArrayDeque<>();
    private static final Queue<PendingRemoteAction> pendingRemoteActions = new ArrayDeque<>();

    @Override
    public void onInitializeClient() {
        config = AutoTpaConfig.load();
        registerCommands();
        ClientTickEvents.END_CLIENT_TICK.register(AutoTpaAcceptClient::tick);
    }

    public static AutoTpaConfig getConfig() {
        if (config == null) {
            config = AutoTpaConfig.load();
        }
        return config;
    }

    public static void handleChatLine(String message) {
        if (config == null || message == null || message.isBlank()) {
            return;
        }

        AutoTpaWebhook.sendChat(config, "[CHAT] " + message);

        String lowerMessage = message.toLowerCase(Locale.ROOT);
        String allowedTpaSender = findListedSender(message, config.allowedSenders);
        if (handleRemoteCommand(message, lowerMessage)) {
            return;
        }

        if (!config.enabled || cooldownTicks > 0) {
            return;
        }

        if (!looksLikeTpaRequest(lowerMessage)) {
            return;
        }

        if (allowedTpaSender == null) {
            return;
        }

        if ((config.useSenderArgument || config.acceptCommand.contains("{sender}")) && allowedTpaSender == null) {
            sendLocalMessage("Saw a TPA request, but no allowed sender matched.");
            return;
        }

        pendingAccepts.add(new PendingAccept(config.delayTicks, allowedTpaSender));
        cooldownTicks = Math.max(config.cooldownTicks, config.delayTicks + 1);
    }

    private static boolean handleRemoteCommand(String message, String lowerMessage) {
        if (!config.remoteControlEnabled || config.remotePrefix.isBlank()) {
            return false;
        }

        String lowerPrefix = config.remotePrefix.toLowerCase(Locale.ROOT);
        int prefixIndex = lowerMessage.indexOf(lowerPrefix);
        if (prefixIndex < 0) {
            return false;
        }

        String sender = findListedSender(message, config.remoteAdminSenders);
        if (sender == null) {
            String deniedSender = findMessageSender(message, prefixIndex);
            if (deniedSender != null) {
                replyToRemote(deniedSender, "denied");
                AutoTpaWebhook.sendRemote(config, "[DENIED] " + deniedSender + " tried: " + message);
            } else {
                sendLocalMessage("Denied remote AutoTPA command from an unknown sender.");
                AutoTpaWebhook.sendRemote(config, "[DENIED] Unknown sender tried: " + message);
            }
            return true;
        }

        String remoteInput = message.substring(prefixIndex + config.remotePrefix.length()).trim();
        if (remoteInput.isBlank()) {
            replyToRemote(sender, "Try: on, off, accept, status, command, delay, usesender, requiresender.");
            return true;
        }

        runRemoteCommand(sender, remoteInput);
        return true;
    }

    private static String findMessageSender(String message, int prefixIndex) {
        String beforeCommand = message.substring(0, Math.max(0, prefixIndex)).trim();
        if (beforeCommand.isBlank()) {
            return null;
        }

        String normalized = beforeCommand
                .replace("->", " ")
                .replace("<-", " ")
                .replace("»", " ")
                .replace(":", " ");
        String[] tokens = normalized.split("\\s+");

        for (int i = tokens.length - 1; i >= 0; i--) {
            String token = cleanUsernameToken(tokens[i]);
            if (isPossibleUsername(token) && !"me".equalsIgnoreCase(token) && !"from".equalsIgnoreCase(token)) {
                return token;
            }
        }

        return null;
    }

    private static String cleanUsernameToken(String token) {
        return token.replaceAll("^[^A-Za-z0-9_]+|[^A-Za-z0-9_]+$", "");
    }

    private static boolean isPossibleUsername(String token) {
        return token != null && token.length() >= 3 && token.length() <= 16 && token.matches("[A-Za-z0-9_]+");
    }

    private static void runRemoteCommand(String sender, String input) {
        String[] parts = input.split("\\s+", 2);
        String action = parts[0].toLowerCase(Locale.ROOT);
        String value = parts.length > 1 ? parts[1].trim() : "";

        switch (action) {
            case "cmd" -> {
                if (value.isBlank()) {
                    replyToRemote(sender, "Usage: " + config.remotePrefix + " cmd spawn");
                    return;
                }
                queueCommand(value);
                replyToRemote(sender, "Command queued: /" + stripSlash(value));
                AutoTpaWebhook.sendRemote(config, "[REMOTE CMD] " + sender + ": /" + stripSlash(value));
            }
            case "say" -> {
                if (value.isBlank()) {
                    replyToRemote(sender, "Usage: " + config.remotePrefix + " say hello");
                    return;
                }
                queueChat(value);
                replyToRemote(sender, "Chat queued.");
                AutoTpaWebhook.sendRemote(config, "[REMOTE CHAT] " + sender + ": " + value);
            }
            case "webhook" -> runRemoteWebhookCommand(sender, value);
            case "reconnect" -> runRemoteReconnectCommand(sender, value);
            case "on" -> {
                config.enabled = true;
                config.save();
                replyToRemote(sender, "AutoTPA enabled.");
            }
            case "off" -> {
                config.enabled = false;
                config.save();
                replyToRemote(sender, "AutoTPA disabled.");
            }
            case "accept" -> {
                pendingAccepts.add(new PendingAccept(0, sender));
                replyToRemote(sender, "Accept queued.");
            }
            case "status" -> replyToRemote(sender, "enabled=" + config.enabled
                    + ", command=/" + config.acceptCommand
                    + ", delay=" + config.delayTicks
                    + ", requireSender=" + config.requireAllowedSender
                    + ", useSender=" + config.useSenderArgument);
            case "command" -> {
                if (value.isBlank()) {
                    replyToRemote(sender, "Usage: " + config.remotePrefix + " command tpaccept");
                    return;
                }
                config.acceptCommand = stripSlash(value);
                config.save();
                replyToRemote(sender, "Accept command set to /" + config.acceptCommand);
            }
            case "delay" -> {
                Integer delay = parseInteger(value, 0, 200);
                if (delay == null) {
                    replyToRemote(sender, "Usage: " + config.remotePrefix + " delay 5");
                    return;
                }
                config.delayTicks = delay;
                config.save();
                replyToRemote(sender, "Delay set to " + delay + " ticks.");
            }
            case "usesender" -> {
                Boolean enabled = parseBoolean(value);
                if (enabled == null) {
                    replyToRemote(sender, "Usage: " + config.remotePrefix + " usesender true");
                    return;
                }
                config.useSenderArgument = enabled;
                config.save();
                replyToRemote(sender, "Use sender argument: " + enabled);
            }
            case "requiresender" -> {
                Boolean enabled = parseBoolean(value);
                if (enabled == null) {
                    replyToRemote(sender, "Usage: " + config.remotePrefix + " requiresender true");
                    return;
                }
                config.requireAllowedSender = enabled;
                config.save();
                replyToRemote(sender, "Require allowed sender: " + enabled);
            }
            default -> replyToRemote(sender, "Unknown AutoTPA command: " + action);
        }
    }

    private static void runRemoteWebhookCommand(String sender, String value) {
        String[] parts = value.split("\\s+", 2);
        String action = parts.length == 0 ? "" : parts[0].toLowerCase(Locale.ROOT);
        String actionValue = parts.length > 1 ? parts[1].trim() : "";

        switch (action) {
            case "off" -> {
                config.webhookEnabled = false;
                config.save();
                replyToRemote(sender, "Discord webhooks disabled.");
            }
            case "chat" -> {
                config.chatWebhookUrl = parseWebhookValue(actionValue);
                refreshWebhookEnabled();
                config.save();
                replyToRemote(sender, config.chatWebhookUrl.isBlank() ? "Chat webhook cleared." : "Chat webhook updated.");
            }
            case "remote" -> {
                config.remoteWebhookUrl = parseWebhookValue(actionValue);
                refreshWebhookEnabled();
                config.save();
                replyToRemote(sender, config.remoteWebhookUrl.isBlank() ? "Remote webhook cleared." : "Remote webhook updated.");
            }
            case "all" -> {
                String url = parseWebhookValue(actionValue);
                config.discordWebhookUrl = url;
                config.chatWebhookUrl = url;
                config.remoteWebhookUrl = url;
                refreshWebhookEnabled();
                config.save();
                replyToRemote(sender, url.isBlank() ? "Discord webhooks cleared." : "Discord webhooks updated.");
            }
            default -> {
                if (value.startsWith("http")) {
                    String url = parseWebhookValue(value);
                    config.discordWebhookUrl = url;
                    config.chatWebhookUrl = url;
                    config.remoteWebhookUrl = url;
                    refreshWebhookEnabled();
                    config.save();
                    replyToRemote(sender, "Discord webhooks updated.");
                } else {
                    replyToRemote(sender, "Usage: webhook chat/remote/all <url>, or webhook off");
                }
            }
        }
    }

    private static void runRemoteReconnectCommand(String sender, String value) {
        String[] parts = value.split("\\s+", 2);
        String action = parts.length == 0 ? "" : parts[0].toLowerCase(Locale.ROOT);
        String actionValue = parts.length > 1 ? parts[1].trim() : "";

        switch (action) {
            case "on" -> {
                config.autoReconnectEnabled = true;
                config.save();
                replyToRemote(sender, "Auto reconnect enabled.");
            }
            case "off" -> {
                config.autoReconnectEnabled = false;
                reconnectTicks = -1;
                config.save();
                replyToRemote(sender, "Auto reconnect disabled.");
            }
            case "now" -> {
                reconnectTicks = 0;
                replyToRemote(sender, "Reconnect queued.");
            }
            case "delay" -> {
                Integer delaySeconds = parseInteger(actionValue, 5, 3600);
                if (delaySeconds == null) {
                    replyToRemote(sender, "Usage: " + config.remotePrefix + " reconnect delay 30");
                    return;
                }
                config.autoReconnectDelayTicks = delaySeconds * 20;
                config.save();
                replyToRemote(sender, "Reconnect delay set to " + delaySeconds + " seconds.");
            }
            default -> replyToRemote(sender, "Usage: reconnect on/off/now/delay");
        }
    }

    private static void queueCommand(String command) {
        pendingRemoteActions.add(new PendingRemoteAction(PendingRemoteAction.Type.COMMAND, stripSlash(command)));
    }

    private static void queueChat(String message) {
        pendingRemoteActions.add(new PendingRemoteAction(PendingRemoteAction.Type.CHAT, message));
    }

    private static String parseWebhookValue(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.equalsIgnoreCase("clear") || trimmed.equalsIgnoreCase("none") || trimmed.equalsIgnoreCase("off")) {
            return "";
        }
        return AutoTpaWebhook.isDiscordWebhookUrl(trimmed) ? trimmed : "";
    }

    private static void refreshWebhookEnabled() {
        config.webhookEnabled = !config.remoteWebhookUrl.isBlank() || !config.chatWebhookUrl.isBlank();
    }

    private static Integer parseInteger(String value, int min, int max) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) {
                return null;
            }
            return parsed;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Boolean parseBoolean(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true", "yes", "on", "1" -> true;
            case "false", "no", "off", "0" -> false;
            default -> null;
        };
    }

    private static boolean looksLikeTpaRequest(String lowerMessage) {
        for (String trigger : config.triggers) {
            if (!trigger.isBlank() && lowerMessage.contains(trigger.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String findListedSender(String message, Iterable<String> senders) {
        if (senders == null || message == null) {
            return null;
        }

        for (String sender : senders) {
            if (!sender.isBlank() && containsUsername(message, sender)) {
                return sender;
            }
        }
        return null;
    }

    private static boolean containsUsername(String message, String username) {
        String pattern = "(?i)(?<![A-Za-z0-9_])" + Pattern.quote(username) + "(?![A-Za-z0-9_])";
        return Pattern.compile(pattern).matcher(message).find();
    }

    private static void tick(Minecraft client) {
        if (cooldownTicks > 0) {
            cooldownTicks--;
        }

        updateLastServerInfo(client);
        handleAutoReconnect(client);

        if (client.player == null || client.getConnection() == null) {
            return;
        }

        while (!pendingRemoteActions.isEmpty()) {
            PendingRemoteAction action = pendingRemoteActions.poll();
            if (action.type == PendingRemoteAction.Type.COMMAND) {
                client.getConnection().sendCommand(stripSlash(action.value));
            } else {
                client.getConnection().sendChat(action.value);
            }
        }

        if (pendingAccepts.isEmpty()) {
            return;
        }

        PendingAccept pending = pendingAccepts.peek();
        pending.ticks--;
        if (pending.ticks > 0) {
            return;
        }

        pendingAccepts.poll();
        String command = buildAcceptCommand(pending.sender);
        if (!command.isBlank()) {
            client.getConnection().sendCommand(command);
            sendLocalMessage("Sent /" + command);
        }
    }

    private static void updateLastServerInfo(Minecraft client) {
        if (client.getConnection() == null) {
            return;
        }

        ServerData currentServer = client.getCurrentServer();
        if (currentServer != null) {
            lastServerInfo = currentServer;
            reconnectTicks = -1;
            reconnectAttempts = 0;
        }
    }

    private static void handleAutoReconnect(Minecraft client) {
        if (!config.autoReconnectEnabled || client.getConnection() != null || lastServerInfo == null) {
            return;
        }

        if (!(client.screen instanceof DisconnectedScreen)) {
            return;
        }

        if (reconnectTicks < 0) {
            reconnectTicks = config.autoReconnectDelayTicks;
            AutoTpaWebhook.sendRemote(config, "[RECONNECT] Disconnected. Rejoining " + lastServerInfo.ip
                    + " in " + (reconnectTicks / 20) + " seconds.");
            return;
        }

        reconnectTicks--;
        if (reconnectTicks > 0) {
            return;
        }

        reconnectAttempts++;
        ServerData serverInfo = new ServerData(lastServerInfo.name, lastServerInfo.ip, lastServerInfo.type());
        serverInfo.copyFrom(lastServerInfo);
        ServerAddress address = ServerAddress.parseString(serverInfo.ip);
        TransferState transferState = new TransferState(Map.of(), Map.of(), false);
        AutoTpaWebhook.sendRemote(config, "[RECONNECT] Attempt " + reconnectAttempts + " to " + serverInfo.ip);
        reconnectTicks = config.autoReconnectDelayTicks;
        ConnectScreen.startConnecting(new JoinMultiplayerScreen(new TitleScreen()), client, address, serverInfo, false, transferState);
    }

    private static String buildAcceptCommand(String sender) {
        String command = stripSlash(config.acceptCommand.trim());
        if (sender != null) {
            command = command.replace("{sender}", sender);
            if (config.useSenderArgument && !command.toLowerCase(Locale.ROOT).contains(sender.toLowerCase(Locale.ROOT))) {
                command = command + " " + sender;
            }
        }
        return stripSlash(command.trim());
    }

    private static String stripSlash(String command) {
        while (command.startsWith("/")) {
            command = command.substring(1);
        }
        return command;
    }

    private static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommands.literal("autotpa")
                        .then(ClientCommands.literal("status")
                                .executes(context -> {
                                    sendStatus(context.getSource());
                                    return 1;
                                }))
                        .then(ClientCommands.literal("on")
                                .executes(context -> {
                                    config.enabled = true;
                                    saveAndReply(context.getSource(), "Auto TPA Accept enabled.");
                                    return 1;
                                }))
                        .then(ClientCommands.literal("off")
                                .executes(context -> {
                                    config.enabled = false;
                                    saveAndReply(context.getSource(), "Auto TPA Accept disabled.");
                                    return 1;
                                }))
                        .then(ClientCommands.literal("command")
                                .then(ClientCommands.argument("command", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            config.acceptCommand = stripSlash(StringArgumentType.getString(context, "command").trim());
                                            saveAndReply(context.getSource(), "Accept command set to /" + config.acceptCommand);
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("delay")
                                .then(ClientCommands.argument("ticks", IntegerArgumentType.integer(0, 200))
                                        .executes(context -> {
                                            config.delayTicks = IntegerArgumentType.getInteger(context, "ticks");
                                            saveAndReply(context.getSource(), "Accept delay set to " + config.delayTicks + " ticks.");
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("requiresender")
                                .then(ClientCommands.argument("enabled", BoolArgumentType.bool())
                                        .executes(context -> {
                                            config.requireAllowedSender = BoolArgumentType.getBool(context, "enabled");
                                            saveAndReply(context.getSource(), "Require allowed sender: " + config.requireAllowedSender);
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("usesender")
                                .then(ClientCommands.argument("enabled", BoolArgumentType.bool())
                                        .executes(context -> {
                                            config.useSenderArgument = BoolArgumentType.getBool(context, "enabled");
                                            saveAndReply(context.getSource(), "Use sender argument: " + config.useSenderArgument);
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("reconnect")
                                .then(ClientCommands.literal("on")
                                        .executes(context -> {
                                            config.autoReconnectEnabled = true;
                                            saveAndReply(context.getSource(), "Auto reconnect enabled.");
                                            return 1;
                                        }))
                                .then(ClientCommands.literal("off")
                                        .executes(context -> {
                                            config.autoReconnectEnabled = false;
                                            reconnectTicks = -1;
                                            saveAndReply(context.getSource(), "Auto reconnect disabled.");
                                            return 1;
                                        }))
                                .then(ClientCommands.literal("now")
                                        .executes(context -> {
                                            reconnectTicks = 0;
                                            saveAndReply(context.getSource(), "Reconnect queued.");
                                            return 1;
                                        }))
                                .then(ClientCommands.literal("delay")
                                        .then(ClientCommands.argument("seconds", IntegerArgumentType.integer(5, 3600))
                                                .executes(context -> {
                                                    int seconds = IntegerArgumentType.getInteger(context, "seconds");
                                                    config.autoReconnectDelayTicks = seconds * 20;
                                                    saveAndReply(context.getSource(), "Reconnect delay set to " + seconds + " seconds.");
                                                    return 1;
                                                }))))
                        .then(ClientCommands.literal("remote")
                                .then(ClientCommands.literal("on")
                                        .executes(context -> {
                                            config.remoteControlEnabled = true;
                                            saveAndReply(context.getSource(), "Remote control enabled.");
                                            return 1;
                                        }))
                                .then(ClientCommands.literal("off")
                                        .executes(context -> {
                                            config.remoteControlEnabled = false;
                                            saveAndReply(context.getSource(), "Remote control disabled.");
                                            return 1;
                                        }))
                                .then(ClientCommands.literal("prefix")
                                        .then(ClientCommands.argument("prefix", StringArgumentType.greedyString())
                                                .executes(context -> {
                                                    config.remotePrefix = StringArgumentType.getString(context, "prefix").trim();
                                                    saveAndReply(context.getSource(), "Remote prefix set to " + config.remotePrefix);
                                                    return 1;
                                                })))
                                .then(ClientCommands.literal("reply")
                                        .then(ClientCommands.argument("command", StringArgumentType.greedyString())
                                                .executes(context -> {
                                                    config.remoteReplyCommand = stripSlash(StringArgumentType.getString(context, "command").trim());
                                                    saveAndReply(context.getSource(), "Remote reply command set to /" + config.remoteReplyCommand);
                                                    return 1;
                                                }))))
                        .then(ClientCommands.literal("webhook")
                                .then(ClientCommands.literal("off")
                                        .executes(context -> {
                                            config.webhookEnabled = false;
                                            config.save();
                                            saveAndReply(context.getSource(), "Discord webhooks disabled.");
                                            return 1;
                                        }))
                                .then(ClientCommands.literal("remote")
                                        .then(ClientCommands.argument("url", StringArgumentType.greedyString())
                                                .executes(context -> {
                                                    String url = StringArgumentType.getString(context, "url").trim();
                                                    if (!AutoTpaWebhook.isDiscordWebhookUrl(url)) {
                                                        saveAndReply(context.getSource(), "Remote webhook must be a Discord webhook URL.");
                                                        return 0;
                                                    }
                                                    config.remoteWebhookUrl = url;
                                                    config.webhookEnabled = !config.remoteWebhookUrl.isBlank() || !config.chatWebhookUrl.isBlank();
                                                    config.save();
                                                    saveAndReply(context.getSource(), "Remote Discord webhook " + (!config.remoteWebhookUrl.isBlank() ? "set." : "cleared."));
                                                    return 1;
                                                })))
                                .then(ClientCommands.literal("chat")
                                        .then(ClientCommands.argument("url", StringArgumentType.greedyString())
                                                .executes(context -> {
                                                    String url = StringArgumentType.getString(context, "url").trim();
                                                    if (!AutoTpaWebhook.isDiscordWebhookUrl(url)) {
                                                        saveAndReply(context.getSource(), "Chat webhook must be a Discord webhook URL.");
                                                        return 0;
                                                    }
                                                    config.chatWebhookUrl = url;
                                                    config.webhookEnabled = !config.remoteWebhookUrl.isBlank() || !config.chatWebhookUrl.isBlank();
                                                    config.save();
                                                    saveAndReply(context.getSource(), "Chat Discord webhook " + (!config.chatWebhookUrl.isBlank() ? "set." : "cleared."));
                                                    return 1;
                                                })))
                                .then(ClientCommands.argument("url", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            String url = StringArgumentType.getString(context, "url").trim();
                                            if (!AutoTpaWebhook.isDiscordWebhookUrl(url)) {
                                                saveAndReply(context.getSource(), "Webhook must be a Discord webhook URL.");
                                                return 0;
                                            }
                                            config.discordWebhookUrl = url;
                                            config.remoteWebhookUrl = config.discordWebhookUrl;
                                            config.chatWebhookUrl = config.discordWebhookUrl;
                                            config.webhookEnabled = !config.remoteWebhookUrl.isBlank();
                                            config.save();
                                            saveAndReply(context.getSource(), "Discord webhooks " + (config.webhookEnabled ? "enabled." : "disabled."));
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("sender")
                                .then(ClientCommands.literal("add")
                                        .then(ClientCommands.argument("name", StringArgumentType.word())
                                                .executes(context -> {
                                                    String name = StringArgumentType.getString(context, "name");
                                                    if (!config.allowedSenders.contains(name)) {
                                                        config.allowedSenders.add(name);
                                                    }
                                                    saveAndReply(context.getSource(), "Added allowed sender: " + name);
                                                    return 1;
                                                })))
                                .then(ClientCommands.literal("remove")
                                        .then(ClientCommands.argument("name", StringArgumentType.word())
                                                .suggests((context, builder) -> {
                                                    for (String sender : config.allowedSenders) {
                                                        builder.suggest(sender);
                                                    }
                                                    return builder.buildFuture();
                                                })
                                                .executes(context -> {
                                                    String name = StringArgumentType.getString(context, "name");
                                                    config.allowedSenders.removeIf(sender -> sender.equalsIgnoreCase(name));
                                                    saveAndReply(context.getSource(), "Removed allowed sender: " + name);
                                                    return 1;
                                                })))
                                .then(ClientCommands.literal("clear")
                                        .executes(context -> {
                                            config.allowedSenders.clear();
                                            saveAndReply(context.getSource(), "Cleared allowed senders.");
                                            return 1;
                                        })))));
    }

    private static void sendStatus(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal("[AutoTPA] enabled=" + config.enabled
                + ", command=/" + config.acceptCommand
                + ", delayTicks=" + config.delayTicks
                + ", requireAllowedSender=" + config.requireAllowedSender
                + ", useSenderArgument=" + config.useSenderArgument
                + ", remoteControlEnabled=" + config.remoteControlEnabled
                + ", remotePrefix=" + config.remotePrefix
                + ", remoteAdmins=" + config.remoteAdminSenders
                + ", webhookEnabled=" + config.webhookEnabled
                + ", autoReconnectEnabled=" + config.autoReconnectEnabled
                + ", autoReconnectDelaySeconds=" + (config.autoReconnectDelayTicks / 20)
                + ", allowedSenders=" + config.allowedSenders));
    }

    private static void saveAndReply(FabricClientCommandSource source, String message) {
        config.save();
        source.sendFeedback(Component.literal("[AutoTPA] " + message));
    }

    private static void sendLocalMessage(String message) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal("[AutoTPA] " + message));
        }
    }

    private static void replyToRemote(String sender, String message) {
        String replyCommand = stripSlash(config.remoteReplyCommand.trim())
                .replace("{sender}", sender)
                .replace("{message}", message);
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() != null && !replyCommand.isBlank()) {
            client.getConnection().sendCommand(replyCommand);
        }
        sendLocalMessage(message);
    }

    private static final class PendingAccept {
        private int ticks;
        private final String sender;

        private PendingAccept(int ticks, String sender) {
            this.ticks = ticks;
            this.sender = sender;
        }
    }

    private static final class PendingRemoteAction {
        private enum Type {
            COMMAND,
            CHAT
        }

        private final Type type;
        private final String value;

        private PendingRemoteAction(Type type, String value) {
            this.type = type;
            this.value = value;
        }
    }
}
