import dev.ichinomiya.ninebotenhance.core.EncoderOverride;
import dev.ichinomiya.ninebotenhance.diagnostics.*;
import java.util.List;

final class OverlayTests {
    private static void check(boolean result,String message){CoreTests.check(result,message);}
    static void run() {
        EncoderOverride none=EncoderOverride.NONE;
        check(!none.active()&&none.bitrateBps()==0&&none.intervalMs()==0&&!none.previewStats(),"no override keeps the vehicle configuration");
        EncoderOverride low=new EncoderOverride(100,3,true);
        check(low.bitrateKbps()==EncoderOverride.MIN_BITRATE_KBPS&&low.fps()==EncoderOverride.MIN_FPS&&low.previewStats(),"overrides clamp to the lower bounds");
        EncoderOverride high=new EncoderOverride(99999,999,false);
        check(high.bitrateKbps()==EncoderOverride.MAX_BITRATE_KBPS&&high.fps()==EncoderOverride.MAX_FPS,"overrides clamp to the upper bounds");
        EncoderOverride typical=new EncoderOverride(1500,20,true);
        check(typical.bitrateBps()==1500000&&typical.intervalMs()==50&&typical.overridesBitrate()&&typical.overridesFps(),"bitrate converts to bit/s and fps to the loop interval");
        check(typical.withFps(0).overridesBitrate()&&!typical.withFps(0).overridesFps()&&typical.withBitrate(0).withFps(0).equals(EncoderOverride.NONE.withPreviewStats(true)),"each override can be cleared on its own");
        EncoderOverride framed=new EncoderOverride(0,0,false,EncoderOverride.HALF_SCREEN_WIDTH,EncoderOverride.HALF_SCREEN_HEIGHT);
        check(framed.overridesFrame()&&framed.halfScreen()&&!framed.fiveInch()&&!framed.sevenInch()&&!framed.active()&&framed.describe().endsWith("frame=240x320"),"the half-screen preset overrides only the composed frame");
        check(new EncoderOverride(0,0,false,EncoderOverride.SEVEN_INCH_WIDTH,EncoderOverride.SEVEN_INCH_HEIGHT).sevenInch()&&none.withFrame(1024,600).sevenInch()&&!none.withFrame(1024,608).sevenInch()&&none.withFrame(1024,600).describe().endsWith("frame=1024x600"),"the seven-inch preset is the 1024 x 600 frame");
        check(!none.overridesFrame()&&none.describe().endsWith("frame=配置")&&new EncoderOverride(0,0,false,240,0).equals(none)&&none.withFrame(848,480).fiveInch(),"a missing side means the configured frame");
        check(new EncoderOverride(0,0,false,100,5000).withFrame(101,321).frameWidth()==160&&new EncoderOverride(0,0,false,100,5000).frameHeight()==1920&&none.withFrame(101,321).frameHeight()==320,"custom frames clamp to 160-1920 and even sides");
        check(EncoderOverride.describeBitrate(1500).equals("1.50 Mbps")&&EncoderOverride.describeBitrate(800).equals("800 kbps"),"bitrate label switches units at 1 Mbps");
        check(typical.describe().contains("bitrate=1500kbps")&&none.describe().contains("原配置"),"override description names the source of each value");

        StreamStats stats=new StreamStats(1000);
        stats.encoded(1200,"encoder",50000);stats.frameSubmitted(1200,"sender.send",50000);
        StreamStats.Snapshot before=stats.snapshot(2000);
        check(before.submittedFrames()==1&&before.submittedBytes()==50000&&before.senderSource().isEmpty()&&before.bandwidthBps()==before.submitBps()&&before.submitBps()>0,"without RTP packets the bandwidth falls back to the frame submission rate");
        stats.packet(1300,"udp.sendTo",1412,"1:100",false);stats.packet(1300,"udp.sendTo",900,"1:100",true);
        StreamStats.Snapshot after=stats.snapshot(2000);
        check(after.packets()==2&&after.sentBytes()==2312&&after.sentFrames()==1&&after.bandwidthBps()==after.sendBps()&&after.sendBps()>0,"RTP packets give the wire-level bandwidth");
        stats.queueDrops(2);stats.queueDrops(5);
        check(stats.snapshot(2000).dropped()==5,"queue drops follow the original absolute counter");
        stats.queueDrops(1);
        check(stats.snapshot(2000).dropped()==6,"a cleared queue restarting from zero does not lose earlier drops");
        stats.queueDrops(-3);check(stats.snapshot(2000).dropped()==6,"negative counters ignored");
        stats.lossReport();stats.receiverReport(64,10);stats.dashboardRequest(3,800000);stats.dashboardRequest(2,15);
        StreamStats.Snapshot loss=stats.snapshot(2000);
        check(loss.lossReports()==1&&loss.lossFraction()==64&&Math.abs(loss.lossPercent()-25)<.001&&loss.cumulativeLost()==10&&loss.requestedBitrate()==800000&&loss.requestedFps()==15,"dashboard feedback is retained");
        stats.receiverReport(300,-1);check(stats.snapshot(2000).lossFraction()==255&&stats.snapshot(2000).cumulativeLost()==10,"loss fraction clamps to 8 bits and unknown cumulative loss keeps the last value");
        stats.stop(2500);stats.queueDrops(9);stats.lossReport();
        check(stats.snapshot(3000).dropped()==6&&stats.snapshot(3000).lossReports()==1,"a stopped session rejects late link feedback");

        check(StreamOverlay.lines(null,null,true).equals(List.of("尚未开始")),"no session shows a placeholder");
        List<String> vehicle=StreamOverlay.lines(loss,20.0,true);
        check(vehicle.size()==3&&vehicle.get(0).startsWith("码率 ")&&vehicle.get(0).contains("  带宽 ")&&!vehicle.get(0).contains("--"),"vehicle overlay shows bitrate and bandwidth");
        check(vehicle.get(1).startsWith("帧率 ")&&vehicle.get(1).contains(" / 20 fps")&&vehicle.get(1).endsWith("帧数 1"),"overlay shows encode fps against the target and the encoded frame count");
        check(vehicle.get(2).equals("丢帧 6  丢包 1 次  25.0% / 10"),"overlay shows queue drops, loss reports and the receiver report");
        List<String> fresh=StreamOverlay.lines(new StreamStats(1000).snapshot(1500),null,true);
        check(fresh.get(0).equals("码率 --  带宽 --")&&fresh.get(1).startsWith("采集 ")&&fresh.get(2).equals("丢帧 0  丢包 0 次"),"unobserved interfaces show as unknown, not zero rates");
        StreamStats local=new StreamStats(1000);local.captured(1100);local.captured(1300);
        List<String> simulation=StreamOverlay.lines(local.snapshot(2000),null,false);
        check(simulation.size()==2&&!simulation.get(0).contains("带宽")&&simulation.get(1).startsWith("采集 ")&&simulation.get(1).endsWith("帧数 2"),"local simulation overlay has no transport lines");
        check(StreamOverlay.lines(stats.snapshot(3000),29.97,true).get(3).equals("已结束")&&StreamOverlay.lines(stats.snapshot(3000),29.97,true).get(1).contains(" / 30.0 fps"),"ended session is marked and fractional targets keep one decimal");
        check(StreamOverlay.rate(1500000).equals("1.50 Mbps")&&StreamOverlay.rate(820000).equals("820 kbps"),"overlay rate units");
    }
}
