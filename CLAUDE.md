# NinebotEnhance 项目约定

## 界面文案
- 界面上不写说明性文案：对话框里不放提示段落、注解行或“为什么 / 怎么工作”的解释。
- 控件标签只写名称，后面不加括号补充（写“寄存器探测”，不写“寄存器探测（画在画面左侧并写日志）”）。
- 滑块当前值只显示数值和单位（“45 秒”“100 W”），不写条件或后果。
- 需要解释的内容放到 README.md 或 docs/architecture.md，不进 APK。

## 构建与验证
- 主机测试 + APK：`py -3 scripts/build.py --sdk <SDK> --jdk <JDK>`。
- 设备冒烟：`py -3 scripts/test_hud_device.py --sdk <SDK> --jdk <JDK> --adb <adb> --serial <serial>`；安装后 `adb shell am force-stop cn.ninebot.ninebot`。
- 不提交 git。AES 密钥、IV 和解密脚本只存放在 D:\NinebotReverse\analysis，不进模块源码、APK 或仓库。
- 模块只通过九号自身接口发送只读蓝牙指令。唯一例外是 `NaviSender`：车辆会话期间按九号 `DashNaviDataMessenger` 的格式向 TFT 板写导航显示数据（指令 113），数据来自手机导航 App 的实时状态（“控件管理”底部的“手机导航上仪表”）或调试区的“巡航导航测试数据”脚本，以及预览工具栏「深色 / 浅色」选的仪表昼夜主题（`DashboardTheme`：`setDashNaviTheme`，TFT 寄存器 247 位写，只影响仪表显示；`FeatureHooks.installTheme` 同时把九号自己发的这条命令改成模块的选择）；不写任何车辆设置寄存器。
- 大灯（TX 灯控）和 BMS（DL 模块保护板，`bms.BmsController`，只写 FC00 密钥交换和 FC17 读数据，握手在本地用 `core.Secp256k1` 完成、不接厂商服务器）是仅有的两台车辆之外的蓝牙设备，同样只用模块自己的蓝牙权限与连接。大灯：权限写在模块清单里、由 `ui.LampSettingsActivity` 申请，GATT 跑在模块进程的 `lamp.LampController`，不要借九号的蓝牙权限或连接。只写 0x11 握手和 0x12 目标高度，不要补发 0x13 行程配置（控制模式位没有可信来源，实测补发也不触发位置上报，且可能改掉设备的控制模式）；高度只取设备 0x15 上报值，不本地估算——这台设备运行时不主动推 0x15，只在收到 0x11 后回一帧，所以读高度靠重发握手轮询。音量还原要用 `adjustStreamVolume` 反向一档再回读，`setStreamVolume` 会被 ColorOS 的联动流策略拉回去。协议文档在 analysis 里，其中 0x12 示例的累加和写错了（应为 0xA9），以算法为准。
- 导航 App（高德 / 腾讯 / 百度）里的 Hook 只观察、只记日志，不改它们的行为；不要在它们进程里反射调用内部类去“修”布局（试过高德的度量恢复，无效），也不要在投屏结束时强制停止导航 App（试过，会丢掉手机上正在导的目的地）；高德搬到副屏必然被系统重建（密度和触摸屏配置位不在其 configChanges 里），目的地靠 `NaviResume` 用高德公开的 `amapuri://route/plan/` 重新规划，不要试图阻止重建；副屏 DPI 错位用「保持 DPI」（`DisplaySettings.renderPlan` + `RootDisplayMain.applyRenderPlan`，WindowManager 强制尺寸/密度，缓冲区和采集尺寸不变）解决，只有「兼容缩放」（shell 拿不到 WRITE_SECURE_SETTINGS 时的备用路径，`DisplaySettings.compatScale`）才把副屏建成渲染尺寸并在采集时缩回，其他情况不要改采集链路去缩放；导航数据的分析材料在 D:\NinebotReverse\analysis\amap_17、tencent_map、baidu_map。
- 外接触摸屏（`display.RootTouchPanel`）只绑定 USB / 蓝牙总线的设备，永远不碰手机自己的面板；投屏期间用 EVIOCGRAB 独占、结束即释放，不改设备节点权限、不写 sysfs；USB 防休眠只靠模块进程持有 `UsbDeviceConnection`（用户授权一次），不用 root 写 power/control。Root 模式的 `su 2000` 带 `-g 2000 -G 1004`（input 组），su 拒绝时退回无组命令。
