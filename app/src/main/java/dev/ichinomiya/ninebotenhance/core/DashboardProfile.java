package dev.ichinomiya.ninebotenhance.core;

import dev.ichinomiya.ninebotenhance.core.SidebarLayout.Box;
import java.util.ArrayList;
import java.util.List;

/**
 * How one dashboard shape is laid out: how the 848 x 480 reference frame the HUD draws in is fitted into the encoder frame (the
 * scale, and where the reference's bottom-right corner lands), the strips kept free above and below the app, the app's default
 * size and density, what the dashboard paints over the frame when no cast configuration has been read, whether the hill-hold
 * dodge has a measured toast to dodge, and where the volume bar sits. Boxes are already in reference coordinates.
 * <p>
 * The five-inch M5P (848 x 480) and every other landscape frame keep the historical fit, anchored at the frame's bottom-right
 * corner. Portrait frames are half-screen dashboards. The seven-inch 1024 x 600 dashboard (a board may report the encoder height
 * aligned to 608; those rows are off screen) was measured from a photo of the calibration grid: status icons in the top 48 rows,
 * the battery band at x 226–760 from y 549, the gear / speed
 * panel at x 760 from y 457, NOS / mode / unit at x 970 from y 355. Its reference is not scaled and its corner sits at (970, 457),
 * so the card column lands at x 770–960 above the panel. The hill-hold toast has not been measured there, so the dodge is off.
 */
public record DashboardProfile(String name,int frameWidth,int frameHeight,float scale,float dx,float dy,boolean halfScreen,
                               int topInset,int bottomInset,int appWidth,int appHeight,int appDpi,List<Box> occlusions,boolean hillHold,Box volume){
    /** The screen is 1024 x 600; a board that aligns the encoder height to 16 reports 608, and the profile accepts both. */
    public static final int SEVEN_INCH_WIDTH=1024,SEVEN_INCH_HEIGHT=600,SEVEN_INCH_ALIGNED_HEIGHT=608;
    public static final int SEVEN_INCH_TOP_INSET=48,SEVEN_INCH_APP_BOTTOM=544,SEVEN_INCH_APP_WIDTH=760;
    /** Where the reference's bottom-right corner lands on the seven-inch frame: 10 px left of the NOS strip, 12 px above the panel. */
    public static final float SEVEN_INCH_ANCHOR_X=970,SEVEN_INCH_ANCHOR_Y=457;
    /** The seven-inch dashboard's own overlays in frame pixels: status bar, battery band, gear / speed panel, NOS strip. */
    public static List<Box> sevenInchOverlays(int height){return List.of(new Box(0,0,1024,48),new Box(226,549,760,height),new Box(760,457,1024,height),new Box(970,355,1024,457));}
    /** The volume bar's place on the seven-inch frame: the app area's left edge, as on the five-inch. */
    private static final Box SEVEN_INCH_VOLUME=new Box(14,150,46,376);
    public DashboardProfile{occlusions=List.copyOf(occlusions);}
    private static volatile DashboardProfile last;
    /** The profile for an encoder frame; frames without a measured dashboard get the generic bottom-right fit. */
    public static DashboardProfile of(int width,int height){
        DashboardProfile cached=last;
        if(cached!=null&&cached.frameWidth==width&&cached.frameHeight==height)return cached;
        DashboardProfile profile=build(width,height);last=profile;return profile;
    }
    private static DashboardProfile build(int width,int height){
        if(SidebarLayout.halfScreen(width,height)){
            float scale=width/SidebarLayout.HALF_SCREEN_SPAN;
            return new DashboardProfile("half-screen",width,height,scale,width-848*scale,height-480*scale,true,SidebarLayout.HALF_SCREEN_TOP_INSET,0,
                    DisplaySettings.DEFAULT_HALF_VIRTUAL_WIDTH,DisplaySettings.DEFAULT_HALF_VIRTUAL_HEIGHT,DisplaySettings.DEFAULT_DPI,List.of(),false,SidebarLayout.VOLUME);
        }
        if(width==SEVEN_INCH_WIDTH&&(height==SEVEN_INCH_HEIGHT||height==SEVEN_INCH_ALIGNED_HEIGHT)){
            float dx=SEVEN_INCH_ANCHOR_X-848,dy=SEVEN_INCH_ANCHOR_Y-480;
            List<Box> occlusions=new ArrayList<>();
            for(Box b:sevenInchOverlays(height))occlusions.add(toReference(b,1,dx,dy));
            return new DashboardProfile("seven-inch",width,height,1,dx,dy,false,SEVEN_INCH_TOP_INSET,height-SEVEN_INCH_APP_BOTTOM,
                    SEVEN_INCH_APP_WIDTH,SEVEN_INCH_APP_BOTTOM-SEVEN_INCH_TOP_INSET,DisplaySettings.DEFAULT_DPI,occlusions,false,toReference(SEVEN_INCH_VOLUME,1,dx,dy));
        }
        float scale=width<=0||height<=0?0:Math.min(width/848f,height/480f);
        return new DashboardProfile(width==848&&height==480?"five-inch":"generic",width,height,scale,width-848*scale,height-480*scale,false,0,0,
                DisplaySettings.DEFAULT_VIRTUAL_WIDTH,DisplaySettings.DEFAULT_VIRTUAL_HEIGHT,DisplaySettings.DEFAULT_DPI,SidebarLayout.DEFAULT_OCCLUSIONS,true,SidebarLayout.VOLUME);
    }
    private static Box toReference(Box frame,float scale,float dx,float dy){return new Box((frame.left()-dx)/scale,(frame.top()-dy)/scale,(frame.right()-dx)/scale,(frame.bottom()-dy)/scale);}
    /** A frame-pixel rectangle in the HUD's reference coordinates. */
    public Box toReference(Box frame){return toReference(frame,scale,dx,dy);}
    /** The frame's left edge in reference units; the volume bar slides in from there. */
    public float referenceLeft(){return scale==0?0:-dx/scale;}
    public String describe(){return name+" scale="+scale+" anchor=("+(848*scale+dx)+","+(480*scale+dy)+") top="+topInset+" bottom="+bottomInset+" app="+appWidth+"x"+appHeight+"@"+appDpi+" hillHold="+hillHold;}
}
