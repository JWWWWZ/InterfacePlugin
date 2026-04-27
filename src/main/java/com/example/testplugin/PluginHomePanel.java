package com.example.testplugin;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.List;

public class PluginHomePanel {
    private final @Nullable Project project;
    private final JPanel rootPanel = new JPanel(new BorderLayout(0, 8));
    private final JButton openHttpRequestButton = new JButton("Open HTTP Request");
    private final JButton loadCommitsButton = new JButton("Load Git Commits");
    private final DefaultListModel<CommitEntry> listModel = new DefaultListModel<>();
    private final JList<CommitEntry> commitList = new JList<>(listModel);
    private final JBTextArea resultArea = new JBTextArea();

    public PluginHomePanel(@Nullable Project project) {
        this.project = project;
        rootPanel.add(createButtonPanel(), BorderLayout.NORTH);
        rootPanel.add(createMainPanel(), BorderLayout.CENTER);

        openHttpRequestButton.addActionListener(e -> openHttpRequestDialog());
        loadCommitsButton.addActionListener(e -> loadCommits());

        commitList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                CommitEntry selected = commitList.getSelectedValue();
                if (selected != null) {
                    analyzeSelectedCommit(selected);
                }
            }
        });
    }

    public JComponent getComponent() {
        return rootPanel;
    }

    // ── 顶部按钮行 ────────────────────────────────────────────────────────────

    private JPanel createButtonPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        panel.add(openHttpRequestButton);
        panel.add(loadCommitsButton);
        return panel;
    }

    // ── 主体：Commit 列表 + 分析结果，上下分隔 ───────────────────────────────

    private JComponent createMainPanel() {
        commitList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        commitList.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JBScrollPane listScroll = new JBScrollPane(commitList);
        listScroll.setBorder(BorderFactory.createTitledBorder("Git Commits（选中一条查看变更详情）"));

        resultArea.setEditable(false);
        resultArea.setLineWrap(true);
        resultArea.setWrapStyleWord(true);
        resultArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        resultArea.setText("点击 \"Load Git Commits\" 加载提交记录，然后选中一条查看变更类和方法。");
        JBScrollPane resultScroll = new JBScrollPane(resultArea);
        resultScroll.setBorder(BorderFactory.createTitledBorder("变更类 & 方法（+ 新增  - 删除  = 保留）"));

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, listScroll, resultScroll);
        splitPane.setDividerLocation(220);
        splitPane.setResizeWeight(0.4);
        return splitPane;
    }

    // ── 操作：打开 HTTP 请求弹窗 ──────────────────────────────────────────────

    private void openHttpRequestDialog() {
        HttpRequestDialog dialog = new HttpRequestDialog(project);
        dialog.show();
    }

    // ── 操作：加载 Git Commit 列表 ────────────────────────────────────────────

    private void loadCommits() {
        if (project == null) {
            resultArea.setText("未找到活跃项目，请在项目中打开此工具窗口。");
            return;
        }

        loadCommitsButton.setEnabled(false);
        listModel.clear();
        resultArea.setText("正在加载 Git 提交记录...");

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Loading Git Commits", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                List<CommitEntry> commits = GitChangeMethodCollector.loadRecentCommits(project, 50);
                ApplicationManager.getApplication().invokeLater(() -> {
                    listModel.clear();
                    if (commits.isEmpty()) {
                        resultArea.setText("未找到 Git 提交记录，请确认当前项目位于 Git 仓库中。");
                    } else {
                        for (CommitEntry entry : commits) {
                            listModel.addElement(entry);
                        }
                        resultArea.setText("已加载 " + commits.size() + " 条提交记录，点击任意一条查看变更详情。");
                    }
                    loadCommitsButton.setEnabled(true);
                });
            }
        });
    }

    // ── 操作：分析选中的 Commit ────────────────────────────────────────────────

    private void analyzeSelectedCommit(@NotNull CommitEntry entry) {
        if (project == null) {
            return;
        }

        resultArea.setText("正在分析 Commit " + entry.commit().getId().toShortString() + "...");

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Analyzing Commit", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                String result = GitChangeMethodCollector.analyzeCommit(project, entry.commit());
                ApplicationManager.getApplication().invokeLater(() -> {
                    resultArea.setText(result);
                    resultArea.setCaretPosition(0);
                });
            }
        });
    }
}

