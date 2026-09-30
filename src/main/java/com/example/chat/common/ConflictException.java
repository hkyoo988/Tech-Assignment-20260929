package com.example.chat.common;

/**
 * 요청이 현재 상태와 충돌할 때 (409).
 * 예: 같은 clientEventId인데 내용이 다른 이벤트
 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
