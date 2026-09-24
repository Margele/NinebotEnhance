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
- 导航 App（高德 / 腾讯 / 百度）里的 Hook 只观察、只记日志，不改它们的行为；不要在它们进程里反射调用内部类去“修”布局（试过高德的度量恢复，无效），也不要在投屏结束时强制停止导航 App（试过，会丢掉手机上正在导的目的地）；高德搬到副屏必然被系统重建（密度和触摸屏配置位不在其 configChanges 里），目的地靠 `NaviResume` 用高德公开的 `amapuri://route/plan/` 重新规划，不要试图阻止重建；副屏 DPI 错位用「保持 DPI」解决：`DisplaySettings.renderPlan` 算出手机密度下等 dp 的逻辑尺寸，副屏和 ImageReader 直接按该尺寸和密度创建，`FrameClient.composeScaled` 采集时缩回虚拟屏区域；不要再用 WindowManager 强制尺寸 / 密度（shell 常拿不到 WRITE_SECURE_SETTINGS），也没有「兼容缩放」开关；导航数据的分析材料在 D:\NinebotReverse\analysis\amap_17、tencent_map、baidu_map。
- 外接触摸屏（`display.RootTouchPanel`）只绑定 USB / 蓝牙总线的设备，永远不碰手机自己的面板；投屏期间用 EVIOCGRAB 独占、结束即释放，不改设备节点权限、不写 sysfs；USB 防休眠只靠模块进程持有 `UsbDeviceConnection`（用户授权一次），不用 root 写 power/control。Root 模式的 `su 2000` 带 `-g 2000 -G 1004`（input 组），su 拒绝时退回无组命令。
- 仪表形状的差异都放在 `core.DashboardProfile`（按编码尺寸选档：五寸 848×480、竖向半屏、七寸 1024×600（板子报对齐后的 608 也认），其它横向尺寸通用）：参考帧拟合的缩放和锚点、顶部 / 底部预留、应用区默认值、无配置时的遮挡框、驻车避让是否可用、音量条位置。七寸的数字来自定位图照片（状态栏 0–48、信息带 x 226–760 从 y 549 起、面板 x 760 从 y 457 起、竖条 x 970 从 y 355 起），参考帧不缩放、(848,480) 锚到 (970,457)，应用区 760×496 @ (0,48)、底部预留是整帧高减 544（600 时 56）；不要在 HUD 或 `DisplaySettings` 里另加按尺寸判断的分支，量到新数据改档案。
- 虚拟显示器与投屏分层（`core.DirectSession` 的 `Display` / `Cast`）：投屏结束只 `detachCast`，不关虚拟显示器；预览的「关闭」和返回只收起窗口，只有九号最后一个 Activity 销毁或九号进程退出才关。预览的「横屏」用 `setRequestedOrientation` 转整个窗口（九号 MainActivity 的 configChanges 含 orientation，不重建），收起时还原；预览工具栏没有「通知」按钮，「校准」只在辅助进程打开了绑定的触摸屏（状态 `touch_present`）时出现。按钮行装在 `layout_detail_navigation_card` 里 `layoutHistory` 下面（与 1.1.4 相同，随蓝牙连接出现），不锚到 `layout_detail_location_card`；「全屏投屏」不检查巡航入口是否显示、可用，直接调 `ivCruise` 原监听，检查都交给九号；设置里没有「本地模拟」。
- 驻车避让（`HillHoldDetector`）的速度、功率只来自车辆 `rSpeed` / `rPower`（`RideState`，由 `VehicleHooks` 写入），不接 BMS；「BMS 优先」只影响电压 / 功率卡片的显示值、显示条件和曲线，不要让 BMS 数据进入 `RideState`。
- 模块唯一的网络访问是 `service.UpdateChecker` 对 GitHub Releases API 的版本检查（最多 6 小时一次，只 GET，不上传、不下载 APK，用户自己去 Releases 页面）；不要加别的网络请求。
- 模块不在后台运行：大灯 / BMS 链路只在有人持有时存在（投屏会话快照、九号界面可见时的 `READ` 轮询、模块自己的大灯 / BMS 界面），最后一个持有到期即关闭 GATT、停止 tick；不要恢复 autoConnect 待命或任何后台保活，九号不在前台且没有投屏时模块进程不该有蓝牙连接和定时器。
