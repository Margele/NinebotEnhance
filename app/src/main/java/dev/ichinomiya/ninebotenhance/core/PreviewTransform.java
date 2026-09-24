package dev.ichinomiya.ninebotenhance.core;

/** Phone-only clockwise quarter-turn followed by FIT; independent of the virtual display rotation. */
public final class PreviewTransform {
    public final TouchMapping fit;
    public final float[] input;
    private final int topInset, contentHeight, contentWidth;
    public final float[] frameInput;
    public PreviewTransform(int width, int height, int viewWidth, int viewHeight, boolean rotated) {
        this(width, height, viewWidth, viewHeight, rotated, 0);
    }
    public PreviewTransform(int width, int height, int viewWidth, int viewHeight, boolean rotated, int topInset) {
        this(width,checkedHeight(height,topInset),width,height,viewWidth,viewHeight,rotated);
    }
    private static int checkedHeight(int height,int inset){if(inset<0||inset>=height||(long)height+inset>Integer.MAX_VALUE)throw new IllegalArgumentException("Invalid top inset");return height+inset;}
    public PreviewTransform(DisplaySettings settings,int viewWidth,int viewHeight,boolean rotated){
        this(settings.width,settings.height,settings.virtualWidth,settings.virtualHeight,settings.contentTop(),viewWidth,viewHeight,rotated);
    }
    public PreviewTransform(int width,int frameHeight,int appWidth,int appHeight,int viewWidth,int viewHeight,boolean rotated){
        this(width,frameHeight,appWidth,appHeight,frameHeight-appHeight,viewWidth,viewHeight,rotated);
    }
    /** {@code appTop} is the app's first row in the frame; rows above and below the app are background and never start a gesture. */
    public PreviewTransform(int width,int frameHeight,int appWidth,int appHeight,int appTop,int viewWidth,int viewHeight,boolean rotated){
        if(appWidth<1||appHeight<1||appWidth>width||appHeight>frameHeight||appTop<0||appTop+appHeight>frameHeight)throw new IllegalArgumentException("Invalid content rectangle");
        topInset=appTop;contentHeight=appHeight;contentWidth=appWidth;
        fit = new TouchMapping(rotated ? frameHeight : width, rotated ? width : frameHeight, viewWidth, viewHeight);
        float s = fit.scale;
        input = rotated ? new float[]{0, s, -fit.top * s, -s, 0, frameHeight + fit.left * s, 0, 0, 1}
                : new float[]{s, 0, -fit.left * s, 0, s, -fit.top * s, 0, 0, 1};
        frameInput=input.clone();input[5] -= topInset;
    }
    /** Reject phone FIT margins and the composited top band before creating an app gesture. */
    public boolean contains(float x, float y) {
        if (!fit.contains(x, y)) return false;
        float sourceX=input[0]*x+input[1]*y+input[2];
        float sourceY = input[3] * x + input[4] * y + input[5];
        return sourceX>=0&&sourceX<contentWidth&&sourceY>=0&&sourceY<contentHeight;
    }
}
