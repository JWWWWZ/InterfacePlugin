package com.example.testplugin;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;

public class HttpRequestDialog extends DialogWrapper {
    private final HttpRequestPanel requestPanel;

    public HttpRequestDialog(@Nullable Project project) {
        super(project);
        requestPanel = new HttpRequestPanel(project);
        setTitle("HTTP Request");
        setResizable(true);
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        return requestPanel.getComponent();
    }
}
