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
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
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
    private View intro;
    private final List<TextView> sceneChips = new ArrayList<>(), sourceChips = new ArrayList<>();
    private String lastTitle = "";
    private float[][] albumPalette;
    private float dp;

    private final Runnable hideMenu = () -> {
        if (panel.getVisibility() != View.VISIBLE) menuButton.animate().alpha(0f).setDuration(600).start();
    };
    private final Runnable hideTrack = () -> trackBox.animate().alpha(0f).setDuration(1200).start();
    private final Runnable hideScene = () -> sceneLabel.animate().alpha(0f).setDuration(900).start();
    private final Runnable showHint = () -> hint.animate().alpha(1f).setDuration(800).start();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        dp = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("tav", MODE_PRIVATE);
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
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));
        buildOverlay();
        setContentView(root);
        applyPrefs();
        if (!hasAudioPermission()) showIntro();
    }

    @Override
    protected void onResume() {
        super.onResume();
        immersive();
        view.onResume();
        if (hasAudioPermission()) startAudio();
        media.start();
        pokeMenu();
    }

    @Override
    protected void onPause() {
        view.onPause();
        capture.stop();
        media.stop();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    @Override
    public void onBackPressed() {
        if (panel.getVisibility() == View.VISIBLE) togglePanel();
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
        if (hasAudioPermission()) {
            if (intro != null) root.removeView(intro);
            intro = null;
            startAudio();
        } else {
            hint.setText("Without audio access TAV can't hear the music.\nTap and drag still make waves.");
            hint.setAlpha(1f);
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
            if (silent) ui.postDelayed(showHint, 2500);
            else hint.animate().alpha(0f).setDuration(500).start();
        });
    }

    @Override
    public void onRenderError(String message) {
        ui.post(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onTrack(String t, String a, Bitmap art) {
        boolean newTrack = !t.equals(lastTitle);
        lastTitle = t;
        if (art != null) {
            float[][] p = Palettes.fromBitmap(art);
            if (p != null) albumPalette = p;
        }
        if (prefs.getInt("palette", Palettes.ALBUM) == Palettes.ALBUM) pushPalette();
        if (!newTrack) return;
        view.queueEvent(renderer::onTrackChanged);
        title.setText(t);
        artist.setText(a);
        trackBox.animate().cancel();
        trackBox.setAlpha(0f);
        trackBox.setTranslationY(16 * dp);
        trackBox.animate().alpha(1f).translationY(0).setDuration(900).start();
        ui.removeCallbacks(hideTrack);
        ui.postDelayed(hideTrack, 7000);
    }

    // ------------------------------------------------------------------ settings

    private void applyPrefs() {
        final int scene = prefs.getInt("scene", 0), sym = prefs.getInt("sym", 1), q = prefs.getInt("quality", 1);
        final boolean auto = prefs.getBoolean("autopilot", true);
        view.queueEvent(() -> {
            renderer.setScene(scene);
            renderer.setSymmetry(sym);
            renderer.setQuality(q);
            renderer.setAutopilot(auto);
        });
        pushPalette();
    }

    private void pushPalette() {
        int idx = prefs.getInt("palette", Palettes.ALBUM);
        final float[][] p = (idx == Palettes.ALBUM && albumPalette != null) ? albumPalette : Palettes.preset(idx);
        view.queueEvent(() -> renderer.setPalette(p));
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
        hint.setText("Play something on Spotify ♪\nTap to make ripples · drag to draw with sound");
        hint.setLineSpacing(0, 1.3f);
        hint.setAlpha(0f);
        lp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        lp.topMargin = px(220);
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
        lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.START);
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
        GradientDrawable glass = new GradientDrawable();
        glass.setColor(0xD90B0B14);
        glass.setCornerRadius(px(26));
        glass.setStroke(px(1), 0x33FFFFFF);
        panel.setBackground(glass);
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
            prefs.edit().putInt("palette", i).apply();
            if (i == Palettes.ALBUM && !MediaWatcher.hasNotificationAccess(this)) askNotificationAccess();
            pushPalette();
        });

        addRow("Listen to", new String[]{"Phone audio (Spotify)", "Microphone"}, prefs.getInt("source", 0), sourceChips, i -> {
            prefs.edit().putInt("source", i).apply();
            if (hasAudioPermission()) startAudio();
        });

        addRow("Autopilot (switch scenes with the music)", new String[]{"On", "Off"}, prefs.getBoolean("autopilot", true) ? 0 : 1,
                new ArrayList<>(), i -> {
                    final boolean on = i == 0;
                    prefs.edit().putBoolean("autopilot", on).apply();
                    view.queueEvent(() -> renderer.setAutopilot(on));
                });

        addRow("Quality", new String[]{"Battery", "Balanced", "Max"}, prefs.getInt("quality", 1), new ArrayList<>(), i -> {
            prefs.edit().putInt("quality", i).apply();
            view.queueEvent(() -> renderer.setQuality(i));
        });

        TextView help = text(12.5f, 0x99FFFFFF, false);
        help.setText("Tap: ripple   •   Drag: draw with sound   •   Two-finger swipe: next scene   •   Long-press: this menu\n"
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

    private void addRow(String label, String[] options, int selected, List<TextView> keep, Pick pick) {
        TextView l = text(11.5f, 0x99FFFFFF, false);
        l.setText(label.toUpperCase(Locale.ROOT));
        l.setLetterSpacing(0.12f);
        l.setPadding(0, px(14), 0, px(6));
        panelContent.addView(l);
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
        boolean show = panel.getVisibility() != View.VISIBLE;
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

    private void askNotificationAccess() {
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

    /** First-run card explaining why the audio permission is needed. */
    private void showIntro() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(px(26), px(26), px(26), px(22));
        GradientDrawable glass = new GradientDrawable();
        glass.setColor(0xE60B0B14);
        glass.setCornerRadius(px(26));
        glass.setStroke(px(1), 0x33FFFFFF);
        card.setBackground(glass);

        TextView h = text(30, Color.WHITE, true);
        h.setText("T A V");
        h.setLetterSpacing(0.3f);
        card.addView(h);
        TextView body = text(15, 0xD9FFFFFF, false);
        body.setText("TAV turns whatever your phone is playing — Spotify, YouTube, anything — into light.\n\n"
                + "To hear it, Android needs the “record audio” permission. TAV only analyses the sound "
                + "frame by frame to drive the visuals; nothing is recorded, stored or sent anywhere.");
        body.setLineSpacing(0, 1.25f);
        body.setGravity(Gravity.CENTER);
        body.setPadding(0, px(14), 0, px(20));
        card.addView(body);
        TextView go = text(16, Color.WHITE, true);
        go.setText("Let it listen");
        go.setShadowLayer(0, 0, 0, 0);
        go.setPadding(px(28), px(12), px(28), px(12));
        styleChip(go, true);
        go.setOnClickListener(v -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO));
        card.addView(go);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        lp.setMargins(px(24), 0, px(24), 0);
        intro = card;
        root.addView(card, lp);
    }
}
