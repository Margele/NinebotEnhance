import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Looper;
import dev.ichinomiya.ninebotenhance.notification.DashboardHud;
import dev.ichinomiya.ninebotenhance.core.TireTelemetry;
import dev.ichinomiya.ninebotenhance.core.BatteryTelemetry;
import dev.ichinomiya.ninebotenhance.core.WidgetSettings;
import dev.ichinomiya.ninebotenhance.core.WidgetCondition;
import dev.ichinomiya.ninebotenhance.core.NotificationTimeline;
import dev.ichinomiya.ninebotenhance.core.RegisterProbe;
import dev.ichinomiya.ninebotenhance.core.RideState;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;

/** Runs the built APK's real Android Canvas renderer with synthetic data, without posting notifications. */
public final class HudSmoke {
    private static final int BACKGROUND=0xff343e46,ART=0xff3079c1,ACCENT=0xff7cd6a4;
    private static int checks;
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }
    static Bundle state(long cursor, Bundle... events) {
        Bundle phone=new Bundle();phone.putInt("battery",46);phone.putBoolean("charging",true);
        phone.putBoolean("wifi",true);phone.putBoolean("phone_permission",true);
        phone.putIntegerArrayList("slots",new ArrayList<>(Arrays.asList(1,2)));
        phone.putIntegerArrayList("levels",new ArrayList<>(Arrays.asList(4,3)));
        Bundle state=new Bundle();state.putString("epoch","smoke");state.putLong("cursor",cursor);
        state.putBoolean("enabled",true);state.putBundle("phone",phone);
        Bundle music=new Bundle();music.putBoolean("granted",true);music.putBoolean("active",true);music.putString("media_session","synthetic-player");
        music.putString("title","夜空中的星");music.putString("artist","示例歌手");music.putInt("state",3);music.putLong("position",65000);music.putLong("duration",240000);music.putFloat("speed",1);music.putLong("updated",100000);
        Bitmap art=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888);art.eraseColor(ART);music.putParcelable("art",art);music.putLong("art_revision",1);state.putBundle("music",music);
        state.putParcelableArrayList("events",new ArrayList<>(Arrays.asList(events)));return state;
    }
    private static Bundle event(int seq,long now) {
        Bundle b=new Bundle();b.putString("key","same-app-same-notification-id");b.putLong("seq",seq);
        b.putLong("posted",now);b.putInt("duration",15000);b.putString("app","同一 App");
        b.putString("title","第 "+seq+" 条通知");b.putString("text","每条通知独立显示和计时");return b;
    }
    static TireTelemetry.Snapshot tireState(){
        TireTelemetry t=new TireTelemetry();t.select("synthetic-vehicle");
        t.update("synthetic-vehicle",true,2.4f,29f,TireTelemetry.Source.BLUETOOTH,1789453320000L,99000);
        t.update("synthetic-vehicle",false,2.6f,30f,TireTelemetry.Source.BLUETOOTH,1789453320000L,99000);
        return t.snapshot();
    }
    static BatteryTelemetry.Snapshot batteryState(){
        BatteryTelemetry b=new BatteryTelemetry();b.select("synthetic-vehicle");
        b.update("synthetic-vehicle",BatteryTelemetry.decode("rVoltage",new byte[]{0x3f,0x1c}),BatteryTelemetry.Source.BLUETOOTH,1789453320000L,99000);
        return b.snapshot();
    }
    private static Bitmap frame(DashboardHud hud,long now) {
        Bitmap image=Bitmap.createBitmap(848,480,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(image);canvas.drawColor(BACKGROUND);hud.draw(canvas,848,480,now);return image;
    }
    private static boolean solid(Bitmap image,int left,int top,int right,int bottom,int color){
        for(int y=top;y<bottom;y++)for(int x=left;x<right;x++)if(image.getPixel(x,y)!=color)return false;
        return true;
    }
    private static boolean blocks(DashboardHud hud,float x,float y,long now){Bundle hit=hud.touch(x,y,848,480,now);return hit!=null&&"block".equals(hit.getString("command"));}
    private static boolean bright(Bitmap image,int left,int top,int right,int bottom){
        for(int y=top;y<bottom;y++)for(int x=left;x<right;x++){int p=image.getPixel(x,y);if(Color.red(p)>160&&Color.green(p)>160)return true;}
        return false;
    }
    private static boolean shade(Bitmap image,int colour,int left,int top,int right,int bottom){
        int r=Color.red(colour),g=Color.green(colour),b=Color.blue(colour);
        for(int y=top;y<bottom;y++)for(int x=left;x<right;x++){
            int p=image.getPixel(x,y);
            if(Math.abs(Color.red(p)-r)<14&&Math.abs(Color.green(p)-g)<14&&Math.abs(Color.blue(p)-b)<14)return true;
        }
        return false;
    }
    private static int lit(Bitmap image,int left,int top,int right,int bottom){
        int count=0;
        for(int y=top;y<bottom;y++)for(int x=left;x<right;x++){int p=image.getPixel(x,y);if(Color.red(p)>160&&Color.green(p)>160&&Color.blue(p)>160)count++;}
        return count;
    }
    /** Same font as the HUD percentage, so the tests can locate the right-anchored phone elements. */
    private static float percentWidth(String percent){android.graphics.Paint p=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);p.setTextSize(14);p.setTypeface(android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.BOLD));return p.measureText(percent);}
    public static void main(String[] args) {
        try { run(args); } catch(Throwable error) { error.printStackTrace(System.out);System.exit(1); }
    }
    private static void run(String[] args) throws Exception {
        Looper.prepareMainLooper();
        // app_process does not get the font-map setup normally performed during application binding.
        android.graphics.Typeface.class.getDeclaredMethod("setSystemFontMap",android.os.SharedMemory.class)
                .invoke(null,new Object[]{null});
        NotificationContentSmoke.run();
        DashboardHud hud=new DashboardHud();hud.reset("test");
        hud.accept("test",state(0),100000);
        Bitmap missingTires=frame(hud,100000);hud.acceptTires(tireState());
        Bitmap missingBattery=frame(hud,100000);hud.acceptBattery(batteryState());
        Bitmap musicFrame=frame(hud,100000);
        var live=hud.stack(100000);int musicTop=(int)live.music().top(),artX=(int)live.music().left()+12+24,artLeft=(int)live.music().left()+12,musicMid=musicTop+32;
        int tyreTop=(int)live.tyres().top(),voltageTop=(int)live.voltage().top();
        check(live.music().right()==838&&live.music().left()==648&&live.music().height()==84,"a playing card keeps the full sidebar width and the chart height");
        float batteryX=838-10-percentWidth("46%")-6-27,wifiCenterX=batteryX-8-22+11;int wifiColumn=Math.round(wifiCenterX);
        check(live.phone().left()==648&&live.phone().right()==838,"two SIMs fill the full sidebar width like the other cards");
        check(bright(musicFrame,655,447,672,462)&&!bright(musicFrame,649,447,655,462),"with two SIMs the first SIM starts at the left edge of the card");
        check(live.music().bottom()+6==live.phone().top()&&live.tyres().bottom()+6==live.music().top()&&live.voltage().right()==633&&live.voltage().bottom()==468,"music and tyres stack above the phone status with six-pixel gaps; the chart voltage would reach into the instrument and moves to the left column");
        check(live.voltage().height()==84&&live.tyres().height()==28&&live.voltage().top()==384&&live.tyres().top()==316,"the chart card is 84 high and the tyre line 28, the right column topping out at 316");
        check(live.voltage().left()==443&&live.tyres().left()==648&&live.tyres().right()==838,"the chart card and the tyre line both span the full sidebar width");
        check(!musicFrame.sameAs(missingTires),"tyre line switches from unknown fields to actual measurements");
        check(!musicFrame.sameAs(missingBattery),"voltage row switches from its placeholder to the decoded reading");
        check(blocks(hud,820,tyreTop+14,100000)&&hud.touch(820,tyreTop-3,848,480,100000)==null&&blocks(hud,550,voltageTop+45,100000)&&hud.touch(700,300,848,480,100000)==null,"tyre and voltage cards only consume touches inside their rectangles");
        check(hud.revision(104000)!=hud.revision(104001),"a voltage older than five read intervals expires and advances the output revision");
        check(!frame(hud,104000).sameAs(frame(hud,104001)),"an expired voltage is drawn as unknown");
        check(musicFrame.getPixel(artX,musicTop+24)==ART&&musicFrame.getPixel(420,musicTop+24)==BACKGROUND,"playing music shows unobscured album art and leaves the app area left of both columns alone");
        check(musicFrame.getPixel(700,236)==BACKGROUND&&musicFrame.getPixel(600,tyreTop+10)==BACKGROUND,"no overlay backing is drawn above the column and the tyre line does not reach the left app area");
        check(hud.touch(600,300,848,480,100000)==null,"left app area remains outside hit targets");
        check(solid(musicFrame,700,musicTop+79,816,musicTop+81,musicFrame.getPixel(820,musicTop+52))&&hud.touch(700,436,848,480,100000)==null,"music ends immediately after the time labels without a volume row");
        check(solid(musicFrame,720,musicTop+50,825,musicTop+56,musicFrame.getPixel(820,musicTop+52)),"no textual playback status appears below the artist");
        check(blocks(hud,artX,musicTop+24,100000)&&hud.touch(420,musicTop+24,848,480,100000)==null,"album art only consumes touches inside the music card");
        check(blocks(hud,artLeft+5,musicTop+62,100000),"progress is display only");
        long rev=hud.revision(100000);check(hud.revision(101000)!=rev,"playing music advances the output revision without app redraws");
        Bundle paused=state(0);paused.getBundle("music").putInt("state",2);hud.accept("test",paused,100000);
        Bitmap pausedFrame=frame(hud,100000);
        check(pausedFrame.getPixel(artX-4,musicMid)==Color.WHITE&&pausedFrame.getPixel(artX+3,musicMid)==Color.WHITE&&pausedFrame.getPixel(artX-16,musicMid)==ART,"pause glyph overlays the centre of the original album art");
        check(pausedFrame.getPixel(artLeft+5,musicTop+62)==ACCENT,"paused track retains progress");
        // Auto-hide: the card opened at 100000 and closes five seconds later; being the lowest card, the ones above drop into its place.
        check(hud.stack(104999).music()!=null&&hud.stack(105000).music()==null&&hud.stack(105000).tyres().bottom()==434,"the music card auto-hides five seconds after the last playback change and the cards above drop into its place");
        check(hud.animating(105100)&&!hud.animating(105400),"hiding fades the music card out and glides the other cards over a short animation");
        check(hud.revision(160000)==hud.revision(161000),"a hidden card, paused music and an empty chart do not cause perpetual redraw revisions");
        check(hud.revision(159000)!=hud.revision(159001),"tyre readings older than two read intervals invalidate an otherwise static frame");
        Bundle idle=state(0);Bundle idleMusic=new Bundle();idleMusic.putBoolean("granted",true);idle.putBundle("music",idleMusic);hud.accept("test",idle,170000);
        var idleStack=hud.stack(170000);Bitmap idleFrame=frame(hud,170400);
        check(idleStack.music()==null&&idleStack.tyres().bottom()==434&&idleStack.voltage().bottom()==400&&hud.touch(820,436,848,480,170000)==null&&hud.touch(820,430,848,480,170000)!=null,"music without a track stays hidden and tyres and voltage sit right above the phone status");
        check(idleFrame.getPixel(640,414)==BACKGROUND&&hud.touch(640,414,848,480,170400)==null,"the app area left of the sidebar stays free");
        Bundle small=state(0);Bundle smallPhone=small.getBundle("phone");smallPhone.putBoolean("wifi",false);smallPhone.putIntegerArrayList("slots",new ArrayList<>(Arrays.asList(1)));smallPhone.putIntegerArrayList("levels",new ArrayList<>(Arrays.asList(4)));
        hud.accept("test",small,171000);
        check(hud.stack(171000).phone().left()>700&&hud.animating(171100)&&frame(hud,171400).getPixel(660,454)==BACKGROUND&&hud.touch(660,454,848,480,171000)==null,"phone status shrinks without Wi-Fi and a second SIM, animating its width");
        // Two SIMs without Wi-Fi or mobile data: the slot is simply empty; with a mobile generation the slot shows its text.
        Bundle noSlot=state(0);noSlot.getBundle("phone").putBoolean("wifi",false);hud.accept("test",noSlot,171500);
        check(!bright(frame(hud,171900),wifiColumn-9,447,wifiColumn+10,462),"without Wi-Fi or mobile data nothing is drawn in the slot");
        Bundle mobile=state(0);mobile.getBundle("phone").putBoolean("wifi",false);mobile.getBundle("phone").putString("network","5G");hud.accept("test",mobile,172000);
        check(hud.stack(172000).phone().left()==live.phone().left()&&bright(frame(hud,172400),wifiColumn-9,447,wifiColumn+10,462),"without Wi-Fi the mobile network type takes the Wi-Fi slot at the same width");
        hud.accept("test",idle,173000);
        Bundle stopped=state(0);stopped.getBundle("music").putInt("state",1);hud.accept("test",stopped,173000);
        check(idleFrame.sameAs(frame(hud,173400)),"stopped session cannot leave stale track information visible");
        long T=180000;hud.accept("test",state(0),T);
        for(int i=1;i<=3;i++)hud.accept("test",state(i,event(i,T+i*500)),T+i*500);
        check(hud.summary(T+2000).contains("visible=3"),"same notification ID must produce three cards");
        check(blocks(hud,743,388,T+2000),"visible notification consumes taps above music");
        Bitmap image=frame(hud,T+2000);
        // The column change animates for 300 ms after the third card has fully entered; glyph checks use a settled frame.
        Bitmap settled=frame(hud,T+2400);
        // At the centre of the Wi-Fi glyph the three arcs and dot must form four separated bands.
        int bands=0;boolean previous=false;int liftedPhoneTop=Math.round(hud.stack(T+2000).phone().top()),columnShift=Math.round(hud.stack(T+2000).phone().right())-838;
        check(liftedPhoneTop==440-3*NotificationTimeline.STEP&&columnShift==633-838,"three notifications lift the phone status by three slots, into the instrument zone, so it continues in the left column");
        for(int y=liftedPhoneTop+4;y<=liftedPhoneTop+24;y++){
            // The glyph centre may sit between pixel columns, so three neighbouring columns are combined.
            boolean bright=false;for(int x=wifiColumn+columnShift-1;x<=wifiColumn+columnShift+1;x++){int color=settled.getPixel(x,y);bright|=Color.red(color)>160&&Color.green(color)>160;}
            if(bright&&!previous)bands++;previous=bright;
        }
        check(bands==4,"Wi-Fi must have three arcs and a dot, found "+bands+" bands");
        check(settled.getPixel(artX+columnShift,musicTop+24-3*NotificationTimeline.STEP)==ART&&settled.getPixel(artX,musicTop+24)!=ART&&hud.stack(T+2000).music().right()==633,"three notifications move the entire music widget up three slots and into the left column");
        Bitmap comparison=Bitmap.createBitmap(848,960,Bitmap.Config.ARGB_8888);Canvas comparisonCanvas=new Canvas(comparison);comparisonCanvas.drawBitmap(musicFrame,0,0,null);comparisonCanvas.drawBitmap(settled,0,480,null);
        try(FileOutputStream out=new FileOutputStream(args[0])){comparison.compress(Bitmap.CompressFormat.PNG,100,out);}
        // Re-reading a mailbox response must not duplicate/restart cards or retire the oldest one.
        hud.accept("test",state(3,event(3,T+1500)),T+2000);
        Bitmap replay=frame(hud,T+2000);check(image.sameAs(replay),"mailbox replay changed the rendered queue");
        hud.accept("test",state(4,event(4,T+2100)),T+2100);
        check(hud.summary(T+2339).contains("visible=3"),"fourth post exceeded capacity during exit");
        check(hud.summary(T+2340).contains("visible=3"),"fourth post must enter after oldest exits");
        hud.accept("old-session",state(5,event(5,T+2400)),T+2400);
        check(hud.summary(T+2400).contains("visible=3"),"old session changed active cards");
        Bundle removal=new Bundle();removal.putString("key","same-app-same-notification-id");
        removal.putLong("seq",5);removal.putBoolean("removed",true);
        hud.accept("test",state(5,removal),T+2500);
        check(hud.summary(T+2740).contains("visible=0"),"source removal must remove all its cards");
        hud.reset("next");check(hud.summary(T+3000).contains("visible=0"),"session reset retained cards");
        Bundle narrow=state(0);narrow.putInt("notification_width",200);hud.accept("next",narrow,T+3000);
        narrow=state(1,event(1,T+3000));narrow.putInt("notification_width",200);hud.accept("next",narrow,T+3000);
        check(hud.touch(620,440,848,480,T+4000)==null&&blocks(hud,639,440,T+4000),"custom 200px notification remains right aligned with adjusted hit bounds");
        check(frame(hud,T+4400).getPixel(620,440)==BACKGROUND,"narrower notification frees the left app region once the lifted cards have settled");
        Bundle unauthorised=new Bundle();unauthorised.putString("epoch","revoked");unauthorised.putLong("cursor",0);hud.accept("next",unauthorised,T+4000);
        check(frame(hud,T+4400).getPixel(artX,musicTop+24)!=ART&&hud.stack(T+4400).music()==null,"revoking access clears cached artwork and hides the music card");
        hud.setWidgets(new WidgetSettings(0));hud.accept("next",state(8,event(8,T+4000)),T+4000);
        check(solid(frame(hud,T+4500),0,0,848,480,BACKGROUND),"disabling all widgets clears every HUD pixel once the cards have faded");
        check(hud.touch(740,450,848,480,T+4500)==null&&hud.touch(740,100,848,480,T+4500)==null,"hidden widgets do not intercept app touches");
        hud.setWidgets(new WidgetSettings(WidgetSettings.MUSIC|WidgetSettings.MUSIC_AUTO_HIDE));hud.accept("next",idle,T+5000);
        check(hud.stack(T+5000).music()==null&&hud.touch(820,450,848,480,T+5000)==null&&solid(frame(hud,T+5000),0,0,848,480,BACKGROUND),"music alone stays hidden while nothing plays");
        hud.accept("next",state(0),T+5000);
        check(hud.touch(820,450,848,480,T+5000)!=null&&hud.touch(820,380,848,480,T+5000)==null,"playing music alone packs against the lower edge without reserved phone or vehicle gaps");
        check(hud.stack(T+9999).music()!=null&&hud.stack(T+10000).music()==null,"the music card closes after the configured five seconds");
        Bundle nextTrack=state(0);nextTrack.getBundle("music").putString("title","另一首歌");hud.accept("next",nextTrack,T+11000);
        check(hud.stack(T+11000).music()!=null&&hud.stack(T+15999).music()!=null&&hud.stack(T+16000).music()==null,"a track change re-opens the card for another five seconds");
        Bundle sameTrack=state(0);sameTrack.getBundle("music").putString("title","另一首歌");sameTrack.getBundle("music").putLong("position",90000);hud.accept("next",sameTrack,T+17000);
        check(hud.stack(T+17000).music()==null,"a position update alone does not re-open the card");
        Bundle pausedTrack=state(0);pausedTrack.getBundle("music").putString("title","另一首歌");pausedTrack.getBundle("music").putInt("state",2);hud.accept("next",pausedTrack,T+18000);
        check(hud.stack(T+18000).music()!=null&&hud.stack(T+23000).music()==null,"pausing re-opens the card and it closes again");
        hud.setWidgets(new WidgetSettings(WidgetSettings.MUSIC));
        check(hud.stack(T+30000).music()!=null,"without auto-hide the card stays while a track exists");
        hud.setWidgets(WidgetSettings.DEFAULT);hud.accept("next",state(8,event(8,T+4000)),T+31000);
        check(hud.summary(T+31000).contains("visible=0"),"re-enabling notifications cannot replay posts received while hidden");
        hud.reset("sim");Bundle off=state(0);off.putBoolean("enabled",false);hud.accept("sim",off,300000);
        hud.simulate(300000);check(hud.summary(300100).contains("visible=1"),"a simulated notification appears even while notifications are disabled");
        hud.accept("sim",off,300300);check(hud.summary(300400).contains("visible=1")&&blocks(hud,830,440,300400),"later snapshots keep the simulated card until it expires");
        Bundle two=state(0);two.putInt("notification_limit",1);hud.accept("sim",two,301000);hud.simulate(301000);hud.simulate(301100);
        check(hud.summary(301100).contains("visible=1"),"the visible limit from the phone settings caps simulated cards too");
        Bundle brief=state(0);brief.putInt("notification_seconds",5);hud.accept("sim",brief,320000);hud.simulate(320000);
        check(hud.summary(324999).contains("visible=1")&&hud.summary(325000).contains("visible=0"),"simulated cards use the configured display time");
        hud.reset("vol");Bundle withVolume=state(0);Bundle volume=new Bundle();volume.putInt("level",7);volume.putInt("max",15);volume.putLong("seq",1);withVolume.putBundle("volume",volume);
        hud.accept("vol",withVolume,400000);
        check(frame(hud,400100).getPixel(30,370)==BACKGROUND,"the first volume snapshot of a session only records the level");
        volume.putInt("level",10);volume.putLong("seq",2);hud.accept("vol",withVolume,400500);
        check(frame(hud,400520).getPixel(30,370)==BACKGROUND&&hud.animating(400550),"the bar starts off screen to the left and slides in");
        Bitmap volumeFrame=frame(hud,400900);
        check(volumeFrame.getPixel(30,370)==ACCENT&&volumeFrame.getPixel(30,160)!=ACCENT&&volumeFrame.getPixel(30,160)!=BACKGROUND&&hud.touch(30,300,848,480,400900)==null,"a volume change shows a bar filled to the level above the dashboard speaker icon without blocking touches");
        volume.putInt("level",12);volume.putLong("seq",3);hud.accept("vol",withVolume,401000);
        check(frame(hud,401020).getPixel(30,370)==ACCENT&&!hud.animating(401300),"a further change while the bar is visible keeps it in place instead of sliding in again");
        check(frame(hud,403100).getPixel(30,370)==ACCENT&&frame(hud,403500).getPixel(30,370)==BACKGROUND,"the later change extends the hold and the bar then slides out to the left");
        hud.setWidgets(WidgetSettings.DEFAULT.with(WidgetSettings.VOLUME,false));volume.putInt("level",14);volume.putLong("seq",4);hud.accept("vol",withVolume,404000);
        check(frame(hud,404400).getPixel(30,370)==BACKGROUND,"the volume widget switch hides the bar");
        hud.setWidgets(WidgetSettings.DEFAULT);
        hud.reset("chart");hud.accept("chart",state(0),500000);
        BatteryTelemetry series=new BatteryTelemetry();series.select("synthetic-vehicle");
        for(int i=0;i<20;i++){series.update("synthetic-vehicle",79.3f-(i%5)*0.3f,BatteryTelemetry.Source.BLUETOOTH,1789453320000L+i*1000L,480000+i*1000L);hud.acceptBattery(series.snapshot());}
        var chartStack=hud.stack(500000);Bitmap chartFrame=frame(hud,500000);int greens=0,greensLeft=0;
        int chartLeft=(int)chartStack.voltage().left();
        for(int y=(int)chartStack.voltage().top()+30;y<(int)chartStack.voltage().top()+78;y++)for(int x=chartLeft+12;x<chartLeft+180;x++){int p=chartFrame.getPixel(x,y);if(Color.green(p)>Color.red(p)+40){greens++;if(x<chartLeft+57)greensLeft++;}}
        check(chartStack.voltage().height()==84&&greens>100&&greensLeft==0,"the voltage card draws the last 30 s of readings, leaving the older left part empty; green="+greens+" left="+greensLeft);
        check(hud.revision(500000)!=hud.revision(501000)&&hud.revision(501000)!=hud.revision(502000),"a live curve re-encodes once a second as it scrolls");
        hud.setWidgets(WidgetSettings.DEFAULT.with(WidgetSettings.VOLTAGE_CHART,false));
        check(hud.stack(500000).voltage().height()==28&&hud.animating(500100)&&!hud.animating(500400),"switching the chart off shrinks the card to one row with a size animation");
        hud.reset("probe");hud.accept("probe",state(0),700000);
        java.util.List<RegisterProbe.Row> rows=new ArrayList<>();
        rows.add(new RegisterProbe.Row("rInfoBool2",new RegisterProbe.Value("0800",8,699000,699000,false,false)));
        rows.add(new RegisterProbe.Row("rStateBool",new RegisterProbe.Value("0100",1,690000,690000,true,false)));
        rows.add(new RegisterProbe.Row("rSpeed",null));
        hud.acceptProbe(rows);
        check(solid(frame(hud,700000),6,28,200,60,BACKGROUND),"the register table is not drawn while the probe switch is off");
        hud.setWidgets(WidgetSettings.DEFAULT.with(WidgetSettings.REGISTER_PROBE,true));
        Bitmap probeFrame=frame(hud,700000);
        check(!solid(probeFrame,6,28,200,60,BACKGROUND)&&bright(probeFrame,10,32,120,60)&&hud.touch(100,45,848,480,700000)==null,"with the probe switch on the register table is drawn at the left without blocking touches");
        check(hud.revision(700000)!=hud.revision(701000),"the register table re-encodes once a second for its age labels");
        hud.setWidgets(WidgetSettings.DEFAULT);
        hud.reset("ride");hud.accept("ride",state(0),500000);hud.setWidgets(WidgetSettings.DEFAULT.with(WidgetSettings.SPEED,true).with(WidgetSettings.POWER,true));
        for(int i=0;i<20;i++)hud.acceptRide(new RideState.Snapshot(120+(i%4)*15,200+(i%3)*80,480000+i*1000L,480000+i*1000L));
        // stack() at 500000 starts the fade-in of the freshly enabled cards; the frame is taken once it has finished.
        var rideStack=hud.stack(500000);Bitmap rideFrame=frame(hud,500400);int speedGreens=0;
        int rideLeft=(int)rideStack.speed().left();
        for(int y=(int)rideStack.speed().top()+30;y<(int)rideStack.speed().top()+78;y++)for(int x=rideLeft+12;x<rideLeft+180;x++){int p=rideFrame.getPixel(x,y);if(Color.green(p)>Color.red(p)+40)speedGreens++;}
        check(rideStack.speed().height()==84&&rideStack.power().height()==84&&rideStack.power().bottom()+6==rideStack.speed().top()&&rideStack.speed().bottom()+6==rideStack.voltage().top()&&speedGreens>100&&blocks(hud,rideStack.power().left()+50,rideStack.power().top()+40,500400)&&rideStack.power().right()==633&&rideStack.voltage().bottom()==468,"speed and power cards draw their own curves above the voltage card and consume touches; green="+speedGreens);
        hud.setWidgets(WidgetSettings.DEFAULT);
        hud.reset("hold");hud.accept("hold",state(0),800000);hud.acceptTires(tireState());
        check(hud.stack(800000).phone().right()==838&&!hud.hillHold(800000),"without hill hold the cards keep the right edge");
        hud.acceptRide(new RideState.Snapshot(0,168,799900,799900));
        check(!hud.hillHold(800000)&&hud.stack(800000).phone().right()==838,"the hold condition must last the minimum time before the cards move");
        hud.acceptRide(new RideState.Snapshot(0,168,801900,801900));hud.hillHold(802000);
        hud.acceptRide(new RideState.Snapshot(0,168,803900,803900));
        var held=hud.stack(804000);
        check(hud.hillHold(804000)&&held.phone().right()==568&&held.phone().bottom()==468&&held.music().right()==568&&held.music().bottom()==434&&held.tyres().right()==838&&held.tyres().bottom()==366&&held.voltage().right()==633&&held.voltage().bottom()==344&&hud.animating(804100),"after three seconds the covered phone and music cards slide left while tyres and voltage restack on the toast, animated");
        Bitmap heldFrame=frame(hud,804400);
        check(heldFrame.getPixel(700,455)==BACKGROUND&&hud.touch(700,455,848,480,804400)==null&&bright(heldFrame,520,447,566,462)&&!bright(heldFrame,780,447,830,462),"the area under the toast is left empty and the phone text moves with its card");
        hud.accept("hold",state(1,event(1,804500)),804500);hud.acceptRide(new RideState.Snapshot(0,168,805900,805900));
        var heldLifted=hud.stack(806000);
        check(hud.hillHold(806000)&&heldLifted.phone().right()==568&&heldLifted.phone().bottom()==402&&heldLifted.tyres().right()==838&&heldLifted.tyres().bottom()==366&&blocks(hud,500,440,806000)&&hud.touch(700,440,848,480,806000)==null,"a notification and the phone card it lifts into the toast slide left together while the rest restack on the toast");
        check(hud.hillHold(809000)&&hud.hillHold(809500)&&!hud.hillHold(810000)&&hud.stack(810000).phone().right()==838&&hud.stack(810000).phone().bottom()==402,"stale readings end the dodge only after the minimum time, then the lifted column returns to the right");
        // Display conditions and the column order.
        hud.reset("cond");hud.accept("cond",state(0),900000);hud.acceptTires(tireState());
        hud.setWidgets(WidgetSettings.DEFAULT.withCondition(WidgetSettings.PHONE,new WidgetCondition(WidgetCondition.WHILE,0,5,WidgetCondition.SPEED,5,30,0,3000,20,100,0,100)));
        check(hud.stack(900000).phone()==null&&hud.stack(900000).music()!=null,"a speed condition hides the phone card while no speed reading exists");
        hud.acceptRide(new RideState.Snapshot(120,300,900500,900500));
        check(hud.stack(900500).phone()!=null&&hud.stack(900500).phone().bottom()==468&&hud.stack(900500).music().bottom()==434,"a speed inside the range shows the card again");
        hud.acceptRide(new RideState.Snapshot(400,300,901000,901000));
        check(hud.stack(901000).phone()==null&&hud.revision(901000)!=hud.revision(900500),"a speed outside the range hides it and changes the revision");
        TireTelemetry freshTyres=new TireTelemetry();freshTyres.select("synthetic-vehicle");freshTyres.update("synthetic-vehicle",true,2.4f,29f,TireTelemetry.Source.BLUETOOTH,1789453320000L,901400);freshTyres.update("synthetic-vehicle",false,2.6f,30f,TireTelemetry.Source.BLUETOOTH,1789453320000L,901400);hud.acceptTires(freshTyres.snapshot());
        hud.setWidgets(WidgetSettings.DEFAULT.withCondition(WidgetSettings.PHONE,new WidgetCondition(WidgetCondition.WHILE,0,5,WidgetCondition.TYRE_FRONT_PRESSURE|WidgetCondition.TYRE_REAR_TEMP,0,160,0,30000,20,90,0,100,20,30,12,35,-20,100,-20,100)));
        check(hud.stack(901500).phone()!=null,"a front pressure of 2.4 bar inside 2.0 to 3.0 with an open rear temperature range shows the card");
        hud.setWidgets(WidgetSettings.DEFAULT.withCondition(WidgetSettings.PHONE,new WidgetCondition(WidgetCondition.WHILE,0,5,WidgetCondition.TYRE_FRONT_PRESSURE,0,160,0,30000,20,90,0,100,25,30,12,35,-20,100,-20,100)));
        check(hud.stack(901500).phone()==null,"a front pressure below 2.5 bar hides it");
        hud.setWidgets(WidgetSettings.DEFAULT.withCondition(WidgetSettings.TYRES,WidgetCondition.onChange(WidgetCondition.VOLUME_CHANGE,2)));
        Bundle condVolume=state(0);Bundle level=new Bundle();level.putInt("level",5);level.putInt("max",15);level.putLong("seq",1);condVolume.putBundle("volume",level);hud.accept("cond",condVolume,902000);
        check(hud.stack(902000).tyres()==null&&hud.stack(902000).phone()!=null,"a change condition keeps the tyre card hidden until its trigger fires");
        level.putInt("level",6);level.putLong("seq",2);hud.accept("cond",condVolume,902500);
        check(hud.stack(902500).tyres()!=null&&hud.stack(904499).tyres()!=null&&hud.stack(904500).tyres()==null,"a volume change shows the tyre card for the configured two seconds");
        hud.setWidgets(WidgetSettings.DEFAULT.withOrder(java.util.List.of(WidgetSettings.PHONE,WidgetSettings.NOTIFICATIONS,WidgetSettings.MUSIC)));
        hud.accept("cond",state(1,event(1,905000)),905000);
        var ordered=hud.stack(906000);
        check(ordered.phone().bottom()==468&&ordered.notificationBottom()==434&&ordered.tyres().bottom()==368&&blocks(hud,700,450,906000)&&blocks(hud,700,400,906000)&&hud.touch(700,436,848,480,906000)==null,"a phone card ordered below the notifications stays at the bottom while the notification block and the cards above it move up, with matching touch areas");
        Bitmap orderedFrame=frame(hud,906000);
        check(orderedFrame.getPixel(700,466)!=BACKGROUND&&orderedFrame.getPixel(700,436)==BACKGROUND&&orderedFrame.getPixel(700,420)!=BACKGROUND,"the frame draws the phone card at the bottom, a gap, then the notification above it");
        hud.setWidgets(WidgetSettings.DEFAULT.withOrder(java.util.List.of(WidgetSettings.NOTIFICATIONS,WidgetSettings.PHONE,WidgetSettings.MUSIC,WidgetSettings.VOLTAGE,WidgetSettings.COLUMN_DIVIDER,WidgetSettings.TYRES)));
        var twoColumns=hud.stack(906000);
        check(twoColumns.tyres().right()==633&&twoColumns.tyres().bottom()==402&&twoColumns.phone().bottom()==402&&blocks(hud,600,395,906400)&&hud.touch(500,460,848,480,906400)==null&&frame(hud,906400).getPixel(600,395)!=BACKGROUND,"a card moved to the left column sits left of the right column, rises above the notification and is drawn and touchable there once its move has settled");
        hud.setWidgets(WidgetSettings.DEFAULT.withCondition(WidgetSettings.NOTIFICATIONS,new WidgetCondition(WidgetCondition.WHILE,0,5,WidgetCondition.PLAYING,0,100,0,3000,20,100,0,100)));
        Bundle pausedCond=state(1,event(1,905000));pausedCond.getBundle("music").putInt("state",2);hud.accept("cond",pausedCond,907000);
        check(hud.stack(907000).phone().bottom()==468&&hud.summary(907000).contains("visible=1")&&frame(hud,907000).getPixel(700,436)==BACKGROUND,"notifications gated on playing music are held back while it is paused: the card stays queued, nothing is drawn where it would sit and the column is not lifted");
        // Light dashboard theme: the same data on the light palette, appended below the dark comparison image.
        DashboardHud light=new DashboardHud();light.reset("light");light.setDark(false);light.acceptTires(tireState());light.acceptBattery(batteryState());
        light.accept("light",state(0),100000);light.simulate(100300);light.simulate(100350);light.stack(100400);light.stack(101000);
        Bitmap lightFrame=Bitmap.createBitmap(848,480,Bitmap.Config.ARGB_8888);Canvas lightCanvas=new Canvas(lightFrame);lightCanvas.drawColor(0xffe6eaee);light.draw(lightCanvas,848,480,101500);
        var lightStack=light.stack(101500);int lightSurface=lightFrame.getPixel((int)lightStack.music().right()-4,(int)lightStack.music().top()+32);
        check(!light.dark()&&Color.red(lightSurface)>230&&Color.green(lightSurface)>230&&Color.blue(lightSurface)>230,"light theme paints cards on a near-white surface, got #"+Integer.toHexString(lightSurface)+" box="+lightStack.music()+" notifications="+light.summary(101500));
        // The 2.4 inch screen profile: the module's own speed and battery blocks replace every card on the whole panel.
        DashboardHud panelHud=new DashboardHud();panelHud.reset("small");panelHud.setSmallScreen(true);
        panelHud.accept("small",state(0),100000);panelHud.acceptBattery(batteryState());
        panelHud.acceptRide(new RideState.Snapshot(450,1500,100000,100000));
        Bitmap panel=Bitmap.createBitmap(240,320,Bitmap.Config.ARGB_8888);
        Canvas panelCanvas=new Canvas(panel);panelCanvas.drawColor(BACKGROUND);panelHud.draw(panelCanvas,240,320,100000);
        check(panelHud.smallScreen(),"the small screen profile replaces the card stack");
        check(panel.getPixel(0,0)!=BACKGROUND&&panel.getPixel(239,319)!=BACKGROUND,"the panel paints the whole 240 x 320 frame");
        check(bright(panel,40,30,200,130),"the speed reading is drawn across the top block");
        check(bright(panel,40,190,200,300),"the voltage row is drawn below the divider");
        check(panel.getPixel(0,0)==0xff000000&&panel.getPixel(239,319)==0xff000000,"the panel backdrop is black by default");
        panelHud.setSmallBackground(0xff224466);panelHud.draw(panelCanvas,240,320,100011);
        check(panel.getPixel(0,0)==0xff224466,"a chosen backdrop fills the frame");
        panelHud.setSmallBackground(0);panelHud.draw(panelCanvas,240,320,100012);
        check(panel.getPixel(0,0)!=0xff224466,"zero hands the backdrop back to the theme");
        panelHud.setSmallBackground(0xff000000);
        Bitmap stretched=Bitmap.createBitmap(480,640,Bitmap.Config.ARGB_8888);
        Canvas stretchedCanvas=new Canvas(stretched);stretchedCanvas.drawColor(BACKGROUND);panelHud.draw(stretchedCanvas,480,640,100013);
        check(bright(stretched,100,80,400,320)&&bright(stretched,60,370,420,600),"the panel keeps its proportions on a frame twice the design size");
        check(panelHud.touch(120,160,240,320,100000)!=null&&"block".equals(panelHud.touch(120,160,240,320,100000).getString("command")),"the panel consumes every touch inside the frame");
        check(panelHud.touch(120,400,240,320,100000)==null&&panelHud.touch(-4,160,240,320,100000)==null,"a touch outside the frame is not consumed");
        panelHud.setSmallColors(0xffff0000,0,0);panelHud.draw(panelCanvas,240,320,100001);
        check(shade(panel,0xffff0000,40,30,200,140)&&!shade(panel,0xffff0000,40,180,200,319),"a custom speed colour paints the speed block and nothing below it");
        panelHud.setSmallColors(0,0xff00ff00,0);panelHud.draw(panelCanvas,240,320,100002);
        check(!shade(panel,0xffff0000,40,30,200,140)&&shade(panel,0xff00ff00,40,180,200,319),"the colour moved down to the first row and left the speed block");
        panelHud.setSmallColors(0,0,0);
        check(panelHud.smallRowColor(0)==0&&panelHud.smallRowColor(1)==0&&panelHud.smallRowColor(2)==0,"zero keeps every row on the theme's own colour");
        panelHud.draw(panelCanvas,240,320,100003);int fromVehicle=lit(panel,40,180,200,319);
        panelHud.setSmallSource(1);check(panelHud.smallSource()==1,"the panel is switched over to the protection board");
        panelHud.draw(panelCanvas,240,320,100004);
        check(lit(panel,40,180,200,319)!=fromVehicle,"reading the board instead of the vehicle changes what the panel shows");
        panelHud.setSmallSource(9);check(panelHud.smallSource()==1,"an out of range source is clamped");
        panelHud.setSmallSource(0);panelHud.draw(panelCanvas,240,320,100005);
        check(panelHud.smallSource()==0&&lit(panel,40,180,200,319)==fromVehicle,"switching back restores the vehicle reading");
        panelHud.setSmallScreen(false);
        check(!panelHud.smallScreen(),"switching the profile off restores the card layout");
        Bitmap darkImage=android.graphics.BitmapFactory.decodeFile(args[0]);Bitmap both=Bitmap.createBitmap(848,darkImage.getHeight()+480,Bitmap.Config.ARGB_8888);
        Canvas bothCanvas=new Canvas(both);bothCanvas.drawBitmap(darkImage,0,0,null);bothCanvas.drawBitmap(lightFrame,0,darkImage.getHeight(),null);
        try(FileOutputStream out=new FileOutputStream(args[0])){both.compress(Bitmap.CompressFormat.PNG,100,out);}
        System.out.println("PASS: "+checks+" Android HUD checks");
    }
}
