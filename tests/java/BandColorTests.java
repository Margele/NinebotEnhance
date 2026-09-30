import dev.ichinomiya.ninebotenhance.core.*;
import java.nio.ByteBuffer;
import java.util.Map;

public final class BandColorTests {
    static void run(){
        CoreTests.check(BandColor.parse("#242424")==DisplaySettings.DEFAULT_BACKGROUND_COLOR,"default background is opaque near-black grey");
        CoreTests.check(BandColor.parse(" 12a0E3 ")==0xff12a0e3&&BandColor.hex(0xff12a0e3).equals("#12A0E3"),"custom RGB accepts mixed case and round-trips");
        for(String invalid:new String[]{null,"#123","#ff242424","#GG2424"})CoreTests.rejects(()->BandColor.parse(invalid),"invalid colour rejected");
        CoreTests.rejects(()->new DisplaySettings(848,480,640,440,160,0x80242424),"transparency rejected before allocation");
        DisplaySettings d=DisplaySettings.read((key,fallback)->fallback);
        CoreTests.check(d.width==848&&d.height==480&&d.virtualWidth==640&&d.virtualHeight==440&&d.dpi==160,"empty settings use calibrated frame and virtual display defaults");
        DisplaySettings legacy=DisplaySettings.read(Map.of("width",848,"height",440,"dpi",160,"top_inset",40,"top_color",0xff12a0e3)::getOrDefault);
        CoreTests.check(legacy.width==848&&legacy.height==480&&legacy.virtualWidth==640&&legacy.virtualHeight==440&&legacy.backgroundColor==0xff12a0e3,"legacy default migrates to split layout, preserving output size and custom colour");
        DisplaySettings odd=DisplaySettings.read(Map.of("width",900,"height",500,"dpi",160,"top_inset",25)::getOrDefault);
        CoreTests.check(odd.width==900&&odd.height==526&&odd.virtualWidth==678&&odd.virtualHeight==482,"odd legacy total height rounds up without losing extent");
        DisplaySettings zero=DisplaySettings.read(Map.of("width",860,"height",480,"dpi",200,"top_inset",0)::getOrDefault);
        CoreTests.check(zero.height==480&&zero.dpi==200,"explicit zero legacy inset does not grow the output");
        DisplaySettings saved=DisplaySettings.read(Map.of("layout_version",2,"width",900,"height",500,"virtual_width",700,"virtual_height",400,"dpi",160,"background_color",0xff12a0e3,"top_inset",40)::getOrDefault);
        CoreTests.check(saved.width==900&&saved.height==500&&saved.virtualWidth==700&&saved.virtualHeight==400&&saved.backgroundColor==0xff12a0e3,"new settings preserve explicit independent dimensions and ignore legacy inset");
        CoreTests.check(saved.label().contains("700 × 400")&&saved.label().contains("#12A0E3"),"diagnostics report both dimensions and background");
        DisplaySettings kept=DisplaySettings.read(Map.of("layout_version",2,"keep_phone_dpi",1)::getOrDefault),off=DisplaySettings.read(Map.of("layout_version",2,"keep_phone_dpi",0)::getOrDefault);
        CoreTests.check(kept.keepPhoneDpi&&!off.keepPhoneDpi&&!saved.keepPhoneDpi&&!legacy.keepPhoneDpi,"keep-phone-DPI reads as an int flag and is off when unsaved");
        DisplaySettings lit=DisplaySettings.read(Map.of("layout_version",2,"light_background_color",0xfff0f0f0)::getOrDefault);
        CoreTests.check(lit.lightBackgroundColor==0xfff0f0f0&&saved.lightBackgroundColor==DisplaySettings.DEFAULT_LIGHT_BACKGROUND_COLOR&&lit.label().contains("#F0F0F0"),"the light background reads with a default and shows in the label");
        ByteBuffer app=ByteBuffer.allocate(d.virtualWidth*d.virtualHeight*4);app.putInt(app.capacity()-4,0x102030ff);
        ByteBuffer output=ByteBuffer.allocate(d.width*d.height*4);
        PixelPacking.compose(app,d.virtualWidth*4,4,d.virtualWidth,d.virtualHeight,output,d.width,d.height,d.backgroundColor);
        CoreTests.check(output.getInt(0)==0x242424ff&&output.getInt((d.width*40-1)*4)==0x242424ff,"top 40 rows are full-width background");
        ByteBuffer src=ByteBuffer.allocate(4*3*4);for(int i=0;i<12;i++)src.putInt(i*4,(i+1)<<8|0xff);
        ByteBuffer healed=ByteBuffer.allocate(6*5*4);PixelPacking.compose(src,16,4,4,3,healed,6,5,0xff242424,true);
        java.util.function.BiFunction<Integer,Integer,Integer> at=(x,y)->healed.getInt(((2+y)*6+x)*4);
        CoreTests.check(at.apply(1,1)==(6<<8|0xff)&&at.apply(0,1)==(6<<8|0xff)&&at.apply(3,1)==(7<<8|0xff)&&at.apply(1,0)==(6<<8|0xff)&&at.apply(1,2)==(6<<8|0xff)&&at.apply(0,0)==(6<<8|0xff)&&at.apply(3,2)==(7<<8|0xff)&&healed.getInt(0)==0x242424ff,"healed edges repeat the inner ring and leave the interior and background alone");
        ByteBuffer plain=ByteBuffer.allocate(6*5*4);PixelPacking.compose(src,16,4,4,3,plain,6,5,0xff242424,false);
        CoreTests.check(plain.getInt((2*6)*4)==(1<<8|0xff)&&plain.getInt((2*6+3)*4)==(4<<8|0xff),"without healing every source pixel is preserved");
        CoreTests.check(output.getInt(((d.height-1)*d.width+d.virtualWidth-1)*4)==0x102030ff,"last virtual-display pixel survives at bottom-left content corner");
        CoreTests.check(output.limit()==848*480*4&&output.getInt(output.limit()-4)==0x242424ff,"right background fills all the way to the bottom of the fixed canvas");
    }
}
