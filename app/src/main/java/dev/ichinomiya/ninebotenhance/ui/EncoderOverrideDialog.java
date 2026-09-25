package dev.ichinomiya.ninebotenhance.ui;

import android.app.*;
import android.content.res.ColorStateList;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import dev.ichinomiya.ninebotenhance.client.FrameClient;
import dev.ichinomiya.ninebotenhance.core.DisplaySettings;
import dev.ichinomiya.ninebotenhance.core.EncoderOverride;
import dev.ichinomiya.ninebotenhance.core.SidebarLayout;

/**
 * Overrides: preview statistics, encoder bitrate and frame rate, the composed frame size, the virtual display size and density
 * (shape defaults apply while unchecked), the two background colours, keep-DPI and compat scaling. Labels only, no explanatory copy.
 * The display part is saved with the display settings and needs an idle session; the encoder part applies at once.
 */
public final class EncoderOverrideDialog {
    private static final int STEP=EncoderOverride.BITRATE_STEP_KBPS;
    public static void show(Activity activity,FrameClient frames,View reference,boolean idle){
        MirrorUi theme=new MirrorUi(activity,reference);EncoderOverride current=frames.encoderOverride();DisplaySettings cached=frames.cachedSettings();
        LinearLayout content=new LinearLayout(activity);content.setOrientation(LinearLayout.VERTICAL);
        int pad=MirrorUi.dp(activity,20),gap=MirrorUi.dp(activity,8);content.setPadding(pad,gap,pad,gap);
        CheckBox preview=check(activity,theme,content,"预览统计",current.previewStats());
        CheckBox bitrate=check(activity,theme,content,"覆盖码率",current.overridesBitrate());
        LinearLayout bitrateBlock=block(activity,content,current.overridesBitrate());
        SeekBar bitrateBar=WidgetOptionsDialog.slider(activity,theme,bitrateBlock,"码率",EncoderOverride.MIN_BITRATE_KBPS/STEP,EncoderOverride.MAX_BITRATE_KBPS/STEP,
                (current.overridesBitrate()?current.bitrateKbps():EncoderOverride.DEFAULT_BITRATE_KBPS)/STEP,v->EncoderOverride.describeBitrate(v*STEP));
        CheckBox fps=check(activity,theme,content,"覆盖帧率",current.overridesFps());
        LinearLayout fpsBlock=block(activity,content,current.overridesFps());
        SeekBar fpsBar=WidgetOptionsDialog.slider(activity,theme,fpsBlock,"帧率",EncoderOverride.MIN_FPS,EncoderOverride.MAX_FPS,
                current.overridesFps()?current.fps():EncoderOverride.DEFAULT_FPS,v->v+" fps");
        // The frame override only changes the module's composed frame; the size Ninebot encodes for the vehicle stays its own.
        CheckBox frame=check(activity,theme,content,"覆盖分辨率",current.overridesFrame());
        LinearLayout frameBlock=block(activity,content,current.overridesFrame());
        RadioGroup presets=new RadioGroup(activity);presets.setOrientation(RadioGroup.VERTICAL);
        RadioButton half=radio(activity,theme,presets,"半屏仪表 "+EncoderOverride.HALF_SCREEN_WIDTH+" × "+EncoderOverride.HALF_SCREEN_HEIGHT);
        RadioButton five=radio(activity,theme,presets,"五寸仪表 "+EncoderOverride.FIVE_INCH_WIDTH+" × "+EncoderOverride.FIVE_INCH_HEIGHT);
        RadioButton seven=radio(activity,theme,presets,"七寸仪表 "+EncoderOverride.SEVEN_INCH_WIDTH+" × "+EncoderOverride.SEVEN_INCH_HEIGHT);
        RadioButton custom=radio(activity,theme,presets,"自定义");
        frameBlock.addView(presets,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout customRow=new LinearLayout(activity);customRow.setGravity(Gravity.CENTER_VERTICAL);customRow.setPadding(0,gap,0,0);
        int width=current.overridesFrame()?current.frameWidth():frames.frameWidth(),height=current.overridesFrame()?current.frameHeight():frames.frameHeight();
        EditText widthField=field(activity,theme,customRow,"宽",width),heightField=field(activity,theme,customRow,"高",height);
        frameBlock.addView(customRow,new LinearLayout.LayoutParams(-1,-2));
        boolean customSelected=current.overridesFrame()&&!current.halfScreen()&&!current.fiveInch()&&!current.sevenInch();
        (current.halfScreen()?half:current.fiveInch()?five:current.sevenInch()?seven:customSelected?custom:half).setChecked(true);
        customRow.setVisibility(customSelected?View.VISIBLE:View.GONE);
        presets.setOnCheckedChangeListener((g,id)->customRow.setVisibility(id==custom.getId()?View.VISIBLE:View.GONE));
        // Virtual display: the frame profile's defaults (five-inch 640 x 440, half-screen 240 x 300, seven-inch 760 x 496, all 160 DPI) unless overridden.
        int[] defaults=DisplaySettings.defaultVirtual(frames.frameWidth(),frames.frameHeight());
        CheckBox virtual=check(activity,theme,content,"覆盖虚拟屏",cached.virtualOverride);
        LinearLayout virtualBlock=block(activity,content,cached.virtualOverride);
        LinearLayout virtualRow=new LinearLayout(activity);virtualRow.setGravity(Gravity.CENTER_VERTICAL);
        EditText virtualWidth=field(activity,theme,virtualRow,"宽",cached.virtualOverride?cached.virtualWidth:defaults[0]);
        EditText virtualHeight=field(activity,theme,virtualRow,"高",cached.virtualOverride?cached.virtualHeight:defaults[1]);
        EditText dpi=field(activity,theme,virtualRow,"DPI",cached.virtualOverride?cached.dpi:defaults[2]);
        virtualBlock.addView(virtualRow,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout colours=new LinearLayout(activity);colours.setGravity(Gravity.CENTER_VERTICAL);colours.setPadding(0,gap,0,0);
        BandColorButton dark=colour(activity,theme,colours,"深色背景",cached.backgroundColor),light=colour(activity,theme,colours,"浅色背景",cached.lightBackgroundColor);
        content.addView(colours,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout dpiRow=new LinearLayout(activity);dpiRow.setGravity(Gravity.CENTER_VERTICAL);
        CheckBox keepDpi=check(activity,theme,dpiRow,"保持 DPI",cached.keepPhoneDpi);
        CheckBox compat=check(activity,theme,dpiRow,"兼容缩放",cached.compatScale);
        ((LinearLayout.LayoutParams)keepDpi.getLayoutParams()).width=0;((LinearLayout.LayoutParams)keepDpi.getLayoutParams()).weight=1;
        ((LinearLayout.LayoutParams)compat.getLayoutParams()).width=0;((LinearLayout.LayoutParams)compat.getLayoutParams()).weight=1;
        content.addView(dpiRow,new LinearLayout.LayoutParams(-1,-2));
        Runnable compatSync=()->compat.setEnabled(idle&&keepDpi.isChecked());
        keepDpi.setOnCheckedChangeListener((b,c)->compatSync.run());
        bitrate.setOnCheckedChangeListener((b,checked)->bitrateBlock.setVisibility(checked?View.VISIBLE:View.GONE));
        fps.setOnCheckedChangeListener((b,checked)->fpsBlock.setVisibility(checked?View.VISIBLE:View.GONE));
        frame.setOnCheckedChangeListener((b,checked)->frameBlock.setVisibility(checked?View.VISIBLE:View.GONE));
        virtual.setOnCheckedChangeListener((b,checked)->virtualBlock.setVisibility(checked?View.VISIBLE:View.GONE));
        for(View v:new View[]{virtual,virtualWidth,virtualHeight,dpi,dark,light,keepDpi})v.setEnabled(idle);
        compatSync.run();
        ScrollView scroll=new ScrollView(activity);scroll.addView(content);
        TextView title=new TextView(activity);title.setText("设置覆盖");title.setTextSize(20);title.setTextColor(theme.text);title.setPadding(pad,pad,pad,pad/2);
        AlertDialog dialog=new AlertDialog.Builder(activity).setCustomTitle(title).setView(scroll).setNegativeButton("关闭",null).setPositiveButton("保存",null).create();
        dialog.show();dialog.getWindow().setBackgroundDrawable(theme.background(activity,theme.surface,22,false));
        dialog.getButton(-1).setTextColor(theme.accent);dialog.getButton(-2).setTextColor(theme.accent);
        dialog.getButton(-1).setOnClickListener(v->{
            int frameWidth=0,frameHeight=0;
            if(frame.isChecked()){
                if(half.isChecked()){frameWidth=EncoderOverride.HALF_SCREEN_WIDTH;frameHeight=EncoderOverride.HALF_SCREEN_HEIGHT;}
                else if(five.isChecked()){frameWidth=EncoderOverride.FIVE_INCH_WIDTH;frameHeight=EncoderOverride.FIVE_INCH_HEIGHT;}
                else if(seven.isChecked()){frameWidth=EncoderOverride.SEVEN_INCH_WIDTH;frameHeight=EncoderOverride.SEVEN_INCH_HEIGHT;}
                else{frameWidth=number(widthField);frameHeight=number(heightField);}
            }
            frames.saveEncoderOverride(new EncoderOverride(bitrate.isChecked()?bitrateBar.getProgress()*STEP:0,fps.isChecked()?fpsBar.getProgress():0,preview.isChecked(),frameWidth,frameHeight));
            if(!idle){dialog.dismiss();return;}
            DisplaySettings base=frames.cachedSettings();boolean over=virtual.isChecked();
            DisplaySettings next;
            try{
                next=new DisplaySettings(base.width,base.height,over?number(virtualWidth):base.virtualWidth,over?number(virtualHeight):base.virtualHeight,over?number(dpi):base.dpi,
                        dark.color(),keepDpi.isChecked(),light.color(),over,0,compat.isChecked()&&keepDpi.isChecked());
            }catch(IllegalArgumentException e){Toast.makeText(activity,e.getMessage(),Toast.LENGTH_LONG).show();return;}
            frames.saveSettings(next,frames.cachedApp(),error->{if(error!=null&&!activity.isDestroyed())Toast.makeText(activity,error,Toast.LENGTH_LONG).show();});
            dialog.dismiss();
        });
    }
    private static int number(EditText field){try{return Integer.parseInt(field.getText().toString().trim());}catch(NumberFormatException e){return 0;}}
    private static CheckBox check(Activity activity,MirrorUi theme,LinearLayout parent,String label,boolean checked){
        CheckBox box=new CheckBox(activity);box.setText(label);box.setTextColor(theme.text);box.setTextSize(15);
        box.setButtonTintList(ColorStateList.valueOf(theme.accent));box.setPadding(0,MirrorUi.dp(activity,8),0,MirrorUi.dp(activity,8));box.setChecked(checked);
        parent.addView(box,new LinearLayout.LayoutParams(-1,-2));return box;
    }
    private static RadioButton radio(Activity activity,MirrorUi theme,RadioGroup parent,String label){
        RadioButton button=new RadioButton(activity);button.setId(View.generateViewId());button.setText(label);button.setTextColor(theme.text);button.setTextSize(15);
        button.setButtonTintList(ColorStateList.valueOf(theme.accent));button.setPadding(0,MirrorUi.dp(activity,6),0,MirrorUi.dp(activity,6));
        parent.addView(button,new RadioGroup.LayoutParams(-1,-2));return button;
    }
    private static EditText field(Activity activity,MirrorUi theme,LinearLayout row,String caption,int value){
        TextView label=new TextView(activity);label.setText(caption);label.setTextColor(theme.secondary);label.setTextSize(13);
        LinearLayout.LayoutParams labelParams=new LinearLayout.LayoutParams(-2,-2);labelParams.setMarginEnd(MirrorUi.dp(activity,6));
        if(row.getChildCount()>0)labelParams.setMarginStart(MirrorUi.dp(activity,12));
        row.addView(label,labelParams);
        int pad=MirrorUi.dp(activity,10);
        EditText edit=new EditText(activity);edit.setSingleLine(true);edit.setInputType(InputType.TYPE_CLASS_NUMBER);edit.setText(String.valueOf(value));
        edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});edit.setTextColor(theme.text);edit.setTextSize(15);edit.setBackgroundTintList(null);
        edit.setBackground(theme.background(activity,theme.input,10,false));edit.setPadding(pad,pad,pad,pad);edit.setGravity(Gravity.CENTER);
        row.addView(edit,new LinearLayout.LayoutParams(0,-2,1));return edit;
    }
    private static BandColorButton colour(Activity activity,MirrorUi theme,LinearLayout row,String caption,int color){
        LinearLayout column=new LinearLayout(activity);column.setOrientation(LinearLayout.VERTICAL);
        TextView label=new TextView(activity);label.setText(caption);label.setTextColor(theme.secondary);label.setTextSize(13);label.setPadding(0,0,0,MirrorUi.dp(activity,4));column.addView(label);
        BandColorButton button=new BandColorButton(activity,theme,color);column.addView(button,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,-2,1);if(row.getChildCount()>0)params.setMarginStart(MirrorUi.dp(activity,12));
        row.addView(column,params);return button;
    }
    private static LinearLayout block(Activity activity,LinearLayout parent,boolean visible){
        LinearLayout block=new LinearLayout(activity);block.setOrientation(LinearLayout.VERTICAL);block.setVisibility(visible?View.VISIBLE:View.GONE);
        parent.addView(block,new LinearLayout.LayoutParams(-1,-2));return block;
    }
    private EncoderOverrideDialog(){}
}
