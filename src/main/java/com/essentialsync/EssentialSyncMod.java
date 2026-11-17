package com.essentialsync;

import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import org.slf4j.Logger;

@Mod(EssentialSyncMod.MODID)
public class EssentialSyncMod {
    public static final String MODID = "essentialsync";
    public static final Logger LOGGER = LogUtils.getLogger();

    public EssentialSyncMod(ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, SyncConfig.SPEC);
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            modContainer.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        }
        LOGGER.info("EssentialSync initialized");
    }
}
