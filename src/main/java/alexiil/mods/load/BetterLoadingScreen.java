package alexiil.mods.load;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Objects;

import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.common.MinecraftForge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.eventbus.EventBus;

import cpw.mods.fml.client.event.ConfigChangedEvent.OnConfigChangedEvent;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.FMLModContainer;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.EventHandler;
import cpw.mods.fml.common.Mod.Instance;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.ModMetadata;
import cpw.mods.fml.common.event.FMLConstructionEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerAboutToStartEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@Mod(
        modid = Tags.MOD_ID,
        version = Tags.MOD_VERSION,
        name = Tags.MOD_NAME,
        acceptedMinecraftVersions = "[1.7.10]",
        acceptableRemoteVersions = "*")
public class BetterLoadingScreen {

    @Instance(Tags.MOD_ID)
    public static BetterLoadingScreen instance;

    public static final Logger log = LogManager.getLogger(Tags.MOD_ID);
    public static ModMetadata meta;

    @EventHandler
    public void construct(FMLConstructionEvent event) throws IOException {
        ModLoadingListener thisListener = null;
        for (ModContainer mod : Loader.instance().getActiveModList()) {
            if (mod instanceof FMLModContainer) {
                EventBus bus = null;
                try {
                    // It's a bit questionable to be changing FML itself, but reflection is better than ASM transforming
                    // forge
                    Field f = FMLModContainer.class.getDeclaredField("eventBus");
                    f.setAccessible(true);
                    bus = (EventBus) f.get(mod);
                } catch (Throwable t) {
                    t.printStackTrace();
                }
                if (bus != null) {
                    if (mod.getModId().equals(Tags.MOD_ID)) {
                        thisListener = new ModLoadingListener(mod);
                        bus.register(thisListener);
                    } else bus.register(new ModLoadingListener(mod));
                }
            }
        }
        if (thisListener != null) {
            ModLoadingListener.doProgress(ModLoadingListener.State.CONSTRUCT, thisListener);
        }
    }

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(instance);
        FMLCommonHandler.instance().bus().register(instance);
        meta = event.getModMetadata();
    }

    @SubscribeEvent
    @SideOnly(Side.CLIENT)
    public void guiOpen(GuiOpenEvent event) throws IOException {
        ProgressDisplayer.close();
    }

    @SubscribeEvent
    public void configChanged(OnConfigChangedEvent event) {
        if (Objects.equals(event.modID, Tags.MOD_ID)) ProgressDisplayer.cfg.save();
    }

    @EventHandler
    @SideOnly(Side.SERVER)
    public void serverAboutToStart(FMLServerAboutToStartEvent event) throws IOException {
        ProgressDisplayer.close();
    }
}
