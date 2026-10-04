package com.example.chat.event;

/** 세션에서 일어나는 사실의 종류. SESSION_STARTED, DISCONNECTED, RECONNECTED는 서버만 기록한다. */
public enum EventType {
    SESSION_STARTED, JOINED, LEFT, MESSAGE_SENT, DISCONNECTED, RECONNECTED, SESSION_ENDED;

    public boolean affectsParticipant() {
        return this == JOINED || this == LEFT || this == DISCONNECTED || this == RECONNECTED;
    }
}
