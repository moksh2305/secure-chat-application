import javax.swing.*;
import javax.swing.text.*;
import javax.swing.Timer;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.Base64;

public class ChatClient extends JFrame {
    private JTextPane chatPane;
    private JTextField inputField;
    private JButton sendButton, fileButton, themeButton;
    private JList<String> userList;
    private DefaultListModel<String> userListModel;
    private PrintWriter out;
    private BufferedReader in;
    private String userName;
    private Map<String, Color> userColors = new HashMap<>();
    private Random rand = new Random();
    private SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");
    private JLabel typingLabel;
    private javax.swing.Timer typingTimer = null;

    // Message id -> (offset in doc)
    private Map<Integer, Integer> messageOffsets = new HashMap<>();
    // Message id -> reactions (emoji -> set of users)
    private Map<Integer, Map<String, Set<String>>> reactionsMap = new HashMap<>();
    // Used to assign message ids as they arrive
    private int nextMsgId = 1;
    // Used to map doc offset to message id
    private Map<Integer, Integer> offsetToMsgId = new HashMap<>();

    private static final Map<String, String> EMOJI_MAP = Map.of(
            ":smile:", "\uD83D\uDE04",
            ":heart:", "\u2764\uFE0F",
            ":thumbs_up:", "\uD83D\uDC4D",
            ":laugh:", "\uD83D\uDE02"
    );
    private static final List<String> REACTION_EMOJIS = List.of("\uD83D\uDC4D", "\u2764\uFE0F", "\uD83D\uDE02");

    // Theme
    private boolean darkMode = false;

    public ChatClient(String serverAddress, int port) {
        // Prompt for username
        userName = JOptionPane.showInputDialog(this, "Enter your username:", "Username", JOptionPane.PLAIN_MESSAGE);
        if (userName == null || userName.trim().isEmpty()) System.exit(0);

        setTitle("Java Chat - " + userName);
        setSize(800, 550);
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        chatPane = new JTextPane();
        chatPane.setEditable(false);
        chatPane.setFont(new Font(getEmojiFont(), Font.PLAIN, 16));
        JScrollPane chatScroll = new JScrollPane(chatPane);

        inputField = new JTextField();
        sendButton = new JButton("Send");
        fileButton = new JButton("Send File");
        themeButton = new JButton("Dark Mode");

        JPanel inputPanel = new JPanel(new BorderLayout());
        inputPanel.add(fileButton, BorderLayout.WEST);
        inputPanel.add(inputField, BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);

        JPanel topPanel = new JPanel(new BorderLayout());
        typingLabel = new JLabel(" ");
        typingLabel.setFont(new Font("SansSerif", Font.ITALIC, 12));
        typingLabel.setForeground(Color.GRAY);
        topPanel.add(typingLabel, BorderLayout.WEST);
        topPanel.add(themeButton, BorderLayout.EAST);

        userListModel = new DefaultListModel<>();
        userList = new JList<>(userListModel);
        userList.setPreferredSize(new Dimension(120, 0));
        userList.setBorder(BorderFactory.createTitledBorder("Users"));

        add(chatScroll, BorderLayout.CENTER);
        add(inputPanel, BorderLayout.SOUTH);
        add(userList, BorderLayout.EAST);
        add(topPanel, BorderLayout.NORTH);

        sendButton.addActionListener(e -> sendMessage());
        inputField.addActionListener(e -> sendMessage());

        // Typing indicator
        inputField.addKeyListener(new KeyAdapter() {
            public void keyTyped(KeyEvent e) {
                out.println("/typing " + userName);
            }
        });

        // File sharing
        fileButton.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                File file = chooser.getSelectedFile();
                try {
                    byte[] data = Files.readAllBytes(file.toPath());
                    String encoded = Base64.getEncoder().encodeToString(data);
                    out.println("/file " + file.getName() + " " + encoded);
                    appendSystemMessage("File sent: " + file.getName());
                } catch (IOException ex) {
                    appendSystemMessage("File send failed.");
                }
            }
        });

        // Theme toggle
        themeButton.addActionListener(e -> {
            darkMode = !darkMode;
            updateTheme();
        });

        // Message reactions: right-click on chatPane
        chatPane.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e) || e.getClickCount() == 2) {
                    int pos = chatPane.viewToModel2D(e.getPoint());
                    Integer msgId = getMsgIdForOffset(pos);
                    if (msgId != null) {
                        String emoji = (String) JOptionPane.showInputDialog(
                                ChatClient.this,
                                "React to message:",
                                "React",
                                JOptionPane.PLAIN_MESSAGE,
                                null,
                                REACTION_EMOJIS.toArray(),
                                REACTION_EMOJIS.get(0));
                        if (emoji != null && !emoji.isEmpty()) {
                            out.println("/react " + msgId + " " + emoji + " " + userName);
                        }
                    }
                }
            }
        });

        updateTheme();
        setVisible(true);

        try {
            Socket socket = new Socket(serverAddress, port);
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            // Send username to server
            out.println(userName);

            // Start thread to read messages
            new Thread(() -> {
                String msg;
                try {
                    while ((msg = in.readLine()) != null) {
                        handleServerMessage(msg);
                    }
                } catch (IOException ex) {
                    appendSystemMessage("Connection closed.");
                }
            }).start();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Unable to connect to server.", "Error", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }

    private void sendMessage() {
        String msg = inputField.getText().trim();
        if (!msg.isEmpty()) {
            if (msg.startsWith("/pm ") || msg.startsWith("/file ")) {
                out.println(msg);
            } else {
                out.println("/msg " + parseEmojis(msg));
            }
            inputField.setText("");
        }
    }

    private void handleServerMessage(String msg) {
        if (msg.startsWith("/msgid ")) {
            // Next message will have this id
            nextMsgId = Integer.parseInt(msg.substring(7));
        } else if (msg.startsWith("/msg ")) {
            // Format: /msg msgId username message
            String[] parts = msg.split(" ", 4);
            if (parts.length == 4) {
                int msgId = Integer.parseInt(parts[1]);
                String sender = parts[2];
                String message = parts[3];
                appendChatMessage(msgId, sender, message);
            }
        } else if (msg.startsWith("/notify ")) {
            appendSystemMessage(msg.substring(8));
        } else if (msg.startsWith("/users ")) {
            updateUserList(msg.substring(7).split(" "));
        } else if (msg.startsWith("/error ")) {
            JOptionPane.showMessageDialog(this, msg.substring(7), "Error", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        } else if (msg.startsWith("/pm ")) {
            int firstSpace = msg.indexOf(' ', 4);
            if (firstSpace > 0) {
                String sender = msg.substring(4, firstSpace);
                String message = msg.substring(firstSpace + 1);
                appendPrivateMessage(sender, message);
            }
        } else if (msg.startsWith("/file ")) {
            String[] parts = msg.split(" ", 3);
            if (parts.length == 3) {
                String filename = parts[1];
                byte[] data = Base64.getDecoder().decode(parts[2]);
                SwingUtilities.invokeLater(() -> {
                    int option = JOptionPane.showConfirmDialog(this,
                            "Receive file: " + filename + " (" + data.length + " bytes)?",
                            "File Received", JOptionPane.YES_NO_OPTION);
                    if (option == JOptionPane.YES_OPTION) {
                        try {
                            Files.write(Paths.get(filename), data);
                            appendSystemMessage("File saved: " + filename);
                        } catch (IOException ex) {
                            appendSystemMessage("Failed to save file.");
                        }
                    }
                });
            }
        } else if (msg.startsWith("/typing ")) {
            String typingUser = msg.substring(8);
            showTyping(typingUser);
        } else if (msg.startsWith("/react ")) {
            // Format: /react msgId emoji username
            String[] parts = msg.split(" ", 4);
            if (parts.length == 4) {
                int msgId = Integer.parseInt(parts[1]);
                String emoji = parts[2];
                String reactingUser = parts[3];
                addReaction(msgId, emoji, reactingUser);
            }
        }
    }

    private void appendChatMessage(int msgId, String sender, String message) {
        SwingUtilities.invokeLater(() -> {
            StyledDocument doc = chatPane.getStyledDocument();
            Style style = chatPane.addStyle("Style", null);

            // Timestamp
            String timestamp = "[" + timeFormat.format(new Date()) + "] ";
            StyleConstants.setForeground(style, darkMode ? Color.LIGHT_GRAY : Color.GRAY);
            int offset = doc.getLength();
            try {
                doc.insertString(doc.getLength(), timestamp, style);
            } catch (BadLocationException e) {}

            // Username in color
            Color color = userColors.computeIfAbsent(sender, k -> getRandomColor());
            StyleConstants.setForeground(style, color);
            StyleConstants.setBold(style, true);
            try {
                doc.insertString(doc.getLength(), sender, style);
            } catch (BadLocationException e) {}

            // Message with emojis
            StyleConstants.setForeground(style, darkMode ? Color.WHITE : Color.BLACK);
            StyleConstants.setBold(style, false);
            String parsedMsg = parseEmojis(message);
            try {
                doc.insertString(doc.getLength(), ": " + parsedMsg, style);
            } catch (BadLocationException e) {}

            // Reactions (if any)
            String reacts = getReactionsString(msgId);
            if (!reacts.isEmpty()) {
                StyleConstants.setForeground(style, Color.ORANGE);
                try {
                    doc.insertString(doc.getLength(), " " + reacts, style);
                } catch (BadLocationException e) {}
            }

            try {
                doc.insertString(doc.getLength(), "\n", style);
            } catch (BadLocationException e) {}

            // Map offset to msgId for reactions
            messageOffsets.put(msgId, offset);
            offsetToMsgId.put(offset, msgId);

            chatPane.setCaretPosition(doc.getLength());
        });
    }

    private void appendPrivateMessage(String sender, String message) {
        SwingUtilities.invokeLater(() -> {
            StyledDocument doc = chatPane.getStyledDocument();
            Style style = chatPane.addStyle("PM", null);
            StyleConstants.setForeground(style, Color.MAGENTA);
            StyleConstants.setBold(style, true);
            String timestamp = "[" + timeFormat.format(new Date()) + "] ";
            try {
                doc.insertString(doc.getLength(), timestamp, style);
                doc.insertString(doc.getLength(), "[PM] " + sender + ": " + parseEmojis(message) + "\n", style);
            } catch (BadLocationException e) {}
            chatPane.setCaretPosition(doc.getLength());
        });
    }

    private void appendSystemMessage(String message) {
        SwingUtilities.invokeLater(() -> {
            StyledDocument doc = chatPane.getStyledDocument();
            Style style = chatPane.addStyle("System", null);
            StyleConstants.setForeground(style, darkMode ? Color.CYAN : Color.BLUE);
            StyleConstants.setItalic(style, true);
            try {
                doc.insertString(doc.getLength(), "[System] " + message + "\n", style);
            } catch (BadLocationException e) {}
            chatPane.setCaretPosition(doc.getLength());
        });
    }

    private void updateUserList(String[] users) {
        SwingUtilities.invokeLater(() -> {
            userListModel.clear();
            for (String user : users) {
                if (!user.trim().isEmpty())
                    userListModel.addElement(user);
            }
        });
    }

    private Color getRandomColor() {
        float hue = rand.nextFloat();
        float saturation = 0.7f + rand.nextFloat() * 0.3f; // 0.7 - 1.0
        float brightness = 0.7f + rand.nextFloat() * 0.3f; // 0.7 - 1.0
        return Color.getHSBColor(hue, saturation, brightness);
    }

    private String parseEmojis(String message) {
        for (var entry : EMOJI_MAP.entrySet()) {
            message = message.replace(entry.getKey(), entry.getValue());
        }
        return message;
    }

    private void showTyping(String typingUser) {
        if (typingUser.equals(userName)) return;
        SwingUtilities.invokeLater(() -> {
            typingLabel.setText(typingUser + " is typing...");
            if (typingTimer != null) typingTimer.stop();
            typingTimer = new javax.swing.Timer(2000, e -> typingLabel.setText(" "));
            typingTimer.setRepeats(false);
            typingTimer.start();
        });
    }

    private String getEmojiFont() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return "Segoe UI Emoji";
        if (os.contains("mac")) return "Apple Color Emoji";
        return "Segoe UI Emoji";
    }

    private void updateTheme() {
        Color bg = darkMode ? new Color(30, 32, 34) : Color.WHITE;
        Color fg = darkMode ? Color.WHITE : Color.BLACK;
        chatPane.setBackground(bg);
        chatPane.setForeground(fg);
        inputField.setBackground(bg);
        inputField.setForeground(fg);
        userList.setBackground(bg);
        userList.setForeground(fg);
        typingLabel.setForeground(darkMode ? Color.LIGHT_GRAY : Color.GRAY);
        themeButton.setText(darkMode ? "Light Mode" : "Dark Mode");
        repaint();
    }

    // Message reactions
    private void addReaction(int msgId, String emoji, String reactingUser) {
        reactionsMap.putIfAbsent(msgId, new HashMap<>());
        Map<String, Set<String>> reactMap = reactionsMap.get(msgId);
        reactMap.putIfAbsent(emoji, new HashSet<>());
        reactMap.get(emoji).add(reactingUser);
        updateReactionsDisplay(msgId);
    }

    private void updateReactionsDisplay(int msgId) {
        // Redraw the message line with updated reactions
        Integer offset = messageOffsets.get(msgId);
        if (offset == null) return;
        SwingUtilities.invokeLater(() -> {
            try {
                StyledDocument doc = chatPane.getStyledDocument();
                int lineEnd = doc.getText(offset, doc.getLength() - offset).indexOf('\n');
                if (lineEnd == -1) lineEnd = doc.getLength() - offset;
                int reactionPos = offset;
                // Find the position after the message text (before \n)
                String line = doc.getText(offset, lineEnd);
                int insertAt = offset + line.length();
                // Remove old reactions (if any)
                doc.remove(insertAt, 0); // No-op, but could be used to remove old reactions if needed
                // Insert new reactions
                String reacts = getReactionsString(msgId);
                if (!reacts.isEmpty()) {
                    Style style = chatPane.addStyle("React", null);
                    StyleConstants.setForeground(style, Color.ORANGE);
                    doc.insertString(insertAt, " " + reacts, style);
                }
            } catch (Exception e) { /* ignore */ }
        });
    }

    private String getReactionsString(int msgId) {
        Map<String, Set<String>> reactMap = reactionsMap.get(msgId);
        if (reactMap == null) return "";
        StringBuilder sb = new StringBuilder();
        for (var entry : reactMap.entrySet()) {
            sb.append(entry.getKey()).append("×").append(entry.getValue().size()).append(" ");
        }
        return sb.toString().trim();
    }

    private Integer getMsgIdForOffset(int pos) {
        // Find the closest offset <= pos
        Integer best = null;
        for (var entry : offsetToMsgId.entrySet()) {
            if (entry.getKey() <= pos && (best == null || entry.getKey() > best)) {
                best = entry.getKey();
            }
        }
        return best == null ? null : offsetToMsgId.get(best);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ChatClient("localhost", 5000));
    }
}
