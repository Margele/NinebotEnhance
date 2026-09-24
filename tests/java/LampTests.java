import dev.ichinomiya.ninebotenhance.core.LampSettings;
import dev.ichinomiya.ninebotenhance.core.LampState;
import dev.ichinomiya.ninebotenhance.core.SidebarLayout;
import dev.ichinomiya.ninebotenhance.core.TxLampProtocol;
import dev.ichinomiya.ninebotenhance.core.WidgetSettings;
import java.util.Arrays;
import java.util.List;

final class LampTests {
    private static byte[] bytes(int... values){byte[] out=new byte[values.length];for(int i=0;i<values.length;i++)out[i]=(byte)values[i];return out;}
    static void run(){
        // The integration note's worked example prints 0x209 for this sum, but 0xAA+0x55+0x12+0x02+0x64+0x32 is 0x1A9; the
        // checksum follows the algorithm the note also states and its reference implementation, not that arithmetic slip.
        byte[] move=TxLampProtocol.moveTo(100,50);
        CoreTests.check(Arrays.equals(move,bytes(0xAA,0x55,0x12,0x02,0x64,0x32,0xA9)),"height command is the framed sum of its own bytes: "+hex(move));
        CoreTests.check(TxLampProtocol.sign(bytes(0xAA,0x55,0x12,0x02,0x64,0x32),6)==0xA9,"the checksum is the low byte of the running sum");
        byte[] auth=TxLampProtocol.auth("123456");
        CoreTests.check(auth.length==11&&auth[2]==0x11&&auth[3]==6&&auth[4]=='1'&&auth[9]=='6'&&(auth[10]&0xff)==TxLampProtocol.sign(auth,10),"the handshake carries the password as ASCII digits");
        CoreTests.check(Arrays.equals(TxLampProtocol.password("098765"),TxLampProtocol.frame(0x16,"098765".getBytes(java.nio.charset.StandardCharsets.US_ASCII))),"changing the password reuses the handshake shape");
        for(String invalid:new String[]{null,"12345","1234567","12345a"})CoreTests.check(!TxLampProtocol.validPassword(invalid),"a password that is not six digits is rejected");
        CoreTests.rejects(()->TxLampProtocol.auth("abcdef"),"a non numeric password never reaches the device");
        CoreTests.rejects(()->TxLampProtocol.moveTo(101,50),"a height above 100 is rejected");
        CoreTests.rejects(()->TxLampProtocol.moveTo(50,101),"a speed above 100 is rejected");

        byte[] report=TxLampProtocol.frame(TxLampProtocol.CMD_STATE,bytes(42,50,10,90));
        TxLampProtocol.Report parsed=TxLampProtocol.parse(report);
        CoreTests.check(parsed!=null&&parsed.state()&&parsed.position()==42&&parsed.speed()==50&&parsed.low()==10&&parsed.high()==90,"the position report carries height, speed and both travel limits");
        TxLampProtocol.Report check=TxLampProtocol.parse(TxLampProtocol.frame(TxLampProtocol.CMD_SELF_CHECK,bytes(2,1)));
        CoreTests.check(check!=null&&!check.state()&&check.selfCheck()==2&&check.direction()==1&&check.position()==-1,"the self check report carries its mode and direction only");
        CoreTests.check(TxLampProtocol.parse(TxLampProtocol.frame(TxLampProtocol.CMD_AUTH,bytes(1)))==null,"an execution receipt is not a state report");

        byte[] broken=report.clone();broken[broken.length-1]^=0x01;
        CoreTests.check(TxLampProtocol.payload(broken)==null&&TxLampProtocol.parse(broken)==null&&TxLampProtocol.command(broken)==-1,"a frame whose checksum does not hold is dropped");
        CoreTests.check(TxLampProtocol.payload(bytes(0x55,0xAA,0x15,0x00,0x00))==null&&TxLampProtocol.payload(bytes(0xAA,0x55,0x15))==null&&TxLampProtocol.payload(null)==null,"a wrong header, a truncated buffer and no buffer are all dropped");
        CoreTests.check(TxLampProtocol.accepted(TxLampProtocol.frame(TxLampProtocol.CMD_AUTH,bytes(1)))
                &&!TxLampProtocol.accepted(TxLampProtocol.frame(TxLampProtocol.CMD_AUTH,bytes(0)))
                &&!TxLampProtocol.accepted(TxLampProtocol.frame(TxLampProtocol.CMD_STATE,bytes(1,1,1,1))),"only an answer to the handshake with a leading one counts as accepted");
        CoreTests.check(TxLampProtocol.clampPosition(120,10,90)==90&&TxLampProtocol.clampPosition(0,10,90)==10&&TxLampProtocol.clampPosition(50,90,10)==50,"targets stay inside the reported travel limits and an inverted range is ignored");

        LampSettings bound=new LampSettings("a1:b2:c3:d4:e5:f6","123456",12,8,false,true);
        CoreTests.check(bound.mac().equals("A1:B2:C3:D4:E5:F6")&&bound.bound()&&bound.protocolSpeed()==60,"a binding upper cases its address and scales the speed by five");
        CoreTests.check(!new LampSettings("A1:B2:C3:D4:E5:F6","12345",1,2,false,true).bound()&&!new LampSettings("nonsense","123456",1,2,false,true).bound(),"a half filled binding is not usable");
        CoreTests.check(new LampSettings("A1:B2:C3:D4:E5:F6","123456",99,99,false,true).speed()==LampSettings.MAX_SPEED
                &&new LampSettings("A1:B2:C3:D4:E5:F6","123456",0,0,false,true).steps()==LampSettings.MIN_STEPS,"speed and step count are clamped to their ranges");
        LampSettings mapped=new LampSettings("A1:B2:C3:D4:E5:F6","123456",10,8,false,true);
        CoreTests.check(LampSettings.topLimit(0,73)==72&&LampSettings.topLimit(73,0)==72&&LampSettings.topLimit(10,11)==11&&LampSettings.topLimit(5,5)==5&&LampSettings.DEFAULT_STEPS==8&&LampSettings.MIN_STEPS==5&&LampSettings.MAX_STEPS==15,"the usable top is one unit under the reported limit and the step range is 5-15");
        CoreTests.check(mapped.displayPercent(73,0,73)==100&&mapped.displayPercent(72,0,73)==100&&mapped.displayPercent(0,0,73)==0&&mapped.displayPercent(80,0,73)==100&&mapped.displayPercent(-1,0,73)==-1,"the reported travel range remaps onto 100% and the top of travel reads full");
        CoreTests.check(mapped.displayPercent(36,0,73)==50&&mapped.stepUnits(0,73)==Math.round(72/8f)&&mapped.stepPercent()==13,"a mid height scales onto the range and one notch is the range split into the step count");
        LampSettings flipped=new LampSettings("A1:B2:C3:D4:E5:F6","123456",10,8,true,true);
        CoreTests.check(flipped.displayPercent(0,0,73)==100&&flipped.displayPercent(72,0,73)==0&&flipped.displayPercent(73,0,73)==0,"reversing swaps which end of the travel reads as full brightness");
        CoreTests.check(LampSettings.advertisedName("A1:B2:C3:D4:E5:F6").equals("MOTORED4E5F6")&&LampSettings.advertisedName("nonsense").isEmpty(),"the advertising name is MOTORE plus the last three address bytes");
        CoreTests.check(TxLampProtocol.lampName("MOTORED4E5F6")&&TxLampProtocol.lampName("motore123")&&TxLampProtocol.lampName("MOTOR")
                &&!TxLampProtocol.lampName("MOTO")&&!TxLampProtocol.lampName("Mi Band")&&!TxLampProtocol.lampName(null)&&!TxLampProtocol.lampName(""),"the scan matches the MOTOR name stem whatever its case");

        LampState ready=LampState.NONE.withPosition(42,50,10,90);
        CoreTests.check(ready.ready()&&ready.knownPosition()&&ready.text().equals("42")&&ready.unit().equals("%")&&ready.describe().equals("已连接 42%，行程 10–90%"),"a reporting lamp shows its height and the travel range the device reported");
        for(LampState absent:new LampState[]{LampState.NONE,LampState.of(LampState.CONNECTING,""),LampState.of(LampState.REJECTED,""),LampState.of(LampState.IDLE,""),ready.withPhase(LampState.FAILED,"")})
            CoreTests.check(!absent.knownPosition()&&absent.text().equals("未连接")&&absent.unit().isEmpty(),"every state but a live link reads 未连接");
        CoreTests.check(LampState.of(LampState.REJECTED,"").describe().equals("密码错误")&&LampState.of(LampState.FAILED,"蓝牙未开启").describe().equals("连接失败：蓝牙未开启")&&LampState.of(LampState.IDLE,"").describe().equals("未连接")&&!LampState.of(LampState.IDLE,"").ready(),"the lamp screen names why the link is not up");
        CoreTests.check(LampState.NONE.lowLimit()==0&&LampState.NONE.highLimit()==100&&ready.lowLimit()==10&&ready.highLimit()==90,"travel limits fall back to the full span before the first report");

        CoreTests.check(WidgetSettings.CARDS.contains(WidgetSettings.LAMP)&&WidgetSettings.DEFAULT_ORDER.contains(WidgetSettings.LAMP)
                &&(WidgetSettings.OFF_BY_DEFAULT&WidgetSettings.LAMP)!=0&&!WidgetSettings.DEFAULT.enabled(WidgetSettings.LAMP),"the lamp card exists and is off until it is set up");
        CoreTests.check(WidgetSettings.index(WidgetSettings.LAMP)>=0&&WidgetSettings.CONDITIONAL.length==10,"the lamp card takes a display condition like the others");
        WidgetSettings older=WidgetSettings.migrate(5,WidgetSettings.ALL,30,1000,5,30);
        CoreTests.check(!older.enabled(WidgetSettings.LAMP)&&WidgetSettings.migrate(6,WidgetSettings.ALL,30,1000,5,30).enabled(WidgetSettings.LAMP),"saves from before the lamp existed do not inherit it");
        CoreTests.check(WidgetSettings.normalizeOrder(List.of(WidgetSettings.PHONE)).contains(WidgetSettings.LAMP),"an order saved without the lamp gains it");

        WidgetSettings shown=WidgetSettings.DEFAULT.with(WidgetSettings.LAMP,true);
        SidebarLayout.Stack stack=SidebarLayout.arrange(shown,WidgetSettings.LAMP,0,new SidebarLayout.Sizes(100,190,100,190,190,190,120),false);
        CoreTests.check(stack.lamp()!=null&&stack.lamp().bottom()==SidebarLayout.BOTTOM&&stack.lamp().height()==SidebarLayout.LAMP_HEIGHT
                &&stack.lamp().width()==120&&stack.lamp().right()==SidebarLayout.RIGHT&&stack.of(WidgetSettings.LAMP)==stack.lamp(),"the lamp card sits at the bottom right at its measured width");
        CoreTests.check(SidebarLayout.arrange(shown,0,0,new SidebarLayout.Sizes(100,190,100,190),false).lamp()==null,"a hidden lamp card takes no room");
    }
    private static String hex(byte[] data){StringBuilder b=new StringBuilder();for(byte v:data)b.append(String.format(java.util.Locale.ROOT,"%02X ",v));return b.toString().trim();}
}
