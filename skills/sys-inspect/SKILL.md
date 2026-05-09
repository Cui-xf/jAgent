---
name: sys-inspect
description: 收集本机基本信息——OS、CPU、内存、磁盘、关键进程
---

# sys-inspect

给用户一份当前机器的健康体检报告。

## 执行方式

运行本 skill 目录里的 `scripts/collect.sh`：

```
shell_execute("bash ./skills/sys-inspect/scripts/collect.sh")
```

## 报告格式

拿到脚本输出后，按这个结构总结给用户：

1. **Host**：主机名 / OS 版本
2. **CPU & Load**：核数 / 1-5-15min 负载
3. **Memory**：总量 / 已用 / 可用
4. **Disk**：主要挂载点的使用率
5. **Top processes**：CPU 和内存 Top 3

如果某项使用率 > 85% 要在摘要里标红（用 `⚠️` 表示）。
