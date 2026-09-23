import dev.ichinomiya.ninebotenhance.core.*;
import java.util.List;

final class TouchPanelTests {
    /** A real listing from the test phone: the USB panel, a virtual key device, the phone's own panel, plus a single-touch Bluetooth pad. */
    private static final String LISTING=String.join("\n",
        "add device 1: /dev/input/event13",
        "  bus:      0003",
        "  vendor    1a86",
        "  product   e2e4",
        "  version   0100",
        "  name:     \"SHI Touch\"",
        "  location: \"usb-xhci-hcd.5.auto-1.2/input0\"",
        "  id:       \"\"",
        "  version:  1.0.1",
        "  events:",
        "    KEY (0001): 014a ",
        "    ABS (0003): 0000  : value 834, min 0, max 4096, fuzz 0, flat 0, resolution 19",
        "                0001  : value 1177, min 0, max 4096, fuzz 0, flat 0, resolution 30",
        "                002f  : value 0, min 0, max 4, fuzz 0, flat 0, resolution 0",
        "                0030  : value 0, min 0, max 4096, fuzz 0, flat 0, resolution 30",
        "                0035  : value 0, min 0, max 4096, fuzz 0, flat 0, resolution 19",
        "                0036  : value 0, min 0, max 4096, fuzz 0, flat 0, resolution 30",
        "                0039  : value 0, min 0, max 65535, fuzz 0, flat 0, resolution 0",
        "    MSC (0004): 0005 ",
        "  input props:",
        "    INPUT_PROP_DIRECT",
        "add device 2: /dev/input/event12",
        "  bus:      0003",
        "  vendor    0000",
        "  product   0000",
        "  version   0001",
        "  name:     \"uinput_nav\"",
        "  location: \"\"",
        "  id:       \"\"",
        "  version:  1.0.1",
        "  events:",
        "    KEY (0001): 0067  0069  006a  006c  0073  00d4  00d9 ",
        "  input props:",
        "    <none>",
        "add device 3: /dev/input/event8",
        "  bus:      0000",
        "  vendor    0000",
        "  product   0000",
        "  version   0000",
        "  name:     \"touchpanel\"",
        "  location: \"\"",
        "  id:       \"\"",
        "  version:  1.0.1",
        "  events:",
        "    KEY (0001): 014a ",
        "    ABS (0003): 002f  : value 0, min 0, max 9, fuzz 0, flat 0, resolution 0",
        "                0035  : value 0, min 0, max 35967, fuzz 0, flat 0, resolution 0",
        "                0036  : value 0, min 0, max 39679, fuzz 0, flat 0, resolution 0",
        "                0039  : value 0, min 0, max 65535, fuzz 0, flat 0, resolution 0",
        "  input props:",
        "    INPUT_PROP_DIRECT",
        "add device 4: /dev/input/event3",
        "  bus:      0005",
        "  vendor    046d",
        "  product   b34c",
        "  version   0001",
        "  name:     \"BT Pad\"",
        "  events:",
        "    KEY (0001): 014a ",
        "    ABS (0003): 0000  : value 0, min 0, max 1023, fuzz 0, flat 0, resolution 0",
        "                0001  : value 0, min 0, max 767, fuzz 0, flat 0, resolution 0",
        "  input props:",
        "    <none>");
    static void run(){
        // Identity and persistence.
        TouchPanel panel=new TouchPanel(0x1a86,0xe2e4,"SHI Touch",1,false,null);
        CoreTests.check(panel.bound()&&panel.id().equals("1a86:e2e4")&&panel.label().equals("SHI Touch 1a86:e2e4"),"panel label carries name and ids");
        CoreTests.check(!TouchPanel.NONE.bound()&&TouchPanel.NONE.label().equals("未绑定")&&TouchPanel.NONE.marks()&&!TouchPanel.NONE.calibrated(),"the empty panel is unbound, marks default on, no calibration");
        CoreTests.rejects(()->new TouchPanel(1,2,"x",4,true,null),"rotation beyond three quarter turns is refused");
        CoreTests.check(panel.withMarks(true).marks()&&panel.withMarks(true).sameDevice(panel)&&panel.withMarks(true).rotation()==1,"marks toggle keeps identity and rotation");
        java.util.Map<String,Object> store=new java.util.HashMap<>();
        panel.write((k,v)->store.put(k,v),(k,v)->store.put(k,v));
        TouchPanel back=TouchPanel.read((k,f)->store.containsKey(k)?(Integer)store.get(k):f,(k,f)->store.containsKey(k)?(String)store.get(k):f);
        CoreTests.check(back.equals(panel),"panel round-trips through the settings writers");
        CoreTests.check(TouchPanel.read((k,f)->f,(k,f)->f).equals(TouchPanel.NONE),"missing settings read as unbound");
        CoreTests.check(TouchPanel.read((k,f)->k.equals("touch_rotation")?9:f,(k,f)->"x").equals(TouchPanel.NONE),"a corrupt rotation reads as unbound");
        CoreTests.check(panel.withRotation(3).sameDevice(panel)&&!panel.sameDevice(TouchPanel.NONE),"same device ignores the rotation");
        // Probe listing.
        List<TouchPanelProbe.Device> devices=TouchPanelProbe.parse(LISTING);
        CoreTests.check(devices.size()==4,"four devices parsed");
        TouchPanelProbe.Device shi=devices.get(0);
        CoreTests.check(shi.path().equals("/dev/input/event13")&&shi.bus()==3&&shi.vendor()==0x1a86&&shi.product()==0xe2e4&&shi.name().equals("SHI Touch"),"usb panel identity parsed: "+shi.describe());
        CoreTests.check(shi.touchscreen()&&shi.slots()==5&&shi.x().max()==4096&&shi.y().max()==4096&&shi.direct()&&shi.external(),"multitouch axes come from the MT ranges and the slot count");
        CoreTests.check(!devices.get(1).touchscreen(),"a key-only device is not a touchscreen");
        TouchPanelProbe.Device internal=devices.get(2);
        CoreTests.check(internal.touchscreen()&&!internal.external()&&internal.slots()==10,"the phone panel is a touchscreen but not external");
        TouchPanelProbe.Device pad=devices.get(3);
        CoreTests.check(pad.external()&&pad.touchscreen()&&pad.slots()==0&&pad.x().max()==1023&&pad.y().max()==767,"a single-touch Bluetooth device uses ABS_X / ABS_Y");
        CoreTests.check(TouchPanelProbe.find(devices,panel)==shi,"the bound panel is found by identity");
        CoreTests.check(TouchPanelProbe.find(devices,new TouchPanel(0,0,"touchpanel",0,true,null))==null,"an internal panel is never matched");
        CoreTests.check(TouchPanelProbe.find(devices,new TouchPanel(0x1a86,0xe2e4,"Other",0,true,null))==null,"a different name does not match");
        CoreTests.check(TouchPanelProbe.find(devices,TouchPanel.NONE)==null,"an unbound panel matches nothing");
        CoreTests.check(TouchPanelProbe.parse("").isEmpty()&&TouchPanelProbe.parse(null).isEmpty(),"an empty listing has no devices");
        // Mapping in the four mountings.
        TouchPanelProbe.Axis ax=new TouchPanelProbe.Axis(0,4096),ay=new TouchPanelProbe.Axis(0,4096);
        float[] p=TouchPanelMapping.map(1024,2048,ax,ay,0,640,440);
        CoreTests.close(p[0],160,"upright x");CoreTests.close(p[1],220,"upright y");
        p=TouchPanelMapping.map(1024,2048,ax,ay,1,640,440);
        CoreTests.close(p[0],320,"quarter turn x");CoreTests.close(p[1],110,"quarter turn y");
        p=TouchPanelMapping.map(1024,2048,ax,ay,2,640,440);
        CoreTests.close(p[0],480,"half turn x");CoreTests.close(p[1],220,"half turn y");
        p=TouchPanelMapping.map(1024,2048,ax,ay,3,640,440);
        CoreTests.close(p[0],320,"three quarter x");CoreTests.close(p[1],330,"three quarter y");
        p=TouchPanelMapping.map(-50,9999,ax,ay,0,640,440);
        CoreTests.close(p[0],0,"raw below range clamps");CoreTests.close(p[1],440,"raw above range clamps");
        CoreTests.rejects(()->TouchPanelMapping.map(0,0,ax,ay,4,640,440),"rotation 4 is refused");
        CoreTests.rejects(()->TouchPanelMapping.map(0,0,ax,ay,0,0,440),"an empty picture is refused");
        CoreTests.rejects(()->new TouchPanelProbe.Axis(5,5),"an empty axis is refused");
        // Protocol B: one finger down, move, up.
        TouchPanelTracker t=new TouchPanelTracker(5);
        CoreTests.check(t.multitouch()&&t.event(3,0x39,7).isEmpty()&&t.event(3,0x35,100).isEmpty()&&t.event(3,0x36,200).isEmpty()&&t.event(1,0x14a,1).isEmpty(),"nothing is reported before SYN_REPORT");
        List<TouchPanelTracker.Report> r=t.event(0,0,0);
        CoreTests.check(r.size()==1&&r.get(0).action()==TouchPanelTracker.DOWN&&r.get(0).tracking()==7
                &&r.get(0).contacts().equals(List.of(new TouchPanelTracker.Contact(7,0,100,200))),"first contact is DOWN with slot 0 as the pointer");
        t.event(3,0x35,110);r=t.event(0,0,0);
        CoreTests.check(r.size()==1&&r.get(0).action()==TouchPanelTracker.MOVE&&r.get(0).contacts().get(0).x()==110,"a position change is MOVE");
        CoreTests.check(t.event(0,0,0).isEmpty(),"an unchanged frame reports nothing");
        // Second finger in slot 1, then the first lifts, then the second.
        t.event(3,0x2f,1);t.event(3,0x39,8);t.event(3,0x35,300);t.event(3,0x36,400);r=t.event(0,0,0);
        CoreTests.check(r.size()==1&&r.get(0).action()==TouchPanelTracker.POINTER_DOWN&&r.get(0).tracking()==8&&r.get(0).contacts().size()==2
                &&r.get(0).contacts().get(1).pointer()==1,"second contact is POINTER_DOWN listing both contacts");
        t.event(3,0x2f,0);t.event(3,0x39,-1);r=t.event(0,0,0);
        CoreTests.check(r.size()==1&&r.get(0).action()==TouchPanelTracker.POINTER_UP&&r.get(0).tracking()==7&&r.get(0).contacts().size()==2,"lifting one of two is POINTER_UP and still lists both");
        t.event(3,0x2f,1);t.event(3,0x39,-1);r=t.event(0,0,0);
        CoreTests.check(r.size()==1&&r.get(0).action()==TouchPanelTracker.UP&&r.get(0).contacts().size()==1&&t.contacts().isEmpty(),"the last finger is UP");
        // A slot reused with a new tracking id inside one frame ends one contact and starts another.
        t.event(3,0x2f,0);t.event(3,0x39,9);t.event(3,0x35,1);t.event(3,0x36,2);t.event(0,0,0);
        t.event(3,0x39,10);t.event(3,0x35,3);r=t.event(0,0,0);
        CoreTests.check(r.size()==2&&r.get(0).action()==TouchPanelTracker.UP&&r.get(0).tracking()==9&&r.get(1).action()==TouchPanelTracker.DOWN
                &&r.get(1).tracking()==10&&r.get(1).contacts().get(0).x()==3,"a swapped tracking id is UP then DOWN");
        TouchPanelTracker.Report cancel=t.reset();
        CoreTests.check(cancel!=null&&cancel.action()==TouchPanelTracker.CANCEL&&t.contacts().isEmpty()&&t.reset()==null,"reset cancels the live gesture once");
        // Single-touch device.
        TouchPanelTracker s=new TouchPanelTracker(0);
        CoreTests.check(!s.multitouch(),"no slots means single touch");
        s.event(3,0,50);s.event(3,1,60);s.event(1,0x14a,1);r=s.event(0,0,0);
        CoreTests.check(r.size()==1&&r.get(0).action()==TouchPanelTracker.DOWN&&r.get(0).contacts().equals(List.of(new TouchPanelTracker.Contact(0,0,50,60))),"BTN_TOUCH with ABS_X / ABS_Y is one contact");
        s.event(3,0,55);r=s.event(0,0,0);
        CoreTests.check(r.size()==1&&r.get(0).action()==TouchPanelTracker.MOVE,"single touch moves");
        s.event(1,0x14a,0);r=s.event(0,0,0);
        CoreTests.check(r.size()==1&&r.get(0).action()==TouchPanelTracker.UP,"BTN_TOUCH release is UP");
        CoreTests.check(new TouchPanelTracker(99).event(3,0x2f,98).isEmpty(),"slots are capped without failing");
        // A gate judged where a contact first touches: refused contacts stay invisible until they lift.
        TouchPanelTracker g=new TouchPanelTracker(5);g.setGate((x,y)->x>=1000);
        g.event(3,0x39,1);g.event(3,0x35,10);g.event(3,0x36,10);
        CoreTests.check(g.event(0,0,0).isEmpty(),"a contact refused by the gate reports nothing");
        g.event(3,0x35,2000);CoreTests.check(g.event(0,0,0).isEmpty(),"moving into the accepted area does not revive it");
        g.event(3,0x39,-1);CoreTests.check(g.event(0,0,0).isEmpty(),"its lift reports nothing either");
        g.event(3,0x39,2);g.event(3,0x35,1500);r=g.event(0,0,0);
        CoreTests.check(r.size()==1&&r.get(0).action()==TouchPanelTracker.DOWN,"the next contact inside the area is reported");
        g.event(3,0x35,5);r=g.event(0,0,0);
        CoreTests.check(r.size()==1&&r.get(0).action()==TouchPanelTracker.MOVE&&r.get(0).contacts().get(0).x()==5,"an accepted contact keeps reporting outside the area");
        TouchPanelTracker sg=new TouchPanelTracker(0);sg.setGate((x,y)->false);sg.event(3,0,1);sg.event(3,1,1);sg.event(1,0x14a,1);
        CoreTests.check(sg.event(0,0,0).isEmpty()&&sg.contacts().isEmpty(),"single touch honours the gate");
        sg.event(1,0x14a,0);sg.event(0,0,0);sg.setGate(null);sg.event(1,0x14a,1);
        CoreTests.check(sg.event(0,0,0).size()==1,"without a gate the next single touch reports");
        // Calibration: an exact affine is recovered from the targets, text round-trips, bad taps are refused.
        TouchCalibration truth=new TouchCalibration(1.1f,0.02f,-0.05f,-0.01f,0.95f,0.03f);
        float[][] raw=new float[TouchCalibration.TARGETS.length][];
        for(int i=0;i<raw.length;i++){
            float[] goal=TouchCalibration.TARGETS[i];float det=truth.a()*truth.e()-truth.b()*truth.d();float u=goal[0]-truth.c(),v=goal[1]-truth.f();
            raw[i]=new float[]{(u*truth.e()-truth.b()*v)/det,(truth.a()*v-truth.d()*u)/det};
        }
        TouchCalibration fit=TouchCalibration.solve(raw,TouchCalibration.TARGETS);
        for(int i=0;i<raw.length;i++){float[] hit=fit.apply(raw[i][0],raw[i][1]);CoreTests.close(hit[0],TouchCalibration.TARGETS[i][0],"fit x "+i);CoreTests.close(hit[1],TouchCalibration.TARGETS[i][1],"fit y "+i);}
        CoreTests.check(TouchCalibration.decode(fit.encode())!=null&&TouchCalibration.decode(fit.encode()).encode().equals(fit.encode()),"calibration text round-trips");
        CoreTests.check(TouchCalibration.decode("")==null&&TouchCalibration.decode(null)==null&&TouchCalibration.decode("1,2,3")==null&&TouchCalibration.decode("x,y,z,u,v,w")==null,"bad calibration text reads as none");
        float[][] turned=new float[raw.length][];for(int i=0;i<raw.length;i++){float[] goal=TouchCalibration.TARGETS[i];turned[i]=new float[]{1-goal[1],goal[0]};}
        TouchCalibration rot=TouchCalibration.solve(turned,TouchCalibration.TARGETS);float[] q=rot.apply(0.7f,0.7f);
        CoreTests.close(q[0],0.7f,"a quarter-turn mounted panel calibrates x");CoreTests.close(q[1],0.3f,"a quarter-turn mounted panel calibrates y");
        float[][] line={{0.1f,0.1f},{0.5f,0.5f},{0.9f,0.9f}};
        CoreTests.rejects(()->TouchCalibration.solve(line,line),"collinear taps are refused");
        float[][] noisy=new float[raw.length][];for(int i=0;i<raw.length;i++)noisy[i]=raw[i].clone();noisy[4]=new float[]{raw[4][0]+0.3f,raw[4][1]};
        CoreTests.rejects(()->TouchCalibration.solve(noisy,TouchCalibration.TARGETS),"a tap far from its target is refused");
        CoreTests.rejects(()->new TouchCalibration(0,0,0,0,0,0),"a collapsed map is refused");
        CoreTests.rejects(()->TouchCalibration.solve(new float[][]{{0,0},{1,1}},new float[][]{{0,0},{1,1}}),"two taps are not enough");
        TouchPanel calibrated=panel.withCalibration(fit);
        java.util.Map<String,Object> store2=new java.util.HashMap<>();calibrated.write((k,v)->store2.put(k,v),(k,v)->store2.put(k,v));
        TouchPanel back2=TouchPanel.read((k,f)->store2.containsKey(k)?(Integer)store2.get(k):f,(k,f)->store2.containsKey(k)?(String)store2.get(k):f);
        CoreTests.check(back2.calibrated()&&back2.calibration().encode().equals(fit.encode())&&back2.sameDevice(panel)&&!panel.calibrated()&&back2.withCalibration(null).equals(panel),"calibration persists with the panel");
        // Marks painted on the frame: live contacts, a lingering fade after the lift, staleness.
        TouchMarks m=new TouchMarks();
        CoreTests.check(m.visible(0).length==0&&m.alpha(0)==0,"nothing before the first report");
        m.accept(new float[]{10,20},1000);
        CoreTests.check(java.util.Arrays.equals(m.visible(1100),new float[]{10,20})&&m.alpha(1100)==1,"live contacts show at full strength");
        CoreTests.check(m.visible(1000+TouchMarks.STALE_MS).length==0&&m.alpha(1000+TouchMarks.STALE_MS)==0,"a contact without updates goes stale");
        m.accept(new float[0],1200);
        CoreTests.check(java.util.Arrays.equals(m.visible(1300),new float[]{10,20})&&m.alpha(1300)<1&&m.alpha(1300)>0,"a lifted contact lingers and fades");
        CoreTests.check(m.visible(1200+TouchMarks.LINGER_MS).length==0,"the lingering ring disappears");
        m.accept(new float[]{1,2,3,4},2000);m.clear();
        CoreTests.check(m.visible(2001).length==0&&m.alpha(2001)==0,"clear drops everything");
        CoreTests.rejects(()->m.accept(new float[]{1},0),"odd point arrays are refused");
    }
}
