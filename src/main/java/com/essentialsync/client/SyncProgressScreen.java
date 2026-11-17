package com.essentialsync.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Full-screen progress display used only during the initial downstream world sync
 * (when loading the managed world from S3). The player must wait for the download
 * to complete before the world opens.
 *
 * Upload progress after quitting uses the non-blocking {@link SyncHudOverlay} instead.
 */
public class SyncProgressScreen extends Screen {

    private volatile String statusText;
    private volatile long completedBytes;
    private volatile long totalBytes;
    private volatile int completedFiles;
    private volatile int totalFiles;

    public SyncProgressScreen(String titleText, String initialStatus) {
        super(Component.literal(titleText));
        this.statusText = initialStatus;
    }

    public void updateProgress(String status, long completedBytes, long totalBytes, int completedFiles, int totalFiles) {
        this.statusText = status;
        this.completedBytes = completedBytes;
        this.totalBytes = totalBytes;
        this.completedFiles = completedFiles;
        this.totalFiles = totalFiles;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        int centerY = this.height / 2;

        graphics.drawCenteredString(this.font, this.title, centerX, centerY - 40, 0xFFFFFFFF);
        graphics.drawCenteredString(this.font, Component.literal(statusText), centerX, centerY - 18, 0xFFCCCCCC);

        int barWidth = 240;
        int barHeight = 10;
        int barLeft = centerX - barWidth / 2;
        int barTop = centerY;

        // Bar border + background
        graphics.fill(barLeft - 1, barTop - 1, barLeft + barWidth + 1, barTop + barHeight + 1, 0xFF555555);
        graphics.fill(barLeft, barTop, barLeft + barWidth, barTop + barHeight, 0xFF1A1A1A);

        float progress = 0.0f;
        if (totalBytes > 0) {
            progress = Math.min(1.0f, Math.max(0.0f, (float) completedBytes / (float) totalBytes));
        } else if (totalFiles > 0) {
            progress = Math.min(1.0f, Math.max(0.0f, (float) completedFiles / (float) totalFiles));
        }
        int filled = (int) (barWidth * progress);
        if (filled > 0) {
            graphics.fill(barLeft, barTop, barLeft + filled, barTop + barHeight, 0xFF3CB371);
        }

        if (totalFiles > 0 || totalBytes > 0) {
            String stats = String.format(
                    "%d / %d files  (%s / %s)",
                    completedFiles, totalFiles,
                    formatBytes(completedBytes), formatBytes(totalBytes)
            );
            graphics.drawCenteredString(this.font, Component.literal(stats), centerX, centerY + 18, 0xFFAAAAAA);
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024.0) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        return String.format("%.1f MB", mb);
    }
}
