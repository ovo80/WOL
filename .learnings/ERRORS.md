# Errors

Command failures and integration errors.

---

## [ERR-20260804-001] mvn_wrapper_script_broken

**Logged**: 2026-08-04T14:10:00+08:00
**Priority**: high
**Status**: resolved
**Area**: infra

### Summary
本机 `mvn` 脚本报 "找不到或无法加载主类 org.codehaus.plexus.classworlds.launcher.Launcher"，但 Maven 本身可用。

### Error
```
错误: 找不到或无法加载主类 org.codehaus.plexus.classworlds.launcher.Launcher
原因: java.lang.ClassNotFoundException: org.codehaus.plexus.classworlds.launcher.Launcher
```

### Context
- 环境：Windows 11 + Git Bash，Maven 3.9.16 位于 E:\env\Maven\apache-maven-3.9.16
- MAVEN_HOME 已设置但 M2_HOME 为空，mvn shell 脚本解析 classpath 失败
- boot/plexus-classworlds-2.11.0.jar 实际存在

### Suggested Fix
绕过 mvn 脚本，直接用 java 启动：
```bash
export M2_HOME="E:\\env\\Maven\\apache-maven-3.9.16"
java -classpath "$M2_HOME/boot/plexus-classworlds-2.11.0.jar" \
  "-Dclassworlds.conf=$M2_HOME/bin/m2.conf" \
  "-Dmaven.home=$M2_HOME" \
  "-Dmaven.multiModuleProjectDirectory=<projectDir>" \
  org.codehaus.plexus.classworlds.launcher.Launcher <goals>
```
已验证可正常执行 Maven 构建。

### Metadata
- Reproducible: yes
- Related Files: E:\env\Maven\apache-maven-3.9.16\bin\mvn
- Tags: maven, windows, git-bash

---
