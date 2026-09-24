package dev.ichinomiya.ninebotenhance.core;

import java.util.*;

/** One geometry definition shared by drawing and hit testing, in the 848 x 480 reference frame. */
public final class SidebarLayout {
    public static final float LEFT=648,RIGHT=838,BOTTOM=468,GAP=6,WIDTH=RIGHT-LEFT,MIN_WIDTH=24;
    /** Below this scale of the reference frame the cards are unreadable and are not drawn. */
    public static final float MIN_FIT=0.4f;
    /** Half-screen (portrait) frames show the card column and its margins across the whole frame width; the app keeps a strip above. */
    public static final float HALF_SCREEN_SPAN=848-(LEFT-10);
    public static final int HALF_SCREEN_TOP_INSET=20;
    public record Fit(float scale,float dx,float dy,boolean halfScreen,DashboardProfile profile){}
    public static boolean halfScreen(int width,int height){return width>0&&height>width;}
    /** How the reference frame lands in the encoder frame: the frame's dashboard profile decides the scale and the anchor. */
    public static Fit fit(int width,int height){
        if(width<=0||height<=0)return new Fit(0,0,0,false,DashboardProfile.of(848,480));
        DashboardProfile profile=DashboardProfile.of(width,height);
        return new Fit(profile.scale(),profile.dx(),profile.dy(),profile.halfScreen(),profile);
    }
    public static boolean fits(int width,int height){return fit(width,height).scale()>=MIN_FIT;}
    /** Half-screen notifications cannot be wider than the column they share with the cards. */
    public static int notificationWidth(int width,boolean halfScreen){return halfScreen?Math.min(width,(int)WIDTH):width;}
    public static Sizes fullWidth(){return new Sizes(WIDTH,WIDTH,WIDTH,WIDTH,WIDTH,WIDTH,WIDTH);}
    public static Sizes fullWidth(float bmsHeight){return new Sizes(WIDTH,WIDTH,WIDTH,WIDTH,WIDTH,WIDTH,WIDTH,WIDTH,bmsHeight);}
    /** Second column just left of the first, for cards the dashboard's top-right instrument would otherwise hide. */
    public static final float LEFT_COLUMN_RIGHT=LEFT-2.5f*GAP,LEFT_COLUMN_LEFT=LEFT_COLUMN_RIGHT-WIDTH;
    /** The music card matches the chart cards in height. */
    public static final float PHONE_HEIGHT=28,MUSIC_HEIGHT=84,TYRE_HEIGHT=28,VOLTAGE_HEIGHT=28,VOLTAGE_CHART_HEIGHT=84,LAMP_HEIGHT=28,BMS_ROW_HEIGHT=28;
    public static final float NOTIFICATION_HEIGHT=60;
    public record Box(float left,float top,float right,float bottom){
        public float height(){return bottom-top;}
        public float width(){return right-left;}
        public boolean contains(float x,float y){return x>=left&&x<right&&y>=top&&y<bottom;}
        public Box shifted(float dy){return dy==0?this:new Box(left,top+dy,right,bottom+dy);}
    }
    /**
     * Cards of both columns (null when switched off or hidden) plus where the notification block sits: its bottom edge, and whether
     * the hill-hold dodge moved it left of the toast.
     */
    public record Stack(Box phone,Box music,Box voltage,Box tyres,Box speed,Box power,Box lamp,Box bms,float notificationBottom,boolean notificationDodged){
        public Stack(Box phone,Box music,Box voltage,Box tyres,Box speed,Box power){this(phone,music,voltage,tyres,speed,power,null,null,BOTTOM,false);}
        public Stack(Box phone,Box music,Box voltage,Box tyres,Box speed,Box power,Box lamp,float notificationBottom,boolean notificationDodged){this(phone,music,voltage,tyres,speed,power,lamp,null,notificationBottom,notificationDodged);}
        public Stack shifted(float dy){return dy==0?this:new Stack(shift(phone,dy),shift(music,dy),shift(voltage,dy),shift(tyres,dy),shift(speed,dy),shift(power,dy),shift(lamp,dy),shift(bms,dy),notificationBottom,notificationDodged);}
        private static Box shift(Box box,float dy){return box==null?null:box.shifted(dy);}
        /** Box of the card for a widget flag; null when absent. */
        public Box of(int widget){
            return switch(widget){case WidgetSettings.PHONE->phone;case WidgetSettings.MUSIC->music;case WidgetSettings.TYRES->tyres;case WidgetSettings.VOLTAGE->voltage;case WidgetSettings.SPEED->speed;case WidgetSettings.POWER->power;case WidgetSettings.LAMP->lamp;case WidgetSettings.BMS->bms;default->null;};
        }
    }
    /** Content widths measured by the renderer; music and tyres are fixed to the sidebar width, the metric cards use template widths without their charts. */
    public record Sizes(float phoneWidth,float musicWidth,float voltageWidth,float tyreWidth,float speedWidth,float powerWidth,float lampWidth,float bmsWidth,float bmsHeight){
        public Sizes(float phoneWidth,float musicWidth,float voltageWidth,float tyreWidth){this(phoneWidth,musicWidth,voltageWidth,tyreWidth,WIDTH,WIDTH,WIDTH);}
        public Sizes(float phoneWidth,float musicWidth,float voltageWidth,float tyreWidth,float speedWidth,float powerWidth,float lampWidth){this(phoneWidth,musicWidth,voltageWidth,tyreWidth,speedWidth,powerWidth,lampWidth,WIDTH,BMS_ROW_HEIGHT);}
        public Sizes(float phoneWidth,float musicWidth,float voltageWidth,float tyreWidth,float speedWidth,float powerWidth){this(phoneWidth,musicWidth,voltageWidth,tyreWidth,speedWidth,powerWidth,WIDTH);}
    }
    /** Vertical volume bar above the dashboard speaker icon at the lower left; outside the columns and display only. */
    public static final Box VOLUME=new Box(14,150,46,376);
    /** Calibrated fallback for the dashboard's own instrument card at the top right; the cast configuration's bound rectangles replace it when read. */
    public static final Box INSTRUMENT=new Box(640,44,842,254);
    public static final List<Box> DEFAULT_OCCLUSIONS=List.of(INSTRUMENT);
    /** Dashboard toast "拧动油门解除坡道驻车" shown while hill hold is engaged, measured from a dashboard photo of the grid. */
    public static final Box HILL_HOLD_TOAST=new Box(574,372,848,462);
    /** Voltage, speed and power cards share one size: a single row, or a row plus a chart. */
    public static float metricHeight(boolean chart){return chart?VOLTAGE_CHART_HEIGHT:VOLTAGE_HEIGHT;}
    public static float voltageHeight(boolean chart){return metricHeight(chart);}
    /** Notification cards relative to the bottom edge; the renderer moves them to the block's place in the order. */
    public static Box notification(int width,float slot,float enter,float exit){
        float left=RIGHT-width+(width+33)*(1-enter)+(width+33)*exit;
        float base=BOTTOM-NOTIFICATION_HEIGHT+slot;
        // Every exiting slot reaches the bottom edge, so dismissing a whole group
        // also lowers the widgets continuously instead of jumping at removal.
        float top=base+NotificationTimeline.STEP*(1-enter)+(BOTTOM+GAP-base)*exit;
        return new Box(left,top,left+width,top+NOTIFICATION_HEIGHT);
    }
    public static float occupied(Box card){return Math.max(0,BOTTOM-card.top()+GAP);}
    public static Stack arrange(WidgetSettings settings,boolean phoneAvailable,boolean musicVisible,float notificationHeight){return arrange(settings,phoneAvailable,musicVisible,notificationHeight,new Sizes(WIDTH,WIDTH,WIDTH,WIDTH),false);}
    public static Stack arrange(WidgetSettings settings,boolean phoneAvailable,boolean musicVisible,float notificationHeight,Sizes sizes){return arrange(settings,phoneAvailable,musicVisible,notificationHeight,sizes,false);}
    public static Stack arrange(WidgetSettings settings,boolean phoneAvailable,boolean musicVisible,float notificationHeight,Sizes sizes,boolean dodge){
        int visible=settings.mask();
        if(!phoneAvailable)visible&=~WidgetSettings.PHONE;if(!musicVisible)visible&=~WidgetSettings.MUSIC;if(!settings.showsTyres())visible&=~WidgetSettings.TYRES;
        return arrange(settings,visible,notificationHeight,sizes,dodge);
    }
    public static Stack arrange(WidgetSettings settings,int visible,float notificationHeight,Sizes sizes,boolean dodge){return arrange(settings,visible,notificationHeight,sizes,dodge,DEFAULT_OCCLUSIONS);}
    /**
     * Right column: cards stack bottom up in the configured order; the notification block takes its own slot with the given height
     * (0 while empty), so cards ordered below it keep their place. With dodge on, every card the hill-hold toast covers (judged at
     * its current position) slides to the toast's left at its own height and the rest restack on the toast's top edge in order; a
     * covered notification block is flagged so the renderer slides it left too, an uncovered one restacks like a card.
     * Left column: its cards stack bottom up from the bottom edge, but always above the notification block (whose cards reach into
     * that column), above the toast and above every card the dodge pushed out of the right column. Right-column cards that would
     * reach into a dashboard occlusion (the instrument card by default, the configuration's bound rectangles when read) join the
     * left column, each slotted among its cards by height, until the column is short enough again.
     */
    public static Stack arrange(WidgetSettings settings,int visible,float notificationHeight,Sizes sizes,boolean dodge,List<Box> occlusions){return arrange(settings,visible,notificationHeight,sizes,dodge,occlusions,false);}
    /** A single column (half-screen frames) stacks every card in the saved order on the right, with no dodge and no occlusion overflow. */
    public static Stack arrange(WidgetSettings settings,int visible,float notificationHeight,Sizes sizes,boolean dodge,List<Box> occlusions,boolean singleColumn){
        float lift=Math.max(0,notificationHeight),bottom=BOTTOM,notificationBottom=BOTTOM,leftBottom=BOTTOM;
        LinkedHashMap<Integer,Box> placed=new LinkedHashMap<>();
        List<Integer> right=settings.rightOrder(),left=settings.leftOrder();
        if(singleColumn){ArrayList<Integer> all=new ArrayList<>(right);all.addAll(left);right=all;left=List.of();dodge=false;occlusions=List.of();}
        for(int w:right){
            if(w==WidgetSettings.NOTIFICATIONS){notificationBottom=bottom;bottom-=lift;continue;}
            if((visible&w)==0)continue;
            Box card=box(bottom,height(settings,sizes,w),width(sizes,w),WIDTH,RIGHT);placed.put(w,card);bottom=card.top()-GAP;
        }
        boolean dodged=false;
        if(dodge){
            float leftEdge=HILL_HOLD_TOAST.left()-GAP,upper=HILL_HOLD_TOAST.top()-GAP;
            Box block=lift>0?new Box(LEFT,notificationBottom-lift+GAP,RIGHT,notificationBottom):null;
            dodged=covered(block);leftBottom=Math.min(leftBottom,upper);
            for(int w:right){
                if(w==WidgetSettings.NOTIFICATIONS){if(block!=null&&!dodged){notificationBottom=upper;upper-=lift;}continue;}
                Box card=placed.get(w);if(card==null)continue;
                if(covered(card)){Box moved=new Box(leftEdge-card.width(),card.top(),leftEdge,card.bottom());placed.put(w,moved);leftBottom=Math.min(leftBottom,moved.top()-GAP);}
                else{Box moved=new Box(RIGHT-card.width(),upper-card.height(),RIGHT,upper);placed.put(w,moved);upper=moved.top()-GAP;}
            }
        }
        if(lift>0)leftBottom=Math.min(leftBottom,notificationBottom-lift);
        ArrayList<Integer> leftCards=new ArrayList<>();ArrayList<Box> reference=new ArrayList<>();float probe=leftBottom;
        for(int w:left){if((visible&w)==0)continue;Box card=box(probe,height(settings,sizes,w),width(sizes,w),WIDTH,LEFT_COLUMN_RIGHT);leftCards.add(w);reference.add(card);probe=card.top()-GAP;}
        // Overflow: a right-column card under the instrument is inserted above every left card that sits lower than it did.
        for(int w:right){
            Box card=placed.get(w);if(card==null||card.right()!=RIGHT||!intersectsAny(card,occlusions))continue;
            float center=(card.top()+card.bottom())/2;int index=0;for(Box other:reference)if((other.top()+other.bottom())/2>center)index++;
            leftCards.add(index,w);reference.add(index,card);placed.remove(w);
        }
        for(int w:leftCards){Box card=box(leftBottom,height(settings,sizes,w),width(sizes,w),WIDTH,LEFT_COLUMN_RIGHT);placed.put(w,card);leftBottom=card.top()-GAP;}
        return new Stack(placed.get(WidgetSettings.PHONE),placed.get(WidgetSettings.MUSIC),placed.get(WidgetSettings.VOLTAGE),placed.get(WidgetSettings.TYRES),placed.get(WidgetSettings.SPEED),placed.get(WidgetSettings.POWER),placed.get(WidgetSettings.LAMP),placed.get(WidgetSettings.BMS),notificationBottom,dodged);
    }
    private static float height(WidgetSettings s,Sizes z,int widget){
        return switch(widget){
            case WidgetSettings.PHONE->PHONE_HEIGHT;case WidgetSettings.MUSIC->MUSIC_HEIGHT;case WidgetSettings.TYRES->TYRE_HEIGHT;
            case WidgetSettings.VOLTAGE->metricHeight(s.enabled(WidgetSettings.VOLTAGE_CHART));case WidgetSettings.SPEED->metricHeight(s.enabled(WidgetSettings.SPEED_CHART));
            case WidgetSettings.POWER->metricHeight(s.enabled(WidgetSettings.POWER_CHART));case WidgetSettings.LAMP->LAMP_HEIGHT;case WidgetSettings.BMS->z.bmsHeight();default->0;
        };
    }
    private static float width(Sizes z,int widget){
        return switch(widget){
            case WidgetSettings.PHONE->z.phoneWidth();case WidgetSettings.MUSIC->z.musicWidth();case WidgetSettings.TYRES->z.tyreWidth();
            case WidgetSettings.VOLTAGE->z.voltageWidth();case WidgetSettings.SPEED->z.speedWidth();case WidgetSettings.POWER->z.powerWidth();case WidgetSettings.LAMP->z.lampWidth();case WidgetSettings.BMS->z.bmsWidth();default->WIDTH;
        };
    }
    /** Strict overlap: boxes that only share an edge do not intersect. */
    public static boolean intersects(Box a,Box b){return a!=null&&b!=null&&a.left()<b.right()&&b.left()<a.right()&&a.top()<b.bottom()&&b.top()<a.bottom();}
    public static boolean intersectsAny(Box card,List<Box> boxes){if(boxes!=null)for(Box b:boxes)if(intersects(card,b))return true;return false;}
    /** Whether a card at this position lies under the hill-hold toast. */
    public static boolean covered(Box card){return card!=null&&card.top()<HILL_HOLD_TOAST.bottom()&&card.bottom()>HILL_HOLD_TOAST.top()&&card.right()>HILL_HOLD_TOAST.left();}
    /** Horizontal shift that puts a right-aligned card or notification just left of the hill-hold toast. */
    public static float dodgeShift(){return HILL_HOLD_TOAST.left()-GAP-RIGHT;}
    public static float clampWidth(float width){return clampWidth(width,WIDTH);}
    public static float clampWidth(float width,float max){return Float.isNaN(width)?max:Math.max(MIN_WIDTH,Math.min(max,width));}
    private static Box box(float bottom,float height,float width,float max,float right){width=clampWidth(width,max);return new Box(right-width,bottom-height,right,bottom);}
    private SidebarLayout(){}
}
