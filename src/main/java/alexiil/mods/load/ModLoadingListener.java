package alexiil.mods.load;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.minecraftforge.common.MinecraftForge;

import com.google.common.eventbus.Subscribe;

import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.event.FMLConstructionEvent;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLLoadCompleteEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.relauncher.FMLLaunchHandler;

public class ModLoadingListener {

    public enum State {

        CONSTRUCT("construction"),
        PRE_INIT("pre_initialization"),
        LITE_LOADER_INIT("lite", true, true),
        INIT("initialization"),
        POST_INIT("post_initialization"),
        LOAD_COMPLETE("completed"),
        FINAL_LOADING("reloading_resource_packs", true, false);

        private String translatedName = null;
        final String name;
        /**
         * If this state is only called once. This is false for all except for FINAL_LOADING
         */
        final boolean isLoneState;
        /**
         * If this is true, then ModStage.getNext will skip this, but it will still be included in the percentage
         * calculation
         */
        final boolean shouldSkip;

        State(String name, boolean mods, boolean skip) {
            isLoneState = mods;
            this.name = name;
            shouldSkip = skip;
        }

        State(String name) {
            this(name, false, false);
        }
    }

    private static class ModStage {

        public final State state;

        @Override
        public String toString() {
            return "ModStage [state=" + state + ", index=" + index + "]";
        }

        public final int index;

        public ModStage(State state, int index) {
            this.state = state;
            this.index = index;
        }

        public ModStage getNext() {
            int ind = index + 1;
            State s = state;
            if (ind == listeners.size() || s.isLoneState) {
                ind = 0;
                int ord = s.ordinal() + 1;
                if (ord == State.values().length) return null;
                s = State.values()[ord];
                if (s.shouldSkip) return new ModStage(s, ind).getNext();
            }
            return new ModStage(s, ind);
        }

        public float getProgress() {
            float values = 100 / (float) (State.values().length - 1);
            float part = state.ordinal() * values;
            float size = listeners.size();
            float percent = values * index / size;
            return part + percent;
        }
    }

    private static List<ModLoadingListener> listeners = new ArrayList<>();
    private static ModStage stage = null;

    private final ModContainer mod;

    public ModLoadingListener(ModContainer mod) {
        this.mod = mod;
        if (listeners.isEmpty()) MinecraftForge.EVENT_BUS.register(this);
        listeners.add(this);
    }

    @Subscribe
    public void construct(FMLConstructionEvent event) throws IOException {
        doProgress(State.CONSTRUCT, this);
    }

    @Subscribe
    public void preinit(FMLPreInitializationEvent event) throws IOException {
        doProgress(State.PRE_INIT, this);
    }

    @Subscribe
    public void init(FMLInitializationEvent event) throws IOException {
        doProgress(State.INIT, this);
    }

    @Subscribe
    public void postinit(FMLPostInitializationEvent event) throws IOException {
        doProgress(State.POST_INIT, this);
    }

    @Subscribe
    public void loadComplete(FMLLoadCompleteEvent event) throws IOException {
        doProgress(State.LOAD_COMPLETE, this);
    }

    public static void doProgress(State state, ModLoadingListener mod) throws IOException {
        if (stage == null) if (mod == null) stage = new ModStage(state, 0);
        else stage = new ModStage(state, listeners.indexOf(mod));
        float percent = stage.getProgress() / 100F;
        stage = stage.getNext();
        if (stage.state == State.FINAL_LOADING) {
            percent = stage.getProgress() / 100F;
        }
        if (FMLLaunchHandler.side().isClient()) {
            ProgressDisplayer.displayProgress(percent);
        }
    }
}
