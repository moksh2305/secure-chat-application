import java.io.*;
import java.net.*;
import java.util.*;

public class ChatServer {
    private static final int PORT = 5000;
    private static final int HISTORY_SIZE = 100;

    private static Set<ClientHandler> clients = Collections.synchronizedSet(new HashSet<>());
    private static Map<String, ClientHandler> userMap = Collections.synchronizedMap(new HashMap<>());
    private static LinkedList<String> messageHistory = new LinkedList<>();
    private static int messageIdCounter = 1;


    private static Map<Integer, Map<String, Set<String>>> reactionsMap = Collections.synchronizedMap(new HashMap<>());

    public static void main(String[] args) throws IOException {
        ServerSocket serverSocket = new ServerSocket(PORT);
        System.out.println("Server started on port " + PORT);

        while (true) {
            Socket socket = serverSocket.accept();
            ClientHandler handler = new ClientHandler(socket);
            new Thread(handler).start();
        }
    }

    static void broadcast(String message, String excludeUser) {
        synchronized (clients) {
            // Remove the exclusion so everyone gets the message
            for (ClientHandler client : clients) {
                client.sendMessage(message);
            }
        }
    }

    static void sendHistory(ClientHandler client) {
        for (String msg : messageHistory) {
            client.sendMessage(msg);
        }
        // Send all reactions for messages
        synchronized (reactionsMap) {
            for (var entry : reactionsMap.entrySet()) {
                int msgId = entry.getKey();
                Map<String, Set<String>> reactMap = entry.getValue();
                for (var reactEntry : reactMap.entrySet()) {
                    String emoji = reactEntry.getKey();
                    for (String user : reactEntry.getValue()) {
                        client.sendMessage("/react " + msgId + " " + emoji + " " + user);
                    }
                }
            }
        }
    }

    static void broadcastUserList() {
        StringBuilder userListMsg = new StringBuilder("/users ");
        synchronized (clients) {
            for (ClientHandler client : clients) {
                userListMsg.append(client.getUserName()).append(" ");
            }
        }
        String msg = userListMsg.toString().trim();
        for (ClientHandler client : clients) {
            client.sendMessage(msg);
        }
    }

    static class ClientHandler implements Runnable {
        private Socket socket;
        private PrintWriter out;
        private BufferedReader in;
        private String userName;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        public String getUserName() {
            return userName;
        }

        public void run() {
            try {
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out = new PrintWriter(socket.getOutputStream(), true);
                // First message from client is the username
                userName = in.readLine();
                if (userName == null || userName.trim().isEmpty() || userMap.containsKey(userName)) {
                    out.println("/error Username invalid or already taken.");
                    socket.close();
                    return;
                }
                userMap.put(userName, this);
                clients.add(this);

                System.out.println(userName + " joined the chat.");
                broadcast("/notify " + userName + " joined the chat.", userName);
                broadcastUserList();
                sendHistory(this);

                String message;
                while ((message = in.readLine()) != null) {
                    if (message.equalsIgnoreCase("/quit")) break;

                    // Typing indicator
                    if (message.startsWith("/typing ")) {
                        broadcast("/typing " + userName, userName); // exclude sender
                        continue;
                    }

                    // Private message
                    if (message.startsWith("/pm ")) {
                        String[] parts = message.split(" ", 3);
                        if (parts.length == 3) {
                            String target = parts[1];
                            String pm = parts[2];
                            ClientHandler recipient = userMap.get(target);
                            if (recipient != null) {
                                recipient.sendMessage("/pm " + userName + " " + pm);
                                sendMessage("/pm " + userName + " " + pm); // echo to sender
                            } else {
                                sendMessage("/notify User not found.");
                            }
                        }
                        continue;
                    }

                    // File sharing
                    if (message.startsWith("/file ")) {
                        broadcast(message, null);
                        continue;
                    }

                    // Message reaction
                    if (message.startsWith("/react ")) {
                        // Format: /react messageId emoji username
                        String[] parts = message.split(" ", 4);
                        if (parts.length == 4) {
                            int msgId = Integer.parseInt(parts[1]);
                            String emoji = parts[2];
                            String reactingUser = parts[3];
                            synchronized (reactionsMap) {
                                reactionsMap.putIfAbsent(msgId, new HashMap<>());
                                Map<String, Set<String>> reactMap = reactionsMap.get(msgId);
                                reactMap.putIfAbsent(emoji, new HashSet<>());
                                reactMap.get(emoji).add(reactingUser);
                            }
                            broadcast(message, null);
                        }
                        continue;
                    }

                    // Normal message (assign message id)
                    // Normal message (assign message id)
                    if (message.startsWith("/msg ")) {
                        int msgId = messageIdCounter++;
                        String fullMsg = "/msg " + msgId + " " + userName + " " + message.substring(5);

                        // Save in history
                        synchronized (messageHistory) {
                            messageHistory.add(fullMsg);
                            if (messageHistory.size() > HISTORY_SIZE) {
                                messageHistory.removeFirst();
                            }
                        }

                        // Broadcast message id and message
                        broadcast("/msgid " + msgId, null);
                        broadcast(fullMsg, null);
                        continue;
                    }

                    // Fallback: broadcast as-is (shouldn't happen)
                    broadcast(message, null);
                }
            } catch (IOException e) {
                System.out.println("Connection error: " + e.getMessage());
            } finally {
                try {
                    socket.close();
                } catch (IOException e) {}
                clients.remove(this);
                userMap.remove(userName);
                broadcast("/notify " + userName + " left the chat.", userName);
                broadcastUserList();
            }
        }

        void sendMessage(String message) {
            out.println(message);
        }
    }
}
