package com.chat;

import jakarta.websocket.*;
import jakarta.websocket.server.*;
import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@ServerEndpoint("/chat/{username}/{room}")
public class SimpleChatEndpoint {
    
    // FIX: Use ConcurrentHashMap for thread safety
    private static final Map<String, Set<Session>> roomSessions = new ConcurrentHashMap<>();
    
    // Database config
    private static final String DB_URL = "jdbc:mysql://localhost:3306/chatapp";
    private static final String DB_USER = "root";
    private static final String DB_PASSWORD = "your_password";
    
    @OnOpen
    public void onOpen(Session session, 
                      @PathParam("username") String username,
                      @PathParam("room") String room) throws IOException {
        
        System.out.println("✅ " + username + " joined room: " + room);
        
        // Store user info
        session.getUserProperties().put("username", username);
        session.getUserProperties().put("room", room);
        
        // FIX: Use synchronized block for thread safety
        synchronized (roomSessions) {
            roomSessions.computeIfAbsent(room, k -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
                       .add(session);
        }
        
        System.out.println("📊 Room " + room + " now has " + roomSessions.get(room).size() + " users");
        
        // Save user to DB
        saveUserToDB(username);
        
        // Load chat history
        loadChatHistory(session, room);
        
        // Send welcome
        String welcome = String.format(
            "{\"type\":\"system\",\"message\":\"Welcome %s to %s!\"}",
            username, room
        );
        session.getBasicRemote().sendText(welcome);
        
        // FIX: Broadcast join message to ALL users in room
        String joinMsg = String.format(
            "{\"type\":\"join\",\"user\":\"%s\"}",
            username
        );
        broadcastToRoom(room, joinMsg);
        
        // Send updated user list
        sendUserList(room);
    }
    
    @OnMessage
    public void onMessage(String message, Session session) throws IOException {
        String username = (String) session.getUserProperties().get("username");
        String room = (String) session.getUserProperties().get("room");
        
        if (username != null && room != null) {
            System.out.println("📨 Message from " + username + " in " + room + ": " + message);
            
            // Save to database
            saveMessageToDB(username, room, message);
            
            // Create JSON message
            String jsonMsg = String.format(
                "{\"type\":\"message\",\"user\":\"%s\",\"message\":\"%s\",\"time\":\"%d\"}",
                username,
                escapeJson(message),
                System.currentTimeMillis()
            );
            
            // FIX: Broadcast to ALL users in room (including sender)
            broadcastToRoom(room, jsonMsg);
            
            System.out.println("📤 Broadcasted to " + getRoomUserCount(room) + " users in " + room);
        }
    }
    
    @OnClose
    public void onClose(Session session) {
        String username = (String) session.getUserProperties().get("username");
        String room = (String) session.getUserProperties().get("room");
        
        if (username != null && room != null) {
            System.out.println("❌ " + username + " left room: " + room);
            
            // Remove session from room
            synchronized (roomSessions) {
                Set<Session> sessions = roomSessions.get(room);
                if (sessions != null) {
                    sessions.remove(session);
                    if (sessions.isEmpty()) {
                        roomSessions.remove(room);
                    }
                }
            }
            
            // Broadcast leave message
            try {
                String leaveMsg = String.format("{\"type\":\"leave\",\"user\":\"%s\"}", username);
                broadcastToRoom(room, leaveMsg);
                sendUserList(room);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
    
    @OnError
    public void onError(Session session, Throwable throwable) {
        System.err.println("WebSocket Error: " + throwable.getMessage());
        throwable.printStackTrace();
    }
    
    // FIX: IMPROVED BROADCAST METHOD
    private void broadcastToRoom(String room, String message) throws IOException {
        Set<Session> sessions = roomSessions.get(room);
        
        if (sessions != null && !sessions.isEmpty()) {
            System.out.println("📡 Broadcasting to " + sessions.size() + " sessions in room: " + room);
            
            List<Session> closedSessions = new ArrayList<>();
            
            for (Session session : sessions) {
                if (session.isOpen()) {
                    try {
                        synchronized (session) {
                            session.getBasicRemote().sendText(message);
                        }
                        System.out.println("   ✅ Sent to session: " + session.getId());
                    } catch (IOException e) {
                        System.err.println("   ❌ Failed to send to session " + session.getId() + ": " + e.getMessage());
                        closedSessions.add(session);
                    }
                } else {
                    closedSessions.add(session);
                }
            }
            
            // Remove closed sessions
            if (!closedSessions.isEmpty()) {
                sessions.removeAll(closedSessions);
                System.out.println("🗑️ Removed " + closedSessions.size() + " closed sessions");
            }
        } else {
            System.out.println("⚠️ No sessions in room: " + room);
        }
    }
    
    private void sendUserList(String room) throws IOException {
        Set<Session> sessions = roomSessions.get(room);
        if (sessions != null && !sessions.isEmpty()) {
            List<String> users = new ArrayList<>();
            for (Session s : sessions) {
                String user = (String) s.getUserProperties().get("username");
                if (user != null) {
                    users.add(user);
                }
            }
            
            String userListJson = String.format(
                "{\"type\":\"users\",\"users\":\"%s\"}",
                String.join(", ", users)
            );
            
            broadcastToRoom(room, userListJson);
        }
    }
    
    private int getRoomUserCount(String room) {
        Set<Session> sessions = roomSessions.get(room);
        return sessions != null ? sessions.size() : 0;
    }
    
    // ============ DATABASE METHODS ============
    
    private void saveUserToDB(String username) {
        // Your existing database code
    }
    
    private void saveMessageToDB(String username, String room, String message) {
        // Your existing database code
    }
    
    private void loadChatHistory(Session session, String room) {
        // Your existing database code
    }
    
    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
}