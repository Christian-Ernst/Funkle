package io.github.christianernst.tav;

/** A visual scene: a fullscreen fragment shader plus an optional particle pass. Mirrors SCENES in web/engine.js. */
final class Scene {
    final String id, name, frag, pointsVert;
    final int pointCount;
    /** Particle instances; 0 means "one per symmetry copy". */
    final int instances;
    final boolean beatRipples;
    /** The scene's sliders (menu → Tune this scene), 0..1 each with 0.5 the look it was made with; uTune in the shaders. */
    final String[] tune;
    static final int TUNE = 3;
    /** The Canvas scene: a black sheet the user paints on (see TavRenderer's painting list and uBrush in stroke.vert). */
    static final String CANVAS = "canvas";

    private Scene(String id, String name, String frag, String pointsVert, int pointCount, int instances, boolean beatRipples, String... tune) {
        this.id = id;
        this.name = name;
        this.frag = frag;
        this.pointsVert = pointsVert;
        this.pointCount = pointCount;
        this.instances = instances;
        this.beatRipples = beatRipples;
        this.tune = tune;
    }

    /** True for the Canvas scene, where a drag paints with the brush instead of drawing a singing line. */
    boolean paint() {
        return CANVAS.equals(id);
    }

    /** The preference and Look key of slider k: its name in lower case. */
    String tuneId(int k) {
        return tune[k].toLowerCase(java.util.Locale.ROOT);
    }

    static int indexOf(String id) {
        for (int i = 0; i < ALL.length; i++) if (ALL[i].id.equals(id)) return i;
        return -1;
    }

    static final Scene[] ALL = {
            new Scene("halo", "Halo", "halo.frag", null, 0, 1, true, "Rays", "Trails", "Rings"),
            new Scene("tunnel", "Wormhole", "tunnel.frag", null, 0, 1, false, "Speed", "Panels", "Twist"),
            new Scene("pulsar", "Pulsar", "pulsar.frag", null, 0, 1, false, "Ridges", "Height", "Stars"),
            new Scene("kaleido", "Mandala", "kaleido.frag", null, 0, 1, false, "Jewels", "Echo", "Spin"),
            new Scene("orb", "Ferro", "orb.frag", null, 0, 1, false, "Spikes", "Size", "Trails"),
            new Scene("nebula", "Nebula", "nebula.frag", "nebula.vert", 48000, 1, false, "Spin", "Glow", "Spread"),
            new Scene("spiro", "Spiro", "scope.frag", "scope.vert", 4096, 0, false, "Pen", "Thickness", "Trails"),
            new Scene("fluid", "Ink", "fluid.frag", null, 0, 1, false, "Flow", "Ink", "Swirl"),
            new Scene("landscape", "Horizon", "landscape.frag", null, 0, 1, false, "Height", "Haze", "Glow"),
            new Scene(CANVAS, "Canvas", "canvas.frag", null, 0, 1, false, "Echo", "Pulse", "Haze"),
    };
}
