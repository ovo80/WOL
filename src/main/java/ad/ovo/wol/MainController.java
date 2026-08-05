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


public class MainController {

    private static final Logger log = LoggerFactory.getLogger(MainController.class);


    private enum StatusType { INFO, SUCCESS, ERROR }

    private final WolService wolService = new WolService();


    private final ObservableList<Device> devices = FXCollections.observableArrayList();


    private Device currentDevice;


    private boolean dirty;


    private boolean suppressChangeEvents;


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


    @FXML
    private void initialize() {
        DeviceConfig config = wolService.loadConfig();
        devices.setAll(config.getDevices());

        countField.setText(String.valueOf(config.getSendCount()));


        macField.setTextFormatter(new TextFormatter<>(charFilter("[0-9A-Fa-f:\\-]")));
        portField.setTextFormatter(new TextFormatter<>(charFilter("[0-9]")));
        countField.setTextFormatter(new TextFormatter<>(charFilter("[0-9]")));


        deviceList.setItems(devices);
        deviceList.setCellFactory(list -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(Device item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.displayName());
            }
        });


        deviceList.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && newVal != currentDevice) {
                if (dirty && !confirmDiscardChanges()) {

                    deviceList.getSelectionModel().select(currentDevice);
                    return;
                }
                loadDeviceToForm(newVal);
            }
        });


        bindDirty(nameField.textProperty());
        bindDirty(macField.textProperty());
        bindDirty(broadcastField.textProperty());
        bindDirty(portField.textProperty());

        currentTheme = config.getTheme();
        applyTheme(config.getTheme());


        syncThemeButton(config.getTheme());


        if (!devices.isEmpty()) {
            deviceList.getSelectionModel().select(0);
        } else {
            setStatus("没有可用设备，请点击「＋ 新建」", StatusType.ERROR);
        }
    }


    @FXML
    private void onSend() {
        final int count = parseCount(countField.getText());
        if (count < 0) {
            setStatus("发送失败：连发次数必须为 1-" + AppConfig.SEND_COUNT_MAX + " 之间的数字", StatusType.ERROR);
            return;
        }
        final Device toSend = deviceFromForm();
        if (toSend == null) {
            return;
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


    @FXML
    private void onSave() {
        if (currentDevice == null) {
            setStatus("保存失败：请先选择或新建设备", StatusType.ERROR);
            return;
        }
        Device form = deviceFromForm();
        if (form == null) {
            return;
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

            if (placeholder != null) {
                devices.remove(placeholder);
            }
            devices.add(Math.min(index, devices.size()), removed);
            deviceList.getSelectionModel().select(removed);
            setStatus(e.getMessage(), StatusType.ERROR);
        }
    }


    @FXML
    private void onToggleTheme() {
        String target = AppConfig.THEME_DARK.equals(currentTheme)
                ? AppConfig.THEME_LIGHT : AppConfig.THEME_DARK;
        applyTheme(target);
        currentTheme = target;


        DeviceConfig config = wolService.loadConfig();
        config.setTheme(target);
        try {
            wolService.saveConfig(config);
        } catch (WolException e) {
            log.warn("主题偏好保存失败: {}", e.getMessage());
        }
    }


    private void applyTheme(String theme) {
        Scene scene = statusBanner.getScene();
        if (scene == null) {
            return;
        }
        String css = "/ad/ovo/wol/css/theme-" + theme + ".css";
        scene.getStylesheets().setAll(getClass().getResource(css).toExternalForm());
        syncThemeButton(theme);
        log.debug("已应用主题: {}", theme);
    }


    private void syncThemeButton(String theme) {
        themeButton.setText(AppConfig.THEME_DARK.equals(theme) ? "\u2600" : "\u263E");
    }


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


    private void copyFormTo(Device target, Device form) {
        target.setName(form.getName());
        target.setMacAddress(form.getMacAddress());
        target.setBroadcastAddress(form.getBroadcastAddress());
        target.setPort(form.getPort());
    }


    private DeviceConfig buildConfigFromState() {
        DeviceConfig config = new DeviceConfig();
        config.setTheme(currentTheme);
        config.setDevices(devices);
        int count = parseCount(countField.getText());
        config.setSendCount(count < 0 ? AppConfig.DEFAULT_SEND_COUNT : count);
        return config;
    }


    private void bindDirty(javafx.beans.value.ObservableValue<?> property) {
        property.addListener((obs, oldVal, newVal) -> {
            if (!suppressChangeEvents) {
                dirty = true;
            }
        });
    }


    private boolean confirmDiscardChanges() {
        return showConfirm("未保存的修改", "当前设备的修改尚未保存，切换后将丢失。是否继续？", "继续");
    }


    private boolean showConfirm(String title, String message, String okText) {
        ButtonType ok = new ButtonType(okText);
        ButtonType cancel = new ButtonType("取消");
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ok, cancel);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setGraphic(null);

        alert.getDialogPane().getStylesheets().add(getClass()
                .getResource("/ad/ovo/wol/css/dialog.css").toExternalForm());
        return alert.showAndWait().filter(ok::equals).isPresent();
    }


    private void setStatus(String message, StatusType type) {
        statusBanner.getStyleClass().removeAll("info", "success", "error");
        statusBanner.getStyleClass().add(type.name().toLowerCase());
        statusBanner.setText(message);
    }


    private void restoreButtons() {
        sendButton.setDisable(false);
        saveButton.setDisable(false);
        newDeviceButton.setDisable(false);
        deleteDeviceButton.setDisable(false);
    }


    private int parsePort(String raw) {
        return parseInRange(raw, AppConfig.PORT_MIN, AppConfig.PORT_MAX);
    }


    private int parseCount(String raw) {
        return parseInRange(raw, AppConfig.SEND_COUNT_MIN, AppConfig.SEND_COUNT_MAX);
    }


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


    private static UnaryOperator<TextFormatter.Change> charFilter(String allowedRegex) {
        return change -> change.getControlNewText().matches("(?:" + allowedRegex + ")*") ? change : null;
    }
}
