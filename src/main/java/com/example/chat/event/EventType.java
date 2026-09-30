package com.example.chat.event;

public enum EventType {
    SESSION_STARTED, JOINED, LEFT, MESSAGE_SENT, DISCONNECTED, RECONNECTED, SESSION_ENDED;

    public boolean affectsParticipant() {
        return this == JOINED || this == LEFT || this == DISCONNECTED || this == RECONNECTED;
    }
}
