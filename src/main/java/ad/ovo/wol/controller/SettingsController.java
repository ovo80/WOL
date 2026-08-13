/*
 * WOL 唤醒工具 - 设置窗口控制器。
 *
 * Copyright (c) 2026 ovo80
 * MIT License. See the LICENSE file in the project root for details.
 */
package ad.ovo.wol.controller;

import ad.ovo.modloader.Mod;
import ad.ovo.modloader.PluginManager;
import ad.ovo.wol.common.config.AppConfig;
import ad.ovo.wol.model.AppSettings;
import ad.ovo.wol.plugin.Language;
import ad.ovo.wol.plugin.LanguageManager;
import ad.ovo.wol.plugin.Theme;
import ad.ovo.wol.plugin.ThemeManager;
import ad.ovo.wol.service.ConfigService;
import java.io.IOException;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 设置窗口控制器：主题、语言、模组的展示与切换。
 *
 * <p>依赖注入：{@link #inject} 由 {@link MainController} 在打开窗口时调用，传入主控制器与三个管理器；变更经 {@link
 * ConfigService#saveSettings} 即时持久化。
 *
 * <p>可变状态（均在 FX 线程）：主题/语言选择与各插件开关，全部直接反映到 {@code settings.properties}。
 */
public class SettingsController {

  private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

  private MainController mainController;
  private ThemeManager themeManager;
  private LanguageManager languageManager;
  private PluginManager pluginManager;

  @FXML private ListView<Theme> themeList;
  @FXML private ListView<Language> languageList;
  @FXML private VBox modsContainer;
  @FXML private Label modsEmptyHint;
  @FXML private Label languageHint;

  /**
   * 注入依赖并初始化列表（由 {@link MainController} 调用一次）。
   *
   * @param mainController 主控制器，用于应用主题到主窗口
   * @param themeManager 主题管理器（已 scan）
   * @param languageManager 语言管理器（已 scan）
   * @param pluginManager 插件管理器（已 scan）
   */
  public void inject(
      MainController mainController,
      ThemeManager themeManager,
      LanguageManager languageManager,
      PluginManager pluginManager) {
    this.mainController = mainController;
    this.themeManager = themeManager;
    this.languageManager = languageManager;
    this.pluginManager = pluginManager;
    buildThemeList();
    buildLanguageList();
    buildModList();
  }

  private void buildThemeList() {
    themeList.setItems(FXCollections.observableArrayList(themeManager.getThemes()));
    themeList.setCellFactory(
        list ->
            new ListCell<>() {
              @Override
              protected void updateItem(Theme item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getName());
              }
            });

    // 选中当前主题；选中变更即时应用并持久化
    AppSettings settings = ConfigService.loadSettings();
    String current = settings.getTheme();
    Theme resolved = themeManager.resolve(current);
    themeList.getSelectionModel().select(resolved);

    themeList
        .getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (obs, oldVal, newVal) -> {
              if (newVal == null) {
                return;
              }
              mainController.applyTheme(newVal.getId());
              persistSettings(s -> s.setTheme(newVal.getId()));
            });
  }

  private void buildLanguageList() {
    languageList.setItems(FXCollections.observableArrayList(languageManager.getLanguages()));
    languageList.setCellFactory(
        list ->
            new ListCell<>() {
              @Override
              protected void updateItem(Language item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getName());
              }
            });

    AppSettings settings = ConfigService.loadSettings();
    languageList.getSelectionModel().select(languageManager.resolve(settings.getLanguage()));

    languageList
        .getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (obs, oldVal, newVal) -> {
              if (newVal == null) {
                return;
              }
              persistSettings(s -> s.setLanguage(newVal.getCode()));
              // 界面文案的多语言翻译尚未接入（见 README），即时提示避免用户误以为切换失效
              if (languageHint != null) {
                languageHint.setText(
                    "语言已保存：" + newVal.getName() + "（界面翻译将在后续版本提供）");
              }
            });
  }

  private void buildModList() {
    var mods = pluginManager.getMods();
    modsContainer.getChildren().clear();
    if (mods.isEmpty()) {
      modsEmptyHint.setVisible(true);
      modsEmptyHint.setManaged(true);
      return;
    }
    modsEmptyHint.setVisible(false);
    modsEmptyHint.setManaged(false);

    for (Mod mod : mods) {
      CheckBox box = new CheckBox(mod.name() + "  v" + mod.version());
      box.getStyleClass().add("mod-check");
      // 先设初始态再挂监听，避免初始化触发持久化
      box.setSelected(pluginManager.isEnabled(mod.id()));
      box.selectedProperty()
          .addListener(
              (obs, oldVal, newVal) -> {
                pluginManager.setEnabled(mod.id(), newVal);
                persistSettings(s -> s.setModEnabled(mod.id(), newVal));
              });
      modsContainer.getChildren().add(box);
    }
  }

  /** 读取现有设置、改写指定字段并落盘；失败仅告警，不影响本次内存态生效。 */
  private void persistSettings(java.util.function.Consumer<AppSettings> mutator) {
    AppSettings settings = ConfigService.loadSettings();
    mutator.accept(settings);
    try {
      ConfigService.saveSettings(settings);
    } catch (IOException e) {
      log.warn("设置保存失败: {}", e.getMessage());
    }
  }
}
