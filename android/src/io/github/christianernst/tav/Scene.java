package io.github.christianernst.tav;

/** A visual scene: a fullscreen fragment shader plus an optional particle pass. Mirrors SCENES in web/engine.js. */
final class Scene {
    final String id, name, frag, pointsVert;
    final int pointCount;
    /** Particle instances; 0 means "one per symmetry copy". */
    final int instances;
    final boolean beatRipples;

    private Scene(String id, String name, String frag, String pointsVert, int pointCount, int instances, boolean beatRipples) {
        this.id = id;
        this.name = name;
        this.frag = frag;
        this.pointsVert = pointsVert;
        this.pointCount = pointCount;
        this.instances = instances;
        this.beatRipples = beatRipples;
    }

    static final Scene[] ALL = {
            new Scene("halo", "Halo", "halo.frag", null, 0, 1, true),
            new Scene("tunnel", "Wormhole", "tunnel.frag", null, 0, 1, false),
            new Scene("pulsar", "Pulsar", "pulsar.frag", null, 0, 1, false),
            new Scene("kaleido", "Mandala", "kaleido.frag", null, 0, 1, false),
            new Scene("orb", "Ferro", "orb.frag", null, 0, 1, false),
            new Scene("nebula", "Nebula", "nebula.frag", "nebula.vert", 48000, 1, false),
            new Scene("spiro", "Spiro", "scope.frag", "scope.vert", 4096, 0, false),
    };
}
