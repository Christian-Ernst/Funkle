package io.github.christianernst.tav;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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

/** Full-screen visualizer with a glassy pop-up control panel. */
public final class MainActivity extends Activity implements TavRenderer.Host, AudioCapture.Listener, MediaWatcher.Listener {
    private static final int REQ_AUDIO = 7, REQ_NOTIFY = 8;
    private static final int[] SYMMETRY = {1, 2, 3, 4, 6, 8, 12};
    private static final String HINT_IDLE = "Play something on Spotify \u266A\nTap to make ripples \u00B7 drag to draw with sound";
    private static final String HINT_BLOCKED = "Music is playing, but TAV hears silence.\nTurn the volume up, or open \u2630 \u2192 Listen to \u2192 Microphone.";
    private static final String HINT_NO_PERMISSION = "Without audio access TAV can't hear the music.\nTap and drag still make waves.";
    private static final String HINT_DEMO = "This is TAV's built-in beat.\nTap to make ripples \u00B7 drag to draw \u00B7 \u2630 \u2192 Listen to, for your own music";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private AudioCapture capture;
    private TavRenderer renderer;
    private TavView view;
    private MediaWatcher media;

    private FrameLayout root;
    private TextView sceneLabel, hint, title, artist, menuButton;
    private LinearLayout trackBox, panelContent;
    private ScrollView panel;
    private View intro, notice;
    private TextView introBody, introButton;
    private boolean permissionPending, openSettingsInstead, askedNotificationAccess;
    private final List<TextView> sceneChips = new ArrayList<>(), sourceChips = new ArrayList<>();
    // the other rows, so a Look or a newer cloud copy of the settings can move the chips too
    private final List<TextView> mirrorChips = new ArrayList<>(), paletteChips = new ArrayList<>(), cmChips = new ArrayList<>(),
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
        if (panel.getVisibility() != View.VISIBLE && colourEditor == null && equalizerCard == null && tuneCard == null)
            menuButton.animate().alpha(0f).setDuration(600).start();
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
        if (intro != null || notice != null) return;   // a card is explaining things already
        // Music is known to be playing but the output mix reads as silence: the ROM or the
        // player is keeping audio away from the visualizer. Point at the microphone.
        boolean blocked = capture.source() == AudioCapture.SOURCE_SYSTEM && media.isPlaying();
        hint.setText(capture.demoRunning() ? HINT_DEMO : !hasAudioPermission() ? HINT_NO_PERMISSION : blocked ? HINT_BLOCKED : HINT_IDLE);
        if (hint.getAlpha() < 1f) hint.animate().alpha(1f).setDuration(800).start();
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        dp = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("tav", MODE_PRIVATE);
        firstRun = getSharedPreferences("tav_state", MODE_PRIVATE);
        if (!firstRun.contains("first_open_at")) firstRun.edit().putLong("first_open_at", System.currentTimeMillis()).apply();
        looks = new LookStore(this);
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

    @Override
    protected void onResume() {
        super.onResume();
        immersive();
        pokeMenu();
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
        else if (panel.getVisibility() == View.VISIBLE) togglePanel();
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
            for (int i = 0; i < sceneChips.size(); i++) styleChip(sceneChips.get(i), i == scene);
            if (tuneCard != null && tuneScene != scene) closeTune(false);   // the sliders belong to the old scene
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
        final boolean auto = prefs.getBoolean("autopilot", true), calm = prefs.getInt("motion", 0) == 1;
        view.queueEvent(() -> {
            renderer.setScene(scene);
            renderer.setSymmetry(sym);
            renderer.setQuality(q);
            renderer.setBrightness(lit);
            renderer.setCalm(calm);
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
        LinearLayout box = sliderCard("Tune " + sc.name, sc.tune, tuneSliders, tuneLabels,
                (k, v) -> {
                    setTune(s, k, v);
                    refreshTuneLabels(sc);
                }, () -> closeTune(false));
        tuneCard = openCard(box);
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
        });
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
        lp.setMargins(px(24), 0, px(24), px(120));
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

        buildPanel();
    }

    private void buildPanel() {
        panel = new ScrollView(this);
        panel.setBackground(glass(0xD90B0B14));
        panel.setVisibility(View.GONE);
        panel.setVerticalScrollBarEnabled(false);
        panelContent = new LinearLayout(this);
        panelContent.setOrientation(LinearLayout.VERTICAL);
        panelContent.setPadding(px(18), px(16), px(18), px(18));
        panel.addView(panelContent);

        TextView head = text(18, Color.WHITE, true);
        head.setText("T A V");
        head.setLetterSpacing(0.3f);
        panelContent.addView(head);

        TextView nowLabel = text(11.5f, 0x99FFFFFF, false);
        nowLabel.setText("NOW PLAYING");
        nowLabel.setLetterSpacing(0.12f);
        nowLabel.setPadding(0, px(14), 0, px(4));
        panelContent.addView(nowLabel);
        LinearLayout nowRow = new LinearLayout(this);
        nowRow.setGravity(Gravity.CENTER_VERTICAL);
        panelArt = artView(48, 8);
        panelArt.setContentDescription("Album art");
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(px(48), px(48));
        plp.rightMargin = px(12);
        nowRow.addView(panelArt, plp);
        LinearLayout nowTexts = new LinearLayout(this);
        nowTexts.setOrientation(LinearLayout.VERTICAL);
        nowPlaying = text(15, Color.WHITE, false);
        nowPlaying.setShadowLayer(0, 0, 0, 0);
        nowPlaying.setSingleLine(true);
        nowPlaying.setEllipsize(TextUtils.TruncateAt.END);
        nowPlaying.setText("Nothing yet \u2014 start a song in Spotify");
        nowTexts.addView(nowPlaying);
        openPlayer = text(13, 0xFFFF7AB8, false);
        openPlayer.setShadowLayer(0, 0, 0, 0);
        openPlayer.setText(playerLabel(false));
        openPlayer.setPadding(0, px(4), px(12), px(4));
        openPlayer.setOnClickListener(v -> {
            openPlayer();
            pokeMenu();
        });
        nowTexts.addView(openPlayer);
        nowRow.addView(nowTexts, new LinearLayout.LayoutParams(0, -2, 1f));
        panelContent.addView(nowRow);
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(0, px(10), 0, 0);
        TransportButton prev = new TransportButton(this, TransportButton.PREVIOUS, false);
        playButton = new TransportButton(this, TransportButton.PLAY, true);
        TransportButton next = new TransportButton(this, TransportButton.NEXT, false);
        prev.setOnClickListener(v -> {
            skip(false);
            pokeMenu();
        });
        playButton.setOnClickListener(v -> {
            playPause();
            pokeMenu();
        });
        next.setOnClickListener(v -> {
            skip(true);
            pokeMenu();
        });
        LinearLayout.LayoutParams small = new LinearLayout.LayoutParams(px(46), px(46)), big = new LinearLayout.LayoutParams(px(58), px(58));
        small.rightMargin = big.rightMargin = px(14);
        controls.addView(prev, small);
        controls.addView(playButton, big);
        controls.addView(next, new LinearLayout.LayoutParams(px(46), px(46)));
        panelContent.addView(controls);

        List<TextView> lookChips = new ArrayList<>();
        addRow("Looks (save what is on screen, share it, bring it back)", new String[]{"Save this look", "My looks", "Open a code or link"}, -1, lookChips, i -> {
            for (TextView c : lookChips) styleChip(c, false);   // actions, not a choice
            if (i == 0) showSaveLook();
            else if (i == 1) showLooks();
            else showOpenCode();
        });
        looksChip = lookChips.get(1);
        for (TextView c : lookChips) styleChip(c, false);
        refreshLooksChip();

        String[] sceneNames = new String[Scene.ALL.length];
        for (int i = 0; i < sceneNames.length; i++) sceneNames[i] = Scene.ALL[i].name;
        addRow("Scene", sceneNames, prefs.getInt("scene", 0), sceneChips, i -> view.queueEvent(() -> renderer.setScene(i)));

        addRow("Tune this scene (three sliders, live)", new String[]{"Sliders", "Reset"}, -1, null, i -> {
            if (i == 0) showTune();
            else resetTune();
        });

        String[] shapes = new String[Shapes.NAMES.length + 1];
        System.arraycopy(Shapes.NAMES, 0, shapes, 0, Shapes.NAMES.length);
        shapes[shapes.length - 1] = "✕ Clear";
        addRow("Draw a sound shape", shapes, -1, null, i -> {
            if (i == Shapes.NAMES.length) {
                view.queueEvent(renderer::clearStrokes);
                return;
            }
            final List<float[]> pts = Shapes.make(i, 0, 0, 0.62f);
            view.queueEvent(() -> renderer.addShape(pts));
        });

        String[] syms = {"Off", "2", "3", "4", "6", "8", "12"};
        int symSel = 0;
        for (int i = 0; i < SYMMETRY.length; i++) if (SYMMETRY[i] == prefs.getInt("sym", 1)) symSel = i;
        addRow("Mirror (mandala drawing)", syms, symSel, mirrorChips, i -> {
            prefs.edit().putInt("sym", SYMMETRY[i]).apply();
            view.queueEvent(() -> renderer.setSymmetry(SYMMETRY[i]));
        });

        String[] paletteNames = new String[Palettes.NAMES.length + 1];
        System.arraycopy(Palettes.NAMES, 0, paletteNames, 0, Palettes.NAMES.length);
        paletteNames[Palettes.NAMES.length] = "\u2684 Shuffle";
        addRow("Colours", paletteNames, prefs.getInt("palette", Palettes.ALBUM), paletteChips, i -> {
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
            pushPalette();
            if (i == Palettes.CUSTOM) showColourEditor();
        });

        addRow("Colour motion (how the colours move with the music)", ColourDrive.MODE_NAMES, colourMode, cmChips, i -> {
            if (i == ColourDrive.CUSTOM) showColourMotion();   // Custom: the sliders
            else setColourDrive(ColourDrive.preset(i), i);
        });

        addRow("Brightness", new String[]{"Dim", "Soft", "Normal", "Bright"},
                prefs.getInt("brightness", TavRenderer.DEFAULT_BRIGHTNESS), brightChips, i -> {
                    prefs.edit().putInt("brightness", i).apply();
                    view.queueEvent(() -> renderer.setBrightness(i));
                });

        addRow("Motion", new String[]{"Full", "Calm (no flashes)"}, prefs.getInt("motion", 0), motionChips, i -> {
            prefs.edit().putInt("motion", i).apply();
            view.queueEvent(() -> renderer.setCalm(i == 1));
        });

        addRow("Equalizer (how hard the scenes react)", new String[]{"Flat", "Punch", "Custom"}, eqMode, eqChips, i -> {
            if (i == 2) showEqualizer();   // Custom: the sliders
            else setEqualizer(i == 0 ? Equalizer.flat() : Equalizer.punch(), i);
        });

        addRow("Listen to", new String[]{"Phone audio (Spotify)", "Microphone"}, prefs.getInt("source", 0), sourceChips, i -> {
            prefs.edit().putInt("source", i).apply();
            if (hasAudioPermission()) startAudio();
            else requestAudio();
        });

        addRow("Autopilot (switch scenes with the music)", new String[]{"On", "Off"}, prefs.getBoolean("autopilot", true) ? 0 : 1,
                autoChips, i -> {
                    final boolean on = i == 0;
                    prefs.edit().putBoolean("autopilot", on).apply();
                    view.queueEvent(() -> renderer.setAutopilot(on));
                });

        addRow("Song title", new String[]{"Fades out", "Always on screen"}, titleAlways ? 1 : 0, new ArrayList<>(), i -> {
            titleAlways = i == 1;
            prefs.edit().putBoolean("titleAlways", titleAlways).apply();
            ui.removeCallbacks(hideTrack);
            if (lastTitle.isEmpty()) return;
            if (titleAlways) fadeTrack(trackAlpha(), 400);
            else ui.postDelayed(hideTrack, 2500);
        });

        addRow("Light up when music starts (a quiet notification)", new String[]{"Off", "Spotify", "Any player"},
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

        addRow("Quality (Auto finds the sharpest your phone keeps smooth)", QUALITY_NAMES, prefs.getInt("quality", TavRenderer.DEFAULT_QUALITY), qualityChips, i -> {
            prefs.edit().putInt("quality", i).apply();
            view.queueEvent(() -> renderer.setQuality(i));
        });

        if (cloud.enabled()) addRow("Account", new String[]{"Your streak, sign-in and privacy"}, -1, null, i -> showAccount());

        TextView help = text(12.5f, 0x99FFFFFF, false);
        help.setText("Tap: ripple   \u2022   Drag: draw with sound   \u2022   Two-finger swipe: next scene   \u2022   Long-press: this menu\n"
                + "Song title on screen: swipe it for the next or previous track, tap it to pause.\n"
                + "A look is the scene, colours, mirror, brightness, motion and equalizer together. Share one as a link or six-letter code.\n"
                + "Album colours and the music-started nudge need notification access (only used to read what's playing).");
        help.setLineSpacing(0, 1.25f);
        help.setPadding(0, px(14), 0, 0);
        panelContent.addView(help);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        lp.setMargins(px(12), 0, px(12), px(100));
        root.addView(panel, lp);
    }

    interface Pick {
        void on(int index);
    }

    /** Small spaced capitals above a row of chips or a slider. */
    private TextView label(String s) {
        TextView l = text(11.5f, 0x99FFFFFF, false);
        l.setText(s.toUpperCase(Locale.ROOT));
        l.setLetterSpacing(0.12f);
        l.setPadding(0, px(14), 0, px(6));
        return l;
    }

    private GradientDrawable glass(int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(px(26));
        g.setStroke(px(1), 0x33FFFFFF);
        return g;
    }

    private void addRow(String label, String[] options, int selected, List<TextView> keep, Pick pick) {
        panelContent.addView(label(label));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        hs.addView(row);
        final List<TextView> chips = keep != null ? keep : new ArrayList<>();
        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            TextView c = text(14, Color.WHITE, false);
            c.setShadowLayer(0, 0, 0, 0);
            c.setText(options[i]);
            c.setPadding(px(14), px(8), px(14), px(8));
            styleChip(c, i == selected);
            c.setOnClickListener(v -> {
                if (keep != null) for (int k = 0; k < chips.size(); k++) styleChip(chips.get(k), k == idx);
                pick.on(idx);
                pokeMenu();
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
            clp.rightMargin = px(8);
            row.addView(c, clp);
            chips.add(c);
        }
        panelContent.addView(hs);
    }

    private void styleChip(TextView c, boolean selected) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(px(18));
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
        else showPanel(panel.getVisibility() != View.VISIBLE);
    }

    private void showPanel(boolean show) {
        if (show) {
            panel.setVisibility(View.VISIBLE);
            panel.setAlpha(0f);
            panel.setTranslationY(40 * dp);
            panel.animate().alpha(1f).translationY(0).setDuration(250).setListener(null).start();
            menuButton.setText("✕");
        } else {
            panel.animate().alpha(0f).translationY(40 * dp).setDuration(200)
                    .withEndAction(() -> panel.setVisibility(View.GONE)).start();
            menuButton.setText("☰");
        }
        pokeMenu();
    }

    private void pokeMenu() {
        menuButton.animate().cancel();
        menuButton.setAlpha(0.85f);
        ui.removeCallbacks(hideMenu);
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
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(18), px(4), px(18), px(16));
        box.addView(label("Your colours · tap one to change it"));
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
        TextView shuffle = text(14, Color.WHITE, false);
        shuffle.setShadowLayer(0, 0, 0, 0);
        shuffle.setText("\u2684 Shuffle");
        shuffle.setContentDescription("Shuffle: three random colours that go together");
        shuffle.setPadding(px(16), px(10), px(16), px(10));
        styleChip(shuffle, false);
        shuffle.setOnClickListener(v -> shuffleColours());
        LinearLayout.LayoutParams shp = new LinearLayout.LayoutParams(-2, -2);
        shp.rightMargin = px(8);
        row.addView(shuffle, shp);
        TextView done = text(15, Color.WHITE, true);
        done.setShadowLayer(0, 0, 0, 0);
        done.setText("Done");
        done.setPadding(px(22), px(10), px(22), px(10));
        styleChip(done, true);
        done.setOnClickListener(v -> closeColourEditor(false));
        row.addView(done);
        box.addView(row);
        hueSlider = slider(box, "Hue");
        satSlider = slider(box, "Saturation");
        valSlider = slider(box, "Brightness");
        int[] rainbow = new int[7];
        for (int i = 0; i < rainbow.length; i++) rainbow[i] = Color.HSVToColor(new float[]{i * 60f, 1f, 1f});
        hueSlider.setColors(rainbow);

        colourEditor = openCard(box);
        selectSwatch(0);
    }

    private GradientSlider slider(LinearLayout box, String name) {
        TextView l = label(name);
        l.setPadding(0, px(8), 0, 0);
        box.addView(l);
        GradientSlider s = new GradientSlider(this, name);
        s.setListener((v, done) -> editColour(done));
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
        LinearLayout box = sliderCard("How the colours move", ColourDrive.NAMES, cmSliders, cmLabels,
                (i, v) -> {
                    setColourDrive(colourDrive.with(i, v * ColourDrive.MAX[i]), ColourDrive.CUSTOM);
                    refreshColourLabels();
                }, () -> closeColourMotion(false));
        colourCard = openCard(box);
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

    /** The body of a slider card: a title with a Done chip, then a label and a slider per name. */
    private LinearLayout sliderCard(String title, String[] names, GradientSlider[] sliders, TextView[] labels, Slide slide, Runnable done) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(18), px(4), px(18), px(16));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(label(title), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView doneChip = text(15, Color.WHITE, true);
        doneChip.setShadowLayer(0, 0, 0, 0);
        doneChip.setText("Done");
        doneChip.setPadding(px(22), px(10), px(22), px(10));
        styleChip(doneChip, true);
        doneChip.setOnClickListener(v -> done.run());
        head.addView(doneChip);
        box.addView(head);
        for (int i = 0; i < names.length; i++) {
            final int k = i;
            labels[i] = label(names[i]);
            box.addView(labels[i]);
            GradientSlider s = new GradientSlider(this, names[i]);
            s.setColors(0xFF2A2740, 0xFFFF2E8C);
            s.setKnobColor(0xFFFFFFFF);
            s.setListener((value, finished) -> slide.on(k, value));
            box.addView(s, new LinearLayout.LayoutParams(-1, -2));
            sliders[i] = s;
        }
        return box;
    }

    /** Puts a card over the visuals in the menu's place and returns it. */
    private View openCard(View box) {
        ScrollView card = new ScrollView(this);   // scrolls on a short landscape screen
        card.setBackground(glass(0xE60B0B14));
        card.setVerticalScrollBarEnabled(false);
        card.setClickable(true);   // touches on the card don't fall through to the visuals
        card.addView(box);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        lp.setMargins(px(12), 0, px(12), px(100));
        root.addView(card, lp);
        card.setAlpha(0f);
        card.setTranslationY(40 * dp);
        card.animate().alpha(1f).translationY(0).setDuration(250).start();
        if (panel.getVisibility() == View.VISIBLE) showPanel(false);   // the card takes its place
        menuButton.setText("✕");
        pokeMenu();
        return card;
    }

    /** backToMenu: Back returns to the menu the card came from; Done and ✕ close both. */
    private void closeCard(View card, boolean backToMenu) {
        card.animate().alpha(0f).translationY(40 * dp).setDuration(200).withEndAction(() -> root.removeView(card)).start();
        if (backToMenu) {
            showPanel(true);
        } else {
            menuButton.setText("☰");
            pokeMenu();
        }
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
        LinearLayout box = sliderCard("How hard the scenes react", EQ_NAMES, eqSliders, eqLabels,
                this::editEqualizer, () -> closeEqualizer(false));
        equalizerCard = openCard(box);
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
        try {   // the value tells which choices stick, not just which were touched
            cloud.track("setting_changed", new JSONObject().put("key", key).put("to", v == null ? JSONObject.NULL : v));
        } catch (JSONException ignored) {
        }
        p.edit().putLong(SETTINGS_AT, System.currentTimeMillis()).apply();
        ui.removeCallbacks(syncSettings);
        ui.postDelayed(syncSettings, 1500);
    };

    private void startCloud(Intent intent) {
        cloud = Cloud.get(this);
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
            if (changed && looksCard != null) {   // redraw the list with what came in
                closeLooks(false);
                showLooks();
            }
        }), 4500);
    }

    private void endSession() {
        if (sessionStartedAt == 0) return;
        try {
            cloud.track("session_end", new JSONObject().put("seconds", (System.currentTimeMillis() - sessionStartedAt) / 1000)
                    .put("end_scene", Scene.ALL[Math.max(0, Math.min(Scene.ALL.length - 1, prefs.getInt("scene", 0)))].name)
                    .put("autopilot", prefs.getBoolean("autopilot", true)).put("mode", "normal"));
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
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(18), px(4), px(18), px(16));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(17, Color.WHITE, true);
        title.setShadowLayer(0, 0, 0, 0);
        title.setText("Your TAV");
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView done = text(15, Color.WHITE, true);
        done.setShadowLayer(0, 0, 0, 0);
        done.setText("Done");
        done.setPadding(px(22), px(10), px(22), px(10));
        styleChip(done, true);
        done.setOnClickListener(v -> closeAccount(false));
        head.addView(done);
        box.addView(head);

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

        ScrollView card = new ScrollView(this);
        card.setBackground(glass(0xE60B0B14));
        card.setVerticalScrollBarEnabled(false);
        card.setClickable(true);
        card.addView(box);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        lp.setMargins(px(12), 0, px(12), px(100));
        root.addView(card, lp);
        card.setAlpha(0f);
        card.setTranslationY(40 * dp);
        card.animate().alpha(1f).translationY(0).setDuration(250).start();
        if (panel.getVisibility() == View.VISIBLE) showPanel(false);
        accountCard = card;
        menuButton.setText("✕");
        refreshAccount();
        pokeMenu();
    }

    private TextView actionChip(String label, boolean primary, View.OnClickListener on) {
        TextView c = text(14, Color.WHITE, false);
        c.setShadowLayer(0, 0, 0, 0);
        c.setText(label);
        c.setPadding(px(14), px(8), px(14), px(8));
        styleChip(c, primary);
        c.setOnClickListener(v -> {
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
        card.animate().alpha(0f).translationY(40 * dp).setDuration(200).withEndAction(() -> root.removeView(card)).start();
        if (backToMenu) {
            showPanel(true);
        } else {
            menuButton.setText("☰");
            pokeMenu();
        }
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
        select(sceneChips, clampScene(prefs.getInt("scene", 0)));
        int symSel = 0;
        for (int i = 0; i < SYMMETRY.length; i++) if (SYMMETRY[i] == prefs.getInt("sym", 1)) symSel = i;
        select(mirrorChips, symSel);
        select(paletteChips, prefs.getInt("palette", Palettes.ALBUM));
        select(cmChips, colourMode);
        select(brightChips, prefs.getInt("brightness", TavRenderer.DEFAULT_BRIGHTNESS));
        select(motionChips, prefs.getInt("motion", 0));
        select(eqChips, eqMode);
        select(autoChips, prefs.getBoolean("autopilot", true) ? 0 : 1);
        select(qualityChips, prefs.getInt("quality", TavRenderer.DEFAULT_QUALITY));
    }

    private void select(List<TextView> chips, int sel) {
        for (int i = 0; i < chips.size(); i++) styleChip(chips.get(i), i == sel);
    }

    private void refreshLooksChip() {
        if (looksChip != null) looksChip.setText(looks.isEmpty() ? "My looks" : "My looks (" + looks.all().size() + ")");
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
        select(paletteChips, Palettes.CUSTOM);
        if (colourEditor != null) selectSwatch(editing);
        else flash("S H U F F L E");
        cloud.track("setting_changed", "key", "colours_shuffle");
    }

    /** The head of a Looks card: a title and a Done chip. */
    private LinearLayout cardBox(String title, Runnable done) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(18), px(4), px(18), px(16));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(label(title), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView doneChip = text(15, Color.WHITE, true);
        doneChip.setShadowLayer(0, 0, 0, 0);
        doneChip.setText("Done");
        doneChip.setPadding(px(22), px(10), px(22), px(10));
        styleChip(doneChip, true);
        doneChip.setOnClickListener(v -> done.run());
        head.addView(doneChip);
        box.addView(head);
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
        LinearLayout box = cardBox("Save this look", () -> closeLooks(false));
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
        looksCard = openCard(box);
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
        LinearLayout box = cardBox("My looks", () -> closeLooks(false));
        if (looks.isEmpty()) {
            TextView t = text(14, 0xCCFFFFFF, false);
            t.setShadowLayer(0, 0, 0, 0);
            t.setText("Nothing saved yet. Set the scene, colours and mirror the way you like them, then Looks → Save this look.");
            t.setLineSpacing(0, 1.25f);
            t.setPadding(0, px(8), 0, px(6));
            box.addView(t);
        }
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
                closeLooks(false);
                showLooks();
            });
            del.setContentDescription("Delete the look " + e.look.name);
            row.addView(del);
            box.addView(row);
        }
        looksCard = openCard(box);
    }

    /** Looks → Open a code or link: paste a six-letter code or a whole link. */
    private void showOpenCode() {
        if (looksCard != null) return;
        LinearLayout box = cardBox("Open a shared look", () -> closeLooks(false));
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
        looksCard = openCard(box);
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
        closeLooks(false);
        if (panel.getVisibility() == View.VISIBLE) showPanel(false);
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
