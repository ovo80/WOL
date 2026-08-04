package ad.ovo.wol.model;

import ad.ovo.wol.config.AppConfig;

/**
 * 设备模型：一台可被唤醒的局域网主机。
 * <p>纯 POJO，不依赖任何 JavaFX / 持久化细节。</p>
 */
public class Device {

    private String name = "";
    private String macAddress = "";
    private String broadcastAddress = AppConfig.DEFAULT_BROADCAST;
    private int port = AppConfig.DEFAULT_WOL_PORT;

    /**
     * 列表展示名：只显示设备名（用户要求不显示 MAC 地址）；
     * 未命名时显示「未命名设备」。
     */
    public String displayName() {
        return name == null || name.isBlank() ? "未命名设备" : name.trim();
    }

    // ---- getters / setters（统一 trim / 兜底） ----

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null ? "" : name.trim();
    }

    public String getMacAddress() {
        return macAddress;
    }

    public void setMacAddress(String macAddress) {
        this.macAddress = macAddress == null ? "" : macAddress.trim();
    }

    public String getBroadcastAddress() {
        return broadcastAddress;
    }

    public void setBroadcastAddress(String broadcastAddress) {
        this.broadcastAddress = broadcastAddress == null ? "" : broadcastAddress.trim();
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }
}
