/*
 * WOL 唤醒工具 - 主界面控制器。
 *
 * Copyright (c) 2026 ovo80
 * MIT License. See the LICENSE file in the project root for details.
 */
package ad.ovo.wol.controller;

import ad.ovo.wol.common.config.AppConfig;
import ad.ovo.wol.common.exception.WolException;
import ad.ovo.wol.model.AppSettings;
import ad.ovo.wol.model.Device;
import ad.ovo.wol.model.DeviceConfig;
import ad.ovo.wol.service.ConfigService;
import ad.ovo.wol.service.WolService;
import ad.ovo.wol.service.impl.WolServiceImpl;
import ad.ovo.wol.util.WolUtil;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 主界面控制器：设备列表、编辑表单与发送流程的交互编排。
 *
 * <p>分层约定：网络发送委托 {@link WolService}，持久化委托 {@link ConfigService}，本类只翻译界面状态与用户意图。
 *
 * <p>可变状态（均在 FX 线程内读写）：{@link #devices} 设备列表（持久化的唯一事实来源）、{@link #currentDevice} 当前编辑设备、 {@link
 * #dirty} 未保存标记、{@link #currentTheme} 当前主题。
 *
 * <p>线程模型：唯一后台线程为发送线程 {@code wol-send-thread}（见 {@link #onSend()}），其结果经 Task 回调回到 FX 线程； 其余方法一律在 FX
 * 线程执行。
 */
public class MainController {

  private static final Logger log = LoggerFactory.getLogger(MainController.class);

  /** 状态横幅样式类别，对应 CSS 类 info/success/error */
  private enum StatusType {
    INFO,
    SUCCESS,
    ERROR
  }

  private final WolService wolService = new WolServiceImpl();

  /** 设备列表，与左侧 ListView 绑定；增删后需同步持久化 */
  private final ObservableList<Device> devices = FXCollections.observableArrayList();

  /** 当前正在表单中编辑的设备；null 表示尚未选择 */
  private Device currentDevice;

  /** 表单是否有未保存修改（切换设备/新建时用于丢弃确认） */
  private boolean dirty;

  /** 表单回填期间置 true，抑制字段监听器把回填误判为修改 */
  private boolean suppressChangeEvents;

  /** 当前主题标识：dark / light（见 {@link AppConfig#THEME_DARK}） */
  private String currentTheme = AppConfig.DEFAULT_THEME;

  // FXML 注入控件（fx:id 与 main.fxml 一一对应）
  @FXML private ListView<Device> deviceList;
  @FXML private TextField nameField;
  @FXML private TextField macField;
  @FXML private TextField broadcastField;
  @FXML private TextField portField;
  @FXML private TextField countField;
  @FXML private CheckBox srvModeBox;
  @FXML private Label broadcastLabel;
  @FXML private Label portLabel;
  @FXML private Button newDeviceButton;
  @FXML private Button deleteDeviceButton;
  @FXML private Button sendButton;
  @FXML private Button saveButton;
  @FXML private Button themeButton;
  @FXML private Label statusBanner;

  /**
   * FXML 加载完成后由 JavaFX 框架回调：装载配置、装配输入过滤与监听器。
   *
   * <p>数据契约：{@link ConfigService#load()} 保证列表非空（至少一台默认设备）；{@link ConfigService#loadSettings()}
   * 保证主题/次数为合法值（非法回退默认）。本阶段 {@code statusBanner.getScene()} 为 null，{@link #applyTheme(String)}
   * 只同步按钮图案，不触碰场景样式表。
   *
   * <p>副作用：读取设备与设置两个配置文件（I/O），可能触发旧配置迁移写盘。
   */
  @FXML
  private void initialize() {
    DeviceConfig config = ConfigService.load();
    AppSettings settings = ConfigService.loadSettings();
    devices.setAll(config.getDevices());

    countField.setText(String.valueOf(settings.getSendCount()));

    // 输入白名单：MAC 仅允许十六进制 + 冒号/连字符；端口与次数仅允许数字
    macField.setTextFormatter(new TextFormatter<>(charFilter("[0-9A-Fa-f:\\-]")));
    portField.setTextFormatter(new TextFormatter<>(charFilter("[0-9]")));
    countField.setTextFormatter(new TextFormatter<>(charFilter("[0-9]")));

    deviceList.setItems(devices);
    // 列表项只展示设备名（归一化逻辑见 Device#displayName）
    deviceList.setCellFactory(
        list ->
            new javafx.scene.control.ListCell<>() {
              @Override
              protected void updateItem(Device item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.displayName());
              }
            });

    // 切换选中：存在未保存修改时先确认，取消则回退原选中项
    deviceList
        .getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (obs, oldVal, newVal) -> {
              if (newVal != null && newVal != currentDevice) {
                if (dirty && !confirmDiscardChanges()) {

                  deviceList.getSelectionModel().select(currentDevice);
                  return;
                }
                loadDeviceToForm(newVal);
              }
            });

    // 字段编辑（非回填）即标记 dirty
    bindDirty(nameField.textProperty());
    bindDirty(macField.textProperty());
    bindDirty(broadcastField.textProperty());
    bindDirty(portField.textProperty());

    // SRV 模式开关：切换表单语义（广播→SRV 地址、端口禁用）并标记 dirty
    srvModeBox
        .selectedProperty()
        .addListener(
            (obs, oldVal, newVal) -> {
              if (!suppressChangeEvents) {
                dirty = true;
              }
              applySrvModeUi(newVal);
            });

    currentTheme = settings.getTheme();
    // initialize 阶段 scene 为 null：本调用只同步按钮图案（顺序约定见 applyTheme）
    applyTheme(settings.getTheme());

    if (!devices.isEmpty()) {
      deviceList.getSelectionModel().select(0);
    } else {
      setStatus("没有可用设备，请点击「＋ 新建」", StatusType.ERROR);
    }
  }

  /**
   * 「发送唤醒包」：以表单当前值构造设备，后台线程连发魔术包。
   *
   * <p>约束：表单无需先保存即可发送；发送期间禁用全部操作按钮防止重复提交，结果（成功/失败/取消）回显状态后统一恢复按钮。
   *
   * <p>SRV 模式（{@link #srvModeBox} 选中）：跳过端口字段，由后台线程解析 SRV 记录得到目标地址与端口，成功后把 {@code IP:端口}
   * 回填到端口框（程序化写入，不触发脏标记）。
   *
   * <p>副作用：发起 UDP 网络发送与 DNS 查询（后台线程，阻塞时长约为连发次数 × 100ms 加解析耗时），不修改配置与设备对象。
   */
  @FXML
  private void onSend() {
    // 次数非法（-1 哨兵）时直接提示，不进入发送流程
    final int count = parseCount(countField.getText());
    if (count < 0) {
      setStatus("发送失败：连发次数必须为 1-" + AppConfig.SEND_COUNT_MAX + " 之间的数字", StatusType.ERROR);
      return;
    }
    final boolean srvMode = srvModeBox.isSelected();
    final Device toSend;
    try {
      // 表单校验失败抛 IllegalArgumentException，消息直接展示
      toSend = deviceFromForm();
    } catch (IllegalArgumentException e) {
      setStatus(e.getMessage(), StatusType.ERROR);
      return;
    }

    // SRV 模式下广播字段是记录名、端口框是解析回显位，均不直接参与目标拼接
    final AtomicReference<String> resolvedTarget = new AtomicReference<>();
    sendButton.setDisable(true);
    saveButton.setDisable(true);
    newDeviceButton.setDisable(true);
    deleteDeviceButton.setDisable(true);
    setStatus(
        srvMode
            ? "正在解析 SRV 并连发 " + count + " 个魔术包 ..."
            : "正在连发 "
                + count
                + " 个魔术包到 "
                + toSend.getBroadcastAddress()
                + ":"
                + toSend.getPort()
                + " ...",
        StatusType.INFO);

    // 网络/DNS I/O 移出 FX 线程；UI 更新仅经 Task 回调（内部 Platform.runLater）完成
    Task<Void> task =
        new Task<>() {
          @Override
          protected Void call() throws WolException {
            if (srvMode) {
              // 解析结果（IP:端口）经 AtomicReference 传回 FX 线程
              resolvedTarget.set(
                  wolService.sendWakeUpViaSrv(toSend.getMacAddress(), toSend.getSrvName(), count));
            } else {
              wolService.sendWakeUp(toSend, count);
            }
            updateMessage("魔术包已发送（连发 " + count + " 次）");
            return null;
          }
        };

    task.setOnSucceeded(
        e -> {
          String target = resolvedTarget.get();
          if (target != null) {
            // 回显解析结果属程序化展示，不应标记为未保存修改
            suppressChangeEvents = true;
            portField.setText(target);
            suppressChangeEvents = false;
          }
          setStatus(task.getMessage() != null ? task.getMessage() : "魔术包已发送", StatusType.SUCCESS);
          restoreButtons();
        });
    task.setOnFailed(
        e -> {
          Throwable t = task.getException();

          // WolException 的消息面向用户，直接展示；其他异常兜底为类型名
          String detail =
              (t == null)
                  ? "未知错误"
                  : (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
          setStatus("发送失败：" + detail, StatusType.ERROR);
          restoreButtons();
        });
    task.setOnCancelled(
        e -> {
          setStatus("发送已取消", StatusType.INFO);
          restoreButtons();
        });

    // 守护线程：应用退出时不阻塞进程
    Thread thread = new Thread(task, "wol-send-thread");
    thread.setDaemon(true);
    thread.start();
  }

  /**
   * 「保存配置」：表单值写入当前设备并立即持久化。
   *
   * <p>副作用：写盘两个配置文件（I/O，原子写入）；写盘失败时内存中的设备值已被表单覆盖（不回滚），界面提示失败原因。
   */
  @FXML
  private void onSave() {
    if (currentDevice == null) {
      setStatus("保存失败：请先选择或新建设备", StatusType.ERROR);
      return;
    }
    Device form;
    try {
      form = deviceFromForm();
    } catch (IllegalArgumentException e) {
      setStatus(e.getMessage(), StatusType.ERROR);
      return;
    }
    copyFormTo(currentDevice, form);
    try {
      saveAll();
      dirty = false;
      deviceList.refresh();
      setStatus("配置已保存到 " + ConfigService.getConfigPath(), StatusType.SUCCESS);
    } catch (IOException e) {
      setStatus("配置保存失败：" + e.getMessage(), StatusType.ERROR);
    }
  }

  /**
   * 「＋ 新建」：追加一台默认设备并立即落盘。
   *
   * <p>副作用：写盘（I/O）；落盘失败时从列表移除该设备并提示。
   */
  @FXML
  private void onNewDevice() {
    if (dirty && !confirmDiscardChanges()) {
      return;
    }
    Device device = new Device();
    // 默认名带序号便于区分；其余字段使用模型默认值（见 Device）
    device.setName("新设备 " + (devices.size() + 1));
    devices.add(device);
    try {
      saveAll();
      deviceList.getSelectionModel().select(device);
      setStatus("已新建设备「" + device.displayName() + "」，请填写信息后保存", StatusType.INFO);
    } catch (IOException e) {
      devices.remove(device);
      setStatus("配置保存失败：" + e.getMessage(), StatusType.ERROR);
    }
  }

  /**
   * 「删除」：移除当前设备并落盘。
   *
   * <p>不变式：列表不允许为空——删除后若为空则补一台占位设备（与 {@link ConfigService#load()} 的兜底一致）；落盘失败时回滚内存列表。
   *
   * <p>副作用：写盘（I/O）。
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
    // 占位设备仅在删除后列表为空时创建，随下次操作自然替换
    Device placeholder = null;
    if (devices.isEmpty()) {
      placeholder = new Device();
      devices.add(placeholder);
    }
    try {
      saveAll();
      // 选中邻近项：原位置越界时回退到列表末尾
      int next = Math.min(index, devices.size() - 1);
      deviceList.getSelectionModel().select(next);
      setStatus("设备已删除", StatusType.INFO);
    } catch (IOException e) {
      restoreDeviceAfterFailedDelete(removed, index, placeholder);
      setStatus("配置保存失败：" + e.getMessage(), StatusType.ERROR);
    }
  }

  private void restoreDeviceAfterFailedDelete(Device removed, int index, Device placeholder) {
    if (placeholder != null) {
      devices.remove(placeholder);
    }
    devices.add(Math.min(index, devices.size()), removed);
    deviceList.getSelectionModel().select(removed);
  }

  /**
   * 主题切换：dark/light 互切，即时生效并持久化。
   *
   * <p>副作用：写盘 {@code settings.properties}（I/O）；写盘失败仅提示，不影响本次主题在内存中生效。
   */
  @FXML
  private void onToggleTheme() {
    String target =
        AppConfig.THEME_DARK.equals(currentTheme) ? AppConfig.THEME_LIGHT : AppConfig.THEME_DARK;
    applyTheme(target);
    currentTheme = target;

    AppSettings settings = ConfigService.loadSettings();
    settings.setTheme(target);
    try {
      ConfigService.saveSettings(settings);
    } catch (IOException e) {
      log.warn("主题偏好保存失败: {}", e.getMessage());
      setStatus("主题保存失败：" + e.getMessage(), StatusType.ERROR);
    }
  }

  /**
   * 应用主题：先同步按钮图案，再替换场景样式表。
   *
   * <p>调用顺序约定：{@link #syncThemeButton(String)} 必须位于 scene 判空之前——initialize 阶段 scene 为 null，
   * 按钮图案需无条件同步，而样式表操作要求场景已就绪（FxmlLoadTest 有回归断言）。
   *
   * @param theme 主题标识：dark / light
   */
  private void applyTheme(String theme) {
    syncThemeButton(theme);
    Scene scene = statusBanner.getScene();
    if (scene == null) {
      return;
    }
    String css = "/ad/ovo/wol/css/theme-" + theme + ".css";
    scene.getStylesheets().setAll(getClass().getResource(css).toExternalForm());
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
    srvModeBox.setSelected(device.isSrvEnabled());
    portField.setText(String.valueOf(device.getPort()));
    suppressChangeEvents = false;
    dirty = false;
  }

  /**
   * @return 新设备实例，字段来自表单（不修改任何既有对象）
   */
  private Device deviceFromForm() {
    Device d = new Device();
    applyFormTo(d);
    return d;
  }

  /**
   * 将表单构建的实例字段拷贝到既有设备对象。
   *
   * <p>保留目标实例引用不变——列表选中状态依赖引用相等。SRV 模式下端口字段不参与表单（端口框为解析回显位），保留目标设备既有端口， 切回普通模式后仍可用。
   *
   * @param target 待更新的设备实例（即 {@link #currentDevice}）
   * @param form 表单构建的新实例（仅作为字段源）
   */
  private void copyFormTo(Device target, Device form) {
    target.setName(form.getName());
    target.setMacAddress(form.getMacAddress());
    target.setBroadcastAddress(form.getBroadcastAddress());
    target.setSrvEnabled(form.isSrvEnabled());
    target.setSrvName(form.getSrvName());
    if (!form.isSrvEnabled()) {
      target.setPort(form.getPort());
    }
  }

  /**
   * 将表单值写入目标设备，并对字段做合法性校验。
   *
   * <p>SRV 模式（{@link #srvModeBox} 选中）：广播字段语义变为 SRV 记录名，仅校验非空；端口由解析结果提供，跳过端口校验。
   *
   * @param target 写入目标（onSave 为 currentDevice；onSend 为新实例）
   * @throws IllegalArgumentException 端口越界、MAC 非法或 SRV 地址为空时；消息含具体原因
   */
  private void applyFormTo(Device target) {
    target.setName(nameField.getText());
    target.setMacAddress(macField.getText());
    target.setBroadcastAddress(broadcastField.getText());
    target.setSrvEnabled(srvModeBox.isSelected());
    target.setSrvName(broadcastField.getText());

    try {
      WolUtil.parseMac(target.getMacAddress());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("MAC 地址不合法：" + e.getMessage());
    }

    // SRV 模式：广播字段为 SRV 记录名，端口由解析提供，两者均不在此校验
    if (target.isSrvEnabled()) {
      if (target.getSrvName().isBlank()) {
        throw new IllegalArgumentException("SRV 地址不能为空");
      }
      return;
    }

    int port = parsePort(portField.getText());
    if (port < 0) {
      throw new IllegalArgumentException("目标端口必须为 1-65535 之间的数字");
    }
    target.setPort(port);
  }

  /** 切换表单的 SRV 语义：广播行变为 SRV 地址（提示文本同步），端口行变为「解析目标」且输入框禁用。 */
  private void applySrvModeUi(boolean srvMode) {
    broadcastLabel.setText(srvMode ? "SRV 地址" : "广播地址");
    broadcastField.setPromptText(srvMode ? "如 _wol._udp.example.com（自动解析端口）" : "IP 或主机名");
    portLabel.setText(srvMode ? "解析目标" : "目标端口");
    portField.setDisable(srvMode);
    portField.setPromptText(srvMode ? "发送后显示解析结果" : "默认 9");
  }

  /**
   * 持久化全部设备与软件设置（设备、主题、连发次数）。
   *
   * <p>数据契约：设备与设置分文件写入 {@code device.properties} / {@code settings.properties}；连发次数解析失败时回退 {@link
   * AppConfig#DEFAULT_SEND_COUNT}。
   *
   * @throws IOException 任一文件写盘失败时（原子写入，失败不破坏既有文件）
   */
  private void saveAll() throws IOException {
    DeviceConfig config = new DeviceConfig();
    config.setDevices(devices);
    ConfigService.save(config);

    AppSettings settings = new AppSettings();
    settings.setTheme(currentTheme);
    int count = parseCount(countField.getText());
    settings.setSendCount(count < 0 ? AppConfig.DEFAULT_SEND_COUNT : count);
    ConfigService.saveSettings(settings);
  }

  private void bindDirty(javafx.beans.value.ObservableValue<?> property) {
    property.addListener(
        (obs, oldVal, newVal) -> {
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

    alert
        .getDialogPane()
        .getStylesheets()
        .add(getClass().getResource("/ad/ovo/wol/css/dialog.css").toExternalForm());
    return alert.showAndWait().filter(ok::equals).isPresent();
  }

  /** 更新状态横幅：切换样式类（info/success/error）并设置文本。 */
  private void setStatus(String message, StatusType type) {
    statusBanner.getStyleClass().removeAll("info", "success", "error");
    statusBanner.getStyleClass().add(type.name().toLowerCase());
    statusBanner.setText(message);
  }

  /** 恢复发送期间禁用的全部操作按钮。 */
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

  /**
   * 解析闭区间整数：空白、非数字或越界一律返回 -1（-1 为「非法」哨兵值，由调用方决定提示文案）。
   *
   * @param raw 原始文本，可为 null
   * @param min 合法下界（含）
   * @param max 合法上界（含）
   * @return 合法时返回解析值，否则 -1
   */
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

  /**
   * 构造 TextFormatter 白名单过滤器：新文本仅含允许字符时接受，否则拒绝变更。
   *
   * @param allowedRegex 允许字符集的正则（置于非捕获组内，如 "[0-9A-Fa-f:\\-]"）
   * @return 过滤器函数；返回 null 表示拒绝本次输入变更
   */
  private static UnaryOperator<TextFormatter.Change> charFilter(String allowedRegex) {
    return change ->
        change.getControlNewText().matches("(?:" + allowedRegex + ")*") ? change : null;
  }
}
