package com.essentialsync.client;

import com.essentialsync.EssentialSyncMod;
import com.essentialsync.sync.WorldSyncService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Renders a small top-left overlay on any screen while an EssentialSync upload is in progress.
 * This lets the user navigate freely (title screen, world list, etc.) while their world
 * syncs to the cloud in the background.
 *
 * Layout (top-left corner):
 *   ┌──────────────────────────┐
 *   │ ↑ EssentialSync syncing  │
 *   │ Uploading region/r.0.0…  │
 *   │ ████████░░░░░░░  12/48f  │
 *   └──────────────────────────┘
 */
@EventBusSubscriber(modid = EssentialSyncMod.MODID, value = Dist.CLIENT)
public final class SyncHudOverlay {

    private static final String[] SPINNER = {"|", "/", "-", "\\"};
    private static final int PANEL_W = 192;
    private static final int PANEL_H = 42;
    private static final int PAD = 4;

    private SyncHudOverlay() {
    }

    @SubscribeEvent
    public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        WorldSyncService.UploadStatus status = WorldSyncService.getHudStatus();
        if (status == null) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        GuiGraphics graphics = event.getGuiGraphics();
        Font font = mc.font;

        int x = PAD;
        int y = PAD;

        // Semi-transparent dark background
        graphics.fill(x - 2, y - 2, x + PANEL_W + 2, y + PANEL_H + 2, 0xBB000000);

        // Top row: spinner + title
        int spinIdx = (int) ((System.currentTimeMillis() / 150L) % SPINNER.length);
        String header = SPINNER[spinIdx] + " EssentialSync uploading";
        graphics.drawString(font, header, x, y, 0xFF44DD88, false);

        // Middle row: current phase (truncated)
        String phase = status.phase();
        int maxPhaseChars = 30;
        if (phase.length() > maxPhaseChars) {
            phase = phase.substring(0, maxPhaseChars - 3) + "...";
        }
        graphics.drawString(font, phase, x, y + 11, 0xFFCCCCCC, false);

        // File count on right side of middle row
        if (status.totalFiles() > 0) {
            String fileLabel = status.completedFiles() + "/" + status.totalFiles() + "f";
            int labelX = x + PANEL_W - font.width(fileLabel);
            graphics.drawString(font, fileLabel, labelX, y + 11, 0xFF888888, false);
        }

        // Progress bar
        int barY = y + 25;
        int barH = 6;
        graphics.fill(x, barY, x + PANEL_W, barY + barH, 0xFF222222);

        float progress = status.progress();
        int filled = Math.max(0, Math.min(PANEL_W, (int) (PANEL_W * progress)));
        if (filled > 0) {
            // Gradient: dark green → bright green
            graphics.fill(x, barY, x + filled, barY + barH, 0xFF3CB371);
        }

        // Byte progress inside / next to bar
        if (status.totalBytes() > 0) {
            String byteLabel = formatBytes(status.completedBytes()) + " / " + formatBytes(status.totalBytes());
            int labelX = x + PANEL_W - font.width(byteLabel);
            graphics.drawString(font, byteLabel, labelX, barY + barH + 2, 0xFF666666, false);
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
