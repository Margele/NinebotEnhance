package dev.ichinomiya.ninebotenhance.core;

/**
 * User overrides for the original cast encoder: a forced bitrate and/or frame rate (0 = keep the vehicle configuration), whether the
 * phone preview paints the live stream statistics, and a forced size for the module's own composed frame (0 × 0 = the size read from the
 * vehicle's cast configuration). The frame override never changes what Ninebot encodes for the vehicle; the composed frame is fitted into
 * that bitmap as before. Values are clamped into the supported ranges on construction.
 */
public record EncoderOverride(int bitrateKbps,int fps,boolean previewStats,int frameWidth,int frameHeight) {
    public static final int MIN_BITRATE_KBPS=100,MAX_BITRATE_KBPS=3000,BITRATE_STEP_KBPS=50;
    public static final int MIN_FPS=5,MAX_FPS=30;
    public static final int DEFAULT_BITRATE_KBPS=1500,DEFAULT_FPS=20;
    /** Frame presets: the portrait half-screen dashboard, the five-inch dashboard (M5P) and the seven-inch dashboard. */
    public static final int HALF_SCREEN_WIDTH=240,HALF_SCREEN_HEIGHT=320,FIVE_INCH_WIDTH=848,FIVE_INCH_HEIGHT=480;
    public static final int SEVEN_INCH_WIDTH=DashboardProfile.SEVEN_INCH_WIDTH,SEVEN_INCH_HEIGHT=DashboardProfile.SEVEN_INCH_HEIGHT;
    public static final EncoderOverride NONE=new EncoderOverride(0,0,false);
    public EncoderOverride(int bitrateKbps,int fps,boolean previewStats){this(bitrateKbps,fps,previewStats,0,0);}
    public EncoderOverride {
        bitrateKbps=bitrateKbps<=0?0:Math.max(MIN_BITRATE_KBPS,Math.min(MAX_BITRATE_KBPS,bitrateKbps));
        fps=fps<=0?0:Math.max(MIN_FPS,Math.min(MAX_FPS,fps));
        if(frameWidth<=0||frameHeight<=0){frameWidth=0;frameHeight=0;}
        else{frameWidth=side(frameWidth);frameHeight=side(frameHeight);}
    }
    private static int side(int value){return Math.max(DashboardLayout.MIN_SIDE,Math.min(DashboardLayout.MAX_SIDE,value))&~1;}
    public boolean overridesBitrate(){return bitrateKbps>0;}
    public boolean overridesFps(){return fps>0;}
    public boolean overridesFrame(){return frameWidth>0&&frameHeight>0;}
    public boolean active(){return overridesBitrate()||overridesFps();}
    public boolean halfScreen(){return frameWidth==HALF_SCREEN_WIDTH&&frameHeight==HALF_SCREEN_HEIGHT;}
    public boolean fiveInch(){return frameWidth==FIVE_INCH_WIDTH&&frameHeight==FIVE_INCH_HEIGHT;}
    public boolean sevenInch(){return frameWidth==SEVEN_INCH_WIDTH&&frameHeight==SEVEN_INCH_HEIGHT;}
    /** Bits per second for the encoder, 0 when not overriding. */
    public int bitrateBps(){return bitrateKbps*1000;}
    /** Milliseconds between encoder loop iterations, 0 when not overriding. */
    public int intervalMs(){return fps==0?0:Math.max(1,1000/fps);}
    public EncoderOverride withBitrate(int kbps){return new EncoderOverride(kbps,fps,previewStats,frameWidth,frameHeight);}
    public EncoderOverride withFps(int value){return new EncoderOverride(bitrateKbps,value,previewStats,frameWidth,frameHeight);}
    public EncoderOverride withPreviewStats(boolean value){return new EncoderOverride(bitrateKbps,fps,value,frameWidth,frameHeight);}
    public EncoderOverride withFrame(int width,int height){return new EncoderOverride(bitrateKbps,fps,previewStats,width,height);}
    public static String describeBitrate(int kbps){return kbps>=1000?String.format(java.util.Locale.ROOT,"%.2f Mbps",kbps/1000.0):kbps+" kbps";}
    public String describe(){
        return (overridesBitrate()?"bitrate="+bitrateKbps+"kbps":"bitrate=原配置")+" "+(overridesFps()?"fps="+fps:"fps=原配置")+" previewStats="+previewStats
                +" frame="+(overridesFrame()?frameWidth+"x"+frameHeight:"配置");
    }
}
