import dev.ichinomiya.ninebotenhance.core.CanopyLampProtocol;
import dev.ichinomiya.ninebotenhance.core.EscLampProtocol;
import dev.ichinomiya.ninebotenhance.core.LampKind;
import dev.ichinomiya.ninebotenhance.core.LampSettings;
import dev.ichinomiya.ninebotenhance.core.SgLampProtocol;
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
        otherControllers();
    }
    /** The 摩灯客 ESC and canopy controllers and the SG lift: frames, replies and the settings that drive them. */
    private static void otherControllers(){
        // ESC: AA55 "0001" type data CRC16(Modbus, high first) 0D0A.
        CoreTests.check(EscLampProtocol.crc16("123456789".getBytes(java.nio.charset.StandardCharsets.US_ASCII),0,9)==0x4B37,"the CRC is Modbus CRC16 (check value 4B37)");
        byte[] slide=EscLampProtocol.moveTo("123456",50);
        CoreTests.check(slide.length==19&&(slide[0]&0xff)==0xAA&&slide[1]==0x55&&slide[2]=='0'&&slide[5]=='1'&&(slide[6]&0xff)==0xA3
                &&slide[7]=='1'&&slide[12]=='6'&&slide[13]==3&&slide[14]==50&&slide[17]==0x0D&&slide[18]==0x0A,"the slide command carries the ASCII password, direction 3 and the height: "+hex(slide));
        int crc=EscLampProtocol.crc16(slide,0,15);
        CoreTests.check((slide[15]&0xff)==(crc>>8)&&(slide[16]&0xff)==(crc&0xff),"the CRC covers the header through the data, high byte first");
        CoreTests.check(Arrays.equals(EscLampProtocol.query(),EscLampProtocol.frame(0xA9))&&EscLampProtocol.query().length==11,"the firmware query has no data and no password");
        EscLampProtocol.Response ack=EscLampProtocol.parse(EscLampProtocol.frame(0xEE,(byte)0xA3,(byte)1));
        CoreTests.check(ack!=null&&ack.type()==0xEE&&EscLampProtocol.accepted(ack,0xA3)&&EscLampProtocol.receipt(ack,0xA3)&&!EscLampProtocol.accepted(ack,0xA0)&&!EscLampProtocol.receipt(ack,0xA0),"an EE receipt names the command it answers and whether it was accepted");
        EscLampProtocol.Response refused=EscLampProtocol.parse(EscLampProtocol.frame(0xEE,(byte)0xA3,(byte)0));
        CoreTests.check(refused!=null&&EscLampProtocol.receipt(refused,0xA3)&&!EscLampProtocol.accepted(refused,0xA3),"a receipt with result 0 is a refusal");
        byte[] info=EscLampProtocol.frame(0xB9,bytes(1,2,3,1,0,7));
        CoreTests.check(EscLampProtocol.parse(info).type()==0xB9&&EscLampProtocol.parse(info).data().length==6,"the firmware answer parses with its data");
        byte[] corrupt=info.clone();corrupt[8]^=1;
        byte[] noTail=info.clone();noTail[noTail.length-1]=0;
        CoreTests.check(EscLampProtocol.parse(corrupt)==null&&EscLampProtocol.parse(noTail)==null&&EscLampProtocol.parse(bytes(0xAA,0x55,0x30))==null&&EscLampProtocol.parse(null)==null,"a bad CRC, a missing tail, a short buffer and no buffer are all dropped");
        CoreTests.rejects(()->EscLampProtocol.moveTo("12345",50),"a five character ESC password is rejected");
        CoreTests.rejects(()->EscLampProtocol.moveTo("123456",101),"an ESC height above 100 is rejected");
        CoreTests.check(EscLampProtocol.validPassword("12ab56")&&!EscLampProtocol.validPassword("12 456")&&!EscLampProtocol.validPassword(null),"the ESC password is six printable characters");
        // Canopy: B3 len cmd data ck, bare CC position.
        CoreTests.check(Arrays.equals(CanopyLampProtocol.frame(0x10,(byte)2),bytes(0xB3,0x05,0x10,0x02,0xCA)),"the framed command matches the vendor example B3 05 10 02 CA");
        CoreTests.check(Arrays.equals(CanopyLampProtocol.queryConfig(),bytes(0xB3,0x05,0x1D,0x00,0xD5)),"the configuration query is 0x1D with one zero byte");
        CoreTests.check(Arrays.equals(CanopyLampProtocol.moveTo(15),bytes(0xCC,0x0F))&&Arrays.equals(CanopyLampProtocol.moveTo(0),bytes(0xCC,0)),"the position command is the bare CC pair");
        CoreTests.rejects(()->CanopyLampProtocol.moveTo(30),"a canopy position above 29 is rejected");
        byte[] status=CanopyLampProtocol.frame(0x20,bytes(12,1,0,0));
        byte[] config=CanopyLampProtocol.frame(0x21,bytes(0,1,2,3,1,2,3,0,0,0,9,7));
        CoreTests.check(CanopyLampProtocol.position(status)==12&&CanopyLampProtocol.position(config)==7,"the status frame carries the position in byte 3 and the configuration answer in byte 14");
        CoreTests.check(CanopyLampProtocol.position(CanopyLampProtocol.frame(0x22,bytes(26,9,25)))==-1&&CanopyLampProtocol.position(bytes(0xB3,0x09,0x20))==-1&&CanopyLampProtocol.position(bytes(0xCC,5))==-1&&CanopyLampProtocol.position(null)==-1,"other frames, a truncated frame and no frame carry no position");
        CoreTests.check(CanopyLampProtocol.position(CanopyLampProtocol.frame(0x20,bytes(40,0,0,0)))==-1,"a position past the travel is not believed");
        // SG: plain bytes, jog heartbeats and an explicit stop.
        CoreTests.check(Arrays.equals(SgLampProtocol.jog(1,100),bytes(0xA1,1,1,0,0,0,3,0x64,0x1F))&&Arrays.equals(SgLampProtocol.jog(2,50),bytes(0xA1,2,1,0,0,0,3,0x32,0x1F)),"a jog names its direction and PWM");
        CoreTests.check(Arrays.equals(SgLampProtocol.stop(),bytes(0xA1,1,2,0,0,0,1,0x1F))&&Arrays.equals(SgLampProtocol.init(),bytes(0xAF,1,2,3,4,5,6,0xFF)),"the stop and session frames are the vendor constants");
        CoreTests.rejects(()->SgLampProtocol.jog(3,80),"a third jog direction is rejected");
        CoreTests.rejects(()->SgLampProtocol.jog(1,49),"a PWM under 50 is rejected");
        CoreTests.check(SgLampProtocol.clampPwm(10)==50&&SgLampProtocol.clampPwm(200)==100&&SgLampProtocol.clampPwm(75)==75,"the PWM is clamped to 50–100");
        CoreTests.check(SgLampProtocol.lampName("JUXUN-01")&&SgLampProtocol.lampName("juxun")&&!SgLampProtocol.lampName("MOTORE")&&!SgLampProtocol.lampName(null),"the SG scan matches JUXUN in any case");
        CoreTests.check(SgLampProtocol.motor(bytes(0xFE,0,5,0,0,0,0,0,0,0x11,0x10))==1&&SgLampProtocol.motor(bytes(0xFE,0,0,0,0,0,0,0,0,0x22,0x20))==2&&SgLampProtocol.motor(bytes(0xFE,0,0,0,0,0,0,0,0,0x20,0x20))==0&&SgLampProtocol.motor(bytes(0xAF,0xA2,7))==-1,"the FE frame says whether the motor runs and which way");
        CoreTests.check(SgLampProtocol.connected(bytes(0xAF,0xA2,7))&&!SgLampProtocol.connected(bytes(0xFE))&&SgLampProtocol.alarm(bytes(0xD1,1))&&!SgLampProtocol.alarm(bytes(0xD1,0)),"AF opens the session and D1 01 is the stall alarm");
        // Settings per kind.
        CoreTests.check(LampKind.of(2)==LampKind.CANOPY&&LampKind.of(99)==LampKind.TX&&LampKind.TX.needsPassword()&&LampKind.ESC.needsPassword()&&!LampKind.CANOPY.needsPassword()&&!LampKind.SG.needsPassword(),"kinds resolve by id and know whether they take a password");
        CoreTests.check(LampKind.CANOPY.singleCharacteristic()&&LampKind.SG.singleCharacteristic()&&!LampKind.TX.singleCharacteristic()&&!LampKind.ESC.singleCharacteristic(),"the canopy and SG share one characteristic for both directions");
        CoreTests.check(LampKind.TX.matches("MOTORE123",false)&&!LampKind.TX.matches("x",true)&&LampKind.SG.matches("JUXUN",false)&&LampKind.ESC.matches("大灯@01",false)&&LampKind.ESC.matches("x",true)&&LampKind.CANOPY.matches("x",true)&&!LampKind.CANOPY.matches("x",false),"each kind matches by its name or its advertised service");
        LampSettings sg=new LampSettings("A1:B2:C3:D4:E5:F6","",10,8,false,true,LampKind.SG,1000);
        CoreTests.check(sg.bound()&&!sg.kind().positional&&sg.pwm()==50&&sg.jogMs()==1000&&new LampSettings("A1:B2:C3:D4:E5:F6","",20,8,false,true,LampKind.SG,0).jogMs()==LampSettings.MIN_JOG_MS,"an SG binding needs no password, maps the speed onto the PWM and clamps the run time");
        LampSettings canopy=new LampSettings("A1:B2:C3:D4:E5:F6","",10,8,false,true,LampKind.CANOPY,1000);
        CoreTests.check(canopy.bound()&&canopy.top(0,29)==29&&canopy.displayPercent(29,0,29)==100&&canopy.displayPercent(15,0,29)==52&&canopy.stepUnits(0,29)==4,"the canopy travel of thirty positions maps onto 100% with no top margin");
        LampSettings esc=new LampSettings("A1:B2:C3:D4:E5:F6","12ab56",10,8,false,true,LampKind.ESC,1000);
        CoreTests.check(esc.bound()&&esc.top(0,100)==100&&esc.displayPercent(100,0,100)==100&&esc.stepUnits(0,100)==13&&!new LampSettings("A1:B2:C3:D4:E5:F6","12345",10,8,false,true,LampKind.ESC,1000).bound(),"an ESC binding takes any six characters and uses the whole 0–100 travel");
        CoreTests.check(new LampSettings("A1:B2:C3:D4:E5:F6","123456",10,8,false,true).kind()==LampKind.TX&&new LampSettings("A1:B2:C3:D4:E5:F6","123456",10,8,false,true).jogMs()==LampSettings.DEFAULT_JOG_MS,"an old style binding is a TX one");
        CoreTests.check(new LampSettings("A1:B2:C3:D4:E5:F6","123456",10,8,false,true,null,1000).kind()==LampKind.TX,"a missing kind reads as TX");
    }
    private static String hex(byte[] data){StringBuilder b=new StringBuilder();for(byte v:data)b.append(String.format(java.util.Locale.ROOT,"%02X ",v));return b.toString().trim();}
}
