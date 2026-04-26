package com.example.testplugin;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

public class HttpRequestPanel {
    private final @Nullable Project project;
    private final JPanel rootPanel = new JPanel(new BorderLayout(0, 12));
    private final JTextField urlField = new JTextField();
    private final JTextField cookieField = new JTextField();
    private final JComboBox<String> methodComboBox = new JComboBox<>(new String[]{"GET", "POST"});
    private final JButton sendButton = new JButton("Send");
    private final JBTextArea responseArea = new JBTextArea();

    public HttpRequestPanel(@Nullable Project project) {
        this.project = project;
        rootPanel.add(createFormPanel(), BorderLayout.NORTH);
        rootPanel.add(createResponsePanel(), BorderLayout.CENTER);
        sendButton.addActionListener(event -> sendRequest());
    }

    public JComponent getComponent() {
        return rootPanel;
    }

    private JPanel createFormPanel() {
        JPanel formPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 8, 8, 8);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0;
        formPanel.add(new JLabel("URL:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1;
        urlField.setPreferredSize(new Dimension(320, 28));
        formPanel.add(urlField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0;
        formPanel.add(new JLabel("Cookie:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1;
        formPanel.add(cookieField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.weightx = 0;
        formPanel.add(new JLabel("Method:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1;
        formPanel.add(methodComboBox, gbc);

        gbc.gridx = 1;
        gbc.gridy = 3;
        gbc.weightx = 0;
        gbc.anchor = GridBagConstraints.EAST;
        gbc.fill = GridBagConstraints.NONE;
        formPanel.add(sendButton, gbc);

        return formPanel;
    }

    private JComponent createResponsePanel() {
        responseArea.setEditable(false);
        responseArea.setLineWrap(true);
        responseArea.setWrapStyleWord(true);
        responseArea.setText("Response will appear here.");
        return new JBScrollPane(responseArea);
    }

    private void sendRequest() {
        String url = urlField.getText().trim();
        String cookie = cookieField.getText().trim();
        String method = (String) methodComboBox.getSelectedItem();

        if (url.isEmpty()) {
            Messages.showErrorDialog(project, "Please enter a URL.", "Invalid Input");
            return;
        }

        String safeMethod = method == null ? "GET" : method;
        sendButton.setEnabled(false);
        responseArea.setText("Sending request...");

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Sending HTTP Request", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                try {
                    String response = executeRequest(url, cookie, safeMethod);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        responseArea.setText(response);
                        responseArea.setCaretPosition(0);
                        sendButton.setEnabled(true);
                    });
                } catch (Exception ex) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        responseArea.setText("Request failed:\n" + ex.getMessage());
                        responseArea.setCaretPosition(0);
                        sendButton.setEnabled(true);
                    });
                }
            }
        });
    }

    private String executeRequest(String url, String cookie, String method) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("Accept", "*/*");

        if (!cookie.isEmpty()) {
            connection.setRequestProperty("Cookie", cookie);
        }

        if ("POST".equalsIgnoreCase(method)) {
            connection.setDoOutput(true);
        }

        int responseCode = connection.getResponseCode();
        InputStream stream = responseCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String body = readStream(stream);

        return "HTTP " + responseCode + "\n\n" + body;
    }

    private String readStream(@Nullable InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
            return builder.toString();
        }
    }
}
