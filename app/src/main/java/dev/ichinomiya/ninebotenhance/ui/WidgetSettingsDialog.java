package dev.ichinomiya.ninebotenhance.ui;

import android.app.*;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.*;
import dev.ichinomiya.ninebotenhance.client.FrameClient;
import dev.ichinomiya.ninebotenhance.core.WidgetSettings;
import java.util.*;

/**
 * Display switches per widget. The cards are listed top down like the screen: the left column first, a draggable divider line, then
 * the right column; rows and the divider reorder by dragging their handles. Every widget has a condition button and those with
 * options a settings button. The volume bar, the read intervals and the hill-hold dodge follow below.
 */
public final class WidgetSettingsDialog {
    public static void show(Activity activity,FrameClient frames,View reference){
        MirrorUi theme=new MirrorUi(activity,reference);WidgetSettings settings=frames.widgetSettings();
        LinearLayout content=new LinearLayout(activity);content.setOrientation(LinearLayout.VERTICAL);
        int pad=MirrorUi.dp(activity,20);content.setPadding(pad,MirrorUi.dp(activity,8),pad,0);
        ScrollView scroll=new ScrollView(activity);
        LinearLayout list=new LinearLayout(activity);list.setOrientation(LinearLayout.VERTICAL);
        LinkedHashMap<Integer,CheckBox> checks=new LinkedHashMap<>();
        content.addView(WidgetOptionsDialog.caption(activity,theme,"左侧"));
        List<Integer> topDown=new ArrayList<>(settings.order());Collections.reverse(topDown);
        for(int flag:topDown)list.addView(flag==WidgetSettings.COLUMN_DIVIDER?divider(activity,theme,list,scroll):row(activity,frames,reference,theme,settings,flag,checks,list,scroll),rowParams(activity));
        content.addView(list);
        content.addView(row(activity,frames,reference,theme,settings,WidgetSettings.VOLUME,checks,null,null),rowParams(activity));
        Button reads=new Button(activity);reads.setText("数据读取设置");theme.button(reads,null);reads.setTextSize(14);
        reads.setOnClickListener(v->WidgetOptionsDialog.reads(activity,frames,reference));
        LinearLayout.LayoutParams readParams=new LinearLayout.LayoutParams(-1,-2);readParams.topMargin=MirrorUi.dp(activity,8);readParams.bottomMargin=MirrorUi.dp(activity,8);content.addView(reads,readParams);
        LinearLayout hold=(LinearLayout)row(activity,frames,reference,theme,settings,WidgetSettings.HILL_HOLD_DODGE,checks,null,null);
        if(!frames.hillHoldSupported())for(int i=0;i<hold.getChildCount();i++)hold.getChildAt(i).setEnabled(false);
        content.addView(hold,rowParams(activity));
        // Live phone navigation relayed to the dashboard (preference navi_live, default on); saved with the rest of the dialog.
        CheckBox naviLive=new CheckBox(activity);naviLive.setText("手机导航上仪表");naviLive.setTextColor(theme.text);naviLive.setTextSize(16);
        naviLive.setButtonTintList(android.content.res.ColorStateList.valueOf(theme.accent));naviLive.setChecked(frames.naviLive());
        LinearLayout naviRow=new LinearLayout(activity);naviRow.setOrientation(LinearLayout.HORIZONTAL);naviRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        naviRow.addView(naviLive,new LinearLayout.LayoutParams(0,-2,1));content.addView(naviRow,rowParams(activity));
        scroll.addView(content);
        TextView title=new TextView(activity);title.setText("控件管理");title.setTextSize(20);title.setTextColor(theme.text);title.setPadding(pad,pad,pad,pad/2);
        AlertDialog dialog=new AlertDialog.Builder(activity).setCustomTitle(title).setView(scroll).setNegativeButton("关闭",null)
                .setPositiveButton("保存",(d,w)->{
                    // Switches and the order live here; options, conditions, intervals and thresholds belong to their own dialogs.
                    WidgetSettings current=frames.widgetSettings();int mask=current.mask();
                    for(Map.Entry<Integer,CheckBox> e:checks.entrySet())mask=e.getValue().isChecked()?mask|e.getKey():mask&~e.getKey();
                    ArrayList<Integer> bottomUp=new ArrayList<>();for(int i=list.getChildCount()-1;i>=0;i--)bottomUp.add((Integer)list.getChildAt(i).getTag());
                    frames.saveWidgetSettings(current.withMask(mask).withOrder(bottomUp));
                    if(naviLive.isChecked()!=frames.naviLive())frames.saveNaviLive(naviLive.isChecked());
                }).create();
        dialog.show();dialog.getWindow().setBackgroundDrawable(theme.background(activity,theme.surface,22,false));
        dialog.getButton(-1).setTextColor(theme.accent);dialog.getButton(-2).setTextColor(theme.accent);
    }
    static String label(int flag){
        return switch(flag){
            case WidgetSettings.PHONE->"手机状态";case WidgetSettings.MUSIC->"音乐";case WidgetSettings.TYRES->"胎压";case WidgetSettings.VOLTAGE->"电压";case WidgetSettings.SPEED->"速度";
            case WidgetSettings.POWER->"功率";case WidgetSettings.NOTIFICATIONS->"通知";case WidgetSettings.VOLUME->"音量";case WidgetSettings.HILL_HOLD_DODGE->"驻车避让";case WidgetSettings.LAMP->"大灯";case WidgetSettings.BMS->"BMS";default->"";
        };
    }
    private static LinearLayout.LayoutParams rowParams(Activity activity){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=MirrorUi.dp(activity,4);return p;}
    static TextView handle(Activity activity,MirrorUi theme){
        TextView handle=new TextView(activity);handle.setText("≡");handle.setTextSize(22);handle.setTextColor(theme.secondary);handle.setGravity(Gravity.CENTER);return handle;
    }
    /** The line between the columns: rows above it are the left column, rows below the right one. */
    private static View divider(Activity activity,MirrorUi theme,LinearLayout list,ScrollView scroll){
        LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);row.setTag(WidgetSettings.COLUMN_DIVIDER);row.setMinimumHeight(MirrorUi.dp(activity,40));
        TextView handle=handle(activity,theme);row.addView(handle,new LinearLayout.LayoutParams(MirrorUi.dp(activity,32),-1));handle.setOnTouchListener(new DragHandle(row,list,scroll));
        View line=new View(activity);line.setBackgroundColor(theme.border);LinearLayout.LayoutParams lineParams=new LinearLayout.LayoutParams(0,MirrorUi.dp(activity,1),1);row.addView(line,lineParams);
        TextView label=new TextView(activity);label.setText("右侧");label.setTextColor(theme.secondary);label.setTextSize(13);label.setPadding(MirrorUi.dp(activity,8),0,MirrorUi.dp(activity,8),0);row.addView(label);
        View tail=new View(activity);tail.setBackgroundColor(theme.border);row.addView(tail,new LinearLayout.LayoutParams(0,MirrorUi.dp(activity,1),1));
        return row;
    }
    /** One row: optional drag handle, switch, the condition button and, where the widget has options, its settings button. */
    private static View row(Activity activity,FrameClient frames,View reference,MirrorUi theme,WidgetSettings settings,int flag,Map<Integer,CheckBox> checks,LinearLayout list,ScrollView scroll){
        LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);row.setTag(flag);
        if(list!=null){TextView handle=handle(activity,theme);row.addView(handle,new LinearLayout.LayoutParams(MirrorUi.dp(activity,32),-1));handle.setOnTouchListener(new DragHandle(row,list,scroll));}
        CheckBox box=new CheckBox(activity);checks.put(flag,box);box.setText(label(flag));box.setTextColor(theme.text);box.setTextSize(16);
        box.setButtonTintList(ColorStateList.valueOf(theme.accent));box.setPadding(0,MirrorUi.dp(activity,9),0,MirrorUi.dp(activity,9));box.setChecked(settings.enabled(flag));
        row.addView(box,new LinearLayout.LayoutParams(0,-2,1));
        if(flag!=WidgetSettings.HILL_HOLD_DODGE)row.addView(small(activity,theme,"条件",v->WidgetConditionDialog.show(activity,frames,reference,flag,label(flag))),smallParams(activity));
        Runnable open=switch(flag){
            case WidgetSettings.TYRES->()->WidgetOptionsDialog.tyres(activity,frames,reference);
            case WidgetSettings.VOLTAGE->()->WidgetOptionsDialog.voltage(activity,frames,reference);
            case WidgetSettings.SPEED->()->WidgetOptionsDialog.speed(activity,frames,reference);
            case WidgetSettings.POWER->()->WidgetOptionsDialog.power(activity,frames,reference);
            case WidgetSettings.NOTIFICATIONS->()->frames.notificationSettings(activity,theme.dark);
            case WidgetSettings.HILL_HOLD_DODGE->()->WidgetOptionsDialog.hold(activity,frames,reference);
            case WidgetSettings.BMS->()->BmsCardDialog.show(activity,frames,reference);
            default->null;
        };
        if(open!=null)row.addView(small(activity,theme,"设置",v->open.run()),smallParams(activity));
        if(flag==WidgetSettings.LAMP)row.addView(small(activity,theme,"管理",v->frames.lampSettings(activity,theme.dark)),smallParams(activity));
        if(flag==WidgetSettings.BMS)row.addView(small(activity,theme,"管理",v->frames.bmsSettings(activity,theme.dark)),smallParams(activity));
        return row;
    }
    private static Button small(Activity activity,MirrorUi theme,String text,View.OnClickListener click){
        Button b=new Button(activity);b.setText(text);b.setAllCaps(false);theme.button(b,null);b.setTextSize(12);
        b.setMinHeight(0);b.setMinimumHeight(0);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(MirrorUi.dp(activity,6),0,MirrorUi.dp(activity,6),0);b.setGravity(Gravity.CENTER);
        b.setOnClickListener(click);return b;
    }
    private static LinearLayout.LayoutParams smallParams(Activity activity){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(MirrorUi.dp(activity,56),MirrorUi.dp(activity,32));p.leftMargin=MirrorUi.dp(activity,6);return p;}
    /**
     * The dragged row follows the finger, the others slide aside, and the drop position becomes its new index; the scroll view stays
     * still meanwhile. Notifications live in the right column only: their row cannot rise above the divider and the divider cannot sink below them.
     */
    static final class DragHandle implements View.OnTouchListener{
        private final View row;private final LinearLayout list;private final ScrollView scroll;
        private float startY;private int from,target,lowest,highest,margin;private int[] tops,heights;
        DragHandle(View row,LinearLayout list,ScrollView scroll){this.row=row;this.list=list;this.scroll=scroll;}
        @Override public boolean onTouch(View v,MotionEvent e){
            int count=list.getChildCount();
            switch(e.getActionMasked()){
                case MotionEvent.ACTION_DOWN:{
                    startY=e.getRawY();from=target=list.indexOfChild(row);margin=MirrorUi.dp(v.getContext(),4);tops=new int[count];heights=new int[count];
                    int divider=-1,notifications=-1;
                    for(int i=0;i<count;i++){View child=list.getChildAt(i);tops[i]=child.getTop();heights[i]=child.getHeight();int tag=(Integer)child.getTag();if(tag==WidgetSettings.COLUMN_DIVIDER)divider=i;else if(tag==WidgetSettings.NOTIFICATIONS)notifications=i;}
                    int tag=(Integer)row.getTag();lowest=0;highest=count-1;
                    if(tag==WidgetSettings.NOTIFICATIONS&&divider>=0)lowest=divider+1;
                    if(tag==WidgetSettings.COLUMN_DIVIDER&&notifications>=0)highest=notifications-1;
                    row.setAlpha(.7f);row.setTranslationZ(MirrorUi.dp(v.getContext(),4));
                    if(scroll!=null)scroll.requestDisallowInterceptTouchEvent(true);return true;}
                case MotionEvent.ACTION_MOVE:{
                    if(tops==null)return true;
                    float dy=e.getRawY()-startY;row.setTranslationY(dy);float center=tops[from]+heights[from]/2f+dy;
                    int next=0;for(int i=0;i<count;i++){if(i!=from&&tops[i]+heights[i]/2f<center)next++;}
                    next=Math.max(lowest,Math.min(highest,next));
                    if(next!=target){target=next;int shift=heights[from]+margin;for(int i=0;i<count;i++){View child=list.getChildAt(i);if(child==row)continue;child.setTranslationY(from<target&&i>from&&i<=target?-shift:from>target&&i>=target&&i<from?shift:0);}}
                    return true;}
                case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:{
                    for(int i=0;i<count;i++)list.getChildAt(i).setTranslationY(0);
                    row.setAlpha(1);row.setTranslationZ(0);
                    int destination=target;boolean commit=e.getActionMasked()==MotionEvent.ACTION_UP&&destination!=from;target=from;tops=null;
                    // Re-parenting the row inside this dispatch cancels the very touch being handled; do it once the event is over.
                    if(commit)list.post(()->{if(list.indexOfChild(row)>=0&&destination<list.getChildCount()){list.removeView(row);list.addView(row,destination);}});
                    if(e.getActionMasked()==MotionEvent.ACTION_UP)v.performClick();
                    return true;}
                default:return false;
            }
        }
    }
    private WidgetSettingsDialog(){}
}
