package alexiil.mods.load;

import static org.lwjgl.opengl.GL11.*;

import java.awt.Color;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.audio.SoundEventAccessorComposite;
import net.minecraft.client.audio.SoundHandler;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.IResourcePack;
import net.minecraft.client.resources.LanguageManager;
import net.minecraft.util.ResourceLocation;

import lombok.val;
import org.lwjglx.LWJGLException;
import org.lwjglx.opengl.Display;
import org.lwjgl.opengl.GL11;

import alexiil.mods.load.ProgressDisplayer.IDisplayer;
import alexiil.mods.load.json.Area;
import alexiil.mods.load.json.EPosition;
import alexiil.mods.load.json.EType;
import alexiil.mods.load.json.ImageRender;
import org.lwjglx.opengl.SharedDrawable;

import net.minecraftforge.client.event.sound.PlaySoundSourceEvent;
import net.minecraftforge.common.MinecraftForge;
import cpw.mods.fml.client.FMLFileResourcePack;
import cpw.mods.fml.client.FMLFolderResourcePack;
import cpw.mods.fml.client.SplashProgress;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

public class MinecraftDisplayer implements IDisplayer {

    private List<ImageRender> images = new ArrayList<>();
    private TextureManager textureManager = null;
    private Map<String, FontRenderer> fontRenderers = new HashMap<String, FontRenderer>();
    private FontRenderer fontRenderer = null;
    private ScaledResolution resolution = null;
    private Minecraft mc = null;
    private IResourcePack myPack;
    private float clearRed = 1, clearGreen = 1, clearBlue = 1;
    public static float lastPercent = 0;
    private List<String> alreadyUsedBGs = new ArrayList<>();
    private List<String> alreadyUsedTooltips = new ArrayList<>();
    private String background = LoadingConfig.Fixed.randomBackgroundArray[0];
    private String[] randomTips;
    private String tip = "";
    private float[] lbRGB = new float[] { 1, 1, 0 };

    public static boolean isRegisteringGTmaterials = false;
    public static boolean isReplacingVanillaMaterials = false;
    public static boolean isRegisteringBartWorks = false;
    public static volatile boolean blending = false;
    public static volatile boolean blendingJustSet = false;
    public static volatile float blendAlpha = 1F;
    public static volatile long blendStartMillis = 0;
    private static String newBlendImage = "none";

    private ScheduledExecutorService backgroundExec = null;
    private boolean scheduledTipExecSet = false;

    private ScheduledExecutorService tipExec = null;
    private boolean scheduledBackgroundExecSet = false;

    private Thread splashRenderThread = null;
    private boolean splashRenderKillSwitch = false;
    /**
     * During the load phase, the main thread still needs to access OpenGL to load textures, etc. To achieve this, the
     * splash render thread takes over the main context, and the main thread is assigned this shared context. A context
     * can only be active in one thread at a time, hence this solution (inspired by FML's SplashProgress implementation)
     */
    private SharedDrawable loadingDrawable = null;

    private float currentPercent = 0;

    /**
     * Called from gregtech
     */
    @SuppressWarnings({
            "LombokGetterMayBeUsed",
            "RedundantSuppression"
    })
    public static float getLastPercent() {
        return lastPercent;
    }

    public static void playFinishedSound() {
        val played = new AtomicBoolean(false);
        val handler = new Handler(played);
        MinecraftForge.EVENT_BUS.register(handler);
        try {
            SoundHandler soundHandler = Minecraft.getMinecraft().getSoundHandler();
            ResourceLocation location = new ResourceLocation(LoadingConfig.Fixed.sound);
            SoundEventAccessorComposite snd = soundHandler.getSound(location);
            if (snd == null) {
                BetterLoadingScreen.log.warn("The sound given (" + LoadingConfig.Fixed.sound + ") did not give a valid sound!");
                return;
            }
            ISound sound = PositionedSoundRecord.func_147673_a(location);
            for (int i = 0; i < 100; i++) {
                if (played.get()) {
                    break;
                } else {
                    try {
                        soundHandler.playSound(sound);
                    } catch (IllegalArgumentException ignored) {
                    }
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        } finally {
            MinecraftForge.EVENT_BUS.unregister(handler);
        }
    }

    @SuppressWarnings("unchecked")
    private List<IResourcePack> getOnlyList() {
        Field[] flds = mc.getClass().getDeclaredFields();
        for (Field f : flds) {
            if (f.getType().equals(List.class) && !Modifier.isStatic(f.getModifiers())) {
                f.setAccessible(true);
                try {
                    return (List<IResourcePack>) f.get(mc);
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        }
        return null;
    }

    public String randomBackground(String currentBG) {
        if (LoadingConfig.Fixed.randomBackgroundArray.length == 1) {
            return LoadingConfig.Fixed.randomBackgroundArray[0];
        }
        Random rand = new Random();
        String res = LoadingConfig.Fixed.randomBackgroundArray[rand.nextInt(LoadingConfig.Fixed.randomBackgroundArray.length)];
        if (LoadingConfig.Fixed.randomBackgroundArray.length == alreadyUsedBGs.size()) {
            alreadyUsedBGs.clear();
        }
        while (res.equals(currentBG) || alreadyUsedBGs.contains(res)) {
            res = LoadingConfig.Fixed.randomBackgroundArray[rand.nextInt(LoadingConfig.Fixed.randomBackgroundArray.length)];
        }
        alreadyUsedBGs.add(res);
        return res;
    }

    public String randomTooltip(String currentTooltip) {
        if (randomTips.length == 1) {
            return randomTips[0];
        }
        Random rand = new Random();
        String res = randomTips[rand.nextInt(randomTips.length)];
        if (randomTips.length == alreadyUsedTooltips.size()) {
            alreadyUsedTooltips.clear();
        }
        while (res.equals(currentTooltip) || alreadyUsedTooltips.contains(res)) {
            res = randomTips[rand.nextInt(randomTips.length)];
        }
        alreadyUsedTooltips.add(res);
        return res;
    }

    public static String[] readTipsFile(String file) throws IOException {
        BufferedReader reader;
        List<String> lines = new ArrayList<>();
        try {
            reader = new BufferedReader((new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))); // new
            StringBuffer inputBuffer = new StringBuffer();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.charAt(0) != '#') {
                    lines.add(line);
                }
                inputBuffer.append(line);
                inputBuffer.append('\n');
            }
            if (lines.size() == 0) {
                lines.add("No tips!");
            }
            reader.close();

            FileOutputStream fileOut = new FileOutputStream(file);
            fileOut.write(inputBuffer.toString().getBytes(StandardCharsets.UTF_8));
            fileOut.close();
        } catch (FileNotFoundException e) {
            BetterLoadingScreen.log.error("Error while opening tips file");
            return new String[] { "Failed to load tips! If you didn't do anything, complain on the GTNH Discord" };
        }
        return lines.toArray(new String[0]);
    }

    public static void placeTipsFile() throws IOException {
        String locale;
        if (!LoadingConfig.Fixed.useCustomTips) {
            BetterLoadingScreen.log.info("Not using custom tooltips");
            locale = Minecraft.getMinecraft().getLanguageManager().getCurrentLanguage().getLanguageCode();
            if (locale.length() > 5) {
                locale = locale.substring(0, 5);
            }
        } else {
            locale = LoadingConfig.Fixed.customTipFile;
            BetterLoadingScreen.log.info("Using custom tooltips, name: " + locale);
        }
        InputStream fileContents;
        try {
            fileContents = Minecraft.getMinecraft().getResourceManager()
                    .getResource(new ResourceLocation("betterloadingscreen:tips/" + locale + ".txt")).getInputStream();
        } catch (Exception e) {
            fileContents = Minecraft.getMinecraft().getResourceManager()
                    .getResource(new ResourceLocation("betterloadingscreen:tips/en_US.txt")).getInputStream();
            locale = "en_US";
            BetterLoadingScreen.log.info("Language not found");
        }
        byte[] buffer = new byte[fileContents.available()];
        fileContents.read(buffer);
        File dir = new File("./config/Betterloadingscreen/tips");
        if (!dir.exists()) {
            BetterLoadingScreen.log.warn("tips dir does not exist");
            dir.mkdirs();
        } else {
            BetterLoadingScreen.log.debug("tips dir exists");
        }
        BetterLoadingScreen.log.debug("Current locale: " + locale);
        File dest = new File("./config/Betterloadingscreen/tips/" + locale + ".txt");
        BetterLoadingScreen.log.debug("dest set");
        OutputStream outStream = new FileOutputStream(dest);
        outStream.write(buffer);
        outStream.close();
    }

    public void handleTips() {
        String locale = "en_US";
        if (!LoadingConfig.Fixed.useCustomTips) {
            BetterLoadingScreen.log.info("Not using custom tooltips");
            locale = Minecraft.getMinecraft().getLanguageManager().getCurrentLanguage().getLanguageCode();
            BetterLoadingScreen.log.debug("Locale is: " + locale);
            if (locale.length() > 5) {
                BetterLoadingScreen.log.debug("locale before trimming: " + locale);
                locale = locale.substring(0, 5);
            }
        } else {
            locale = LoadingConfig.Fixed.customTipFile;
            BetterLoadingScreen.log.info("Using custom tooltips, name: " + locale);
        }
        // BetterLoadingScreen.log.trace("Language is: " + locale);
        File tipsCheck = new File("./config/Betterloadingscreen/tips/" + locale + ".txt");
        if (tipsCheck.exists()) {
            BetterLoadingScreen.log.debug("Tips file exists");
            try {
                // log.info("Using locale " + locale + "(3)");
                randomTips = readTipsFile("./config/Betterloadingscreen/tips/" + locale + ".txt");
                Random rand = new Random();
                tip = randomTips[rand.nextInt(randomTips.length)];
                // BetterLoadingScreen.log.trace("choosing first tip: "+tip);
                // hmm trying to schedule tip changing
                if (!scheduledTipExecSet) {
                    // BetterLoadingScreen.log.trace("Setting tip exec");
                    // BetterLoadingScreen.log.trace("List of tips length: "+String.valueOf(randomTips.length));
                    scheduledTipExecSet = true;
                    tipExec = Executors.newSingleThreadScheduledExecutor();
                    tipExec.scheduleAtFixedRate(new Runnable() {

                        @Override
                        public void run() {
                            tip = randomTooltip(tip);
                        }
                    }, LoadingConfig.Fixed.tipsChangeFrequency, LoadingConfig.Fixed.tipsChangeFrequency, TimeUnit.SECONDS);
                }
            } catch (IOException e) {
                BetterLoadingScreen.log.error("./config/Betterloadingscreen/tips/" + locale + ".txt");
                e.printStackTrace();
            }
        } else {
            try {
                // BetterLoadingScreen.log.trace("Using locale " + locale + "(4)");
                tipsCheck = new File("./config/Betterloadingscreen/tips/" + locale + ".txt");
                // BetterLoadingScreen.log.trace("Checking if "+locale+".txt exists");
                if (tipsCheck.exists()) {
                    // BetterLoadingScreen.log.trace("Using locale " + locale + "(5)");
                    randomTips = readTipsFile("./config/Betterloadingscreen/" + locale + ".txt");
                } else {
                    tipsCheck = new File("./config/Betterloadingscreen/tips/en_US.txt");
                    if (!tipsCheck.exists()) {
                        // BetterLoadingScreen.log.trace("Placing tips");
                        placeTipsFile();
                    }
                    randomTips = readTipsFile("./config/Betterloadingscreen/tips/en_US.txt");
                }
                Random rand = new Random();
                tip = randomTips[rand.nextInt(randomTips.length)];
                // BetterLoadingScreen.log.trace("choosing first tip: "+tip);
                if (!scheduledTipExecSet) {
                    // BetterLoadingScreen.log.trace("Setting tip exec");
                    // BetterLoadingScreen.log.trace("List of tips length: "+String.valueOf(randomTips.length));
                    scheduledTipExecSet = true;
                    tipExec = Executors.newSingleThreadScheduledExecutor();
                    tipExec.scheduleAtFixedRate(new Runnable() {

                        @Override
                        public void run() {
                            tip = randomTooltip(tip);
                        }
                    }, LoadingConfig.Fixed.tipsChangeFrequency, LoadingConfig.Fixed.tipsChangeFrequency, TimeUnit.SECONDS);
                }
            } catch (IOException e) {
                BetterLoadingScreen.log.error("Error handling new tips file");
                e.printStackTrace();
            }
        }
    }

    // Minecraft's display hasn't been created yet, so don't bother trying to do anything now
    @Override
    public void open() {
        mc = Minecraft.getMinecraft();
        try {
            lbRGB[0] = (float) (Color.decode("#" + LoadingConfig.Fixed.loadingBarsColor).getRed() & 255) / 255.0f;
            lbRGB[1] = (float) (Color.decode("#" + LoadingConfig.Fixed.loadingBarsColor).getGreen() & 255) / 255.0f;
            lbRGB[2] = (float) (Color.decode("#" + LoadingConfig.Fixed.loadingBarsColor).getBlue() & 255) / 255.0f;
        } catch (Exception e) {
            lbRGB[0] = 1;
            lbRGB[1] = 0.5176471f;
            lbRGB[2] = 0;
            BetterLoadingScreen.log.warn("Invalid loading bar color, setting default");
        }

        // Add ourselves as a resource pack
        if (!ProgressDisplayer.coreModLocation.isDirectory())
            myPack = new FMLFileResourcePack(ProgressDisplayer.modContainer);
        else myPack = new FMLFolderResourcePack(ProgressDisplayer.modContainer);
        getOnlyList().add(myPack);
        mc.refreshResources();

        handleTips();

        if (LoadingConfig.Fixed.randomBackgrounds) {
            // BetterLoadingScreen.log.trace("choosing first random bg");
            Random rand = new Random();
            background = LoadingConfig.Fixed.randomBackgroundArray[rand.nextInt(LoadingConfig.Fixed.randomBackgroundArray.length)];

            /// timer
            if (!scheduledBackgroundExecSet) {
                // BetterLoadingScreen.log.trace("Setting background exec");
                scheduledBackgroundExecSet = true;
                backgroundExec = Executors.newSingleThreadScheduledExecutor();
                backgroundExec.scheduleAtFixedRate(new Runnable() {

                    @Override
                    public void run() {
                        if (!blending /*
                                       * && !isRegisteringBartWorks && !isRegisteringGTmaterials &&
                                       * !isReplacingVanillaMaterials
                                       */) {
                            MinecraftDisplayer.blendingJustSet = true;
                            MinecraftDisplayer.blendAlpha = 1;
                            MinecraftDisplayer.blendStartMillis = System.currentTimeMillis();
                            MinecraftDisplayer.blending = true;
                        }
                    }
                }, LoadingConfig.Fixed.changeFrequency, LoadingConfig.Fixed.changeFrequency, TimeUnit.SECONDS);
            }
        }
    }

    @Override
    public void displayProgress(float percent) {
        currentPercent = percent;
        if (splashRenderThread == null) {
            try {
                loadingDrawable = new SharedDrawable(Display.getDrawable());
                Display.getDrawable().releaseContext();
                loadingDrawable.makeCurrent();
            } catch (LWJGLException e) {
                e.printStackTrace();
                throw new RuntimeException(e); // work around checked exceptions
            }
            splashRenderThread = new Thread(new Runnable() {

                /**
                 * Has to be locked while running Display.update()
                 */
                Semaphore fmlMutex;

                @Override
                public void run() {
                    try {
                        Field f = SplashProgress.class.getDeclaredField("mutex");
                        f.setAccessible(true);
                        fmlMutex = (Semaphore) f.get(null);
                        Display.getDrawable().makeCurrent();
                    } catch (Exception e) {
                        e.printStackTrace();
                        throw new RuntimeException(e);
                    }
                    while (!MinecraftDisplayer.this.splashRenderKillSwitch) {
                        resetGlState();
                        try {
                            displayProgressInWorkerThread(currentPercent);
                        } catch (Exception e) {
                            BetterLoadingScreen.log.warn("BLS splash error: ", e);
                        }

                        fmlMutex.acquireUninterruptibly();
                        Display.update();
                        fmlMutex.release();
                        Display.sync(60);
                    }
                    resetGlState();
                    try {
                        Display.getDrawable().releaseContext();
                    } catch (LWJGLException e) {
                        e.printStackTrace();
                        throw new RuntimeException(e);
                    }
                }

                private void resetGlState() {
                    Minecraft mc = Minecraft.getMinecraft();
                    int w = Display.getWidth();
                    int h = Display.getHeight();
                    mc.displayWidth = w;
                    mc.displayHeight = h;
                    GL11.glClearColor(0, 0, 0, 1);
                    GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
                    GL11.glEnable(GL_DEPTH_TEST);
                    GL11.glDepthFunc(GL_LEQUAL);
                    GL11.glEnable(GL_ALPHA_TEST);
                    GL11.glAlphaFunc(GL_GREATER, .1f);
                    GL11.glViewport(0, 0, w, h);
                    GL11.glMatrixMode(GL_PROJECTION);
                    GL11.glLoadIdentity();
                    GL11.glOrtho(320 - w / 2, 320 + w / 2, 240 + h / 2, 240 - h / 2, -1, 1);
                    GL11.glMatrixMode(GL_MODELVIEW);
                    GL11.glLoadIdentity();
                }
            });
            splashRenderThread.setName("BLS Splash renderer");
            splashRenderThread.setDaemon(true);
            splashRenderThread.setUncaughtExceptionHandler(
                    (Thread t, Throwable e) -> {
                        BetterLoadingScreen.log.error("BetterLodingScreen thread exception", e);
                    });
            splashRenderThread.start();
            if (splashRenderThread.getState() == Thread.State.TERMINATED) {
                throw new IllegalStateException("BetterLoadingScreen splash thread terminated upon start");
            }
        }
    }

    public void displayProgressInWorkerThread(float percent) {
        images.clear();
        if (alexiil.mods.load.MinecraftDisplayer.isRegisteringGTmaterials || isReplacingVanillaMaterials
                || isRegisteringBartWorks) {
            // background
            if (!background.isEmpty()) {
                images.add(new ImageRender(
                        background,
                        EPosition.TOP_LEFT,
                        EType.STATIC_BLENDED,
                        new Area(0, 0, 256, 256),
                        new Area(0, 0, 0, 0)));
            } else {
                images.add(new ImageRender(
                        "betterloadingscreen:textures/transparent.png",
                        EPosition.TOP_LEFT,
                        EType.STATIC,
                        new Area(0, 0, 256, 256),
                        new Area(0, 0, 10, 10)));
            }
            // Static NORMAL bar image
            images.add(new ImageRender(
                    LoadingConfig.Fixed.progress,
                    EPosition.BOTTOM_CENTER,
                    EType.STATIC,
                    new Area(LoadingConfig.Fixed.progressPos[0], LoadingConfig.Fixed.progressPos[1], LoadingConfig.Fixed.progressPos[2], LoadingConfig.Fixed.progressPos[3]),
                    new Area(LoadingConfig.Fixed.progressPos[4], LoadingConfig.Fixed.progressPos[5], LoadingConfig.Fixed.progressPos[6], LoadingConfig.Fixed.progressPos[7])));
            // Dynamic NORMAL bar image (yellow thing)
            images.add(new ImageRender(
                    LoadingConfig.Fixed.progress,
                    EPosition.BOTTOM_CENTER,
                    EType.DYNAMIC_PERCENTAGE,
                    new Area(
                            LoadingConfig.Fixed.progressPosAnimated[0],
                            LoadingConfig.Fixed.progressPosAnimated[1],
                            LoadingConfig.Fixed.progressPosAnimated[2],
                            LoadingConfig.Fixed.progressPosAnimated[3]),
                    new Area(
                            LoadingConfig.Fixed.progressPosAnimated[4],
                            LoadingConfig.Fixed.progressPosAnimated[5],
                            LoadingConfig.Fixed.progressPosAnimated[6],
                            LoadingConfig.Fixed.progressPosAnimated[7])));
            // Static GT bar image
            images.add(new ImageRender(
                    LoadingConfig.Fixed.GTprogress,
                    EPosition.BOTTOM_CENTER,
                    EType.STATIC,
                    new Area(LoadingConfig.Fixed.GTprogressPos[0], LoadingConfig.Fixed.GTprogressPos[1], LoadingConfig.Fixed.GTprogressPos[2], LoadingConfig.Fixed.GTprogressPos[3]),
                    new Area(LoadingConfig.Fixed.GTprogressPos[4], LoadingConfig.Fixed.GTprogressPos[5], LoadingConfig.Fixed.GTprogressPos[6], LoadingConfig.Fixed.GTprogressPos[7])));
            // Dynamic GT bar image (yellow thing)
            images.add(new ImageRender(
                    LoadingConfig.Fixed.GTprogressAnimated,
                    EPosition.BOTTOM_CENTER,
                    EType.DYNAMIC_PERCENTAGE,
                    new Area(
                            LoadingConfig.Fixed.GTprogressPosAnimated[0],
                            LoadingConfig.Fixed.GTprogressPosAnimated[1],
                            LoadingConfig.Fixed.GTprogressPosAnimated[2],
                            LoadingConfig.Fixed.GTprogressPosAnimated[3]),
                    new Area(
                            LoadingConfig.Fixed.GTprogressPosAnimated[4],
                            LoadingConfig.Fixed.GTprogressPosAnimated[5],
                            LoadingConfig.Fixed.GTprogressPosAnimated[6],
                            LoadingConfig.Fixed.GTprogressPosAnimated[7])));
        } else {
            // background
            if (!background.isEmpty()) {
                images.add(new ImageRender(
                        background,
                        EPosition.TOP_LEFT,
                        EType.STATIC_BLENDED,
                        new Area(0, 0, 256, 256),
                        new Area(0, 0, 0, 0)));
            } else {
                images.add(new ImageRender(
                        "betterloadingscreen:textures/transparent.png",
                        EPosition.TOP_LEFT,
                        EType.STATIC,
                        new Area(0, 0, 256, 256),
                        new Area(0, 0, 10, 10)));
            }
            // Static NORMAL bar image
            images.add(new ImageRender(
                    LoadingConfig.Fixed.progress,
                    EPosition.BOTTOM_CENTER,
                    EType.STATIC,
                    new Area(LoadingConfig.Fixed.progressPos[0], LoadingConfig.Fixed.progressPos[1], LoadingConfig.Fixed.progressPos[2], LoadingConfig.Fixed.progressPos[3]),
                    new Area(LoadingConfig.Fixed.progressPos[4], LoadingConfig.Fixed.progressPos[5], LoadingConfig.Fixed.progressPos[6], LoadingConfig.Fixed.progressPos[7])));
            // Dynamic NORMAL bar image (yellow thing)
            images.add(new ImageRender(
                    LoadingConfig.Fixed.progressAnimated,
                    EPosition.BOTTOM_CENTER,
                    EType.DYNAMIC_PERCENTAGE,
                    new Area(
                            LoadingConfig.Fixed.progressPosAnimated[0],
                            LoadingConfig.Fixed.progressPosAnimated[1],
                            LoadingConfig.Fixed.progressPosAnimated[2],
                            LoadingConfig.Fixed.progressPosAnimated[3]),
                    new Area(
                            LoadingConfig.Fixed.progressPosAnimated[4],
                            LoadingConfig.Fixed.progressPosAnimated[5],
                            LoadingConfig.Fixed.progressPosAnimated[6],
                            LoadingConfig.Fixed.progressPosAnimated[7])));
        }
        if (LoadingConfig.Fixed.tipsEnabled) {
            // Tips text
            images.add(new ImageRender(
                    LoadingConfig.Dynamic.font,
                    EPosition.valueOf(LoadingConfig.Fixed.baseTipsTextPos),
                    EType.TIPS_TEXT,
                    null,
                    new Area(LoadingConfig.Fixed.tipsTextPos[0], LoadingConfig.Fixed.tipsTextPos[1], 0, 0),
                    LoadingConfig.Fixed.tipsColor,
                    tip,
                    ""));
        }
        // Hmmm no idea what that is, maybe the thing that clears the screen
        images.add(new ImageRender(null, null, EType.CLEAR_COLOUR, null, null, "ffffff", null, ""));

        resolution = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);

        preDisplayScreen();

        int imageCounter = 0;

        if (!isRegisteringGTmaterials && !isReplacingVanillaMaterials && !isRegisteringBartWorks) {
            lastPercent = percent;
        }

        for (ImageRender image : images) {
            // Warning: do not add underline/strikethrough styling to the text, as that can cause Tesselator data races
            // between threads
            if (image != null && !(imageCounter > 4
                && (isRegisteringGTmaterials || isReplacingVanillaMaterials || isRegisteringBartWorks)
                && imageCounter < 9)) {
                drawImageRender(image, percent);
            } else if (image != null && isRegisteringGTmaterials) {
                drawImageRender(image, lastPercent);
            } else if (isReplacingVanillaMaterials) {
                drawImageRender(
                        image,
                        lastPercent);
            } else if (isRegisteringBartWorks) {
                drawImageRender(image, lastPercent);
            }
            imageCounter++;
        }
    }

    private FontRenderer fontRenderer(String fontTexture) {
        if (fontRenderers.containsKey(fontTexture)) {
            return fontRenderers.get(fontTexture);
        }
        FontRenderer font = new FontRenderer(mc.gameSettings, new ResourceLocation(fontTexture), textureManager, false);
        font.onResourceManagerReload(mc.getResourceManager());
        mc.refreshResources();
        font.onResourceManagerReload(mc.getResourceManager());
        fontRenderers.put(fontTexture, font);
        return font;
    }

    public void drawImageRender(ImageRender render, double percent) {
        int startX = render.transformX(resolution.getScaledWidth());
        int startY = render.transformY(resolution.getScaledHeight());
        int PWidth = 0;
        int PHeight = 0;
        if (render.position != null) {
            PWidth = render.position.width == 0 ? resolution.getScaledWidth() : render.position.width;
            PHeight = render.position.height == 0 ? resolution.getScaledHeight() : render.position.height;
        }
        GL11.glColor4f(render.getRed(), render.getGreen(), render.getBlue(), 1);
        switch (render.type) {
            case DYNAMIC_PERCENTAGE: {
                ResourceLocation res = new ResourceLocation(render.resourceLocation);
                textureManager.bindTexture(res);
                double visibleWidth = PWidth * percent;
                double textureWidth = render.texture.width * percent;
                GL11.glColor4f(lbRGB[0], lbRGB[1], lbRGB[2], LoadingConfig.Fixed.loadingBarsAlpha);
                drawRect(
                        startX,
                        startY,
                        visibleWidth,
                        PHeight,
                        render.texture.x,
                        render.texture.y,
                        textureWidth,
                        render.texture.height);
                GL11.glColor4f(1, 1, 1, 1);
                break;
            }
            case TIPS_TEXT: {
                FontRenderer font = fontRenderer(render.resourceLocation);
                int width = font.getStringWidth(render.text);
                int startX1 = render.positionType.transformX(render.position.x, resolution.getScaledWidth() - width);
                // BetterLoadingScreen.log.trace("startX1 normal: "+startX1);
                int startY1 = render.positionType
                        .transformY(render.position.y, resolution.getScaledHeight() - font.FONT_HEIGHT);
                if (LoadingConfig.Fixed.tipsTextShadow) {
                    font.drawStringWithShadow(render.text, startX1, startY1, Integer.parseInt(LoadingConfig.Fixed.tipsColor, 16));
                } else {
                    drawString(font, render.text, startX1, startY1, Integer.parseInt(LoadingConfig.Fixed.tipsColor, 16));
                }
                break;
            }
            case STATIC:
            case STATIC_BLENDED: {
                if (blending && render.type == EType.STATIC_BLENDED) {
                    if (blendingJustSet) {
                        blendingJustSet = false;
                        newBlendImage = randomBackground(render.resourceLocation);
                    }

                    if (LoadingConfig.Fixed.blendTimeMillis < 1.f) {
                        blendAlpha = 0.f;
                    } else {
                        blendAlpha = Float.max(
                                0.f,
                                1.0f - (float) (System.currentTimeMillis() - blendStartMillis) / LoadingConfig.Fixed.blendTimeMillis);
                    }
                    if (blendAlpha <= 0.f) {
                        blending = false;
                        background = newBlendImage;
                    }

                    GL11.glColor4f(render.getRed(), render.getGreen(), render.getBlue(), 1f);
                    bindTexture(render.resourceLocation);
                    drawRect(
                            startX,
                            startY,
                            PWidth,
                            PHeight,
                            render.texture.x,
                            render.texture.y,
                            render.texture.width,
                            render.texture.height);

                    ImageRender render2 = new ImageRender(
                            newBlendImage,
                            EPosition.TOP_LEFT,
                            EType.STATIC,
                            new Area(0, 0, 256, 256),
                            new Area(0, 0, 0, 0));
                    GL11.glColor4f(render2.getRed(), render2.getGreen(), render2.getBlue(), 1.f - blendAlpha);
                    bindTexture(render2.resourceLocation);
                    drawRect(
                            startX,
                            startY,
                            PWidth,
                            PHeight,
                            render2.texture.x,
                            render2.texture.y,
                            render2.texture.width,
                            render2.texture.height);
                    break;
                } else {
                    GL11.glColor4f(render.getRed(), render.getGreen(), render.getBlue(), 1F);
                    bindTexture(render.resourceLocation);
                    drawRect(
                            startX,
                            startY,
                            PWidth,
                            PHeight,
                            render.texture.x,
                            render.texture.y,
                            render.texture.width,
                            render.texture.height);
                    break;
                }

                // break;
            }
            case CLEAR_COLOUR: // Ignore this, as its set elsewhere
                break;
        }
    }

    private void bindTexture(String resourceLocation) {
        ResourceLocation res = new ResourceLocation(resourceLocation);
        textureManager.bindTexture(res);
    }

    public void drawString(FontRenderer font, String text, int x, int y, int colour) {
        font.drawString(text, x, y, colour);
        GL11.glColor4f(1, 1, 1, 1);
    }

    public void drawRect(double x, double y, double drawnWidth, double drawnHeight, double u, double v, double uWidth,
            double vHeight) {
        float f = 1 / 256F;
        // Can't use Tesselator, because the main thread can be using it simultaneously
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2d(u * f, (v + vHeight) * f);
        GL11.glVertex3d(x, y + drawnHeight, 0);
        GL11.glTexCoord2d((u + uWidth) * f, (v + vHeight) * f);
        GL11.glVertex3d(x + drawnWidth, y + drawnHeight, 0);
        GL11.glTexCoord2d((u + uWidth) * f, v * f);
        GL11.glVertex3d(x + drawnWidth, y, 0);
        GL11.glTexCoord2d(u * f, v * f);
        GL11.glVertex3d(x, y, 0);
        GL11.glEnd();
    }

    private void preDisplayScreen() {
        // BetterLoadingScreen.log.trace("Called preDisplayScreen");
        // bruh
        if (textureManager == null) {
            textureManager = mc.renderEngine = new TextureManager(mc.getResourceManager());
            mc.refreshResources();
            textureManager.onResourceManagerReload(mc.getResourceManager());
            mc.fontRenderer = new FontRenderer(
                    mc.gameSettings,
                    new ResourceLocation("textures/font/ascii.png"),
                    textureManager,
                    false);
            if (mc.gameSettings.language != null) {
                mc.fontRenderer.setUnicodeFlag(mc.func_152349_b());
                LanguageManager lm = mc.getLanguageManager();
                mc.fontRenderer.setBidiFlag(lm.isCurrentLanguageBidirectional());
            }
            mc.fontRenderer.onResourceManagerReload(mc.getResourceManager());
        }
        if (fontRenderer != mc.fontRenderer) {
            fontRenderer = mc.fontRenderer;
        }
        // if (textureManager != mc.renderEngine)
        // textureManager = mc.renderEngine;
        resolution = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        int i = resolution.getScaleFactor();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        GL11.glOrtho(
                0.0D,
                (double) resolution.getScaledWidth(),
                (double) resolution.getScaledHeight(),
                0.0D,
                1000.0D,
                3000.0D);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glLoadIdentity();
        GL11.glTranslatef(0.0F, 0.0F, -2000.0F);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_FOG);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_TEXTURE_2D);

        GL11.glClearColor(clearRed, clearGreen, clearBlue, 1);

        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glAlphaFunc(GL11.GL_GREATER, 1.F / 255.F);

        GL11.glColor4f(1, 1, 1, 1);
    }

    @Override
    public void close() {
        if (splashRenderThread != null && splashRenderThread.isAlive()) {
            BetterLoadingScreen.log.info("BLS Splash loading thread closing");
            splashRenderKillSwitch = true;
            try {
                loadingDrawable.releaseContext();
                splashRenderThread.join();
                Display.getDrawable().makeCurrent();
                Minecraft.getMinecraft().resize(Display.getWidth(), Display.getHeight());
            } catch (LWJGLException | InterruptedException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }
        if (tipExec != null) {
            tipExec.shutdown();
        }
        if (backgroundExec != null) {
            backgroundExec.shutdown();
        }
        getOnlyList().remove(myPack);
    }

    public static class Handler {
        private final AtomicBoolean played;

        Handler(AtomicBoolean played) {
            this.played = played;
        }

        @SubscribeEvent
        public void onPlaySound(PlaySoundSourceEvent event) {
            played.set(true);
        }
    }
}
