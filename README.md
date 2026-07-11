# Auto TPA Accept

Client-side Fabric mod that watches incoming chat/system messages for teleport request text and can automatically run `/tpaccept` for configured senders.

## Features

- Configurable accept delay, cooldown, command format, allowed senders, and trigger phrases.
- Optional remote control through in-game private messages using a prefix such as `!autotpa`.
- Optional auto reconnect after disconnect.
- Optional Discord webhook logging for chat, remote commands, and reconnect events.
- Mod Menu config screen.

## Safety and privacy notes

- No Discord token or webhook URL is included by default.
- Allowed senders and remote admins are empty by default. Add only players you trust.
- Remote control is off by default. When enabled, listed remote admins can make this client run commands or send chat through private-message commands.
- Auto reconnect is off by default. If enabled, reconnect logs can include the server address.
- Chat webhooks can forward every chat line the client receives. Only enable chat logging if you are comfortable sending that chat to your Discord webhook.
- Webhook fields only accept Discord webhook URLs under `https://discord.com/api/webhooks/` or `https://discordapp.com/api/webhooks/`.

## Commands

- `/autotpa status`
- `/autotpa on` or `/autotpa off`
- `/autotpa sender add <name>`
- `/autotpa sender remove <name>`
- `/autotpa command tpaccept`
- `/autotpa delay <ticks>`
- `/autotpa remote on` or `/autotpa remote off`
- `/autotpa reconnect on`, `/autotpa reconnect off`, `/autotpa reconnect now`
- `/autotpa webhook remote <discord webhook url>`
- `/autotpa webhook chat <discord webhook url>`
- `/autotpa webhook off`

## Remote command examples

Remote commands are sent to this client in a private message by a configured remote admin:

- `/msg AltAccount !autotpa status`
- `/msg AltAccount !autotpa on`
- `/msg AltAccount !autotpa accept`
- `/msg AltAccount !autotpa cmd /home`
- `/msg AltAccount !autotpa say hello`
- `/msg AltAccount !autotpa reconnect off`

