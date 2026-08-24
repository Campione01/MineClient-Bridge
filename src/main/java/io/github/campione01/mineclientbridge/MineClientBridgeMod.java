package io.github.campione01.mineclientbridge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.apache.logging.log4j.Logger;

@Mod(value = MineClientBridgeMod.MOD_ID, dist = Dist.CLIENT)
public final class MineClientBridgeMod {
    public static final String MOD_ID = "mineclient_bridge";
    public static final Logger LOGGER = BridgeLog.LOGGER;

    public MineClientBridgeMod(IEventBus modEventBus) {
        BridgeServer.start();
        Runtime.getRuntime().addShutdownHook(new Thread(
                BridgeServer::stop,
                "MineClient-Bridge-Shutdown"));
    }
}
