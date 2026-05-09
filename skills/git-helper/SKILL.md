---
name: git-helper
description: 查看 git 仓库的状态、历史、分支、diff，按约定风格生成 commit message
---

# git-helper

当用户询问 git 仓库的状态、历史、想写 commit message 时使用本 skill。

## 步骤

1. **先定位仓库根目录**：如果用户没指定路径，用
   `shell_execute("git rev-parse --show-toplevel")` 取当前目录所属仓库。
2. **状态概览**（用户问"有什么变更"之类）：
   - `git status -sb` 看分支和短状态
   - `git diff --stat` 看文件级改动规模
3. **查看历史**（用户问"最近提交"之类）：
   - `git log --oneline -n 20` 最近 20 条
   - `git log --oneline --graph --all -n 30` 带分支图
4. **写 commit message**：
   - 先 `git diff --cached` 看暂存区变更，没暂存就 `git diff`
   - 按下面的风格给出消息，**不要自动 commit**，给用户确认
5. **对比分支**：`git log --oneline A..B` / `git diff A...B`

## Commit message 风格

使用 Conventional Commits：

```
<type>(<scope>): <subject>

<body 可选，说明 why，不是 what>
```

常用 type：
- `feat`：新功能
- `fix`：bug 修复
- `refactor`：重构，不改外部行为
- `docs`：文档
- `test`：测试
- `chore`：构建/脚手架/工具

subject 一句话，小写开头，不加句号，不超过 72 字。
