package com.example.testplugin;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public class HttpRequestToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        HttpRequestPanel requestPanel = new HttpRequestPanel(project);
        Content content = ContentFactory.getInstance().createContent(requestPanel.getComponent(), "", false);
        toolWindow.getContentManager().addContent(content);
    }
}
