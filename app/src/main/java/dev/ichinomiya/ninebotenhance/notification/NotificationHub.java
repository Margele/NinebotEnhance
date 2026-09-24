package dev.ichinomiya.ninebotenhance.notification;

import android.content.Context;
import android.app.Notification;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.*;
import dev.ichinomiya.ninebotenhance.core.LampState;
import dev.ichinomiya.ninebotenhance.core.NotificationDeduplicator;
import dev.ichinomiya.ninebotenhance.lamp.LampController;
import dev.ichinomiya.ninebotenhance.bms.BmsController;
import java.util.*;

/** Small, memory-only event mailbox, read only through the UID-checked frame broker. */
public final class NotificationHub {
    private static NotificationHub instance;
    public static synchronized NotificationHub get(Context c){if(instance==null)instance=new NotificationHub(c.getApplicationContext());return instance;}
    private final Context context;private final PhoneStatus phone;private final MusicStatus music;private final VolumeStatus volume;private final LampController lamp;private final BmsController bms;private final String process=UUID.randomUUID().toString();
    private long sequence,revision;private final ArrayDeque<Bundle> events=new ArrayDeque<>();
    private final NotificationDeduplicator posts=new NotificationDeduplicator();
    private NotificationHub(Context c){
        context=c;phone=new PhoneStatus(c);music=new MusicStatus(c);lamp=LampController.get(c);bms=BmsController.get(c);
        // Silent media keys belong to the lamp: with a track playing they stay the volume keys they always were.
        volume=new VolumeStatus(c,up->!music.playing()&&lamp.stepFromVolume(up));
    }
    public synchronized void clear(){events.clear();posts.clear();revision++;}
    public synchronized void post(String key,String pkg,Notification notification){
        NotificationPreferences prefs=new NotificationPreferences(context);
        if(!prefs.enabled()||!prefs.granted()||!prefs.packages().contains(pkg))return;
        NotificationContent.Content content=NotificationContent.read(notification);if(content==null)return;
        if(!content.messages().isEmpty()){
            for(NotificationDeduplicator.Message message:posts.acceptMessages(key,content.messages()))
                postCard(key,pkg,content.title(),message.text(),prefs.seconds()*1000);
        }else if(posts.accept(key,content.timestamp(),content.title(),content.text())){
            postCard(key,pkg,content.title(),content.text(),prefs.seconds()*1000);
        }
    }
    /** Each dashboard snapshot renews the lamp's hold, so the radio is open exactly while a cast session runs. */
    private Bundle lampBundle(){
        lamp.hold(LampController.HOLD_SESSION,LampController.HOLD_MS);LampState state=lamp.state();Bundle b=new Bundle();
        b.putInt("phase",state.phase());b.putInt("position",state.position());b.putInt("speed",state.speed());
        b.putInt("low",state.low());b.putInt("high",state.high());b.putString("detail",state.detail());
        // The height shown on the dashboard is the raw device position remapped onto the reported travel limits and reversed if
        // set; the mapping settings live only in this process, so the displayed percent is computed here rather than in the HUD.
        b.putInt("percent",lamp.settings().displayPercent(state.position(),state.lowLimit(),state.highLimit()));return b;
    }
    /** Each snapshot also keeps the BMS link open and its poll running; the reading is whatever the board last answered. */
    private Bundle bmsBundle(){bms.hold(BmsController.HOLD_SESSION,BmsController.HOLD_MS);bms.poll();return BmsBundle.write(bms.state(),bms.settings().pollMs());}
    /**
     * Ninebot's status poll says whether one of its screens is visible. The lamp and BMS links are held while it is and let go the
     * moment it is not; a running session keeps holding them through its own snapshots, so casting with Ninebot behind another app
     * is unaffected. A hub that was never created has no links to drop, so a background poll does not create one.
     */
    public static void hostVisible(Context c,boolean visible){
        NotificationHub hub;
        synchronized(NotificationHub.class){hub=instance;}
        if(hub==null){if(!visible)return;hub=get(c);}
        if(visible){
            hub.lamp.hold(LampController.HOLD_FOREGROUND,LampController.HOLD_MS);
            hub.bms.hold(BmsController.HOLD_FOREGROUND,BmsController.HOLD_MS);hub.bms.poll();
        }else{hub.lamp.release(LampController.HOLD_FOREGROUND);hub.bms.release(BmsController.HOLD_FOREGROUND);}
    }
    private void postCard(String key,String pkg,String title,String text,int duration){
        Bundle event=new Bundle();event.putString("key",key);event.putString("package",pkg);event.putString("title",title);event.putString("text",text);
        try{event.putString("app",context.getPackageManager().getApplicationLabel(context.getPackageManager().getApplicationInfo(pkg,0)).toString());}catch(Exception e){event.putString("app",pkg);}
        event.putInt("duration",duration);enqueue(event);
    }
    public synchronized void removed(String key){if(!posts.remove(key))return;Bundle b=new Bundle();b.putString("key",key);b.putBoolean("removed",true);enqueue(b);}
    private void enqueue(Bundle b){b.putLong("seq",++sequence);b.putLong("posted",SystemClock.elapsedRealtime());events.addLast(b);while(events.size()>32)events.removeFirst();}
    public synchronized Bundle snapshot(long after,String knownEpoch){return snapshot(after,knownEpoch,-1);}
    public synchronized Bundle snapshot(long after,String knownEpoch,long knownArt){
        NotificationPreferences prefs=new NotificationPreferences(context);boolean enabled=prefs.enabled()&&prefs.granted();
        if(!enabled&&!events.isEmpty())clear();
        String epoch=process+":"+revision;Bundle b=new Bundle();b.putString("epoch",epoch);b.putBoolean("enabled",enabled);b.putBundle("phone",phone.snapshot());
        b.putInt("notification_width",prefs.width());b.putInt("notification_seconds",prefs.seconds());b.putBundle("volume",volume.snapshot());b.putInt("notification_limit",prefs.limit());b.putBundle("music",music.snapshot(epoch.equals(knownEpoch)?knownArt:-1));
        ArrayList<Bundle> out=new ArrayList<>();long cursor=sequence,now=SystemClock.elapsedRealtime();
        if(enabled&&epoch.equals(knownEpoch)&&after>=0){cursor=after;for(Bundle stored:events){if(stored.getLong("seq")<=after)continue;cursor=stored.getLong("seq");if(now-stored.getLong("posted")>60000)continue;Bundle item=new Bundle(stored);String pkg=item.getString("package");if(pkg!=null){try{Drawable d=context.getPackageManager().getApplicationIcon(pkg);Bitmap icon=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);d.setBounds(0,0,32,32);d.draw(new Canvas(icon));item.putParcelable("icon",icon);}catch(Exception ignored){}}out.add(item);if(out.size()==8)break;}}
        b.putBundle("lamp",lampBundle());b.putBundle("bms",bmsBundle());
        b.putLong("cursor",cursor);b.putParcelableArrayList("events",out);return b;
    }
}
