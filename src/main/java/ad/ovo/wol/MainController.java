package ad.ovo.wol;

import ad.ovo.wol.config.AppConfig;
import ad.ovo.wol.exception.WolException;
import ad.ovo.wol.model.AppSettings;
import ad.ovo.wol.model.Device;
import ad.ovo.wol.model.DeviceConfig;
import ad.ovo.wol.service.ConfigService;
import ad.ovo.wol.service.WolService;
import ad.ovo.wol.util.WolUtil;
import java.io.IOException;
import java.util.function.UnaryOperator;
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

/**
 * 主界面控制器：设备列表、编辑表单与发送流程的交互编排。
 *
 * <p>分层约定：网络发送委托 {@link WolService}，持久化委托 {@link ConfigService}， 本类只翻译界面状态与用户意图，不直接触碰 UDP 与配置文件。
 *
 * <p>可变状态（均在 FX 线程内读写）：{@link #devices} 设备列表（持久化的唯一 事实来源）、{@link #currentDevice} 当前编辑设备、{@link
 * #dirty} 未保存标记、 {@link #currentTheme} 当前主题。
 *
 * <p>线程模型：唯一后台线程为发送线程 {@code wol-send-thread}（见 {@link #onSend()}），其结果经 Task 回调回到 FX 线程；其余方法一律在 FX
 * 线程执行。
 *
 * <p>副作用：新建/删除/保存设备与切换主题都会立即写盘（I/O）。
 */
public class MainController {

  private static final Logger log = LoggerFactory.getLogger(MainController.class);

  /** 状态横幅样式类别，对应 CSS 类 info/success/error */
  private enum StatusType {
    INFO,
    SUCCESS,
    ERROR
  }

  private final WolService wolService = new WolService();

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
  @FXML private Button newDeviceButton;
  @FXML private Button deleteDeviceButton;
  @FXML private Button sendButton;
  @FXML private Button saveButton;
  @FXML private Button themeButton;
  @FXML private Label statusBanner;

  /**
   * FXML 加载完成后由 JavaFX 框架回调：装载配置、装配输入过滤与监听器。
   *
   * <p>数据契约：{@link ConfigService#load()} 保证列表非空（至少一台默认设备）； {@link ConfigService#loadSettings()}
   * 保证主题/次数为合法值（非法回退默认）。
   *
   * <p>执行顺序依赖：本阶段 {@code statusBanner.getScene()} 为 null， {@link #applyTheme(String)}
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
   * <p>约束：表单无需先保存即可发送；发送期间禁用全部操作按钮防止重复提交， 结果（成功/失败/取消）回显状态后统一恢复按钮。
   *
   * <p>副作用：发起 UDP 网络发送（后台线程，阻塞时长约为连发次数 × 100ms）， 不修改配置与设备对象。
   */
  @FXML
  private void onSend() {
    // 次数非法（-1 哨兵）时直接提示，不进入发送流程
    final int count = parseCount(countField.getText());
    if (count < 0) {
      setStatus("发送失败：连发次数必须为 1-" + AppConfig.SEND_COUNT_MAX + " 之间的数字", StatusType.ERROR);
      return;
    }
    final Device toSend;
    try {
      // 表单校验（端口/MAC）失败抛 IllegalArgumentException，消息直接展示
      toSend = deviceFromForm();
    } catch (IllegalArgumentException e) {
      setStatus(e.getMessage(), StatusType.ERROR);
      return;
    }

    sendButton.setDisable(true);
    saveButton.setDisable(true);
    newDeviceButton.setDisable(true);
    deleteDeviceButton.setDisable(true);
    setStatus(
        "正在连发 "
            + count
            + " 个魔术包到 "
            + toSend.getBroadcastAddress()
            + ":"
            + toSend.getPort()
            + " ...",
        StatusType.INFO);

    // 网络 I/O 移出 FX 线程；UI 更新仅经 Task 回调（内部 Platform.runLater）完成
    Task<Void> task =
        new Task<>() {
          @Override
          protected Void call() throws WolException {
            wolService.sendWakeUp(toSend, count);
            updateMessage("魔术包已发送（连发 " + count + " 次）");
            return null;
          }
        };

    task.setOnSucceeded(
        e -> {
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
   * <p>副作用：写盘两个配置文件（I/O，原子写入）；写盘失败时内存中的设备值 已被表单覆盖（不回滚），界面提示失败原因。
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
   * <p>新建即持久化（重启后保留）；落盘失败时从列表移除该设备并提示。
   *
   * <p>副作用：写盘（I/O）。
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

  /**
   * 删除落盘失败时的内存回滚：移除占位设备，将被删设备恢复到原位置。
   *
   * @param removed 被删设备实例
   * @param index 原列表下标（用于恢复位置）
   * @param placeholder 删除后创建的占位设备；未创建时为 null
   */
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
   * <p>副作用：写盘 {@code settings.properties}（I/O）；写盘失败仅提示， 不影响本次主题在内存中生效。
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
   * <p>调用顺序约定：{@link #syncThemeButton(String)} 必须位于 scene 判空 之前——initialize 阶段 scene 为
   * null，按钮图案需无条件同步，而样式表 操作要求场景已就绪（FxmlLoadTest 有回归断言）。
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

  /**
   * 同步主题按钮图案：深色主题显示 ☀，浅色主题显示 ☾。
   *
   * @param theme 主题标识：dark / light
   */
  private void syncThemeButton(String theme) {
    themeButton.setText(AppConfig.THEME_DARK.equals(theme) ? "\u2600" : "\u263E");
  }

  /**
   * 将设备字段回填表单并清除脏标记。
   *
   * <p>回填期间置 {@link #suppressChangeEvents}，避免 setText 触发 dirty； 之后 {@link #currentDevice} 指向该实例。
   *
   * @param device 目标设备
   */
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

  /**
   * 从表单当前值构建新设备实例（不修改任何既有对象）。
   *
   * @return 新设备实例，字段来自表单
   * @throws IllegalArgumentException 端口不在 1-65535 或 MAC 格式非法时 （消息可直接展示，见 {@link
   *     #applyFormTo(Device)}）
   */
  private Device deviceFromForm() {
    Device d = new Device();
    applyFormTo(d);
    return d;
  }

  /**
   * 将表单构建的实例字段拷贝到既有设备对象。
   *
   * <p>保留目标实例引用不变——列表选中状态依赖引用相等。
   *
   * @param target 待更新的设备实例（即 {@link #currentDevice}）
   * @param form 表单构建的新实例（仅作为字段源）
   */
  private void copyFormTo(Device target, Device form) {
    target.setName(form.getName());
    target.setMacAddress(form.getMacAddress());
    target.setBroadcastAddress(form.getBroadcastAddress());
    target.setPort(form.getPort());
  }

  /**
   * 将表单值写入目标设备，并对端口与 MAC 做合法性校验。
   *
   * @param target 写入目标（onSave 为 currentDevice；onSend 为新实例）
   * @throws IllegalArgumentException 端口越界或 MAC 非法时；消息含具体原因
   */
  private void applyFormTo(Device target) {
    target.setName(nameField.getText());
    target.setMacAddress(macField.getText());
    target.setBroadcastAddress(broadcastField.getText());

    int port = parsePort(portField.getText());
    if (port < 0) {
      throw new IllegalArgumentException("目标端口必须为 1-65535 之间的数字");
    }
    target.setPort(port);

    try {
      WolUtil.parseMac(target.getMacAddress());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("MAC 地址不合法：" + e.getMessage());
    }
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

  /**
   * 订阅字段变更：非回填阶段将 {@link #dirty} 置 true。
   *
   * @param property 表单字段的文本属性
   */
  private void bindDirty(javafx.beans.value.ObservableValue<?> property) {
    property.addListener(
        (obs, oldVal, newVal) -> {
          if (!suppressChangeEvents) {
            dirty = true;
          }
        });
  }

  /**
   * 弹窗确认放弃未保存修改。
   *
   * @return true 表示用户选择继续（允许放弃修改）
   */
  private boolean confirmDiscardChanges() {
    return showConfirm("未保存的修改", "当前设备的修改尚未保存，切换后将丢失。是否继续？", "继续");
  }

  /**
   * 通用确认弹窗。
   *
   * @param title 窗口标题
   * @param message 正文
   * @param okText 确认按钮文案
   * @return true 表示点击了确认按钮
   */
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

  /**
   * 更新状态横幅：切换样式类（info/success/error）并设置文本。
   *
   * @param message 展示文案（用户可见，不携带堆栈）
   * @param type 样式类别
   */
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

  /** 解析端口文本，非法/越界返回 -1（哨兵约定见 {@link #parseInRange(String, int, int)}）。 */
  private int parsePort(String raw) {
    return parseInRange(raw, AppConfig.PORT_MIN, AppConfig.PORT_MAX);
  }

  /** 解析连发次数文本，非法/越界返回 -1。 */
  private int parseCount(String raw) {
    return parseInRange(raw, AppConfig.SEND_COUNT_MIN, AppConfig.SEND_COUNT_MAX);
  }

  /**
   * 解析闭区间整数：空白、非数字或越界一律返回 -1。
   *
   * <p>约定：-1 为「非法」哨兵值，由调用方决定提示文案；合法区间参数 均来自 {@link AppConfig}。
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
