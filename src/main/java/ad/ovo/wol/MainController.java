package ad.ovo.wol;

import ad.ovo.wol.config.AppConfig;
import ad.ovo.wol.exception.WolException;
import ad.ovo.wol.model.Device;
import ad.ovo.wol.model.DeviceConfig;
import ad.ovo.wol.service.WolService;
import ad.ovo.wol.util.WolUtil;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.UnaryOperator;

/**
 * FXML 控制器（MVC - Controller 层）。
 *
 * <p>职责：界面绑定与用户交互编排（多设备列表增删改查、发送、主题切换）；
 * 业务逻辑全部委托 {@link WolService}，网络 I/O 运行在后台 {@link Task} 线程。</p>
 *
 * <p>持久化语义：新建/删除设备立即落盘；字段编辑需点「保存配置」；
 * 主题切换即时落盘。</p>
 *
 * <p><b>文案约定</b>：WOL 协议无法确认目标是否开机，界面只显示
 * 「魔术包已发送」或「发送失败：xxx」，绝不提示“开机成功/唤醒成功”。</p>
 */
public class MainController {

    private static final Logger log = LoggerFactory.getLogger(MainController.class);

    /** 状态类型：决定横幅配色 */
    private enum StatusType { INFO, SUCCESS, ERROR }

    private final WolService wolService = new WolService();

    /** 设备列表（可观察，供 ListView） */
    private final ObservableList<Device> devices = FXCollections.observableArrayList();

    /** 表单当前对应的设备 */
    private Device currentDevice;

    /** 表单是否有未保存修改 */
    private boolean dirty;

    /** 程序回填表单时抑制 dirty 标记 */
    private boolean suppressChangeEvents;

    /** 当前生效主题（与 UI 实际状态强一致；保存配置时以此为唯一事实，避免读盘拿到过期值） */
    private String currentTheme = AppConfig.DEFAULT_THEME;

    @FXML private ListView<Device> deviceList;
    @FXML private TextField nameField;
    @FXML private TextField macField;
    @FXML private TextField broadcastField;
    @FXML private TextField portField;
    @FXML private TextField countField;
    @FXML private Button newDeviceButton;
    @FXML private Button deleteDeviceButton;
    @FXML private Button sendButton;
    @FXML private Button saveButton;
    @FXML private Button themeButton;
    @FXML private Label statusBanner;

    /**
     * FXML 加载完成后自动调用：加载配置、填充设备列表、应用主题。
     */
    @FXML
    private void initialize() {
        DeviceConfig config = wolService.loadConfig();
        devices.setAll(config.getDevices());

        countField.setText(String.valueOf(config.getSendCount()));

        // 输入过滤
        macField.setTextFormatter(new TextFormatter<>(charFilter("[0-9A-Fa-f:\\-]")));
        portField.setTextFormatter(new TextFormatter<>(charFilter("[0-9]")));
        countField.setTextFormatter(new TextFormatter<>(charFilter("[0-9]")));

        // 列表渲染：显示设备展示名
        deviceList.setItems(devices);
        deviceList.setCellFactory(list -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(Device item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.displayName());
            }
        });

        // 设备选择 → 加载表单（若有未保存修改先确认）
        deviceList.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && newVal != currentDevice) {
                if (dirty && !confirmDiscardChanges()) {
                    // 用户取消：回退选中
                    deviceList.getSelectionModel().select(currentDevice);
                    return;
                }
                loadDeviceToForm(newVal);
            }
        });

        // 表单字段变更 → dirty 标记（程序回填时抑制）
        bindDirty(nameField.textProperty());
        bindDirty(macField.textProperty());
        bindDirty(broadcastField.textProperty());
        bindDirty(portField.textProperty());

        currentTheme = config.getTheme();
        applyTheme(config.getTheme());
        // initialize 阶段 scene 尚未挂载，applyTheme 只负责 CSS（由 MainApp 兜底），
        // 主题按钮图标必须在此处独立同步，否则初始显示空白
        syncThemeButton(config.getTheme());

        // 默认选中第一台设备
        if (!devices.isEmpty()) {
            deviceList.getSelectionModel().select(0);
        } else {
            setStatus("没有可用设备，请点击「＋ 新建」", StatusType.ERROR);
        }
    }

    /**
     * 「发送唤醒包」：用表单当前值发送（未保存也生效），后台连发 N 个魔术包。
     */
    @FXML
    private void onSend() {
        final int count = parseCount(countField.getText());
        if (count < 0) {
            setStatus("发送失败：连发次数必须为 1-" + AppConfig.SEND_COUNT_MAX + " 之间的数字", StatusType.ERROR);
            return;
        }
        final Device toSend = deviceFromForm();
        if (toSend == null) {
            return; // deviceFromForm 内部已回显具体错误
        }

        sendButton.setDisable(true);
        saveButton.setDisable(true);
        newDeviceButton.setDisable(true);
        deleteDeviceButton.setDisable(true);
        setStatus("正在连发 " + count + " 个魔术包到 " + toSend.getBroadcastAddress() + ":" + toSend.getPort()
                + " ...", StatusType.INFO);

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws WolException {
                wolService.sendWakeUp(toSend, count);
                updateMessage("魔术包已发送（连发 " + count + " 次）");
                return null;
            }
        };

        task.setOnSucceeded(e -> {
            setStatus(task.getMessage() != null ? task.getMessage() : "魔术包已发送", StatusType.SUCCESS);
            restoreButtons();
        });
        task.setOnFailed(e -> {
            Throwable t = task.getException();
            // 运行时异常（如 NPE）的 getMessage() 可能为 null，兜底显示异常类名
            String detail = (t == null) ? "未知错误"
                    : (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            setStatus("发送失败：" + detail, StatusType.ERROR);
            restoreButtons();
        });
        task.setOnCancelled(e -> {
            setStatus("发送已取消", StatusType.INFO);
            restoreButtons();
        });

        Thread thread = new Thread(task, "wol-send-thread");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 「保存配置」：校验并将当前表单写入当前设备，持久化到程序目录配置文件。
     */
    @FXML
    private void onSave() {
        if (currentDevice == null) {
            setStatus("保存失败：请先选择或新建设备", StatusType.ERROR);
            return;
        }
        Device form = deviceFromForm();
        if (form == null) {
            return; // 错误已回显
        }
        copyFormTo(currentDevice, form);
        try {
            wolService.saveConfig(buildConfigFromState());
            dirty = false;
            deviceList.refresh();
            setStatus("配置已保存到 " + DeviceConfig.getConfigPath(), StatusType.SUCCESS);
        } catch (WolException e) {
            setStatus(e.getMessage(), StatusType.ERROR);
        }
    }

    /**
     * 「＋ 新建」：追加一台新设备并选中，立即持久化。
     */
    @FXML
    private void onNewDevice() {
        if (dirty && !confirmDiscardChanges()) {
            return;
        }
        Device device = new Device();
        device.setName("新设备 " + (devices.size() + 1));
        devices.add(device);
        try {
            wolService.saveConfig(buildConfigFromState());
            deviceList.getSelectionModel().select(device);
            setStatus("已新建设备「" + device.displayName() + "」，请填写信息后保存", StatusType.INFO);
        } catch (WolException e) {
            devices.remove(device);
            setStatus(e.getMessage(), StatusType.ERROR);
        }
    }

    /**
     * 「删除」：移除当前设备（保留至少一台），立即持久化。
     */
    @FXML
    private void onDeleteDevice() {
        if (currentDevice == null) {
            return;
        }
        if (!showConfirm("删除设备", "确定删除「" + currentDevice.displayName() + "」吗？删除后将无法恢复。", "删除")) {
            return;
        }
        int index = devices.indexOf(currentDevice);
        Device removed = currentDevice;
        devices.remove(removed);
        Device placeholder = null;
        if (devices.isEmpty()) {
            placeholder = new Device();
            devices.add(placeholder);
        }
        try {
            wolService.saveConfig(buildConfigFromState());
            int next = Math.min(index, devices.size() - 1);
            deviceList.getSelectionModel().select(next);
            setStatus("设备已删除", StatusType.INFO);
        } catch (WolException e) {
            // 保存失败回滚：撤掉占位设备、恢复被删设备并重新选中，保持内存与磁盘一致
            if (placeholder != null) {
                devices.remove(placeholder);
            }
            devices.add(Math.min(index, devices.size()), removed);
            deviceList.getSelectionModel().select(removed);
            setStatus(e.getMessage(), StatusType.ERROR);
        }
    }

    /** 主题切换：深色 ↔ 浅色，并持久化偏好 */
    @FXML
    private void onToggleTheme() {
        String target = AppConfig.THEME_DARK.equals(currentTheme)
                ? AppConfig.THEME_LIGHT : AppConfig.THEME_DARK;
        applyTheme(target);
        currentTheme = target;

        // 基于磁盘配置仅改主题保存：避免把表单中未保存的设备编辑一并落盘
        DeviceConfig config = wolService.loadConfig();
        config.setTheme(target);
        try {
            wolService.saveConfig(config);
        } catch (WolException e) {
            log.warn("主题偏好保存失败: {}", e.getMessage());
        }
    }

    // ==================== 私有辅助 ====================

    /** 应用指定主题（替换 Scene 的样式表） */
    private void applyTheme(String theme) {
        Scene scene = statusBanner.getScene();
        if (scene == null) {
            return; // initialize 阶段 scene 尚未挂载，由 MainApp 兜底设置
        }
        String css = "/ad/ovo/wol/css/theme-" + theme + ".css";
        scene.getStylesheets().setAll(getClass().getResource(css).toExternalForm());
        syncThemeButton(theme);
        log.debug("已应用主题: {}", theme);
    }

    /** 同步主题按钮图标：深色主题显示 ☀（点击切浅色），浅色显示 ☾ */
    private void syncThemeButton(String theme) {
        themeButton.setText(AppConfig.THEME_DARK.equals(theme) ? "\u2600" : "\u263E");
    }

    /** 加载设备到表单（抑制 dirty） */
    private void loadDeviceToForm(Device device) {
        currentDevice = device;
        suppressChangeEvents = true;
        nameField.setText(device.getName());
        macField.setText(device.getMacAddress());
        broadcastField.setText(device.getBroadcastAddress());
        portField.setText(String.valueOf(device.getPort()));
        suppressChangeEvents = false;
        dirty = false;
    }

    /** 从表单构造设备并做轻校验；非法时回显错误并返回 null */
    private Device deviceFromForm() {
        Device d = new Device();
        d.setName(nameField.getText());
        d.setMacAddress(macField.getText());
        d.setBroadcastAddress(broadcastField.getText());

        int port = parsePort(portField.getText());
        if (port < 0) {
            setStatus("目标端口必须为 1-65535 之间的数字", StatusType.ERROR);
            return null;
        }
        d.setPort(port);

        if (!d.getMacAddress().isEmpty()) {
            try {
                WolUtil.parseMac(d.getMacAddress());
            } catch (IllegalArgumentException e) {
                setStatus("MAC 地址不合法：" + e.getMessage(), StatusType.ERROR);
                return null;
            }
        }
        return d;
    }

    /** 将表单构造值复制到目标设备 */
    private void copyFormTo(Device target, Device form) {
        target.setName(form.getName());
        target.setMacAddress(form.getMacAddress());
        target.setBroadcastAddress(form.getBroadcastAddress());
        target.setPort(form.getPort());
    }

    /**
     * 从当前状态组装配置（设备列表 + 全局设置）。
     * <p>theme 取 {@link #currentTheme}（内存事实源，与 UI 实际主题强一致），
     * 不读盘、不重建，彻底避免主题偏好被覆盖或读回过期值。</p>
     */
    private DeviceConfig buildConfigFromState() {
        DeviceConfig config = new DeviceConfig();
        config.setTheme(currentTheme);
        config.setDevices(devices);
        int count = parseCount(countField.getText());
        config.setSendCount(count < 0 ? AppConfig.DEFAULT_SEND_COUNT : count);
        return config;
    }

    /** 字段变更监听：回填期间抑制，其余置 dirty */
    private void bindDirty(javafx.beans.value.ObservableValue<?> property) {
        property.addListener((obs, oldVal, newVal) -> {
            if (!suppressChangeEvents) {
                dirty = true;
            }
        });
    }

    /** 未保存修改确认框：返回 true 表示用户确认丢弃 */
    private boolean confirmDiscardChanges() {
        return showConfirm("未保存的修改", "当前设备的修改尚未保存，切换后将丢失。是否继续？", "继续");
    }

    /**
     * 统一的确认弹窗：固定 Windows 原生风格（白底、直角、扁平按钮无光晕，不随主题）。
     *
     * @return true 表示用户点击了确认按钮
     */
    private boolean showConfirm(String title, String message, String okText) {
        ButtonType ok = new ButtonType(okText);
        ButtonType cancel = new ButtonType("取消");
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ok, cancel);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setGraphic(null); // 去掉系统警告图标，保持简约
        // 应用 Windows 原生风格样式（独立文件，不随主题）
        alert.getDialogPane().getStylesheets().add(getClass()
                .getResource("/ad/ovo/wol/css/dialog.css").toExternalForm());
        return alert.showAndWait().filter(ok::equals).isPresent();
    }

    /** 分级状态回显：横幅着色 */
    private void setStatus(String message, StatusType type) {
        statusBanner.getStyleClass().removeAll("info", "success", "error");
        statusBanner.getStyleClass().add(type.name().toLowerCase());
        statusBanner.setText(message);
    }

    /** 发送结束恢复按钮可用状态（成功/失败/取消统一走这里） */
    private void restoreButtons() {
        sendButton.setDisable(false);
        saveButton.setDisable(false);
        newDeviceButton.setDisable(false);
        deleteDeviceButton.setDisable(false);
    }

    /** 解析端口输入：非法返回 -1（调用方负责提示） */
    private int parsePort(String raw) {
        return parseInRange(raw, AppConfig.PORT_MIN, AppConfig.PORT_MAX);
    }

    /** 解析发送次数输入：非法返回 -1（调用方负责提示） */
    private int parseCount(String raw) {
        return parseInRange(raw, AppConfig.SEND_COUNT_MIN, AppConfig.SEND_COUNT_MAX);
    }

    /** 通用范围解析：空/非数字/越界均返回 -1 */
    private int parseInRange(String raw, int min, int max) {
        if (raw == null || raw.isBlank()) {
            return -1;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value >= min && value <= max ? value : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 构造只允许白名单字符的 TextFormatter 过滤器 */
    private static UnaryOperator<TextFormatter.Change> charFilter(String allowedRegex) {
        return change -> change.getControlNewText().matches("(?:" + allowedRegex + ")*") ? change : null;
    }
}
