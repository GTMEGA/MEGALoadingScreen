package alexiil.mods.load;

import alexiil.mods.load.ProgressDisplayer.IDisplayer;

public class MinecraftDisplayerWrapper implements IDisplayer {

    private MinecraftDisplayer mcDisp;

    @Override
    public void open() {
    }

    @Override
    public void displayProgress(float percent) {
        if (mcDisp == null) {
            try {
                mcDisp = new MinecraftDisplayer();
                mcDisp.open();
            } catch (Throwable t) {
                BetterLoadingScreen.log.error("Failed to load Minecraft Displayer!");
                t.printStackTrace();
                mcDisp = null;
            }
        }
        if (mcDisp != null) mcDisp.displayProgress(percent);
    }

    @Override
    public void close() {
        if (mcDisp != null) mcDisp.close();
    }

    public static void playFinishedSound() {
        MinecraftDisplayer.playFinishedSound();
    }
}
