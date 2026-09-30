package com.example.chat.event.dto;

/**
 * 이벤트 수집 결과.
 * duplicate = true 이면 이미 저장된 이벤트를 다시 받은 것(재전송)이고, event는 최초 저장 결과다.
 */
public record AppendResult(EventResponse event, boolean duplicate) {

    public static AppendResult created(EventResponse event) {
        return new AppendResult(event, false);
    }

    public static AppendResult duplicate(EventResponse event) {
        return new AppendResult(event, true);
    }
}
