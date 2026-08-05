package ad.ovo.wol.model;

import ad.ovo.wol.config.AppConfig;


public class Device {

    private String name = "";
    private String macAddress = "";
    private String broadcastAddress = AppConfig.DEFAULT_BROADCAST;
    private int port = AppConfig.DEFAULT_WOL_PORT;


    public String displayName() {
        return name == null || name.isBlank() ? "未命名设备" : name.trim();
    }


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
