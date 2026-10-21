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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Full-screen visualizer with a glassy pop-up control panel. */
public final class MainActivity extends Activity implements TavRenderer.Host, AudioCapture.Listener, MediaWatcher.Listener {
    private static final int REQ_AUDIO = 7;
    private static final int[] SYMMETRY = {1, 2, 3, 4, 6, 8, 12};
    private static final String HINT_IDLE = "Play something on Spotify \u266A\nTap to make ripples \u00B7 drag to draw with sound";
    private static final String HINT_BLOCKED = "Music is playing, but TAV hears silence.\nTurn the volume up, or open \u2630 \u2192 Listen to \u2192 Microphone.";
    private static final String HINT_NO_PERMISSION = "Without audio access TAV can't hear the music.\nTap and drag still make waves.";

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
    private String lastTitle = "", lastArtist = "";
    private boolean playing, titleAlways;
    private TextView nowPlaying;
    private TransportButton playButton;
    private float[][] albumPalette;
    private float dp;
    // colour editor (Colours -> Custom)
    private View colourEditor;
    private final int[] custom = new int[3];
    private final View[] swatches = new View[3];
    private ColourSlider hueSlider, satSlider, valSlider;
    private int editing;

    private final Runnable hideMenu = () -> {
        if (panel.getVisibility() != View.VISIBLE && colourEditor == null) menuButton.animate().alpha(0f).setDuration(600).start();
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
        ui.postDelayed(this.showHint, 3000);   // keep re-checking until sound returns (onSilence stops it)
        if (intro != null || notice != null) return;   // a card is explaining things already
        // Music is known to be playing but the output mix reads as silence: the ROM or the
        // player is keeping audio away from the visualizer. Point at the microphone.
        boolean blocked = capture.source() == AudioCapture.SOURCE_SYSTEM && media.isPlaying();
        hint.setText(!hasAudioPermission() ? HINT_NO_PERMISSION : blocked ? HINT_BLOCKED : HINT_IDLE);
        if (hint.getAlpha() < 1f) hint.animate().alpha(1f).setDuration(800).start();
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        dp = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("tav", MODE_PRIVATE);
        titleAlways = prefs.getBoolean("titleAlways", false);
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
    }

    // Rendering and listening follow visibility, not focus: in split-screen on Android 7-9 the
    // window you are not touching is only paused, and TAV next to Spotify must keep going.
    @Override
    protected void onStart() {
        super.onStart();
        view.onResume();
        if (hasAudioPermission()) {
            removeIntro();   // also covers access granted from the system settings
            startAudio();
        }
        media.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        immersive();
        pokeMenu();
        if (askedNotificationAccess) {
            askedNotificationAccess = false;
            if (MediaWatcher.hasNotificationAccess(this)) pushPalette();
            else if (Build.VERSION.SDK_INT >= 33) explainRestrictedSettings();
        }
    }

    @Override
    protected void onStop() {
        view.onPause();
        capture.stop();
        media.stop();
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
        });
    }

    @Override
    public void onSilence(boolean silent) {
        ui.post(() -> {
            ui.removeCallbacks(showHint);
            if (silent) ui.postDelayed(showHint, 2500);   // re-posts itself until sound returns
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
        if (art != null) {
            float[][] p = Palettes.fromBitmap(art);
            if (p != null) albumPalette = p;
        }
        if (prefs.getInt("palette", Palettes.ALBUM) == Palettes.ALBUM) pushPalette();
        if (!newTrack) return;
        view.queueEvent(renderer::onTrackChanged);
        title.setText(t);
        artist.setText(a);
        trackBox.setContentDescription("Now playing: " + t + (a.isEmpty() ? "" : " by " + a)
                + ". Double-tap to play or pause.");
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
        final int scene = prefs.getInt("scene", 0), sym = prefs.getInt("sym", 1), q = prefs.getInt("quality", 1);
        final int lit = prefs.getInt("brightness", TavRenderer.DEFAULT_BRIGHTNESS);
        final boolean auto = prefs.getBoolean("autopilot", true);
        view.queueEvent(() -> {
            renderer.setScene(scene);
            renderer.setSymmetry(sym);
            renderer.setQuality(q);
            renderer.setBrightness(lit);
            renderer.setAutopilot(auto);
        });
        pushPalette();
    }

    private void pushPalette() {
        final float[][] p = paletteFor(prefs.getInt("palette", Palettes.ALBUM));
        view.queueEvent(() -> renderer.setPalette(p));
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
        trackBox.setOrientation(LinearLayout.VERTICAL);
        title = text(24, Color.WHITE, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        artist = text(15, 0xC0FFFFFF, false);
        artist.setLetterSpacing(0.08f);
        trackBox.addView(title);
        trackBox.addView(artist);
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
        nowPlaying = text(15, Color.WHITE, false);
        nowPlaying.setShadowLayer(0, 0, 0, 0);
        nowPlaying.setSingleLine(true);
        nowPlaying.setEllipsize(TextUtils.TruncateAt.END);
        nowPlaying.setText("Nothing yet \u2014 start a song in Spotify");
        panelContent.addView(nowPlaying);
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

        String[] sceneNames = new String[Scene.ALL.length];
        for (int i = 0; i < sceneNames.length; i++) sceneNames[i] = Scene.ALL[i].name;
        addRow("Scene", sceneNames, prefs.getInt("scene", 0), sceneChips, i -> view.queueEvent(() -> renderer.setScene(i)));

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
        addRow("Mirror (mandala drawing)", syms, symSel, new ArrayList<>(), i -> {
            prefs.edit().putInt("sym", SYMMETRY[i]).apply();
            view.queueEvent(() -> renderer.setSymmetry(SYMMETRY[i]));
        });

        addRow("Colours", Palettes.NAMES, prefs.getInt("palette", Palettes.ALBUM), new ArrayList<>(), i -> {
            // the first custom palette starts from the colours on screen, so read them before switching
            if (i == Palettes.CUSTOM && !prefs.contains("custom0")) {
                System.arraycopy(Palettes.toColors(paletteFor(prefs.getInt("palette", Palettes.ALBUM))), 0, custom, 0, 3);
                saveCustom();
            }
            prefs.edit().putInt("palette", i).apply();
            if (i == Palettes.ALBUM && !MediaWatcher.hasNotificationAccess(this)) askNotificationAccess();
            pushPalette();
            if (i == Palettes.CUSTOM) showColourEditor();
        });

        addRow("Brightness", new String[]{"Dim", "Soft", "Normal", "Bright"},
                prefs.getInt("brightness", TavRenderer.DEFAULT_BRIGHTNESS), new ArrayList<>(), i -> {
                    prefs.edit().putInt("brightness", i).apply();
                    view.queueEvent(() -> renderer.setBrightness(i));
                });

        addRow("Listen to", new String[]{"Phone audio (Spotify)", "Microphone"}, prefs.getInt("source", 0), sourceChips, i -> {
            prefs.edit().putInt("source", i).apply();
            if (hasAudioPermission()) startAudio();
            else requestAudio();
        });

        addRow("Autopilot (switch scenes with the music)", new String[]{"On", "Off"}, prefs.getBoolean("autopilot", true) ? 0 : 1,
                new ArrayList<>(), i -> {
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

        addRow("Quality", new String[]{"Battery", "Balanced", "Max"}, prefs.getInt("quality", 1), new ArrayList<>(), i -> {
            prefs.edit().putInt("quality", i).apply();
            view.queueEvent(() -> renderer.setQuality(i));
        });

        TextView help = text(12.5f, 0x99FFFFFF, false);
        help.setText("Tap: ripple   \u2022   Drag: draw with sound   \u2022   Two-finger swipe: next scene   \u2022   Long-press: this menu\n"
                + "Song title on screen: swipe it for the next or previous track, tap it to pause.\n"
                + "Album colours need notification access (only used to read what's playing).");
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
        if (panel.getVisibility() == View.VISIBLE) showPanel(false);   // the editor takes its place
        colourEditor = card;
        menuButton.setText("✕");
        selectSwatch(0);
        pokeMenu();
    }

    private ColourSlider slider(LinearLayout box, String name) {
        TextView l = label(name);
        l.setPadding(0, px(8), 0, 0);
        box.addView(l);
        ColourSlider s = new ColourSlider(this, name);
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
        card.animate().alpha(0f).translationY(40 * dp).setDuration(200).withEndAction(() -> root.removeView(card)).start();
        if (backToMenu) {
            showPanel(true);
        } else {
            menuButton.setText("☰");
            pokeMenu();
        }
    }

    private void askNotificationAccess() {
        askedNotificationAccess = true;   // checked when we come back in onResume
        Toast.makeText(this, "Allow TAV in the list to colour the visuals from album art", Toast.LENGTH_LONG).show();
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

    /** First-run card explaining why the audio permission is needed. */
    private void showIntro() {
        LinearLayout c = card("T A V",
                "TAV turns whatever your phone is playing \u2014 Spotify, YouTube, anything \u2014 into light.\n\n"
                        + "To hear it, Android needs the \u201Crecord audio\u201D permission. TAV only analyses the sound "
                        + "frame by frame to drive the visuals; nothing is recorded, stored or sent anywhere.",
                "Let it listen", this::requestAudio, this::removeIntro);
        TextView[] parts = (TextView[]) c.getTag();
        introBody = parts[0];
        introButton = parts[1];
        intro = c;
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
                        + "then pick Colours \u2192 Album in TAV again.",
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
