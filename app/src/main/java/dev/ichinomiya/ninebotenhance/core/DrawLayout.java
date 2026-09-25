package dev.ichinomiya.ninebotenhance.core;

/**
 * Geometry of the drawn picture inside one frame: the gauge arc in the upper part of the area the dashboard leaves free, the
 * information fields sharing the strip under it. Everything scales with the frame, so any dashboard size gets the same picture.
 */
public record DrawLayout(float left, float top, float right, float bottom, float centerX, float centerY, float radius, float stroke,
                         float valueSize, float unitSize, float scaleSize, float fieldTop, float fieldWidth, float fieldValueSize, float fieldLabelSize) {
    /** The arc opens downward: it starts at 135 degrees and sweeps 270 clockwise. */
    public static final float START_ANGLE = 135, SWEEP = 270;
    public static DrawLayout of(int width, int height, int topInset, int bottomInset, int fields) {
        float top = Math.max(0, topInset), bottom = Math.max(top + 1, height - Math.max(0, bottomInset)), area = bottom - top;
        float gauge = fields > 0 ? area * 0.66f : area;
        float radius = Math.min(width * 0.5f, gauge * 0.5f) * 0.8f, stroke = radius * 0.13f;
        float centerX = width / 2f, centerY = top + gauge * 0.55f;
        float strip = bottom - (top + gauge);
        return new DrawLayout(0, top, width, bottom, centerX, centerY, radius, stroke, radius * 0.6f, radius * 0.19f, radius * 0.16f,
                top + gauge, fields > 0 ? width / (float) fields : width, strip * 0.4f, strip * 0.2f);
    }
    /** Degrees of arc for a value on a gauge of the given full scale; clamped to the arc. */
    public static float sweepFor(float value, float max) {
        if (max <= 0 || Float.isNaN(value)) return 0;
        return Math.max(0, Math.min(SWEEP, SWEEP * value / max));
    }
    /** Centre x of the i-th field. */
    public float fieldCenter(int index) { return left + fieldWidth * (index + 0.5f); }
}
