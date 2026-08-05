package ad.ovo.wol.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 设备列表模型（纯数据容器）。
 *
 * <p>只读约定：{@link #getDevices()} 返回不可修改视图，修改须经
 * {@link #addDevice(Device)} 或 {@link #setDevices(List)}。
 */
public class DeviceConfig {

    private final List<Device> devices = new ArrayList<>();

    /**
     * 设备列表只读视图。
     *
     * <p>注意：视图实时反映容器内容（非快照），但不可增删元素。
     *
     * @return 不可修改的设备列表视图
     */
    public List<Device> getDevices() {
        return Collections.unmodifiableList(devices);
    }

    /**
     * 追加一台设备到列表末尾。
     *
     * @param device 待追加设备（需非 null）
     */
    public void addDevice(Device device) {
        devices.add(device);
    }

    /**
     * 整体替换设备列表（清空后拷贝）。
     *
     * <p>注意：浅拷贝——新旧列表共享设备元素引用，入参后续对元素本身的
     * 修改会反映到本容器。
     *
     * @param newDevices 新列表
     */
    public void setDevices(List<Device> newDevices) {
        devices.clear();
        devices.addAll(newDevices);
    }
}
