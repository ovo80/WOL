# Feature Requests

Capabilities requested by the user.

---

## [FEAT-20260804-001] wol_java_fx_desktop_tool

**Logged**: 2026-08-04T14:20:00+08:00
**Priority**: medium
**Status**: resolved
**Area**: frontend

### Requested Capability
局域网 WOL 唤醒桌面工具（JavaFX）：UDP 广播发送魔术包、设备配置持久化、MVC 分层、非 UI 线程网络 I/O。

### User Context
唤醒局域网内远程计算机；要求状态区不出现"开机成功"类误导文案；配置文件存用户目录避免权限问题。

### Complexity Estimate
medium

### Suggested Implementation
已按需求完整实现于 E:\home\wol（MainApp/MainController/WolUtil/DeviceConfig + main.fxml）。
关键点：JavaFX 20.0.2（JDK 17 兼容）、Task 后台线程 + updateMessage 回显、发送期间禁用按钮、~/.wol-tool/device.properties。

### Metadata
- Frequency: first_time
- Related Features: none

---
