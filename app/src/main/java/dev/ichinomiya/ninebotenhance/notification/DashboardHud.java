package dev.ichinomiya.ninebotenhance.notification;

import android.graphics.*;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.TextPaint;
import dev.ichinomiya.ninebotenhance.core.CardMotion;
import dev.ichinomiya.ninebotenhance.core.NotificationTimeline;
import dev.ichinomiya.ninebotenhance.core.MusicPlayback;
import dev.ichinomiya.ninebotenhance.core.TireTelemetry;
import dev.ichinomiya.ninebotenhance.core.BatteryTelemetry;
import dev.ichinomiya.ninebotenhance.core.RegisterProbe;
import dev.ichinomiya.ninebotenhance.core.RideState;
import dev.ichinomiya.ninebotenhance.core.HillHoldDetector;
import dev.ichinomiya.ninebotenhance.core.DashboardProfile;
import dev.ichinomiya.ninebotenhance.core.WidgetSettings;
import dev.ichinomiya.ninebotenhance.core.HudPalette;
import dev.ichinomiya.ninebotenhance.core.LampState;
import dev.ichinomiya.ninebotenhance.core.BmsCard;
import dev.ichinomiya.ninebotenhance.core.BmsSettings;
import dev.ichinomiya.ninebotenhance.core.BmsState;
import dev.ichinomiya.ninebotenhance.core.WidgetCondition;
import dev.ichinomiya.ninebotenhance.core.SidebarLayout;
import java.util.*;

/** Canvas counterpart of the approved HTML. Immutable card images; no UI overlays or touch injection. */
public final class DashboardHud {
    private static final int SIDEBAR_RIGHT=838;
    /** Card colours of the current dashboard theme; a change re-encodes every card and drops the pre-rendered notification cards. */
    private HudPalette p=HudPalette.DARK;
    /** Volume bar hold and slide times and its fill animation; the voltage chart window comes from the settings. */
    public static final long VOLUME_SHOW_MS=2200,VOLUME_SLIDE_MS=260,VOLUME_FILL_MS=150;
    private static final float INSET=10,VALUE_COLUMN=36,TYRE_INSET=8;
    /** Music card geometry inside its 84-pixel height: artwork square, header centre, progress bar and time labels. */
    private static final float MUSIC_ART=48,MUSIC_ART_TOP=8,MUSIC_HEADER=32,MUSIC_BAR_Y=62,MUSIC_TIME_Y=72;
    private record Card(Bitmap bitmap) {}
    private record Sample(long elapsed,float value) {}
    private final NotificationTimeline<Card> timeline=new NotificationTimeline<>();
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final TextPaint text=new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint.FontMetrics textMetrics=new Paint.FontMetrics();
    private final Rect glyphBounds=new Rect();
    private final Path wifiGlyph=wifiGlyph(),chartLine=new Path(),chartFill=new Path();
    private String epoch="",session="";private long cursor=-1,revision,lastUpdate;private Bundle phone,music;
    private Bitmap artwork;private long artRevision=-1;private int notificationWidth=NotificationTimeline.DEFAULT_WIDTH;
    private boolean receiving;private int simulated;private long simulatedUntil;private int simulatedDuration=15000;
    private CardMotion phoneMotion=new CardMotion(),musicMotion=new CardMotion(),voltageMotion=new CardMotion(),tyreMotion=new CardMotion(),speedMotion=new CardMotion(),powerMotion=new CardMotion(),lampMotion=new CardMotion(),bmsMotion=new CardMotion();
    /** Lamp link and the height it last reported; it arrives with the dashboard snapshot, never estimated here. */
    private LampState lamp=LampState.NONE;
    /** Displayed brightness 0–100 the module computed from the raw height, its usable range and its reverse flag; -1 when unknown. */
    private int lampPercent=-1;
    /** BMS link and its last reading from the dashboard snapshot; the card layout and the BMS-first switches are module settings. */
    private BmsState bms=BmsState.NONE;private int bmsPollMs=BmsSettings.DEFAULT_POLL_MS;private BmsCard.Layout bmsLayout=BmsCard.DEFAULT;
    private final BmsCardPainter bmsPainter=new BmsCardPainter();
    public synchronized void setBmsLayout(BmsCard.Layout value){if(value!=null&&!bmsLayout.equals(value)){bmsLayout=value;revision++;}}
    private boolean bmsFresh(long now){return bms.connected(now,bmsPollMs*3L+1000);}
    private boolean voltageFromBms(long now){return widgets.enabled(WidgetSettings.VOLTAGE_FROM_BMS)&&bmsFresh(now);}
    private boolean powerFromBms(long now){return widgets.enabled(WidgetSettings.POWER_FROM_BMS)&&bmsFresh(now);}
    private float voltageValue(long now){if(voltageFromBms(now))return bms.data().volts();return expired(battery.voltage(),now)?Float.NaN:battery.voltage().number();}
    private float powerValue(long now){if(powerFromBms(now))return bms.data().watts();return powerExpired(now)?Float.NaN:ride.power();}
    /** Per-widget windows opened by change conditions, indexed like WidgetSettings.CONDITIONAL; rest and lifted layouts of the last sync. */
    private final long[] shownUntil=new long[WidgetSettings.CONDITIONAL.length];private String musicKeyShown="";private boolean musicMovingShown;
    private SidebarLayout.Stack actualStack;
    private long volumeSeq=-1,volumeShownAt=-1,volumeShownUntil=-1,volumeFillAt;private int volumeLevel,volumeMax=15;private float volumeFillFrom,volumeFillTo;
    private final ArrayDeque<Sample> voltageHistory=new ArrayDeque<>(),speedHistory=new ArrayDeque<>(),powerHistory=new ArrayDeque<>();
    private final HillHoldDetector detector=new HillHoldDetector();
    private TireTelemetry.Snapshot tires=TireTelemetry.EMPTY;
    private BatteryTelemetry.Snapshot battery=BatteryTelemetry.EMPTY;
    private WidgetSettings widgets=WidgetSettings.DEFAULT;
    /** Dashboard-painted rectangles from the cast configuration, in reference coordinates; null until one is read. */
    private volatile List<SidebarLayout.Box> configuredOcclusions;
    /** Set from the frame the HUD is drawn into: the profile decides the fit, the half-screen single column, the dodge and the volume bar's place. */
    private volatile DashboardProfile profile=DashboardProfile.of(848,480);
    private volatile boolean halfScreen;
    public boolean halfScreen(){return halfScreen;}
    /** The 2.4 inch screen profile: the module's own speed and battery blocks replace every sidebar card. */
    private volatile boolean smallScreen;
    /** The 2.4 inch panel is drawn in its own 240 x 320 reference and scaled into whatever frame the panel is given. */
    private static final float SMALL_PANEL_WIDTH=240,SMALL_PANEL_HEIGHT=320;
    /** The panel is 320 tall but the layout fills 300 of it, leaving the top strip clear of the speed digits. */
    private static final float SMALL_LAYOUT_HEIGHT=300;
    /** Row colours of the small screen: 0 keeps the theme's own text colour. */
    private int smallSpeedColor,smallRow1Color,smallRow2Color;
    /** The panel's own backdrop: an opaque colour, or 0 to keep the theme's surface. Black out of the box. */
    private int smallBackground=0xff000000;
    /** Where the small screen reads voltage and charge: the vehicle registers or the bound protection board. */
    private int smallSource;
    /** The module's bundled font for all module-drawn text; null keeps the system sans-serif. */
    private Typeface fixedTypeface;
    public synchronized void setSmallScreen(boolean value){if(smallScreen==value)return;smallScreen=value;timeline.clear();revision++;}
    public boolean smallScreen(){return smallScreen;}
    public synchronized void setSmallColors(int speed,int row1,int row2){
        int a=opaque(speed),b=opaque(row1),c=opaque(row2);
        if(a==smallSpeedColor&&b==smallRow1Color&&c==smallRow2Color)return;
        smallSpeedColor=a;smallRow1Color=b;smallRow2Color=c;revision++;
    }
    public synchronized void setSmallSource(int value){value=Math.max(0,Math.min(1,value));if(value==smallSource)return;smallSource=value;revision++;}
    public int smallSource(){return smallSource;}
    /** The stored colour for a panel row; 0 when the row keeps the theme's own text colour. */
    public int smallRowColor(int row){return row==0?smallSpeedColor:row==1?smallRow1Color:row==2?smallRow2Color:0;}
    public synchronized void setSmallBackground(int value){value=opaque(value);if(value==smallBackground)return;smallBackground=value;revision++;}
    public int smallBackground(){return smallBackground;}
    /** Only an opaque colour overrides the theme; anything else falls back to it. */
    private static int opaque(int value){return (value>>>24)==0xff?value:0;}
    public synchronized void setFixedTypeface(Typeface value){if(value==fixedTypeface)return;fixedTypeface=value;timeline.clear();revision++;}
    public DashboardProfile profile(){return profile;}
    private void adopt(SidebarLayout.Fit fit){profile=fit.profile();if(fit.halfScreen()!=halfScreen){halfScreen=fit.halfScreen();timeline.clear();revision++;}}
    /** Dashboard-painted rectangles in the 848 x 480 reference frame; empty or null falls back to the profile's measured overlays. */
    public void setOcclusions(List<SidebarLayout.Box> boxes){configuredOcclusions=boxes==null||boxes.isEmpty()?null:List.copyOf(boxes);}
    public List<SidebarLayout.Box> occlusions(){List<SidebarLayout.Box> configured=configuredOcclusions;return configured!=null?configured:profile.occlusions();}
    private List<RegisterProbe.Row> probeRows;private RideState.Snapshot ride;
    public synchronized void reset(){reset("");}
    public synchronized void reset(String request){
        session=request;timeline.clear();phone=music=null;tires=TireTelemetry.EMPTY;battery=BatteryTelemetry.EMPTY;artwork=null;artRevision=-1;epoch="";cursor=-1;receiving=false;lastUpdate=0;simulatedUntil=0;
        phoneMotion=new CardMotion();musicMotion=new CardMotion();voltageMotion=new CardMotion();tyreMotion=new CardMotion();speedMotion=new CardMotion();powerMotion=new CardMotion();lampMotion=new CardMotion();bmsMotion=new CardMotion();detector.reset();
        ride=null;Arrays.fill(shownUntil,0);musicKeyShown="";musicMovingShown=false;volumeSeq=-1;volumeShownAt=volumeShownUntil=-1;volumeFillAt=0;volumeFillFrom=volumeFillTo=0;revision++;
    }
    public synchronized void acceptTires(TireTelemetry.Snapshot snapshot){if(snapshot==null)snapshot=TireTelemetry.EMPTY;if(!tires.equals(snapshot)){tires=snapshot;revision++;}}
    public synchronized void acceptBattery(BatteryTelemetry.Snapshot snapshot){
        if(snapshot==null)snapshot=BatteryTelemetry.EMPTY;if(battery.equals(snapshot))return;
        battery=snapshot;revision++;BatteryTelemetry.Value voltage=snapshot.voltage();
        if(voltage!=null&&!widgets.enabled(WidgetSettings.VOLTAGE_FROM_BMS))sample(voltageHistory,voltage.elapsedTime(),voltage.number());
    }
    /** One chart sample per received value; histories outlive sessions and forget anything past the longest chart window. */
    private static void sample(ArrayDeque<Sample> history,long at,float value){
        if(at<=0||!(history.isEmpty()||history.peekLast().elapsed()<at))return;
        history.addLast(new Sample(at,value));
        while(!history.isEmpty()&&(history.size()>600||at-history.peekFirst().elapsed()>WidgetSettings.MAX_CHART_SECONDS*1000L))history.removeFirst();
    }
    /** Latest speed/power readings; hill hold moves the cards away from the dashboard toast while the dodge switch is on. */
    public synchronized void acceptRide(RideState.Snapshot snapshot){
        if(!Objects.equals(ride,snapshot)){ride=snapshot;revision++;}
        if(snapshot!=null){if(snapshot.speedTenths()>=0)sample(speedHistory,snapshot.speedAt(),snapshot.speedKmh());if(snapshot.hasPower()&&!widgets.enabled(WidgetSettings.POWER_FROM_BMS))sample(powerHistory,snapshot.powerAt(),snapshot.power());}
    }
    /** Hill hold is judged on the vehicle's own rSpeed / rPower readings only; BMS priority changes what the power card shows, never this. */
    public synchronized boolean hillHold(long now){return profile.hillHold()&&widgets.enabled(WidgetSettings.HILL_HOLD_DODGE)&&detector.update(ride,now,widgets.holdPowerMin(),widgets.holdPowerMax(),widgets.holdSpeedMaxTenths(),widgets.holdMs());}
    /** Debug register table; null or empty hides it. Equal snapshots do not re-encode. */
    public synchronized void acceptProbe(List<RegisterProbe.Row> rows){if(!Objects.equals(probeRows,rows)){probeRows=rows;revision++;}}
    private boolean probeShown(){return probeRows!=null&&!probeRows.isEmpty()&&widgets.enabled(WidgetSettings.REGISTER_PROBE);}
    public synchronized void setDark(boolean dark){if(p.dark()==dark)return;p=HudPalette.of(dark);timeline.clear();revision++;}
    public boolean dark(){return p.dark();}
    public synchronized void setWidgets(WidgetSettings value){if(!widgets.equals(value)){widgets=value;if(!widgets.enabled(WidgetSettings.NOTIFICATIONS)){timeline.clear();receiving=false;}revision++;}}
    public synchronized void request(Bundle args){args.putLong("hud_cursor",cursor);args.putString("hud_epoch",epoch);args.putLong("music_art_revision",artRevision);}
    public synchronized void accept(String request,Bundle state,long now){
        if(!session.equals(request))return;
        if(state==null){reset(session);return;}
        lastUpdate=now;
        String nextEpoch=state.getString("epoch","");if(!epoch.equals(nextEpoch)){timeline.clear();epoch=nextEpoch;cursor=-1;artRevision=-1;artwork=null;revision++;}
        int nextWidth=Math.max(NotificationTimeline.MIN_WIDTH,Math.min(NotificationTimeline.MAX_WIDTH,state.getInt("notification_width",NotificationTimeline.DEFAULT_WIDTH)));
        if(notificationWidth!=nextWidth){notificationWidth=nextWidth;timeline.clear();revision++;}
        if(timeline.setLimit(state.getInt("notification_limit",NotificationTimeline.DEFAULT_LIMIT),now))revision++;
        simulatedDuration=NotificationTimeline.clampSeconds(state.getInt("notification_seconds",15))*1000;
        Bundle nextMusic=state.getBundle("music");
        // A track change or a play/pause change fires the matching triggers; a track appearing counts as both.
        if(activeMusic(nextMusic)){
            String key=musicKey(nextMusic);boolean moving=MusicPlayback.moving(nextMusic.getInt("state"));
            int fired=(!musicActive()||!key.equals(musicKeyShown)?WidgetCondition.TRACK_CHANGE:0)|(!musicActive()||moving!=musicMovingShown?WidgetCondition.PLAYBACK_CHANGE:0);
            if(fired!=0)fire(fired,now);
            musicKeyShown=key;musicMovingShown=moving;
        }else musicKeyShown="";
        if(!sameMusic(music,nextMusic)){music=nextMusic;revision++;}else music=nextMusic;
        long nextArt=music==null?-1:music.getLong("art_revision",-1);
        if(nextArt!=artRevision){artRevision=nextArt;artwork=music==null?null:music.getParcelable("art",Bitmap.class);revision++;}
        Bundle lampState=state.getBundle("lamp");
        LampState nextLamp=lampState==null?LampState.NONE:new LampState(lampState.getInt("phase"),lampState.getInt("position",-1),
                lampState.getInt("speed",-1),lampState.getInt("low",-1),lampState.getInt("high",-1),lampState.getString("detail",""));
        int nextPercent=lampState==null?-1:lampState.getInt("percent",-1);
        if(!lamp.equals(nextLamp)||lampPercent!=nextPercent){lamp=nextLamp;lampPercent=nextPercent;revision++;}
        Bundle bmsBundle=state.getBundle("bms");BmsState nextBms=BmsBundle.read(bmsBundle);int nextPoll=BmsBundle.pollMs(bmsBundle);
        if(!bms.equals(nextBms)||(nextPoll>0&&bmsPollMs!=nextPoll)){
            boolean reading=nextBms.data().known()&&nextBms.data().at()!=bms.data().at();
            bms=nextBms;if(nextPoll>0)bmsPollMs=nextPoll;revision++;
            if(reading){
                if(widgets.enabled(WidgetSettings.VOLTAGE_FROM_BMS))sample(voltageHistory,nextBms.data().at(),nextBms.data().volts());
                if(widgets.enabled(WidgetSettings.POWER_FROM_BMS))sample(powerHistory,nextBms.data().at(),nextBms.data().watts());
            }
        }
        Bundle volume=state.getBundle("volume");
        if(volume!=null){long seq=volume.getLong("seq",-1);
            // The first snapshot of a session only records the level; later sequence numbers mean the user changed it.
            if(seq!=volumeSeq){boolean change=volumeSeq>=0;volumeSeq=seq;volumeLevel=volume.getInt("level",0);volumeMax=Math.max(1,volume.getInt("max",15));if(change){volumeChanged(now);fire(WidgetCondition.VOLUME_CHANGE,now);}revision++;}}
        long previousCursor=cursor;cursor=Math.max(cursor,state.getLong("cursor",-1));Bundle nextPhone=state.getBundle("phone");
        // Small immutable snapshot. Revision changes only on new phone data or notification events.
        if(!samePhone(phone,nextPhone)){phone=nextPhone;revision++;}
        receiving=state.getBoolean("enabled")&&widgets.enabled(WidgetSettings.NOTIFICATIONS);
        // Simulated cards are local and outlive a disabled mailbox until they expire on their own.
        // Card motions start at the snapshot time rather than at the next render, so sparse renders still animate correctly.
        if(!receiving){if(now>=simulatedUntil&&!timeline.empty(now)){timeline.clear();revision++;}sync(now);return;}
        ArrayList<Bundle> events=state.getParcelableArrayList("events",Bundle.class);if(events==null){sync(now);return;}
        for(Bundle e:events){long seq=e.getLong("seq",-1);if(seq<=previousCursor)continue;previousCursor=seq;
            String key=e.getString("key","");if(e.getBoolean("removed"))timeline.remove(key,now);else{
            int duration=Math.max(1000,Math.min(60000,e.getInt("duration",15000)));
            if(now-e.getLong("posted",now)>duration)continue;
            timeline.add(key,new Card(card(e)),duration,now);
        }revision++;}
        sync(now);
    }
    /** Fixture for the device smoke tests: one synthetic card with the configured width, independent of the phone mailbox and its switches. No UI reaches it. */
    public synchronized void simulate(long now){
        Bundle e=new Bundle();e.putString("app","Ninebot Enhance");e.putString("title","模拟通知 "+(++simulated));e.putString("text","用于预览通知的位置、宽度和字号");
        int duration=simulatedDuration;timeline.add("simulated-"+simulated,new Card(card(e)),duration,now);
        simulatedUntil=Math.max(simulatedUntil,now+duration+NotificationTimeline.EXIT_MS);revision++;
    }
    private static boolean samePhone(Bundle a,Bundle b){if(a==null||b==null)return a==b;return a.getInt("battery",-1)==b.getInt("battery",-1)&&a.getBoolean("charging")==b.getBoolean("charging")&&a.getBoolean("wifi")==b.getBoolean("wifi")&&a.getBoolean("phone_permission")==b.getBoolean("phone_permission")&&Objects.equals(a.getIntegerArrayList("slots"),b.getIntegerArrayList("slots"))&&Objects.equals(a.getIntegerArrayList("levels"),b.getIntegerArrayList("levels"))&&Objects.equals(a.getString("network",""),b.getString("network",""));}
    private static boolean sameMusic(Bundle a,Bundle b){if(a==null||b==null)return a==b;for(String key:new String[]{"media_session","title","artist"})if(!Objects.equals(a.getString(key),b.getString(key)))return false;for(String key:new String[]{"duration","position","updated","art_revision"})if(a.getLong(key)!=b.getLong(key))return false;return a.getInt("state")==b.getInt("state")&&a.getBoolean("active")==b.getBoolean("active")&&a.getBoolean("granted")==b.getBoolean("granted")&&a.getFloat("speed")==b.getFloat("speed");}
    private static boolean activeMusic(Bundle m){return m!=null&&m.getBoolean("granted")&&m.getBoolean("active")&&MusicPlayback.hasTrack(m.getInt("state"));}
    private static String musicKey(Bundle m){return m.getString("media_session","")+"\n"+m.getString("title","")+"\n"+m.getString("artist","");}
    private boolean musicActive(){return activeMusic(music);}
    private boolean musicVisible(long now){return visible(WidgetSettings.MUSIC,now);}
    /** A trigger opens every widget whose condition waits for it, each for its own time. */
    private void fire(int trigger,long now){
        for(int i=0;i<WidgetSettings.CONDITIONAL.length;i++){WidgetCondition c=widgets.condition(WidgetSettings.CONDITIONAL[i]);if(c.triggered(trigger)){shownUntil[i]=Math.max(shownUntil[i],now+c.showMs());revision++;}}
    }
    /** Whether a widget's display condition holds now: always, inside its window after a trigger, or while every checked value is in range. */
    private boolean conditionMet(int widget,long now){
        WidgetCondition c=widgets.condition(widget);
        return switch(c.mode()){
            case WidgetCondition.ON_CHANGE->now<shownUntil[WidgetSettings.index(widget)];
            case WidgetCondition.WHILE->c.matches(new WidgetCondition.Measurements(speedExpired(now)?Float.NaN:ride.speedKmh(),powerValue(now),voltageValue(now),
                    volumeSeq<0?Float.NaN:volumeLevel*100f/volumeMax,musicActive()&&MusicPlayback.moving(music.getInt("state")),
                    tyre(tires.front().pressure(),now),tyre(tires.rear().pressure(),now),tyre(tires.front().temperature(),now),tyre(tires.rear().temperature(),now),bmsFresh(now)));
            default->true;
        };
    }
    private float tyre(TireTelemetry.Value value,long now){return expired(value,now)?Float.NaN:value.number();}
    /** Enabled widgets whose data exists and whose condition holds; drives layout, drawing and re-encoding. */
    private int visibleMask(long now){
        int mask=0;
        if(widgets.enabled(WidgetSettings.PHONE)&&phone!=null&&conditionMet(WidgetSettings.PHONE,now))mask|=WidgetSettings.PHONE;
        // With the "always" condition the music card stays as an idle card when no media session exists.
        if(widgets.enabled(WidgetSettings.MUSIC)&&(musicActive()?conditionMet(WidgetSettings.MUSIC,now):widgets.condition(WidgetSettings.MUSIC).mode()==WidgetCondition.ALWAYS))mask|=WidgetSettings.MUSIC;
        if(widgets.showsTyres()&&conditionMet(WidgetSettings.TYRES,now))mask|=WidgetSettings.TYRES;
        for(int w:new int[]{WidgetSettings.VOLTAGE,WidgetSettings.SPEED,WidgetSettings.POWER,WidgetSettings.NOTIFICATIONS,WidgetSettings.VOLUME,WidgetSettings.LAMP,WidgetSettings.BMS})if(widgets.enabled(w)&&conditionMet(w,now))mask|=w;
        return mask;
    }
    private boolean visible(int widget,long now){return (visibleMask(now)&widget)!=0;}
    /** Compact visibility bits for the revision hash, one per conditional widget. */
    private int visibleBits(long now){int mask=visibleMask(now),bits=0;for(int i=0;i<WidgetSettings.CONDITIONAL.length;i++)if((mask&WidgetSettings.CONDITIONAL[i])!=0)bits|=1<<i;return bits;}
    /** Notification cards are held back entirely while the notification condition fails. */
    private List<NotificationTimeline.Entry<Card>> cards(long now){return conditionMet(WidgetSettings.NOTIFICATIONS,now)?timeline.entries(now):List.of();}
    private boolean musicMoving(long now){return musicVisible(now)&&musicActive()&&MusicPlayback.moving(music.getInt("state"));}
    /** A change while hidden slides the bar in from the left; while visible it only extends the hold; during the slide-out it turns back. */
    private void volumeChanged(long now){
        float fraction=Math.max(0,Math.min(1,volumeLevel/(float)volumeMax));
        if(volumeShownAt<0||now>=volumeShownUntil+VOLUME_SLIDE_MS)volumeShownAt=now;
        else if(now>=volumeShownUntil){float exit=Math.min(1,(now-volumeShownUntil)/(float)VOLUME_SLIDE_MS);volumeShownAt=now-Math.round((1-exit)*VOLUME_SLIDE_MS);}
        volumeShownUntil=now+VOLUME_SHOW_MS;
        volumeFillFrom=volumeFill(now);volumeFillTo=fraction;volumeFillAt=now;
    }
    private float volumeFill(long now){return volumeFillFrom+(volumeFillTo-volumeFillFrom)*CardMotion.ease(Math.max(0,Math.min(1,(now-volumeFillAt)/(float)VOLUME_FILL_MS)));}
    /** Horizontal offset of the bar: negative while sliding, 0 while holding, NaN while hidden. */
    private float volumeOffset(long now){
        if(!visible(WidgetSettings.VOLUME,now)||volumeShownAt<0||now<volumeShownAt)return Float.NaN;
        float travel=profile.volume().right()-profile.referenceLeft()+12;
        if(now<volumeShownUntil)return -travel*(1-CardMotion.ease(Math.min(1,(now-volumeShownAt)/(float)VOLUME_SLIDE_MS)));
        float exit=(now-volumeShownUntil)/(float)VOLUME_SLIDE_MS;
        return exit>=1?Float.NaN:-travel*CardMotion.ease(exit);
    }
    private boolean volumeVisible(long now){return !Float.isNaN(volumeOffset(now));}
    private boolean volumeAnimating(long now){float offset=volumeOffset(now);return !Float.isNaN(offset)&&(offset!=0||now-volumeFillAt<VOLUME_FILL_MS);}
    private boolean chartLive(long now){
        return widgets.enabled(WidgetSettings.VOLTAGE)&&widgets.enabled(WidgetSettings.VOLTAGE_CHART)&&live(voltageHistory,now,widgets.chartWindowMs())
            ||widgets.enabled(WidgetSettings.SPEED)&&widgets.enabled(WidgetSettings.SPEED_CHART)&&live(speedHistory,now,widgets.speedChartWindowMs())
            ||widgets.enabled(WidgetSettings.POWER)&&widgets.enabled(WidgetSettings.POWER_CHART)&&live(powerHistory,now,widgets.powerChartWindowMs());
    }
    private static boolean live(ArrayDeque<Sample> history,long now,long window){int count=0;for(Sample s:history)if(s.elapsed()>=now-window&&s.elapsed()<=now)count++;return count>=2;}
    /** Static cards only re-encode when a value expires; animations tick at 20 Hz, playing music and a live chart once a second. */
    public synchronized long revision(long now){
        sync(now);
        long tick=animating(now)?now/50:musicMoving(now)||chartLive(now)||probeShown()?now/1000:0;
        long flags=expiryFlags(now)|(hillHold(now)?1024:0)|(volumeVisible(now)?2048:0)|((long)visibleBits(now)<<12);
        // Ten conditional widgets need 22 flag bits; the tick keeps the rest of the 44 below the revision.
        return(revision<<44)^((flags&0x3FFFFFL)<<22)^(tick&0x3FFFFFL);
    }
    private static boolean expired(long elapsedTime,long now,long limit){return now-elapsedTime>limit;}
    private boolean expired(TireTelemetry.Value value,long now){return value==null||expired(value.elapsedTime(),now,widgets.tyreLimitMs());}
    private boolean expired(BatteryTelemetry.Value value,long now){return value==null||expired(value.elapsedTime(),now,widgets.voltageLimitMs());}
    private int expiryFlags(long now){
        int flags=0;
        if(widgets.showsTyres())flags|=(expired(tires.front().pressure(),now)?1:0)|(expired(tires.front().temperature(),now)?2:0)|(expired(tires.rear().pressure(),now)?4:0)|(expired(tires.rear().temperature(),now)?8:0);
        if(widgets.enabled(WidgetSettings.VOLTAGE))flags|=(voltageFromBms(now)?false:expired(battery.voltage(),now))?16:0;
        if(widgets.enabled(WidgetSettings.BMS)||widgets.enabled(WidgetSettings.VOLTAGE_FROM_BMS)||widgets.enabled(WidgetSettings.POWER_FROM_BMS))flags|=bmsFresh(now)?0:32;
        if(widgets.enabled(WidgetSettings.SPEED))flags|=speedExpired(now)?256:0;
        if(widgets.enabled(WidgetSettings.POWER))flags|=(powerFromBms(now)?false:powerExpired(now))?512:0;
        return flags;
    }
    public synchronized boolean animating(long now){sync(now);return !cards(now).isEmpty()||phoneMotion.animating(now)||musicMotion.animating(now)||voltageMotion.animating(now)||tyreMotion.animating(now)||speedMotion.animating(now)||powerMotion.animating(now)||lampMotion.animating(now)||bmsMotion.animating(now)||volumeAnimating(now);}
    public synchronized String summary(long now){return "notifications="+receiving+" visible="+timeline.entries(now).size()+" phone="+(phone!=null)+" phonePermission="+(phone!=null&&phone.getBoolean("phone_permission"))+" ageMs="+(lastUpdate==0?-1:now-lastUpdate);}
    public synchronized void draw(Canvas canvas,int width,int height,long now){
        if(!smallScreen&&!SidebarLayout.fits(width,height))return;
        SidebarLayout.Fit fit=smallScreen?null:SidebarLayout.fit(width,height);if(fit!=null)adopt(fit);
        int save=canvas.save();
        try{canvas.clipRect(0,0,width,height);
            if(smallScreen){drawSmall(canvas,width,height,now);return;}
            float scale=fit.scale();canvas.translate(fit.dx(),fit.dy());canvas.scale(scale,scale);
            List<NotificationTimeline.Entry<Card>> cards=cards(now);SidebarLayout.Stack actual=layout(cards,now);
            float dx=hillHold(now)&&actual.notificationDodged()?SidebarLayout.dodgeShift():0,dyN=actual.notificationBottom()-SidebarLayout.BOTTOM;
            drawCard(canvas,bmsMotion,now,box->drawBms(canvas,now,box));
            drawCard(canvas,lampMotion,now,box->drawLamp(canvas,box));
            drawCard(canvas,powerMotion,now,box->drawPower(canvas,now,box));
            drawCard(canvas,speedMotion,now,box->drawSpeed(canvas,now,box));
            drawCard(canvas,tyreMotion,now,box->drawTyres(canvas,now,box));
            drawCard(canvas,voltageMotion,now,box->drawVoltage(canvas,now,box));
            drawCard(canvas,musicMotion,now,box->drawMusic(canvas,now,box));
            drawCard(canvas,phoneMotion,now,box->drawPhone(canvas,box));
            drawVolume(canvas,now);
            if(probeShown())drawProbe(canvas,now);
            for(NotificationTimeline.Entry<Card> e:cards){
                float exit=e.exit(now);float w=e.data.bitmap.getWidth();SidebarLayout.Box bounds=notificationBox(e,now);float x=bounds.left()+dx,y=bounds.top()+dyN;
                paint.setAlpha(Math.round(255*Math.min(1,(now-e.born)/88f)*(1-exit)));canvas.drawBitmap(e.data.bitmap,x,y,paint);paint.setAlpha(255);
                float remaining=Math.max(0,(e.expires-now)/(float)(e.expires-e.born));paint.setColor(p.remaining());canvas.drawRect(x+12,y+SidebarLayout.NOTIFICATION_HEIGHT-3,x+12+(w-24)*remaining,y+SidebarLayout.NOTIFICATION_HEIGHT-1,paint);
            }
        }finally{paint.setAlpha(255);canvas.restoreToCount(save);}
    }
    /** The 2.4 inch panel: the backdrop fills the frame, the panel itself is laid out at 320 tall and scaled to the frame's height so it never hugs an edge. */
    private void drawSmall(Canvas c,int width,int height,long now){
        paint.setStyle(Paint.Style.FILL);paint.setColor(smallBackground==0?p.surface():smallBackground);c.drawRect(0,0,width,height,paint);
        float scale=height/SMALL_PANEL_HEIGHT;
        if(scale<=0)return;
        int save=c.save();
        try{c.scale(scale,scale);drawPanel(c,width/scale,now);}
        finally{c.restoreToCount(save);}
    }
    /** The panel's own coordinates: the speed block over a divider and up to two rows of two fields. */
    private void drawPanel(Canvas c,float width,long now){
        float top=SMALL_PANEL_HEIGHT-SMALL_LAYOUT_HEIGHT,content=SMALL_LAYOUT_HEIGHT;
        float speedHeight=content*0.56f,batteryHeight=content-speedHeight;
        String speed=speedExpired(now)?"--":String.valueOf(Math.round(ride.speedKmh()));
        smallCell(c,width*.5f,top+speedHeight*.44f,width*.94f,speed,"km/h",
                Math.min(speedHeight*.62f,width*.44f),Math.min(speedHeight*.16f,width*.07f),smallColor(0,p.text()));
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1);paint.setColor(p.border());
        c.drawLine(0,top+speedHeight+.5f,width,top+speedHeight+.5f,paint);paint.setStyle(Paint.Style.FILL);
        List<List<Integer>> rows=smallRows();
        float rowTop=top+speedHeight+batteryHeight*.32f,rowStep=batteryHeight*.40f;
        for(int i=0;i<rows.size();i++){
            List<Integer> fields=rows.get(i);if(fields.isEmpty())continue;
            float size=i==0?Math.min(batteryHeight*.40f,width*.20f):Math.min(batteryHeight*.32f,width*.16f);
            float unitSize=i==0?Math.min(batteryHeight*.15f,width*.08f):Math.min(batteryHeight*.13f,width*.07f);
            float centerY=rowTop+i*rowStep;int colour=smallColor(i+1,p.text());
            if(fields.size()==1){
                int field=fields.get(0);
                smallCell(c,width*.5f,centerY,width*.90f,smallValue(field,now),BmsCard.unit(field),size,unitSize,colour);
                continue;
            }
            for(int j=0;j<fields.size();j++){
                int field=fields.get(j);
                smallCell(c,width*(j==0?.27f:.73f),centerY,width*.44f,smallValue(field,now),BmsCard.unit(field),size,unitSize,colour);
            }
        }
    }
    /** The saved card layout clamped to what the panel can hold: two rows of at most two fields each. */
    private List<List<Integer>> smallRows(){
        ArrayList<List<Integer>> out=new ArrayList<>();
        for(List<Integer> row:bmsLayout.visibleRows()){
            if(out.size()==2)break;
            out.add(row.size()>2?List.of(row.get(0),row.get(1)):row);
        }
        return out;
    }
    private int smallColor(int row,int palette){
        int custom=smallRowColor(row);
        return custom==0?palette:custom;
    }
    /** Voltage and charge follow the chosen source; every other field only exists on the protection board. */
    private String smallValue(int field,long now){
        if(field==BmsCard.SOC&&smallSource==0){
            BatteryTelemetry.Value level=battery.percent();
            return level==null||expired(level,now)?"--":String.valueOf(Math.round(level.number()));
        }
        if(field==BmsCard.VOLTAGE&&smallSource==0){
            float volts=expired(battery.voltage(),now)?Float.NaN:battery.voltage().number();
            return Float.isNaN(volts)?"--":String.format(Locale.ROOT,"%.1f",volts);
        }
        return BmsCard.value(field,bmsFresh(now)?bms.data():null);
    }
    /** One centred cell: the value and its unit share a centre, each on its own baseline, shrunk until both fit. */
    private void smallCell(Canvas c,float centerX,float centerY,float available,String value,String unit,float size,float unitSize,int colour){
        float gap=unitSize*.25f;
        float valueWidth=measure(value,size,true);
        float total=valueWidth+(unit.isEmpty()?0:gap+measure(unit,unitSize,false));
        if(total>available&&total>0){
            float scale=available/total;size*=scale;unitSize*=scale;gap*=scale;
            valueWidth=measure(value,size,true);
            total=valueWidth+(unit.isEmpty()?0:gap+measure(unit,unitSize,false));
        }
        font(size,true);text.getFontMetrics(textMetrics);
        float valueBaseline=centerY-(textMetrics.ascent+textMetrics.descent)/2f;
        font(unitSize,false);text.getFontMetrics(textMetrics);
        float unitBaseline=centerY-(textMetrics.ascent+textMetrics.descent)/2f;
        float x=centerX-total/2f;
        write(c,value,x,valueBaseline,size,colour,true);
        if(!unit.isEmpty())write(c,unit,x+valueWidth+gap,unitBaseline,unitSize,p.unit(),false);
    }
    private interface CardPainter{void paint(SidebarLayout.Box box);}
    /** Every card is painted at its animated rectangle: translated to its top, clipped to its current size and faded by its alpha. */
    private void drawCard(Canvas c,CardMotion motion,long now,CardPainter painter){
        if(!motion.drawn(now))return;SidebarLayout.Box box=motion.box(now);if(box==null)return;
        float alpha=motion.alpha(now);if(alpha<=0)return;
        int saved=alpha<1?c.saveLayerAlpha(box.left()-1,box.top()-1,box.right()+1,box.bottom()+1,Math.round(255*alpha)):c.save();
        c.clipRect(box.left()-1,box.top()-1,box.right()+1,box.bottom()+1);c.translate(0,box.top());
        painter.paint(new SidebarLayout.Box(box.left(),0,box.right(),box.height()));
        c.restoreToCount(saved);
    }
    private Bitmap card(Bundle b){
        int width=SidebarLayout.notificationWidth(notificationWidth,halfScreen),height=(int)SidebarLayout.NOTIFICATION_HEIGHT;Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);bitmap.setDensity(Bitmap.DENSITY_NONE);Canvas c=new Canvas(bitmap);surface(c,0,0,width,height,12);
        paint.setColor(p.accent());c.drawRoundRect(0,12,2,48,1,1,paint);
        Bitmap icon=b.getParcelable("icon",Bitmap.class);if(icon!=null&&!icon.isRecycled())c.drawBitmap(icon,null,new RectF(12,12,48,48),paint);else{paint.setColor(p.iconBox());c.drawRoundRect(12,12,48,48,10,10,paint);write(c,"N",23,37,18,p.iconGlyph(),true);}
        String app=fit(b.getString("app",""),Math.min(120,(width-80)*.4f),13,false);float appWidth=measure(app,13,false);write(c,app,width-12-appWidth,22,13,p.label(),false);
        String title=b.getString("title","");if(title.isEmpty())title=b.getString("app","");write(c,fit(title,width-appWidth-76,17,true),58,26,17,p.text(),true);
        write(c,fit(b.getString("text",""),width-70,15,false),58,48,15,p.body(),false);return bitmap;
    }
    private void surface(Canvas c,float x,float y,float w,float h,float radius){paint.setStyle(Paint.Style.FILL);paint.setColor(p.surface());c.drawRoundRect(x,y,x+w,y+h,radius,radius,paint);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1);paint.setColor(p.border());c.drawRoundRect(x+.5f,y+.5f,x+w-.5f,y+h-.5f,radius,radius,paint);paint.setStyle(Paint.Style.FILL);}
    private void drawMusic(Canvas c,long now,SidebarLayout.Box box){
        boolean idle=!musicActive();
        float left=box.left(),contentLeft=left+12,contentRight=box.right()-12,contentWidth=contentRight-contentLeft;
        surface(c,left,0,box.right()-left,SidebarLayout.MUSIC_HEIGHT,11);
        int state=idle?0:music.getInt("state");float headerCenter=MUSIC_HEADER,artTop=MUSIC_ART_TOP,art=MUSIC_ART;
        int save=c.save();Path clip=new Path();clip.addRoundRect(contentLeft,artTop,contentLeft+art,artTop+art,8,8,Path.Direction.CW);c.clipPath(clip);
        // surface() ends with the translucent border paint; artwork must be drawn at full opacity.
        paint.setAlpha(255);
        if(!idle&&artwork!=null&&!artwork.isRecycled())c.drawBitmap(artwork,null,new RectF(contentLeft,artTop,contentLeft+art,artTop+art),paint);
        else{paint.setColor(p.artBox());c.drawRect(contentLeft,artTop,contentLeft+art,artTop+art,paint);centerGlyph(c,"♪",contentLeft+art/2,headerCenter,28,p.artGlyph(),true);}
        if(state==2){
            float centerX=contentLeft+art/2;
            paint.setColor(p.pauseBackdrop());c.drawCircle(centerX,headerCenter,11,paint);
            paint.setColor(p.pauseGlyph());
            c.drawRoundRect(centerX-5,headerCenter-6,centerX-2,headerCenter+6,1,1,paint);
            c.drawRoundRect(centerX+2,headerCenter-6,centerX+5,headerCenter+6,1,1,paint);
        }c.restoreToCount(save);
        float labelsLeft=contentLeft+art+8,labelsWidth=contentRight-labelsLeft;
        centerLine(c,fit(idle?"无播放":musicTitle(),labelsWidth,13,true),labelsLeft,idle?headerCenter:headerCenter-8,13,p.text(),true);
        if(!idle)centerLine(c,fit(musicArtist(),labelsWidth,10,false),labelsLeft,headerCenter+9,10,p.label(),false);
        long duration=idle?0:music.getLong("duration");
        long position=idle?-1:MusicPlayback.position(music.getLong("position",-1),duration,state,music.getFloat("speed"),music.getLong("updated"),now);
        float fraction=duration>0&&position>=0?Math.min(1,position/(float)duration):0;
        bar(c,contentLeft,MUSIC_BAR_Y,contentWidth,2,fraction);centerLine(c,MusicPlayback.time(position),contentLeft,MUSIC_TIME_Y,9,p.label(),false);
        String end=duration>0?MusicPlayback.time(duration):"--:--";centerLine(c,end,contentRight-measure(end,9,false),MUSIC_TIME_Y,9,p.label(),false);
    }
    private String musicTitle(){String title=music==null?"":music.getString("title","");return title.isEmpty()?"未知曲目":title;}
    private String musicArtist(){String artist=music==null?"":music.getString("artist","");return artist.isEmpty()?"未知歌手":artist;}
    /** The music card keeps the full sidebar width; title and artist are ellipsized inside it. */
    private float musicWidth(){return SidebarLayout.WIDTH;}
    /** One row: `前 2.4 bar 29 ℃  后 2.6 bar 30 ℃` across the full sidebar width; an overlong row is squeezed rather than clipped. */
    private static final float TYRE_SEPARATOR=8,TYRE_LABEL=11,TYRE_VALUE=14,TYRE_UNIT=9;
    private void drawTyres(Canvas c,long now,SidebarLayout.Box box){
        float left=box.left();surface(c,left,0,box.right()-left,SidebarLayout.TYRE_HEIGHT,11);
        float centerY=SidebarLayout.TYRE_HEIGHT/2,available=box.width()-2*TYRE_INSET,needed=tyreContentWidth(now);
        int saved=c.save();
        if(needed>available&&needed>0){c.translate(left+TYRE_INSET,0);c.scale(available/needed,1);c.translate(-(left+TYRE_INSET),0);}
        float x=left+TYRE_INSET;boolean first=true;
        if(widgets.enabled(WidgetSettings.TYRE_FRONT)){x=tyreWheel(c,"前",tires.front(),now,x,centerY);first=false;}
        if(widgets.enabled(WidgetSettings.TYRE_REAR)){if(!first)x+=TYRE_SEPARATOR;tyreWheel(c,"后",tires.rear(),now,x,centerY);}
        c.restoreToCount(saved);
    }
    private float tyreWheel(Canvas c,String label,TireTelemetry.Wheel wheel,long now,float x,float centerY){
        String pressure=expired(wheel.pressure(),now)?"--":TireTelemetry.pressure(wheel.pressure());
        String temperature=expired(wheel.temperature(),now)?"--":TireTelemetry.temperature(wheel.temperature());
        centerLine(c,label,x,centerY,TYRE_LABEL,p.label(),false);x+=measure(label,TYRE_LABEL,false)+3;
        centerLine(c,pressure,x,centerY,TYRE_VALUE,p.text(),true);x+=measure(pressure,TYRE_VALUE,true)+2;
        centerLine(c,"bar",x,centerY,TYRE_UNIT,p.unit(),false);x+=measure("bar",TYRE_UNIT,false)+4;
        centerLine(c,temperature,x,centerY,TYRE_VALUE,p.text(),true);x+=measure(temperature,TYRE_VALUE,true)+1;
        centerLine(c,"℃",x,centerY,TYRE_UNIT,p.unit(),false);return x+measure("℃",TYRE_UNIT,false);
    }
    /** Width the enabled wheels actually need right now, used only to squeeze an overlong row. */
    private float tyreContentWidth(long now){
        float total=0;boolean first=true;
        if(widgets.enabled(WidgetSettings.TYRE_FRONT)){total+=wheelWidth("前",tires.front(),now);first=false;}
        if(widgets.enabled(WidgetSettings.TYRE_REAR)){if(!first)total+=TYRE_SEPARATOR;total+=wheelWidth("后",tires.rear(),now);}
        return total;
    }
    private float wheelWidth(String label,TireTelemetry.Wheel wheel,long now){
        String pressure=expired(wheel.pressure(),now)?"--":TireTelemetry.pressure(wheel.pressure()),temperature=expired(wheel.temperature(),now)?"--":TireTelemetry.temperature(wheel.temperature());
        return measure(label,TYRE_LABEL,false)+3+measure(pressure,TYRE_VALUE,true)+2+measure("bar",TYRE_UNIT,false)+4+measure(temperature,TYRE_VALUE,true)+1+measure("℃",TYRE_UNIT,false);
    }
    private float tyreWidth(){return SidebarLayout.WIDTH;}
    /** Label, large value and unit on the first row; below it the optional curve over the configured chart window. */
    private void drawMetric(Canvas c,long now,SidebarLayout.Box box,String label,String value,String unit,ArrayDeque<Sample> history,boolean chart,long window,int decimals,float minSpan){
        float left=box.left(),contentLeft=left+INSET,contentRight=box.right()-INSET;
        surface(c,left,0,box.right()-left,SidebarLayout.metricHeight(chart),11);
        float centerY=SidebarLayout.VOLTAGE_HEIGHT/2;
        centerLine(c,label,contentLeft,centerY,13,p.label(),false);
        centerLine(c,value,contentLeft+VALUE_COLUMN,centerY,17,p.text(),true);
        centerLine(c,unit,contentLeft+VALUE_COLUMN+measure(value,17,true)+4,centerY,11,p.unit(),false);
        if(chart)drawChart(c,now,history,window,decimals,minSpan,contentLeft,30,contentRight,SidebarLayout.VOLTAGE_CHART_HEIGHT-6);
    }
    private void drawVoltage(Canvas c,long now,SidebarLayout.Box box){
        BatteryTelemetry.Value voltage=battery.voltage();
        drawMetric(c,now,box,"电压",voltageFromBms(now)?String.format(Locale.ROOT,"%.1f",bms.data().volts()):expired(voltage,now)?"--":BatteryTelemetry.voltage(voltage),"V",voltageHistory,widgets.enabled(WidgetSettings.VOLTAGE_CHART),widgets.chartWindowMs(),1,0.5f);
    }
    private void drawSpeed(Canvas c,long now,SidebarLayout.Box box){
        drawMetric(c,now,box,"速度",speedExpired(now)?"--":String.format(Locale.ROOT,"%.1f",ride.speedKmh()),"km/h",speedHistory,widgets.enabled(WidgetSettings.SPEED_CHART),widgets.speedChartWindowMs(),1,1f);
    }
    private void drawPower(Canvas c,long now,SidebarLayout.Box box){
        drawMetric(c,now,box,"功率",powerFromBms(now)?String.valueOf(bms.data().watts()):powerExpired(now)?"--":String.valueOf(ride.power()),"W",powerHistory,widgets.enabled(WidgetSettings.POWER_CHART),widgets.powerChartWindowMs(),0,50f);
    }
    /** Height the device reported (or, for the ESC, was last sent), 已连接 for a timed lift that has none, otherwise 未连接. */
    private void drawLamp(Canvas c,SidebarLayout.Box box){
        float left=box.left(),contentLeft=left+INSET;boolean known=lamp.knownPosition()&&lampPercent>=0;
        surface(c,left,0,box.right()-left,SidebarLayout.LAMP_HEIGHT,11);
        // The brightness fills the card from the left behind the text: half the width at 50 %, so the card body reads as a level.
        if(known&&lampPercent>0){
            float frac=Math.min(1,lampPercent/100f);int save=c.save();
            Path clip=new Path();clip.addRoundRect(left,0,box.right(),SidebarLayout.LAMP_HEIGHT,11,11,Path.Direction.CW);c.clipPath(clip);
            paint.setColor(p.chartFill());c.drawRect(left,0,left+(box.right()-left)*frac,SidebarLayout.LAMP_HEIGHT,paint);
            c.restoreToCount(save);
        }
        float centerY=SidebarLayout.LAMP_HEIGHT/2;
        centerLine(c,"大灯",contentLeft,centerY,13,p.label(),false);
        String value=lampText();
        centerLine(c,value,contentLeft+VALUE_COLUMN,centerY,known?17:13,known?p.text():p.unit(),known);
        if(known)centerLine(c,"%",contentLeft+VALUE_COLUMN+measure(value,17,true)+4,centerY,11,p.unit(),false);
    }
    /** The BMS card: its rows from the layout while a fresh reading exists, otherwise one line reading 未连接. */
    private void drawBms(Canvas c,long now,SidebarLayout.Box box){bmsPainter.draw(c,p,box.left(),box.width(),bmsLayout,bms.data(),bmsFresh(now));}
    private float bmsHeight(long now){return bmsPainter.height(bmsLayout,bmsFresh(now));}
    private String lampText(){return lamp.knownPosition()&&lampPercent>=0?String.valueOf(lampPercent):lamp.ready()?"已连接":"未连接";}
    private float lampWidth(){
        boolean known=lamp.knownPosition()&&lampPercent>=0;String value=lampText();
        return 2*INSET+VALUE_COLUMN+measure(value,known?17:13,known)+(known?4+measure("%",11,false):0);
    }
    private boolean speedExpired(long now){return ride==null||ride.speedAt()==0||ride.speedTenths()<0||now-ride.speedAt()>widgets.speedLimitMs();}
    private boolean powerExpired(long now){return ride==null||!ride.hasPower()||now-ride.powerAt()>widgets.powerLimitMs();}
    private void drawChart(Canvas c,long now,ArrayDeque<Sample> history,long window,int decimals,float minSpan,float left,float top,float right,float bottom){
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1);paint.setColor(p.divider());c.drawLine(left,bottom+.5f,right,bottom+.5f,paint);paint.setStyle(Paint.Style.FILL);
        float min=Float.MAX_VALUE,max=-Float.MAX_VALUE;int count=0;long start=now-window;
        for(Sample s:history){if(s.elapsed()<start||s.elapsed()>now)continue;count++;min=Math.min(min,s.value());max=Math.max(max,s.value());}
        if(count<2){centerLine(c,"曲线采样中",left,(top+bottom)/2,9,p.unit(),false);return;}
        float span=Math.max(minSpan,max-min),lo=(min+max)/2-span*.6f,hi=(min+max)/2+span*.6f;
        chartLine.reset();chartFill.reset();boolean started=false;float lastX=left;
        for(Sample s:history){if(s.elapsed()<start||s.elapsed()>now)continue;
            float x=left+(s.elapsed()-start)/(float)window*(right-left),y=bottom-(s.value()-lo)/(hi-lo)*(bottom-top);
            if(!started){chartLine.moveTo(x,y);chartFill.moveTo(x,bottom);chartFill.lineTo(x,y);started=true;}else{chartLine.lineTo(x,y);chartFill.lineTo(x,y);}lastX=x;}
        chartFill.lineTo(lastX,bottom);chartFill.close();
        paint.setColor(p.chartFill());c.drawPath(chartFill,paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.5f);paint.setStrokeJoin(Paint.Join.ROUND);paint.setColor(p.accent());c.drawPath(chartLine,paint);paint.setStyle(Paint.Style.FILL);
        String pattern=decimals==0?"%.0f":"%.1f",high=String.format(Locale.ROOT,pattern,max),low=String.format(Locale.ROOT,pattern,min);
        write(c,high,right-measure(high,8,false),top+7,8,p.unit(),false);write(c,low,right-measure(low,8,false),bottom-1,8,p.unit(),false);
    }
    private float metricWidth(boolean chart,String template,String unit){return chart?SidebarLayout.WIDTH:2*INSET+VALUE_COLUMN+measure(template,17,true)+4+measure(unit,11,false);}
    private float voltageWidth(){return metricWidth(widgets.enabled(WidgetSettings.VOLTAGE_CHART),"888.8","V");}
    private float speedWidth(){return metricWidth(widgets.enabled(WidgetSettings.SPEED_CHART),"88.8","km/h");}
    private float powerWidth(){return metricWidth(widgets.enabled(WidgetSettings.POWER_CHART),"-8888","W");}
    /** Vertical level bar above the dashboard speaker icon; slides in from the left after a volume change, never a touch target. */
    private void drawVolume(Canvas c,long now){
        float offset=volumeOffset(now);if(Float.isNaN(offset))return;SidebarLayout.Box b=profile.volume();
        int saved=c.save();c.translate(offset,0);
        float radius=b.width()/2;surface(c,b.left(),b.top(),b.width(),b.height(),radius);
        float inset=4,fraction=volumeFill(now);
        float innerRadius=(b.width()-2*inset)/2,fillTop=b.bottom()-inset-(b.height()-2*inset)*fraction;
        paint.setColor(p.track());c.drawRoundRect(b.left()+inset,b.top()+inset,b.right()-inset,b.bottom()-inset,innerRadius,innerRadius,paint);
        if(fraction>0){paint.setColor(p.accent());c.drawRoundRect(b.left()+inset,Math.min(fillTop,b.bottom()-inset-2*innerRadius),b.right()-inset,b.bottom()-inset,innerRadius,innerRadius,paint);}
        String label=Math.round(volumeFillTo*100)+"%";write(c,label,(b.left()+b.right())/2-measure(label,12,true)/2,b.top()-8,12,p.text(),true);
        c.restoreToCount(saved);
    }
    /** Debug overlay at the left: register name, last bytes and seconds since the reply. Amber while a read is in flight, green when the value changed within three seconds, grey when unanswered. Display only. */
    private static final float PROBE_LEFT=6,PROBE_TOP=28,PROBE_COLUMN=204,PROBE_ROW=15;private static final int PROBE_ROWS_PER_COLUMN=29;
    private void drawProbe(Canvas c,long now){
        List<RegisterProbe.Row> all=probeRows;int capacity=2*PROBE_ROWS_PER_COLUMN;
        // More registers than fit (raw index scans): show the ones that changed most recently, then the most recently answered.
        List<RegisterProbe.Row> rows=all.size()>capacity?RegisterProbe.prioritized(all,capacity):all;
        int columns=Math.max(1,(rows.size()+PROBE_ROWS_PER_COLUMN-1)/PROBE_ROWS_PER_COLUMN),perColumn=(rows.size()+columns-1)/columns;
        surface(c,PROBE_LEFT,PROBE_TOP,columns*PROBE_COLUMN+6,16+perColumn*PROBE_ROW+6,8);
        String header=all.size()>capacity?"寄存器探测 "+all.size()+"（有回复 "+RegisterProbe.replied(all)+"，3 秒内变化 "+RegisterProbe.changed(all,now,RegisterProbe.CHANGE_HIGHLIGHT_MS)+"；按变化时间排序）":"寄存器探测 "+all.size();
        centerLine(c,header,PROBE_LEFT+8,PROBE_TOP+9,9,p.label(),false);
        for(int i=0;i<rows.size();i++){
            RegisterProbe.Row row=rows.get(i);RegisterProbe.Value v=row.value();int column=i/perColumn,line=i%perColumn;
            float x=PROBE_LEFT+6+column*PROBE_COLUMN,y=PROBE_TOP+18+line*PROBE_ROW+PROBE_ROW/2;
            boolean pending=v!=null&&v.pending(),changed=v!=null&&v.changedWithin(now,RegisterProbe.CHANGE_HIGHLIGHT_MS),silent=v!=null&&v.silent();
            int color=pending?p.probePending():changed?p.accent():silent?p.unit():p.text();
            if(pending){paint.setStyle(Paint.Style.FILL);paint.setColor(p.probePending());c.drawCircle(x+2,y,2,paint);}
            centerLine(c,row.name(),x+8,y,10,pending?p.probePending():changed?p.accent():p.label(),false);
            centerLine(c,v==null||v.repliedAt()==0?"--":v.hex(),x+112,y,11,color,true);
            String age=v==null?"":v.repliedAt()>0?(now-v.repliedAt())/1000+"s":silent?"无回复":"…";
            centerLine(c,age,x+PROBE_COLUMN-10-measure(age,9,false),y,9,silent?p.unit():p.label(),false);
        }
    }
    /** Right-anchored phone layout shared by drawing and sizing: percentage, battery, Wi-Fi/network slot, then the SIMs. */
    private record PhoneLayout(float width,float simsX,float slotX,float batteryX,float percentLeft,boolean slot,int count,boolean known,String percent){}
    private static final float PERCENT_INSET=10,BATTERY_GAP=6,BATTERY_WIDTH=27,SLOT_WIDTH=22;
    private PhoneLayout phoneLayout(float right){
        ArrayList<Integer> slots=phone.getIntegerArrayList("slots");boolean known=phone.getBoolean("phone_permission");int count=slots==null?0:Math.min(2,slots.size());
        boolean slot=phone.getBoolean("wifi")||!phone.getString("network","").isEmpty();
        int level=phone.getInt("battery",-1);String percent=level<0?"--%":level+"%";
        float percentLeft=right-PERCENT_INSET-measure(percent,14,true),batteryX=percentLeft-BATTERY_GAP-BATTERY_WIDTH,slotX=batteryX-8-SLOT_WIDTH;
        float signals=known&&count>0?count*32+(count-1)*6:24,simsX=(slot?slotX-6:batteryX-8)-signals,content=right-simsX+12;
        // Two SIMs fill the sidebar width like the other cards, the SIMs starting at the left edge; fewer elements keep a compact card.
        float width=count==2?Math.max(content,SidebarLayout.WIDTH):content;
        if(width>content)simsX=right-width+12;
        return new PhoneLayout(width,simsX,slotX,batteryX,percentLeft,slot,count,known,percent);
    }
    private float phoneWidth(){return phone==null?0:phoneLayout(SIDEBAR_RIGHT).width();}
    private SidebarLayout.Sizes sizes(){return new SidebarLayout.Sizes(phoneWidth(),musicWidth(),voltageWidth(),tyreWidth(),speedWidth(),powerWidth(),lampWidth(),SidebarLayout.WIDTH,bmsHeight(android.os.SystemClock.elapsedRealtime()));}
    private void bar(Canvas c,float x,float y,float width,float height,float fraction){paint.setStyle(Paint.Style.FILL);paint.setColor(p.track());c.drawRoundRect(x,y,x+width,y+height,height/2,height/2,paint);paint.setColor(p.accent());c.drawRoundRect(x,y,x+width*Math.max(0,Math.min(1,fraction)),y+height,height/2,height/2,paint);}
    /** Display-only HUD consumes touches so they cannot reach the application behind it; cards use their settled targets. */
    public synchronized Bundle touch(float x,float y,int width,int height,long now){
        Bundle hit=new Bundle();hit.putString("command","block");
        if(smallScreen)return x>=0&&y>=0&&x<width&&y<height?hit:null;
        if(!SidebarLayout.fits(width,height))return null;
        SidebarLayout.Fit fit=SidebarLayout.fit(width,height);adopt(fit);float scale=fit.scale();x=(x-fit.dx())/scale;y=(y-fit.dy())/scale;
        List<NotificationTimeline.Entry<Card>> cards=cards(now);SidebarLayout.Stack stack=layout(cards,now);
        float dx=hillHold(now)&&stack.notificationDodged()?SidebarLayout.dodgeShift():0,dyN=stack.notificationBottom()-SidebarLayout.BOTTOM;
        for(NotificationTimeline.Entry<Card> e:cards)if(notificationBox(e,now).contains(x-dx,y-dyN))return hit;
        for(SidebarLayout.Box box:new SidebarLayout.Box[]{stack.phone(),stack.music(),stack.voltage(),stack.tyres(),stack.speed(),stack.power(),stack.lamp(),stack.bms()})if(box!=null&&box.contains(x,y))return hit;
        return null;
    }
    private SidebarLayout.Box notificationBox(NotificationTimeline.Entry<Card> entry,long now){return SidebarLayout.notification(entry.data.bitmap.getWidth(),entry.slot(now),entry.enter(now),entry.exit(now));}
    private float lift(List<NotificationTimeline.Entry<Card>> cards,long now){float height=0;for(NotificationTimeline.Entry<Card> entry:cards)height=Math.max(height,SidebarLayout.occupied(notificationBox(entry,now)));return height;}
    /**
     * The lifted layout is the motion target for every card. While the notification lift changes, each frame retargets the motions
     * from their current positions, so cards trail the notification on one continuous eased path, column changes and hill-hold
     * dodges included, with no jump when a card switches columns.
     */
    private SidebarLayout.Stack layout(List<NotificationTimeline.Entry<Card>> cards,long now){
        float lift=lift(cards,now);boolean hold=hillHold(now);
        SidebarLayout.Stack actual=SidebarLayout.arrange(widgets,visibleMask(now),lift,halfScreen?SidebarLayout.fullWidth(bmsHeight(now)):sizes(),hold,occlusions(),halfScreen);
        phoneMotion.target(actual.phone(),now);musicMotion.target(actual.music(),now);voltageMotion.target(actual.voltage(),now);tyreMotion.target(actual.tyres(),now);speedMotion.target(actual.speed(),now);powerMotion.target(actual.power(),now);lampMotion.target(actual.lamp(),now);bmsMotion.target(actual.bms(),now);
        actualStack=actual;return actual;
    }
    private void sync(long now){layout(cards(now),now);}
    /** Settled card rectangles including the notification lift, for tests and diagnostics; touch uses the same boxes. */
    public synchronized SidebarLayout.Stack stack(long now){return layout(cards(now),now);}
    private void drawPhone(Canvas c,SidebarLayout.Box box){
        if(phone==null)return;
        paint.setStrokeCap(Paint.Cap.ROUND);
        ArrayList<Integer> slots=phone.getIntegerArrayList("slots"),levels=phone.getIntegerArrayList("levels");boolean wifi=phone.getBoolean("wifi");String network=phone.getString("network","");
        // Everything anchors to the card's own right edge, so a card that slides away from the dashboard toast takes its text with it.
        PhoneLayout l=phoneLayout(box.right());int count=l.count();
        float y=0;surface(c,box.left(),y,box.right()-box.left(),28,11);
        float x=l.simsX();
        if(!l.known()||count==0){write(c,l.known()?"无卡":"?",x,y+19,11,p.dim(),false);x+=24;}else for(int i=0;i<count;i++){
            write(c,String.valueOf(slots.get(i)),x,y+20,11,p.dim(),false);int signal=levels!=null&&i<levels.size()?levels.get(i):-1;
            for(int bar=0;bar<4;bar++){paint.setColor(bar<signal?p.icon():p.signalOff());c.drawRoundRect(x+10+bar*6,y+22-(bar+1)*4,x+14+bar*6,y+22,1,1,paint);}if(signal<0)write(c,"?",x+16,y+15,10,p.icon(),false);x+=32+(i<count-1?6:0);
        }
        if(wifi)drawWifi(c,l.slotX()+(SLOT_WIDTH-19)/2,y+4.5f);else if(l.slot())centerGlyph(c,network,l.slotX()+SLOT_WIDTH/2,y+14,13,p.icon(),true);
        x=l.batteryX();int level=phone.getInt("battery",-1);boolean charging=phone.getBoolean("charging");int color=charging?p.batteryCharging():level>=0&&level<=20?p.batteryLow():p.icon();
        paint.setColor(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.5f);c.drawRoundRect(x+1,y+7,x+24,y+21,3,3,paint);c.drawLine(x+26,y+11,x+26,y+17,paint);paint.setStyle(Paint.Style.FILL);if(level>0)c.drawRoundRect(x+3.5f,y+9.5f,x+3.5f+18*Math.min(100,level)/100f,y+18.5f,1,1,paint);
        if(charging){Path bolt=new Path();bolt.moveTo(x+14,y+6);bolt.lineTo(x+8,y+15);bolt.lineTo(x+12,y+15);bolt.lineTo(x+11,y+22);bolt.lineTo(x+18,y+12);bolt.lineTo(x+13,y+12);bolt.close();paint.setColor(p.boltOutline());paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);c.drawPath(bolt,paint);paint.setStyle(Paint.Style.FILL);paint.setColor(p.bolt());c.drawPath(bolt,paint);}
        write(c,l.percent(),l.percentLeft(),y+19,14,p.percent(),true);
    }
    /** Same three arcs and dot as i-wifi in the HTML prototype, in its 24 by 24 viewBox. */
    private static Path wifiGlyph(){
        Path p=new Path();wifiArc(p,16,10,8);wifiArc(p,11,6.5f,11.5f);wifiArc(p,5,3,15);return p;
    }
    private static void wifiArc(Path path,float radius,float halfChord,float endY){
        float centerY=endY+(float)Math.sqrt(radius*radius-halfChord*halfChord);
        float angle=(float)Math.toDegrees(Math.asin(halfChord/radius));
        path.addArc(12-radius,centerY-radius,12+radius,centerY+radius,270-angle,angle*2);
    }
    private void drawWifi(Canvas c,float x,float y){
        int save=c.save();c.translate(x,y);c.scale(19f/24,19f/24);
        paint.setColor(p.icon());paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);paint.setStrokeCap(Paint.Cap.ROUND);
        c.drawPath(wifiGlyph,paint);paint.setStyle(Paint.Style.FILL);c.drawCircle(12,19,1,paint);c.restoreToCount(save);
    }
    private void font(float size,boolean bold){
        text.setTextSize(size);
        Typeface face=fixedTypeface;
        text.setTypeface(face==null?(bold?Typeface.create("sans-serif",Typeface.BOLD):Typeface.create("sans-serif",Typeface.NORMAL))
                :Typeface.create(face,bold?Typeface.BOLD:Typeface.NORMAL));
    }
    private String fit(String s,float width,float size,boolean bold){font(size,bold);return TextUtils.ellipsize(s,text,Math.max(0,width),TextUtils.TruncateAt.END).toString();}
    private float measure(String s,float size,boolean bold){font(size,bold);return text.measureText(s);}
    private void write(Canvas c,String s,float x,float y,float size,int color,boolean bold){font(size,bold);text.setColor(color);c.drawText(s,x,y,text);}
    private void centerLine(Canvas c,String s,float x,float centerY,float size,int color,boolean bold){font(size,bold);text.getFontMetrics(textMetrics);text.setColor(color);c.drawText(s,x,centerY-(textMetrics.ascent+textMetrics.descent)/2,text);}
    private void centerGlyph(Canvas c,String s,float centerX,float centerY,float size,int color,boolean bold){font(size,bold);text.getTextBounds(s,0,s.length(),glyphBounds);text.setColor(color);c.drawText(s,centerX-glyphBounds.exactCenterX(),centerY-glyphBounds.exactCenterY(),text);}
}
