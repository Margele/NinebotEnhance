package dev.ichinomiya.ninebotenhance.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Turns the evdev stream of a touch panel into pointer reports, one list per SYN_REPORT. Multitouch panels speak protocol B
 * (slots with tracking ids); anything else is read as a single contact from ABS_X / ABS_Y and BTN_TOUCH.
 */
public final class TouchPanelTracker {
    public static final int EV_SYN=0,EV_KEY=1,EV_ABS=3,SYN_REPORT=0,BTN_TOUCH=0x14a,ABS_MT_TRACKING_ID=0x39;
    /** Same numbering as MotionEvent actions. */
    public static final int DOWN=0,UP=1,MOVE=2,CANCEL=3,POINTER_DOWN=5,POINTER_UP=6;
    public static final int MAX_SLOTS=32;
    /** {@code pointer} is the slot, small and stable for the contact's life, so it becomes the MotionEvent pointer id; {@code tracking} tells contacts apart when a slot is reused at once. */
    public record Contact(int tracking,int pointer,int x,int y){}
    public record Report(int action,int tracking,List<Contact> contacts){}
    /** Judged once per contact where it first touches; a refused contact stays invisible until it lifts. */
    @FunctionalInterface public interface Gate{boolean accept(int x,int y);}
    private final int slots;private final int[] tracking,xs,ys,judged;private final boolean[] ignored;private int slot;private boolean touching,singleJudged,singleIgnored;private int sx,sy;
    private volatile Gate gate;
    private List<Contact> previous=List.of();
    public TouchPanelTracker(int slots){
        this.slots=Math.max(0,Math.min(MAX_SLOTS,slots));
        tracking=new int[Math.max(1,this.slots)];xs=new int[tracking.length];ys=new int[tracking.length];judged=new int[tracking.length];ignored=new boolean[tracking.length];
        Arrays.fill(tracking,-1);Arrays.fill(judged,-1);
    }
    public boolean multitouch(){return slots>0;}
    public List<Contact> contacts(){return previous;}
    public void setGate(Gate value){gate=value;}
    public List<Report> event(int type,int code,int value){
        if(type==EV_ABS){
            if(slots>0){
                if(code==TouchPanelProbe.ABS_MT_SLOT)slot=Math.max(0,Math.min(slots-1,value));
                else if(code==ABS_MT_TRACKING_ID)tracking[slot]=value;
                else if(code==TouchPanelProbe.ABS_MT_POSITION_X)xs[slot]=value;
                else if(code==TouchPanelProbe.ABS_MT_POSITION_Y)ys[slot]=value;
            }else if(code==TouchPanelProbe.ABS_X)sx=value;
            else if(code==TouchPanelProbe.ABS_Y)sy=value;
            return List.of();
        }
        if(type==EV_KEY&&code==BTN_TOUCH){touching=value!=0;return List.of();}
        return type==EV_SYN&&code==SYN_REPORT?sync():List.of();
    }
    /** Forget every contact, e.g. when the device went away; the returned report cancels a gesture that was in progress. */
    public Report reset(){
        List<Contact> before=previous;previous=List.of();Arrays.fill(tracking,-1);Arrays.fill(judged,-1);Arrays.fill(ignored,false);
        touching=false;singleJudged=false;singleIgnored=false;slot=0;
        return before.isEmpty()?null:new Report(CANCEL,-1,before);
    }
    private List<Contact> current(){
        List<Contact> out=new ArrayList<>();Gate judge=gate;
        if(slots>0){
            for(int i=0;i<slots;i++){
                if(tracking[i]<0){judged[i]=-1;ignored[i]=false;continue;}
                if(judged[i]!=tracking[i]){judged[i]=tracking[i];ignored[i]=judge!=null&&!judge.accept(xs[i],ys[i]);}
                if(!ignored[i])out.add(new Contact(tracking[i],i,xs[i],ys[i]));
            }
        }else if(touching){
            if(!singleJudged){singleJudged=true;singleIgnored=judge!=null&&!judge.accept(sx,sy);}
            if(!singleIgnored)out.add(new Contact(0,0,sx,sy));
        }else{singleJudged=false;singleIgnored=false;}
        return out;
    }
    private List<Report> sync(){
        List<Contact> now=current(),before=previous;List<Report> out=new ArrayList<>();
        List<Contact> live=new ArrayList<>(before);
        for(Contact old:before)if(find(now,old)==null){
            out.add(new Report(live.size()==1?UP:POINTER_UP,old.tracking(),List.copyOf(live)));live.remove(old);
        }
        for(Contact contact:now)if(find(before,contact)==null){
            live=refresh(live,now);live.add(contact);
            out.add(new Report(live.size()==1?DOWN:POINTER_DOWN,contact.tracking(),List.copyOf(live)));
        }
        if(out.isEmpty()&&!now.isEmpty()&&!now.equals(before))out.add(new Report(MOVE,-1,List.copyOf(now)));
        previous=now;return out;
    }
    private static Contact find(List<Contact> list,Contact key){
        for(Contact c:list)if(c.tracking()==key.tracking()&&c.pointer()==key.pointer())return c;
        return null;
    }
    private static List<Contact> refresh(List<Contact> live,List<Contact> now){
        List<Contact> out=new ArrayList<>();
        for(Contact c:live){Contact fresh=find(now,c);out.add(fresh==null?c:fresh);}
        return out;
    }
}
