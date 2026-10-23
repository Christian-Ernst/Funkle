package io.github.christianernst.tav;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.graphics.Outline;
import android.view.ViewOutlineProvider;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.view.inputmethod.EditorInfo;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Full-screen visualizer with a bottom-sheet menu (Sheet) and a quick bar over the visuals. */
public final class MainActivity extends Activity implements TavRenderer.Host, AudioCapture.Listener, MediaWatcher.Listener {
    private static final int REQ_AUDIO = 7, REQ_NOTIFY = 8;
    private static final int[] SYMMETRY = {1, 2, 3, 4, 6, 8, 12};
    private static final String HINT_IDLE = "Play something on Spotify \u266A\nTap to make ripples \u00B7 drag to draw with sound";
    private static final String HINT_BLOCKED = "Music is playing, but TAV hears silence.\nTurn the volume up, or open \u2630 \u2192 Sound \u2192 Microphone.";
    private static final String HINT_NO_PERMISSION = "Without audio access TAV can't hear the music.\nTap and drag still make waves.";
    private static final String HINT_DEMO = "This is TAV's built-in beat.\nTap to make ripples \u00B7 drag to draw \u00B7 \u2630 \u2192 Sound, for your own music";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private AudioCapture capture;
    private TavRenderer renderer;
    private TavView view;
    private MediaWatcher media;

    private FrameLayout root;
    private TextView sceneLabel, hint, title, artist, menuButton;
    private LinearLayout trackBox, quickBar;
    private Sheet sheet;
    private View[] tileScenes, tileColours, tileLooks, tileAutopilot;   // {tile, picture, value}
    private TextView rowTune, rowDraw, rowSound, rowDisplay, rowAccount, quickScene, quickPause;
    private View quickClear, quickUndo;
    private boolean strokesOnScreen, frozen;
    private final List<View> sceneTiles = new ArrayList<>(), paletteSwatches = new ArrayList<>();
    private final Map<String, Bitmap> thumbs = new java.util.HashMap<>();
    private View intro, notice;
    private TextView introBody, introButton;
    private boolean permissionPending, openSettingsInstead, askedNotificationAccess;
    private final List<TextView> sourceChips = new ArrayList<>();
    // the other rows, so a Look or a newer cloud copy of the settings can move the chips too
    private final List<TextView> mirrorChips = new ArrayList<>(), cmChips = new ArrayList<>(),
            brightChips = new ArrayList<>(), motionChips = new ArrayList<>(), eqChips = new ArrayList<>(),
            autoChips = new ArrayList<>(), qualityChips = new ArrayList<>();
    private SharedPreferences firstRun;   // first-run bookkeeping, kept out of the synced settings
    private LookStore looks;
    private View looksCard, incoming;   // Looks: save, list, open a code; a shared Look that just arrived
    private TextView looksChip;
    private final java.util.Random rnd = new java.util.Random();
    private static float[] demoSamples;   // the built-in beat, made once per process
    private String lastTitle = "", lastArtist = "";
    private boolean playing, titleAlways;
    private boolean visible, silentNow;   // started (not stopped), and the visuals currently see silence
    private TextView nowPlaying, openPlayer;
    private ImageView trackArt, panelArt;
    private Hairline hairline;
    private Bitmap lastArt;
    private TransportButton playButton;
    private float[][] albumPalette;
    private float dp;
    // colour editor (Colours -> Custom)
    private View colourEditor;
    private final int[] custom = new int[3];
    private final View[] swatches = new View[3];
    private GradientSlider hueSlider, satSlider, valSlider;
    private int editing;
    // equalizer (menu → Equalizer → Custom)
    private View equalizerCard;
    private View accountCard;
    private Cloud cloud;
    private long sessionStartedAt;
    private final GradientSlider[] eqSliders = new GradientSlider[EQ_NAMES.length];
    private final TextView[] eqLabels = new TextView[EQ_NAMES.length];
    private Equalizer equalizer;
    private int eqMode;   // the chip in the menu: 0 Flat, 1 Punch, 2 Custom
    private static final String[] EQ_NAMES = {"Master", "Bass", "Low mids", "High mids", "Treble", "Punch", "Beat"};
    private static final int EQ_PUNCH = 5;
    private View colourCard;   // Colour motion → Custom: the sliders
    private final GradientSlider[] cmSliders = new GradientSlider[ColourDrive.NAMES.length];
    private final TextView[] cmLabels = new TextView[ColourDrive.NAMES.length];
    private ColourDrive colourDrive;
    private int colourMode;   // the chip in the menu, see ColourDrive.MODE_NAMES
    private View tuneCard;    // Tune this scene: the scene's three sliders
    private int tuneScene = -1;   // the scene the open card belongs to
    private final GradientSlider[] tuneSliders = new GradientSlider[Scene.TUNE];
    private final TextView[] tuneLabels = new TextView[Scene.TUNE];

    private final Runnable hideMenu = () -> {
        if (!sheet.shown()) {
            menuButton.animate().alpha(0f).setDuration(600).start();
            if (!frozen) quickBar.animate().alpha(0f).setDuration(600).start();
        }
    };
    private final Runnable hideTrack = () -> {
        if (!titleAlways) fadeTrack(0f, 1200);
    };

    /**
     * Animates the song title's opacity. At 0 it becomes INVISIBLE, so it takes no touches
     * (a transparent but clickable view would swallow taps meant for the visuals) and screen
     * readers skip it.
     */
    private void fadeTrack(float alpha, long ms) {
        trackBox.animate().cancel();
        if (alpha > 0f) {
            trackBox.setVisibility(View.VISIBLE);
            trackBox.animate().alpha(alpha).setDuration(ms).start();
        } else {
            trackBox.animate().alpha(0f).setDuration(ms).withEndAction(() -> trackBox.setVisibility(View.INVISIBLE)).start();
        }
    }
    private final Runnable hideScene = () -> sceneLabel.animate().alpha(0f).setDuration(900).start();
    private final Runnable showHint = () -> {
        if (!visible) return;   // stopped (in the background): nothing to explain, don't wake up
        ui.postDelayed(this.showHint, 3000);   // keep re-checking until sound returns (onSilence stops it)
        if (intro != null || notice != null || sheet.shown()) return;   // a card or the menu is explaining things already
        // Music is known to be playing but the output mix reads as silence: the ROM or the
        // player is keeping audio away from the visualizer. Point at the microphone.
        boolean blocked = capture.source() == AudioCapture.SOURCE_SYSTEM && media.isPlaying();
        hint.setText(capture.demoRunning() ? HINT_DEMO : !hasAudioPermission() ? HINT_NO_PERMISSION : blocked ? HINT_BLOCKED : HINT_IDLE);
        if (hint.getAlpha() < 1f) hint.animate().alpha(1f).setDuration(800).start();
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        CrashLog.install(this);   // first: a crash anywhere below is shown on the next launch
        dp = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("tav", MODE_PRIVATE);
        firstRun = getSharedPreferences("tav_state", MODE_PRIVATE);
        if (!firstRun.contains("first_open_at")) firstRun.edit().putLong("first_open_at", System.currentTimeMillis()).apply();
        looks = new LookStore(this);
        cloud = Cloud.get(this);   // before buildOverlay, which asks cloud.enabled() for the Account row
        titleAlways = prefs.getBoolean("titleAlways", false);
        equalizer = savedEqualizer();
        eqMode = prefs.getInt("eq_mode", 1);
        colourMode = prefs.getInt("cm_mode", ColourDrive.DEFAULT);
        colourDrive = colourMode == ColourDrive.CUSTOM ? savedColourDrive() : ColourDrive.preset(colourMode);
        if (prefs.getInt("prefs_version", 1) < 2) {   // Custom moved when the Ocean, Candy and Rainbow presets came
            SharedPreferences.Editor e = prefs.edit().putInt("prefs_version", 2);
            if (prefs.getInt("palette", Palettes.ALBUM) == Palettes.CUSTOM_V1) e.putInt("palette", Palettes.CUSTOM);
            e.apply();
        }
        if (prefs.getInt("prefs_version", 1) < PREFS_VERSION) {   // Quality: Battery, Balanced, Max became Auto, Low, Medium, High
            SharedPreferences.Editor e = prefs.edit().putInt("prefs_version", PREFS_VERSION);
            if (prefs.contains("quality")) e.putInt("quality", qualityV3(prefs.getInt("quality", 1)));
            e.apply();
        }
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }

        capture = new AudioCapture(this);
        renderer = new TavRenderer(this, capture, this);
        media = new MediaWatcher(this, this);
        view = new TavView(this, renderer, new TavView.Gestures() {
            @Override
            public void onAnyTouch() {
                pokeMenu();
            }

            @Override
            public void onLongPress() {
                togglePanel();
            }
        });

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        // A second finger joins the view the first one touched, so a two-finger scene swipe that
        // lands partly on the song title still reaches the visuals as one gesture.
        root.setMotionEventSplittingEnabled(false);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));
        buildOverlay();
        setContentView(root);
        applyPrefs();
        if (!hasAudioPermission()) showIntro();
        startCloud(getIntent());
        handleLink(getIntent().getData());
        String crash = CrashLog.take(this);
        if (crash != null) showCrash(crash);
    }

    /** The previous launch died: show the trace, with a button that copies all of it. */
    private void showCrash(final String trace) {
        if (notice != null) return;
        notice = card("Last time, TAV crashed",
                "This is what went wrong. Copy it and paste it into the chat, then TAV can be fixed.\n\n"
                        + CrashLog.summary(trace, 10),
                "Copy the report", () -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("TAV crash", trace));
                    flash("Copied");
                    dismissNotice();
                }, this::dismissNotice);
        TextView[] parts = (TextView[]) notice.getTag();
        parts[0].setGravity(Gravity.START);
        parts[0].setTextSize(12.5f);
        parts[0].setTypeface(Typeface.MONOSPACE);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (cloud.takeTokens(intent.getData())) Toast.makeText(this, "Signed in", Toast.LENGTH_SHORT).show();
        handleLink(intent.getData());
    }

    // Rendering and listening follow visibility, not focus: in split-screen on Android 7-9 the
    // window you are not touching is only paused, and TAV next to Spotify must keep going.
    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        NowPlayingNudge.tavVisible = true;
        NowPlayingNudge.cancel(this);   // TAV is on screen: the offer to open it is moot
        view.onResume();
        if (hasAudioPermission()) {
            removeIntro();   // also covers access granted from the system settings
            startAudio();
        } else {
            startDemo();   // something to look at before (or without) the permission
        }
        media.start();
        sessionStartedAt = System.currentTimeMillis();
        cloud.track("session_start", "source", !hasAudioPermission() ? "demo" : prefs.getInt("source", 0) == 0 ? "visualizer" : "mic");
        ui.removeCallbacks(showHint);   // one hint loop at a time
        if (silentNow) ui.postDelayed(showHint, 1000);   // still silent when we come back: explain again
    }

    /** Tells the renderer the panel's refresh rate, now and whenever it changes (adaptive 60/120 Hz panels). */
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int id) {
        }

        @Override
        public void onDisplayRemoved(int id) {
        }

        @Override
        public void onDisplayChanged(int id) {
            if (id == Display.DEFAULT_DISPLAY) pushRefreshRate();
        }
    };

    private void pushRefreshRate() {
        Display d = getWindowManager().getDefaultDisplay();
        if (d == null) return;
        final float hz = d.getRefreshRate();
        view.queueEvent(() -> renderer.setRefreshRate(hz));
    }

    @Override
    protected void onResume() {
        super.onResume();
        immersive();
        pokeMenu();
        pushRefreshRate();
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (dm != null) dm.registerDisplayListener(displayListener, ui);
        if (askedNotificationAccess) {
            askedNotificationAccess = false;
            if (MediaWatcher.hasNotificationAccess(this)) {
                pushPalette();
                media.stop();   // reconnect so the sessions are read with the new access
                media.start();
                if (prefs.getInt(NowPlayingNudge.PREF, NowPlayingNudge.OFF) != NowPlayingNudge.OFF
                        && Build.VERSION.SDK_INT >= 33 && !NowPlayingNudge.allowed(this))
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIFY);
            } else if (Build.VERSION.SDK_INT >= 33) explainRestrictedSettings();
        }
    }

    @Override
    protected void onStop() {
        visible = false;
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (dm != null) dm.unregisterDisplayListener(displayListener);
        NowPlayingNudge.tavVisible = false;
        ui.removeCallbacks(showHint);   // otherwise the hint loop keeps waking the CPU in the background
        view.onPause();
        capture.stop();
        media.stop();
        endSession();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);   // the hint re-check loop would otherwise outlive us
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    @Override
    public void onBackPressed() {
        if (colourEditor != null) closeColourEditor(true);   // back to the menu it came from
        else if (equalizerCard != null) closeEqualizer(true);
        else if (tuneCard != null) closeTune(true);
        else if (accountCard != null) closeAccount(true);
        else if (colourCard != null) closeColourMotion(true);
        else if (looksCard != null) closeLooks(true);
        else if (incoming != null) dismissIncoming();
        else if (sheet.shown()) {
            if (sheet.depth() > 0) sheet.pop();
            else showPanel(false);
        }
        else super.onBackPressed();
    }

    // ------------------------------------------------------------------ audio + permissions

    private boolean hasAudioPermission() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void startAudio() {
        int got = capture.start(prefs.getInt("source", AudioCapture.SOURCE_SYSTEM));
        markSource(got);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_NOTIFY) {
            if (results.length > 0 && !NowPlayingNudge.allowed(this))
                Toast.makeText(this, "Without notifications TAV can't offer to light up when music starts", Toast.LENGTH_LONG).show();
            return;
        }
        if (code != REQ_AUDIO) return;
        permissionPending = false;
        if (results.length == 0) return;   // the request was interrupted, nothing was decided
        if (hasAudioPermission()) {
            removeIntro();
            startAudio();
        } else if (introBody != null && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            // Android won't show the dialog again; the only way left is the app's settings page.
            openSettingsInstead = true;
            introBody.setText("Android won't ask again. To let TAV hear the music, open App info \u2192 Permissions "
                    + "\u2192 Microphone and choose Allow. Tap and drag work either way.");
            introButton.setText("Open App info");
        }
    }

    private void requestAudio() {
        if (openSettingsInstead) {
            openAppInfo();
            return;
        }
        if (permissionPending) return;   // a second tap would cancel the dialog that is already up
        permissionPending = true;
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
    }

    private void openAppInfo() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException e) {
            Toast.makeText(this, "Open Settings \u2192 Apps \u2192 TAV", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onSourceProblem(int requested, int fellBackTo) {
        ui.post(() -> {
            String msg;
            if (fellBackTo == AudioCapture.SOURCE_MIC)
                msg = "This phone blocks capturing system audio; listening through the microphone instead.";
            else if (fellBackTo == AudioCapture.SOURCE_SYSTEM)
                msg = "Microphone unavailable; using phone audio.";
            else msg = "No audio source available.";
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        });
    }

    // ------------------------------------------------------------------ renderer + media callbacks

    @Override
    public void onSceneChanged(int scene) {
        ui.post(() -> {
            prefs.edit().putInt("scene", scene).apply();
            sceneLabel.setText(spaced(Scene.ALL[scene].name));
            sceneLabel.animate().cancel();
            sceneLabel.setAlpha(1f);
            ui.removeCallbacks(hideScene);
            ui.postDelayed(hideScene, 1800);
            markScene(scene);
            refreshHome();
            if (tuneCard != null && tuneScene != scene) closeTune(true);   // the sliders belong to the old scene
        });
    }

    @Override
    public void onAutopilotLook(Autopilot.Look look) {
        ui.post(() -> {
            int pal = look.palette == null ? -1 : indexOf(Palettes.NAMES, look.palette);
            if (pal >= 0) {   // shown, not saved: the user's own choice comes back when the autopilot stops painting
                final float[][] p = paletteFor(pal);
                view.queueEvent(() -> {
                    renderer.setPalette(p);
                    renderer.setRainbow(pal == Palettes.RAINBOW);
                });
            }
            if (look.colourMode >= 0) {
                final ColourDrive d = ColourDrive.preset(look.colourMode);
                view.queueEvent(() -> renderer.setColourDrive(d));
            }
            StringBuilder label = new StringBuilder(Scene.ALL[look.scene >= 0 ? look.scene : Math.max(0, Math.min(Scene.ALL.length - 1, prefs.getInt("scene", 0)))].name);
            if (pal >= 0) label.append(" \u00b7 ").append(look.palette);
            if (look.colourMode >= 0) label.append(" \u00b7 ").append(ColourDrive.MODE_NAMES[look.colourMode]);
            sceneLabel.setText(spaced(label.toString()));
            sceneLabel.animate().cancel();
            sceneLabel.setAlpha(1f);
            ui.removeCallbacks(hideScene);
            ui.postDelayed(hideScene, 2200);
            try {
                cloud.track("autopilot", new JSONObject().put("reason", look.reason)
                        .put("scene", look.scene >= 0 ? Scene.ALL[look.scene].id : JSONObject.NULL)
                        .put("palette", look.palette != null ? look.palette : JSONObject.NULL)
                        .put("colour_motion", look.colourMode >= 0 ? ColourDrive.MODE_NAMES[look.colourMode] : JSONObject.NULL)
                        .put("energy", Math.round(look.energy * 100) / 100.0).put("bpm", Math.round(look.bpm)));
            } catch (JSONException ignored) {
            }
        });
    }

    /** Strokes came or went (drawn, a shape, undo, clear, three-finger tap): the Clear and Undo buttons follow. */
    @Override
    public void onStrokesChanged(int count) {
        ui.post(() -> {
            strokesOnScreen = count > 0;
            refreshQuick();
        });
    }

    @Override
    public void onAutopilotWeights(String weights) {
        ui.post(() -> {
            if (!weights.equals(prefs.getString("ap_weights", ""))) prefs.edit().putString("ap_weights", weights).apply();
        });
    }

    @Override
    public void onSilence(boolean silent) {
        ui.post(() -> {
            if (!silent && !firstRun.getBoolean("first_visual", false)) {   // the onboarding metric: first launch to first moving frame
                firstRun.edit().putBoolean("first_visual", true).apply();
                cloud.track("first_visual", "seconds_since_first_open", (System.currentTimeMillis() - firstRun.getLong("first_open_at", System.currentTimeMillis())) / 1000);
            }
            silentNow = silent;
            ui.removeCallbacks(showHint);
            if (silent && visible) ui.postDelayed(showHint, 2500);   // re-posts itself until sound returns
            else hint.animate().alpha(0f).setDuration(500).start();
        });
    }

    @Override
    public void onRenderError(String message) {
        ui.post(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onTrack(String t, String a, Bitmap art) {
        boolean newTrack = !t.equals(lastTitle) || !a.equals(lastArtist);
        lastTitle = t;
        lastArtist = a;
        nowPlaying.setText(a.isEmpty() ? t : t + "  \u00B7  " + a);
        openPlayer.setText(playerLabel(true));
        if (art != null) {
            float[][] p = Palettes.fromBitmap(art);
            if (p != null) albumPalette = p;
        }
        if (art != null || newTrack) showArt(art);
        if (prefs.getInt("palette", Palettes.ALBUM) == Palettes.ALBUM) pushPalette();
        if (!newTrack) return;
        view.queueEvent(renderer::onTrackChanged);
        title.setText(t);
        artist.setText(a);
        trackBox.setContentDescription("Now playing: " + t + (a.isEmpty() ? "" : " by " + a)
                + ". Double-tap to play or pause.");
        hairline.clear();   // the new track's position arrives with the next update
        trackBox.animate().cancel();
        trackBox.setVisibility(View.VISIBLE);
        trackBox.setAlpha(0f);
        trackBox.setTranslationX(0);
        trackBox.setTranslationY(16 * dp);
        trackBox.animate().alpha(trackAlpha()).translationY(0).setDuration(900).start();
        ui.removeCallbacks(hideTrack);
        ui.postDelayed(hideTrack, 7000);
        int shown = firstRun.getInt("title_hints", 0);   // the swipe nobody guesses: said with the first few songs
        if (shown < 3 && intro == null) {
            firstRun.edit().putInt("title_hints", shown + 1).apply();
            showHintNow("Swipe the title for the next track  \u00B7  tap it to pause");
        }
    }

    @Override
    public void onPlayback(boolean nowPlayingState) {
        playing = nowPlayingState;
        playButton.setKind(playing ? TransportButton.PAUSE : TransportButton.PLAY);
        // no alpha test: a fade-in that started this frame still reads 0 and must be redirected too
        if (titleAlways && !lastTitle.isEmpty()) {
            trackBox.setVisibility(View.VISIBLE);
            trackBox.animate().alpha(trackAlpha()).setDuration(400).start();   // keeps a running slide-in going
        }
    }

    /** The on-screen title dims while playback is paused. */
    private float trackAlpha() {
        return playing || !titleAlways ? 1f : 0.55f;
    }

    @Override
    public void onPosition(long positionMs, long durationMs, float speed) {
        hairline.set(positionMs, durationMs, speed);
    }

    /** Album art in the title overlay and the menu; both fall back to a soft placeholder. */
    private void showArt(Bitmap art) {
        if (art == lastArt) return;
        lastArt = art;
        for (ImageView v : new ImageView[]{trackArt, panelArt}) {
            if (art != null) v.setImageBitmap(art);
            else v.setImageDrawable(null);
            v.setVisibility(art != null ? View.VISIBLE : View.GONE);
        }
    }

    /** "Open Spotify" when Spotify is what plays or is installed, else the generic wording. */
    private String playerLabel(boolean playing) {
        String pkg = media.playerPackage();
        boolean spotify = NowPlayingNudge.SPOTIFY_PACKAGE.equals(pkg)
                || (pkg == null && MediaWatcher.isInstalled(this, NowPlayingNudge.SPOTIFY_PACKAGE));
        return (spotify ? "Open Spotify" : playing ? "Open player" : "Open Spotify") + "  \u2197";
    }

    private void openPlayer() {
        if (!media.openPlayer(this))
            Toast.makeText(this, "No music player found. Install Spotify, or play from any app.", Toast.LENGTH_LONG).show();
    }

    /** A square view with rounded corners for album art. */
    private ImageView artView(int sizeDp, final float radiusDp) {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusDp * dp);
            }
        });
        v.setClipToOutline(true);
        v.setElevation(4 * dp);
        v.setVisibility(View.GONE);
        return v;
    }

    // ------------------------------------------------------------------ playback

    private void playPause() {
        flash(media.playPause() ? "P L A Y" : "P A U S E");   // the watcher reports the new state back
    }

    private void skip(boolean forward) {
        if (forward) media.next();
        else media.previous();
        flash(forward ? "N E X T" : "P R E V I O U S");
        if (trackBox.getVisibility() == View.VISIBLE && trackBox.getAlpha() > 0f) {   // the title slides out the way it was flicked
            float d = (forward ? -1 : 1) * 60 * dp;
            trackBox.animate().translationX(d).alpha(0f).setDuration(180)
                    .withEndAction(() -> {
                        trackBox.setTranslationX(-d);
                        trackBox.animate().translationX(0).alpha(trackAlpha()).setDuration(260).start();
                    }).start();
            if (!titleAlways) {   // brought back by the skip: let it fade again
                ui.removeCallbacks(hideTrack);
                ui.postDelayed(hideTrack, 5000);
            }
        }
    }

    /** Short spaced-out label at the top, the same place scene names appear. */
    private void flash(String label) {
        sceneLabel.setText(label);
        sceneLabel.animate().cancel();
        sceneLabel.setAlpha(1f);
        ui.removeCallbacks(hideScene);
        ui.postDelayed(hideScene, 1200);
    }

    // ------------------------------------------------------------------ settings

    private void applyPrefs() {
        final int scene = prefs.getInt("scene", 0), sym = prefs.getInt("sym", 1), q = prefs.getInt("quality", TavRenderer.DEFAULT_QUALITY);
        final int lit = prefs.getInt("brightness", TavRenderer.DEFAULT_BRIGHTNESS);
        final int auto = autopilotMode();
        final boolean calm = prefs.getInt("motion", 0) == 1;
        final String weights = prefs.getString("ap_weights", "");
        view.queueEvent(() -> {
            renderer.restoreScene(scene);
            renderer.setSymmetry(sym);
            renderer.setQuality(q);
            renderer.setBrightness(lit);
            renderer.setCalm(calm);
            renderer.setAutopilotWeights(weights);
            renderer.setAutopilot(auto);
        });
        pushPalette();
        pushEqualizer();
        pushColourDrive();
        pushTune();
    }

    // ------------------------------------------------------------------ per-scene sliders

    private static String tuneKey(Scene sc, int k) {
        return "tune_" + sc.id + "_" + k;
    }

    private float tuneValue(Scene sc, int k) {
        return prefs.getFloat(tuneKey(sc, k), 0.5f);
    }

    /** Every scene's sliders from prefs to the renderer. */
    private void pushTune() {
        for (int s = 0; s < Scene.ALL.length; s++) {
            for (int k = 0; k < Scene.TUNE; k++) {
                final int fs = s, fk = k;
                final float v = tuneValue(Scene.ALL[s], k);
                view.queueEvent(() -> renderer.setTune(fs, fk, v));
            }
        }
    }

    private void setTune(int s, int k, float v) {
        v = Math.max(0f, Math.min(1f, v));
        prefs.edit().putFloat(tuneKey(Scene.ALL[s], k), v).apply();
        final int fs = s, fk = k;
        final float fv = v;
        view.queueEvent(() -> renderer.setTune(fs, fk, fv));
    }

    /** Glass card with the current scene's three sliders; every change shows on the scene at once. */
    private void showTune() {
        if (tuneCard != null) return;
        final int s = Math.max(0, Math.min(Scene.ALL.length - 1, prefs.getInt("scene", 0)));
        final Scene sc = Scene.ALL[s];
        tuneScene = s;
        LinearLayout box = sliderCard("Three sliders that are " + sc.name + "'s own. The middle is the look the scene was made with; every change shows at once.",
                sc.tune, tuneSliders, tuneLabels,
                (k, v) -> {
                    setTune(s, k, v);
                    refreshTuneLabels(sc);
                    refreshHome();
                });
        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, px(14), 0, 0);
        actions.addView(chip("Reset to the scene's own look", false, v -> resetTune()));
        box.addView(actions);
        tuneCard = openCard("Tune " + sc.name, box);
        for (int k = 0; k < Scene.TUNE; k++) tuneSliders[k].setValue(tuneValue(sc, k));
        refreshTuneLabels(sc);
    }

    private void refreshTuneLabels(Scene sc) {
        for (int k = 0; k < Scene.TUNE; k++) {
            tuneLabels[k].setText((sc.tune[k] + "  \u00B7  " + Math.round(tuneValue(sc, k) * 100) + " %").toUpperCase(Locale.ROOT));
        }
    }

    /** Back to the look the scene was made with (every slider in the middle). */
    private void resetTune() {
        int s = Math.max(0, Math.min(Scene.ALL.length - 1, prefs.getInt("scene", 0)));
        for (int k = 0; k < Scene.TUNE; k++) setTune(s, k, 0.5f);
        if (tuneCard != null && tuneScene == s) {
            for (int k = 0; k < Scene.TUNE; k++) tuneSliders[k].setValue(0.5f);
            refreshTuneLabels(Scene.ALL[s]);
        }
        flash("Tune reset");
        refreshHome();
    }

    private void closeTune(boolean backToMenu) {
        final View card = tuneCard;
        if (card == null) return;
        tuneCard = null;
        tuneScene = -1;
        closeCard(card, backToMenu);
    }

    private void pushPalette() {
        final int idx = prefs.getInt("palette", Palettes.ALBUM);
        final float[][] p = paletteFor(idx);
        view.queueEvent(() -> {
            renderer.setPalette(p);
            renderer.setRainbow(idx == Palettes.RAINBOW);
            // Album colours already change with the track and Custom is the user's own work: the autopilot leaves both alone
            renderer.setColoursLocked(idx == Palettes.ALBUM || idx == Palettes.CUSTOM);
        });
    }

    /**
     * The Autopilot chip (Off, Scenes, Scenes + colours). Installs from before the chip had three
     * options kept a boolean, which maps to Off or the default; the boolean is kept in step for the
     * session events.
     */
    private int autopilotMode() {
        if (prefs.contains("autopilot_mode")) return prefs.getInt("autopilot_mode", Autopilot.DEFAULT);
        return prefs.getBoolean("autopilot", true) ? Autopilot.DEFAULT : Autopilot.OFF;
    }

    private void setAutopilotMode(int mode) {
        prefs.edit().putInt("autopilot_mode", mode).putBoolean("autopilot", mode != Autopilot.OFF).apply();
        view.queueEvent(() -> renderer.setAutopilot(mode));
        // back to the user's own colours when the autopilot no longer paints them
        if (mode != Autopilot.FULL) {
            pushPalette();
            pushColourDrive();
        }
    }

    private static int indexOf(String[] names, String name) {
        for (int i = 0; i < names.length; i++) if (names[i].equals(name)) return i;
        return -1;
    }

    private float[][] paletteFor(int idx) {
        if (idx == Palettes.CUSTOM) return Palettes.fromColors(savedCustom());
        return (idx == Palettes.ALBUM && albumPalette != null) ? albumPalette : Palettes.preset(idx);
    }

    /** The custom colours (ARGB) in prefs custom0..custom2; Neon until the editor has stored any. */
    private int[] savedCustom() {
        int[] neon = Palettes.toColors(Palettes.preset(1)), c = new int[3];
        for (int k = 0; k < 3; k++) c[k] = prefs.getInt("custom" + k, neon[k]);
        return c;
    }

    private void saveCustom() {
        SharedPreferences.Editor e = prefs.edit();
        for (int k = 0; k < 3; k++) e.putInt("custom" + k, custom[k]);
        e.apply();
    }

    // ------------------------------------------------------------------ UI

    private void immersive() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    private int px(float v) {
        return Math.round(v * dp);
    }

    private static String spaced(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toUpperCase(Locale.ROOT).toCharArray()) b.append(c).append(' ');
        return b.toString().trim();
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif-light", bold ? Typeface.BOLD : Typeface.NORMAL));
        t.setShadowLayer(12 * dp, 0, 0, 0xAA000000);
        return t;
    }

    private void buildOverlay() {
        sceneLabel = text(13, 0xCCFFFFFF, false);
        sceneLabel.setLetterSpacing(0.25f);
        sceneLabel.setAlpha(0f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = px(44);
        root.addView(sceneLabel, lp);

        hint = text(16, 0xB3FFFFFF, false);
        hint.setGravity(Gravity.CENTER);
        hint.setText(HINT_IDLE);
        hint.setLineSpacing(0, 1.3f);
        hint.setAlpha(0f);
        // anchored to the bottom so it stays on screen in landscape too
        lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.setMargins(px(24), 0, px(24), px(172));
        root.addView(hint, lp);

        trackBox = new LinearLayout(this);
        trackBox.setOrientation(LinearLayout.HORIZONTAL);
        trackBox.setGravity(Gravity.CENTER_VERTICAL);
        trackArt = artView(56, 10);
        trackArt.setContentDescription("Album art. Opens the music player.");
        trackArt.setOnClickListener(v -> openPlayer());
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(px(56), px(56));
        alp.rightMargin = px(14);
        trackBox.addView(trackArt, alp);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        title = text(24, Color.WHITE, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        artist = text(15, 0xC0FFFFFF, false);
        artist.setLetterSpacing(0.08f);
        texts.addView(title);
        texts.addView(artist);
        hairline = new Hairline(this);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(px(132), px(2));
        hlp.topMargin = px(8);
        texts.addView(hairline, hlp);
        trackBox.addView(texts, new LinearLayout.LayoutParams(-2, -2));
        trackBox.setAlpha(0f);
        trackBox.setVisibility(View.INVISIBLE);
        trackBox.setPadding(0, px(8), px(16), px(8));   // a little more to grab
        trackBox.setOnTouchListener(new TitleGestures());
        trackBox.setOnClickListener(v -> playPause());   // the tap, also for screen readers
        trackBox.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            // screen-reader users can't make the swipe: offer next/previous as actions
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.action_next_track, "Next track"));
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.action_previous_track, "Previous track"));
            }

            @Override
            public boolean performAccessibilityAction(View host, int action, Bundle args) {
                if (action == R.id.action_next_track || action == R.id.action_previous_track) {
                    skip(action == R.id.action_next_track);
                    return true;
                }
                return super.performAccessibilityAction(host, action, args);
            }
        });
        lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        lp.setMargins(px(28), 0, px(96), px(40));
        root.addView(trackBox, lp);

        menuButton = text(22, Color.WHITE, false);
        menuButton.setText("☰");
        menuButton.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x55000000);
        bg.setStroke(px(1), 0x55FFFFFF);
        menuButton.setBackground(bg);
        menuButton.setContentDescription("Menu");
        menuButton.setOnClickListener(v -> togglePanel());
        lp = new FrameLayout.LayoutParams(px(52), px(52), Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, px(24), px(36));
        root.addView(menuButton, lp);

        buildQuickBar();
        buildSheet();
    }

    /**
     * The quick bar: a pill above the song title with the things people reach for between songs,
     * scene back and forth, pause the visuals, shuffle the colours, clear the drawing. It shows with
     * the menu button on any touch and fades with it.
     */
    private void buildQuickBar() {
        quickBar = new LinearLayout(this);
        quickBar.setGravity(Gravity.CENTER_VERTICAL);
        quickBar.setBackground(glass(0x66000000, 24));
        quickBar.setPadding(px(4), 0, px(4), 0);
        quickBar.setAlpha(0f);
        quickBar.addView(quickButton("\u2039", "Previous scene", v -> view.queueEvent(() -> renderer.nextScene(-1))));
        quickScene = flat(12.5f, Color.WHITE, false);
        quickScene.setLetterSpacing(0.2f);
        quickScene.setGravity(Gravity.CENTER);
        quickScene.setMinWidth(px(96));
        quickScene.setPadding(px(4), px(12), px(4), px(12));
        quickScene.setContentDescription("Scene. Opens the scene list.");
        quickScene.setOnClickListener(v -> {
            Sheet.tick(v);
            showPanel(true);
            showScenes();
        });
        quickBar.addView(quickScene);
        quickBar.addView(quickButton("\u203A", "Next scene", v -> view.queueEvent(() -> renderer.nextScene(1))));
        quickBar.addView(divider());
        quickPause = quickButton("\u275A\u275A", "Pause the visuals", v -> setFrozen(!frozen));
        quickBar.addView(quickPause);
        quickBar.addView(quickButton("\u2684", "Shuffle the colours", v -> shuffleColours()));
        quickUndo = quickButton("\u21B6", "Undo the last drawing", v -> view.queueEvent(renderer::undoStroke));
        quickUndo.setVisibility(View.GONE);
        quickBar.addView(quickUndo);
        quickClear = quickButton("\u232B", "Clear the drawing", v -> clearDrawing());
        quickClear.setVisibility(View.GONE);
        quickBar.addView(quickClear);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, px(48), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = px(108);
        root.addView(quickBar, lp);
    }

    private TextView quickButton(String glyph, String description, View.OnClickListener on) {
        TextView b = flat(glyph.length() > 1 ? 11 : 24, Color.WHITE, false);
        b.setText(glyph);
        b.setGravity(Gravity.CENTER);
        b.setContentDescription(description);
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(v -> {
            Sheet.tick(v);
            on.onClick(v);
            pokeMenu();
        });
        b.setLayoutParams(new LinearLayout.LayoutParams(px(44), px(44)));
        return b;
    }

    private View divider() {
        View d = new View(this);
        d.setBackgroundColor(0x33FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(1), px(22));
        lp.setMargins(px(4), 0, px(4), 0);
        d.setLayoutParams(lp);
        return d;
    }

    private void refreshQuick() {
        if (quickClear != null) quickClear.setVisibility(strokesOnScreen ? View.VISIBLE : View.GONE);
        if (quickUndo != null) quickUndo.setVisibility(strokesOnScreen ? View.VISIBLE : View.GONE);
        if (quickPause != null) {
            quickPause.setText(frozen ? "\u25B6" : "\u275A\u275A");
            quickPause.setTextSize(TypedValue.COMPLEX_UNIT_SP, frozen ? 18 : 11);
            quickPause.setContentDescription(frozen ? "Resume the visuals" : "Pause the visuals");
        }
    }

    /** Pause: the picture freezes where it is (and the demo beat goes quiet); the bar stays so it can be resumed. */
    private void setFrozen(boolean f) {
        frozen = f;
        view.setRenderMode(f ? android.opengl.GLSurfaceView.RENDERMODE_WHEN_DIRTY : android.opengl.GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        if (f && capture.demoAudible()) capture.setDemoAudible(false);
        refreshQuick();
        flash(f ? "P A U S E D" : "");
    }

    // ------------------------------------------------------------------ the menu (a bottom sheet with pages)

    /**
     * Builds the sheet and its home page: now playing with the transport, four big tiles (Scenes,
     * Colours, Looks, Autopilot) and a list of groups (Tune, Draw, Sound, Display, Account, Help).
     * Every setting from before is still here, one tap deeper; the pages are built on demand.
     */
    private void buildSheet() {
        sheet = new Sheet(this, new Sheet.Host() {
            @Override
            public void onDismiss() {
                closeAll();
            }

            @Override
            public void onBack() {
                onBackPressed();
            }
        });
        LinearLayout home = new LinearLayout(this);
        home.setOrientation(LinearLayout.VERTICAL);

        // now playing: art, title, the player, prev / play / next
        LinearLayout nowRow = new LinearLayout(this);
        nowRow.setGravity(Gravity.CENTER_VERTICAL);
        nowRow.setPadding(px(2), px(4), 0, px(12));
        panelArt = artView(44, 9);
        panelArt.setContentDescription("Album art");
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(px(44), px(44));
        plp.rightMargin = px(10);
        nowRow.addView(panelArt, plp);
        LinearLayout nowTexts = new LinearLayout(this);
        nowTexts.setOrientation(LinearLayout.VERTICAL);
        nowPlaying = flat(14.5f, Color.WHITE, false);
        nowPlaying.setSingleLine(true);
        nowPlaying.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        nowPlaying.setText("Nothing yet — start a song in Spotify");
        nowTexts.addView(nowPlaying);
        openPlayer = flat(12.5f, 0xFFFF7AB8, false);
        openPlayer.setText(playerLabel(false));
        openPlayer.setPadding(0, px(3), px(12), px(3));
        openPlayer.setOnClickListener(v -> {
            openPlayer();
            pokeMenu();
        });
        nowTexts.addView(openPlayer);
        nowRow.addView(nowTexts, new LinearLayout.LayoutParams(0, -2, 1f));
        TransportButton prev = new TransportButton(this, TransportButton.PREVIOUS, false);
        playButton = new TransportButton(this, TransportButton.PLAY, true);
        TransportButton next = new TransportButton(this, TransportButton.NEXT, false);
        prev.setOnClickListener(v -> {
            Sheet.tick(v);
            skip(false);
            pokeMenu();
        });
        playButton.setOnClickListener(v -> {
            Sheet.tick(v);
            playPause();
            pokeMenu();
        });
        next.setOnClickListener(v -> {
            Sheet.tick(v);
            skip(true);
            pokeMenu();
        });
        LinearLayout.LayoutParams small = new LinearLayout.LayoutParams(px(40), px(40)), big = new LinearLayout.LayoutParams(px(50), px(50));
        small.leftMargin = big.leftMargin = px(8);
        nowRow.addView(prev, small);
        nowRow.addView(playButton, big);
        nowRow.addView(next, new LinearLayout.LayoutParams(px(40), px(40), 0) {{
            leftMargin = px(8);
        }});
        home.addView(nowRow);

        // the four big tiles
        LinearLayout grid1 = new LinearLayout(this), grid2 = new LinearLayout(this);
        tileScenes = tile(grid1, "Scenes", "◉", this::showScenes);
        tileColours = tile(grid1, "Colours", "◐", this::showColours);
        tileLooks = tile(grid2, "Looks", "♡", this::showLooks);
        tileAutopilot = tile(grid2, "Autopilot", "⟳", this::showAutopilot);
        home.addView(grid1, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams g2 = new LinearLayout.LayoutParams(-1, -2);
        g2.topMargin = px(10);
        home.addView(grid2, g2);

        // the groups
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, px(14), 0, 0);
        rowTune = row(list, "Tune this scene", this::showTune);
        rowDraw = row(list, "Draw", this::showDraw);
        rowSound = row(list, "Sound", this::showSound);
        rowDisplay = row(list, "Display", this::showDisplay);
        if (cloud.enabled()) rowAccount = row(list, "Account", this::showAccount);
        row(list, "Gestures and help", this::showHelp);
        TextView exit = row(list, "Exit TAV", this::exitApp);
        exit.setText("everything stops");
        home.addView(list);

        sheet.setHome(home);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        root.addView(sheet, lp);
        refreshHome();
    }

    /** Exit: the activity goes, and with it (onStop) the rendering, the audio capture and the media watcher. */
    private void exitApp() {
        setFrozen(false);
        capture.setDemoAudible(false);
        endSession();
        finishAndRemoveTask();
        // the activity's onStop releases the visualizer, the capture and the media watcher; the process
        // itself goes a moment later (after the session event has been flushed), so nothing idles in the background
        new Handler(Looper.getMainLooper()).postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 1500);
    }

    /** Text without the overlay's glow, for the sheet. */
    private TextView flat(float sp, int color, boolean bold) {
        TextView t = text(sp, color, bold);
        t.setShadowLayer(0, 0, 0, 0);
        return t;
    }

    /** A big home tile: a picture behind (the scene's thumbnail, the palette), a title and the current value. */
    private View[] tile(LinearLayout into, String name, String glyph, Runnable open) {
        FrameLayout t = new FrameLayout(this);
        t.setClickable(true);
        t.setFocusable(true);
        t.setContentDescription(name);
        t.setBackground(glass(0x1EFFFFFF, 20));
        t.setClipToOutline(true);
        t.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 20 * dp);
            }
        });
        ImageView picture = new ImageView(this);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        picture.setAlpha(0.55f);
        t.addView(picture, new FrameLayout.LayoutParams(-1, -1));
        View scrim = new View(this);
        GradientDrawable sg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xB3000000, 0x26000000});
        scrim.setBackground(sg);
        t.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(px(16), px(14), px(16), px(14));
        TextView title = flat(16, Color.WHITE, true);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setText(name);
        texts.addView(title);
        TextView value = flat(12.5f, 0xB3FFFFFF, false);
        value.setSingleLine(true);
        value.setEllipsize(TextUtils.TruncateAt.END);
        value.setPadding(0, px(3), 0, 0);
        texts.addView(value);
        t.addView(texts, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        TextView g = flat(20, 0xCCFFFFFF, false);
        g.setText(glyph);
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        glp.setMargins(0, px(10), px(14), 0);
        t.addView(g, glp);
        t.setOnClickListener(v -> {
            Sheet.tick(v);
            open.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, px(96), 1f);
        if (into.getChildCount() > 0) lp.leftMargin = px(10);
        into.addView(t, lp);
        return new View[]{t, picture, value};
    }

    /** A group row: name, the current value on the right, a chevron. 54 dp tall. */
    private TextView row(LinearLayout into, String name, Runnable open) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setClickable(true);
        r.setFocusable(true);
        r.setContentDescription(name);
        r.setPadding(px(16), 0, px(12), 0);
        r.setBackground(glass(0x12FFFFFF, 16));
        TextView title = flat(15.5f, Color.WHITE, false);
        title.setText(name);
        r.addView(title, new LinearLayout.LayoutParams(-2, -2));
        TextView value = flat(13, 0x99FFFFFF, false);
        value.setGravity(Gravity.END);
        value.setSingleLine(true);
        value.setEllipsize(TextUtils.TruncateAt.END);
        value.setPadding(px(12), 0, px(8), 0);
        r.addView(value, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView chevron = flat(20, 0x80FFFFFF, false);
        chevron.setText("›");
        r.addView(chevron);
        r.setOnClickListener(v -> {
            Sheet.tick(v);
            open.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(54));
        lp.topMargin = px(6);
        into.addView(r, lp);
        return value;
    }

    /** The home page shows what is set: tile pictures and values, row values. Cheap, so it runs after every change. */
    private void refreshHome() {
        if (sheet == null) return;
        int s = clampScene(prefs.getInt("scene", 0)), pal = prefs.getInt("palette", Palettes.ALBUM);
        ((TextView) tileScenes[2]).setText(Scene.ALL[s].name + (tuneMoved(Scene.ALL[s]) ? " · tuned" : ""));
        ((ImageView) tileScenes[1]).setImageBitmap(thumb(Scene.ALL[s].id));
        String palName = pal == Palettes.ALBUM ? (lastTitle.isEmpty() ? "Album" : "Album · " + lastTitle) : Palettes.NAMES[pal];
        ((TextView) tileColours[2]).setText(palName + " · " + ColourDrive.MODE_NAMES[colourMode]);
        ((ImageView) tileColours[1]).setImageDrawable(paletteDrawable(pal, 0));
        int n = looks.all().size();
        ((TextView) tileLooks[2]).setText(n == 0 ? "Save what is on screen" : n == 1 ? "1 saved look" : n + " saved looks");
        ((TextView) tileAutopilot[2]).setText(Autopilot.MODE_NAMES[autopilotMode()]);
        Scene sc = Scene.ALL[s];
        StringBuilder tune = new StringBuilder();
        for (int k = 0; k < Scene.TUNE; k++) tune.append(k > 0 ? " · " : "").append(Math.round(tuneValue(sc, k) * 100));
        rowTune.setText(sc.name + "  " + tune);
        int sym = prefs.getInt("sym", 1);
        rowDraw.setText(sym <= 1 ? "Mirror off" : "Mirror ×" + sym);
        int src = prefs.getInt("source", 0), eq = eqMode;
        rowSound.setText((src == 0 ? "Phone audio" : "Microphone") + " · " + new String[]{"Flat", "Punch", "Custom EQ"}[Math.max(0, Math.min(2, eq))]);
        rowDisplay.setText(new String[]{"Dim", "Soft", "Normal", "Bright"}[Math.max(0, Math.min(3, prefs.getInt("brightness", TavRenderer.DEFAULT_BRIGHTNESS)))]
                + " · " + QUALITY_NAMES[Math.max(0, Math.min(3, prefs.getInt("quality", TavRenderer.DEFAULT_QUALITY)))]
                + (prefs.getInt("motion", 0) == 1 ? " · Calm" : ""));
        if (rowAccount != null) rowAccount.setText(cloud.anonymous() ? "Guest" : cloud.email());
        if (quickScene != null) quickScene.setText(spaced(sc.name));
    }

    private boolean tuneMoved(Scene sc) {
        for (int k = 0; k < Scene.TUNE; k++) if (tuneValue(sc, k) != 0.5f) return true;
        return false;
    }

    /** A scene's thumbnail from assets/thumbs (made by android/tools/thumbs.sh); null when there is none. */
    private Bitmap thumb(String sceneId) {
        Bitmap b = thumbs.get(sceneId);
        if (b != null || thumbs.containsKey(sceneId)) return b;
        try (java.io.InputStream in = getAssets().open("thumbs/" + sceneId + ".jpg")) {
            b = android.graphics.BitmapFactory.decodeStream(in);
        } catch (java.io.IOException e) {
            b = null;
        }
        thumbs.put(sceneId, b);
        return b;
    }

    /** The palette as a picture: its three colours (seven hues for Rainbow) as a diagonal gradient. */
    private GradientDrawable paletteDrawable(int idx, float radiusDp) {
        int[] c;
        if (idx == Palettes.RAINBOW) {
            c = new int[7];
            for (int i = 0; i < 7; i++) c[i] = Color.HSVToColor(new float[]{i * 51.4f, 0.9f, 1f});
        } else {
            c = Palettes.toColors(paletteFor(idx));
        }
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR, c);
        if (radiusDp > 0) d.setCornerRadius(px(radiusDp));
        return d;
    }

    // ---- pages

    private LinearLayout pageBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        return box;
    }

    private TextView note(LinearLayout into, String s) {
        TextView t = flat(13, 0x99FFFFFF, false);
        t.setText(s);
        t.setLineSpacing(0, 1.3f);
        t.setPadding(px(2), px(6), px(2), px(6));
        into.addView(t);
        return t;
    }

    /** Scenes: a grid of thumbnails, the current one framed; then the sliders and the autopilot's mode. */
    private void showScenes() {
        LinearLayout box = pageBox();
        sceneTiles.clear();
        int cols = 3;
        LinearLayout rowL = null;
        for (int i = 0; i < Scene.ALL.length; i++) {
            if (i % cols == 0) {
                rowL = new LinearLayout(this);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                rlp.topMargin = i == 0 ? px(4) : px(8);
                box.addView(rowL, rlp);
            }
            final int idx = i;
            FrameLayout t = new FrameLayout(this);
            t.setClickable(true);
            t.setFocusable(true);
            t.setContentDescription("Scene " + Scene.ALL[i].name);
            t.setClipToOutline(true);
            t.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 16 * dp);
                }
            });
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Bitmap b = thumb(Scene.ALL[i].id);
            if (b != null) img.setImageBitmap(b);
            else img.setBackground(paletteDrawable(prefs.getInt("palette", Palettes.ALBUM), 0));
            t.addView(img, new FrameLayout.LayoutParams(-1, -1));
            View scrim = new View(this);
            scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xB3000000}));
            t.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
            TextView name = flat(13.5f, Color.WHITE, true);
            name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            name.setText(Scene.ALL[i].name);
            name.setPadding(px(10), 0, px(10), px(9));
            t.addView(name, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
            View frame = new View(this);   // the selection ring
            t.addView(frame, new FrameLayout.LayoutParams(-1, -1));
            t.setTag(frame);
            t.setOnClickListener(v -> {
                Sheet.tick(v);
                markScene(idx);
                view.queueEvent(() -> renderer.setScene(idx));
                pokeMenu();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, px(132), 1f);
            if (i % cols > 0) lp.leftMargin = px(8);
            rowL.addView(t, lp);
            sceneTiles.add(t);
        }
        while (rowL != null && rowL.getChildCount() < cols) {   // keep the last row's tiles the same size
            View filler = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1f);
            lp.leftMargin = px(8);
            rowL.addView(filler, lp);
        }
        markScene(clampScene(prefs.getInt("scene", 0)));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, px(8), 0, 0);
        row(list, "Tune this scene", this::showTune);
        box.addView(list);
        note(box, "Two-finger swipe on the visuals also changes the scene. The autopilot picks scenes by the music's energy; it is in the Autopilot tile.");
        openCard("Scenes", box);
    }

    private void markScene(int scene) {
        for (int i = 0; i < sceneTiles.size(); i++) {
            View frame = (View) sceneTiles.get(i).getTag();
            if (i == scene) {
                GradientDrawable d = new GradientDrawable();
                d.setCornerRadius(px(16));
                d.setStroke(px(3), 0xFFFF2E8C);
                frame.setBackground(d);
            } else {
                frame.setBackground(null);
            }
            sceneTiles.get(i).setSelected(i == scene);
        }
    }

    /** Colours: the palettes as swatches, Shuffle, the Custom editor, and the colour motion. */
    private void showColours() {
        LinearLayout box = pageBox();
        box.addView(label("Palette"));
        paletteSwatches.clear();
        LinearLayout rowL = null;
        int cols = 4, count = Palettes.NAMES.length + 1;   // + Shuffle
        for (int i = 0; i < count; i++) {
            if (i % cols == 0) {
                rowL = new LinearLayout(this);
                box.addView(rowL, new LinearLayout.LayoutParams(-1, -2));
            }
            final int idx = i;
            boolean shuffle = i == Palettes.NAMES.length;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setClickable(true);
            cell.setFocusable(true);
            cell.setPadding(0, px(6), 0, px(6));
            cell.setContentDescription(shuffle ? "Shuffle: three random colours that go together" : "Colours " + Palettes.NAMES[i]);
            FrameLayout dot = new FrameLayout(this);
            View fill = new View(this);
            if (shuffle) {
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColor(0x1AFFFFFF);
                g.setStroke(px(1), 0x4DFFFFFF);
                fill.setBackground(g);
            } else {
                GradientDrawable g = paletteDrawable(i, 0);
                g.setShape(GradientDrawable.OVAL);
                fill.setBackground(g);
            }
            dot.addView(fill, new FrameLayout.LayoutParams(-1, -1));
            if (shuffle || i == Palettes.ALBUM) {
                TextView glyph = flat(20, Color.WHITE, false);
                glyph.setText(shuffle ? "⚄" : "♪");
                glyph.setGravity(Gravity.CENTER);
                dot.addView(glyph, new FrameLayout.LayoutParams(-1, -1));
            }
            View ring = new View(this);
            dot.addView(ring, new FrameLayout.LayoutParams(-1, -1));
            dot.setTag(ring);
            cell.addView(dot, new LinearLayout.LayoutParams(px(52), px(52)));
            TextView name = flat(12, 0xCCFFFFFF, false);
            name.setText(shuffle ? "Shuffle" : Palettes.NAMES[i]);
            name.setPadding(0, px(5), 0, 0);
            cell.addView(name);
            cell.setOnClickListener(v -> {
                Sheet.tick(v);
                pickPalette(idx);
                pokeMenu();
            });
            rowL.addView(cell, new LinearLayout.LayoutParams(0, -2, 1f));
            if (!shuffle) paletteSwatches.add(dot);
        }
        while (rowL != null && rowL.getChildCount() < cols) rowL.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        markPalette(prefs.getInt("palette", Palettes.ALBUM));
        TextView editChip = chip("Edit my colours", false, v -> showColourEditor());
        LinearLayout editRow = new LinearLayout(this);
        editRow.setPadding(0, px(4), 0, 0);
        editRow.addView(editChip);
        box.addView(editRow);

        addRow(box, "Colour motion · how the colours move with the music", ColourDrive.MODE_NAMES, colourMode, cmChips, i -> {
            view.queueEvent(renderer::autopilotUserColours);
            if (i == ColourDrive.CUSTOM) showColourMotion();   // Custom: the sliders
            else setColourDrive(ColourDrive.preset(i), i);
            refreshHome();
        });
        note(box, "Album takes the colours from the cover of what is playing. Shuffle makes three colours that go together and keeps them under Custom.");
        openCard("Colours", box);
    }

    /** The Colours page's swatch, the old chip row's pick: the same logic as before, one place. */
    private void pickPalette(int i) {
        if (i == Palettes.NAMES.length) {   // a harmonious random palette into the Custom slot
            shuffleColours();
            return;
        }
        // the first custom palette starts from the colours on screen, so read them before switching
        if (i == Palettes.CUSTOM && !prefs.contains("custom0")) {
            System.arraycopy(Palettes.toColors(paletteFor(prefs.getInt("palette", Palettes.ALBUM))), 0, custom, 0, 3);
            saveCustom();
        }
        prefs.edit().putInt("palette", i).apply();
        if (i == Palettes.ALBUM && !MediaWatcher.hasNotificationAccess(this))
            askNotificationAccess("Allow TAV in the list to colour the visuals from album art");
        view.queueEvent(renderer::autopilotUserColours);   // the user's colours stay for this track
        pushPalette();
        markPalette(i);
        refreshHome();
        if (i == Palettes.CUSTOM) showColourEditor();
    }

    private void markPalette(int sel) {
        for (int i = 0; i < paletteSwatches.size(); i++) {
            View ring = (View) paletteSwatches.get(i).getTag();
            if (i == sel) {
                GradientDrawable d = new GradientDrawable();
                d.setShape(GradientDrawable.OVAL);
                d.setStroke(px(3), Color.WHITE);
                ring.setBackground(d);
            } else {
                ring.setBackground(null);
            }
        }
    }

    private void showAutopilot() {
        LinearLayout box = pageBox();
        addRow(box, "Autopilot", Autopilot.MODE_NAMES, autopilotMode(), autoChips, i -> {
            setAutopilotMode(i);
            refreshHome();
        });
        note(box, "Scenes: it changes the scene with the music's energy and waits for the downbeat of a drop. "
                + "Scenes + colours: new colours per section too, on top of your own palette for this track. "
                + "Your own picks always win; it learns which scenes you keep.");
        openCard("Autopilot", box);
    }

    /** Draw: sound shapes to put on the scene, the mirror, clear. */
    private void showDraw() {
        LinearLayout box = pageBox();
        note(box, "Drag a finger over the visuals to draw a line that sings; a tap makes a ripple.");
        addRow(box, "Put a shape on screen", Shapes.NAMES, -1, null, i -> {
            final List<float[]> pts = Shapes.make(i, 0, 0, 0.62f);
            view.queueEvent(() -> renderer.addShape(pts));
        });
        String[] syms = {"Off", "2", "3", "4", "6", "8", "12"};
        int symSel = 0;
        for (int i = 0; i < SYMMETRY.length; i++) if (SYMMETRY[i] == prefs.getInt("sym", 1)) symSel = i;
        addRow(box, "Mirror · copies of the drawing, like a mandala", syms, symSel, mirrorChips, i -> {
            prefs.edit().putInt("sym", SYMMETRY[i]).apply();
            view.queueEvent(() -> renderer.setSymmetry(SYMMETRY[i]));
            refreshHome();
        });
        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, px(12), 0, 0);
        actions.addView(chip("\u21B6  Undo", false, v -> view.queueEvent(renderer::undoStroke)));
        TextView clear = chip("\u2715  Clear the drawing", true, v -> clearDrawing());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.leftMargin = px(8);
        actions.addView(clear, clp);
        box.addView(actions);
        note(box, "Pinch to zoom in for a moment; a three-finger tap also clears everything.");
        openCard("Draw", box);
    }

    private void clearDrawing() {
        view.queueEvent(renderer::clearStrokes);   // onStrokesChanged hides the buttons
        flash("C L E A R E D");
    }

    /** Sound: what TAV listens to, how hard the scenes react, the music-started nudge. */
    private void showSound() {
        LinearLayout box = pageBox();
        addRow(box, "Listen to", new String[]{"Phone audio (Spotify)", "Microphone"}, prefs.getInt("source", 0), sourceChips, i -> {
            prefs.edit().putInt("source", i).apply();
            if (hasAudioPermission()) startAudio();
            else requestAudio();
            refreshHome();
        });
        addRow(box, "Equalizer · how hard the scenes react", new String[]{"Flat", "Punch", "Custom"}, eqMode, eqChips, i -> {
            if (i == 2) showEqualizer();   // Custom: the sliders
            else setEqualizer(i == 0 ? Equalizer.flat() : Equalizer.punch(), i);
            refreshHome();
        });
        addRow(box, "Light up when music starts · a quiet notification", new String[]{"Off", "Spotify", "Any player"},
                prefs.getInt(NowPlayingNudge.PREF, NowPlayingNudge.OFF), new ArrayList<>(), i -> {
                    prefs.edit().putInt(NowPlayingNudge.PREF, i).apply();
                    if (i == NowPlayingNudge.OFF) {
                        NowPlayingNudge.cancel(this);
                        return;
                    }
                    if (!MediaWatcher.hasNotificationAccess(this))
                        askNotificationAccess("Allow TAV in the list so it can see when music starts");
                    else if (Build.VERSION.SDK_INT >= 33 && !NowPlayingNudge.allowed(this))
                        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIFY);
                });
        note(box, "Phone audio hears what the phone plays, through Android's visualizer. Album colours and the nudge read what is playing through notification access; nothing is recorded.");
        openCard("Sound", box);
    }

    /** Display: brightness, motion, quality, the song title. */
    private void showDisplay() {
        LinearLayout box = pageBox();
        addRow(box, "Brightness", new String[]{"Dim", "Soft", "Normal", "Bright"},
                prefs.getInt("brightness", TavRenderer.DEFAULT_BRIGHTNESS), brightChips, i -> {
                    prefs.edit().putInt("brightness", i).apply();
                    view.queueEvent(() -> renderer.setBrightness(i));
                    refreshHome();
                });
        addRow(box, "Motion", new String[]{"Full", "Calm (no flashes)"}, prefs.getInt("motion", 0), motionChips, i -> {
            prefs.edit().putInt("motion", i).apply();
            view.queueEvent(() -> renderer.setCalm(i == 1));
            refreshHome();
        });
        addRow(box, "Quality · Auto finds the sharpest your phone keeps smooth", QUALITY_NAMES, prefs.getInt("quality", TavRenderer.DEFAULT_QUALITY), qualityChips, i -> {
            prefs.edit().putInt("quality", i).apply();
            view.queueEvent(() -> renderer.setQuality(i));
            refreshHome();
        });
        addRow(box, "Song title", new String[]{"Fades out", "Always on screen"}, titleAlways ? 1 : 0, new ArrayList<>(), i -> {
            titleAlways = i == 1;
            prefs.edit().putBoolean("titleAlways", titleAlways).apply();
            ui.removeCallbacks(hideTrack);
            if (lastTitle.isEmpty()) return;
            if (titleAlways) fadeTrack(trackAlpha(), 400);
            else ui.postDelayed(hideTrack, 2500);
        });
        openCard("Display", box);
    }

    private void showHelp() {
        LinearLayout box = pageBox();
        String[][] lines = {
                {"Tap", "a ripple"},
                {"Drag", "draw a line that sings"},
                {"Two fingers, swipe", "next or previous scene"},
                {"Long-press", "this menu"},
                {"Song title", "swipe it to skip, tap it to pause"},
                {"‹ › at the bottom", "scene, shuffle colours, clear the drawing"},
        };
        for (String[] l : lines) {
            LinearLayout r = new LinearLayout(this);
            r.setPadding(0, px(7), 0, px(7));
            TextView a = flat(14.5f, Color.WHITE, true);
            a.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            a.setText(l[0]);
            r.addView(a, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView b = flat(14, 0xB3FFFFFF, false);
            b.setText(l[1]);
            b.setGravity(Gravity.END);
            r.addView(b, new LinearLayout.LayoutParams(0, -2, 1.3f));
            box.addView(r);
        }
        note(box, "A look is the scene, colours and their motion, mirror, brightness, motion and equalizer together. Share one as a link or a six-letter code.\n"
                + "Album colours and the music-started nudge need notification access, used only to read what is playing.");
        openCard("Gestures and help", box);
    }

    interface Pick {
        void on(int index);
    }

    /** Small spaced capitals above a row of chips or a slider. */
    private TextView label(String s) {
        TextView l = flat(11.5f, 0x99FFFFFF, false);
        l.setText(s.toUpperCase(Locale.ROOT));
        l.setLetterSpacing(0.1f);
        l.setPadding(px(2), px(14), 0, px(8));
        return l;
    }

    private GradientDrawable glass(int fill) {
        return glass(fill, 26);
    }

    private GradientDrawable glass(int fill, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(px(radiusDp));
        g.setStroke(px(1), 0x33FFFFFF);
        return g;
    }

    /** A labelled row of chips in a page; one chip is selected unless `keep` is null (actions). */
    private void addRow(LinearLayout into, String label, String[] options, int selected, List<TextView> keep, Pick pick) {
        into.addView(label(label));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        LinearLayout row = new LinearLayout(this);
        hs.addView(row);
        final List<TextView> chips = keep != null ? keep : new ArrayList<>();
        chips.clear();
        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            TextView c = chip(options[i], i == selected, v -> {
                if (keep != null) for (int k = 0; k < chips.size(); k++) styleChip(chips.get(k), k == idx);
                pick.on(idx);
                pokeMenu();
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, px(44));
            clp.rightMargin = px(8);
            row.addView(c, clp);
            chips.add(c);
        }
        into.addView(hs);
    }

    /** A chip: 44 dp tall, a tick of haptics on tap. */
    private TextView chip(String label, boolean selected, View.OnClickListener on) {
        TextView c = flat(14.5f, Color.WHITE, false);
        c.setText(label);
        c.setGravity(Gravity.CENTER);
        c.setPadding(px(18), px(10), px(18), px(10));
        c.setMinHeight(px(44));
        styleChip(c, selected);
        c.setOnClickListener(v -> {
            Sheet.tick(v);
            on.onClick(v);
        });
        return c;
    }

    private void styleChip(TextView c, boolean selected) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(px(22));
        if (selected) {
            d.setColors(new int[]{0xFFFF2E8C, 0xFF7B3CFF});
            d.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
        } else {
            d.setColor(0x1AFFFFFF);
            d.setStroke(px(1), 0x33FFFFFF);
        }
        c.setBackground(d);
    }

    private void markSource(int src) {
        for (int i = 0; i < sourceChips.size(); i++) styleChip(sourceChips.get(i), i == src);
    }

    private void togglePanel() {
        if (colourEditor != null) closeColourEditor(false);   // ✕ or a long-press while editing closes everything
        else if (equalizerCard != null) closeEqualizer(false);
        else if (accountCard != null) closeAccount(false);
        else if (colourCard != null) closeColourMotion(false);
        else if (looksCard != null) closeLooks(false);
        else showPanel(!sheet.shown());
    }

    /** ✕ or a drag on the handle: closes whichever page is open, and the sheet with it. */
    private void closeAll() {
        if (colourEditor != null) closeColourEditor(false);
        else if (equalizerCard != null) closeEqualizer(false);
        else if (tuneCard != null) closeTune(false);
        else if (accountCard != null) closeAccount(false);
        else if (colourCard != null) closeColourMotion(false);
        else if (looksCard != null) closeLooks(false);
        else showPanel(false);
    }

    private void showPanel(boolean show) {
        if (show) {
            refreshHome();
            sheet.show();
            hint.setVisibility(View.INVISIBLE);   // the sheet explains things now
            menuButton.animate().alpha(0f).setDuration(150).start();
            quickBar.animate().alpha(0f).setDuration(150).start();
        } else {
            sheet.hide();
            hint.setVisibility(View.VISIBLE);
            pokeMenu();
        }
    }

    /** Shows the menu button and the quick bar, and hides them again after a few seconds. */
    private void pokeMenu() {
        ui.removeCallbacks(hideMenu);
        if (sheet.shown()) return;
        menuButton.animate().cancel();
        menuButton.setAlpha(0.85f);
        quickBar.animate().cancel();
        quickBar.setAlpha(1f);
        ui.postDelayed(hideMenu, 3500);
    }

    // ------------------------------------------------------------------ colour editor

    /**
     * Glass card in the menu's place for the custom palette: three swatches, and hue, saturation
     * and brightness for the selected one. Changes show on the visuals while you drag.
     */
    private void showColourEditor() {
        if (colourEditor != null) return;
        System.arraycopy(savedCustom(), 0, custom, 0, 3);
        LinearLayout box = pageBox();
        note(box, "Three colours of your own. Tap a swatch, then move hue, saturation and brightness; the scene follows while you drag.");
        box.addView(label("Your colours \u00B7 tap one to change it"));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (int k = 0; k < 3; k++) {
            final int idx = k;
            View s = new View(this);
            s.setContentDescription("Colour " + (k + 1));
            s.setOnClickListener(v -> selectSwatch(idx));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(px(44), px(44));
            slp.rightMargin = px(14);
            row.addView(s, slp);
            swatches[k] = s;
        }
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView shuffle = chip("\u2684 Shuffle", false, v -> shuffleColours());
        shuffle.setContentDescription("Shuffle: three random colours that go together");
        row.addView(shuffle);
        box.addView(row);
        hueSlider = slider(box, "Hue");
        satSlider = slider(box, "Saturation");
        valSlider = slider(box, "Brightness");
        int[] rainbow = new int[7];
        for (int i = 0; i < rainbow.length; i++) rainbow[i] = Color.HSVToColor(new float[]{i * 60f, 1f, 1f});
        hueSlider.setColors(rainbow);

        colourEditor = openCard("My colours", box);
        selectSwatch(0);
    }

    private GradientSlider slider(LinearLayout box, String name) {
        TextView l = label(name);
        l.setPadding(0, px(8), 0, 0);
        box.addView(l);
        GradientSlider s = new GradientSlider(this, name);
        s.setListener((v, done) -> {
            sheet.setGhost(!done);
            editColour(done);
        });
        box.addView(s, new LinearLayout.LayoutParams(-1, -2));
        return s;
    }

    private void selectSwatch(int k) {
        editing = k;
        float[] hsv = new float[3];
        Color.colorToHSV(custom[k], hsv);
        hueSlider.setValue(hsv[0] / 360f);
        satSlider.setValue(hsv[1]);
        valSlider.setValue((hsv[2] - Palettes.MIN_VALUE) / (1 - Palettes.MIN_VALUE));
        refreshEditor();
    }

    /** The slider positions as HSV; brightness never goes below Palettes.MIN_VALUE. */
    private float[] editorHsv() {
        return new float[]{hueSlider.value() * 360f, satSlider.value(),
                Palettes.MIN_VALUE + (1 - Palettes.MIN_VALUE) * valSlider.value()};
    }

    private void editColour(boolean done) {
        custom[editing] = Color.HSVToColor(editorHsv());
        refreshEditor();
        final float[][] p = Palettes.fromColors(custom);
        view.queueEvent(() -> renderer.setPalette(p, true));
        if (done) saveCustom();
    }

    private void refreshEditor() {
        float[] hsv = editorHsv();
        for (int k = 0; k < 3; k++) {
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(custom[k]);
            d.setStroke(px(k == editing ? 3 : 1), k == editing ? Color.WHITE : 0x55FFFFFF);
            swatches[k].setBackground(d);
            swatches[k].setSelected(k == editing);
        }
        satSlider.setColors(Color.HSVToColor(new float[]{hsv[0], 0f, hsv[2]}), Color.HSVToColor(new float[]{hsv[0], 1f, hsv[2]}));
        valSlider.setColors(Color.HSVToColor(new float[]{hsv[0], hsv[1], Palettes.MIN_VALUE}),
                Color.HSVToColor(new float[]{hsv[0], hsv[1], 1f}));
        int c = Color.HSVToColor(hsv);
        hueSlider.setKnobColor(c);
        satSlider.setKnobColor(c);
        valSlider.setKnobColor(c);
    }

    /** backToMenu: Back returns to the menu the editor came from; Done and ✕ close both. */
    private void closeColourEditor(boolean backToMenu) {
        final View card = colourEditor;
        if (card == null) return;
        colourEditor = null;
        saveCustom();
        refreshHome();
        closeCard(card, backToMenu);
    }

    // ------------------------------------------------------------------ colour motion

    private ColourDrive savedColourDrive() {
        ColourDrive d = ColourDrive.preset(ColourDrive.CUSTOM);
        return new ColourDrive(prefs.getFloat("cm_drift", d.drift), prefs.getFloat("cm_beat", d.beatStep),
                prefs.getFloat("cm_drop", d.dropStep), prefs.getFloat("cm_tilt", d.tilt), prefs.getFloat("cm_pump", d.pump));
    }

    /**
     * Saves a colour drive and makes it live. mode is the menu chip it belongs to; the cm_* values
     * are the Custom sliders and survive a visit to the other chips.
     */
    private void setColourDrive(ColourDrive d, int mode) {
        colourDrive = d;
        colourMode = mode;
        SharedPreferences.Editor e = prefs.edit().putInt("cm_mode", mode);
        if (mode == ColourDrive.CUSTOM) {
            e.putFloat("cm_drift", d.drift).putFloat("cm_beat", d.beatStep).putFloat("cm_drop", d.dropStep)
                    .putFloat("cm_tilt", d.tilt).putFloat("cm_pump", d.pump);
        }
        e.apply();
        pushColourDrive();
    }

    private void pushColourDrive() {
        final ColourDrive d = colourDrive;
        view.queueEvent(() -> renderer.setColourDrive(d));
    }

    /** Glass card with the five colour-motion sliders; every change shows on the scenes at once. */
    private void showColourMotion() {
        if (colourCard != null) return;
        if (colourMode != ColourDrive.CUSTOM) setColourDrive(savedColourDrive(), ColourDrive.CUSTOM);
        LinearLayout box = sliderCard("Drift turns the hue slowly on its own, Beat step and Drop jump move it on the music, Band tilt and Band pump let bass, mids and treble shift and swell the colours.",
                ColourDrive.NAMES, cmSliders, cmLabels,
                (i, v) -> {
                    setColourDrive(colourDrive.with(i, v * ColourDrive.MAX[i]), ColourDrive.CUSTOM);
                    refreshColourLabels();
                });
        colourCard = openCard("Colour motion", box);
        for (int i = 0; i < ColourDrive.NAMES.length; i++) cmSliders[i].setValue(colourDrive.amount(i) / ColourDrive.MAX[i]);
        refreshColourLabels();
    }

    private void refreshColourLabels() {
        for (int i = 0; i < ColourDrive.NAMES.length; i++) {
            int pct = Math.round(colourDrive.amount(i) / ColourDrive.MAX[i] * 100);
            cmLabels[i].setText((ColourDrive.NAMES[i] + "  \u00B7  " + pct + " %").toUpperCase(Locale.ROOT));
        }
    }

    private void closeColourMotion(boolean backToMenu) {
        final View card = colourCard;
        if (card == null) return;
        colourCard = null;
        closeCard(card, backToMenu);
    }

    interface Slide {
        void on(int index, float position);
    }

    /**
     * The body of a slider page: a line on what it does, then a label and a slider per name. While a
     * slider is dragged the sheet goes see-through, so the change is judged on the scene itself.
     */
    private LinearLayout sliderCard(String what, String[] names, GradientSlider[] sliders, TextView[] labels, Slide slide) {
        LinearLayout box = pageBox();
        if (what != null) note(box, what);
        for (int i = 0; i < names.length; i++) {
            final int k = i;
            labels[i] = label(names[i]);
            box.addView(labels[i]);
            GradientSlider s = new GradientSlider(this, names[i]);
            s.setColors(0xFF2A2740, 0xFFFF2E8C);
            s.setKnobColor(0xFFFFFFFF);
            s.setListener((value, finished) -> {
                sheet.setGhost(!finished);
                if (finished) Sheet.tick(s);
                slide.on(k, value);
            });
            box.addView(s, new LinearLayout.LayoutParams(-1, -2));
            sliders[i] = s;
        }
        return box;
    }

    /** Pushes a page onto the sheet (opening it if it was closed) and returns the page. */
    private View openCard(String title, View box) {
        if (!sheet.shown()) showPanel(true);
        return sheet.push(title, box);
    }

    /** backToMenu: Back returns to the page the card came from; ✕ and a drag close the sheet. */
    private void closeCard(View card, boolean backToMenu) {
        if (backToMenu) sheet.remove(card);
        else showPanel(false);
    }

    // ------------------------------------------------------------------ equalizer

    private Equalizer savedEqualizer() {
        Equalizer d = Equalizer.punch();
        float[] g = new float[Equalizer.BANDS];
        for (int b = 0; b < g.length; b++) g[b] = prefs.getFloat("eq_g" + b, d.gain[b]);
        return new Equalizer(prefs.getFloat("eq_master", d.master), g, prefs.getFloat("eq_punch", d.punch),
                prefs.getFloat("eq_beat", d.beatGain));
    }

    /** Saves an equalizer and makes it live. mode is the menu chip it belongs to (2 = Custom). */
    private void setEqualizer(Equalizer e, int mode) {
        equalizer = e;
        eqMode = mode;
        SharedPreferences.Editor ed = prefs.edit().putInt("eq_mode", mode).putFloat("eq_master", e.master)
                .putFloat("eq_punch", e.punch).putFloat("eq_beat", e.beatGain);
        for (int b = 0; b < Equalizer.BANDS; b++) ed.putFloat("eq_g" + b, e.gain[b]);
        ed.apply();
        pushEqualizer();
    }

    private void pushEqualizer() {
        final Equalizer e = equalizer;
        view.queueEvent(() -> renderer.setEqualizer(e));
    }

    /** The amount slider i stands for: master, the four bands, punch (0–100 %) and beat. */
    private float eqAmount(int i) {
        Equalizer e = equalizer;
        return i == 0 ? e.master : i <= Equalizer.BANDS ? e.gain[i - 1] : i == EQ_PUNCH ? e.punch : e.beatGain;
    }

    /** The slider runs 0–200 % except punch, which runs 0–100 %. */
    private static float eqRange(int i) {
        return i == EQ_PUNCH ? 1f : 2f;
    }

    /**
     * Glass card with seven sliders over the visuals. Each change makes a new equalizer and shows
     * on the scenes at once, so you can drag until it hits right.
     */
    private void showEqualizer() {
        if (equalizerCard != null) return;
        LinearLayout box = sliderCard("How hard the scenes react. Master and the four bands scale the music's levels; Punch makes loud passages hit harder than quiet ones; Beat is the size of each beat's pulse.",
                EQ_NAMES, eqSliders, eqLabels, this::editEqualizer);
        equalizerCard = openCard("Equalizer", box);
        showEqualizerValues();
    }

    /** Puts every slider and label where the current equalizer is. */
    private void showEqualizerValues() {
        for (int i = 0; i < EQ_NAMES.length; i++) eqSliders[i].setValue(eqAmount(i) / eqRange(i));
        refreshEqualizerLabels();
    }

    private void refreshEqualizerLabels() {
        for (int i = 0; i < EQ_NAMES.length; i++) {
            eqLabels[i].setText((EQ_NAMES[i] + "  \u00B7  " + Math.round(eqAmount(i) * 100) + " %").toUpperCase(Locale.ROOT));
        }
    }

    private void editEqualizer(int i, float position) {
        Equalizer e = equalizer;
        float master = e.master, punch = e.punch, beat = e.beatGain, v = position * eqRange(i);
        float[] g = e.gain.clone();
        if (i == 0) master = v;
        else if (i <= Equalizer.BANDS) g[i - 1] = v;
        else if (i == EQ_PUNCH) punch = v;
        else beat = v;
        setEqualizer(new Equalizer(master, g, punch, beat), 2);
        refreshEqualizerLabels();
    }

    private void closeEqualizer(boolean backToMenu) {
        final View card = equalizerCard;
        if (card == null) return;
        equalizerCard = null;
        closeCard(card, backToMenu);
    }

    private void askNotificationAccess(String why) {
        askedNotificationAccess = true;   // checked when we come back in onResume
        Toast.makeText(this, why, Toast.LENGTH_LONG).show();
        Intent i;
        if (Build.VERSION.SDK_INT >= 30) {
            i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS);
            i.putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    new ComponentName(this, MediaListenerService.class).flattenToString());
        } else {
            i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        }
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        }
    }

    /** A glass card over the visuals with one main action and a way to dismiss it. */
    private LinearLayout card(String heading, String text, String action, Runnable onAction, Runnable onDismiss) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(px(26), px(24), px(26), px(18));
        card.setBackground(glass(0xE60B0B14));
        card.setClickable(true);   // touches on the card don't fall through to the visuals

        TextView h = text(heading.length() <= 5 ? 30 : 20, Color.WHITE, true);
        h.setText(heading);
        h.setGravity(Gravity.CENTER);
        if (heading.length() <= 5) h.setLetterSpacing(0.3f);
        card.addView(h);
        TextView body = text(15, 0xD9FFFFFF, false);
        body.setText(text);
        body.setLineSpacing(0, 1.25f);
        body.setGravity(Gravity.CENTER);
        body.setPadding(0, px(14), 0, px(20));
        card.addView(body);
        TextView go = text(16, Color.WHITE, true);
        go.setText(action);
        go.setShadowLayer(0, 0, 0, 0);
        go.setPadding(px(28), px(12), px(28), px(12));
        styleChip(go, true);
        go.setOnClickListener(v -> onAction.run());
        card.addView(go);
        TextView later = text(14, 0x99FFFFFF, false);
        later.setText("Not now");
        later.setPadding(px(20), px(14), px(20), px(4));
        later.setOnClickListener(v -> onDismiss.run());
        card.addView(later);
        card.setTag(new TextView[]{body, go});

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        lp.setMargins(px(24), 0, px(24), 0);
        root.addView(card, lp);
        return card;
    }

    /**
     * First-run card. The visuals are already running on the built-in beat behind it (see
     * startDemo), so the permission is asked for after the first wow, not before it.
     */
    private void showIntro() {
        LinearLayout c = card("T A V",
                "This is TAV on its built-in beat. Your own music looks like this too: Spotify, YouTube, anything your phone plays.\n\n"
                        + "To hear it, Android needs the \u201Crecord audio\u201D permission. TAV only analyses the sound "
                        + "frame by frame to drive the visuals; nothing is recorded, stored or sent anywhere.",
                "Light up my music", this::requestAudio, () -> {
                    removeIntro();
                    showHintNow(HINT_DEMO);
                });
        TextView[] parts = (TextView[]) c.getTag();
        introBody = parts[0];
        introButton = parts[1];
        TextView hear = text(14, Color.WHITE, false);
        hear.setShadowLayer(0, 0, 0, 0);
        hear.setText("\u25B6 Hear the demo");
        hear.setPadding(px(20), px(10), px(20), px(10));
        styleChip(hear, false);
        hear.setOnClickListener(v -> {
            boolean on = !capture.demoAudible();
            if (on) startDemo();
            capture.setDemoAudible(on);
            hear.setText(capture.demoAudible() ? "\u275A\u275A Mute the demo" : "\u25B6 Hear the demo");
        });
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-2, -2);
        hp.topMargin = px(10);
        c.addView(hear, c.getChildCount() - 1, hp);   // above "Not now"
        intro = c;
    }

    /** Drives the visuals from the built-in beat while the app may not listen yet. */
    private void startDemo() {
        if (capture.demoRunning()) return;
        float[] s = demoSamples;
        if (s != null) {
            capture.startDemo(s);
            return;
        }
        new Thread(() -> {
            final float[] made = DemoTrack.synth();
            ui.post(() -> {
                demoSamples = made;
                if (visible && !hasAudioPermission() && !capture.demoRunning()) capture.startDemo(made);
            });
        }, "tav-demo").start();
    }

    /** Shows a hint for a few seconds, outside the silence loop. */
    private void showHintNow(String text) {
        hint.setText(text);
        hint.animate().cancel();
        hint.animate().alpha(1f).setDuration(600).start();
        ui.postDelayed(() -> {
            if (!silentNow) hint.animate().alpha(0f).setDuration(800).start();
        }, 7000);
    }

    private void removeIntro() {
        if (intro != null) root.removeView(intro);
        intro = null;
        introBody = introButton = null;
        ui.postDelayed(this::showGestureCoach, 1500);
    }

    /** Once, after the first-run card: the gestures nobody would guess, on a card over the running visuals. */
    private void showGestureCoach() {
        if (firstRun.getBoolean("coached", false) || notice != null || intro != null) return;
        firstRun.edit().putBoolean("coached", true).apply();
        notice = card("Three moves",
                "Swipe with two fingers for the next scene.\n\n"
                        + "Swipe the song title for the next track; tap it to pause the music.\n\n"
                        + "Drag one finger to draw with sound. Long-press, or \u2630, for the menu.",
                "Got it", this::dismissNotice, this::dismissNotice);
        TextView[] parts = (TextView[]) notice.getTag();
        parts[0].setGravity(Gravity.START);
    }

    /**
     * Android 13+ greys out notification access for apps installed from a downloaded APK
     * ("restricted setting") until the user allows it from the app's info page.
     */
    private void explainRestrictedSettings() {
        if (notice != null) return;
        notice = card("One more switch",
                "Android blocks notification access for apps installed outside an app store. "
                        + "Open App info, tap \u22EE in the top corner, choose \u201CAllow restricted settings\u201D, "
                        + "then pick the setting in TAV again.",
                "Open App info", () -> {
                    dismissNotice();
                    openAppInfo();
                }, this::dismissNotice);
    }

    private void dismissNotice() {
        if (notice != null) root.removeView(notice);
        notice = null;
    }

    /** Swipe the on-screen song title to skip, tap it to play or pause. Invisible: lets touches through. */
    // ------------------------------------------------------------------ cloud: account, synced settings, events

    /** Settings that follow the user between devices: everything in prefs but the sync bookkeeping. */
    private static final String SETTINGS_AT = "settings_at";
    /** prefs layout: 2 moved Custom to palette 11; 3 renumbered Quality (0 Auto, 1 Low, 2 Medium, 3 High). */
    static final int PREFS_VERSION = 3;
    static final String[] QUALITY_NAMES = {"Auto", "Low", "Medium", "High"};

    /** Quality before prefs version 3 (0 Battery, 1 Balanced, 2 Max) to the current numbering. */
    static int qualityV3(int old) {
        return old <= 0 ? 1 : old == 1 ? TavRenderer.QUALITY_AUTO : 3;
    }

    private final Runnable syncSettings = () -> cloud.syncSettings(settingsDocument(), prefs.getLong(SETTINGS_AT, 0), (remote, err) -> {
        if (remote == null) return;   // ours was newer, or offline
        applyRemoteSettings(remote);
    });

    private final SharedPreferences.OnSharedPreferenceChangeListener cloudPrefs = (p, key) -> {
        if (key == null || key.equals(SETTINGS_AT)) return;
        Object v = p.getAll().get(key);
        if (!key.equals("ap_weights")) {   // the autopilot's own bookkeeping is synced but not an event
            try {   // the value tells which choices stick, not just which were touched
                cloud.track("setting_changed", new JSONObject().put("key", key).put("to", v == null ? JSONObject.NULL : v));
            } catch (JSONException ignored) {
            }
        }
        p.edit().putLong(SETTINGS_AT, System.currentTimeMillis()).apply();
        ui.removeCallbacks(syncSettings);
        ui.postDelayed(syncSettings, 1500);
    };

    private void startCloud(Intent intent) {
        cloud.setListener(() -> {
            if (accountCard != null) refreshAccount();
        });
        if (cloud.takeTokens(intent.getData())) Toast.makeText(this, "Signed in", Toast.LENGTH_SHORT).show();
        cloud.start();
        if (!cloud.enabled()) return;
        prefs.registerOnSharedPreferenceChangeListener(cloudPrefs);
        ui.postDelayed(syncSettings, 3000);   // after the anonymous sign-in has had a chance
        ui.postDelayed(() -> looks.sync(cloud, (changed, err) -> {
            refreshLooksChip();
            refreshHome();
            if (changed && looksCard != null) {   // redraw the list with what came in
                closeLooks(true);
                showLooks();
            }
        }), 4500);
    }

    private void endSession() {
        if (sessionStartedAt == 0) return;
        try {
            cloud.track("session_end", new JSONObject().put("seconds", (System.currentTimeMillis() - sessionStartedAt) / 1000)
                    .put("end_scene", Scene.ALL[Math.max(0, Math.min(Scene.ALL.length - 1, prefs.getInt("scene", 0)))].name)
                    .put("autopilot", autopilotMode() != Autopilot.OFF).put("autopilot_mode", Autopilot.MODE_NAMES[autopilotMode()]).put("mode", "normal"));
        } catch (JSONException ignored) {
        }
        sessionStartedAt = 0;
        cloud.flush();
        cloud.newSession();
    }

    private JSONObject settingsDocument() {
        JSONObject doc = new JSONObject();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (e.getKey().equals(SETTINGS_AT)) continue;
            try {
                doc.put(e.getKey(), e.getValue());
            } catch (JSONException ignored) {
            }
        }
        return doc;
    }

    /** The cloud copy is newer: take it, keeping each key's type, and push it to the renderer. */
    private void applyRemoteSettings(JSONObject remote) {
        prefs.unregisterOnSharedPreferenceChangeListener(cloudPrefs);
        SharedPreferences.Editor ed = prefs.edit();
        Map<String, ?> all = prefs.getAll();
        for (java.util.Iterator<String> it = remote.keys(); it.hasNext(); ) {
            String k = it.next();
            if (k.startsWith("_") || k.equals("prefs_version")) continue;   // the layout version is this app's, not the account's
            Object cur = all.get(k), v = remote.opt(k);
            if (v == null || v == JSONObject.NULL) continue;
            if (k.equals("quality") && v instanceof Number && remote.optInt("prefs_version", PREFS_VERSION) < PREFS_VERSION) v = qualityV3(((Number) v).intValue());
            if (cur instanceof Boolean || v instanceof Boolean) ed.putBoolean(k, v instanceof Boolean ? (Boolean) v : !v.toString().equals("0"));
            else if (cur instanceof Float || (cur == null && k.startsWith("eq_") && !k.equals("eq_mode")) || (cur == null && k.startsWith("cm_")) || (cur == null && k.startsWith("tune_")))
                ed.putFloat(k, (float) remote.optDouble(k, 0));
            else if (v instanceof Number) ed.putInt(k, ((Number) v).intValue());
            else ed.putString(k, v.toString());
        }
        ed.putLong(SETTINGS_AT, remote.optLong("_updated_at", System.currentTimeMillis())).apply();
        prefs.registerOnSharedPreferenceChangeListener(cloudPrefs);
        titleAlways = prefs.getBoolean("titleAlways", false);
        equalizer = savedEqualizer();
        eqMode = prefs.getInt("eq_mode", 1);
        colourMode = prefs.getInt("cm_mode", ColourDrive.DEFAULT);
        colourDrive = colourMode == ColourDrive.CUSTOM ? savedColourDrive() : ColourDrive.preset(colourMode);
        applyPrefs();
        refreshChips();
    }

    private TextView accountText, streakText;
    private EditText emailField;

    /** Glass card: streak and listening time, email or Google sign-in, the analytics switch. */
    private void showAccount() {
        if (accountCard != null) return;
        LinearLayout box = pageBox();
        note(box, "An account keeps your looks, settings and streak on every device. Guests can do everything too; the account only makes it follow you.");

        streakText = text(26, Color.WHITE, true);
        streakText.setShadowLayer(0, 0, 0, 0);
        streakText.setPadding(0, px(10), 0, 0);
        box.addView(streakText);
        accountText = text(13.5f, 0xCCFFFFFF, false);
        accountText.setShadowLayer(0, 0, 0, 0);
        accountText.setLineSpacing(0, 1.2f);
        accountText.setPadding(0, px(6), 0, px(10));
        box.addView(accountText);

        emailField = new EditText(this);
        emailField.setHint("you@example.com");
        emailField.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        emailField.setSingleLine(true);
        emailField.setTextColor(Color.WHITE);
        emailField.setHintTextColor(0x66FFFFFF);
        emailField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        emailField.setPadding(px(16), px(10), px(16), px(10));
        GradientDrawable fieldBg = new GradientDrawable();
        fieldBg.setCornerRadius(px(18));
        fieldBg.setColor(0x40000000);
        fieldBg.setStroke(px(1), 0x33FFFFFF);
        emailField.setBackground(fieldBg);
        box.addView(emailField, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, px(10), 0, 0);
        row.addView(actionChip("Email me a link", true, v -> {
            String email = emailField.getText().toString().trim();
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                accountText.setText("Enter an email address first.");
                return;
            }
            cloud.sendMagicLink(email, (how, err) -> accountText.setText(err != null ? "Could not send the link: " + err.getMessage()
                    : "confirm".equals(how) ? "Check " + email + ": tap the link and this guest becomes your account."
                    : "Check " + email + " for your sign-in link."));
        }));
        row.addView(actionChip("Google", false, v -> cloud.googleUrl((u, err) -> {
            if (u != null) startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
        })));
        row.addView(actionChip("Sign out", false, v -> cloud.signOut((ok, err) -> refreshAccount())));
        box.addView(row);

        CheckBox analytics = new CheckBox(this);
        analytics.setText("Help improve TAV with anonymous usage stats");
        analytics.setTextColor(0xCCFFFFFF);
        analytics.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        analytics.setChecked(cloud.analyticsOn());
        analytics.setPadding(px(6), px(12), 0, px(4));
        analytics.setOnCheckedChangeListener((b, on) -> cloud.setAnalytics(on));
        box.addView(analytics);
        TextView forget = text(12.5f, 0x99FFFFFF, false);
        forget.setShadowLayer(0, 0, 0, 0);
        forget.setText("Delete my usage history");
        forget.setPaintFlags(forget.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        forget.setPadding(px(6), px(6), 0, 0);
        forget.setOnClickListener(v -> cloud.forgetMyEvents((r, err) -> {
            Toast.makeText(this, err == null ? "History deleted" : "Failed: " + err.getMessage(), Toast.LENGTH_SHORT).show();
            refreshAccount();
        }));
        box.addView(forget);

        accountCard = openCard("Your TAV", box);
        refreshAccount();
    }

    private TextView actionChip(String label, boolean primary, View.OnClickListener on) {
        TextView c = chip(label, primary, v -> {
            on.onClick(v);
            pokeMenu();
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.rightMargin = px(8);
        c.setLayoutParams(clp);
        return c;
    }

    private void refreshAccount() {
        if (accountCard == null) return;
        boolean guest = cloud.anonymous();
        emailField.setVisibility(guest ? View.VISIBLE : View.GONE);
        accountText.setText(guest ? "You are a guest. Add an email or Google to keep your streak, settings and presets on every device."
                : "Signed in as " + cloud.email() + ". Settings and presets follow you to every device.");
        streakText.setText("");
        cloud.me((me, err) -> {
            if (me == null || accountCard == null) return;
            int streak = me.optInt("current_streak"), week = me.optInt("days_this_week"), minutes = me.optInt("listened_seconds") / 60;
            streakText.setText(streak + " day streak" + "  ·  " + week + "/7 days  ·  " + minutes + " min of light");
        });
    }

    private void closeAccount(boolean backToMenu) {
        final View card = accountCard;
        if (card == null) return;
        accountCard = null;
        closeCard(card, backToMenu);
    }

    // ------------------------------------------------------------------ looks: save, reapply, share

    /** Everything on screen right now as a Look, without a name. */
    private Look captureLook() {
        Look l = new Look();
        int pal = prefs.getInt("palette", Palettes.ALBUM);
        System.arraycopy(Palettes.toColors(paletteFor(pal)), 0, l.argb, 0, 3);
        // album colours are a snapshot in a Look: they would mean something else on the receiver's music
        l.palette = pal == Palettes.CUSTOM || pal == Palettes.ALBUM ? "Custom" : Palettes.NAMES[pal];
        l.rainbow = pal == Palettes.RAINBOW;
        l.motion = colourDrive;
        Scene sc = Scene.ALL[clampScene(prefs.getInt("scene", 0))];
        l.scene = sc.id;
        JSONObject tune = new JSONObject();   // only the sliders moved off the scene's own look
        for (int k = 0; k < Scene.TUNE; k++) {
            float v = tuneValue(sc, k);
            if (v != 0.5f) try {
                tune.put(sc.tuneId(k), v);
            } catch (JSONException ignored) {
            }
        }
        if (tune.length() > 0) l.tune = tune;
        l.sym = prefs.getInt("sym", 1);
        l.brightness = prefs.getInt("brightness", TavRenderer.DEFAULT_BRIGHTNESS);
        l.calm = prefs.getInt("motion", 0);
        l.eq = equalizer;
        l.eqMode = eqMode;
        return l;
    }

    private static int clampScene(int i) {
        return Math.max(0, Math.min(Scene.ALL.length - 1, i));
    }

    /** Puts a Look on screen: prefs, renderer, menu chips. origin is own, shared or daily. */
    private void applyLook(Look l, String origin, String presetId) {
        SharedPreferences.Editor ed = prefs.edit();
        int pal = -1;
        for (int i = 1; i < Palettes.NAMES.length; i++)
            if (i != Palettes.CUSTOM && Palettes.NAMES[i].equalsIgnoreCase(l.palette)) pal = i;
        if (pal < 0) {
            pal = Palettes.CUSTOM;
            for (int k = 0; k < 3; k++) {
                custom[k] = l.argb[k];
                ed.putInt("custom" + k, l.argb[k]);
            }
        }
        ed.putInt("palette", pal);
        int sceneIdx = l.scene != null ? Scene.indexOf(l.scene) : -1;
        if (sceneIdx >= 0) ed.putInt("scene", sceneIdx);
        if (sceneIdx >= 0 && l.tune != null) {   // the Look's scene back to its own look, then the sliders it carries
            Scene sc = Scene.ALL[sceneIdx];
            for (int k = 0; k < Scene.TUNE; k++) ed.putFloat(tuneKey(sc, k), (float) l.tune.optDouble(sc.tuneId(k), 0.5));
        }
        if (l.sym > 0) ed.putInt("sym", l.sym);
        if (l.brightness >= 0) ed.putInt("brightness", l.brightness);
        if (l.calm >= 0) ed.putInt("motion", l.calm);
        ed.apply();
        if (l.motion != null) {
            int mode = ColourDrive.CUSTOM;
            for (int m = 0; m < ColourDrive.CUSTOM; m++) if (sameDrive(ColourDrive.preset(m), l.motion)) mode = m;
            setColourDrive(l.motion, mode);
        }
        if (l.eq != null) setEqualizer(l.eq, l.eqMode >= 0 ? l.eqMode : 2);
        applyPrefs();
        final int lookScene = prefs.getInt("scene", 0);
        view.queueEvent(() -> {   // a look is the user's pick: the autopilot holds its scene and keeps its colours for this track
            renderer.setScene(lookScene);
            renderer.autopilotUserColours();
        });
        refreshChips();
        flash(spaced(l.name));
        try {
            cloud.track("look_applied", new JSONObject().put("preset_id", presetId == null ? JSONObject.NULL : presetId).put("origin", origin));
        } catch (JSONException ignored) {
        }
    }

    private static boolean sameDrive(ColourDrive a, ColourDrive b) {
        return a.drift == b.drift && a.beatStep == b.beatStep && a.dropStep == b.dropStep && a.tilt == b.tilt && a.pump == b.pump;
    }

    /** Re-styles every chip row from prefs, after a Look or a newer cloud copy of the settings. */
    private void refreshChips() {
        markScene(clampScene(prefs.getInt("scene", 0)));
        int symSel = 0;
        for (int i = 0; i < SYMMETRY.length; i++) if (SYMMETRY[i] == prefs.getInt("sym", 1)) symSel = i;
        select(mirrorChips, symSel);
        markPalette(prefs.getInt("palette", Palettes.ALBUM));
        select(cmChips, colourMode);
        select(brightChips, prefs.getInt("brightness", TavRenderer.DEFAULT_BRIGHTNESS));
        select(motionChips, prefs.getInt("motion", 0));
        select(eqChips, eqMode);
        select(autoChips, autopilotMode());
        select(qualityChips, prefs.getInt("quality", TavRenderer.DEFAULT_QUALITY));
        refreshHome();
    }

    private void select(List<TextView> chips, int sel) {
        for (int i = 0; i < chips.size(); i++) styleChip(chips.get(i), i == sel);
    }

    private void refreshLooksChip() {
        refreshHome();
    }

    /** Three random colours that go together, into the Custom slot (menu → Colours → Shuffle, or the editor). */
    private void shuffleColours() {
        System.arraycopy(Palettes.shuffle(rnd), 0, custom, 0, 3);
        saveCustom();
        prefs.edit().putInt("palette", Palettes.CUSTOM).apply();
        final float[][] p = Palettes.fromColors(custom);
        view.queueEvent(() -> {
            renderer.setPalette(p, colourEditor != null);
            renderer.setRainbow(false);
        });
        markPalette(Palettes.CUSTOM);
        refreshHome();
        if (colourEditor != null) selectSwatch(editing);
        else flash("S H U F F L E");
        cloud.track("setting_changed", "key", "colours_shuffle");
    }

    /** The body of a Looks page; the title goes to the sheet's header. */
    private LinearLayout cardBox(String title) {
        LinearLayout box = pageBox();
        box.setTag(title);
        return box;
    }

    private EditText field(String hint, String value) {
        EditText f = new EditText(this);
        f.setHint(hint);
        f.setText(value);
        f.setSingleLine(true);
        f.setImeOptions(EditorInfo.IME_ACTION_DONE);
        f.setTextColor(Color.WHITE);
        f.setHintTextColor(0x66FFFFFF);
        f.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        f.setPadding(px(16), px(10), px(16), px(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(18));
        bg.setColor(0x40000000);
        bg.setStroke(px(1), 0x33FFFFFF);
        f.setBackground(bg);
        return f;
    }

    /** Three dots in a Look's colours. */
    private View swatchDots(int[] argb) {
        LinearLayout dots = new LinearLayout(this);
        dots.setGravity(Gravity.CENTER_VERTICAL);
        for (int c : argb) {
            View d = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(c);
            d.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(14), px(14));
            lp.rightMargin = px(3);
            dots.addView(d, lp);
        }
        dots.setPadding(0, 0, px(10), 0);
        return dots;
    }

    /** Looks → Save this look: a name (suggested from scene and colours), Save, Save and share. */
    private void showSaveLook() {
        if (looksCard != null) return;
        final Look look = captureLook();
        int pal = prefs.getInt("palette", Palettes.ALBUM);
        String colours = pal == Palettes.ALBUM ? (lastTitle.isEmpty() ? "Album" : lastTitle) : pal == Palettes.CUSTOM ? "my colours" : Palettes.NAMES[pal];
        final EditText name = field("Name", looks.suggestName(Scene.ALL[clampScene(prefs.getInt("scene", 0))].name + " · " + colours));
        LinearLayout box = cardBox("Save this look");
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, px(6), 0, px(4));
        row.addView(swatchDots(look.argb));
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        box.addView(row);
        TextView what = text(12.5f, 0x99FFFFFF, false);
        what.setShadowLayer(0, 0, 0, 0);
        what.setText("Scene, colours and their motion, mirror, brightness, motion and equalizer, as they are now.");
        what.setPadding(0, px(6), 0, px(10));
        box.addView(what);
        LinearLayout actions = new LinearLayout(this);
        actions.addView(actionChip("Save", true, v -> {
            saveLook(look, name.getText().toString());
            closeLooks(false);
        }));
        actions.addView(actionChip("Save and share", false, v -> {
            LookStore.Entry e = saveLook(look, name.getText().toString());
            closeLooks(false);
            shareLook(e);
        }));
        box.addView(actions);
        name.setOnEditorActionListener((v, action, ev) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            saveLook(look, name.getText().toString());
            closeLooks(false);
            return true;
        });
        looksCard = openCard("Save this look", box);
        name.requestFocus();
    }

    private LookStore.Entry saveLook(Look look, String name) {
        look.name = Look.cleanName(name);
        LookStore.Entry e = looks.add(look, "own");
        refreshLooksChip();
        looks.pushEntry(cloud, e, null);
        flash("S A V E D");
        try {
            cloud.track("look_saved", new JSONObject().put("preset_id", e.cloudId == null ? JSONObject.NULL : e.cloudId).put("origin", "own"));
        } catch (JSONException ignored) {
        }
        return e;
    }

    /** Looks → My looks: tap a name to apply it; Share and ✕ per Look. */
    private void showLooks() {
        if (looksCard != null) return;
        LinearLayout box = cardBox("Looks");
        note(box, "A look is everything on screen: scene, colours and their motion, mirror, brightness, motion and equalizer. Save one, and bring it back or share it as a link or a six-letter code.");
        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, px(4), 0, px(10));
        actions.addView(actionChip("\u2661 Save this look", true, v -> {
            closeLooks(true);
            showSaveLook();
        }));
        actions.addView(actionChip("Open a code or link", false, v -> {
            closeLooks(true);
            showOpenCode();
        }));
        box.addView(actions);
        if (looks.isEmpty()) note(box, "Nothing saved yet. Set the scene and colours the way you like them, then Save this look.");
        else box.addView(label("My looks"));
        for (final LookStore.Entry e : new ArrayList<>(looks.all())) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, px(5), 0, px(5));
            row.addView(swatchDots(e.look.argb));
            TextView name = text(15, Color.WHITE, false);
            name.setShadowLayer(0, 0, 0, 0);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setText(e.look.name + (e.code != null ? "   · " + e.code : ""));
            name.setContentDescription("Apply the look " + e.look.name);
            name.setOnClickListener(v -> {
                applyLook(e.look, e.origin, e.cloudId);
                closeLooks(false);
            });
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            row.addView(actionChip("Share", false, v -> shareLook(e)));
            TextView del = actionChip("✕", false, v -> {
                looks.delete(cloud, e);
                refreshLooksChip();
                closeLooks(true);
                showLooks();
            });
            del.setContentDescription("Delete the look " + e.look.name);
            row.addView(del);
            box.addView(row);
        }
        looksCard = openCard("Looks", box);
    }

    /** Looks → Open a code or link: paste a six-letter code or a whole link. */
    private void showOpenCode() {
        if (looksCard != null) return;
        LinearLayout box = cardBox("Open a shared look");
        final EditText f = field("Code or link", "");
        f.setImeOptions(EditorInfo.IME_ACTION_GO);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, px(6), 0, px(6));
        row.addView(f, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView go = actionChip("Open", true, v -> openLookText(f.getText().toString()));
        ((LinearLayout.LayoutParams) go.getLayoutParams()).leftMargin = px(8);
        row.addView(go);
        box.addView(row);
        f.setOnEditorActionListener((v, action, ev) -> {
            if (action != EditorInfo.IME_ACTION_GO) return false;
            openLookText(f.getText().toString());
            return true;
        });
        TextView t = text(12.5f, 0x99FFFFFF, false);
        t.setShadowLayer(0, 0, 0, 0);
        t.setText("A look someone shared with you: the six letters, or the whole link. Links from TAV open here on their own.");
        t.setLineSpacing(0, 1.25f);
        box.addView(t);
        looksCard = openCard("Open a shared look", box);
        f.requestFocus();
    }

    private void closeLooks(boolean backToMenu) {
        final View card = looksCard;
        if (card == null) return;
        looksCard = null;
        closeCard(card, backToMenu);
    }

    /** A code, a link or a payload, typed or from a deep link. */
    private void openLookText(String s) {
        final String code = Look.codeIn(s);
        Look inline = Look.decode(Look.payloadIn(s));
        if (inline != null) {
            receiveLook(inline, code, null);
            return;
        }
        if (code == null) {
            Toast.makeText(this, "That is not a TAV code or link", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!cloud.enabled()) {
            Toast.makeText(this, "Codes need the cloud edition; ask for the full link instead", Toast.LENGTH_LONG).show();
            return;
        }
        flash("O P E N I N G");
        cloud.presetByCodeAnon(code, (row, err) -> {
            Look l = row != null ? Look.fromJson(row.optJSONObject("data")) : null;
            if (l == null) Toast.makeText(this, err != null ? "Could not reach the cloud: " + err.getMessage() : "No look has the code " + code, Toast.LENGTH_LONG).show();
            else receiveLook(l, code, row.optString("id", null));
        });
    }

    /** A Look from a link or code: on screen at once (that is the point), then offered for keeping. */
    private void receiveLook(final Look l, final String code, final String presetId) {
        if (sheet.shown()) closeAll();
        applyLook(l, "shared", presetId);
        dismissIncoming();
        for (LookStore.Entry e : looks.all())
            if (e.look.name.equalsIgnoreCase(l.name) && e.look.toJson().toString().equals(l.toJson().toString())) return;   // already on the shelf
        if (intro != null) {   // first run, from a link: keep it without a second card over the permission one
            LookStore.Entry e = looks.add(l, "shared");
            e.code = code;
            refreshLooksChip();
            return;
        }
        incoming = card("“" + l.name + "”", "A shared look, now on screen. Keep it, and it is in Looks → My looks whenever you want it back.",
                "Keep it", () -> {
                    LookStore.Entry e = looks.add(l, "shared");
                    e.code = code;
                    refreshLooksChip();
                    looks.pushEntry(cloud, e, null);
                    dismissIncoming();
                    flash("S A V E D");
                }, this::dismissIncoming);
    }

    private void dismissIncoming() {
        if (incoming != null) root.removeView(incoming);
        incoming = null;
    }

    /** Shares a Look as a link (and a six-letter code when the cloud is there) through the system sheet. */
    private void shareLook(final LookStore.Entry e) {
        looks.publish(cloud, e, (x, err) -> {
            refreshLooksChip();
            String link = e.look.shareUrl(e.code);
            String text = "My TAV look “" + e.look.name + "”" + (e.code != null ? " · code " + e.code : "") + "\n" + link;
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "TAV look: " + e.look.name).putExtra(Intent.EXTRA_TEXT, text);
            try {
                startActivity(Intent.createChooser(send, "Share your look"));
            } catch (RuntimeException ex) {
                Toast.makeText(this, link, Toast.LENGTH_LONG).show();
            }
            try {
                cloud.track("look_shared", new JSONObject().put("preset_id", e.cloudId == null ? JSONObject.NULL : e.cloudId)
                        .put("channel", e.code != null ? "code" : "link"));
            } catch (JSONException ignored) {
            }
        });
    }

    /** tav://look?d=... and the web edition's share links (see AndroidManifest) open the Look. */
    private boolean handleLink(Uri uri) {
        if (uri == null) return false;
        boolean isLook = "tav".equals(uri.getScheme()) ? "look".equals(uri.getHost())
                : uri.getPath() != null && uri.getPath().startsWith("/TAV/web");
        if (!isLook) return false;
        String text = "#" + (uri.getEncodedFragment() != null ? uri.getEncodedFragment() : "") + "&" + (uri.getEncodedQuery() != null ? uri.getEncodedQuery() : "");
        if (Look.codeIn(text) == null && Look.payloadIn(text) == null) return false;   // the plain web page, nothing to open
        openLookText(text);
        return true;
    }

    private final class TitleGestures implements View.OnTouchListener {
        private float x0, y0, swipeX0;
        private boolean tracking, multi;

        private float avgX(MotionEvent e) {
            float sum = 0;
            for (int i = 0; i < e.getPointerCount(); i++) sum += e.getX(i);
            return sum / e.getPointerCount();
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    tracking = true;
                    multi = false;
                    x0 = e.getRawX();
                    y0 = e.getRawY();
                    pokeMenu();
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    multi = true;   // two fingers on the title: a scene swipe, as on the visuals
                    swipeX0 = avgX(e);
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                    if (multi && e.getPointerCount() == 2) {
                        float dx = avgX(e) - swipeX0;
                        if (Math.abs(dx) > 90 * dp) {
                            final int dir = dx < 0 ? 1 : -1;
                            view.queueEvent(() -> renderer.nextScene(dir));
                        }
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    tracking = false;
                    return true;
                case MotionEvent.ACTION_UP: {
                    if (!tracking || multi) {
                        tracking = false;
                        return true;
                    }
                    tracking = false;
                    float dx = e.getRawX() - x0, dy = e.getRawY() - y0;
                    float slop = ViewConfiguration.get(MainActivity.this).getScaledTouchSlop();
                    if (Math.abs(dx) > 56 * dp && Math.abs(dx) > 1.5f * Math.abs(dy)) skip(dx < 0);
                    else if (Math.hypot(dx, dy) < slop) v.performClick();
                    if (!titleAlways) {   // using it keeps it around a little longer
                        ui.removeCallbacks(hideTrack);
                        ui.postDelayed(hideTrack, 5000);
                    }
                    return true;
                }
                default:
                    return true;
            }
        }
    }
}
