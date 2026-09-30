package dev.ichinomiya.ninebotenhance.notification;

import android.content.*;
import android.media.AudioManager;
import android.os.*;

/**
 * Reports the phone volume level of the stream that last changed. Levels only; no audio content or session identity.
 * <p>
 * An interceptor may claim a media-volume press instead: the previous level is put back before anything else observes it and
 * the change is not recorded, so the press moves the lamp and the volume stays exactly where the rider left it.
 */
public final class VolumeStatus {
    /** Returns whether the press was consumed; {@code up} is the direction of the rejected change. */
    public interface Interceptor{boolean consume(boolean up);}
    private static final String CHANGED="android.media.VOLUME_CHANGED_ACTION",STREAM="android.media.EXTRA_VOLUME_STREAM_TYPE",
            VALUE="android.media.EXTRA_VOLUME_STREAM_VALUE",PREVIOUS="android.media.EXTRA_PREV_VOLUME_STREAM_VALUE";
    private final AudioManager audio;private int stream=AudioManager.STREAM_MUSIC,level=-1,max=15,polledMusic=-1;private long seq,changedAt;
    private final Interceptor interceptor;private int restoringTo=-1;private long restoringAt;
    /** Volume the current press burst started at; every held repeat restores to this same anchor instead of the drifting per-event previous. */
    private int baseline=-1;private long burstAt;private static final long BURST_GAP_MS=900;
    public VolumeStatus(Context context){this(context,up->false);}
    public VolumeStatus(Context context,Interceptor interceptor){
        this.interceptor=interceptor==null?up->false:interceptor;
        AudioManager manager=null;
        try{manager=context.getSystemService(AudioManager.class);}catch(RuntimeException ignored){}
        audio=manager;
        if(audio!=null)try{level=polledMusic=audio.getStreamVolume(AudioManager.STREAM_MUSIC);max=Math.max(1,audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));}catch(RuntimeException ignored){}
        // The framework-protected broadcast carries the stream and both values; other apps cannot send it.
        try{context.registerReceiver(new BroadcastReceiver(){@Override public void onReceive(Context c,Intent intent){
            if(intent==null||!CHANGED.equals(intent.getAction()))return;
            int type=intent.getIntExtra(STREAM,-1),value=intent.getIntExtra(VALUE,-1),previous=intent.getIntExtra(PREVIOUS,-1);
            if(type<0||value<0||value==previous)return;
            if(type!=AudioManager.STREAM_MUSIC)android.util.Log.i("NinebotEnhance","LAMP volume stream "+type+" "+previous+"->"+value);
            if(type==AudioManager.STREAM_MUSIC&&consumed(value,previous))return;
            record(type,value);
        }},new IntentFilter(CHANGED),Context.RECEIVER_EXPORTED);}catch(RuntimeException ignored){}
    }
    /** The restore we asked for is not a rider action, and a claimed press must not reach the volume bar. */
    private boolean consumed(int value,int previous){
        synchronized(this){
            if(value==restoringTo&&SystemClock.elapsedRealtime()-restoringAt<1500){polledMusic=value;return true;}
        }
        if(previous<0||audio==null)return false;
        boolean taken=interceptor.consume(value>previous);
        android.util.Log.i("NinebotEnhance","LAMP volume media "+previous+"->"+value+" taken="+taken);
        if(!taken){synchronized(this){baseline=-1;}return false;}
        long now=SystemClock.elapsedRealtime();int target;
        synchronized(this){
            // A press after a quiet gap opens a new burst and anchors the baseline; every held repeat then restores to that same
            // level rather than to its own previous, which has already been stepped down, so the volume does not walk away.
            if(baseline<0||now-burstAt>BURST_GAP_MS)baseline=previous;
            burstAt=now;target=baseline;
        }
        restore(target,value>previous);
        return true;
    }
    /**
     * Put the media stream back to the burst baseline. A plain setStreamVolume is not enough on builds that keep several streams
     * aliased to the media one: they were all stepped, and the policy pulls the media level back to match them. Stepping back
     * through adjustStreamVolume moves the whole group, and the level is read back and forced to the baseline if it still did not
     * land, which also recovers when a held key stepped several times before this restore ran.
     */
    private void restore(int baseline,boolean up){
        int after=-1;
        try{
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,up?AudioManager.ADJUST_LOWER:AudioManager.ADJUST_RAISE,0);
            after=audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            if(after!=baseline){audio.setStreamVolume(AudioManager.STREAM_MUSIC,baseline,0);after=audio.getStreamVolume(AudioManager.STREAM_MUSIC);}
        }catch(RuntimeException e){android.util.Log.i("NinebotEnhance","LAMP volume restore failed "+e);}
        int landed=after<0?baseline:after;
        synchronized(this){restoringTo=landed;restoringAt=SystemClock.elapsedRealtime();polledMusic=landed;}
        android.util.Log.i("NinebotEnhance","LAMP volume restored to "+landed+" wanted "+baseline);
    }
    private synchronized void record(int type,int value){
        if(audio==null)return;
        int limit;try{limit=Math.max(1,audio.getStreamMaxVolume(type));}catch(RuntimeException e){limit=max;}
        stream=type;level=Math.max(0,Math.min(limit,value));max=limit;seq++;changedAt=SystemClock.elapsedRealtime();
        if(type==AudioManager.STREAM_MUSIC)polledMusic=level;
    }
    /** Polling the media stream as well covers devices that deliver the broadcast late or not at all. */
    public synchronized Bundle snapshot(){
        if(audio!=null)try{int value=audio.getStreamVolume(AudioManager.STREAM_MUSIC);if(polledMusic>=0&&value!=polledMusic)record(AudioManager.STREAM_MUSIC,value);polledMusic=value;}catch(RuntimeException ignored){}
        Bundle b=new Bundle();b.putInt("stream",stream);b.putInt("level",level);b.putInt("max",max);b.putLong("seq",seq);b.putLong("changed",changedAt);return b;
    }
}
