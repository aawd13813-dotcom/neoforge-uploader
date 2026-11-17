package com.essentialsync.client;

import com.essentialsync.sync.LockManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;

/**
 * Shown when another friend currently holds an active host lock in lock.json.
 */
public class SyncBlockedScreen extends Screen {
    private final Screen returnScreen;
    private final LockManager.LockInfo lockInfo;

    public SyncBlockedScreen(Screen returnScreen, LockManager.LockInfo lockInfo) {
        super(Component.literal("World Currently Hosted"));
        this.returnScreen = returnScreen;
        this.lockInfo = lockInfo;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        this.addRenderableWidget(
                Button.builder(Component.literal("Back to World List"), btn -> {
                            if (this.minecraft != null) {
                                Screen target = returnScreen != null
                                        ? returnScreen
                                        : new SelectWorldScreen(new TitleScreen());
                                this.minecraft.setScreen(target);
                            }
                        })
                        .bounds(centerX - 100, centerY + 36, 200, 20)
                        .build()
        );
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        long remainingMs = Math.max(0L, lockInfo.expiresAt() - System.currentTimeMillis());
        long totalSec = remainingMs / 1000L;
        long mins = totalSec / 60L;
        long secs = totalSec % 60L;

        String message = String.format(
                "World is currently hosted by %s (lock expires in %dm %ds).",
                lockInfo.holderName(),
                mins,
                secs
        );
        String hint = "Join them via Essential instead, or retry after expiry.";

        int centerX = this.width / 2;
        int centerY = this.height / 2;

        graphics.drawCenteredString(this.font, this.title, centerX, centerY - 36, 0xFFFF5555);
        graphics.drawCenteredString(this.font, Component.literal(message), centerX, centerY - 12, 0xFFFFFFFF);
        graphics.drawCenteredString(this.font, Component.literal(hint), centerX, centerY + 4, 0xFFCCCCCC);
    }
}
