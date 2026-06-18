package com.example.testplugin;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;

public class PluginHomePanel {
    private final @Nullable Project project;
    private final JPanel rootPanel = new JPanel(new BorderLayout(0, 8));
    private final JButton openHttpRequestButton = new JButton("Open HTTP Request");
    private final JButton loadCommitsButton     = new JButton("Load Git Commits");
    private final JButton scanControllerButton  = new JButton("Scan Controller");
    private final JButton genDocButton          = new JButton("生成 AI 接口文档");
    private final JButton syncToYapiButton      = new JButton("同步到 YApi");

    // ── AI config ────────────────────────────────────────────────────────────
    private final JTextField    apiUrlField   = new JTextField("https://api.deepseek.com", 28);
    private final JPasswordField apiKeyField  = new JPasswordField(24);
    private final JTextField    modelField    = new JTextField("deepseek-v4-flash", 10);

    // ── YApi config ──────────────────────────────────────────────────────────
    private final JTextField    yapiUrlField   = new JTextField("http://localhost:3000", 28);
    private final JPasswordField yapiTokenField = new JPasswordField("20d7f0566a18421f4a98a7fa01fdb7ea94f48fde6d22f1e1727722223dbc882f", 24);
    private final JTextField    yapiProjectIdField = new JTextField("11", 8);
    private final JTextField    yapiCatIdField     = new JTextField("11", 8);

    private final DefaultListModel<CommitEntry> listModel = new DefaultListModel<>();
    private final JList<CommitEntry> commitList = new JList<>(listModel);
    private final JBTextArea resultArea = new JBTextArea();
    private final JBTextArea docArea    = new JBTextArea();

    /** The most recently扫描到的 JSON */
    private volatile String lastScannedJson = null;
    private volatile String lastAiDoc      = null;

    public PluginHomePanel(@Nullable Project project) {
        this.project = project;

        JPanel northSection = new JPanel(new BorderLayout(0, 4));
        northSection.add(createButtonPanel(), BorderLayout.NORTH);
        northSection.add(createAiConfigPanel(), BorderLayout.CENTER);
        northSection.add(createYapiConfigPanel(), BorderLayout.SOUTH);
        rootPanel.add(northSection, BorderLayout.NORTH);
        rootPanel.add(createMainPanel(), BorderLayout.CENTER);

        openHttpRequestButton.addActionListener(e -> openHttpRequestDialog());
        loadCommitsButton.addActionListener(e -> loadCommits());
        scanControllerButton.addActionListener(e -> scanController());
        genDocButton.addActionListener(e -> generateAiDoc(lastScannedJson));
        syncToYapiButton.addActionListener(e -> syncToYapi(lastScannedJson, lastAiDoc));

        commitList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                CommitEntry selected = commitList.getSelectedValue();
                if (selected != null) {
                    analyzeSelectedCommit(selected);
                }
            }
        });

        // Lock fixed configuration values so they cannot be changed from the UI.
        apiUrlField.setEditable(false);
        modelField.setEditable(false);
        yapiUrlField.setEditable(false);
        yapiTokenField.setEditable(false);
        yapiProjectIdField.setEditable(false);
        yapiCatIdField.setEditable(false);
    }

    public JComponent getComponent() {
        return rootPanel;
    }

    // ── 顶部按钮行 ────────────────────────────────────────────────────────────

    private JPanel createButtonPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        panel.add(openHttpRequestButton);
        panel.add(loadCommitsButton);
        panel.add(scanControllerButton);
        panel.add(genDocButton);
        return panel;
    }

    // ── AI 配置行 ─────────────────────────────────────────────────────────────

    private JPanel createAiConfigPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("AI 配置"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0; c.gridy = 0; c.weightx = 0;
        panel.add(new JLabel("API URL:"), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(apiUrlField, c);

        c.gridx = 2; c.weightx = 0;
        panel.add(new JLabel("API Key:"), c);
        c.gridx = 3; c.weightx = 0.6;
        panel.add(apiKeyField, c);

        c.gridx = 4; c.weightx = 0;
        panel.add(new JLabel("Model:"), c);
        c.gridx = 5; c.weightx = 0.3;
        panel.add(modelField, c);

        return panel;
    }

    private JPanel createYapiConfigPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("YApi 配置"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0; c.gridy = 0; c.weightx = 0;
        panel.add(new JLabel("YApi URL:"), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(yapiUrlField, c);

        c.gridx = 2; c.weightx = 0;
        panel.add(new JLabel("Token:"), c);
        c.gridx = 3; c.weightx = 0.6;
        panel.add(yapiTokenField, c);

        c.gridx = 4; c.weightx = 0;
        panel.add(new JLabel("Project ID:"), c);
        c.gridx = 5; c.weightx = 0.3;
        panel.add(yapiProjectIdField, c);

        c.gridx = 0; c.gridy = 1; c.weightx = 0;
        panel.add(new JLabel("Cat ID:"), c);
        c.gridx = 1; c.weightx = 0.3;
        panel.add(yapiCatIdField, c);

        c.gridx = 2; c.weightx = 0;
        panel.add(syncToYapiButton, c);

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
        resultScroll.setBorder(BorderFactory.createTitledBorder("接口元数据 JSON"));

        docArea.setEditable(false);
        docArea.setLineWrap(true);
        docArea.setWrapStyleWord(true);
        docArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        docArea.setText("点击 \"生成 AI 接口文档\" 后，AI 生成的 Markdown 文档将显示在此处。");
        JBScrollPane docScroll = new JBScrollPane(docArea);
        docScroll.setBorder(BorderFactory.createTitledBorder("AI 生成的接口文档（Markdown）"));

        // Three-way vertical split: commit list | JSON | AI doc
        JSplitPane rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, resultScroll, docScroll);
        rightSplit.setDividerLocation(240);
        rightSplit.setResizeWeight(0.4);

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, listScroll, rightSplit);
        splitPane.setDividerLocation(180);
        splitPane.setResizeWeight(0.2);
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

    // ── 操作：扫描当前编辑器中打开的 Spring 控制器 ─────────────────────────────

    private void scanController() {
        if (project == null) {
            resultArea.setText("未找到活跃项目。");
            return;
        }

        // Resolve the currently open file in the editor
        VirtualFile[] openFiles = FileEditorManager.getInstance(project).getSelectedFiles();
        if (openFiles.length == 0) {
            resultArea.setText("请先在编辑器中打开一个 Spring Controller 文件。");
            return;
        }

        PsiFile psiFile = com.intellij.openapi.application.ReadAction.compute(
                () -> PsiManager.getInstance(project).findFile(openFiles[0]));

        if (!(psiFile instanceof PsiJavaFile javaFile)) {
            resultArea.setText("当前文件不是 Java 文件，请打开一个 Spring Controller 类。");
            return;
        }

        // Take the first top-level class in the file
        PsiClass[] classes = com.intellij.openapi.application.ReadAction.compute(
                javaFile::getClasses);

        if (classes.length == 0) {
            resultArea.setText("当前 Java 文件中未找到任何类定义。");
            return;
        }

        scanControllerButton.setEnabled(false);
        resultArea.setText("正在扫描控制器接口……");

        // SpringControllerScanner runs the PSI traversal in a background task
        // and calls back on the EDT with the JSON result.
        SpringControllerScanner.scan(project, classes[0], json -> {
            lastScannedJson = json;
            resultArea.setText(json);
            resultArea.setCaretPosition(0);
            scanControllerButton.setEnabled(true);
            docArea.setText("扫描完成。点击 \"生成 AI 接口文档\" 将接口元数据发送给大模型生成文档。");
        });
    }

    // ── 操作：将扫描结果发给 AI 生成接口文档 ─────────────────────────────────────

    private void generateAiDoc(@Nullable String json) {
        if (project == null) return;
        if (json == null || json.isBlank()) {
            docArea.setText("请先点击 \"Scan Controller\" 扫描一个 Controller 文件。");
            return;
        }

        String apiUrl = apiUrlField.getText().trim();
        String apiKey = new String(apiKeyField.getPassword()).trim();
        String model  = modelField.getText().trim();

        if (apiUrl.isBlank()) {
            docArea.setText("请在 AI 配置栏填写 API URL。");
            return;
        }
        if (model.isBlank()) {
            model = AiDocClient.DEFAULT_MODEL;
        }

        genDocButton.setEnabled(false);
        docArea.setText("正在调用 AI 生成接口文档，请稍候……");

        final String finalModel = model;
        final String finalJson  = json;

        ProgressManager.getInstance().run(
                new Task.Backgroundable(project, "Generating API Doc with AI", false) {
                    @Override
                    public void run(@NotNull ProgressIndicator indicator) {
                        indicator.setIndeterminate(true);
                        indicator.setText("Calling AI API…");
                        try {
                            String doc = AiDocClient.generateDoc(
                                    finalJson, apiUrl, apiKey, finalModel,
                                    // Progress messages go to the indicator text (IDE status bar)
                                    // AND are appended to the doc area so the user sees them live.
                                    step -> {
                                        indicator.setText(step);
                                        ApplicationManager.getApplication().invokeLater(
                                                () -> docArea.setText(step));
                                    }
                            );
                            ApplicationManager.getApplication().invokeLater(() -> {
                                docArea.setText(doc);
                                docArea.setCaretPosition(0);
                                lastAiDoc = doc;
                                genDocButton.setEnabled(true);
                            });
                        } catch (Exception ex) {
                            ApplicationManager.getApplication().invokeLater(() -> {
                                docArea.setText("调用 AI 接口失败：\n" + ex.getMessage());
                                genDocButton.setEnabled(true);
                            });
                        }
                    }
                }
        );
    }
    // ── 操作：同步当前扫描结果到 YApi ─────────────────────────────────────────────

    private void syncToYapi(@Nullable String scannedJson, @Nullable String aiDoc) {
        if (project == null) return;
        if (scannedJson == null || scannedJson.isBlank()) {
            docArea.setText("请先点击 \"Scan Controller\" 扫描一个 Controller 文件，再进行 YApi 同步。");
            return;
        }

        String yapiUrl = yapiUrlField.getText().trim();
        String token = new String(yapiTokenField.getPassword()).trim();
        String projectIdText = yapiProjectIdField.getText().trim();
        String catIdText = yapiCatIdField.getText().trim();

        if (yapiUrl.isBlank()) {
            docArea.setText("请填写 YApi URL。例： http://localhost:3000");
            return;
        }
        if (projectIdText.isBlank() || catIdText.isBlank()) {
            docArea.setText("请填写 YApi Project ID 和 Cat ID。" );
            return;
        }

        int projectId;
        int catId;
        try {
            projectId = Integer.parseInt(projectIdText);
            catId = Integer.parseInt(catIdText);
        } catch (NumberFormatException ex) {
            docArea.setText("Project ID 和 Cat ID 必须是整数。" );
            return;
        }

        syncToYapiButton.setEnabled(false);
        docArea.setText("正在同步到 YApi，请稍候……");

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Syncing to YApi", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                try {
                    String result = YapiSyncClient.sync(scannedJson, aiDoc, yapiUrl, token, projectId, catId,
                            step -> ApplicationManager.getApplication().invokeLater(() -> {
                                indicator.setText(step);
                                docArea.setText(step);
                            }));
                    ApplicationManager.getApplication().invokeLater(() -> {
                        docArea.setText(result);
                        docArea.setCaretPosition(0);
                        syncToYapiButton.setEnabled(true);
                    });
                } catch (Exception ex) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        docArea.setText("YApi 同步失败：\n" + ex.getMessage());
                        syncToYapiButton.setEnabled(true);
                    });
                }
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

