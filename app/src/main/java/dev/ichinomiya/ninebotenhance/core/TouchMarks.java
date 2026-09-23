package dev.ichinomiya.ninebotenhance.core;

/**
 * The touch panel's live contacts in virtual-display buffer pixels, kept for painting rings on the frame. Lifted contacts linger
 * for a moment and fade so that a quick tap is still visible; a contact that stops reporting is dropped as stale.
 */
public final class TouchMarks {
    public static final long LINGER_MS=350,STALE_MS=1500;
    public static final float RADIUS=14f;
    private static final float[] NONE=new float[0];
    private float[] live=NONE,last=NONE;private long stamp=-1;private boolean lifted=true;
    /** {@code points} are x, y pairs of the contacts still down; an empty array means everything lifted. */
    public synchronized void accept(float[] points,long now){
        float[] xy=points==null?NONE:points;
        if((xy.length&1)!=0)throw new IllegalArgumentException("points come in pairs");
        if(xy.length>0){live=xy;last=xy;lifted=false;}else{live=NONE;lifted=true;}
        stamp=now;
    }
    public synchronized float[] visible(long now){
        if(stamp<0)return NONE;
        if(!lifted)return now-stamp<STALE_MS?live:NONE;
        return now-stamp<LINGER_MS?last:NONE;
    }
    /** 1 while contacts are down, then falling to 0 over the linger time. */
    public synchronized float alpha(long now){
        if(stamp<0)return 0;
        if(!lifted)return now-stamp<STALE_MS?1:0;
        return Math.max(0f,1f-(now-stamp)/(float)LINGER_MS);
    }
    public synchronized void clear(){live=NONE;last=NONE;stamp=-1;lifted=true;}
}
