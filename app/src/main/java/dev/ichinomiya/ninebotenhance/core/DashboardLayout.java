package dev.ichinomiya.ninebotenhance.core;

import dev.ichinomiya.ninebotenhance.core.SidebarLayout.Box;
import java.util.*;

/**
 * The parts of the dashboard cast configuration (the JSON Ninebot reads from the TFT board and caches as
 * {@code <SN>_screen_cast_config.nb}) that decide the module's frame size and where the dashboard paints over the frame.
 * <p>
 * {@code layout.baseMap.uiStyle.boundRects} lists every rectangle other UI covers on the map. Rectangles that overlap a
 * phone-drawn layout element (any {@code layout.*} entry with a frame, except the map itself) belong to that element and are
 * not dashboard overlays; the remaining rectangles are painted by the dashboard itself and must be dodged. All boxes are in
 * encoder pixels: the configuration's dimension units scaled to {@code codecWidth × codecHeight}.
 */
public record DashboardLayout(int frameWidth,int frameHeight,List<Box> boundRects,List<Box> phoneDrawn,String style) {
    public static final int REFERENCE_WIDTH=848,REFERENCE_HEIGHT=480;
    /** Codec sizes the boards are known to report: 240 x 320 (portrait half-screen dashboards), 848 x 480 (five-inch) and 1024 x 600 (seven-inch, 608 when the board aligns the height). */
    public static final int MIN_SIDE=160,MAX_SIDE=1920;
    /** Calibrated fallback used when no configuration has been read: the instrument card measured from a dashboard photo. */
    public static final DashboardLayout DEFAULT=new DashboardLayout(REFERENCE_WIDTH,REFERENCE_HEIGHT,List.of(SidebarLayout.INSTRUMENT),List.of(),"");
    public DashboardLayout {
        if(frameWidth<MIN_SIDE||frameHeight<MIN_SIDE||frameWidth>MAX_SIDE||frameHeight>MAX_SIDE)throw new IllegalArgumentException("frame "+frameWidth+"x"+frameHeight);
        boundRects=List.copyOf(boundRects);phoneDrawn=List.copyOf(phoneDrawn);style=style==null?"":style;
    }
    public static DashboardLayout parse(String json) {
        Object root=Json.parse(json);
        Map<String,Object> layout=Json.object(Json.get(root,"layout"));
        if(layout==null)throw new IllegalArgumentException("no layout");
        double dimensionWidth=Json.number(Json.get(root,"dimensionWidth"),0),dimensionHeight=Json.number(Json.get(root,"dimensionHeight"),0);
        Map<String,Object> baseMap=Json.object(layout.get("baseMap"));
        if(dimensionWidth<=0||dimensionHeight<=0) {
            dimensionWidth=Json.number(Json.get(baseMap,"frame","width"),0);dimensionHeight=Json.number(Json.get(baseMap,"frame","height"),0);
        }
        if(dimensionWidth<=0||dimensionHeight<=0)throw new IllegalArgumentException("no dimensions");
        double codecWidth=Json.number(Json.get(root,"codecWidth"),dimensionWidth),codecHeight=Json.number(Json.get(root,"codecHeight"),dimensionHeight);
        double sx=codecWidth/dimensionWidth,sy=codecHeight/dimensionHeight;
        List<Box> bounds=new ArrayList<>();
        List<Object> rects=Json.array(Json.get(baseMap,"uiStyle","boundRects"));
        if(rects!=null)for(Object rect:rects) { Box box=box(rect,sx,sy);if(box!=null)bounds.add(box); }
        List<Box> drawn=new ArrayList<>();
        for(Map.Entry<String,Object> entry:layout.entrySet()) {
            if(entry.getKey().equals("baseMap"))continue;
            Box box=box(Json.get(entry.getValue(),"frame"),sx,sy);if(box!=null)drawn.add(box);
        }
        int width=even(codecWidth),height=even(codecHeight);
        return new DashboardLayout(width,height,bounds,drawn,Json.string(Json.get(baseMap,"style")));
    }
    private static Box box(Object value,double sx,double sy) {
        Map<String,Object> map=Json.object(value);if(map==null)return null;
        double x=Json.number(map.get("x"),0),y=Json.number(map.get("y"),0),w=Json.number(map.get("width"),0),h=Json.number(map.get("height"),0);
        if(w<=0||h<=0)return null;
        return new Box((float)(x*sx),(float)(y*sy),(float)((x+w)*sx),(float)((y+h)*sy));
    }
    private static int even(double value) { int v=(int)Math.round(value);return v&~1; }
    /** Rectangles the dashboard paints itself: bound rectangles not overlapping any phone-drawn element. */
    public List<Box> occlusions() {
        List<Box> out=new ArrayList<>();
        for(Box rect:boundRects) {
            boolean phone=false;
            for(Box element:phoneDrawn)if(SidebarLayout.intersects(rect,element)){phone=true;break;}
            if(!phone)out.add(rect);
        }
        return out;
    }
    /** Occlusions in the HUD's 848 × 480 reference frame, through the same fit the HUD draws with ({@link DashboardProfile}). */
    public List<Box> referenceOcclusions() {
        DashboardProfile profile=DashboardProfile.of(frameWidth,frameHeight);
        List<Box> out=new ArrayList<>();
        for(Box box:occlusions())out.add(profile.toReference(box));
        return out;
    }
    public String describe() {
        StringBuilder text=new StringBuilder("frame=").append(frameWidth).append('x').append(frameHeight).append(" style=").append(style).append(" occlusions=");
        for(Box b:occlusions())text.append('(').append((int)b.left()).append(',').append((int)b.top()).append(',').append((int)b.right()).append(',').append((int)b.bottom()).append(')');
        return text.append(" boundRects=").append(boundRects.size()).append(" phoneDrawn=").append(phoneDrawn.size()).toString();
    }
}
