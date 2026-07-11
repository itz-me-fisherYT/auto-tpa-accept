package com.fishersmods.autotpa.mixin;

import com.fishersmods.autotpa.AutoTpaAcceptClient;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatComponent.class)
public class ChatHudMixin {
    @Inject(method = "addServerSystemMessage(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"))
    private void autoTpaAccept$onServerSystemMessage(Component message, CallbackInfo ci) {
        AutoTpaAcceptClient.handleChatLine(message.getString());
    }

    @Inject(method = "addPlayerMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V", at = @At("HEAD"))
    private void autoTpaAccept$onPlayerMessage(Component message, @Nullable MessageSignature signature, @Nullable GuiMessageTag tag, CallbackInfo ci) {
        AutoTpaAcceptClient.handleChatLine(message.getString());
    }
}
