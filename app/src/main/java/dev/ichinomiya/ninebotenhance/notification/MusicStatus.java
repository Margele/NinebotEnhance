package dev.ichinomiya.ninebotenhance.notification;

import android.content.Context;
import android.graphics.*;
import android.media.*;
import android.media.session.*;
import android.os.*;
import dev.ichinomiya.ninebotenhance.core.MusicPlayback;
import java.util.*;

/** Public media-session API under the module's notification-listener grant. Memory only, no artwork URI fetches. */
public final class MusicStatus {
    private final Context context;private final Handler worker;
    private volatile Bundle cached=new Bundle();private boolean pending;private volatile long sampled=-10000;
    private MediaController selected;private String selectedId="";private Bitmap artwork;private long artRevision;
    public MusicStatus(Context context){this.context=context.getApplicationContext();HandlerThread thread=new HandlerThread("Ninebot-Music",android.os.Process.THREAD_PRIORITY_BACKGROUND);thread.start();worker=new Handler(thread.getLooper());}
    /**
     * Whether a track is actually moving right now; decides who owns the volume keys. The cached sample only refreshes while a
     * dashboard snapshot is being taken, so once it is stale the sessions are asked directly: a paused player must hand the keys
     * to the lamp at once, not when the next cast happens to sample it.
     */
    public boolean playing(){
        Bundle b=cached;
        if(SystemClock.elapsedRealtime()-sampled<=1500)return b.getBoolean("active")&&MusicPlayback.moving(b.getInt("state"));
        return livePlaying();
    }
    private boolean livePlaying(){
        try{
            if(!new NotificationPreferences(context).granted())return false;
            MediaSessionManager manager=context.getSystemService(MediaSessionManager.class);if(manager==null)return false;
            for(MediaController candidate:manager.getActiveSessions(NotificationPreferences.listener(context))){
                PlaybackState state=candidate.getPlaybackState();if(state!=null&&MusicPlayback.moving(state.getState()))return true;
            }
            return false;
        }catch(RuntimeException e){return false;}
    }
    public synchronized Bundle snapshot(long knownArt){
        if(!new NotificationPreferences(context).granted()){worker.post(()->{selected=null;selectedId="";artwork=null;});cached=new Bundle();return new Bundle();}
        long now=SystemClock.elapsedRealtime();
        if(!pending&&now-sampled>=1000){sampled=now;pending=true;worker.post(()->{try{cached=sample();}catch(RuntimeException e){cached=new Bundle();}finally{synchronized(MusicStatus.this){pending=false;}}});}
        Bundle out=new Bundle(cached);if(out.getLong("art_revision",-1)==knownArt)out.remove("art");return out;
    }
    private Bundle sample(){
        Bundle b=new Bundle();boolean granted=new NotificationPreferences(context).granted();b.putBoolean("granted",granted);
        if(!granted){selected=null;selectedId="";artwork=null;return b;}
        MediaSessionManager manager=context.getSystemService(MediaSessionManager.class);
        List<MediaController> controllers=manager==null?Collections.emptyList():manager.getActiveSessions(NotificationPreferences.listener(context));
        MediaController next=null;int best=-1;
        for(MediaController candidate:controllers)try{
            PlaybackState state=candidate.getPlaybackState();if(state==null||!MusicPlayback.hasTrack(state.getState()))continue;
            int score=MusicPlayback.moving(state.getState())?3:state.getState()==PlaybackState.STATE_BUFFERING||state.getState()==PlaybackState.STATE_CONNECTING?2:state.getState()==PlaybackState.STATE_PAUSED?1:0;
            if(score>best){best=score;next=candidate;}
        }catch(RuntimeException ignored){}
        if(next==null)return idle(b);
        PlaybackState playback=next.getPlaybackState();
        if(playback==null||!MusicPlayback.hasTrack(playback.getState()))return idle(b);
        if(selected==null||!selected.getSessionToken().equals(next.getSessionToken()))selectedId=UUID.randomUUID().toString();selected=next;
        MediaMetadata metadata=next.getMetadata();
        b.putBoolean("active",true);b.putString("media_session",selectedId);b.putString("title",metadataText(metadata,MediaMetadata.METADATA_KEY_TITLE,MediaMetadata.METADATA_KEY_DISPLAY_TITLE));
        b.putString("artist",metadataText(metadata,MediaMetadata.METADATA_KEY_ARTIST,MediaMetadata.METADATA_KEY_ALBUM_ARTIST));
        b.putLong("duration",metadata==null?0:Math.max(0,metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)));
        b.putInt("state",playback.getState());b.putLong("position",playback.getPosition());
        b.putFloat("speed",playback.getPlaybackSpeed());b.putLong("updated",playback.getLastPositionUpdateTime());
        Bitmap art=null;if(metadata!=null)for(String key:new String[]{MediaMetadata.METADATA_KEY_ALBUM_ART,MediaMetadata.METADATA_KEY_ART,MediaMetadata.METADATA_KEY_DISPLAY_ICON}){try{art=metadata.getBitmap(key);}catch(RuntimeException ignored){}if(art!=null&&!art.isRecycled())break;}
        updateArt(art);b.putLong("art_revision",artRevision);if(artwork!=null)b.putParcelable("art",artwork);
        b.putLong("sampled",SystemClock.elapsedRealtime());return b;
    }
    private Bundle idle(Bundle state){selected=null;selectedId="";updateArt(null);state.putLong("art_revision",artRevision);return state;}
    private void updateArt(Bitmap original){
        Bitmap scaled=null;
        if(original!=null&&!original.isRecycled())try{
            scaled=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888);scaled.setDensity(Bitmap.DENSITY_NONE);
            int side=Math.min(original.getWidth(),original.getHeight()),x=(original.getWidth()-side)/2,y=(original.getHeight()-side)/2;
            new Canvas(scaled).drawBitmap(original,new Rect(x,y,x+side,y+side),new Rect(0,0,64,64),new Paint(Paint.FILTER_BITMAP_FLAG));
        }catch(RuntimeException e){scaled=null;}
        if(artwork==null?scaled==null:scaled!=null&&artwork.sameAs(scaled))return;
        artwork=scaled;artRevision++;
    }
    private static String metadataText(MediaMetadata data,String primary,String fallback){
        if(data==null)return "";CharSequence value=data.getText(primary);if(value==null||value.length()==0)value=data.getText(fallback);
        String text=value==null?"":value.toString().replace('\n',' ').replace('\r',' ');return text.length()>160?text.substring(0,160):text;
    }
}
