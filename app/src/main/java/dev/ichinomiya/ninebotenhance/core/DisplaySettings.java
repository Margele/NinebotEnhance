package dev.ichinomiya.ninebotenhance.core;

/**
 * Output canvas and independent app buffer, placed at the left above the frame's bottom strip ({@code bottomInset}, zero unless the
 * dashboard profile keeps rows free for the dashboard's own overlay). With {@code keepPhoneDpi} the app area still is
 * virtualWidth x virtualHeight and {@code dpi} still defines the dp layout, but the display shows the phone's density with a
 * proportionally larger logical size (the render plan), scaled back into the app area either by the system (forced logical size)
 * or by the capture path ({@code compatScale}): an app moving between the phone and the virtual display then never sees a density change.
 */
public final class DisplaySettings {
    public final int width,height,virtualWidth,virtualHeight,dpi,backgroundColor,lightBackgroundColor;
    /** Rows kept free under the app for the dashboard's own bottom overlay; the app's first row is {@code height - bottomInset - virtualHeight}. */
    public final int bottomInset;
    public final boolean keepPhoneDpi;
    /**
     * Keep-DPI has two implementations. Off (default): the display is created at the app buffer size and the daemon forces the render
     * plan's logical size and density through WindowManager, the system scaling the picture back for free. On: the display and the
     * capture buffer are created at the render plan size and the capture path scales the picture back itself, needing no privileged
     * call. The module switches it on when the forcing was refused.
     */
    public final boolean compatScale;
    /** With the override off the virtual display takes the shape defaults of the frame instead of the saved size. */
    public final boolean virtualOverride;
    /** Largest logical side WindowManager is asked for; beyond it the dp layout shrinks rather than the density drifting. */
    public static final int MAX_RENDER_SIDE=4096;
    /** Logical size and density the display is created at; the capture path scales it into virtualWidth x virtualHeight. */
    public record RenderPlan(int width,int height,int dpi){}
    public static final int DEFAULT_WIDTH=848,DEFAULT_HEIGHT=480,DEFAULT_VIRTUAL_WIDTH=640,DEFAULT_VIRTUAL_HEIGHT=440,DEFAULT_DPI=160;
    /** Portrait (half-screen) frames default to a 240 x 300 virtual display at the same density. */
    public static final int DEFAULT_HALF_VIRTUAL_WIDTH=240,DEFAULT_HALF_VIRTUAL_HEIGHT=300;
    /** The app's default width, height and density for a frame, from its dashboard profile. */
    public static int[] defaultVirtual(int frameWidth,int frameHeight){DashboardProfile p=DashboardProfile.of(frameWidth,frameHeight);return new int[]{p.appWidth(),p.appHeight(),p.appDpi()};}
    public static final int DEFAULT_BACKGROUND_COLOR=0xff242424,DEFAULT_LIGHT_BACKGROUND_COLOR=0xffe6eaee,LAYOUT_VERSION=2;
    public static final boolean DEFAULT_KEEP_PHONE_DPI=true;
    public DisplaySettings(int width,int height,int dpi){this(width,height,width,height,dpi,DEFAULT_BACKGROUND_COLOR);}
    public DisplaySettings(int width,int height,int virtualWidth,int virtualHeight,int dpi,int backgroundColor){this(width,height,virtualWidth,virtualHeight,dpi,backgroundColor,DEFAULT_KEEP_PHONE_DPI);}
    public DisplaySettings(int width,int height,int virtualWidth,int virtualHeight,int dpi,int backgroundColor,boolean keepPhoneDpi){this(width,height,virtualWidth,virtualHeight,dpi,backgroundColor,keepPhoneDpi,DEFAULT_LIGHT_BACKGROUND_COLOR);}
    /** {@code backgroundColor} fills the frame around the app in the dark dashboard theme, {@code lightBackgroundColor} in the light one. */
    public DisplaySettings(int width,int height,int virtualWidth,int virtualHeight,int dpi,int backgroundColor,boolean keepPhoneDpi,int lightBackgroundColor){this(width,height,virtualWidth,virtualHeight,dpi,backgroundColor,keepPhoneDpi,lightBackgroundColor,false);}
    public DisplaySettings(int width,int height,int virtualWidth,int virtualHeight,int dpi,int backgroundColor,boolean keepPhoneDpi,int lightBackgroundColor,boolean virtualOverride){this(width,height,virtualWidth,virtualHeight,dpi,backgroundColor,keepPhoneDpi,lightBackgroundColor,virtualOverride,0);}
    public DisplaySettings(int width,int height,int virtualWidth,int virtualHeight,int dpi,int backgroundColor,boolean keepPhoneDpi,int lightBackgroundColor,boolean virtualOverride,int bottomInset){this(width,height,virtualWidth,virtualHeight,dpi,backgroundColor,keepPhoneDpi,lightBackgroundColor,virtualOverride,bottomInset,false);}
    public DisplaySettings(int width,int height,int virtualWidth,int virtualHeight,int dpi,int backgroundColor,boolean keepPhoneDpi,int lightBackgroundColor,boolean virtualOverride,int bottomInset,boolean compatScale){
        if(width<DashboardLayout.MIN_SIDE||height<DashboardLayout.MIN_SIDE||width>DashboardLayout.MAX_SIDE||height>DashboardLayout.MAX_SIDE||(width&1)!=0||(height&1)!=0||(long)width*height>2073600)
            throw new IllegalArgumentException("整帧宽高需为 160–1920 的偶数，总像素不超过 1920×1080。");
        if(virtualWidth<DashboardLayout.MIN_SIDE||virtualHeight<DashboardLayout.MIN_SIDE||virtualWidth>width||virtualHeight>height||(virtualWidth&1)!=0||(virtualHeight&1)!=0)
            throw new IllegalArgumentException("虚拟屏宽高需为不小于 160 的偶数，且不能超过整帧宽高。");
        if(dpi<100||dpi>480||Math.min(virtualWidth,virtualHeight)*160L/dpi<160)
            throw new IllegalArgumentException("DPI 为 100–480，虚拟屏最短边至少 160 dp。");
        if(bottomInset<0||(long)virtualHeight+bottomInset>height)
            throw new IllegalArgumentException("虚拟屏高度加底部预留不能超过整帧高度。");
        BandColor.requireOpaque(backgroundColor);BandColor.requireOpaque(lightBackgroundColor);
        this.bottomInset=bottomInset;this.width=width;this.height=height;this.virtualWidth=virtualWidth;this.virtualHeight=virtualHeight;this.dpi=dpi;this.backgroundColor=backgroundColor;this.keepPhoneDpi=keepPhoneDpi;this.lightBackgroundColor=lightBackgroundColor;this.virtualOverride=virtualOverride;this.compatScale=compatScale;
    }
    /** Same dp layout at the phone's density; null when the option is off, the density is unknown or already equal. */
    public RenderPlan renderPlan(int phoneDpi){
        if(!keepPhoneDpi||phoneDpi<=0||phoneDpi==dpi)return null;
        double scale=phoneDpi/(double)dpi,w=virtualWidth*scale,h=virtualHeight*scale;
        double fit=Math.min(1,Math.min(MAX_RENDER_SIDE/w,MAX_RENDER_SIDE/h));
        return new RenderPlan(even(w*fit),even(h*fit),phoneDpi);
    }
    private static int even(double value){return (int)Math.round(value/2)*2;}
    public static DisplaySettings defaults(){return new DisplaySettings(DEFAULT_WIDTH,DEFAULT_HEIGHT,DEFAULT_VIRTUAL_WIDTH,DEFAULT_VIRTUAL_HEIGHT,DEFAULT_DPI,DEFAULT_BACKGROUND_COLOR,DEFAULT_KEEP_PHONE_DPI);}
    @FunctionalInterface public interface IntSetting{int get(String key,int fallback);}
    public static DisplaySettings read(IntSetting values){
        if(values.get("layout_version",0)<LAYOUT_VERSION){
            // Old sizes described app content plus top_inset. Preserve that output extent.
            int width=values.get("width",DEFAULT_WIDTH);
            long oldHeight=(long)values.get("height",440)+values.get("top_inset",40);
            if(oldHeight<320||oldHeight>1920)throw new IllegalArgumentException("旧版画面尺寸无效，请重新设置");
            int height=((int)oldHeight+1)&~1;
            return new DisplaySettings(width,height,Math.max(240,(int)(width*640L/848)&~1),Math.max(240,(int)(height*440L/480)&~1),
                    values.get("dpi",DEFAULT_DPI),values.get("top_color",DEFAULT_BACKGROUND_COLOR));
        }
        return new DisplaySettings(values.get("width",DEFAULT_WIDTH),values.get("height",DEFAULT_HEIGHT),
                values.get("virtual_width",DEFAULT_VIRTUAL_WIDTH),values.get("virtual_height",DEFAULT_VIRTUAL_HEIGHT),
                values.get("dpi",DEFAULT_DPI),values.get("background_color",DEFAULT_BACKGROUND_COLOR),values.get("keep_phone_dpi",DEFAULT_KEEP_PHONE_DPI?1:0)!=0,values.get("light_background_color",DEFAULT_LIGHT_BACKGROUND_COLOR),values.get("virtual_override",0)!=0,values.get("bottom_inset",0),values.get("compat_scale",0)!=0);
    }
    /**
     * Same settings inside the frame the cast configuration prescribes: the frame's dashboard profile supplies the defaults and the
     * strips above and below the app, the virtual display shrinks to fit between them, an unusable frame is ignored.
     */
    public DisplaySettings withFrame(int frameWidth,int frameHeight){
        int w=frameWidth&~1,h=frameHeight&~1;DashboardProfile profile=DashboardProfile.of(w,h);
        int bottom=profile.bottomInset(),maxHeight=h-profile.topInset()-bottom;
        int vw=Math.min(virtualOverride?virtualWidth:profile.appWidth(),w)&~1,vh=Math.min(virtualOverride?virtualHeight:profile.appHeight(),maxHeight)&~1,d=virtualOverride?dpi:profile.appDpi();
        if(w==width&&h==height&&vw==virtualWidth&&vh==virtualHeight&&d==dpi&&bottom==bottomInset)return this;
        try { return new DisplaySettings(w,h,vw,vh,d,backgroundColor,keepPhoneDpi,lightBackgroundColor,virtualOverride,bottom,compatScale); }
        catch(IllegalArgumentException e) { return this; }
    }
    public DisplaySettings withCompatScale(boolean value){return value==compatScale?this:new DisplaySettings(width,height,virtualWidth,virtualHeight,dpi,backgroundColor,keepPhoneDpi,lightBackgroundColor,virtualOverride,bottomInset,value);}
    /** The app's first row in the frame, and the row just below its last one. */
    public int contentTop(){return height-bottomInset-virtualHeight;}
    public int contentBottom(){return height-bottomInset;}
    public int background(boolean dark){return dark?backgroundColor:lightBackgroundColor;}
    public String label(){return "整帧 "+width+" × "+height+"，虚拟屏 "+virtualWidth+" × "+virtualHeight+"，"+dpi+" DPI"+(keepPhoneDpi?(compatScale?"（保持手机 DPI，兼容缩放）":"（保持手机 DPI）"):"")+(bottomInset>0?"，底部预留 "+bottomInset:"")+"，背景 "+BandColor.hex(backgroundColor)+" / "+BandColor.hex(lightBackgroundColor);}
    public static String shellQuote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
}
