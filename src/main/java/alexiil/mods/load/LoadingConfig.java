package alexiil.mods.load;

import com.falsepattern.lib.config.Config;
import com.falsepattern.lib.config.ConfigurationManager;

public class LoadingConfig {
    @Config(modid = Tags.MOD_ID,
            customPath = "Betterloadingscreen/betterloadingscreen.cfg")
    public static class Dynamic {
        @Config.Comment("Play a sound after minecraft has finished starting up")
        @Config.DefaultBoolean(true)
        public static boolean playSound;

        @Config.Comment({
                "What font texture to use? Special Cases:",
                " - If you use the Russian mod \"Client Fixer\" then change this to \"textures/font/ascii_fat.png\"",
                "",
                "Note: if a resourcepack adds a font, it will be used by BLS."
        })
        @Config.DefaultString("textures/font/ascii.png")
        public static String font;

        static {
            ConfigurationManager.selfInit();
        }
    }

    public static class Fixed {

        public static final String[] randomBackgroundArray = new String[] {
                "betterloadingscreen:textures/backgrounds/background1.png",
                "betterloadingscreen:textures/backgrounds/background2.png",
                "betterloadingscreen:textures/backgrounds/background3.png",
                "betterloadingscreen:textures/backgrounds/background4.png"
        };
        static final String sound = "betterloadingscreen:loaded";
        static final String GTprogress = "betterloadingscreen:textures/GTMaterialsprogressBars.png";
        static final String progress = "betterloadingscreen:textures/mainProgressBar.png";
        static final String GTprogressAnimated = "betterloadingscreen:textures/GTMaterialsprogressBars.png";
        static final String progressAnimated = "betterloadingscreen:textures/mainProgressBar.png";
        // Coordinate format: {texture x, y, w, h, on-screen x, y, w, h}
        /*
         * private int[] GTprogressPos = new int[] {0, 0, 172, 12, 0, -83, 172, 6}; private int[] GTprogressPosAnimated =
         * new int[] {0, 12, 172, 12, 0, -83, 172, 6};
         */
        static final int[] GTprogressPos = new int[] { 0, 0, 256, 64, 0, 0, 0, 6 };
        static final int[] GTprogressPosAnimated = new int[] {0, 128, 256, 64, 0, 0, 0, 6 };
        static final int[] progressPos = new int[] {0, 0, 256, 64, 0, 0, 0, 6 };
        static final int[] progressPosAnimated = new int[] {0, 128, 256, 64, 0, 0, 0, 6 };
        static final int[] tipsTextPos = new int[] {0, 10 };
        static final String baseTipsTextPos = "BOTTOM_CENTER";
        static final boolean tipsEnabled = true;
        static final String tipsColor = "f0f0f0";
        static final boolean tipsTextShadow = true;
        static final int tipsChangeFrequency = 40;
        static final boolean useCustomTips = false;
        static final String customTipFile = "en_US";
        static final boolean randomBackgrounds = true;
        static final int changeFrequency = 10;
        static final float blendTimeMillis = 2000;
        static final String loadingBarsColor = "2d2dff";
        static final float loadingBarsAlpha = 0.9F;
    }
}
