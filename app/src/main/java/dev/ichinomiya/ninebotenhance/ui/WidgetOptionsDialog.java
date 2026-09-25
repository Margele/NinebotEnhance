package dev.ichinomiya.ninebotenhance.ui;

import android.app.*;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import dev.ichinomiya.ninebotenhance.client.FrameClient;
import dev.ichinomiya.ninebotenhance.core.WidgetSettings;
import java.util.List;

/** One dialog shape for the per-widget options: check boxes bound to setting bits plus sliders; saved into the shared widget settings. Labels only, no explanatory copy. */
public final class WidgetOptionsDialog {
    public record Option(String label,int flag){}
    public interface Describe{String of(int value);}
    public record Slider(String label,int min,int max,int value,Describe describe){}
    public interface Apply{WidgetSettings apply(WidgetSettings current,int mask,int[] sliders);}
    private static final Describe SECONDS=v->v+" 秒";
    /** Read-interval sliders move in half-second steps; the speed one moves in tenths of a second. */
    private static final Describe HALF_SECONDS=v->(v%2==0?String.valueOf(v/2):v/2+".5")+" 秒";
    private static final Describe TENTHS=v->String.format(java.util.Locale.ROOT,"%.1f 秒",v/1000f);
    private static final int STEP=WidgetSettings.READ_STEP_MS,MIN_STEPS=WidgetSettings.MIN_READ_MS/STEP,MAX_STEPS=WidgetSettings.MAX_READ_MS/STEP;
    public static void tyres(Activity activity,FrameClient frames,View reference){
        show(activity,frames,reference,"胎压",
                List.of(new Option("前胎压力与温度",WidgetSettings.TYRE_FRONT),new Option("后胎压力与温度",WidgetSettings.TYRE_REAR)),
                List.of(),(current,mask,values)->current.withMask(mask));
    }
    public static void voltage(Activity activity,FrameClient frames,View reference){
        WidgetSettings s=frames.widgetSettings();
        show(activity,frames,reference,"电压",List.of(new Option("曲线图",WidgetSettings.VOLTAGE_CHART),new Option("BMS 优先",WidgetSettings.VOLTAGE_FROM_BMS)),
                List.of(new Slider("曲线时长",WidgetSettings.MIN_CHART_SECONDS,WidgetSettings.MAX_CHART_SECONDS,s.chartSeconds(),SECONDS)),
                (current,mask,values)->current.withMask(mask).chart(values[0]));
    }
    public static void speed(Activity activity,FrameClient frames,View reference){
        WidgetSettings s=frames.widgetSettings();
        show(activity,frames,reference,"速度",List.of(new Option("曲线图",WidgetSettings.SPEED_CHART)),
                List.of(new Slider("曲线时长",WidgetSettings.MIN_CHART_SECONDS,WidgetSettings.MAX_CHART_SECONDS,s.speedChartSeconds(),SECONDS)),
                (current,mask,values)->current.withMask(mask).speedChart(values[0]));
    }
    public static void power(Activity activity,FrameClient frames,View reference){
        WidgetSettings s=frames.widgetSettings();
        show(activity,frames,reference,"功率",List.of(new Option("曲线图",WidgetSettings.POWER_CHART),new Option("BMS 优先",WidgetSettings.POWER_FROM_BMS)),
                List.of(new Slider("曲线时长",WidgetSettings.MIN_CHART_SECONDS,WidgetSettings.MAX_CHART_SECONDS,s.powerChartSeconds(),SECONDS)),
                (current,mask,values)->current.withMask(mask).powerChart(values[0]));
    }
    public static void hold(Activity activity,FrameClient frames,View reference){
        WidgetSettings s=frames.widgetSettings();
        show(activity,frames,reference,"驻车避让",List.of(),
                List.of(new Slider("最小功率",WidgetSettings.MIN_HOLD_POWER,WidgetSettings.MAX_HOLD_POWER,s.holdPowerMin(),v->v+" W"),
                        new Slider("最大功率",WidgetSettings.MIN_HOLD_POWER_MAX,WidgetSettings.MAX_HOLD_POWER_MAX,s.holdPowerMax(),v->v+" W"),
                        new Slider("速度阈值",WidgetSettings.MIN_HOLD_SPEED,WidgetSettings.MAX_HOLD_SPEED,s.holdSpeedMax(),v->v+" km/h"),
                        new Slider("最短持续",WidgetSettings.MIN_HOLD_SECONDS,WidgetSettings.MAX_HOLD_SECONDS,s.holdSeconds(),SECONDS)),
                (current,mask,values)->current.withMask(mask).holdRange(values[0],values[1],values[2],values[3]));
    }
    public static void reads(Activity activity,FrameClient frames,View reference){
        WidgetSettings s=frames.widgetSettings();
        show(activity,frames,reference,"数据读取设置",List.of(),
                List.of(new Slider("胎压读取间隔",WidgetSettings.MIN_TYRE_SECONDS,WidgetSettings.MAX_TYRE_SECONDS,s.tyreIntervalSeconds(),SECONDS),
                        new Slider("电压读取间隔",MIN_STEPS,MAX_STEPS,s.voltageIntervalMs()/STEP,HALF_SECONDS),
                        new Slider("速度读取间隔",WidgetSettings.speedSteps(WidgetSettings.MIN_SPEED_READ_MS),WidgetSettings.speedSteps(WidgetSettings.MAX_READ_MS),
                                WidgetSettings.speedSteps(s.speedIntervalMs()),v->TENTHS.of(WidgetSettings.speedFromSteps(v))),
                        new Slider("功率读取间隔",MIN_STEPS,MAX_STEPS,s.powerIntervalMs()/STEP,HALF_SECONDS)),
                (current,mask,values)->current.withMask(mask).readIntervals(values[0],values[1]*STEP,WidgetSettings.speedFromSteps(values[2]),values[3]*STEP));
    }
    public static void show(Activity activity,FrameClient frames,View reference,String heading,List<Option> options,List<Slider> sliders,Apply apply){
        MirrorUi theme=new MirrorUi(activity,reference);WidgetSettings settings=frames.widgetSettings();
        LinearLayout content=new LinearLayout(activity);content.setOrientation(LinearLayout.VERTICAL);
        int pad=MirrorUi.dp(activity,20),gap=MirrorUi.dp(activity,8);content.setPadding(pad,gap,pad,gap);
        if(!options.isEmpty())content.addView(caption(activity,theme,"选项"));
        CheckBox[] checks=new CheckBox[options.size()];
        for(int i=0;i<options.size();i++){
            CheckBox box=new CheckBox(activity);checks[i]=box;box.setText(options.get(i).label());box.setTextColor(theme.text);box.setTextSize(15);
            box.setButtonTintList(ColorStateList.valueOf(theme.accent));box.setPadding(0,gap,0,gap);box.setChecked(settings.enabled(options.get(i).flag()));
            content.addView(box,new LinearLayout.LayoutParams(-1,-2));
        }
        SeekBar[] bars=new SeekBar[sliders.size()];
        for(int i=0;i<sliders.size();i++){Slider s=sliders.get(i);bars[i]=slider(activity,theme,content,s.label(),s.min(),s.max(),s.value(),s.describe());}
        ScrollView scroll=new ScrollView(activity);scroll.addView(content);
        TextView title=new TextView(activity);title.setText(heading);title.setTextSize(20);title.setTextColor(theme.text);title.setPadding(pad,pad,pad,pad/2);
        AlertDialog dialog=new AlertDialog.Builder(activity).setCustomTitle(title).setView(scroll).setNegativeButton("关闭",null)
                .setPositiveButton("保存",(d,w)->{
                    WidgetSettings current=frames.widgetSettings();int mask=current.mask();
                    for(int i=0;i<checks.length;i++)mask=checks[i].isChecked()?mask|options.get(i).flag():mask&~options.get(i).flag();
                    int[] values=new int[bars.length];for(int i=0;i<bars.length;i++)values[i]=bars[i].getProgress();
                    frames.saveWidgetSettings(apply.apply(current,mask,values));
                }).create();
        dialog.show();dialog.getWindow().setBackgroundDrawable(theme.background(activity,theme.surface,22,false));
        dialog.getButton(-1).setTextColor(theme.accent);dialog.getButton(-2).setTextColor(theme.accent);
    }
    static SeekBar slider(Activity activity,MirrorUi theme,LinearLayout parent,String label,int min,int max,int value,Describe describe){
        LinearLayout header=new LinearLayout(activity);header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(caption(activity,theme,label),new LinearLayout.LayoutParams(0,-2,1));
        TextView shown=new TextView(activity);shown.setTextColor(theme.text);shown.setTextSize(13);shown.setText(describe.of(value));header.addView(shown);
        LinearLayout.LayoutParams headerParams=new LinearLayout.LayoutParams(-1,-2);headerParams.topMargin=MirrorUi.dp(activity,8);parent.addView(header,headerParams);
        SeekBar bar=new SeekBar(activity);bar.setMin(min);bar.setMax(max);bar.setProgress(Math.max(min,Math.min(max,value)));bar.setContentDescription(label);
        bar.setProgressTintList(ColorStateList.valueOf(theme.accent));bar.setThumbTintList(ColorStateList.valueOf(theme.accent));bar.setProgressBackgroundTintList(ColorStateList.valueOf(theme.input));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int progress,boolean fromUser){shown.setText(describe.of(progress));}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
        parent.addView(bar,new LinearLayout.LayoutParams(-1,MirrorUi.dp(activity,44)));
        return bar;
    }
    static TextView caption(Activity activity,MirrorUi theme,String text){TextView v=new TextView(activity);v.setText(text);v.setTextColor(theme.secondary);v.setTextSize(13);v.setPadding(0,MirrorUi.dp(activity,6),0,MirrorUi.dp(activity,2));return v;}
    private WidgetOptionsDialog(){}
}
